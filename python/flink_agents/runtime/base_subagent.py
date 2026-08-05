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
"""Runtime base for sub-agent setups: lifecycle observation and ids.

The Python parity of Java's ``BaseSubagentSetup`` and
``SubagentIdAllocator``. The setup observes the action task lifecycle to
know which task is currently executing, assigns deterministic identities
for the short ``submit`` forms, and owns the per-task pending-call
registry whose emptiness is enforced when a task finishes.
"""

import hashlib
import json
import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any

from pydantic import PrivateAttr

from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.subagent import SubagentFuture, SubagentSetup
from flink_agents.runtime.subagent_handles import PendingSubagentCallRegistry


def _event_attributes(event: Any) -> dict[str, Any]:
    """Normalize an event's attributes into a plain dict.

    Accepts the Java ``Event`` reference passed across the bridge as well
    as duck-typed fakes in tests; Java maps are copied entry by entry.
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
class TaskFacts:
    """The caller-side facts of one action task execution.

    Mirrors the fields the Java ``SubagentIdAllocator`` namespace digests.
    The facts are identical for every step of a suspended task, so maps
    keyed by :attr:`identity` survive await boundaries without migration.
    """

    key: str
    sequence_number: int
    action_name: str
    event_type: str
    event_attributes: dict[str, Any]

    @staticmethod
    def from_task(task: Any) -> "TaskFacts":
        """Extract the facts from an ``ActionTask`` reference or fake."""
        return TaskFacts(
            key=str(task.getKey()),
            sequence_number=int(task.getSequenceNumber()),
            action_name=str(task.getAction().getName()),
            event_type=str(task.getEvent().getType()),
            event_attributes=_event_attributes(task.getEvent()),
        )

    @property
    def identity(self) -> str:
        """A key stable across the steps of one task execution."""
        return f"{self.key}#{self.sequence_number}#{self.action_name}"


class SubagentIdAllocator:
    """Deterministic ``(session_id, call_id)`` source for one task.

    The Python parity of Java's ``SubagentIdAllocator``: the namespace is
    digested from the task facts plus the agent name, so a failover replay
    of the same task hands out the same ids in the same call order.
    """

    def __init__(self, facts: TaskFacts, agent_name: str | None = None) -> None:
        """Create an allocator over one task's caller-side facts."""
        self._facts = facts
        self._agent_name = agent_name
        self._namespace_digest: str | None = None
        self._session_ordinal = 0
        self._per_session_call_ordinals: dict[str, int] = {}

    def next_session_id(self) -> str:
        """Create a session id scoped to this task's namespace."""
        ordinal = self._session_ordinal
        self._session_ordinal += 1
        return f"{self._digest()}-{ordinal}"

    def next_call_id(self, session_id: str) -> str:
        """Create a call id by appending the per-session ordinal."""
        ordinal = self._per_session_call_ordinals.get(session_id, 0) + 1
        self._per_session_call_ordinals[session_id] = ordinal
        return f"{session_id}-{ordinal}"

    def _digest(self) -> str:
        """Digest the namespace into a name-based UUID string.

        Mirrors Java's ``UUID.nameUUIDFromBytes`` over the canonical JSON
        of the namespace fields, so the ids are reproducible across a
        failover replay.
        """
        if self._namespace_digest is None:
            namespace = {
                "actionName": self._facts.action_name,
                "agentName": self._agent_name,
                "eventAttributes": self._facts.event_attributes,
                "eventType": self._facts.event_type,
                "key": self._facts.key,
                "sequenceNumber": self._facts.sequence_number,
            }
            payload = json.dumps(
                namespace, sort_keys=True, separators=(",", ":"), default=str
            ).encode("utf-8")
            # MD5 with the version/variant bits, as in Java's
            # UUID.nameUUIDFromBytes (a version 3 UUID).
            digest = bytearray(hashlib.md5(payload).digest())
            digest[6] = (digest[6] & 0x0F) | 0x30
            digest[8] = (digest[8] & 0x3F) | 0x80
            self._namespace_digest = str(uuid.UUID(bytes=bytes(digest)))
        return self._namespace_digest


class BaseSubagentSetup(SubagentSetup, ABC):
    """Runtime base for sub-agent setups.

    The Python parity of Java's ``BaseSubagentSetup``: task lifecycle
    observation plus deterministic id assignment for the short
    :meth:`submit` forms.

    The setup observes the task lifecycle to know which action task is
    currently executing: ``submit`` runs on the mailbox thread immediately
    after the executing task's ``on_task_prepared``. The short forms then
    assign the missing ids through a per-task :class:`SubagentIdAllocator`
    built from that task's caller-side facts, so a failover replay assigns
    the same ids, and delegate to :meth:`submit_with_identity`, which stays
    abstract: how an invocation is issued is an execution mode owned by
    the concrete subclass.

    The base also owns the per-task :class:`PendingSubagentCallRegistry`:
    handles created during the task record themselves there, and
    ``on_task_finished`` fails the action when a handle was left
    unresolved — a dropped handle must either be resolved or explicitly
    cancelled, never silently skipped.

    Lifecycle hooks are invoked by the runtime bridge with keyword
    arguments carrying the Java ``ActionTask`` references; every step of a
    suspended (async) task re-fires ``on_task_prepared`` with the same
    facts, and the per-task bookkeeping is keyed by those facts, so it
    survives await boundaries without migration.
    """

    _per_task_allocators: dict[str, SubagentIdAllocator] = PrivateAttr(
        default_factory=dict
    )
    _per_task_registries: dict[str, PendingSubagentCallRegistry] = PrivateAttr(
        default_factory=dict
    )
    _current_facts: TaskFacts | None = PrivateAttr(default=None)
    _resource_name: str | None = PrivateAttr(default=None)

    # --------------------------------------------------------------------------------
    # Task lifecycle hooks (keyword-invoked by the runtime bridge)
    # --------------------------------------------------------------------------------

    def on_task_prepared(self, task: Any) -> None:
        """Record the task whose execution is currently issuing calls."""
        self._current_facts = TaskFacts.from_task(task)

    def on_task_transferred(self, from_task: Any, to_task: Any) -> None:
        """Continuation hook between the steps of a suspended task.

        No-op here: the generated task carries the same facts, so the
        facts-keyed bookkeeping already belongs to the continuation.
        """

    def on_task_finished(self, task: Any) -> None:
        """Drop the task's bookkeeping and enforce resolved handles."""
        self._current_facts = None
        identity = TaskFacts.from_task(task).identity
        self._per_task_allocators.pop(identity, None)
        registry = self._per_task_registries.pop(identity, None)
        if registry is not None:
            registry.check_empty(str(task.getAction().getName()))

    # --------------------------------------------------------------------------------
    # Identity injected by the framework
    # --------------------------------------------------------------------------------

    def set_resource_name(self, resource_name: str) -> None:
        """Record the (qualified) resource name injected on materialization.

        It is carried into the id namespace as the agent name, so
        sub-agents sharing one caller's counting range never hand out the
        same ids.
        """
        self._resource_name = resource_name

    @property
    def resource_name(self) -> str | None:
        """The injected resource name, or None outside the framework."""
        return self._resource_name

    # --------------------------------------------------------------------------------
    # Submit dispatch: complete missing ids, then delegate to the mode
    # --------------------------------------------------------------------------------

    def submit(
        self,
        ctx: RunnerContext,
        prompt: Any,
        session_id: str | None = None,
        call_id: str | None = None,
    ) -> SubagentFuture:
        """Issue an invocation, assigning missing ids deterministically.

        The short forms assign through the currently executing task's
        allocator, so a failover replay assigns the same ids; a fully
        supplied identity is passed through untouched.
        """
        if session_id is None or call_id is None:
            allocator = self._current_allocator()
            if session_id is None:
                session_id = allocator.next_session_id()
            if call_id is None:
                call_id = allocator.next_call_id(session_id)
        return self.submit_with_identity(ctx, prompt, session_id, call_id)

    @abstractmethod
    def submit_with_identity(
        self,
        ctx: RunnerContext,
        prompt: Any,
        session_id: str,
        call_id: str,
    ) -> SubagentFuture:
        """Issue one invocation under the fully assigned identity.

        The execution-mode hook implementing how the invocation is issued;
        the Python parity of Java's full four-argument ``submit``, which
        stays abstract on the base.
        """

    # --------------------------------------------------------------------------------
    # Per-task bookkeeping
    # --------------------------------------------------------------------------------

    def pending_call_registry(self) -> PendingSubagentCallRegistry | None:
        """The registry of the currently executing task.

        Handles record themselves there on creation; returns None outside
        a prepared task, so calls issued without a task context skip
        tracking.
        """
        return self._current_task_registry()

    def _current_task_registry(self) -> PendingSubagentCallRegistry | None:
        if self._current_facts is None:
            return None
        identity = self._current_facts.identity
        registry = self._per_task_registries.get(identity)
        if registry is None:
            registry = PendingSubagentCallRegistry()
            self._per_task_registries[identity] = registry
        return registry

    def _current_allocator(self) -> SubagentIdAllocator:
        """The allocator of the executing task; replays hand out the same
        ids.
        """
        if self._current_facts is None:
            msg = "No prepared action task to assign sub-agent ids from."
            raise RuntimeError(msg)
        facts = self._current_facts
        allocator = self._per_task_allocators.get(facts.identity)
        if allocator is None:
            allocator = SubagentIdAllocator(facts, self._resource_name)
            self._per_task_allocators[facts.identity] = allocator
        return allocator
