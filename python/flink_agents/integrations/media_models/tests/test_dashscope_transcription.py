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
)
from flink_agents.api.media_models.media_model import (
    MediaJobError,
    MediaJobStatus,
    MediaTask,
)
from flink_agents.integrations.media_models.dashscope_media_model import (
    DashScopeMediaModel,
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


def _model(**overrides: Any) -> DashScopeMediaModel:
    values: dict[str, Any] = {
        "model": "fun-asr",
        "task": MediaTask.TRANSCRIPTION,
        "api_key": "sk-test",
        "workspace": "ws-1",
    }
    values.update(overrides)
    return DashScopeMediaModel(**values)


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


def test_submit_sends_one_https_file_and_parameters(sdk_jobs: SimpleNamespace) -> None:
    model = _model(parameters={"channel_id": [1], "format": "wav"})
    model._client().audio.asr.jobs = sdk_jobs

    job = model.submit([_audio()], channel_id=[0], language_hints=["zh"])

    assert job.job_id == "job-1"
    assert job.provider == "dashscope"
    assert job.task is MediaTask.TRANSCRIPTION
    assert job.expires_at_ms == job.submitted_at_ms + 24 * 60 * 60 * 1000
    assert sdk_jobs.created == [
        {
            "model": "fun-asr",
            "file_urls": [_FILE_URL],
            "parameters": {
                "channel_id": [0],
                "format": "wav",
                "language_hints": ["zh"],
            },
        }
    ]
    assert model.parameters == {"channel_id": [1], "format": "wav"}


@pytest.mark.parametrize(
    ("content", "error"),
    [
        ([], ValueError),
        ([_audio(), _audio()], ValueError),
        ([TextBlock(text="hello")], UnsupportedContentBlockError),
        ([AudioBlock.from_base64("audio/wav", "YQ==")], UnsupportedContentBlockError),
        ([_audio("oss://bucket/audio.wav")], ValueError),
    ],
)
def test_submit_rejects_invalid_input(
    sdk_jobs: SimpleNamespace, content: list, error: type[Exception]
) -> None:
    model = _model()
    model._client().audio.asr.jobs = sdk_jobs

    with pytest.raises(error):
        model.submit(content)


def test_fetch_downloads_transcription_without_dashscope_credentials(
    sdk_jobs: SimpleNamespace,
    task_responses: Callable[..., list[dict[str, Any]]],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    model = _model(request_timeout=12)
    model._client().audio.asr.jobs = sdk_jobs
    calls = task_responses(
        model,
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
        ),
    )
    downloads: list[tuple[str, float]] = []

    def download(url: str, timeout: float) -> httpx.Response:
        downloads.append((url, timeout))
        return httpx.Response(200, json=_TRANSCRIPT, request=httpx.Request("GET", url))

    monkeypatch.setattr(
        "flink_agents.integrations.media_models.dashscope_media_model.httpx.get",
        download,
    )
    job = model.submit([_audio()])

    state = model.fetch(job)

    assert calls == [
        {"method": "GET", "path": "/tasks/job-1", "json": None, "headers": None}
    ]
    assert downloads == [(_RESULT_URL, 12)]
    assert state.status is MediaJobStatus.SUCCEEDED
    assert state.result is not None
    assert [block.text for block in state.result.content] == ["hello", "world"]
    assert state.result.metadata["transcription"] == _TRANSCRIPT
    assert state.result.usage == {"duration": 9}
    assert state.result.request_id == "req-1"
    assert state.result.expires_at_ms is None


def test_fetch_and_cancel_report_failed_transcription_subtask(
    sdk_jobs: SimpleNamespace, task_responses: Callable[..., list[dict[str, Any]]]
) -> None:
    model = _model()
    model._client().audio.asr.jobs = sdk_jobs
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
    calls = task_responses(
        model,
        httpx.Response(200, json=failed_transcription),
        httpx.Response(
            400,
            json={"code": "UnsupportedOperation", "message": "task has ended"},
        ),
        httpx.Response(200, json=failed_transcription),
    )
    job = model.submit([_audio()])

    state = model.fetch(job)
    with pytest.raises(MediaJobError) as exc_info:
        model.cancel(job)

    assert state.status is MediaJobStatus.FAILED
    assert state.result is None
    assert state.error_code == "FILE_DOWNLOAD_FAILED"
    assert state.error_message == "FILE_DOWNLOAD_FAILED"
    assert exc_info.value.status is state.status
    assert exc_info.value.code == "UnsupportedOperation"
    assert [(call["method"], call["path"]) for call in calls] == [
        ("GET", "/tasks/job-1"),
        ("POST", "/tasks/job-1/cancel"),
        ("GET", "/tasks/job-1"),
    ]
