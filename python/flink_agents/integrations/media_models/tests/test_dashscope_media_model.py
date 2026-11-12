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
from dashscope.errors import DashScopeError

from flink_agents.api.chat_message import AudioBlock, TextBlock
from flink_agents.api.media_models.media_model import (
    MediaJob,
    MediaJobError,
    MediaJobStatus,
    MediaTask,
)
from flink_agents.integrations.media_models.dashscope_media_model import (
    DashScopeMediaModel,
)

_JOB = MediaJob(
    provider="dashscope",
    model="fun-asr",
    task=MediaTask.TRANSCRIPTION,
    job_id="job-1",
    submitted_at_ms=1_700_000_000_000,
)


def _model(**overrides: object) -> DashScopeMediaModel:
    values: dict[str, object] = {
        "model": "fun-asr",
        "task": MediaTask.TRANSCRIPTION,
        "api_key": "sk-test",
        "workspace": "ws-1",
    }
    values.update(overrides)
    return DashScopeMediaModel(**values)


def test_api_key_falls_back_to_the_environment(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("DASHSCOPE_API_KEY", "sk-env")

    assert _model(api_key=None).api_key == "sk-env"


def test_missing_api_key_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)

    with pytest.raises(ValueError, match="DASHSCOPE_API_KEY"):
        _model(api_key=None)


def test_defaults() -> None:
    model = _model()

    assert model.region == "cn-beijing"
    assert model.request_timeout == 60.0
    assert model.parameters == {}
    assert model.base_url is None
    assert model.voice is None


def test_workspace_gateway_and_request_timeout(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("DASHSCOPE_BASE_URL", raising=False)
    monkeypatch.delenv("DASHSCOPE_HTTP_BASE_URL", raising=False)
    model = _model(region="ap-southeast-1", request_timeout=12)

    client = model._client()

    assert (
        client.config.base_url == "https://ws-1.ap-southeast-1.maas.aliyuncs.com/api/v1"
    )
    assert client.config.timeout == 12


def test_missing_workspace_uses_the_legacy_gateway(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("DASHSCOPE_WORKSPACE", raising=False)
    monkeypatch.delenv("DASHSCOPE_BASE_URL", raising=False)
    monkeypatch.delenv("DASHSCOPE_HTTP_BASE_URL", raising=False)
    model = _model(workspace=None)

    with pytest.warns(UserWarning, match="no workspace configured"):
        base_url = model._client().config.base_url

    assert base_url == "https://dashscope.aliyuncs.com/api/v1"


def test_workspace_environment_is_used_when_the_field_is_unset(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("DASHSCOPE_BASE_URL", raising=False)
    monkeypatch.delenv("DASHSCOPE_HTTP_BASE_URL", raising=False)
    monkeypatch.setenv("DASHSCOPE_WORKSPACE", "ws-env")
    model = _model(workspace=None)

    assert (
        model._client().config.base_url
        == "https://ws-env.cn-beijing.maas.aliyuncs.com/api/v1"
    )


def test_explicit_base_url_replaces_the_derived_address() -> None:
    model = _model(base_url="https://example.test/custom")

    assert model._client().config.base_url == "https://example.test/custom"


def test_invalid_region_is_rejected() -> None:
    model = _model(region="mars-1")

    with pytest.raises(ValueError, match="invalid region"):
        model._client()


@pytest.mark.parametrize("fields_at_top", [False, True])
def test_fetch_returns_failed_state(
    task_responses: Callable[..., list[dict[str, Any]]], fields_at_top: bool
) -> None:
    error = {"code": "InvalidParameter", "message": "bad input"}
    output = {"task_status": "FAILED"}
    body = {"output": output}
    if fields_at_top:
        body.update(error)
    else:
        output.update(error)
    model = _model()
    task_responses(model, httpx.Response(200, json=body))

    state = model.fetch(_JOB)

    assert state.status is MediaJobStatus.FAILED
    assert state.result is None
    assert state.error_code == "InvalidParameter"
    assert state.error_message == "bad input"


def test_fetch_maps_suspended_to_pending(
    task_responses: Callable[..., list[dict[str, Any]]],
) -> None:
    model = _model()
    task_responses(
        model, httpx.Response(200, json={"output": {"task_status": "SUSPENDED"}})
    )

    assert model.fetch(_JOB).status is MediaJobStatus.PENDING


def test_fetch_raises_the_query_error(
    task_responses: Callable[..., list[dict[str, Any]]],
) -> None:
    model = _model()
    task_responses(
        model,
        httpx.Response(401, json={"code": "InvalidApiKey", "message": "bad key"}),
    )

    with pytest.raises(DashScopeError) as exc_info:
        model.fetch(_JOB)

    assert exc_info.value.code == "InvalidApiKey"
    assert exc_info.value.status_code == 401


def test_cancel_uses_the_task_api(
    task_responses: Callable[..., list[dict[str, Any]]],
) -> None:
    model = _model()
    calls = task_responses(
        model, httpx.Response(200, json={"output": {"task_status": "CANCELED"}})
    )

    model.cancel(_JOB)

    assert calls == [
        {
            "method": "POST",
            "path": "/tasks/job-1/cancel",
            "json": None,
            "headers": None,
        }
    ]


@pytest.mark.parametrize(
    ("query_response", "status"),
    [
        (
            httpx.Response(200, json={"output": {"task_status": "RUNNING"}}),
            MediaJobStatus.RUNNING,
        ),
        (
            httpx.Response(401, json={"code": "InvalidApiKey", "message": "bad key"}),
            MediaJobStatus.UNKNOWN,
        ),
    ],
)
def test_cancel_rejection_reports_the_observed_status(
    task_responses: Callable[..., list[dict[str, Any]]],
    query_response: httpx.Response,
    status: MediaJobStatus,
) -> None:
    model = _model()
    calls = task_responses(
        model,
        httpx.Response(
            400,
            json={"code": "UnsupportedOperation", "message": "task is running"},
        ),
        query_response,
    )

    with pytest.raises(MediaJobError) as exc_info:
        model.cancel(_JOB)

    assert exc_info.value.status is status
    assert exc_info.value.code == "UnsupportedOperation"
    assert isinstance(exc_info.value.__cause__, DashScopeError)
    assert [(call["method"], call["path"]) for call in calls] == [
        ("POST", "/tasks/job-1/cancel"),
        ("GET", "/tasks/job-1"),
    ]


def test_generate_submits_once_and_waits(
    sdk_jobs: SimpleNamespace,
    task_responses: Callable[..., list[dict[str, Any]]],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    model = _model(model="happyhorse-1.1-t2v", task=MediaTask.VIDEO_GENERATION)
    model._client().videos.jobs = sdk_jobs
    video_url = "https://oss.example/out.mp4"
    calls = task_responses(
        model,
        httpx.Response(200, json={"output": {"task_status": "PENDING"}}),
        httpx.Response(
            200,
            json={"output": {"task_status": "SUCCEEDED", "video_url": video_url}},
        ),
    )
    monkeypatch.setattr(
        "flink_agents.api.media_models.media_model.time.sleep", lambda _seconds: None
    )

    result = model.generate([TextBlock(text="a cat")])

    assert len(sdk_jobs.created) == 1
    assert result.content[0].source.url == video_url
    assert result.expires_at_ms is None
    assert [(call["method"], call["path"]) for call in calls] == [
        ("GET", "/tasks/job-1"),
        ("GET", "/tasks/job-1"),
    ]


def test_generate_rejects_non_finite_wait_timeout_before_submitting(
    sdk_jobs: SimpleNamespace,
) -> None:
    model = _model()
    model._client().audio.asr.jobs = sdk_jobs
    audio = AudioBlock.from_url("audio/wav", "https://example.test/a.wav")

    with pytest.raises(ValueError, match="wait timeout must be finite"):
        model.generate([audio], timeout=float("nan"))

    assert sdk_jobs.created == []


def test_client_does_not_take_max_retries_from_the_environment(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("DASHSCOPE_MAX_RETRIES", "2")

    assert _model()._client().config.max_retries == 0


def test_close_releases_the_sdk_client() -> None:
    model = _model()
    client = model._client()

    model.close()
    model.close()

    assert client.transport.http_client.is_closed
    replacement = model._client()
    try:
        assert replacement is not client
        assert not replacement.transport.http_client.is_closed
    finally:
        model.close()


def test_async_support_follows_the_task() -> None:
    assert _model(task=MediaTask.VIDEO_GENERATION).supports_async_task()
    assert not _model(task=MediaTask.SPEECH_SYNTHESIS).supports_async_task()


def test_speech_synthesis_does_not_submit_a_task() -> None:
    model = _model(task=MediaTask.SPEECH_SYNTHESIS, voice="longanhuan")

    with pytest.raises(NotImplementedError):
        model.submit([TextBlock(text="hi")])
