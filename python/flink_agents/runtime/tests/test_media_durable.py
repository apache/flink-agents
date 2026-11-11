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
#  limitations under the License.
#################################################################################
from typing import Any, Sequence

import cloudpickle
import pytest
from pydantic import Field

from flink_agents.api.chat_message import DataContentBlock, TextBlock
from flink_agents.api.media_models.media_model import (
    BaseMediaModel,
    MediaJob,
    MediaJobState,
    MediaJobStatus,
    MediaResult,
    MediaTask,
)
from flink_agents.runtime.durable_exception import is_java_interruption
from flink_agents.runtime.flink_runner_context import FlinkRunnerContext
from flink_agents.runtime.tests.test_durable_exception import (
    _INTERRUPTED,
    _java_error,
)
from flink_agents.runtime.tests.test_flink_runner_context_reconcilable import (
    _close_runner_context,
    _create_runner_context,
    _FakeJavaRunnerContext,
    _run_async,
)


class _Calls:
    """Counters and the jobs observed by one scripted model."""

    def __init__(self) -> None:
        self.submits = 0
        self.jobs: list[MediaJob] = []
        self.pending_returned = False
        self.interrupted = False


class _RecoverableMediaModel(BaseMediaModel):
    """Submits one job, then reports pending, an interruption, and success."""

    calls: _Calls = Field(default_factory=_Calls)

    def generate(
        self,
        content: Sequence[DataContentBlock],
        *,
        timeout: float | None = None,
        **kwargs: Any,
    ) -> MediaResult:
        raise NotImplementedError

    def supports_async_task(self) -> bool:
        return True

    def submit(self, content: Sequence[DataContentBlock], **kwargs: Any) -> MediaJob:
        self.calls.submits += 1
        return MediaJob(
            provider=self._provider_name(),
            model=self.model,
            task=self.task,
            job_id="job-1",
            submitted_at_ms=1,
        )

    def fetch(self, job: MediaJob) -> MediaJobState:
        self.calls.jobs.append(job)
        if not self.calls.pending_returned:
            self.calls.pending_returned = True
            return MediaJobState(job=job, status=MediaJobStatus.PENDING)
        if not self.calls.interrupted:
            self.calls.interrupted = True
            raise _java_error(_INTERRUPTED)
        return MediaJobState(
            job=job,
            status=MediaJobStatus.SUCCEEDED,
            result=MediaResult(content=[TextBlock(text="done")]),
        )

    def cancel(self, job: MediaJob) -> None:
        raise NotImplementedError


class _Clock:
    def __init__(self) -> None:
        self.now = 0.0

    def monotonic(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.now += seconds


def _execute(ctx: FlinkRunnerContext, asynchronous: bool, func: Any, *args: Any) -> Any:
    if asynchronous:
        return _run_async(ctx.durable_execute_async(func, *args))
    return ctx.durable_execute(func, *args)


@pytest.mark.parametrize("asynchronous", [False, True])
def test_interrupted_wait_replays_the_submitted_job(
    asynchronous: bool, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A stored submit is replayed after wait is interrupted on the same job."""
    monkeypatch.setattr(
        "flink_agents.api.media_models.media_model.time",
        _Clock(),
    )
    model = _RecoverableMediaModel(
        model="wan2.7-i2v",
        task=MediaTask.VIDEO_GENERATION,
    )

    def submit_job() -> MediaJob:
        return model.submit([TextBlock(text="a cat")])

    def wait_job(job: MediaJob) -> MediaResult:
        return model.wait(job)

    store = _FakeJavaRunnerContext()
    ctx = _create_runner_context(store)
    try:
        job = _execute(ctx, asynchronous, submit_job)
        with pytest.raises(RuntimeError) as raised:
            _execute(ctx, asynchronous, wait_job, job)
    finally:
        _close_runner_context(ctx)

    assert is_java_interruption(raised.value)
    assert model.calls.submits == 1
    assert [seen.job_id for seen in model.calls.jobs] == ["job-1", "job-1"]
    assert [slot.status for slot in store.call_results] == ["SUCCEEDED"]
    assert store.call_results[0].exception_payload is None
    stored_job = cloudpickle.loads(store.call_results[0].result_payload)
    assert stored_job == job
    assert store.current_call_index == 1

    store.current_call_index = 0
    ctx = _create_runner_context(store)
    try:
        recovered = _execute(ctx, asynchronous, submit_job)
        result = _execute(ctx, asynchronous, wait_job, recovered)
    finally:
        _close_runner_context(ctx)

    assert model.calls.submits == 1
    assert recovered == job
    assert recovered is not job
    assert model.calls.jobs[2] is recovered
    assert result == MediaResult(content=[TextBlock(text="done")])
    assert [slot.status for slot in store.call_results] == ["SUCCEEDED", "SUCCEEDED"]
    stored_result = cloudpickle.loads(store.call_results[1].result_payload)
    assert stored_result == result
