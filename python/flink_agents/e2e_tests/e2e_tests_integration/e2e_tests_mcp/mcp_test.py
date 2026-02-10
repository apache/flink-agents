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

import multiprocessing
import os
import runpy
import time
from pathlib import Path

import pytest
from pydantic import BaseModel

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.decorators import (
    action,
    chat_model_connection,
    chat_model_setup,
    mcp_server,
)
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import InputEvent, OutputEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import (
    ResourceDescriptor,
    ResourceName,
)
from flink_agents.api.runner_context import RunnerContext
from flink_agents.e2e_tests.test_utils import pull_model

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
            descriptor_kwargs["prompt"] = "ask_sum"  # MCP prompt registered from my_mcp_server
        return ResourceDescriptor(**descriptor_kwargs)

    @action(InputEvent)
    @staticmethod
    def process_input(event: InputEvent, ctx: RunnerContext) -> None:
        """Process input and send chat request.

        Uses MCP prompt if MCP_SERVER_MODE is "with_prompts",
        otherwise sends direct content message.
        """
        input_data: CalculationInput = event.input
        mcp_mode = os.environ.get("MCP_SERVER_MODE", "with_prompts")

        if mcp_mode == "with_prompts":
            # Send chat request with MCP prompt variables
            # The prompt template will be filled with a and b values
            msg = ChatMessage(
                role=MessageRole.USER,
                extra_args={"a": str(input_data.a), "b": str(input_data.b)},
            )
        else:
            # Send chat request asking to use the add tool
            msg = ChatMessage(
                role=MessageRole.USER,
                content=f"Please use the add tool to calculate the sum of {input_data.a} and {input_data.b}.",
            )

        ctx.send_event(ChatRequestEvent(model="math_chat_model", messages=[msg]))

    @action(ChatResponseEvent)
    @staticmethod
    def process_chat_response(event: ChatResponseEvent, ctx: RunnerContext) -> None:
        """Process chat response and output result."""
        response = event.response
        if response and response.content:
            ctx.send_event(OutputEvent(output=response.content))


def run_mcp_server(server_file: str) -> None:
    """Run the MCP server in a separate process."""
    runpy.run_path(f"{current_dir}/{server_file}")


current_dir = Path(__file__).parent

client = pull_model(OLLAMA_MODEL)


@pytest.mark.parametrize(
    ("mcp_server_mode", "server_file", "server_endpoint"),
    [
        ("with_prompts", "mcp_server.py", MCP_SERVER_ENDPOINT),
        ("without_prompts", "mcp_server_without_prompts.py", MCP_SERVER_ENDPOINT_WITHOUT_PROMPTS),
    ],
)
@pytest.mark.skipif(
    client is None, reason="Ollama client is not available or test model is missing"
)
def test_mcp(mcp_server_mode: str, server_file: str, server_endpoint: str) -> None:
    """Test MCP integration with different server modes.

    Args:
        mcp_server_mode: "with_prompts" or "without_prompts"
        server_file: Name of the MCP server file to run
        server_endpoint: Endpoint URL of the MCP server
    """
    # Start MCP server in background
    print(f"Starting MCP server: {server_file}...")
    server_process = multiprocessing.Process(target=run_mcp_server, args=(server_file,))
    server_process.start()
    time.sleep(5)

    # Set environment variable to control agent behavior
    os.environ["MCP_SERVER_MODE"] = mcp_server_mode

    print(f"\nRunning MyMCPAgent with Ollama model: {OLLAMA_MODEL}")
    print(f"MCP server mode: {mcp_server_mode}")
    print(f"MCP server endpoint: {server_endpoint}\n")

    env = AgentsExecutionEnvironment.get_execution_environment()
    input_list = []
    agent = MyMCPAgent()

    output_list = env.from_list(input_list).apply(agent).to_list()

    # Add test inputs
    input_list.append({"key": "calc1", "value": CalculationInput(a=1, b=2)})
    input_list.append({"key": "calc2", "value": CalculationInput(a=12, b=34)})

    env.execute()

    print("Results:")
    for output in output_list:
        for key, value in output.items():
            print(f"{key}: {value}")
    assert len(output_list) == 2
    server_process.kill()
