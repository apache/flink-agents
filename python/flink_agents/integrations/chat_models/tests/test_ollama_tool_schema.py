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
import json
from typing import Any, Dict, List, Literal

import httpx
from ollama import Client
from pydantic import BaseModel

from flink_agents.api.chat_message import ChatMessage
from flink_agents.integrations.chat_models.ollama_chat_model import (
    OllamaChatModelConnection,
    _to_ollama_tool,
)
from flink_agents.plan.function import PythonFunction
from flink_agents.plan.tools.function_tool import FunctionTool


def get_weather(
    city: str,
    days: int | None,
    interval: int | None = 3,
    unit: Literal["C", "F"] | None = None,
    tags: List[str] | None = None,
    options: Dict[str, int] | None = None,
) -> str:
    """Get the weather forecast for a city.

    Args:
        city: The city name.
        days: The number of days, or null for today only.
        interval: The forecast interval in hours.
        unit: The temperature unit.
        tags: Tags to filter the forecast by.
        options: Extra request options.
    """
    return ""


def _chat_with_tool(tool: FunctionTool) -> Dict[str, Any]:
    """Return the request body the Ollama SDK sends for a chat call with ``tool``."""
    requests: List[Dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return httpx.Response(
            200,
            json={
                "model": "local",
                "message": {"role": "assistant", "content": "ok"},
                "done": True,
                "done_reason": "stop",
            },
        )

    conn = OllamaChatModelConnection()
    conn._OllamaChatModelConnection__client = Client(
        host="http://ollama.test", transport=httpx.MockTransport(handler)
    )
    conn.chat([ChatMessage.user("hi")], tools=[tool], model="local")
    assert len(requests) == 1
    return requests[0]


def test_nullable_parameters_keep_their_type_after_sdk_serialization() -> None:
    """A nullable parameter reaches Ollama with the type list ``[<type>, "null"]``."""
    tool = FunctionTool(func=PythonFunction.from_callable(get_weather))

    function = _chat_with_tool(tool)["tools"][0]["function"]
    parameters = function["parameters"]

    assert function["name"] == "get_weather"
    assert function["description"] == tool.metadata.description
    assert parameters["required"] == ["city", "days"]
    assert parameters["properties"] == {
        "city": {"type": "string", "description": "The city name."},
        "days": {
            "type": ["integer", "null"],
            "description": "The number of days, or null for today only.",
        },
        "interval": {
            "type": ["integer", "null"],
            "description": "The forecast interval in hours.",
        },
        "unit": {
            "type": ["string", "null"],
            "description": "The temperature unit.",
            "enum": ["C", "F", None],
        },
        "tags": {
            "type": ["array", "null"],
            "items": {"type": "string"},
            "description": "Tags to filter the forecast by.",
        },
        "options": {
            "type": ["object", "null"],
            "description": "Extra request options.",
        },
    }


def test_tool_metadata_is_unchanged() -> None:
    """The schema that tool call arguments are validated against is not rewritten."""
    tool = FunctionTool(func=PythonFunction.from_callable(get_weather))
    before = tool.metadata.get_parameters_dict()

    _chat_with_tool(tool)

    assert tool.metadata.get_parameters_dict() == before


class Location(BaseModel):
    """A model parameter type, whose schema is a ``$ref`` into ``$defs``."""

    lat: float


def search(place: Location | None = None, key: int | str | None = None) -> str:
    """Search.

    Args:
        place: A nullable model.
        key: A union of two types and null.
    """
    return ""


def test_other_schemas_are_left_unchanged() -> None:
    """A nullable model and a union of several types are passed to the SDK as is."""
    tool = FunctionTool(func=PythonFunction.from_callable(search))

    parameters = _to_ollama_tool(tool)["function"]["parameters"]

    assert parameters == tool.metadata.get_parameters_dict()
