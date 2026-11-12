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
import json
import pickle
from typing import Any, Sequence

import pytest
from pydantic import Field

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import DataContentBlock, TextBlock, VideoBlock
from flink_agents.api.decorators import media_model
from flink_agents.api.media_models.media_model import (
    BaseMediaModel,
    MediaJob,
    MediaJobError,
    MediaJobState,
    MediaJobStatus,
    MediaJobTimeoutError,
    MediaResult,
    MediaTask,
)
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration

_JOB = MediaJob(
    provider="FakeMediaModel",
    model="fake-model",
    task=MediaTask.VIDEO_GENERATION,
    job_id="job-1",
    submitted_at_ms=1_700_000_000_000,
    expires_at_ms=1_700_086_400_000,
)

_RESULT = MediaResult(
    content=[VideoBlock.from_url("video/mp4", "https://example.test/out.mp4")],
    expires_at_ms=1_700_086_400_000,
    usage={"duration": 5},
    request_id="req-1",
    metadata={"task_metrics": {"total": 1}},
)


class FakeMediaModel(BaseMediaModel):
    """Returns a scripted sequence of observations from ``fetch``."""

    observations: list[MediaJobState] = Field(default_factory=list)

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
        return _JOB

    def fetch(self, job: MediaJob) -> MediaJobState:
        self._check_job(job)
        return self.observations.pop(0)

    def cancel(self, job: MediaJob) -> None:
        self._check_job(job)


def _state(
    status: MediaJobStatus,
    *,
    result: MediaResult | None = None,
    error_code: str | None = None,
    error_message: str | None = None,
) -> MediaJobState:
    return MediaJobState(
        job=_JOB,
        status=status,
        result=result,
        error_code=error_code,
        error_message=error_message,
    )


def _model(observations: list[MediaJobState], **overrides: Any) -> FakeMediaModel:
    values: dict[str, Any] = {
        "model": "fake-model",
        "task": MediaTask.VIDEO_GENERATION,
        "poll_interval": 5.0,
        "observations": observations,
    }
    values.update(overrides)
    return FakeMediaModel(**values)


class _Clock:
    def __init__(self) -> None:
        self.now = 0.0

    def monotonic(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.now += seconds


@pytest.fixture
def clock(monkeypatch: pytest.MonkeyPatch) -> _Clock:
    clock = _Clock()
    monkeypatch.setattr("flink_agents.api.media_models.media_model.time", clock)
    return clock


class AgentWithMediaModel(Agent):
    """Declares one media model through the decorator."""

    @media_model
    @staticmethod
    def video() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=(
                "flink_agents.api.media_models.tests.test_media_model.FakeMediaModel"
            ),
            model="fake-model",
            task="video_generation",
        )


def test_media_model_decorator_is_registered() -> None:
    plan = AgentPlan.from_agent(AgentWithMediaModel(), AgentConfiguration())

    provider = plan.resource_providers[ResourceType.MEDIA_MODEL]["video"]
    assert provider.descriptor.arguments["model"] == "fake-model"
    assert provider.descriptor.arguments["task"] == "video_generation"


def test_resource_type() -> None:
    assert FakeMediaModel.resource_type() is ResourceType.MEDIA_MODEL
    assert ResourceType.MEDIA_MODEL.value == "media_model"


@pytest.mark.usefixtures("clock")
def test_wait_returns_result_after_pending() -> None:
    model = _model(
        [
            _state(MediaJobStatus.PENDING),
            _state(MediaJobStatus.RUNNING),
            _state(MediaJobStatus.SUCCEEDED, result=_RESULT),
        ]
    )
    assert model.wait(_JOB) == _RESULT


def test_wait_failed_raises_with_provider_error() -> None:
    model = _model(
        [
            _state(
                MediaJobStatus.FAILED,
                error_code="InvalidParameter",
                error_message="prompt is empty",
            )
        ]
    )

    with pytest.raises(MediaJobError) as exc_info:
        model.wait(_JOB)

    error = exc_info.value
    assert error.status is MediaJobStatus.FAILED
    assert error.code == "InvalidParameter"
    assert error.message == "prompt is empty"
    assert error.job == _JOB


@pytest.mark.parametrize("status", [MediaJobStatus.CANCELED, MediaJobStatus.UNKNOWN])
def test_wait_terminal_status_raises(status: MediaJobStatus) -> None:
    model = _model([_state(status)])

    with pytest.raises(MediaJobError) as exc_info:
        model.wait(_JOB)

    assert exc_info.value.status is status


def test_wait_succeeded_without_result_raises() -> None:
    model = _model([_state(MediaJobStatus.SUCCEEDED)])

    with pytest.raises(MediaJobError, match="without a result"):
        model.wait(_JOB)


def test_wait_timeout_preserves_the_last_observed_status(
    clock: _Clock, monkeypatch: pytest.MonkeyPatch
) -> None:
    model = _model(
        [
            _state(MediaJobStatus.PENDING),
            _state(MediaJobStatus.SUCCEEDED, result=_RESULT),
        ],
        poll_interval=10.0,
    )

    def change_job(*_args: Any, **_kwargs: Any) -> None:
        pytest.fail("wait must not submit or cancel a job")

    monkeypatch.setattr(FakeMediaModel, "submit", change_job)
    monkeypatch.setattr(FakeMediaModel, "cancel", change_job)

    with pytest.raises(MediaJobTimeoutError) as exc_info:
        model.wait(_JOB, timeout=5.0)

    assert exc_info.value.timeout == 5.0
    assert exc_info.value.job == _JOB
    assert exc_info.value.status is MediaJobStatus.PENDING
    assert clock.now == 5.0
    assert model.wait(_JOB) == _RESULT


def test_wait_with_no_remaining_budget_does_not_fetch() -> None:
    observation = _state(MediaJobStatus.PENDING)
    model = _model([observation])

    with pytest.raises(MediaJobTimeoutError) as exc_info:
        model.wait(_JOB, timeout=0)

    assert exc_info.value.status is MediaJobStatus.UNKNOWN
    assert model.observations == [observation]


def test_wait_returns_result_from_a_fetch_started_before_deadline(
    clock: _Clock, monkeypatch: pytest.MonkeyPatch
) -> None:
    model = _model([_state(MediaJobStatus.SUCCEEDED, result=_RESULT)])
    fetch = FakeMediaModel.fetch

    def slow_fetch(self: FakeMediaModel, job: MediaJob) -> MediaJobState:
        clock.sleep(6.0)
        return fetch(self, job)

    monkeypatch.setattr(FakeMediaModel, "fetch", slow_fetch)

    assert model.wait(_JOB, timeout=5.0) == _RESULT


@pytest.mark.parametrize(
    "error",
    [
        MediaJobError(_JOB, MediaJobStatus.FAILED, "BadRequest", "invalid prompt"),
        MediaJobTimeoutError(_JOB, 5.0, MediaJobStatus.PENDING),
    ],
    ids=["job-error", "timeout"],
)
def test_media_job_exception_pickle_roundtrip(error: MediaJobError) -> None:
    restored = pickle.loads(pickle.dumps(error))

    assert type(restored) is type(error)
    assert vars(restored) == vars(error)
    assert str(restored) == str(error)


@pytest.mark.usefixtures("clock")
def test_wait_without_timeout_polls_until_terminal() -> None:
    model = _model(
        [
            _state(MediaJobStatus.PENDING),
            _state(MediaJobStatus.SUCCEEDED, result=_RESULT),
        ],
        wait_timeout=None,
    )
    assert model.wait(_JOB) == _RESULT


def test_wait_rejects_job_from_another_resource() -> None:
    model = _model([])
    foreign = _JOB.model_copy(update={"provider": "other"})

    with pytest.raises(ValueError, match="belongs to provider"):
        model.wait(foreign)


def test_poll_interval_must_be_positive() -> None:
    with pytest.raises(ValueError, match="greater than 0"):
        _model([], poll_interval=0)


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("wait_timeout", float("nan")),
        ("wait_timeout", float("inf")),
        ("poll_interval", float("inf")),
    ],
)
def test_polling_configuration_requires_finite_values(field: str, value: float) -> None:
    with pytest.raises(ValueError, match="finite"):
        _model([], **{field: value})


def test_wait_rejects_non_finite_timeout_before_querying() -> None:
    observation = _state(MediaJobStatus.SUCCEEDED, result=_RESULT)
    model = _model([observation])

    with pytest.raises(ValueError, match="wait timeout must be finite"):
        model.wait(_JOB, timeout=float("nan"))

    assert model.observations == [observation]


def test_media_job_json_roundtrip() -> None:
    restored = MediaJob.model_validate_json(_JOB.model_dump_json())

    assert restored == _JOB
    payload = json.loads(_JOB.model_dump_json())
    assert payload["task"] == "video_generation"
    assert set(payload) == {
        "provider",
        "model",
        "task",
        "job_id",
        "submitted_at_ms",
        "expires_at_ms",
    }


def test_media_result_json_roundtrip_keeps_block_types() -> None:
    result = MediaResult(
        content=[
            TextBlock(text="hello"),
            VideoBlock.from_url("video/mp4", "https://example.test/out.mp4"),
        ],
        usage={"total_tokens": 12, "ratio": 0.5, "unit": "seconds"},
    )

    restored = MediaResult.model_validate_json(result.model_dump_json())

    assert restored == result
    assert isinstance(restored.content[0], TextBlock)
    assert isinstance(restored.content[1], VideoBlock)


def test_media_job_state_json_roundtrip() -> None:
    state = _state(
        MediaJobStatus.FAILED, error_code="BadRequest", error_message="no prompt"
    )

    restored = MediaJobState.model_validate_json(state.model_dump_json())

    assert restored == state
    assert restored.result is None
