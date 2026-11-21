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
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from types import SimpleNamespace
from typing import Any
from unittest.mock import Mock

import httpx
import pytest
from dashscope.errors import DashScopeError

from flink_agents.api.chat_message import AudioBlock, VideoBlock
from flink_agents.api.media.media_model import (
    AsyncJobModel,
    ImageGenerationModel,
    MediaJob,
    MediaJobError,
    MediaJobStatus,
    MediaResult,
    SpeechSynthesisModel,
    TranscriptionModel,
    VideoGenerationModel,
)
from flink_agents.api.resource import (
    Resource,
    ResourceDescriptor,
    ResourceName,
    ResourceType,
)
from flink_agents.integrations.media_model_providers.dashscope_media_model_provider import (
    DashScopeMediaModelProvider,
)
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.resource_provider import PythonResourceProvider

_JOB = MediaJob(
    provider="dashscope",
    model="fun-asr",
    task="transcription",
    job_id="job-1",
    submitted_at_ms=1_700_000_000_000,
)


@pytest.fixture
def model_factory(
    provider_factory: Callable[..., DashScopeMediaModelProvider],
) -> Callable[..., TranscriptionModel]:
    def create(**overrides: Any) -> TranscriptionModel:
        provider = provider_factory(**overrides)
        provider.open()
        return provider.transcription("fun-asr")

    return create


def test_api_key_is_resolved_when_the_resource_opens(
    provider_factory: Callable[..., DashScopeMediaModelProvider],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)
    model = provider_factory(api_key=None)
    monkeypatch.setenv("DASHSCOPE_API_KEY", "sk-env")
    model.open()
    monkeypatch.setenv("DASHSCOPE_API_KEY", "changed-after-open")

    assert model._client().config.api_key == "sk-env"


def test_missing_api_key_is_rejected_when_the_resource_opens(
    provider_factory: Callable[..., DashScopeMediaModelProvider],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)
    model = provider_factory(api_key=None)

    with pytest.raises(ValueError, match="DASHSCOPE_API_KEY"):
        model.open()


def test_defaults(provider_factory: Callable[..., DashScopeMediaModelProvider]) -> None:
    model = provider_factory()

    assert model.region == "cn-beijing"
    assert model.request_timeout == 60.0
    assert model.transcription("fun-asr").model_parameters == {}
    assert model.base_url is None


def test_workspace_gateway_and_request_timeout(
    provider_factory: Callable[..., DashScopeMediaModelProvider],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("DASHSCOPE_BASE_URL", raising=False)
    monkeypatch.delenv("DASHSCOPE_HTTP_BASE_URL", raising=False)
    model = provider_factory(region="ap-southeast-1", request_timeout=12)

    model.open()
    client = model._client()

    assert (
        client.config.base_url == "https://ws-1.ap-southeast-1.maas.aliyuncs.com/api/v1"
    )
    assert client.config.timeout == 12


def test_missing_workspace_uses_the_legacy_gateway(
    provider_factory: Callable[..., DashScopeMediaModelProvider],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("DASHSCOPE_WORKSPACE", raising=False)
    monkeypatch.delenv("DASHSCOPE_BASE_URL", raising=False)
    monkeypatch.delenv("DASHSCOPE_HTTP_BASE_URL", raising=False)
    model = provider_factory(workspace=None)

    with pytest.warns(UserWarning, match="no workspace configured"):
        model.open()
    base_url = model._client().config.base_url

    assert base_url == "https://dashscope.aliyuncs.com/api/v1"


def test_workspace_environment_is_used_when_the_field_is_unset(
    provider_factory: Callable[..., DashScopeMediaModelProvider],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("DASHSCOPE_BASE_URL", raising=False)
    monkeypatch.delenv("DASHSCOPE_HTTP_BASE_URL", raising=False)
    monkeypatch.setenv("DASHSCOPE_WORKSPACE", "ws-env")
    model = provider_factory(workspace=None)
    model.open()

    assert (
        model._client().config.base_url
        == "https://ws-env.cn-beijing.maas.aliyuncs.com/api/v1"
    )


def test_explicit_base_url_replaces_the_derived_address(
    provider_factory: Callable[..., DashScopeMediaModelProvider],
) -> None:
    model = provider_factory(base_url="https://example.test/custom")
    model.open()

    assert model._client().config.base_url == "https://example.test/custom"


def test_invalid_region_is_rejected(
    provider_factory: Callable[..., DashScopeMediaModelProvider],
) -> None:
    model = provider_factory(region="mars-1")

    with pytest.raises(ValueError, match="invalid region"):
        model.open()


@pytest.mark.parametrize("fields_at_top", [False, True])
def test_fetch_returns_failed_state(
    model_factory: Callable[..., TranscriptionModel],
    task_responses: Callable[..., list[dict[str, Any]]],
    fields_at_top: bool,
) -> None:
    error = {"code": "InvalidParameter", "message": "bad input"}
    output = {"task_status": "FAILED"}
    body = {"output": output}
    if fields_at_top:
        body.update(error)
    else:
        output.update(error)
    model = model_factory()
    task_responses(model, httpx.Response(200, json=body))

    state = model.fetch(_JOB)

    assert state.status is MediaJobStatus.FAILED
    assert state.result is None
    assert state.error_code == "InvalidParameter"
    assert state.error_message == "bad input"


def test_fetch_maps_suspended_to_pending(
    model_factory: Callable[..., TranscriptionModel],
    task_responses: Callable[..., list[dict[str, Any]]],
) -> None:
    model = model_factory()
    task_responses(
        model, httpx.Response(200, json={"output": {"task_status": "SUSPENDED"}})
    )

    assert model.fetch(_JOB).status is MediaJobStatus.PENDING


def test_fetch_raises_the_query_error(
    model_factory: Callable[..., TranscriptionModel],
    task_responses: Callable[..., list[dict[str, Any]]],
) -> None:
    model = model_factory()
    task_responses(
        model,
        httpx.Response(401, json={"code": "InvalidApiKey", "message": "bad key"}),
    )

    with pytest.raises(DashScopeError) as exc_info:
        model.fetch(_JOB)

    assert exc_info.value.code == "InvalidApiKey"
    assert exc_info.value.status_code == 401


def test_cancel_uses_the_task_api(
    model_factory: Callable[..., TranscriptionModel],
    task_responses: Callable[..., list[dict[str, Any]]],
) -> None:
    model = model_factory()
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


def test_task_paths_url_quote_job_id(
    model_factory: Callable[..., TranscriptionModel],
    task_responses: Callable[..., list[dict[str, Any]]],
) -> None:
    model = model_factory()
    special_job = _JOB.model_copy(update={"job_id": "job/1?a=b"})
    calls = task_responses(
        model,
        httpx.Response(200, json={"output": {"task_status": "RUNNING"}}),
        httpx.Response(200, json={"output": {"task_status": "CANCELED"}}),
    )

    assert model.fetch(special_job).status is MediaJobStatus.RUNNING
    model.cancel(special_job)

    assert [(call["method"], call["path"]) for call in calls] == [
        ("GET", "/tasks/job%2F1%3Fa%3Db"),
        ("POST", "/tasks/job%2F1%3Fa%3Db/cancel"),
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
    model_factory: Callable[..., TranscriptionModel],
    task_responses: Callable[..., list[dict[str, Any]]],
    query_response: httpx.Response,
    status: MediaJobStatus,
) -> None:
    model = model_factory()
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
    provider_factory: Callable[..., DashScopeMediaModelProvider],
    sdk_jobs: SimpleNamespace,
    task_responses: Callable[..., list[dict[str, Any]]],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    provider = provider_factory()
    provider.open()
    model = provider.video("happyhorse-1.1-t2v")
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
        "flink_agents.api.media.media_model.time.sleep", lambda _seconds: None
    )

    result = model.generate("a cat")

    assert isinstance(result, MediaResult[VideoBlock])
    assert len(sdk_jobs.created) == 1
    assert result.content[0].source.url == video_url
    assert result.expires_at_ms is None
    assert [(call["method"], call["path"]) for call in calls] == [
        ("GET", "/tasks/job-1"),
        ("GET", "/tasks/job-1"),
    ]


def test_transcribe_rejects_non_finite_timeout_before_submitting(
    model_factory: Callable[..., TranscriptionModel],
    sdk_jobs: SimpleNamespace,
) -> None:
    model = model_factory()
    model._client().audio.asr.jobs = sdk_jobs
    audio = AudioBlock.from_url("audio/wav", "https://example.test/a.wav")

    with pytest.raises(ValueError, match="wait timeout must be finite"):
        model.transcribe(audio, timeout=float("nan"))

    assert sdk_jobs.created == []


def test_signatures_reject_misplaced_control_or_unknown_kwargs(
    provider_factory: Callable[..., DashScopeMediaModelProvider],
) -> None:
    provider = provider_factory()
    image = provider.image("qwen-image-3.0")
    video = provider.video("happyhorse-1.1-t2v")
    audio = AudioBlock.from_url("audio/wav", "https://example.test/a.wav")

    with pytest.raises(TypeError, match="unexpected keyword argument 'timeout'"):
        image.submit("a cat", timeout=10)  # type: ignore[call-arg]
    with pytest.raises(TypeError, match="unexpected keyword argument 'poll_interval'"):
        image.generate("a cat", poll_interval=2)  # type: ignore[call-arg]
    with pytest.raises(TypeError, match="unexpected keyword argument 'duration'"):
        video.submit("a cat", duration=5)  # type: ignore[call-arg]
    with pytest.raises(TypeError, match="unexpected keyword argument 'timeout'"):
        provider.transcription("fun-asr").submit(audio, timeout=5)  # type: ignore[call-arg]


def test_generate_forwards_timeout_as_polling_budget(
    provider_factory: Callable[..., DashScopeMediaModelProvider],
    sdk_jobs: SimpleNamespace,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    provider = provider_factory()
    provider.open()
    model = provider.video("happyhorse-1.1-t2v")
    model._client().videos.jobs = sdk_jobs
    seen: list[tuple[MediaJob, float | None]] = []
    expected = MediaResult[VideoBlock](
        content=[VideoBlock.from_url("video/mp4", "https://oss.example/out.mp4")]
    )

    def fake_wait(
        self: Any,
        job: MediaJob,
        *,
        timeout: float | None = None,
        poll_interval: float = 5.0,
    ) -> MediaResult[VideoBlock]:
        seen.append((job, timeout))
        return expected

    monkeypatch.setattr(AsyncJobModel, "wait", fake_wait)

    assert model.generate("a cat", timeout=42.5) is expected
    assert len(seen) == 1
    assert seen[0][0].job_id == "job-1"
    assert seen[0][1] == 42.5


def test_client_does_not_take_max_retries_from_the_environment(
    provider_factory: Callable[..., DashScopeMediaModelProvider],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("DASHSCOPE_MAX_RETRIES", "2")

    provider = provider_factory()
    provider.open()

    assert provider._client().config.max_retries == 0


def test_provider_descriptor_binds_non_resource_capabilities_without_a_client(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def reject_client_creation(**_kwargs: Any) -> None:
        msg = "Declaring a provider and binding models must not open an SDK client"
        raise AssertionError(msg)

    monkeypatch.setattr(
        "flink_agents.integrations.media_model_providers.dashscope_media_model_provider.create_client",
        reject_client_creation,
    )
    descriptor = ResourceDescriptor(
        clazz=ResourceName.MediaModelProvider.DASHSCOPE,
        api_key="sk-test",
        workspace="ws-1",
    )
    provider = PythonResourceProvider.get(
        "dashscope",
        ResourceDescriptor.model_validate_json(descriptor.model_dump_json()),
        ResourceType.MEDIA_MODEL_PROVIDER,
    ).provide(None, AgentConfiguration())
    assert isinstance(provider, DashScopeMediaModelProvider)
    assert isinstance(provider, Resource)
    speech = provider.speech("qwen-audio-3.0-tts-flash", voice="longanhuan")
    transcription = provider.transcription("fun-asr")
    image = provider.image("qwen-image-3.0")
    video = provider.video("happyhorse-1.1-t2v")

    assert isinstance(speech, SpeechSynthesisModel)
    assert isinstance(transcription, TranscriptionModel)
    assert isinstance(image, ImageGenerationModel)
    assert isinstance(video, VideoGenerationModel)
    assert not isinstance(speech, AsyncJobModel)
    assert all(
        isinstance(model, AsyncJobModel) for model in (transcription, image, video)
    )
    assert all(
        not hasattr(speech, operation)
        for operation in ("submit", "fetch", "wait", "cancel")
    )
    for model in (speech, transcription, image, video):
        assert not isinstance(model, Resource)
        assert not hasattr(model, "close")
    provider.close()


def test_bound_models_copy_their_parameters(
    provider_factory: Callable[..., DashScopeMediaModelProvider],
) -> None:
    provider = provider_factory()
    model_parameters = {"n": 1, "nested": {"seed": 7}}
    first = provider.image("model-a", model_parameters=model_parameters)
    second = provider.image("model-b", model_parameters=model_parameters)

    model_parameters["nested"]["seed"] = 9
    first.model_parameters["nested"]["seed"] = 11

    assert first.model == "model-a"
    assert second.model == "model-b"
    assert first.model_parameters == {"n": 1, "nested": {"seed": 11}}
    assert second.model_parameters == {"n": 1, "nested": {"seed": 7}}
    assert model_parameters == {"n": 1, "nested": {"seed": 9}}


def test_provider_owns_and_closes_the_shared_client(
    provider_factory: Callable[..., DashScopeMediaModelProvider],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    provider = provider_factory()
    models = [
        provider.speech("qwen-audio-3.0-tts-flash", voice="longanhuan"),
        provider.transcription("fun-asr"),
        provider.image("qwen-image-3.0"),
        provider.video("happyhorse-1.1-t2v"),
    ]
    provider.open()
    client = provider._client()
    assert all(model._client() is client for model in models)
    closed = []
    close_client = client.close

    def close() -> None:
        closed.append(client)
        close_client()

    monkeypatch.setattr(client, "close", close)
    provider.close()
    provider.close()

    assert closed == [client]
    assert provider._sdk_client is None
    assert client.transport.http_client.is_closed
    with pytest.raises(RuntimeError, match="closed"):
        provider.open()
    for model in models:
        with pytest.raises(RuntimeError, match="closed"):
            model._client()


def test_closing_unused_provider_prevents_later_connections(
    provider_factory: Callable[..., DashScopeMediaModelProvider],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def reject_client_creation(**_kwargs: Any) -> None:
        msg = "A closed provider must not create a client"
        raise AssertionError(msg)

    monkeypatch.setattr(
        "flink_agents.integrations.media_model_providers.dashscope_media_model_provider.create_client",
        reject_client_creation,
    )
    provider = provider_factory()
    existing_model = provider.speech("qwen-audio-3.0-tts-flash", voice="longanhuan")
    provider.close()
    with pytest.raises(RuntimeError, match="closed"):
        provider.open()

    with pytest.raises(RuntimeError, match="closed"):
        existing_model.synthesize("Hello.")
    for bind in (
        provider.speech,
        provider.transcription,
        provider.image,
        provider.video,
    ):
        with pytest.raises(RuntimeError, match="closed"):
            bind("model")


def test_concurrent_calls_share_the_client_created_during_open(
    provider_factory: Callable[..., DashScopeMediaModelProvider],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    provider = provider_factory()
    models = [provider.speech("tts-a", voice="voice-a"), provider.image("image-b")]
    client = Mock()
    factory = Mock(return_value=client)
    monkeypatch.setattr(
        "flink_agents.integrations.media_model_providers.dashscope_media_model_provider.create_client",
        factory,
    )
    factory.assert_not_called()
    provider.open()
    provider.open()
    factory.assert_called_once()
    start = Barrier(2)

    def connect(model: SpeechSynthesisModel | ImageGenerationModel) -> Any:
        start.wait(timeout=5)
        return model._client()

    with ThreadPoolExecutor(max_workers=2) as pool:
        clients = list(pool.map(connect, models))

    assert all(shared is client for shared in clients)
    factory.assert_called_once()
    provider.close()
    client.close.assert_called_once_with()


def test_bound_model_requires_an_open_provider(
    provider_factory: Callable[..., DashScopeMediaModelProvider],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    factory = Mock()
    monkeypatch.setattr(
        "flink_agents.integrations.media_model_providers.dashscope_media_model_provider.create_client",
        factory,
    )
    provider = provider_factory()
    speech = provider.speech("tts", voice="voice")

    with pytest.raises(RuntimeError, match=r"Call open\(\)"):
        speech.synthesize("Hello")

    factory.assert_not_called()
