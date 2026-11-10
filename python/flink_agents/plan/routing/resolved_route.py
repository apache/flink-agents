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
"""Resolved selection, fallback order and response metadata."""

from dataclasses import dataclass, field
from typing import Any


@dataclass
class ResolvedModelRoute:
    """Facts about one initial request's model selection."""

    requested_model: str
    selected_model: str
    candidates: list[str]
    is_router: bool = False
    fallback_enabled: bool = False
    decision_source: str = "direct"
    reason: str | None = None
    score: float | None = None
    metadata: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def direct(cls, model: str) -> "ResolvedModelRoute":
        """Resolve a concrete model without routing."""
        return cls(model, model, [model])

    def attempt_order(self) -> list[str]:
        """Selected model first, then remaining candidates in declared order."""
        return [self.selected_model] + (
            [name for name in self.candidates if name != self.selected_model]
            if self.fallback_enabled
            else []
        )

    def durable_chat_call_id(self, candidate: str) -> str:
        """Stable call identity, scoped by the durable action's sequence."""
        return f"chat:{self.requested_model}:{candidate}" if self.is_router else "chat"

    def build_response_metadata(
        self, final_model: str, tried: list[str]
    ) -> dict[str, Any]:
        """Build the Java-compatible model_routing response attribute."""
        fallback = final_model != self.selected_model
        result = {
            "router": self.requested_model,
            "selected_model": self.selected_model,
            "final_model": final_model,
            "candidates": list(self.candidates),
            "decision_source": "fallback" if fallback else self.decision_source,
            "fallback_enabled": self.fallback_enabled,
            "fallback_attempted": fallback,
            "fallback_models_tried": tried[1:],
            "metadata": dict(self.metadata),
        }
        if self.reason is not None:
            result["reason"] = self.reason
        if self.score is not None:
            result["score"] = self.score
        return result
