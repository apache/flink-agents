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
#  limitations under the License.
################################################################################
"""Example demonstrating MCP (Model Context Protocol) integration with Flink Agents.

This example shows how to:
1. Define an MCP server connection
2. Use MCP prompts in chat model setups
3. Use MCP tools in actions
4. Use MCP tools without prompts

Prerequisites:
- Run the MCP server first: mcp_server.py or mcp_server_without_prompts.py
"""

import os
import subprocess
import sys
import sysconfig
import time
from pathlib import Path

import pytest
from pydantic import BaseModel
from pyflink.common import Configuration, Encoder, WatermarkStrategy
from pyflink.common.typeinfo import Types
from pyflink.datastream import RuntimeExecutionMode, StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import (
    FileSource,
    StreamFormat,
    StreamingFileSink,
)

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.decorators import (
    action,
    chat_model_connection,
    chat_model_setup,
    mcp_server,
)
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import (
    ResourceDescriptor,
    ResourceName,
)
from flink_agents.api.runner_context import RunnerContext
from flink_agents.e2e_tests.test_utils import pull_model

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]

OLLAMA_MODEL = os.environ.get("MCP_OLLAMA_CHAT_MODEL", "qwen3:1.7b")
MCP_SERVER_ENDPOINT = "http://127.0.0.1:8000/mcp"
MCP_SERVER_ENDPOINT_WITHOUT_PROMPTS = "http://127.0.0.1:8001/mcp"


class CalculationInput(BaseModel):
    """Input for calculation requests."""

    a: int
    b: int


class MyMCPAgent(Agent):
    """Example agent demonstrating MCP prompts and tools integration."""

    @mcp_server
    @staticmethod
    def my_mcp_server() -> ResourceDescriptor:
        """Define MCP server connection based on MCP_SERVER_MODE env variable."""
        mcp_mode = os.environ.get("MCP_SERVER_MODE", "with_prompts")
        if mcp_mode == "without_prompts":
            endpoint = MCP_SERVER_ENDPOINT_WITHOUT_PROMPTS
        else:
            endpoint = MCP_SERVER_ENDPOINT
        return ResourceDescriptor(clazz=ResourceName.MCP_SERVER, endpoint=endpoint)

    @chat_model_connection
    @staticmethod
    def ollama_connection() -> ResourceDescriptor:
        """ChatModelConnection for Ollama."""
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_CONNECTION, request_timeout=240.0
        )

    @chat_model_setup
    @staticmethod
    def math_chat_model() -> ResourceDescriptor:
        """ChatModel using MCP prompt and tool (or just tool if without prompts)."""
        mcp_mode = os.environ.get("MCP_SERVER_MODE", "with_prompts")
        descriptor_kwargs = {
            "clazz": ResourceName.ChatModel.OLLAMA_SETUP,
            "connection": "ollama_connection",
            "model": OLLAMA_MODEL,
            "tools": ["add"],  # MCP tool registered from my_mcp_server
        }
        # Only add prompt if using server with prompts
        if mcp_mode == "with_prompts":
            descriptor_kwargs["prompt"] = (
                "ask_sum"  # MCP prompt registered from my_mcp_server
            )
        return ResourceDescriptor(**descriptor_kwargs)

    @action(EventType.InputEvent)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        """Process input and send chat request.

        Uses MCP prompt if MCP_SERVER_MODE is "with_prompts",
        otherwise sends direct content message.
        """
        input_data = CalculationInput.model_validate(InputEvent.from_event(event).input)
        mcp_mode = os.environ.get("MCP_SERVER_MODE", "with_prompts")

        if mcp_mode == "with_prompts":
            # Send chat request with MCP prompt variables
            # The prompt template will be filled with a and b values
            msg = ChatMessage(role=MessageRole.USER)
            ctx.send_event(
                ChatRequestEvent(
                    model="math_chat_model",
                    messages=[msg],
                    prompt_args={"a": str(input_data.a), "b": str(input_data.b)},
                )
            )
        else:
            # Send chat request asking to use the add tool
            msg = ChatMessage(
                role=MessageRole.USER,
                content=f"Please use the add tool to calculate the sum of {input_data.a} and {input_data.b}.",
            )
            ctx.send_event(ChatRequestEvent(model="math_chat_model", messages=[msg]))

    @action(EventType.ChatResponseEvent)
    @staticmethod
    def process_chat_response(event: Event, ctx: RunnerContext) -> None:
        """Process chat response and output result."""
        response = ChatResponseEvent.from_event(event).response
        if response and response.content:
            ctx.send_event(OutputEvent(output=response.content))


current_dir = Path(__file__).parent

client = pull_model(OLLAMA_MODEL)


@pytest.mark.parametrize(
    ("mcp_server_mode", "server_file", "server_endpoint"),
    [
        ("with_prompts", "mcp_server.py", MCP_SERVER_ENDPOINT),
        (
            "without_prompts",
            "mcp_server_without_prompts.py",
            MCP_SERVER_ENDPOINT_WITHOUT_PROMPTS,
        ),
    ],
)
@pytest.mark.skipif(
    client is None, reason="Ollama client is not available or test model is missing"
)
def test_mcp(
    mcp_server_mode: str,
    server_file: str,
    server_endpoint: str,
    tmp_path: Path,
) -> None:
    """Test MCP integration with different server modes on a MiniCluster.

    Drives ``MyMCPAgent`` (which binds the MCP ``add`` tool and ``ask_sum``
    prompt into an Ollama chat model setup) through a real
    ``StreamExecutionEnvironment`` with a FileSource and file sink. LLM output
    is non-deterministic, so the assertion counts output records rather than
    matching content.

    Args:
        mcp_server_mode: "with_prompts" or "without_prompts"
        server_file: Name of the MCP server file to run
        server_endpoint: Endpoint URL of the MCP server
        tmp_path: pytest fixture providing the sink output directory
    """
    # Start MCP server in background
    print(f"Starting MCP server: {server_file}...")
    server_process = subprocess.Popen([sys.executable, str(current_dir / server_file)])
    try:
        time.sleep(5)

        # Set environment variable to control agent behavior
        os.environ["MCP_SERVER_MODE"] = mcp_server_mode

        print(f"\nRunning MyMCPAgent with Ollama model: {OLLAMA_MODEL}")
        print(f"MCP server mode: {mcp_server_mode}")
        print(f"MCP server endpoint: {server_endpoint}\n")

        config = Configuration()
        config.set_string("state.backend.type", "rocksdb")
        config.set_string("checkpointing.interval", "1s")
        config.set_string("restart-strategy.type", "disable")
        env = StreamExecutionEnvironment.get_execution_environment(config)
        env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
        env.set_parallelism(1)

        # currently, bounded source is not supported due to runtime
        # implementation, so we use a continuous file source here.
        input_datastream = env.from_source(
            source=FileSource.for_record_stream_format(
                StreamFormat.text_line_format(),
                f"file:///{current_dir}/../../resources/mcp_input",
            ).build(),
            watermark_strategy=WatermarkStrategy.no_watermarks(),
            source_name="mcp_source",
        )

        deserialize_datastream = input_datastream.map(
            lambda x: CalculationInput.model_validate_json(x)
        )

        agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
        output_datastream = (
            agents_env.from_datastream(
                input=deserialize_datastream, key_selector=lambda x: str(x.a)
            )
            .apply(MyMCPAgent())
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

        records = [line for line in actual_result if line.strip()]
        assert len(records) == 2, f"expected 2 output records, got {records!r}"
    finally:
        server_process.terminate()
        try:
            server_process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            server_process.kill()
            server_process.wait()
