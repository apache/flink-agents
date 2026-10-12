################################################################################
#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
# limitations under the License.
#################################################################################
"""Runs MultimodalChatAgent on Flink with a real image and an Ollama vision model.

Mirrors the Java MultimodalChatIntegrationTest. Skipped when the Ollama client
or the vision model is not available.
"""

import base64
import json
import os
import struct
import sysconfig
import zlib
from pathlib import Path

import pytest
from pyflink.common import Row
from pyflink.common.typeinfo import BasicTypeInfo, ExternalTypeInfo, RowTypeInfo
from pyflink.datastream import KeySelector, StreamExecutionEnvironment
from pyflink.table import DataTypes, Schema, StreamTableEnvironment, TableDescriptor

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.e2e_tests.e2e_tests_integration.multimodal_chat_agent import (
    VISION_MODEL,
    MultimodalChatAgent,
)
from flink_agents.e2e_tests.test_utils import pull_model

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]

client = pull_model(VISION_MODEL)


class ImageKeySelector(KeySelector):
    """Keys every row the same way; there is one image."""

    def get_key(self, value: Row) -> str:
        """Return a constant key."""
        return "image"


def _red_square_png() -> str:
    """A 64x64 red PNG; Qwen vision processors reject sides under 32 pixels."""
    rows = b"".join(b"\x00" + b"\xff\x00\x00" * 64 for _ in range(64))

    def chunk(kind: bytes, data: bytes) -> bytes:
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", 64, 64, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(rows))
        + chunk(b"IEND", b"")
    )
    return base64.b64encode(png).decode()


@pytest.mark.skipif(
    client is None, reason="Ollama client is not available or vision model is missing"
)
def test_image_reaches_the_model_through_the_agent(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The image in the input row reaches the vision model and it answers."""
    monkeypatch.setenv("OLLAMA_VISION_MODEL", VISION_MODEL)
    stream_env = StreamExecutionEnvironment.get_execution_environment()
    stream_env.set_parallelism(1)
    t_env = StreamTableEnvironment.create(stream_execution_environment=stream_env)
    table = t_env.from_elements(
        elements=[(_red_square_png(),)],
        schema=DataTypes.ROW([DataTypes.FIELD("image", DataTypes.STRING())]),
    )
    env = AgentsExecutionEnvironment.get_execution_environment(
        env=stream_env, t_env=t_env
    )

    output_type_info = RowTypeInfo([BasicTypeInfo.STRING_TYPE_INFO()], ["answer"])
    schema = Schema.new_builder().column("answer", DataTypes.STRING()).build()
    output_table = (
        env.from_table(input=table, key_selector=ImageKeySelector())
        .apply(MultimodalChatAgent())
        .to_table(schema=schema, output_type=ExternalTypeInfo(output_type_info))
    )

    result_dir = tmp_path / "results"
    result_dir.mkdir(parents=True, exist_ok=True)
    t_env.create_temporary_table(
        "sink",
        TableDescriptor.for_connector("filesystem")
        .option("path", str(result_dir.absolute()))
        .format("json")
        .schema(schema)
        .build(),
    )
    output_table.execute_insert("sink").wait()

    answers = []
    for file in result_dir.iterdir():
        if file.is_file():
            with file.open() as f:
                answers.extend(json.loads(line)["answer"] for line in f if line.strip())
    # The model's wording varies; the point is that the image reached it and it
    # answered.
    assert len(answers) == 1, answers
    assert answers[0].strip()
