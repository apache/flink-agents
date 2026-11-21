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
from collections.abc import Mapping, Sequence
from typing import Any

import pytest
from pydantic import Field

from flink_agents.api.chat_message import (
    AudioBlock,
    DocumentBlock,
    ImageBlock,
    TextBlock,
    VideoBlock,
)
from flink_agents.api.media.media_model import (
    AsyncJobModel,
    ImageGenerationModel,
    MediaJob,
    MediaJobError,
    MediaJobState,
    MediaJobStatus,
    MediaJobTimeoutError,
    MediaResult,
    SpeechSynthesisModel,
    TranscriptionModel,
    VideoGenerationModel,
)
from flink_agents.api.media.media_model_provider import BaseMediaModelProvider
from flink_agents.api.resource import ResourceType

_JOB = MediaJob(
    provider="FakeVideoModel",
    model="fake-model",
    task="video_generation",
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


class FakeVideoModel(VideoGenerationModel, AsyncJobModel[MediaResult[VideoBlock]]):
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
        self._check_job(job)
        return self.observations.pop(0)

    def cancel(self, job: MediaJob) -> None:
        self._check_job(job)


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
    observations: list[MediaJobState[MediaResult[VideoBlock]]], **overrides: Any
) -> FakeVideoModel:
    values: dict[str, Any] = {
        "model": "fake-model",
        "observations": observations,
    }
    values.update(overrides)
    return FakeVideoModel(**values)


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
    monkeypatch.setattr("flink_agents.api.media.media_model.time", clock)
    return clock


def test_provider_resource_type_and_defaults() -> None:
    assert BaseMediaModelProvider.resource_type() is ResourceType.MEDIA_MODEL_PROVIDER
    assert ResourceType.MEDIA_MODEL_PROVIDER.value == "media_model_provider"
    provider = BaseMediaModelProvider()
    for method in (
        provider.speech,
        provider.transcription,
        provider.image,
        provider.video,
    ):
        with pytest.raises(NotImplementedError):
            method("model")


def test_capability_base_classes_are_orthogonal_to_async_job_model() -> None:
    for capability in (
        SpeechSynthesisModel,
        TranscriptionModel,
        ImageGenerationModel,
        VideoGenerationModel,
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
    fetch = FakeVideoModel.fetch

    def slow_fetch(self: FakeVideoModel, job: MediaJob) -> MediaJobState:
        clock.sleep(6.0)
        return fetch(self, job)

    monkeypatch.setattr(FakeVideoModel, "fetch", slow_fetch)

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
    "identity",
    [{"provider": "other"}, {"model": "other"}, {"task": "transcription"}],
)
def test_wait_rejects_job_from_another_model(identity: dict[str, Any]) -> None:
    model = _model([])
    foreign = _JOB.model_copy(update=identity)

    with pytest.raises(ValueError, match="belongs to provider"):
        model.wait(foreign)


@pytest.mark.parametrize(
    ("timeout", "error_type", "pattern"),
    [
        (float("nan"), ValueError, "wait timeout must be finite"),
        (float("inf"), ValueError, "wait timeout must be finite"),
        (-1.0, ValueError, "non-negative"),
        (True, TypeError, "finite number or None"),
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


@pytest.mark.parametrize(
    "task",
    ["video_generation", "image_generation", "transcription", "speech_synthesis"],
)
def test_media_job_json_roundtrip(task: str) -> None:
    job = MediaJob(**{**_JOB.model_dump(), "task": task})
    restored = MediaJob.model_validate_json(job.model_dump_json())

    assert restored == job
    assert type(restored.task) is str
    payload = json.loads(job.model_dump_json())
    assert payload["task"] == task
    assert set(payload) == {
        "provider",
        "model",
        "task",
        "job_id",
        "submitted_at_ms",
        "expires_at_ms",
    }


@pytest.mark.parametrize("from_json", [False, True])
@pytest.mark.parametrize("task", ["unknown", ""])
def test_media_job_rejects_invalid_task(from_json: bool, task: str) -> None:
    payload = {**_JOB.model_dump(), "task": task}
    validate = MediaJob.model_validate_json if from_json else MediaJob.model_validate
    value = json.dumps(payload) if from_json else payload
    with pytest.raises(ValueError, match="Invalid media task"):
        validate(value)


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
        (False, TypeError, "finite number or None"),
    ],
)
def test_generate_rejects_invalid_wait_budget_before_submitting(
    timeout: Any, error_type: type[Exception], pattern: str
) -> None:
    model = _model([])

    with pytest.raises(error_type, match=pattern):
        model.generate("A cat", timeout=timeout)

    assert model.submissions == []


def test_capability_identity_is_not_a_configuration_field() -> None:
    model = _model([])
    assert not hasattr(model, "task")
    assert "_task" not in model.model_dump()
    with pytest.raises(ValueError, match="Extra inputs are not permitted"):
        _model([], _task="transcription")


class FakeSpeechSynthesisModel(SpeechSynthesisModel):
    def synthesize(
        self,
        text: str,
        *,
        voice: str | None = None,
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[AudioBlock]:
        return MediaResult[AudioBlock](
            content=[
                AudioBlock.from_url("audio/wav", "https://example.test/speech.wav")
            ],
            metadata={"text": text, "timeout": timeout},
        )


class FakeSyncImageGenerationModel(ImageGenerationModel):
    def generate(
        self,
        prompt: str,
        *,
        images: Sequence[ImageBlock] | None = None,
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[ImageBlock]:
        return MediaResult[ImageBlock](
            content=[ImageBlock.from_url("image/png", "https://example.test/img.png")],
            metadata={"prompt": prompt},
        )


def test_speech_synthesis_has_no_job_operations() -> None:
    model = FakeSpeechSynthesisModel(model="fake-speech")
    result = model.synthesize("Hello", timeout=10)

    restored = MediaResult[AudioBlock].model_validate_json(result.model_dump_json())
    assert restored == result
    assert isinstance(restored.content[0], AudioBlock)
    assert result.metadata == {"text": "Hello", "timeout": 10}
    with pytest.raises(ValueError):
        MediaResult[AudioBlock](content=[TextBlock(text="not audio")])
    assert not isinstance(model, AsyncJobModel)
    assert not any(
        hasattr(model, name) for name in ("submit", "fetch", "wait", "cancel")
    )


def test_sync_image_generation_is_not_async_job_model() -> None:
    model = FakeSyncImageGenerationModel(model="fake-sync-image")
    result = model.generate("A notebook.")

    assert isinstance(result, MediaResult[ImageBlock])
    assert not isinstance(model, AsyncJobModel)
    assert not any(
        hasattr(model, name) for name in ("submit", "fetch", "wait", "cancel")
    )


@pytest.mark.parametrize(
    ("result_type", "block"),
    [
        (MediaResult[TextBlock], TextBlock(text="hello")),
        (
            MediaResult[ImageBlock],
            ImageBlock.from_url("image/png", "https://example.test/a.png"),
        ),
        (
            MediaResult[VideoBlock],
            VideoBlock.from_url("video/mp4", "https://example.test/a.mp4"),
        ),
    ],
)
def test_job_state_deserialization_preserves_the_result_type(
    result_type: type[MediaResult], block: Any
) -> None:
    result = result_type(content=[block], usage={"count": 1})
    state_type = MediaJobState[result_type]
    state = state_type(job=_JOB, status=MediaJobStatus.SUCCEEDED, result=result)

    restored = state_type.model_validate_json(state.model_dump_json())

    assert type(restored.result) is result_type
    assert type(restored.result.content[0]) is type(block)
    assert restored == state
    wrong_block = ImageBlock.from_url("image/png", "https://example.test/wrong.png")
    if isinstance(block, ImageBlock):
        wrong_block = TextBlock(text="wrong")
    with pytest.raises(ValueError):
        result_type(content=[wrong_block])
    payload = state.model_dump(mode="json")
    payload["result"]["content"] = [wrong_block.model_dump(mode="json")]
    with pytest.raises(ValueError):
        state_type.model_validate(payload)
