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
"""Internal batch admission, scheduling and durable replay without worker waits."""

from copy import deepcopy
from typing import Any

import pytest

from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.runtime.deferred_subagent import DeferredSubagentFuture
from flink_agents.runtime.internal_subagent_call import InternalSubagentCall
from flink_agents.runtime.tests.test_base_subagent import _run
from flink_agents.runtime.tests.test_flink_runner_context_reconcilable import (
    _close_runner_context,
    _create_runner_context,
    _FakeJavaRunnerContext,
)


class _InternalStore(_FakeJavaRunnerContext):
    def __init__(self) -> None:
        super().__init__()
        self.started: list[str] = []
        self.completed: set[str] = set()
        self.failed: set[str] = set()
        self.outputs: dict[str, Any] = {}

    def bootstrapSubagentCallForScope(
        self, scope: str, session: str, call: str, prompt: Any
    ) -> None:
        assert any(
            slot.function_id == f"{session}#{call}" for slot in self.call_results
        )
        self.started.append(call)
        self.outputs[call] = f"{scope}:{prompt}"

    def isSubagentCallDone(self, session: str, call: str) -> bool:
        assert call in self.started
        return call in self.completed

    def awaitSubagentCall(self, session: str, call: str) -> list:
        assert call in self.completed
        if call in self.failed:
            msg = f"child failed: {call}"
            raise RuntimeError(msg)
        return [self.outputs[call]]

    def getSubagentCallError(self, session: str, call: str) -> str | None:
        return f"child failed: {call}" if call in self.failed else None


def _futures(ctx: Any, size: int) -> list[DeferredSubagentFuture]:
    return [
        DeferredSubagentFuture(
            "session",
            str(i),
            ctx,
            lambda i=i: (
                f"session#{i}",
                InternalSubagentCall(ctx, f"child-{i % 2}", "session", str(i), i),
                None,
            ),
        )
        for i in range(size)
    ]


def _finish(generator: Any, store: _InternalStore) -> Any:
    for _ in range(100):
        store.completed.update(store.started)
        try:
            next(generator)
        except StopIteration as result:
            return result.value
    pytest.fail("batch did not finish")


def test_internal_batch_reserves_queued_calls_and_replays_partial_results(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A crash preserves a completed slot and replays only running/queued calls."""
    store = _InternalStore()
    config = AgentConfiguration({"subagent.parallelism": 2})
    ctx = _create_runner_context(store, config, executor_workers=1)

    def reject_worker(*args: Any, **kwargs: Any) -> None:
        pytest.fail("internal waits must not occupy the shared worker pool")

    monkeypatch.setattr(ctx.executor, "submit", reject_worker)
    futures = _futures(ctx, 5)
    batch = futures[0].combine(*futures[1:]).__await__()
    try:
        next(batch)
        assert store.started == ["0", "1"]
        assert [slot.status for slot in store.call_results] == ["PENDING"] * 5
        store.completed.add("0")
        next(batch)
        assert store.started == ["0", "1", "2"]
        assert [slot.status for slot in store.call_results] == ["SUCCEEDED"] + [
            "PENDING"
        ] * 4
        recovered = _InternalStore()
        recovered.call_results = deepcopy(store.call_results)
    finally:
        batch.close()
        _close_runner_context(ctx)

    restored_ctx = _create_runner_context(recovered, config, executor_workers=1)
    monkeypatch.setattr(restored_ctx.executor, "submit", reject_worker)
    recovered.failed.add("3")
    restored = _futures(restored_ctx, 5)
    replay = restored[0].combine(*restored[1:]).__await__()
    try:
        next(replay)
        assert recovered.started == ["1", "2"]
        results = _finish(replay, recovered)
        assert recovered.started == ["1", "2", "3", "4"]
        assert [result.success for result in results] == [True, True, True, False, True]
        assert results[0].result == ["child-0:0"]
        assert results[4].result == ["child-0:4"]
        assert all(future.done() for future in restored)
        assert all(slot.status == "SUCCEEDED" for slot in recovered.call_results)
        assert recovered.current_call_index == 5
    finally:
        replay.close()
        _close_runner_context(restored_ctx)


@pytest.mark.parametrize(
    "config",
    [
        {"subagent.max-batch-size": 2},
        {"subagent.max-batch-size": 0},
        {"subagent.parallelism": 0},
    ],
)
def test_invalid_or_oversized_batch_is_rejected_before_reservation(
    config: dict,
) -> None:
    """Admission cannot start a child or allocate durable slots."""
    store = _InternalStore()
    ctx = _create_runner_context(store, AgentConfiguration(config))
    futures = _futures(ctx, 3)
    try:
        with pytest.raises(ValueError):
            _run(futures[0].combine(*futures[1:]))
        assert store.operations == []
        assert store.started == []
    finally:
        _close_runner_context(ctx)


def test_duplicate_and_cross_context_handles_are_rejected_before_prepare() -> None:
    """Invalid composition has no dispatch or durable side effects."""
    contexts = [_create_runner_context(_InternalStore()) for _ in range(2)]
    first, second = (_futures(ctx, 1)[0] for ctx in contexts)
    try:
        with pytest.raises(ValueError, match="same sub-agent future"):
            _run(first.combine(first))
        with pytest.raises(ValueError, match="same runner context"):
            _run(first.combine(second))
        assert first._prepared is None
        assert second._prepared is None
    finally:
        for ctx in contexts:
            _close_runner_context(ctx)


@pytest.mark.parametrize(
    "operation", ["bootstrapSubagentCallForScope", "isSubagentCallDone"]
)
def test_internal_runtime_failure_leaves_the_slot_pending(
    monkeypatch: pytest.MonkeyPatch,
    operation: str,
) -> None:
    """Infrastructure errors propagate instead of becoming durable child results."""
    store = _InternalStore()
    ctx = _create_runner_context(store)

    def fail(*args: Any) -> None:
        msg = "mailbox failure"
        raise RuntimeError(msg)

    monkeypatch.setattr(store, operation, fail)
    future = _futures(ctx, 1)[0]
    try:
        with pytest.raises(RuntimeError, match="mailbox failure"):
            _run(future)
        assert store.call_results[0].status == "PENDING"
        assert not future.done()
    finally:
        _close_runner_context(ctx)
