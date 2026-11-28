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
from collections.abc import Callable
from types import SimpleNamespace
from typing import Any

import httpx
import pytest

from flink_agents.api.chat_message import (
    AudioBlock,
    TextBlock,
    UnsupportedContentBlockError,
    VideoBlock,
)
from flink_agents.api.media import (
    BaseTranscriptionModelSetup,
    MediaJob,
    MediaJobError,
    MediaJobState,
    MediaJobStatus,
    MediaResult,
)
from flink_agents.integrations.media_models.dashscope_media_model import (
    DashScopeMediaModelConnection,
    DashScopeTranscriptionModelSetup,
)

_FILE_URL = "https://example.test/audio.wav"
_RESULT_URL = "https://oss.example/result.json?Expires=1700000000"
_TRANSCRIPT = {
    "file_url": _FILE_URL,
    "transcripts": [
        {"channel_id": 0, "text": "hello"},
        {"channel_id": 1, "text": "world"},
    ],
}


@pytest.fixture
def model_factory(
    connection_factory: Callable[..., DashScopeMediaModelConnection],
    setup_factory: Callable[..., BaseTranscriptionModelSetup],
) -> Callable[..., BaseTranscriptionModelSetup]:
    def create(**overrides: Any) -> BaseTranscriptionModelSetup:
        request_timeout = overrides.pop("request_timeout", 60.0)
        connection = connection_factory(request_timeout=request_timeout)
        return setup_factory(
            DashScopeTranscriptionModelSetup,
            connection=connection,
            model="fun-asr",
            **overrides,
        )

    return create


def _audio(url: str = _FILE_URL) -> AudioBlock:
    return AudioBlock.from_url("audio/wav", url)


def _task_body(
    status: str,
    output: dict[str, Any] | None = None,
    **fields: Any,
) -> dict[str, Any]:
    payload = {"task_status": status, **(output or {})}
    body = {
        "request_id": "req-1",
        "usage": {"duration": 9},
        "output": payload,
    }
    body.update(fields)
    return body


def test_submit_sends_one_https_file_and_parameters(
    model_factory: Callable[..., BaseTranscriptionModelSetup], sdk_jobs: SimpleNamespace
) -> None:
    model = model_factory(
        channel_id=[1],
        diarization_enabled=True,
        speaker_count=2,
        additional_kwargs={"format": "wav"},
    )
    model._client().audio.asr.jobs = sdk_jobs

    job = model.submit(
        _audio(), model_parameters={"channel_id": [0], "language_hints": ["zh"]}
    )

    assert job.job_id == "job-1"
    assert job.expires_at_ms == job.submitted_at_ms + 24 * 60 * 60 * 1000
    assert sdk_jobs.created == [
        {
            "model": "fun-asr",
            "file_urls": [_FILE_URL],
            "parameters": {
                "channel_id": [0],
                "diarization_enabled": True,
                "speaker_count": 2,
                "format": "wav",
                "language_hints": ["zh"],
            },
        }
    ]
    assert model.model_kwargs == {
        "model": "fun-asr",
        "channel_id": [1],
        "diarization_enabled": True,
        "speaker_count": 2,
        "format": "wav",
    }


@pytest.mark.parametrize(
    ("recording", "error"),
    [
        (AudioBlock.from_base64("audio/wav", "YQ=="), UnsupportedContentBlockError),
        (_audio("oss://bucket/audio.wav"), UnsupportedContentBlockError),
    ],
)
def test_submit_rejects_unsupported_recording_sources(
    model_factory: Callable[..., BaseTranscriptionModelSetup],
    sdk_jobs: SimpleNamespace,
    recording: AudioBlock,
    error: type[Exception],
) -> None:
    model = model_factory()
    model._client().audio.asr.jobs = sdk_jobs

    with pytest.raises(error):
        model.submit(recording)

    assert sdk_jobs.created == []


def test_submit_accepts_a_video_recording(
    model_factory: Callable[..., BaseTranscriptionModelSetup], sdk_jobs: SimpleNamespace
) -> None:
    model = model_factory()
    model._client().audio.asr.jobs = sdk_jobs
    video_url = "https://example.test/recording.mp4"

    model.submit(VideoBlock.from_url("video/mp4", video_url))

    assert sdk_jobs.created[0]["file_urls"] == [video_url]


def test_fetch_downloads_transcription_without_dashscope_credentials(
    model_factory: Callable[..., BaseTranscriptionModelSetup],
    http_calls,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    model = model_factory(request_timeout=12)
    calls, responses = http_calls
    responses.append(
        httpx.Response(
            200,
            json=_task_body(
                "SUCCEEDED",
                {
                    "results": [
                        {
                            "subtask_status": "SUCCEEDED",
                            "transcription_url": _RESULT_URL,
                        }
                    ]
                },
            ),
        )
    )
    downloads: list[tuple[str, float]] = []

    def download(url: str, timeout: float) -> httpx.Response:
        downloads.append((url, timeout))
        return httpx.Response(200, json=_TRANSCRIPT, request=httpx.Request("GET", url))

    monkeypatch.setattr(
        "flink_agents.integrations.media_models.dashscope_media_model.httpx.get",
        download,
    )
    job = MediaJob(job_id="job-1", submitted_at_ms=0)

    state = model.fetch(job)

    [request] = calls
    assert request.method == "GET"
    assert request.url.path == "/api/v1/tasks/job-1"
    assert downloads == [(_RESULT_URL, 12)]
    assert state.status is MediaJobStatus.SUCCEEDED
    assert isinstance(state.result, MediaResult[TextBlock])
    restored = type(state).model_validate_json(state.model_dump_json())
    assert isinstance(restored.result, MediaResult[TextBlock])
    assert [block.text for block in state.result.content] == ["hello", "world"]
    assert state.result.metadata["transcription"] == _TRANSCRIPT
    assert state.result.usage == {"duration": 9}
    assert state.result.request_id == "req-1"
    assert state.result.expires_at_ms is None


def test_fetch_and_cancel_report_failed_transcription_subtask(
    model_factory: Callable[..., BaseTranscriptionModelSetup],
    http_calls,
) -> None:
    model = model_factory()
    failed_transcription = _task_body(
        "SUCCEEDED",
        {
            "results": [
                {
                    "subtask_status": "FAILED",
                    "code": "FILE_DOWNLOAD_FAILED",
                    "message": "FILE_DOWNLOAD_FAILED",
                }
            ]
        },
    )
    calls, responses = http_calls
    responses.extend(
        [
            httpx.Response(200, json=failed_transcription),
            httpx.Response(
                400,
                json={"code": "UnsupportedOperation", "message": "task has ended"},
            ),
            httpx.Response(200, json=failed_transcription),
        ]
    )
    job = MediaJob(job_id="job-1", submitted_at_ms=0)

    state = model.fetch(job)
    with pytest.raises(MediaJobError) as exc_info:
        model.cancel(job)

    assert isinstance(state, MediaJobState[MediaResult[TextBlock]])
    assert state.status is MediaJobStatus.FAILED
    assert state.result is None
    assert state.error_code == "FILE_DOWNLOAD_FAILED"
    assert state.error_message == "FILE_DOWNLOAD_FAILED"
    assert exc_info.value.status is state.status
    assert exc_info.value.code == "UnsupportedOperation"
    assert [(call.method, call.url.path) for call in calls] == [
        ("GET", "/api/v1/tasks/job-1"),
        ("POST", "/api/v1/tasks/job-1/cancel"),
        ("GET", "/api/v1/tasks/job-1"),
    ]


@pytest.mark.parametrize(
    "bad_url",
    ["file:///etc/passwd", "oss://bucket/result.json", "http:///missing-host"],
)
def test_fetch_rejects_non_http_transcription_result_url(
    model_factory: Callable[..., BaseTranscriptionModelSetup],
    http_calls,
    monkeypatch: pytest.MonkeyPatch,
    bad_url: str,
) -> None:
    model = model_factory()
    _, responses = http_calls
    responses.append(
        httpx.Response(
            200,
            json=_task_body(
                "SUCCEEDED",
                {
                    "results": [
                        {
                            "subtask_status": "SUCCEEDED",
                            "transcription_url": bad_url,
                        }
                    ]
                },
            ),
        )
    )

    def forbid_get(*_args: Any, **_kwargs: Any) -> httpx.Response:
        msg = "httpx.get must not run for invalid transcription result URLs"
        raise AssertionError(msg)

    monkeypatch.setattr(
        "flink_agents.integrations.media_models.dashscope_media_model.httpx.get",
        forbid_get,
    )
    job = MediaJob(job_id="job-1", submitted_at_ms=0)

    with pytest.raises(ValueError, match="http or https URL"):
        model.fetch(job)
