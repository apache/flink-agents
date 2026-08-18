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
################################################################################
"""The framework-level runtime base shared by every sub-agent execution mode."""

import hashlib
import json
import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass, replace
from typing import Any

from pydantic import PrivateAttr

from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.subagent import SubagentFuture, SubagentSetup
from flink_agents.runtime.subagent_handles import PendingSubagentCallRegistry
from flink_agents.runtime.task_lifecycle_listener import TaskLifecycleListener


def _event_attributes(event: Any) -> dict[str, Any]:
    """Normalize an event's attributes into a plain dict.

    Accepts both a Java ``Event`` reference passed across the bridge and a
    plain Python mapping, copying Java maps entry by entry.
    """
    attributes = event.getAttributes()
    if attributes is None:
        return {}
    if isinstance(attributes, dict):
        return dict(attributes)
    try:
        return {str(k): v for k, v in attributes.entrySet()}
    except AttributeError:
        return {str(k): v for k, v in dict(attributes).items()}


@dataclass(frozen=True)
class Namespace:
    """The caller-side identity of one action task execution.

    Provides the task identity keying the runtime bookkeeping and the
    namespace digest seeding the deterministic ids of the sub-agent
    calls the task issues.

    Key, sequence number, action name, and the event's type and
    attributes are facts of the execution itself, identical for every
    sub-agent called from it. The subagent name distinguishes the
    sub-agents called from one action, so it alone keeps their id
    ranges apart.
    """

    key: str
    sequence_number: int
    action_name: str
    event_type: str
    event_attributes: dict[str, Any]
    event_id: str
    subagent_name: str = ""

    @staticmethod
    def from_task(task: Any) -> "Namespace":
        """Extract the facts from an ``ActionTask`` reference or fake."""
        return Namespace(
            key=str(task.getKey()),
            sequence_number=int(task.getSequenceNumber()),
            action_name=str(task.getAction().getName()),
            event_type=str(task.getEvent().getType()),
            event_attributes=_event_attributes(task.getEvent()),
            event_id=str(task.getEvent().getId()),
        )

    @property
    def task_identity(self) -> str:
        """A key unique among live task executions and stable across the
        steps of one task.
        """
        return f"{self.key}#{self.sequence_number}#{self.action_name}#{self.event_id}"

    def namespace_digest(self) -> str:
        """Digest the id-bearing facts into a name-based UUID string.

        The ids are reproducible across a failover replay. The event id
        stays out of the digest: it keys the runtime bookkeeping only.
        """
        fields = {
            "actionName": self.action_name,
            "eventAttributes": self.event_attributes,
            "eventType": self.event_type,
            "key": self.key,
            "sequenceNumber": self.sequence_number,
            "subagentName": self.subagent_name,
        }
        payload = json.dumps(
            fields, sort_keys=True, separators=(",", ":"), default=str
        ).encode("utf-8")
        # MD5 with the version/variant bits, as in Java's
        # UUID.nameUUIDFromBytes (a version 3 UUID).
        digest = bytearray(hashlib.md5(payload).digest())
        digest[6] = (digest[6] & 0x0F) | 0x30
        digest[8] = (digest[8] & 0x3F) | 0x80
        return str(uuid.UUID(bytes=bytes(digest)))


class SubagentIdAllocator:
    """Deterministic ``(session_id, call_id)`` source for one task execution.

    The namespace digest fixes the counting range, so a failover replay
    of the same task hands out the same ids in the same call order.
    """

    def __init__(self, namespace: Namespace) -> None:
        """Create an allocator over one task's namespace."""
        self._namespace = namespace
        self._session_ordinal = 0
        self._per_session_call_ordinals: dict[str, int] = {}

    def next_session_id(self) -> str:
        """Create a session id scoped to this task's namespace."""
        ordinal = self._session_ordinal
        self._session_ordinal += 1
        return f"{self._namespace.namespace_digest()}-{ordinal}"

    def next_call_id(self, session_id: str) -> str:
        """Create a call id by appending the per-session ordinal."""
        ordinal = self._per_session_call_ordinals.get(session_id, 0) + 1
        self._per_session_call_ordinals[session_id] = ordinal
        return f"{session_id}-{ordinal}"


class BaseSubagentSetup(SubagentSetup, TaskLifecycleListener, ABC):
    """Runtime base for sub-agent setups, holding the per-task id allocators
    and pending-call registries keyed to the currently executing action task.
    How an invocation is issued stays an execution mode owned by the concrete
    subclass.
    """

    _per_task_allocators: dict[str, SubagentIdAllocator] = PrivateAttr(
        default_factory=dict
    )
    _per_task_registries: dict[str, PendingSubagentCallRegistry] = PrivateAttr(
        default_factory=dict
    )
    _current_namespace: Namespace | None = PrivateAttr(default=None)
    _subagent_name: str | None = PrivateAttr(default=None)

    # --------------------------------------------------------------------------------
    # Task lifecycle hooks (keyword-invoked by the runtime bridge)
    # --------------------------------------------------------------------------------

    def on_action_prepared(self, task: Any) -> None:
        """Record the task whose execution is currently issuing calls."""
        namespace = Namespace.from_task(task)
        self._current_namespace = replace(
            namespace, subagent_name=self._subagent_name or ""
        )

    def on_action_transferred(self, from_task: Any, to_task: Any) -> None:
        """Move the finishing task's bookkeeping onto the generated task."""
        from_identity = Namespace.from_task(from_task).task_identity
        to_identity = Namespace.from_task(to_task).task_identity
        allocator = self._per_task_allocators.pop(from_identity, None)
        if allocator is not None:
            self._per_task_allocators[to_identity] = allocator
        registry = self._per_task_registries.pop(from_identity, None)
        if registry is not None:
            registry.set_action_name(
                Namespace.from_task(to_task).action_name
            )
            self._per_task_registries[to_identity] = registry

    def on_action_finishing(self, task: Any) -> None:
        """Drop the task's bookkeeping and enforce resolved handles.

        The replay-reuse path reaches the same finalization through
        ``on_action_reused``, keeping the prepared/terminal pairing intact on
        both paths. A failed invocation intentionally skips this cleanup: the
        failure fails the run and the task is replayed on the restarted
        operator, so stale entries cannot outlive the run.
        """
        self._current_namespace = None
        identity = Namespace.from_task(task).task_identity
        self._per_task_allocators.pop(identity, None)
        registry = self._per_task_registries.pop(identity, None)
        if registry is not None:
            registry.check_empty()

    def on_action_reused(self, task: Any) -> None:
        """Reuse is a terminal outcome like finishing, so share finalization."""
        self.on_action_finishing(task)

    # --------------------------------------------------------------------------------
    # Identity injected by the framework
    # --------------------------------------------------------------------------------

    def set_subagent_name(self, subagent_name: str) -> None:
        """Record the resource name the framework injects as the subagent name."""
        self._subagent_name = subagent_name

    @property
    def subagent_name(self) -> str | None:
        """The injected subagent name, or None outside the framework."""
        return self._subagent_name

    # --------------------------------------------------------------------------------
    # Submit dispatch: complete missing ids, then delegate to the mode
    # --------------------------------------------------------------------------------

    async def submit(
        self,
        ctx: RunnerContext,
        prompt: Any,
        session_id: str | None = None,
        call_id: str | None = None,
    ) -> SubagentFuture:
        """Issue an invocation, assigning the missing ids deterministically.

        The ids are assigned when this call is awaited rather than when it is
        made, so a replay awaiting the invocations in the same order hands
        out the same ids.
        """
        if session_id is None or call_id is None:
            allocator = self._current_allocator()
            if session_id is None:
                session_id = allocator.next_session_id()
            if call_id is None:
                call_id = allocator.next_call_id(session_id)
        return await self.submit_with_identity(ctx, prompt, session_id, call_id)

    @abstractmethod
    async def submit_with_identity(
        self,
        ctx: RunnerContext,
        prompt: Any,
        session_id: str,
        call_id: str,
    ) -> SubagentFuture:
        """Issue one invocation under the fully assigned identity.

        The execution-mode hook implementing how the invocation is issued.
        """

    # --------------------------------------------------------------------------------
    # Per-task bookkeeping
    # --------------------------------------------------------------------------------

    def pending_call_registry(self) -> PendingSubagentCallRegistry | None:
        """The registry of the currently executing task.

        Handles record themselves there on creation. Returns None outside a
        prepared task, so calls issued without a task context skip tracking.
        """
        return self._current_task_registry()

    def _current_task_registry(self) -> PendingSubagentCallRegistry | None:
        if self._current_namespace is None:
            return None
        identity = self._current_namespace.task_identity
        registry = self._per_task_registries.get(identity)
        if registry is None:
            registry = PendingSubagentCallRegistry(
                self._current_namespace.action_name
            )
            self._per_task_registries[identity] = registry
        return registry

    def _current_allocator(self) -> SubagentIdAllocator:
        """The allocator of the executing task, scoped to one action
        execution so ordinals restart for the next action. Replays hand
        out the same ids.
        """
        if self._current_namespace is None:
            msg = "No prepared action task to assign sub-agent ids from."
            raise RuntimeError(msg)
        namespace = self._current_namespace
        allocator = self._per_task_allocators.get(namespace.task_identity)
        if allocator is None:
            allocator = SubagentIdAllocator(namespace)
            self._per_task_allocators[namespace.task_identity] = allocator
        return allocator
