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
"""The async-job execution mode running in durable pub/sub mode."""

import time
from abc import ABC, abstractmethod
from concurrent.futures import CancelledError
from enum import Enum
from typing import Any

from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.subagent import (
    Result,
    SubagentFuture,
    SubagentFutures,
)
from flink_agents.runtime.base_subagent import BaseSubagentSetup
from flink_agents.runtime.subagent_handles import SubagentFutureGroup


class RunStatus:
    """State snapshot of a remote run reported by the ``call_query_status``
    probe.

    A state other than ``NOT_STARTED`` means the submission landed on the
    service, which is the sole basis for ``reconcile_submit_request``
    deciding between re-posting and polling. The snapshot never carries the
    result payload.
    """

    class State(Enum):
        """Lifecycle of the remote run."""

        NOT_STARTED = "not_started"
        RUNNING = "running"
        COMPLETED = "completed"
        FAILED = "failed"

    def __init__(self, state: "RunStatus.State", error: str | None = None) -> None:
        """Initialize with the lifecycle state and the optional error."""
        self._state = state
        self._error = error

    @staticmethod
    def not_started() -> "RunStatus":
        """The service has no record of the run: the POST never landed."""
        return RunStatus(RunStatus.State.NOT_STARTED)

    @staticmethod
    def running() -> "RunStatus":
        """The run is in progress."""
        return RunStatus(RunStatus.State.RUNNING)

    @staticmethod
    def completed() -> "RunStatus":
        """The run finished successfully."""
        return RunStatus(RunStatus.State.COMPLETED)

    @staticmethod
    def failed(error: str) -> "RunStatus":
        """The run failed, carrying the error message."""
        return RunStatus(RunStatus.State.FAILED, error)

    @property
    def state(self) -> "RunStatus.State":
        """The lifecycle state of the remote run."""
        return self._state

    @property
    def error(self) -> str | None:
        """The error message of a failed run; None otherwise."""
        return self._error


class AsyncSubagentFuture(SubagentFuture):
    """The sub side of an async-job invocation.

    The run was already started by the durable POST of ``submit``, so the
    handle only subscribes to it: :meth:`done` probes the status directly
    (not durable); awaiting the handle waits through the durable await
    composition; :meth:`cancel` propagates the cancellation through the
    setup's hook, and a cancelled resolve raises ``CancelledError``.
    """

    def __init__(
        self,
        setup: "BaseAsyncSubagentSetup",
        ctx: RunnerContext,
        session_id: str,
        call_id: str,
    ) -> None:
        """Initialize with the owning setup, the context, and the identity."""
        super().__init__(session_id, call_id)
        self._setup = setup
        self._ctx = ctx
        self._consumed = False
        self._cancelled = False
        self._value: Result | None = None

    def done(self) -> bool:
        """Probe the remote status directly; not durable.

        A failover replay may probe a different number of times than the
        original execution.
        """
        if self._consumed or self._cancelled:
            return True
        try:
            probe = self._setup.query_status(self.session_id, self.call_id)
        except Exception:
            return False
        return probe.state in (
            RunStatus.State.COMPLETED,
            RunStatus.State.FAILED,
        )

    def __await__(self) -> Any:
        """Wait for the run through the durable await composition, releasing
        the mailbox while waiting.

        A cancelled handle raises :class:`CancelledError`.
        """
        if self._cancelled:
            msg = f"Sub-agent call cancelled: {self.identity}"
            raise CancelledError(msg)
        if not self._consumed:
            self._value = yield from self._ctx.durable_execute_async(
                self._setup._await_until_terminal,
                self.session_id,
                self.call_id,
                durable_id=f"{self.identity}#await",
            ).__await__()
            self._consumed = True
        return self._value

    def cancel(self) -> None:
        """Propagate the cancellation through the setup's hook; not durable.

        A failover replay (which creates a fresh handle) may propagate it
        again; remote cancellations must be idempotent. A repeated cancel on
        the same handle and a cancel after the resolve are local no-ops. A
        cancelled resolve raises :class:`CancelledError`; a hook failure
        propagates and fails the action.
        """
        if self._consumed or self._cancelled:
            return
        self._setup.cancel_request(self._ctx, self.session_id, self.call_id)
        self._cancelled = True

    def combine(self, *others: SubagentFuture) -> SubagentFutures:
        """Group this handle with others for a batched resolve."""
        return SubagentFutureGroup((self, *others))


class BaseAsyncSubagentSetup(BaseSubagentSetup, ABC):
    """Runtime base for sub-agents whose protocol is an asynchronous job,
    run in pub/sub mode.

    ``submit`` (the pub) starts the run remotely through one durable POST and
    immediately returns a handle carrying the ``(session_id, call_id)``
    identity; ``done``, the resolve and ``cancel`` on the handle (the sub)
    query or steer that run. The shape matches LangGraph runs, OpenAI
    Assistants runs, and A2A long-running tasks.

    Integrations only provide the transport primitives they already
    understand, with no durable concepts involved:

    * :meth:`call_submit_request` — start the run remotely; a raised
      exception fails the action;
    * :meth:`call_query_status` — a read-only probe of the run's current
      state;
    * :meth:`call_fetch_result` — fetch the result of a run that reached a
      terminal state;
    * :meth:`call_cancel_request` — optional hook propagating a cancellation
      to the remote run.

    Persistence conventions:

    * the submit POST — durable, id ``session_id#call_id``, the only
      operation wired to a reconciler (:meth:`reconcile_submit_request`), so
      the remote run is started at most once even across a crash between the
      POST landing and its completion being persisted;
    * :meth:`query_status` — not durable: a direct read-only probe. The state
      advances monotonically toward a terminal state, so a replay observing a
      fresher state is harmless;
    * the fetch — durable, id ``session_id#call_id#fetch``: the result
      enters the caller's data flow and must replay deterministically. No
      reconciler; recovery re-executes the fetch, which is an idempotent
      read;
    * the await composition of the resolve — durable, id
      ``session_id#call_id#await``: poll the status until a terminal
      state, then fetch;
    * :meth:`cancel_request` — not durable: a direct, synchronous
      propagation. Remote cancellations are expected to be idempotent, so a
      replay propagating the cancellation again is harmless.

    The fetch and await ids are fixed per identity: both compositions are
    built from idempotent reads, so a recovery re-executing them converges
    to the same outcome as the original run.

    Cancellation contract (dev-facing): cancel decisions typically depend on
    nondeterministic inputs such as processing time, so a failover replay
    does not promise control flow equivalent to the original execution. The
    only at-most-once guarantee is the POST, enforced by the reconciler;
    cancellation propagation is best-effort and idempotent. The hook returns
    nothing: a cancelled resolve always raises ``CancelledError``, and a
    hook failure propagates from :meth:`cancel` and fails the action.

    Known limitations: a remote session or run record expiring after a
    failover may change the non-durable status probe and prevent a replay
    from reproducing the original fetch path (persisted fetch records still
    short-circuit); a fetch in flight when the process crashed cannot be
    recovered from a consume-once remote (the remote protocol must guarantee
    idempotent reads); and any cancellation may discard a fetch that had
    actually succeeded — cancel is the authoritative control-flow decision.
    """

    #: Delay between status probes while waiting for the run to reach a
    #: terminal state; the Python parity of Java's statusPollIntervalMillis.
    status_poll_interval_seconds: float = 0.01

    def submit_with_identity(
        self,
        ctx: RunnerContext,
        prompt: Any,
        session_id: str,
        call_id: str,
    ) -> SubagentFuture:
        """Start the remote run through the durable POST and return its
        handle.

        The POST runs on the mailbox thread and lands before the handle is
        returned; a POST failure raises and fails the action.
        """
        self.submit_request(ctx, session_id, call_id, prompt)
        return AsyncSubagentFuture(self, ctx, session_id, call_id)

    # --------------------------------------------------------------------------------
    # Framework wrappers: defaults composing the primitives, overridable
    # --------------------------------------------------------------------------------

    def submit_request(
        self,
        ctx: RunnerContext,
        session_id: str,
        call_id: str,
        prompt: Any,
    ) -> None:
        """Run the durable POST of one invocation; the only wrapper wired to
        a reconciler.

        Recovery never assumes the POST was lost: the reconciler probes
        first and resends only a missing run, so a crash after the POST
        landed never duplicates the prompt.
        """
        ctx.durable_execute(
            self._post_submit_request,
            session_id,
            call_id,
            prompt,
            durable_id=f"{session_id}#{call_id}",
            reconciler=lambda: self.reconcile_submit_request(
                session_id, call_id, prompt
            ),
        )

    def query_status(self, session_id: str, call_id: str) -> RunStatus:
        """Probe the remote status; not durable, a direct read-only query."""
        return self.call_query_status(session_id, call_id)

    def fetch_result(
        self,
        ctx: RunnerContext,
        session_id: str,
        call_id: str,
    ) -> Result:
        """Run the durable fetch of a terminal run's result, keyed by
        ``session_id#call_id#fetch``.
        """
        try:
            return ctx.durable_execute(
                self.call_fetch_result,
                session_id,
                call_id,
                durable_id=f"{session_id}#{call_id}#fetch",
            )
        except Exception as e:
            return Result.error(e)

    def cancel_request(self, ctx: RunnerContext, session_id: str, call_id: str) -> None:
        """Propagate the cancellation; not durable, a direct synchronous call
        to the hook.
        """
        self.call_cancel_request(session_id, call_id)

    def _await_until_terminal(self, session_id: str, call_id: str) -> Result:
        """Poll the status until the run reaches a terminal state, then fetch
        the result.

        The body of the durable await composition keyed by
        ``session_id#call_id#await``.
        """
        try:
            while True:
                probe = self.call_query_status(session_id, call_id)
                if probe.state == RunStatus.State.COMPLETED:
                    return self.call_fetch_result(session_id, call_id)
                if probe.state == RunStatus.State.FAILED:
                    return Result.error(probe.error or "run failed")
                # NOT_STARTED or RUNNING: keep probing. A NOT_STARTED run
                # after a durable POST means the remote session expired; see
                # the known limitations in the class docstring.
                time.sleep(self.status_poll_interval_seconds)
        except Exception as e:
            return Result.error(e)

    def _post_submit_request(self, session_id: str, call_id: str, prompt: Any) -> None:
        """The body of the durable POST; delegates to the transport
        primitive.
        """
        self.call_submit_request(session_id, call_id, prompt)

    # --------------------------------------------------------------------------------
    # Transport primitives provided by the integration
    # --------------------------------------------------------------------------------

    @abstractmethod
    def call_submit_request(self, session_id: str, call_id: str, prompt: Any) -> None:
        """Start the run remotely. A raised exception fails the enclosing
        action.
        """

    @abstractmethod
    def call_query_status(self, session_id: str, call_id: str) -> RunStatus:
        """Probe the run's current state read-only; must not alter the remote
        run.

        The status never carries the result payload — the result is fetched
        separately through :meth:`call_fetch_result`.
        """

    @abstractmethod
    def call_fetch_result(self, session_id: str, call_id: str) -> Result:
        """Fetch the result of a run that reached a terminal state; failures
        go into the Result.
        """

    def reconcile_submit_request(
        self, session_id: str, call_id: str, prompt: Any
    ) -> None:
        """The crash-window recovery of the POST: probes the status and
        handles every state explicitly, so a landed POST is never
        duplicated. A probe failure propagates and fails the recovery.
        """
        probe = self.call_query_status(session_id, call_id)
        state = probe.state
        if state == RunStatus.State.NOT_STARTED:
            # The service has no record of the run: the POST never landed.
            self.call_submit_request(session_id, call_id, prompt)
        elif state == RunStatus.State.RUNNING:
            # The POST landed and the run is in flight; the resolve keeps
            # polling it. Nothing to repair.
            pass
        elif state in (RunStatus.State.COMPLETED, RunStatus.State.FAILED):
            # The run reached a terminal state while the caller was down;
            # the resolve picks up the outcome — the fetch or the reported
            # error. Nothing to repair.
            pass
        else:
            # Fail loudly instead of silently skipping an unknown state.
            msg = f"Unknown run state: {state}"
            raise ValueError(msg)

    def call_cancel_request(self, session_id: str, call_id: str) -> None:
        """Hook propagating a cancellation to the remote run; the default is
        a no-op.

        The hook returns nothing: a cancelled resolve always raises
        ``CancelledError``. A replay may propagate the cancellation again;
        remote cancellations must be idempotent.
        """
