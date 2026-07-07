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
"""Remote (real-Flink MiniCluster) e2e tests for MCP integration.

These tests exercise the MCP ``add`` tool and ``ask_sum`` prompt directly from
an agent action -- no LLM is involved. Each MCP server is self-started as a
subprocess and the test is skipped only if that server never becomes reachable.
"""

import json
import multiprocessing
import os
import runpy
import socket
import sysconfig
import time
from pathlib import Path
from typing import Type

import pytest
from pyflink.common import Configuration, Encoder, WatermarkStrategy
from pyflink.common.typeinfo import Types
from pyflink.datastream import RuntimeExecutionMode, StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import (
    FileSource,
    StreamFormat,
    StreamingFileSink,
)

from flink_agents.api.agents.agent import Agent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.e2e_tests.e2e_tests_integration.mcp_remote_agent import (
    MCPCalcInput,
    MCPCalcKeySelector,
    MCPRemoteWithoutPromptsAgent,
    MCPRemoteWithPromptsAgent,
)
from flink_agents.e2e_tests.test_utils import check_result

current_dir = Path(__file__).parent
mcp_server_dir = current_dir / "e2e_tests_mcp"

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]


def _run_mcp_server(server_file: str) -> None:
    """Run an MCP server script in a dedicated process."""
    runpy.run_path(str(mcp_server_dir / server_file))


def _wait_for_port(port: int, timeout: float = 20.0) -> bool:
    """Poll a local TCP port until it accepts a connection or the timeout lapses."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.settimeout(1.0)
            if sock.connect_ex(("127.0.0.1", port)) == 0:
                return True
        time.sleep(0.5)
    return False


def _run_mcp_agent(
    agent: Agent,
    ground_truth_file: str,
    result_dir: Path,
) -> None:
    """Run an MCP agent on a real Flink MiniCluster and verify its output."""
    config = Configuration()
    config.set_string("state.backend.type", "rocksdb")
    config.set_string("checkpointing.interval", "1s")
    config.set_string("restart-strategy.type", "disable")
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)

    input_datastream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(),
            f"file:///{current_dir}/../resources/mcp_remote_input",
        ).build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="mcp_remote_source",
    )

    deserialize_datastream = input_datastream.map(
        lambda x: MCPCalcInput.model_validate_json(x)
    )

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output_datastream = (
        agents_env.from_datastream(
            input=deserialize_datastream, key_selector=MCPCalcKeySelector()
        )
        .apply(agent)
        .to_datastream()
    )

    output_datastream.map(lambda x: json.dumps(x), Types.STRING()).add_sink(
        StreamingFileSink.for_row_format(
            base_path=str(result_dir.absolute()),
            encoder=Encoder.simple_string_encoder(),
        ).build()
    )

    agents_env.execute()

    check_result(
        result_dir=result_dir,
        ground_truth_dir=Path(
            f"{current_dir}/../resources/ground_truth/{ground_truth_file}"
        ),
    )


@pytest.mark.parametrize(
    ("agent_cls", "server_file", "port", "ground_truth_file"),
    [
        (
            MCPRemoteWithPromptsAgent,
            "mcp_server.py",
            8000,
            "test_mcp_remote_with_prompts.txt",
        ),
        (
            MCPRemoteWithoutPromptsAgent,
            "mcp_server_without_prompts.py",
            8001,
            "test_mcp_remote_without_prompts.txt",
        ),
    ],
)
def test_mcp_remote(
    agent_cls: Type[Agent],
    server_file: str,
    port: int,
    ground_truth_file: str,
    tmp_path: Path,
) -> None:
    """Round-trip the MCP add tool (and ask_sum prompt) through a MiniCluster."""
    server_process = multiprocessing.Process(
        target=_run_mcp_server, args=(server_file,)
    )
    server_process.start()
    try:
        if not _wait_for_port(port):
            pytest.skip(f"MCP server {server_file} not reachable on port {port}")

        result_dir = tmp_path / "results"
        result_dir.mkdir(parents=True, exist_ok=True)

        _run_mcp_agent(agent_cls(), ground_truth_file, result_dir)
    finally:
        server_process.kill()
        server_process.join()
