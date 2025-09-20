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
import multiprocessing
import runpy
import time
from pathlib import Path

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.tools.mcp import MCPServer


def run_server() -> None: # noqa : D103
    runpy.run_path(f"{current_dir}/mcp_server.py")

current_dir = Path(__file__).parent
def test_mcp() -> None: # noqa : D103
    process = multiprocessing.Process(target=run_server)
    process.start()
    time.sleep(5)

    mcp_server = MCPServer(endpoint="http://127.0.0.1:8000/mcp")
    prompts = mcp_server.list_prompts()
    assert len(prompts) == 1
    prompt = prompts[0]
    assert prompt.name == "ask_sum"
    message = prompt.format_messages(role=MessageRole.SYSTEM, a="1", b="2")
    assert [ChatMessage(
            role=MessageRole.USER,
            content="Can you please calculate the sum of 1 and 2?",
        )] == message
    tools = mcp_server.list_tools()
    assert len(tools) == 1
    tool = tools[0]
    assert tool.name == "add"

    process.kill()





