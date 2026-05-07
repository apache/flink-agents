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
from typing import Any, ClassVar, Dict, List

try:
    from typing import override
except ImportError:
    from typing_extensions import override
from uuid import UUID

from flink_agents.api.events.event import Event


class ToolRequestEvent(Event):
    """Event representing a tool call request.

    Attributes:
    ----------
    model: str
        name of the model that generated the tool request.
    tool_calls : List[Dict[str, Any]]
        tool calls that should be executed in batch.
    """

    EVENT_TYPE: ClassVar[str] = "_tool_request_event"

    def __init__(self, model: str, tool_calls: List[Dict[str, Any]]) -> None:
        """Create a ToolRequestEvent."""
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
        assert "model" in event.attributes
        assert "tool_calls" in event.attributes
        return ToolRequestEvent(
            model=event.attributes["model"],
            tool_calls=event.attributes["tool_calls"],
        )

    @property
    def model(self) -> str:
        """Return the model name."""
        return self.get_attr("model")

    @property
    def tool_calls(self) -> List[Dict[str, Any]]:
        """Return the list of tool calls."""
        return self.get_attr("tool_calls")


class ToolResponseEvent(Event):
    """Event representing a result from tool call.

    Attributes:
    ----------
    request_id : UUID
        The id of the request event.
    responses : Dict[UUID, Any]
        The dict maps tool call id to result.
    external_ids : Dict[UUID, str]
        Optional identifier for storing original tool call IDs from external systems
        (e.g., Anthropic tool_use_id).
    """

    EVENT_TYPE: ClassVar[str] = "_tool_response_event"

    def __init__(
        self,
        request_id: UUID,
        responses: Dict[UUID, Any],
        external_ids: Dict[UUID, str | None],
    ) -> None:
        """Create a ToolResponseEvent."""
        super().__init__(
            type=ToolResponseEvent.EVENT_TYPE,
            attributes={
                "request_id": request_id,
                "responses": responses,
                "external_ids": external_ids,
            },
        )

    @classmethod
    @override
    def from_event(cls, event: Event) -> "ToolResponseEvent":
        assert "request_id" in event.attributes
        assert "responses" in event.attributes
        assert "external_ids" in event.attributes
        return ToolResponseEvent(
            request_id=event.attributes["request_id"],
            responses=event.attributes["responses"],
            external_ids=event.attributes["external_ids"],
        )

    @property
    def request_id(self) -> UUID:
        """Return the request event ID."""
        return self.get_attr("request_id")

    @property
    def responses(self) -> Dict[UUID, Any]:
        """Return the tool call responses."""
        return self.get_attr("responses")

    @property
    def external_ids(self) -> Dict[UUID, str | None]:
        """Return the external tool call IDs."""
        return self.get_attr("external_ids")
