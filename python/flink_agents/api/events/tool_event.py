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
from typing import Any, ClassVar, Dict, List, Tuple

try:
    from typing import override
except ImportError:
    from typing_extensions import override
from uuid import UUID

from flink_agents.api.chat_message import ToolCallBlock
from flink_agents.api.events.event import BuiltInAttribute, Event
from flink_agents.api.tools.tool_response import ToolResponse


class ToolRequestEvent(Event):
    """Event representing a tool call request.

    Attributes:
    ----------
    model: str
        name of the model that generated the tool request.
    tool_calls : List[ToolCallBlock]
        tool calls that should be executed in batch.
    """

    EVENT_TYPE: ClassVar[str] = "_tool_request_event"

    _ATTRIBUTE_SCHEMA: ClassVar[Tuple[BuiltInAttribute, ...]] = (
        BuiltInAttribute.required("model", str),
        BuiltInAttribute.required_list(
            "tool_calls", "a tool call", (dict, ToolCallBlock)
        ),
    )

    def __init__(self, model: str, tool_calls: List[ToolCallBlock]) -> None:
        """Create a ToolRequestEvent."""
        tool_calls = [ToolCallBlock.model_validate(c) for c in tool_calls]
        if len({c.call_id for c in tool_calls}) != len(tool_calls):
            msg = "Duplicate tool call ID in one request"
            raise ValueError(msg)
        super().__init__(
            type=ToolRequestEvent.EVENT_TYPE,
            attributes={
                "model": model,
                "tool_calls": tool_calls,
            },
        )

    @classmethod
    @override
    def from_event(cls, event: Event) -> "ToolRequestEvent":
        cls._validate_attribute_schema(
            cls.EVENT_TYPE, event.attributes, cls._ATTRIBUTE_SCHEMA
        )
        result = ToolRequestEvent(
            model=event.attributes["model"],
            tool_calls=event.attributes["tool_calls"],
        )
        return result.reconstruct_from(event)

    @property
    def model(self) -> str:
        """Return the model name."""
        return self.get_attr("model")

    @property
    def tool_calls(self) -> List[ToolCallBlock]:
        """Return the list of tool calls."""
        return self.get_attr("tool_calls")


class ToolResponseEvent(Event):
    """Event representing a result from tool call.

    Attributes:
    ----------
    request_id : UUID
        The id of the request event.
    responses : Dict[str, Any]
        The dict maps tool call id to result.
    """

    EVENT_TYPE: ClassVar[str] = "_tool_response_event"

    _ATTRIBUTE_SCHEMA: ClassVar[Tuple[BuiltInAttribute, ...]] = (
        BuiltInAttribute.required_uuid("request_id"),
        BuiltInAttribute.required("responses", dict),
        BuiltInAttribute.optional("success", dict),
        BuiltInAttribute.optional("error", dict),
        # ``timestamp`` is written only by the Java runtime; accept it so
        # Java-produced events restore, then drop it (Python does not carry it)
        # to stay consistent with the Python snapshot shape.
        BuiltInAttribute.optional("timestamp", int),
    )

    def __init__(
        self,
        request_id: UUID,
        responses: Dict[str, Any],
        success: Dict[str, bool] | None = None,
        error: Dict[str, str] | None = None,
    ) -> None:
        """Create a ToolResponseEvent."""
        responses = {
            str(k): ToolResponse.model_validate(v)
            if isinstance(v, dict) and "success" in v and "blocks" in v
            else v
            if isinstance(v, ToolResponse)
            else ToolResponse.error(str(v))
            if not (success or {}).get(k, True)
            else ToolResponse.text(
                v
                if isinstance(v, str)
                else json.dumps(v, ensure_ascii=False, allow_nan=False)
            )
            for k, v in responses.items()
        }
        if success is None:
            success = {k: v.is_success() for k, v in responses.items()}
        super().__init__(
            type=ToolResponseEvent.EVENT_TYPE,
            attributes={
                "request_id": request_id,
                "responses": responses,
                "success": success
                if success is not None
                else dict.fromkeys(responses, True),
                "error": error if error is not None else {},
            },
        )

    @classmethod
    @override
    def from_event(cls, event: Event) -> "ToolResponseEvent":
        cls._validate_attribute_schema(
            cls.EVENT_TYPE, event.attributes, cls._ATTRIBUTE_SCHEMA
        )
        responses = event.attributes["responses"]
        result = ToolResponseEvent(
            request_id=event.attributes["request_id"],
            responses=responses,
            success=event.attributes.get("success", dict.fromkeys(responses, True)),
            error=event.attributes.get("error", {}),
        )
        return result.reconstruct_from(event)

    @property
    def request_id(self) -> UUID:
        """Return the request event ID."""
        val = self.get_attr("request_id")
        return UUID(val) if isinstance(val, str) else val

    @property
    def responses(self) -> Dict[str, ToolResponse]:
        """Return the tool call responses."""
        return self.get_attr("responses")

    @property
    def success(self) -> Dict[str, bool]:
        """Return whether each tool call succeeded."""
        return self.get_attr("success")

    @property
    def error(self) -> Dict[str, str]:
        """Return diagnostic errors for failed tool calls."""
        return self.get_attr("error")
