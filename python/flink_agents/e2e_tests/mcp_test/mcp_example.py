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

Prerequisites:
- Run the MCP server first: mcp_server.py
"""
import multiprocessing
import os
import runpy
import time
from pathlib import Path

from pydantic import BaseModel

from flink_agents.api.agent import Agent
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
from flink_agents.api.resource import ResourceDescriptor
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.tools.mcp import MCPServer
from flink_agents.integrations.chat_models.ollama_chat_model import (
    OllamaChatModelConnection,
    OllamaChatModelSetup,
)

OLLAMA_MODEL = os.environ.get("OLLAMA_CHAT_MODEL", "qwen3:8b")
MCP_SERVER_ENDPOINT = "http://127.0.0.1:8000/mcp"


class CalculationInput(BaseModel):
    """Input for calculation requests."""

    a: int
    b: int


class MyMCPAgent(Agent):
    """Example agent demonstrating MCP prompts and tools integration."""

    @mcp_server
    @staticmethod
    def my_mcp_server() -> MCPServer:
        """Define MCP server connection."""
        return MCPServer(endpoint=MCP_SERVER_ENDPOINT)

    @chat_model_connection
    @staticmethod
    def ollama_connection() -> ResourceDescriptor:
        """ChatModelConnection for Ollama."""
        return ResourceDescriptor(clazz=OllamaChatModelConnection)

    @chat_model_setup
    @staticmethod
    def math_chat_model() -> ResourceDescriptor:
        """ChatModel using MCP prompt and tool."""
        return ResourceDescriptor(
            clazz=OllamaChatModelSetup,
            connection="ollama_connection",
            model=OLLAMA_MODEL,
            prompt="ask_sum",  # MCP prompt registered from my_mcp_server
            tools=["add"],  # MCP tool registered from my_mcp_server
            extract_reasoning=True,
        )

    @action(InputEvent)
    @staticmethod
    def process_input(event: InputEvent, ctx: RunnerContext) -> None:
        """Process input and send chat request using MCP prompt.

        The MCP prompt "ask_sum" accepts parameters {a} and {b}.
        """
        input_data: CalculationInput = event.input

        # Send chat request with MCP prompt variables
        # The prompt template will be filled with a and b values
        msg = ChatMessage(
            role=MessageRole.USER,
            extra_args={"a": str(input_data.a), "b": str(input_data.b)},
        )

        ctx.send_event(ChatRequestEvent(model="math_chat_model", messages=[msg]))

    @action(ChatResponseEvent)
    @staticmethod
    def process_chat_response(event: ChatResponseEvent, ctx: RunnerContext) -> None:
        """Process chat response and output result."""
        response = event.response
        if response and response.content:
            ctx.send_event(OutputEvent(output=response.content))


def run_mcp_server() -> None:
    """Run the MCP server in a separate process."""
    runpy.run_path(f"{current_dir}/mcp_server.py")


current_dir = Path(__file__).parent

if __name__ == "__main__":
    # Start MCP server in background
    print("Starting MCP server...")
    server_process = multiprocessing.Process(target=run_mcp_server)
    server_process.start()
    time.sleep(5)

    print(f"\nRunning MyMCPAgent with Ollama model: {OLLAMA_MODEL}")
    print(f"MCP server endpoint: {MCP_SERVER_ENDPOINT}\n")

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

    server_process.kill()
