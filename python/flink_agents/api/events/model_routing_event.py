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
"""Observability-only model selection event."""

import copy
from numbers import Real
from typing import Any, ClassVar
from uuid import UUID

from flink_agents.api.events.event import BuiltInAttribute, Event


class ModelRoutingEvent(Event):
    """Describe a selection; this event never drives model dispatch."""

    EVENT_TYPE: ClassVar[str] = "_model_routing_event"
    _ATTRIBUTE_SCHEMA: ClassVar[tuple[BuiltInAttribute, ...]] = (
        BuiltInAttribute.required_uuid("request_id"),
        BuiltInAttribute.required("router", str),
        BuiltInAttribute.required("selected_model", str),
        BuiltInAttribute.required("decision_source", str),
        BuiltInAttribute.required("fallback_enabled", bool),
        BuiltInAttribute.required("metadata", dict),
        BuiltInAttribute.required_list("candidates", "a string", (str,)),
        BuiltInAttribute.optional("reason", str),
        BuiltInAttribute.optional("score", Real),
        BuiltInAttribute.optional("decision_ms", Real),
    )

    def __init__(
        self,
        *,
        request_id: UUID,
        router: str,
        candidates: list[str],
        selected_model: str,
        decision_source: str,
        fallback_enabled: bool,
        reason: str | None = None,
        score: float | None = None,
        metadata: dict[str, Any] | None = None,
        decision_ms: float | None = None,
    ) -> None:
        """Create a routing event with Java-compatible attributes."""
        attrs = {
            "request_id": UUID(request_id)
            if isinstance(request_id, str)
            else request_id,
            "router": router,
            "candidates": list(candidates),
            "selected_model": selected_model,
            "decision_source": decision_source,
            "fallback_enabled": fallback_enabled,
            "metadata": copy.deepcopy(metadata or {}),
        }
        attrs.update(
            {
                key: value
                for key, value in (
                    ("reason", reason),
                    ("score", score),
                    ("decision_ms", decision_ms),
                )
                if value is not None
            }
        )
        self._validate_attribute_schema(self.EVENT_TYPE, attrs, self._ATTRIBUTE_SCHEMA)
        super().__init__(type=self.EVENT_TYPE, attributes=attrs)

    @classmethod
    def from_event(cls, event: Event) -> "ModelRoutingEvent":
        """Restore the event and its lineage at a serialization boundary."""
        cls._validate_attribute_schema(
            cls.EVENT_TYPE, event.attributes, cls._ATTRIBUTE_SCHEMA
        )
        return cls(**event.attributes).reconstruct_from(event)

    @property
    def request_id(self) -> UUID:
        """The initial chat request ID."""
        return self.get_attr("request_id")

    @property
    def selected_model(self) -> str:
        """The selected concrete model."""
        return self.get_attr("selected_model")

    @property
    def decision_source(self) -> str:
        """Strategy, default, or fallback source."""
        return self.get_attr("decision_source")

    @property
    def router(self) -> str:
        """The requested router name."""
        return self.get_attr("router")

    @property
    def candidates(self) -> list[str]:
        """The declared candidate order."""
        return self.get_attr("candidates")

    @property
    def fallback_enabled(self) -> bool:
        """Whether fallback was enabled."""
        return self.get_attr("fallback_enabled")

    @property
    def reason(self) -> str | None:
        """The strategy or fallback reason."""
        return self.get_attr("reason")

    @property
    def score(self) -> float | None:
        """The strategy score, when supplied."""
        return self.get_attr("score")

    @property
    def metadata(self) -> dict[str, Any]:
        """Strategy-specific decision metadata."""
        return self.get_attr("metadata")

    @property
    def decision_ms(self) -> float | None:
        """The original strategy latency in milliseconds."""
        return self.get_attr("decision_ms")
