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
"""E2E test: Python-host agent whose orchestration actions are Java.

Companion to ``yaml_cross_language_test.py`` (Python actions + Java tool).
Here ``process_input`` / ``process_chat_response`` are ``type: java`` and
dispatch to ``YamlCrossLanguageActions``; the Python-native built-in
``chat_model_action`` bridges the chat loop, and the math path calls the
cross-language Java ``calculateBMI`` tool. Exercises a Java user action →
Python built-in → Java user action round trip.

Skipped when the Ollama client/model or the cross-language test-jar is
unavailable.
"""

import os
import sysconfig
from pathlib import Path

import pytest
from pyflink.common import Configuration, Encoder, WatermarkStrategy
from pyflink.common.typeinfo import Types
from pyflink.datastream import RuntimeExecutionMode, StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import (
    FileSource,
    StreamFormat,
    StreamingFileSink,
)

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.e2e_tests.test_utils import (
    assert_tool_invoked,
    collect_tool_invocations,
    pull_model,
)

current_dir = Path(__file__).parent
_RESOURCES = current_dir.parent / "resources"
_REPO_ROOT = current_dir.parent.parent.parent.parent
_TEST_JAR = (
    _REPO_ROOT
    / "e2e-test"
    / "flink-agents-end-to-end-tests-resource-cross-language"
    / "target"
    / "flink-agents-end-to-end-tests-resource-cross-language-0.3-SNAPSHOT-tests.jar"
)

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]

OLLAMA_MODEL = os.environ.get("OLLAMA_CHAT_MODEL", "qwen3:1.7b")
_client = pull_model(OLLAMA_MODEL)


@pytest.mark.skipif(
    _client is None,
    reason="Ollama client is not available or test model is missing.",
)
@pytest.mark.skipif(
    not _TEST_JAR.is_file(),
    reason=(
        "Cross-language test-jar is missing; run "
        "'mvn package -DskipTests -pl e2e-test/"
        "flink-agents-end-to-end-tests-resource-cross-language' first."
    ),
)
def test_yaml_python_host_with_java_actions(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """``load_yaml`` agent whose process_* actions are Java, bridged by Python."""
    monkeypatch.setenv("OLLAMA_CHAT_MODEL", OLLAMA_MODEL)
    config = Configuration()
    config.set_string("python.pythonpath", sysconfig.get_paths()["purelib"])
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)
    env.add_jars(f"file://{_TEST_JAR}")

    input_datastream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(),
            f"file:///{_RESOURCES}/yaml_cross_language_input",
        ).build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="yaml_actions_in_java_source",
    )
    deserialize_datastream = input_datastream.map(lambda x: str(x))

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    log_dir = tmp_path / "event_logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    agents_env.get_config().set_str("baseLogDir", str(log_dir))
    agents_env.load_yaml(_RESOURCES / "yaml_cross_language_actions_in_java.yaml")

    output_datastream = (
        agents_env.from_datastream(
            input=deserialize_datastream, key_selector=lambda x: "orderKey"
        )
        .apply("yaml_actions_in_java_agent")
        .to_datastream()
    )

    result_dir = tmp_path / "results"
    result_dir.mkdir(parents=True, exist_ok=True)
    output_datastream.map(
        lambda x: str(x).replace("\n", "").replace("\r", ""), Types.STRING()
    ).add_sink(
        StreamingFileSink.for_row_format(
            base_path=str(result_dir.absolute()),
            encoder=Encoder.simple_string_encoder(),
        ).build()
    )

    agents_env.execute()

    actual_result = []
    for file in result_dir.iterdir():
        if file.is_dir():
            for child in file.iterdir():
                with child.open() as f:
                    actual_result.extend(f.readlines())
        if file.is_file():
            with file.open() as f:
                actual_result.extend(f.readlines())

    # Math path went through the Java calculateBMI tool — cross-language tool.
    assert_tool_invoked(
        collect_tool_invocations(log_dir),
        "calculateBMI",
        {"weightKg": 70, "heightM": 1.75},
    )
    # Creative path uses no tool; its answer mentions a cat.
    joined = "\n".join(actual_result).lower()
    assert "cat" in joined, f"creative answer missing 'cat': {actual_result!r}"
