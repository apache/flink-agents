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
"""Run a resolved route's candidates and keep its routing metadata."""

from typing import Any, Awaitable, Callable, TypeVar
from uuid import UUID

from flink_agents.api.events.model_routing_event import ModelRoutingEvent
from flink_agents.api.memory_object import MemoryObject
from flink_agents.api.runner_context import RunnerContext
from flink_agents.plan.routing.resolved_route import ResolvedModelRoute

# Sensory-memory key; checkpointed state, so the name must not change.
ROUTING_METADATA_CONTEXT = "_ROUTING_METADATA_CONTEXT"

T = TypeVar("T")


class CandidateAttemptFailed(Exception):
    """A candidate exhausted its retry budget or could not be prepared."""

    def __init__(self, error: Exception) -> None:
        """Wrap the candidate's final error."""
        super().__init__(str(error))
        self.error = error


class ModelRoutingExecutionException(Exception):
    """Every candidate in the attempt order failed."""

    def __init__(self, tried: list[str], errors: list[Exception]) -> None:
        """Keep each candidate's cause, in attempt order."""
        super().__init__(f"all {len(tried)} candidates failed")
        self.tried = tried
        self.errors = errors


async def execute(
    initial_request_id: UUID,
    selection: ResolvedModelRoute,
    attempt: Callable[[str, str], Awaitable[T]],
    ctx: RunnerContext,
) -> tuple[str, T]:
    """Try the route's candidates in order and return the first that answers.

    ``attempt(candidate, durable_id)`` raises ``CandidateAttemptFailed`` to move on to
    the next candidate. For a router, a fallback answer is announced with a
    ``ModelRoutingEvent`` and the route's response metadata is stored for the request.
    """
    tried: list[str] = []
    errors: list[Exception] = []
    for candidate in selection.attempt_order():
        tried.append(candidate)
        try:
            result = await attempt(candidate, selection.durable_chat_call_id(candidate))
        except CandidateAttemptFailed as failure:
            errors.append(failure.error)
            continue
        if selection.is_router:
            if candidate != selection.selected_model:
                ctx.send_event(
                    ModelRoutingEvent(
                        request_id=initial_request_id,
                        router=selection.requested_model,
                        candidates=selection.candidates,
                        selected_model=candidate,
                        decision_source="fallback",
                        fallback_enabled=selection.fallback_enabled,
                        reason=f"fallback after selected model '{selection.selected_model}' failed",
                        metadata=selection.metadata,
                    )
                )
            _store_metadata(
                ctx.sensory_memory,
                initial_request_id,
                selection.build_response_metadata(candidate, tried),
            )
        return candidate, result
    raise ModelRoutingExecutionException(tried, errors)


def _store_metadata(
    sensory_memory: MemoryObject, initial_request_id: UUID, metadata: dict[str, Any]
) -> None:
    routing_context = dict(sensory_memory.get(ROUTING_METADATA_CONTEXT) or {})
    routing_context[str(initial_request_id)] = metadata
    sensory_memory.set(ROUTING_METADATA_CONTEXT, routing_context)


def response_metadata(
    sensory_memory: MemoryObject, initial_request_id: UUID
) -> dict[str, Any] | None:
    """Return the routing metadata stored for a request, if it was routed."""
    return (sensory_memory.get(ROUTING_METADATA_CONTEXT) or {}).get(
        str(initial_request_id)
    )
