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
"""Run a resolved route's candidates and hand back the round's routing metadata."""

from dataclasses import dataclass
from typing import Any, Awaitable, Callable, Generic, TypeVar
from uuid import UUID

from flink_agents.api.events.model_routing_event import ModelRoutingEvent
from flink_agents.api.memory_object import MemoryObject
from flink_agents.api.runner_context import RunnerContext
from flink_agents.plan.routing import metadata as routing_metadata
from flink_agents.plan.routing.resolved_route import ResolvedModelRoute

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


@dataclass
class ExecutionResult(Generic[T]):
    """What a round produced: the accepted candidate's answer and its routing block.

    ``routing_metadata`` is None when the round has no block of its own: a request
    that never went through a router, or a tool round, for which the block saved by
    the initial round stands.
    """

    request_id: UUID
    model: str
    attempt: T
    routing_metadata: dict[str, Any] | None


async def execute(
    initial_request_id: UUID,
    selection: ResolvedModelRoute,
    attempt: Callable[[str, str], Awaitable[T]],
    ctx: RunnerContext,
) -> ExecutionResult[T]:
    """Try the route's candidates in order and return the first that answers.

    ``attempt(candidate, durable_id)`` raises ``CandidateAttemptFailed`` to move on to
    the next candidate. For a router, a fallback answer is announced with a
    ``ModelRoutingEvent``. The round's block is returned, not stored: the caller
    saves it for a tool loop or attaches it to the response.
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
        block = None
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
            block = routing_metadata.build(selection, candidate, tried)
        return ExecutionResult(initial_request_id, candidate, result, block)
    raise ModelRoutingExecutionException(tried, errors)


def save_routing_metadata(
    sensory_memory: MemoryObject, result: ExecutionResult
) -> None:
    """Keep the round's block while the request continues in a tool loop.

    A round without a block of its own leaves the saved one in place.
    """
    if result.routing_metadata is not None:
        routing_metadata.save(
            sensory_memory, result.request_id, result.routing_metadata
        )


def get_and_clear_routing_metadata(
    sensory_memory: MemoryObject, result: ExecutionResult
) -> dict[str, Any] | None:
    """Return the block to attach to the final response and remove the saved one.

    None when the request never went through a router.
    """
    saved = routing_metadata.take(sensory_memory, result.request_id)
    return result.routing_metadata if result.routing_metadata is not None else saved


def clear_routing_metadata(sensory_memory: MemoryObject, request_id: UUID) -> None:
    """Remove the request's saved block when the request fails."""
    routing_metadata.take(sensory_memory, request_id)
