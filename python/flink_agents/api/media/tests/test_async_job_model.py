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
from collections.abc import Mapping, Sequence
from typing import Any

import pytest
from pydantic import Field

from flink_agents.api.chat_message import (
    AudioBlock,
    DocumentBlock,
    ImageBlock,
    VideoBlock,
)
from flink_agents.api.media import (
    DEFAULT_WAIT_TIMEOUT,
    AsyncJobModel,
    BaseImageGenerationModelSetup,
    BaseSpeechSynthesisModelSetup,
    BaseTranscriptionModelSetup,
    BaseVideoGenerationModelSetup,
    MediaJob,
    MediaJobError,
    MediaJobState,
    MediaJobStatus,
    MediaJobTimeoutError,
    MediaResult,
)
from flink_agents.api.resource import Resource

_JOB = MediaJob(
    job_id="job-1",
    submitted_at_ms=1_700_000_000_000,
    expires_at_ms=1_700_086_400_000,
)

_RESULT = MediaResult[VideoBlock](
    content=[VideoBlock.from_url("video/mp4", "https://example.test/out.mp4")],
    expires_at_ms=1_700_086_400_000,
    usage={"duration": 5},
    request_id="req-1",
    metadata={"task_metrics": {"total": 1}},
)


class FakeVideoModel(
    BaseVideoGenerationModelSetup, AsyncJobModel[MediaResult[VideoBlock]]
):
    """Returns a scripted sequence of observations from ``fetch``."""

    observations: list[MediaJobState[MediaResult[VideoBlock]]] = Field(
        default_factory=list
    )
    submissions: list[tuple[str, dict[str, Any]]] = Field(default_factory=list)

    def generate(
        self,
        prompt: str = "",
        *,
        media: Sequence[ImageBlock | AudioBlock | VideoBlock | DocumentBlock]
        | None = None,
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[VideoBlock]:
        return self._submit_and_wait(
            prompt, media=media, model_parameters=model_parameters, timeout=timeout
        )

    def submit(
        self,
        prompt: str = "",
        *,
        media: Sequence[ImageBlock | AudioBlock | VideoBlock | DocumentBlock]
        | None = None,
        model_parameters: Mapping[str, Any] | None = None,
    ) -> MediaJob:
        self.submissions.append(
            (prompt, {"media": media, "model_parameters": model_parameters})
        )
        return _JOB

    def fetch(self, job: MediaJob) -> MediaJobState[MediaResult[VideoBlock]]:
        return self.observations.pop(0)

    def cancel(self, job: MediaJob) -> None:
        pass


def _state(
    status: MediaJobStatus,
    *,
    result: MediaResult[VideoBlock] | None = None,
    error_code: str | None = None,
    error_message: str | None = None,
) -> MediaJobState[MediaResult[VideoBlock]]:
    return MediaJobState[MediaResult[VideoBlock]](
        job=_JOB,
        status=status,
        result=result,
        error_code=error_code,
        error_message=error_message,
    )


def _model(
    observations: list[MediaJobState[MediaResult[VideoBlock]]],
) -> FakeVideoModel:
    return FakeVideoModel(
        connection="fake-conn",
        model="fake-model",
        observations=observations,
    )


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
    monkeypatch.setattr("flink_agents.api.media.async_job_model.time", clock)
    return clock


def test_capability_base_classes_are_orthogonal_to_async_job_model() -> None:
    assert not issubclass(AsyncJobModel, Resource)
    for capability in (
        BaseSpeechSynthesisModelSetup,
        BaseTranscriptionModelSetup,
        BaseImageGenerationModelSetup,
        BaseVideoGenerationModelSetup,
    ):
        assert not issubclass(capability, AsyncJobModel)


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
        ]
    )

    def change_job(*_args: Any, **_kwargs: Any) -> None:
        pytest.fail("wait must not submit or cancel a job")

    monkeypatch.setattr(FakeVideoModel, "submit", change_job)
    monkeypatch.setattr(FakeVideoModel, "cancel", change_job)

    with pytest.raises(MediaJobTimeoutError) as exc_info:
        model.wait(_JOB, timeout=5.0, poll_interval=10.0)

    assert exc_info.value.timeout == 5.0
    assert exc_info.value.job == _JOB
    assert exc_info.value.status is MediaJobStatus.PENDING
    assert clock.now == 5.0
    assert model.wait(_JOB) == _RESULT


def test_wait_returns_result_from_a_fetch_started_before_deadline(
    clock: _Clock, monkeypatch: pytest.MonkeyPatch
) -> None:
    model = _model([_state(MediaJobStatus.SUCCEEDED, result=_RESULT)])
    fetch = FakeVideoModel.fetch

    def slow_fetch(self: FakeVideoModel, job: MediaJob) -> MediaJobState:
        clock.sleep(6.0)
        return fetch(self, job)

    monkeypatch.setattr(FakeVideoModel, "fetch", slow_fetch)

    assert model.wait(_JOB, timeout=5.0) == _RESULT


def test_wait_without_timeout_polls_until_terminal(clock: _Clock) -> None:
    model = _model(
        [
            _state(MediaJobStatus.PENDING),
            _state(MediaJobStatus.SUCCEEDED, result=_RESULT),
        ]
    )
    assert model.wait(_JOB, timeout=None, poll_interval=2.5) == _RESULT
    assert clock.now == 2.5


@pytest.mark.parametrize(
    ("timeout", "error_type", "pattern"),
    [
        (float("nan"), ValueError, "wait timeout must be finite"),
        (float("inf"), ValueError, "wait timeout must be finite"),
        (0, ValueError, "greater than 0"),
        (-1.0, ValueError, "greater than 0"),
        (True, TypeError, "positive finite number"),
    ],
)
def test_wait_rejects_invalid_timeout_before_querying(
    timeout: Any, error_type: type[Exception], pattern: str
) -> None:
    observation = _state(MediaJobStatus.SUCCEEDED, result=_RESULT)
    model = _model([observation])

    with pytest.raises(error_type, match=pattern):
        model.wait(_JOB, timeout=timeout)

    assert model.observations == [observation]


@pytest.mark.parametrize(
    ("poll_interval", "error_type", "pattern"),
    [
        (0, ValueError, "greater than 0"),
        (-1.0, ValueError, "greater than 0"),
        (float("nan"), ValueError, "poll interval must be finite"),
        (float("inf"), ValueError, "poll interval must be finite"),
        (True, TypeError, "positive finite number"),
    ],
)
def test_wait_rejects_invalid_poll_interval_before_querying(
    poll_interval: Any, error_type: type[Exception], pattern: str
) -> None:
    observation = _state(MediaJobStatus.SUCCEEDED, result=_RESULT)
    model = _model([observation])

    with pytest.raises(error_type, match=pattern):
        model.wait(_JOB, poll_interval=poll_interval)

    assert model.observations == [observation]


@pytest.mark.usefixtures("clock")
def test_generate_submits_inputs_once_and_waits() -> None:
    model = _model(
        [
            _state(MediaJobStatus.PENDING),
            _state(MediaJobStatus.SUCCEEDED, result=_RESULT),
        ]
    )
    media = [ImageBlock.from_url("image/png", "https://example.test/cat.png")]

    assert (
        model.generate(
            "A cat",
            media=media,
            model_parameters={"resolution": "720P", "duration": 3},
            timeout=10,
        )
        is _RESULT
    )
    assert model.submissions == [
        (
            "A cat",
            {"media": media, "model_parameters": {"resolution": "720P", "duration": 3}},
        )
    ]


@pytest.mark.parametrize(
    ("timeout", "error_type", "pattern"),
    [
        (float("nan"), ValueError, "wait timeout must be finite"),
        (float("inf"), ValueError, "wait timeout must be finite"),
        (0, ValueError, "greater than 0"),
        (-1.0, ValueError, "greater than 0"),
        (False, TypeError, "positive finite number"),
    ],
)
def test_generate_rejects_invalid_wait_budget_before_submitting(
    timeout: Any, error_type: type[Exception], pattern: str
) -> None:
    model = _model([])

    with pytest.raises(error_type, match=pattern):
        model.generate("A cat", timeout=timeout)

    assert model.submissions == []


def test_generate_uses_default_wait_timeout_when_timeout_is_none(
    clock: _Clock, monkeypatch: pytest.MonkeyPatch
) -> None:
    model = _model([])
    monkeypatch.setattr(
        FakeVideoModel,
        "fetch",
        lambda _self, _job: _state(MediaJobStatus.PENDING),
    )

    with pytest.raises(MediaJobTimeoutError) as exc_info:
        model.generate("A cat")

    assert exc_info.value.timeout == DEFAULT_WAIT_TIMEOUT
    assert clock.now == DEFAULT_WAIT_TIMEOUT
