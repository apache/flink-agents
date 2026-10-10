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
"""Fixtures for the real Java/Python tool response bridge test."""

from typing import Any

from flink_agents.api.chat_message import ImageBlock, TextBlock
from flink_agents.api.tools import ToolResponse
from flink_agents.plan.function import JavaFunction
from flink_agents.plan.tools.function_tool import FunctionTool


def media_response() -> ToolResponse:
    """Return media and non-UTF8 binary application data."""
    return ToolResponse(
        blocks=[
            TextBlock(text="before"),
            ImageBlock.from_bytes("image/png", b"\xff\x00"),
            TextBlock(text="after"),
        ],
        metadata={"binary": b"\xff\x00"},
        execution_time_ms=7,
        tool_name="media",
    )


def check_java_response(adapter: Any, class_name: str) -> bool:
    """Check the Java adapter's response after actual JNI conversion to Python."""
    tool = FunctionTool(
        func=JavaFunction(
            qualname=class_name, method_name="mediaResponse", parameter_types=[]
        )
    )
    tool.set_java_resource_adapter(adapter)
    response = tool.call()
    assert response == media_response()
    assert response.to_result_block("call").metadata == {}
    return True
