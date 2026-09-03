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
    the ones it cares about.

    Pairing semantics: ``on_action_prepared`` pairs with exactly one terminal
    callback -- ``on_action_finishing`` followed by ``on_action_finished`` on
    normal completion, ``on_action_reused`` when a replay skips an
    already-completed action, ``on_action_failed`` on invocation failure, or
    ``on_action_transferred`` when a non-finished task hands its context over
    to the task it generated. ``on_action_started`` fires at most once per
    action execution; the gate is checkpointed with the task, so a failover
    replay re-emits ``on_record_start`` but not ``on_action_started``.

    Exception contract: the framework allows a listener to inspect state and
    raise when necessary; a listener that only observes should avoid raising.
    """

    def on_record_start(self, key: Any) -> None:
        """The first task of an input record is about to be prepared.

        Also re-emitted when an in-flight record resumes after a failover,
        so listeners observe a paired bracket for the replayed round.
        """

    def on_action_prepared(self, task: Any) -> None:
        """A task's context is wired up and it is ready to run.

        Fires on every preparation, including re-preparation of a suspended
        or resumed task.
        """

    def on_action_started(self, task: Any) -> None:
        """An action execution is about to run for the first time."""

    def on_action_transferred(self, from_task: Any, to_task: Any) -> None:
        """A non-finished task handed its context to the task it generated."""

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
        """An action invocation failed.

        Purely observational: perceive the failure for logging, metrics, or
        bookkeeping cleanup, but never compensate or decide on rethrowing.
        """

    def on_record_finished(self, key: Any) -> None:
        """Every task spawned by an input record has completed.

        Implementations must make their per-record cleanup idempotent: after
        a failover replay the notification may not arrive again for records
        that completed before the snapshot.
        """
