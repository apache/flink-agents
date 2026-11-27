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
from datetime import timedelta
from enum import Enum
from typing import Any, Callable

from pydantic import field_validator

from flink_agents.api.duration import parse_duration
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.subagent import (
    SubagentFuture,
    SubagentResult,
)
from flink_agents.runtime.async_future import SingleDurableFuture
from flink_agents.runtime.base_subagent import BaseSubagentSetup
from flink_agents.runtime.subagent_handles import (
    SubagentFutureImpl,
)


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
        """The service has no record of the run: the POST never landed (or
        the id mismatches).
        """
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


class BaseAsyncSubagentSetup(BaseSubagentSetup, ABC):
    """Production base for sub-agents whose protocol is an asynchronous job,
    run in durable pub/sub mode.

    ``submit`` publishes the run through one durable POST, the returned
    handle subscribes to it. The shape matches LangGraph runs, OpenAI
    Assistants runs, and A2A long-running tasks.
    """

    #: Delay between status probes while waiting for the run to reach a
    #: terminal state, declared in YAML as ``status_poll_interval``,
    #: the same argument the Java side reads from the descriptor. Both default
    #: to 500 milliseconds, and subclasses override the attribute directly.
    status_poll_interval: timedelta = timedelta(milliseconds=500)

    @field_validator("status_poll_interval", mode="before")
    @classmethod
    def validate_poll_interval(cls, value: Any) -> timedelta:
        """Parse a non-negative polling interval."""
        interval = parse_duration(value)
        if interval < timedelta(0):
            msg = "status_poll_interval must not be negative."
            raise ValueError(msg)
        return interval

    async def submit_with_identity(
        self,
        ctx: RunnerContext,
        prompt: Any,
        session_id: str,
        call_id: str,
    ) -> SubagentFuture:
        """Start the remote run through the durable POST and return its
        handle.

        The POST runs through async durable execution and lands before the
        handle is returned; a POST failure raises and fails the action.
        """
        await SingleDurableFuture(
            ctx,
            f"{session_id}#{call_id}",
            lambda: (
                self.submit_request(ctx, session_id, call_id, prompt),
                lambda: self.reconcile_submit_request(session_id, call_id, prompt),
            ),
        )
        delegate = ctx.durable_execute_async(
            self._await_until_terminal,
            session_id,
            call_id,
            durable_id=f"{session_id}#{call_id}#await",
        )
        return SubagentFutureImpl(
            session_id,
            call_id,
            delegate,
            self.pending_call_registry(),
            probe=lambda: self.query_status(session_id, call_id).state
            in (RunStatus.State.COMPLETED, RunStatus.State.FAILED),
            remote_cancel=lambda: self.cancel_request(ctx, session_id, call_id),
        )

    # --------------------------------------------------------------------------------
    # Framework wrappers: defaults composing the primitives, overridable
    # --------------------------------------------------------------------------------

    def submit_request(
        self, ctx: RunnerContext, session_id: str, call_id: str, prompt: Any
    ) -> Callable[[], None]:
        """Prepare the POST callback on the mailbox after its durable slot is reserved.

        Terminal replay skips preparation. Recovery of a pending POST may repeat
        this method, then invokes ``reconcile_submit_request`` on the async pool.
        """
        return lambda: self._post_submit_request(session_id, call_id, prompt)

    def query_status(self, session_id: str, call_id: str) -> RunStatus:
        """Probe the remote status. The probe is a direct read-only query,
        so durable execution does not record it and a failover replay
        probes again.
        """
        return self.call_query_status(session_id, call_id)

    def cancel_request(self, ctx: RunnerContext, session_id: str, call_id: str) -> None:
        """Propagate the cancellation. The wrapper calls the hook
        synchronously, so durable execution does not record the
        propagation and a failover replay propagates it again.
        """
        self.call_cancel_request(session_id, call_id)

    def _await_until_terminal(self, session_id: str, call_id: str) -> SubagentResult:
        """Poll the status until the run reaches a terminal state, then fetch
        the result.

        The body of the durable await composition keyed by
        ``session_id#call_id#await``. A probe or fetch failure that escapes
        the body is a system-level failure: it propagates instead of being
        folded into an error result.
        """
        while True:
            probe = self.call_query_status(session_id, call_id)
            if probe.state == RunStatus.State.COMPLETED:
                return self.call_fetch_result(session_id, call_id)
            if probe.state == RunStatus.State.FAILED:
                return SubagentResult.error(probe.error or "run failed")
            # NOT_STARTED or RUNNING: keep probing. A NOT_STARTED run after a
            # durable POST means the remote session expired; the replay then
            # observes the fresh state instead of the original probe path.
            time.sleep(self.status_poll_interval.total_seconds())

    def _post_submit_request(self, session_id: str, call_id: str, prompt: Any) -> None:
        """Run the body of the durable POST by delegating to the transport
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

        Implementations must report comprehensible failures (an expired
        endpoint, expired credentials, a rejected run) as a FAILED status
        rather than raising; an exception escaping this probe is treated as
        a system-level failure, propagates, and triggers a job failover.
        """

    @abstractmethod
    def call_fetch_result(self, session_id: str, call_id: str) -> SubagentResult:
        """Fetch the result of a run that reached a terminal state;
        comprehensible failures go into the :class:`SubagentResult`, while a
        raised exception is a system-level failure that propagates.

        The fetch must be an idempotent read: a failover re-executes it when
        the crash hit the fetch in flight.
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
        """Hook propagating a cancellation to the remote run. The default is
        a no-op.

        A replay may propagate the cancellation again, so remote
        cancellations must be idempotent.
        """
