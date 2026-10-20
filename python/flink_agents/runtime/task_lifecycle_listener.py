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
"""Python mirror of the Java ``TaskLifecycleListener``."""

from typing import Any


class TaskLifecycleListener:
    """Observes the per-record and per-action lifecycle of the action operator.

    Mirrors the Java ``TaskLifecycleListener``: the operator drives Python
    actions on the JVM and forwards each lifecycle callback here over pemja.
    Every callback defaults to a no-op, so an implementation overrides only
    the ones it cares about. Callbacks are serialized within one operator:
    on the mailbox thread for cooperative execution, or under the execution
    lock for parallel execution. No fixed thread affinity is guaranteed.

    Pairing semantics: ``on_action_prepared`` enters an execution phase after
    the task's contexts are bound, not necessarily another body invocation.
    The phase ends with ``on_action_finishing`` followed by
    ``on_action_finished`` on normal completion, ``on_action_reused`` when a
    replay skips an already-completed action, or ``on_action_transferred``
    when the task yields execution. A transfer may retain the same task,
    including a worker-to-mailbox handoff. ``on_action_failed`` reports a task
    processing failure; it is not a phase exit and does not imply an open
    phase. ``on_action_started`` fires at most once per action execution; the
    gate is checkpointed with the task, so a failover
    replay re-emits ``on_record_start`` but not ``on_action_started``.

    Exception contract: the framework allows a listener to inspect state and
    raise when necessary; a listener that only observes should avoid raising.
    """

    def on_record_start(self, key: Any) -> None:
        """An input record was admitted for processing.

        Fires whether or not the record triggers any action. Also re-emitted
        when an in-flight record resumes after a failover, so listeners
        observe a paired bracket for the replayed round.
        """

    def on_action_prepared(self, task: Any) -> None:
        """A task's contexts are fully bound for the phase it is entering.

        Fires again after a handoff or suspension; the phase need not invoke
        the action body again.
        """

    def on_action_started(self, task: Any) -> None:
        """An action execution is about to run for the first time."""

    def on_action_transferred(self, from_task: Any, to_task: Any) -> None:
        """A task is yielding execution.

        Unbind the current task and move bookkeeping from ``from_task`` to
        ``to_task``, preserving it unchanged when both identify the same task.
        What comes next is either ``on_action_prepared`` for a further phase
        or ``on_action_failed``.
        """

    def on_action_finishing(self, task: Any) -> None:
        """A task completed but its result is not persisted yet.

        Fires immediately before the result is persisted.
        """

    def on_action_finished(self, task: Any) -> None:
        """A task's invocation finished normally and its result was persisted.

        Marks the end of the normal completion path; a later replay of the
        same action skips the invocation. Not emitted when the action fails.
        """

    def on_action_reused(self, task: Any) -> None:
        """A replayed already-completed action skipped its invocation.

        This is the sole terminal callback on the reuse path.
        """

    def on_action_failed(self, task: Any, error: Any) -> None:
        """Processing an action task failed.

        Purely observational: perceive the failure for logging, metrics, or
        bookkeeping cleanup, but never compensate or decide on rethrowing.

        Not a phase exit, and it does not imply an open phase: a task that
        failed after a transfer arrives here with no ``on_action_prepared`` in
        between, so cleanup must tolerate a task it already unbound.
        """

    def on_record_finished(self, key: Any) -> None:
        """Every task spawned by an input record has completed.

        A record that spawned none is retired here as well, so the bracket
        ``on_record_start`` opened always closes.

        Implementations must make their per-record cleanup idempotent: after
        a failover replay the notification may not arrive again for records
        that completed before the snapshot.
        """
