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
"""Runtime scheduling shared by single awaits and mixed gathers.

Only callable execution runs on the worker pool. Preparation, journal updates,
subgraph polling and outcome mapping run on the action's mailbox continuation.
"""

import logging
import time
from concurrent.futures import CancelledError
from functools import partial
from threading import Lock
from typing import Any, Callable

from typing_extensions import override

from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.duration import format_duration, positive_seconds
from flink_agents.api.runner_context import AsyncFuture, DurableFuture, Outcome
from flink_agents.plan.exceptions import raise_if_framework_fault_or_cancellation

_LOG = logging.getLogger(__name__)


def rethrow_fatal(error: BaseException | None) -> None:
    """Keep runtime faults and interrupted attempts out of durable outcomes."""
    if error is None:
        return
    raise_if_framework_fault_or_cancellation(error)
    if not isinstance(error, Exception):
        raise error


class SchedulableFuture(AsyncFuture[Any]):
    """Internal lifecycle for a call driven by the runtime scheduler."""

    def __init__(self, ctx: Any) -> None:
        """Bind the handle to its creating runner context."""
        AsyncFuture.__init__(self)
        self._ctx = ctx

    @property
    def call_identity(self) -> object:
        """Identity shared by views of the same scheduled call."""
        return self

    def validate(self, ctx: Any) -> None:
        """Check ownership before any call in the batch can produce side effects."""
        if self._ctx is not None and self._ctx is not ctx:
            message = "An async future must be gathered by the context that created it"
            raise ValueError(message)

    def __await__(self) -> Any:
        # Scheduling/bridge failures are never cached as a call's terminal result.
        if not self._is_done():
            yield from self._resolve()
        outcome = self._get_completed_outcome()
        if outcome.error is not None:
            raise outcome.error
        return outcome.value

    def _resolve(self) -> Any:
        return (yield from resolve_calls(self._ctx, (self,), 1, None))

    def prepare(self) -> Outcome | None:
        """Reserve state in input order; return a replayed outcome if available."""
        return self._get_completed_outcome() if self._is_done() else None

    def start(self) -> None:
        """Start the prepared operation without waiting for its result."""
        raise NotImplementedError

    def ready(self) -> bool:
        """Whether finish can collect an outcome or propagate an execution failure."""
        raise NotImplementedError

    def finish(self, timeout: TimeoutError | None = None) -> Outcome:
        """Collect and record a result, or locally complete a timed-out wait."""
        raise NotImplementedError

    def prevent_start(self) -> None:
        """Fence queued work before cancellation, so it cannot start afterwards."""

    def cancel(self) -> None:
        """Cancel outstanding work without recording a terminal result."""

    def close(self) -> None:
        """Release registrations after completion, cancellation or a runtime fault."""


class SingleAsyncFuture(SchedulableFuture):
    """One callable submitted to the shared async pool."""

    def __init__(
        self,
        ctx: Any,
        func: Callable | None,
        args: tuple = (),
        kwargs: dict | None = None,
    ) -> None:
        """Store the callable without submitting work to the executor."""
        super().__init__(ctx)
        self._execution = (
            partial(func, *args, **(kwargs or {})) if func is not None else None
        )
        self._submission = None
        self._started = False
        self._prevented = False
        self._lock = Lock()

    @override
    def start(self) -> None:
        if self._submission is None and not self._is_done():
            self._submission = self._ctx.executor.submit(self._run)

    def _run(self) -> Any:
        with self._lock:
            if self._prevented:
                raise CancelledError()
            self._started = True
        return self._execution()

    @override
    def ready(self) -> bool:
        return self._is_done() or (
            self._submission is not None
            and self._submission.done()
            # A scheduler-fenced call must retain the gather timeout, rather
            # than surface the cancellation used to remove it from the queue.
            and not (self._prevented and not self._started)
        )

    def _outcome(self, timeout: TimeoutError | None) -> Outcome:
        if self._is_done():
            return self._get_completed_outcome()
        if timeout is not None:
            return Outcome.failure(timeout)
        try:
            return Outcome.success(self._submission.result())
        except BaseException as error:
            rethrow_fatal(error)
            return Outcome.failure(error)

    @override
    def finish(self, timeout: TimeoutError | None = None) -> Outcome:
        if not self._is_done():
            self._complete(self._outcome(timeout))
        return self._get_completed_outcome()

    @override
    def prevent_start(self) -> None:
        with self._lock:
            self._prevented = True

    @override
    def cancel(self) -> None:
        if self._submission is not None:
            self._submission.cancel()


class SingleDurableFuture(SingleAsyncFuture, DurableFuture[Any]):
    """A callable whose slot is reserved before user preparation can run."""

    def __init__(self, ctx: Any, durable_id: str, preparation: Callable) -> None:
        """Bind a stable durable id to mailbox-side preparation."""
        super().__init__(ctx, None)
        self._durable_id = durable_id
        self._preparation = preparation
        self._slot: int | None = None

    @override
    def prepare(self) -> Outcome | None:
        if self._slot is not None or self._is_done():
            return super().prepare()
        java = self._ctx._j_runner_context
        index = java.getCurrentCallIndex()
        saved = self._ctx._read_call_result_at(index)
        if saved is not None and saved.function_id != self._durable_id:
            java.clearCallResultsFromAndPersist(index)
            saved = None
        if saved is None:
            java.appendPendingCall(self._durable_id)
        java.advanceCallIndexBy(1)
        self._slot = index
        if saved is not None and saved.status != "PENDING":
            self._complete(self._ctx._read_terminal_outcome(saved))
        else:
            try:
                call, reconciler = self._preparation()
                self._execution = (
                    reconciler if saved is not None and reconciler is not None else call
                )
            except BaseException as error:
                rethrow_fatal(error)
                outcome = Outcome.failure(error)
                self._record(outcome)
                self._complete(outcome)
        return super().prepare()

    def _record(self, outcome: Outcome) -> None:
        rethrow_fatal(outcome.error)
        result, error = self._ctx._serialize_call_payloads(outcome.value, outcome.error)
        self._ctx._j_runner_context.finalizeCallAt(
            self._slot, self._durable_id, result, error
        )

    @override
    def finish(self, timeout: TimeoutError | None = None) -> Outcome:
        if not self._is_done():
            outcome = self._outcome(timeout)
            if self._started:
                self._record(outcome)
            self._complete(outcome)
        return self._get_completed_outcome()


class MappedAsyncFuture(SchedulableFuture):
    """A view of an existing call, without another durable slot or worker."""

    def __init__(self, source: SchedulableFuture, mapper: Callable[[Any], Any]) -> None:
        """Keep a view of the source call without allocating execution state."""
        super().__init__(source._ctx)
        self._source = source
        self._mapper = mapper

    @property
    @override
    def call_identity(self) -> object:
        return self._source.call_identity

    @override
    def validate(self, ctx: Any) -> None:
        self._source.validate(ctx)

    def _convert(self, outcome: Outcome | None) -> Outcome | None:
        if outcome is not None and not self._is_done():
            if outcome.error is None:
                try:
                    outcome = Outcome.success(self._mapper(outcome.value))
                except BaseException as error:
                    rethrow_fatal(error)
                    outcome = Outcome.failure(error)
            self._complete(outcome)
        return self._get_completed_outcome() if self._is_done() else None

    @override
    def prepare(self) -> Outcome | None:
        return self._convert(self._source.prepare())

    @override
    def start(self) -> None:
        self._source.start()

    @override
    def ready(self) -> bool:
        return self._is_done() or self._source.ready()

    @override
    def finish(self, timeout: TimeoutError | None = None) -> Outcome:
        return self._convert(self._source.finish(timeout))

    @override
    def prevent_start(self) -> None:
        self._source.prevent_start()

    @override
    def cancel(self) -> None:
        self._source.cancel()

    @override
    def close(self) -> None:
        self._source.close()


def map_future(source: AsyncFuture, mapper: Callable) -> AsyncFuture:
    """Map a runtime-managed call without wrapping its execution in durability."""
    if not isinstance(source, SchedulableFuture):
        message = "Tool calls require a runtime schedulable future"
        raise TypeError(message)
    return MappedAsyncFuture(source, mapper)


def validate_futures(ctx: Any, futures: tuple) -> None:
    """Reject unsupported, foreign and duplicate calls before any side effect."""
    seen = set()
    for future in futures:
        if not isinstance(future, SchedulableFuture):
            message = "gather requires runtime schedulable futures"
            raise TypeError(message)
        future.validate(ctx)
        identity = id(future.call_identity)
        if identity in seen:
            message = "The same async future cannot appear more than once in gather"
            raise ValueError(message)
        seen.add(identity)


def resolve_calls(ctx: Any, futures: tuple, parallelism: int, timeout: Any) -> Any:
    """Reserve in input order, then run a sliding window and collect by input index."""
    validate_futures(ctx, futures)
    outcomes = [None] * len(futures)
    pending = []
    running = []
    seconds = positive_seconds(timeout) if timeout is not None else None
    deadline = time.monotonic() + seconds if seconds is not None else None
    failure = None
    try:
        for index, future in enumerate(futures):
            outcomes[index] = future.prepare()
            if outcomes[index] is None:
                pending.append(index)
        next_call = 0
        while running or next_call < len(pending):
            for index in tuple(running):
                if futures[index].ready():
                    outcomes[index] = futures[index].finish()
                    running.remove(index)
            if deadline is not None and time.monotonic() >= deadline:
                unfinished = [i for i in pending if outcomes[i] is None]
                for index in unfinished:
                    futures[index].prevent_start()
                for index in unfinished:
                    futures[index].cancel()
                error = TimeoutError(
                    f"Async batch execution timed out after {format_duration(timeout)}"
                )
                for index in unfinished:
                    future = futures[index]
                    outcomes[index] = future.finish(None if future.ready() else error)
                break
            while next_call < len(pending) and len(running) < max(parallelism, 1):
                index = pending[next_call]
                next_call += 1
                future = futures[index]
                future.start()
                if future.ready():
                    outcomes[index] = future.finish()
                else:
                    running.append(index)
            if running:
                yield
    except BaseException as error:
        failure = error
        for index, future in enumerate(futures):
            if outcomes[index] is None:
                _cleanup(future.prevent_start, failure)
        for index, future in enumerate(futures):
            if outcomes[index] is None:
                _cleanup(future.cancel, failure)
        raise
    finally:
        cleanup_failure = failure
        for future in futures:
            cleanup_failure = _cleanup(future.close, cleanup_failure)
        if failure is None and cleanup_failure is not None:
            raise cleanup_failure
    return outcomes


def _cleanup(
    operation: Callable[[], None], failure: BaseException | None
) -> BaseException | None:
    try:
        operation()
    except BaseException as error:
        if failure is None:
            return error
        _LOG.warning("Suppressed async-call cleanup failure", exc_info=error)
    return failure


class GatherAsyncFuture(AsyncFuture[list[Outcome]]):
    """Aggregate handle; it is not itself a schedulable child call."""

    def __init__(self, ctx: Any, futures: tuple) -> None:
        """Keep the input order without starting any child."""
        super().__init__()
        self._ctx = ctx
        self._futures = futures

    def __await__(self) -> Any:
        if not self._is_done():
            outcomes = yield from self._resolve()
            self._complete(Outcome.success(outcomes))
        return self._value

    def _resolve(self) -> Any:
        return (
            yield from resolve_calls(
                self._ctx,
                self._futures,
                self._ctx.config.get(AgentExecutionOptions.ASYNC_BATCH_PARALLELISM),
                self._ctx.config.get(AgentExecutionOptions.ASYNC_BATCH_TIMEOUT),
            )
        )
