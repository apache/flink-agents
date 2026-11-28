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
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from types import SimpleNamespace
from typing import Any
from unittest.mock import Mock

import httpx
import pytest
from dashscope.errors import APIConnectionError, DashScopeError
from pydantic import ValidationError

from flink_agents.api.chat_message import AudioBlock, VideoBlock
from flink_agents.api.media import (
    AsyncJobModel,
    BaseImageGenerationModelSetup,
    BaseMediaModelConnection,
    BaseSpeechSynthesisModelSetup,
    BaseTranscriptionModelSetup,
    BaseVideoGenerationModelSetup,
    MediaJob,
    MediaJobError,
    MediaJobStatus,
    MediaResult,
)
from flink_agents.api.resource import (
    Resource,
    ResourceDescriptor,
    ResourceName,
    ResourceType,
)
from flink_agents.api.resource_context import ResourceContext
from flink_agents.integrations.media_models.dashscope_media_model import (
    DashScopeImageGenerationModelSetup,
    DashScopeMediaModelConnection,
    DashScopeSpeechSynthesisModelSetup,
    DashScopeTranscriptionModelSetup,
    DashScopeVideoGenerationModelSetup,
)
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.resource_provider import PythonResourceProvider
from flink_agents.runtime.resource_cache import ResourceCache

_JOB = MediaJob(job_id="job-1", submitted_at_ms=1_700_000_000_000)


@pytest.fixture
def model_factory(
    connection_factory: Callable[..., DashScopeMediaModelConnection],
    setup_factory: Callable[..., DashScopeTranscriptionModelSetup],
) -> Callable[..., DashScopeTranscriptionModelSetup]:
    def create(**overrides: Any) -> DashScopeTranscriptionModelSetup:
        connection = connection_factory(**overrides)
        return setup_factory(
            DashScopeTranscriptionModelSetup,
            connection=connection,
            model="fun-asr",
        )

    return create


@pytest.fixture(
    params=[
        (DashScopeTranscriptionModelSetup, "fun-asr"),
        (DashScopeImageGenerationModelSetup, "qwen-image-3.0"),
        (DashScopeVideoGenerationModelSetup, "happyhorse-1.1-t2v"),
    ],
    ids=["transcription", "image", "video"],
)
def async_model(request: pytest.FixtureRequest, setup_factory) -> AsyncJobModel:
    setup_cls, model = request.param
    return setup_factory(setup_cls, model=model)


def test_api_key_is_resolved_when_the_resource_opens(
    connection_factory: Callable[..., DashScopeMediaModelConnection],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)
    connection = connection_factory(api_key=None)
    monkeypatch.setenv("DASHSCOPE_API_KEY", "sk-env")
    connection.open()
    monkeypatch.setenv("DASHSCOPE_API_KEY", "changed-after-open")

    assert connection._client().config.api_key == "sk-env"


def test_missing_api_key_is_rejected_when_the_resource_opens(
    connection_factory: Callable[..., DashScopeMediaModelConnection],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)
    connection = connection_factory(api_key=None)

    with pytest.raises(ValueError, match="DASHSCOPE_API_KEY"):
        connection.open()


def test_defaults(
    connection_factory: Callable[..., DashScopeMediaModelConnection],
) -> None:
    connection = connection_factory()

    assert connection.region == "cn-beijing"
    assert connection.request_timeout == 60.0
    assert DashScopeTranscriptionModelSetup(
        connection="dashscope", model="fun-asr"
    ).model_kwargs == {"model": "fun-asr"}
    assert connection.base_url is None


def test_workspace_gateway_and_request_timeout(
    connection_factory: Callable[..., DashScopeMediaModelConnection],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("DASHSCOPE_BASE_URL", raising=False)
    monkeypatch.delenv("DASHSCOPE_HTTP_BASE_URL", raising=False)
    connection = connection_factory(region="ap-southeast-1", request_timeout=12)

    connection.open()
    client = connection._client()

    assert (
        client.config.base_url == "https://ws-1.ap-southeast-1.maas.aliyuncs.com/api/v1"
    )
    assert client.config.timeout == 12


def test_missing_workspace_uses_the_legacy_gateway(
    connection_factory: Callable[..., DashScopeMediaModelConnection],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("DASHSCOPE_WORKSPACE", raising=False)
    monkeypatch.delenv("DASHSCOPE_BASE_URL", raising=False)
    monkeypatch.delenv("DASHSCOPE_HTTP_BASE_URL", raising=False)
    connection = connection_factory(workspace=None)

    with pytest.warns(UserWarning, match="no workspace configured"):
        connection.open()
    base_url = connection._client().config.base_url

    assert base_url == "https://dashscope.aliyuncs.com/api/v1"


def test_workspace_environment_is_used_when_the_field_is_unset(
    connection_factory: Callable[..., DashScopeMediaModelConnection],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("DASHSCOPE_BASE_URL", raising=False)
    monkeypatch.delenv("DASHSCOPE_HTTP_BASE_URL", raising=False)
    monkeypatch.setenv("DASHSCOPE_WORKSPACE", "ws-env")
    connection = connection_factory(workspace=None)
    connection.open()

    assert (
        connection._client().config.base_url
        == "https://ws-env.cn-beijing.maas.aliyuncs.com/api/v1"
    )


def test_explicit_base_url_replaces_the_derived_address(
    connection_factory: Callable[..., DashScopeMediaModelConnection],
) -> None:
    connection = connection_factory(base_url="https://example.test/custom")
    connection.open()

    assert connection._client().config.base_url == "https://example.test/custom"


def test_invalid_region_is_rejected(
    connection_factory: Callable[..., DashScopeMediaModelConnection],
) -> None:
    connection = connection_factory(region="mars-1")

    with pytest.raises(ValueError, match="invalid region"):
        connection.open()


@pytest.mark.parametrize("fields_at_top", [False, True])
def test_fetch_returns_failed_state(
    async_model: AsyncJobModel,
    http_calls,
    fields_at_top: bool,
) -> None:
    error = {"code": "InvalidParameter", "message": "bad input"}
    output = {"task_status": "FAILED"}
    body = {"output": output}
    if fields_at_top:
        body.update(error)
    else:
        output.update(error)
    calls, responses = http_calls
    responses.append(httpx.Response(200, json=body))

    state = async_model.fetch(_JOB)

    [request] = calls
    assert request.method == "GET"
    assert request.url.path == "/api/v1/tasks/job-1"
    assert state.status is MediaJobStatus.FAILED
    assert state.result is None
    assert state.error_code == "InvalidParameter"
    assert state.error_message == "bad input"


@pytest.mark.parametrize(
    ("status", "code", "message"),
    [
        (MediaJobStatus.RUNNING, None, None),
        (MediaJobStatus.CANCELED, "TaskCanceled", "Canceled by user"),
    ],
)
def test_fetch_keeps_task_error_when_gateway_error_fields_are_empty(
    model_factory: Callable[..., BaseTranscriptionModelSetup],
    http_calls,
    status: MediaJobStatus,
    code: str | None,
    message: str | None,
) -> None:
    model = model_factory()
    _, responses = http_calls
    output = {"task_status": status.value.upper()}
    if code is not None:
        output.update(code=code, message=message)
    responses.append(
        httpx.Response(200, json={"code": "", "message": "", "output": output})
    )

    state = model.fetch(_JOB)

    assert state.status is status
    assert state.error_code == code
    assert state.error_message == message


def test_fetch_maps_suspended_to_pending(
    model_factory: Callable[..., BaseTranscriptionModelSetup],
    http_calls,
) -> None:
    model = model_factory()
    _, responses = http_calls
    responses.append(httpx.Response(200, json={"output": {"task_status": "SUSPENDED"}}))

    assert model.fetch(_JOB).status is MediaJobStatus.PENDING


def test_fetch_propagates_network_error(
    async_model: AsyncJobModel,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    send = Mock(side_effect=httpx.ConnectError("connection refused"))
    monkeypatch.setattr(httpx.HTTPTransport, "handle_request", send)
    with pytest.raises(APIConnectionError):
        async_model.fetch(_JOB)
    send.assert_called_once()


def test_fetch_raises_the_query_error(
    async_model: AsyncJobModel,
    http_calls,
) -> None:
    _, responses = http_calls
    responses.append(
        httpx.Response(401, json={"code": "InvalidApiKey", "message": "bad key"})
    )

    with pytest.raises(DashScopeError) as exc_info:
        async_model.fetch(_JOB)

    assert exc_info.value.code == "InvalidApiKey"
    assert exc_info.value.status_code == 401


def test_cancel_uses_the_task_api(
    model_factory: Callable[..., BaseTranscriptionModelSetup],
    http_calls,
) -> None:
    model = model_factory()
    calls, responses = http_calls
    responses.append(httpx.Response(200, json={"output": {"task_status": "CANCELED"}}))

    model.cancel(_JOB)

    [request] = calls
    assert request.method == "POST"
    assert request.url.path == "/api/v1/tasks/job-1/cancel"


def test_task_paths_url_quote_job_id(
    async_model: AsyncJobModel,
    http_calls,
) -> None:
    special_job = _JOB.model_copy(update={"job_id": "job/1?a=b"})
    calls, responses = http_calls
    responses.extend(
        [
            httpx.Response(200, json={"output": {"task_status": "RUNNING"}}),
            httpx.Response(200, json={"output": {"task_status": "CANCELED"}}),
        ]
    )

    assert async_model.fetch(special_job).status is MediaJobStatus.RUNNING
    async_model.cancel(special_job)

    assert [(call.method, call.url.raw_path.decode()) for call in calls] == [
        ("GET", "/api/v1/tasks/job%2F1%3Fa%3Db"),
        ("POST", "/api/v1/tasks/job%2F1%3Fa%3Db/cancel"),
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
    async_model: AsyncJobModel,
    http_calls,
    query_response: httpx.Response,
    status: MediaJobStatus,
) -> None:
    calls, responses = http_calls
    responses.extend(
        [
            httpx.Response(
                400,
                json={"code": "UnsupportedOperation", "message": "task is running"},
            ),
            query_response,
        ]
    )

    with pytest.raises(MediaJobError) as exc_info:
        async_model.cancel(_JOB)

    assert exc_info.value.status is status
    assert exc_info.value.code == "UnsupportedOperation"
    assert isinstance(exc_info.value.__cause__, DashScopeError)
    assert [(call.method, call.url.path) for call in calls] == [
        ("POST", "/api/v1/tasks/job-1/cancel"),
        ("GET", "/api/v1/tasks/job-1"),
    ]


def test_generate_submits_once_and_waits(
    setup_factory: Callable[..., DashScopeVideoGenerationModelSetup],
    http_calls,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    model = setup_factory(
        DashScopeVideoGenerationModelSetup, model="happyhorse-1.1-t2v"
    )
    video_url = "https://oss.example/out.mp4"
    calls, responses = http_calls
    responses.extend(
        [
            httpx.Response(
                200, json={"output": {"task_id": "job-1", "task_status": "PENDING"}}
            ),
            httpx.Response(200, json={"output": {"task_status": "PENDING"}}),
            httpx.Response(
                200,
                json={"output": {"task_status": "SUCCEEDED", "video_url": video_url}},
            ),
        ]
    )
    monkeypatch.setattr(
        "flink_agents.api.media.async_job_model.time.sleep", lambda _seconds: None
    )

    result = model.generate("a cat")

    assert isinstance(result, MediaResult[VideoBlock])
    assert result.content[0].source.url == video_url
    assert result.expires_at_ms is None
    assert [(call.method, call.url.path) for call in calls] == [
        ("POST", "/api/v1/services/aigc/video-generation/video-synthesis"),
        ("GET", "/api/v1/tasks/job-1"),
        ("GET", "/api/v1/tasks/job-1"),
    ]


def test_transcribe_rejects_non_finite_timeout_before_submitting(
    model_factory: Callable[..., BaseTranscriptionModelSetup],
    sdk_jobs: SimpleNamespace,
) -> None:
    model = model_factory()
    model._client().audio.asr.jobs = sdk_jobs
    audio = AudioBlock.from_url("audio/wav", "https://example.test/a.wav")

    with pytest.raises(ValueError, match="wait timeout must be finite"):
        model.transcribe(audio, timeout=float("nan"))

    assert sdk_jobs.created == []


def test_signatures_reject_misplaced_control_or_unknown_kwargs() -> None:
    image = DashScopeImageGenerationModelSetup(
        connection="dashscope", model="qwen-image-3.0"
    )
    video = DashScopeVideoGenerationModelSetup(
        connection="dashscope", model="happyhorse-1.1-t2v"
    )
    asr = DashScopeTranscriptionModelSetup(connection="dashscope", model="fun-asr")
    audio = AudioBlock.from_url("audio/wav", "https://example.test/a.wav")

    with pytest.raises(TypeError, match="unexpected keyword argument 'timeout'"):
        image.submit("a cat", timeout=10)  # type: ignore[call-arg]
    with pytest.raises(TypeError, match="unexpected keyword argument 'poll_interval'"):
        image.generate("a cat", poll_interval=2)  # type: ignore[call-arg]
    with pytest.raises(TypeError, match="unexpected keyword argument 'duration'"):
        video.submit("a cat", duration=5)  # type: ignore[call-arg]
    with pytest.raises(TypeError, match="unexpected keyword argument 'timeout'"):
        asr.submit(audio, timeout=5)  # type: ignore[call-arg]


def test_generate_forwards_timeout_as_polling_budget(
    setup_factory: Callable[..., DashScopeVideoGenerationModelSetup],
    sdk_jobs: SimpleNamespace,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    model = setup_factory(
        DashScopeVideoGenerationModelSetup, model="happyhorse-1.1-t2v"
    )
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
    connection_factory: Callable[..., DashScopeMediaModelConnection],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("DASHSCOPE_MAX_RETRIES", "2")

    connection = connection_factory()
    connection.open()

    assert connection._client().config.max_retries == 0


def test_connection_descriptor_and_setups_resolve_without_opening_a_client(
    setup_factory: Callable[..., Any],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def reject_client_creation(**_kwargs: Any) -> None:
        msg = "Declaring a connection and opening setups must not open an SDK client"
        raise AssertionError(msg)

    monkeypatch.setattr(
        "flink_agents.integrations.media_models.dashscope_media_model.create_client",
        reject_client_creation,
    )
    descriptor = ResourceDescriptor(
        clazz=ResourceName.MediaModel.DASHSCOPE_CONNECTION,
        api_key="sk-test",
        workspace="ws-1",
    )
    connection = PythonResourceProvider.get(
        "dashscope",
        ResourceDescriptor.model_validate_json(descriptor.model_dump_json()),
        ResourceType.MEDIA_MODEL_CONNECTION,
    ).provide(None, AgentConfiguration())
    assert isinstance(connection, DashScopeMediaModelConnection)
    assert isinstance(connection, Resource)
    speech = setup_factory(
        DashScopeSpeechSynthesisModelSetup,
        connection=connection,
        open_connection=False,
        model="qwen-audio-3.0-tts-flash",
        voice="longanhuan",
    )
    transcription = setup_factory(
        DashScopeTranscriptionModelSetup,
        connection=connection,
        open_connection=False,
        model="fun-asr",
    )
    image = setup_factory(
        DashScopeImageGenerationModelSetup,
        connection=connection,
        open_connection=False,
        model="qwen-image-3.0",
    )
    video = setup_factory(
        DashScopeVideoGenerationModelSetup,
        connection=connection,
        open_connection=False,
        model="happyhorse-1.1-t2v",
    )

    assert isinstance(speech, BaseSpeechSynthesisModelSetup)
    assert isinstance(transcription, BaseTranscriptionModelSetup)
    assert isinstance(image, BaseImageGenerationModelSetup)
    assert isinstance(video, BaseVideoGenerationModelSetup)
    assert not isinstance(speech, AsyncJobModel)
    assert all(
        isinstance(model, AsyncJobModel) for model in (transcription, image, video)
    )
    assert all(
        not hasattr(speech, operation)
        for operation in ("submit", "fetch", "wait", "cancel")
    )
    for model in (speech, transcription, image, video):
        assert isinstance(model, Resource)
        model.close()
    connection.close()


def test_setups_shallow_copy_additional_kwargs() -> None:
    additional_kwargs = {"n": 1, "style": "watercolor"}
    first = DashScopeImageGenerationModelSetup(
        connection="dashscope", model="model-a", additional_kwargs=additional_kwargs
    )
    second = DashScopeImageGenerationModelSetup(
        connection="dashscope", model="model-b", additional_kwargs=additional_kwargs
    )

    additional_kwargs["style"] = "oil"
    first.additional_kwargs["seed"] = 11

    assert first.model == "model-a"
    assert second.model == "model-b"
    assert first.additional_kwargs == {"n": 1, "style": "watercolor", "seed": 11}
    assert second.additional_kwargs == {"n": 1, "style": "watercolor"}


def test_setup_additional_kwargs_override_explicit_fields() -> None:
    speech = DashScopeSpeechSynthesisModelSetup(
        connection="dashscope",
        model="tts",
        voice="Cherry",
        format="wav",
        sample_rate=24000,
        language_type="Chinese",
        additional_kwargs={"format": "mp3", "rate": 1.1},
    )
    assert speech.model_kwargs == {
        "model": "tts",
        "voice": "Cherry",
        "format": "mp3",
        "sample_rate": 24000,
        "language_type": "Chinese",
        "rate": 1.1,
    }

    asr = DashScopeTranscriptionModelSetup(
        connection="dashscope",
        model="fun-asr",
        language_hints=["zh"],
        diarization_enabled=True,
        speaker_count=2,
        channel_id=[0],
        additional_kwargs={"speaker_count": 5, "custom": "x"},
    )
    assert asr.model_kwargs == {
        "model": "fun-asr",
        "language_hints": ["zh"],
        "diarization_enabled": True,
        "speaker_count": 5,
        "channel_id": [0],
        "custom": "x",
    }

    image = DashScopeImageGenerationModelSetup(
        connection="dashscope",
        model="qwen-image",
        size="1024*1024",
        n=2,
        negative_prompt="blur",
        prompt_extend=False,
        watermark=True,
        seed=42,
        additional_kwargs={"seed": 1, "extra": "y"},
    )
    assert image.model_kwargs == {
        "model": "qwen-image",
        "size": "1024*1024",
        "n": 2,
        "negative_prompt": "blur",
        "prompt_extend": False,
        "watermark": True,
        "seed": 1,
        "extra": "y",
    }

    video = DashScopeVideoGenerationModelSetup(
        connection="dashscope",
        model="wan2.7-i2v",
        size="1280*720",
        resolution="720P",
        ratio="16:9",
        duration=5,
        prompt_extend=True,
        watermark=False,
        seed=7,
        video_media_roles=["first_frame"],
        additional_kwargs={"duration": 10, "extra": "z"},
    )
    assert video.model_kwargs == {
        "model": "wan2.7-i2v",
        "size": "1280*720",
        "resolution": "720P",
        "ratio": "16:9",
        "duration": 10,
        "prompt_extend": True,
        "watermark": False,
        "seed": 7,
        "video_media_roles": ["first_frame"],
        "extra": "z",
    }


def test_dashscope_setup_rejects_wrong_connection_type() -> None:
    class _OtherConnection(BaseMediaModelConnection):
        pass

    wrong = _OtherConnection()
    ctx = Mock(spec=ResourceContext)
    ctx.get_resource.return_value = wrong
    setup = DashScopeImageGenerationModelSetup(
        connection="other",
        model="qwen-image-3.0",
        resource_context=ctx,
    )
    with pytest.raises(TypeError, match="DashScopeMediaModelConnection"):
        setup.open()


def test_dashscope_config_validation() -> None:
    setup = DashScopeImageGenerationModelSetup(
        connection="dashscope",
        model="qwen-image",
        additional_kwargs=None,  # type: ignore[arg-type]
    )
    assert setup.additional_kwargs == {}
    with pytest.raises(ValidationError):
        DashScopeMediaModelConnection(request_timeout=0)
    with pytest.raises(ValidationError):
        DashScopeImageGenerationModelSetup(
            connection="dashscope",
            model="qwen-image",
            n=0,
        )
    with pytest.raises(ValidationError):
        DashScopeImageGenerationModelSetup(
            connection="dashscope",
            model="qwen-image",
            additional_kwargs="not-a-dict",  # type: ignore[arg-type]
        )


def test_connection_owns_and_closes_the_shared_client(
    connection_factory: Callable[..., DashScopeMediaModelConnection],
    setup_factory: Callable[..., Any],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    connection = connection_factory()
    models = [
        setup_factory(
            DashScopeSpeechSynthesisModelSetup,
            connection=connection,
            open_connection=False,
            model="qwen-audio-3.0-tts-flash",
            voice="longanhuan",
        ),
        setup_factory(
            DashScopeTranscriptionModelSetup,
            connection=connection,
            open_connection=False,
            model="fun-asr",
        ),
        setup_factory(
            DashScopeImageGenerationModelSetup,
            connection=connection,
            open_connection=False,
            model="qwen-image-3.0",
        ),
        setup_factory(
            DashScopeVideoGenerationModelSetup,
            connection=connection,
            open_connection=False,
            model="happyhorse-1.1-t2v",
        ),
    ]
    connection.open()
    client = connection._client()
    assert all(model._client() is client for model in models)
    closed = []
    close_client = client.close

    def close() -> None:
        closed.append(client)
        close_client()

    monkeypatch.setattr(client, "close", close)
    connection.close()
    connection.close()

    assert closed == [client]
    assert connection._sdk_client is None
    assert client.transport.http_client.is_closed
    for model in models:
        with pytest.raises(RuntimeError, match=r"Call open\(\)"):
            model._client()

    connection.open()
    reopened_client = connection._client()
    assert reopened_client is not client
    assert all(model._client() is reopened_client for model in models)
    connection.close()
    assert connection._sdk_client is None


def test_closing_unused_connection_does_not_create_client_and_unopened_model_fails(
    connection_factory: Callable[..., DashScopeMediaModelConnection],
    setup_factory: Callable[..., DashScopeSpeechSynthesisModelSetup],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def reject_client_creation(**_kwargs: Any) -> None:
        msg = "Closing or using an unopened connection must not create a client"
        raise AssertionError(msg)

    monkeypatch.setattr(
        "flink_agents.integrations.media_models.dashscope_media_model.create_client",
        reject_client_creation,
    )
    connection = connection_factory()
    existing_model = setup_factory(
        DashScopeSpeechSynthesisModelSetup,
        connection=connection,
        open_connection=False,
        model="qwen-audio-3.0-tts-flash",
        voice="longanhuan",
    )
    connection.close()

    with pytest.raises(RuntimeError, match=r"Call open\(\)"):
        existing_model.synthesize("Hello.")


def test_concurrent_calls_share_the_client_created_during_open(
    connection_factory: Callable[..., DashScopeMediaModelConnection],
    setup_factory: Callable[..., Any],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    connection = connection_factory()
    models = [
        setup_factory(
            DashScopeSpeechSynthesisModelSetup,
            connection=connection,
            open_connection=False,
            model="tts-a",
            voice="voice-a",
        ),
        setup_factory(
            DashScopeImageGenerationModelSetup,
            connection=connection,
            open_connection=False,
            model="image-b",
        ),
    ]
    client = Mock()
    factory = Mock(return_value=client)
    monkeypatch.setattr(
        "flink_agents.integrations.media_models.dashscope_media_model.create_client",
        factory,
    )
    factory.assert_not_called()
    connection.open()
    connection.open()
    factory.assert_called_once()
    start = Barrier(2)

    def connect(
        model: BaseSpeechSynthesisModelSetup | BaseImageGenerationModelSetup,
    ) -> Any:
        start.wait(timeout=5)
        return model._client()

    with ThreadPoolExecutor(max_workers=2) as pool:
        clients = list(pool.map(connect, models))

    assert all(shared is client for shared in clients)
    factory.assert_called_once()
    connection.close()
    client.close.assert_called_once_with()


def test_setup_requires_an_open_connection(
    setup_factory: Callable[..., DashScopeSpeechSynthesisModelSetup],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    factory = Mock()
    monkeypatch.setattr(
        "flink_agents.integrations.media_models.dashscope_media_model.create_client",
        factory,
    )
    speech = setup_factory(
        DashScopeSpeechSynthesisModelSetup,
        model="tts",
        voice="voice",
        open_connection=False,
    )

    with pytest.raises(RuntimeError, match=r"Call open\(\)"):
        speech.synthesize("Hello")

    factory.assert_not_called()


def test_declarative_dashscope_setups_resolve_and_preserve_connection_lifetime(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = Mock()
    client.audio.tts.http.synthesize.side_effect = lambda *_a, **kwargs: (
        SimpleNamespace(
            audio_url=f"https://example.test/{kwargs['voice']}.wav",
            expires_at=None,
            usage=None,
            request_id="speech-1",
        )
    )
    client.videos.jobs.create.return_value = SimpleNamespace(task_id="vid-job-1")
    client.audio.asr.jobs.create.return_value = SimpleNamespace(task_id="asr-job-1")
    client.transport.request.return_value = httpx.Response(
        200, json={"output": {"task_id": "img-job-1", "task_status": "PENDING"}}
    )
    factory = Mock(return_value=client)
    monkeypatch.setattr(
        "flink_agents.integrations.media_models.dashscope_media_model.create_client",
        factory,
    )

    for setup_cls in (
        DashScopeSpeechSynthesisModelSetup,
        DashScopeTranscriptionModelSetup,
        DashScopeImageGenerationModelSetup,
        DashScopeVideoGenerationModelSetup,
    ):
        with pytest.raises(ValidationError):
            setup_cls(model="m")
        with pytest.raises(ValueError, match="non-blank"):
            setup_cls(connection="   ", model="m")

    connections = {
        ResourceType.MEDIA_MODEL_CONNECTION: {
            "dashscope": PythonResourceProvider.get(
                "dashscope",
                ResourceDescriptor(
                    clazz=ResourceName.MediaModel.DASHSCOPE_CONNECTION,
                    api_key="sk-test",
                ),
                ResourceType.MEDIA_MODEL_CONNECTION,
            )
        },
        ResourceType.MEDIA_MODEL: {
            "speech": PythonResourceProvider.get(
                "speech",
                ResourceDescriptor(
                    clazz=ResourceName.MediaModel.DASHSCOPE_SPEECH_SYNTHESIS_SETUP,
                    connection="dashscope",
                    model="cosyvoice-v3-plus",
                    voice="SetupVoice",
                    additional_kwargs={"voice": "ParamVoice"},
                ),
                ResourceType.MEDIA_MODEL,
            ),
            "asr": PythonResourceProvider.get(
                "asr",
                ResourceDescriptor(
                    clazz=ResourceName.MediaModel.DASHSCOPE_TRANSCRIPTION_SETUP,
                    connection="dashscope",
                    model="fun-asr",
                ),
                ResourceType.MEDIA_MODEL,
            ),
            "image": PythonResourceProvider.get(
                "image",
                ResourceDescriptor(
                    clazz=ResourceName.MediaModel.DASHSCOPE_IMAGE_GENERATION_SETUP,
                    connection="dashscope",
                    model="qwen-image-3.0",
                ),
                ResourceType.MEDIA_MODEL,
            ),
            "video": PythonResourceProvider.get(
                "video",
                ResourceDescriptor(
                    clazz=ResourceName.MediaModel.DASHSCOPE_VIDEO_GENERATION_SETUP,
                    connection="dashscope",
                    model="wan2.7-i2v",
                ),
                ResourceType.MEDIA_MODEL,
            ),
        },
    }

    cache = ResourceCache(connections, AgentConfiguration())
    try:
        speech = cache.get_resource("speech", ResourceType.MEDIA_MODEL)
        asr = cache.get_resource("asr", ResourceType.MEDIA_MODEL)
        image = cache.get_resource("image", ResourceType.MEDIA_MODEL)
        video = cache.get_resource("video", ResourceType.MEDIA_MODEL)
        assert isinstance(speech, DashScopeSpeechSynthesisModelSetup)
        assert isinstance(asr, DashScopeTranscriptionModelSetup)
        assert isinstance(image, DashScopeImageGenerationModelSetup)
        assert isinstance(video, DashScopeVideoGenerationModelSetup)

        assert (
            speech.synthesize("Hi").content[0].source.url
            == "https://example.test/ParamVoice.wav"
        )
        assert (
            speech.synthesize("Hi", model_parameters={"voice": "CallParam"})
            .content[0]
            .source.url
            == "https://example.test/CallParam.wav"
        )
        assert (
            speech.synthesize(
                "Hi",
                voice="ExplicitVoice",
                model_parameters={"voice": "CallParam"},
            )
            .content[0]
            .source.url
            == "https://example.test/ExplicitVoice.wav"
        )
        assert (
            asr.submit(
                AudioBlock.from_url("audio/wav", "https://example.test/a.wav")
            ).job_id
            == "asr-job-1"
        )
        assert image.submit("cat").job_id == "img-job-1"
        assert video.submit("dog").job_id == "vid-job-1"

        speech.close()
        client.close.assert_not_called()
        with pytest.raises(TypeError, match=r"Call open\(\)"):
            speech.synthesize("After setup close")
    finally:
        cache.close()

    client.close.assert_called_once_with()
