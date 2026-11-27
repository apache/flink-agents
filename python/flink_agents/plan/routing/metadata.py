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
"""Build, keep and hand back a routed request's ``model_routing`` block."""

from typing import Any
from uuid import UUID

from flink_agents.api.memory_object import MemoryObject
from flink_agents.plan.routing.resolved_route import ResolvedModelRoute

# Sensory-memory key; checkpointed state, so the name must not change.
ROUTING_METADATA_CONTEXT = "_ROUTING_METADATA_CONTEXT"


def build(
    route: ResolvedModelRoute, final_model: str, tried: list[str]
) -> dict[str, Any]:
    """Build the Java-compatible ``model_routing`` block of a round."""
    fallback = final_model != route.selected_model
    block = {
        "router": route.requested_model,
        "selected_model": route.selected_model,
        "final_model": final_model,
        "candidates": list(route.candidates),
        "decision_source": "fallback" if fallback else route.decision_source,
        "fallback_enabled": route.fallback_enabled,
        "fallback_attempted": fallback,
        "fallback_models_tried": tried[1:],
        "metadata": dict(route.metadata),
    }
    if route.reason is not None:
        block["reason"] = route.reason
    if route.score is not None:
        block["score"] = route.score
    return block


def save(sensory_memory: MemoryObject, request_id: UUID, block: dict[str, Any]) -> None:
    """Save the request's block; replaces the one saved by an earlier round."""
    context = dict(sensory_memory.get(ROUTING_METADATA_CONTEXT) or {})
    context[str(request_id)] = block
    sensory_memory.set(ROUTING_METADATA_CONTEXT, context)


def take(sensory_memory: MemoryObject, request_id: UUID) -> dict[str, Any] | None:
    """Remove and return the request's saved block; None when there is none."""
    context = dict(sensory_memory.get(ROUTING_METADATA_CONTEXT) or {})
    block = context.pop(str(request_id), None)
    if block is not None:
        sensory_memory.set(ROUTING_METADATA_CONTEXT, context)
    return block
