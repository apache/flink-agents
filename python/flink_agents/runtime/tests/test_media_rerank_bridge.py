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
from asyncio import CancelledError as AsyncCancelledError
from concurrent.futures import CancelledError
from types import SimpleNamespace

import pytest

from flink_agents.api.chat_message import AudioBlock, ImageBlock, TextBlock, VideoBlock
from flink_agents.api.media.media_model import (
    AsyncJobModel,
    MediaJob,
    MediaJobError,
    MediaJobState,
    MediaJobStatus,
    MediaJobTimeoutError,
    MediaResult,
)
from flink_agents.api.rerank_models.rerank_model import RerankHit, RerankResult
from flink_agents.integrations.media_model_providers.dashscope_media_model_provider import (
    DashScopeMediaModelProvider,
)
from flink_agents.runtime.python_java_utils import (
    call_media_cancel,
    call_media_fetch,
    call_media_generate,
    call_media_submit,
    call_media_wait,
    call_rerank_model,
    validate_media_binding,
)

_JOB = MediaJob(
    provider="dashscope",
    model="wan2.7-i2v",
    task="video_generation",
    job_id="job-1",
    submitted_at_ms=1,
)


class _Media:
    def fetch(self, job: MediaJob) -> None:
        raise MediaJobError(job, MediaJobStatus.FAILED, "BadRequest", "invalid prompt")

    def wait(
        self,
        job: MediaJob,
        *,
        timeout: float | None = None,
        poll_interval: float = 5.0,
    ) -> None:
        raise MediaJobTimeoutError(job, 5.0, MediaJobStatus.PENDING)

    def cancel(self, job: MediaJob) -> None:
        assert job.job_id == "job-1"


def _provider(bound, task="video_generation") -> SimpleNamespace:
    factories = {
        "speech_synthesis": "speech",
        "transcription": "transcription",
        "image_generation": "image",
        "video_generation": "video",
    }
    return SimpleNamespace(**{factories[task]: lambda model, **options: bound})


class _Rerank:
    def rerank(
        self,
        query,
        documents,
        *,
        model_parameters=None,
        timeout=None,
    ) -> RerankResult:
        assert model_parameters == {"top_n": 1, "return_documents": True}
        assert timeout is None
        assert isinstance(query, TextBlock)
        return RerankResult(
            results=[RerankHit(index=0, relevance_score=0.5, document=documents[0])],
            usage={"total_tokens": 3},
            request_id="req-1",
        )


@pytest.mark.parametrize(
    ("task", "content", "result"),
    [
        (
            "speech_synthesis",
            "Hello.",
            MediaResult[AudioBlock](
                content=[
                    AudioBlock.from_url("audio/mp3", "https://example.test/out.mp3")
                ]
            ),
        ),
        (
            "transcription",
            AudioBlock.from_url("audio/mp3", "https://example.test/input.mp3"),
            MediaResult[TextBlock](content=[TextBlock(text="Hello.")]),
        ),
        (
            "transcription",
            VideoBlock.from_url("video/mp4", "https://example.test/input.mp4"),
            MediaResult[TextBlock](content=[TextBlock(text="Hello.")]),
        ),
        (
            "image_generation",
            {
                "prompt": "A notebook.",
                "images": [
                    ImageBlock.from_url("image/png", "https://example.test/input.png")
                ],
            },
            MediaResult[ImageBlock](
                content=[
                    ImageBlock.from_url("image/png", "https://example.test/out.png")
                ]
            ),
        ),
        (
            "video_generation",
            {
                "prompt": "A notebook.",
                "media": [
                    ImageBlock.from_url("image/png", "https://example.test/input.png"),
                    VideoBlock.from_url("video/mp4", "https://example.test/input.mp4"),
                ],
            },
            MediaResult[VideoBlock](
                content=[
                    VideoBlock.from_url("video/mp4", "https://example.test/out.mp4")
                ]
            ),
        ),
    ],
)
def test_call_media_generate_decodes_capability_input(task, content, result) -> None:
    """Decode the capability request and preserve typed, ordered media in the bridge."""

    def invoke(actual, **kwargs: object) -> MediaResult:
        expected_input = content["prompt"] if isinstance(content, dict) else content
        assert type(actual) is type(expected_input)
        assert actual == expected_input
        expected = {"model_parameters": {"seed": 1}, "timeout": 1.5}
        if isinstance(content, dict):
            field = "images" if task == "image_generation" else "media"
            assert [type(block) for block in kwargs[field]] == [
                type(block) for block in content[field]
            ]
            expected[field] = content[field]
        assert kwargs == expected
        return result

    def bind(model: str, **options: object) -> SimpleNamespace:
        assert model == "model"
        assert options == {"model_parameters": {"seed": 3}}
        method = {
            "speech_synthesis": "synthesize",
            "transcription": "transcribe",
            "image_generation": "generate",
            "video_generation": "generate",
        }[task]
        return SimpleNamespace(**{method: invoke})

    resource = SimpleNamespace(
        **{
            {
                "speech_synthesis": "speech",
                "transcription": "transcription",
                "image_generation": "image",
                "video_generation": "video",
            }[task]: bind
        }
    )
    serialized = json.dumps(
        content, default=lambda block: block.model_dump(mode="json")
    )
    options = '{"model_parameters":{"seed":3}}'
    assert validate_media_binding(resource, task, "model", options) is False
    outcome = call_media_generate(
        resource, task, "model", options, serialized, 1.5, '{"seed":1}'
    )

    assert outcome["status"] == "SUCCESS"
    assert type(result).model_validate_json(outcome["result"]) == result


def test_validate_media_binding_reports_async_capability() -> None:
    provider = DashScopeMediaModelProvider(api_key="sk-test", workspace="ws-1")
    assert (
        validate_media_binding(
            provider, "speech_synthesis", "qwen-audio-3.0-tts-flash", "{}"
        )
        is False
    )
    for task, model in (
        ("transcription", "fun-asr"),
        ("image_generation", "qwen-image-3.0"),
        ("video_generation", "happyhorse-1.1-t2v"),
    ):
        assert validate_media_binding(provider, task, model, "{}") is True
    assert not isinstance(provider.speech("qwen-audio-3.0-tts-flash"), AsyncJobModel)
    assert isinstance(provider.image("qwen-image-3.0"), AsyncJobModel)


def test_call_media_fetch_serializes_the_typed_result() -> None:
    result = MediaResult[VideoBlock](
        content=[VideoBlock.from_url("video/mp4", "https://example.test/out.mp4")]
    )
    state = MediaJobState[MediaResult[VideoBlock]](
        job=_JOB, status=MediaJobStatus.SUCCEEDED, result=result
    )
    resource = SimpleNamespace(fetch=lambda job: state)

    outcome = call_media_fetch(
        _provider(resource), "video_generation", "model", "{}", _JOB.model_dump_json()
    )

    assert outcome["status"] == "SUCCESS"
    decoded = MediaJobState[MediaResult[VideoBlock]].model_validate_json(
        outcome["result"]
    )
    assert isinstance(decoded.result, MediaResult[VideoBlock])
    assert decoded.result == result


def test_call_media_fetch_returns_job_error() -> None:
    outcome = call_media_fetch(
        _provider(_Media()), "video_generation", "model", "{}", _JOB.model_dump_json()
    )

    assert outcome["status"] == "JOB_ERROR"
    assert outcome["job_status"] == "failed"
    assert outcome["code"] == "BadRequest"
    assert outcome["message"] == "invalid prompt"
    assert MediaJob.model_validate_json(outcome["job"]).job_id == "job-1"


def test_call_media_wait_returns_timeout() -> None:
    outcome = call_media_wait(
        _provider(_Media()),
        "video_generation",
        "model",
        "{}",
        _JOB.model_dump_json(),
        5.0,
    )

    assert outcome["status"] == "TIMEOUT"
    assert outcome["job_status"] == "pending"
    assert outcome["timeout"] == 5.0
    assert outcome["message"] == "timed out after 5.0 seconds"


def test_call_media_wait_forwards_timeout_and_poll_interval() -> None:
    seen: list[dict[str, object]] = []
    result = MediaResult[VideoBlock](content=[])

    def wait(job: MediaJob, **kwargs: object) -> MediaResult[VideoBlock]:
        assert job == _JOB
        seen.append(kwargs)
        return result

    outcome = call_media_wait(
        _provider(SimpleNamespace(wait=wait)),
        "video_generation",
        "model",
        "{}",
        _JOB.model_dump_json(),
        None,
        2.5,
    )
    assert outcome["status"] == "SUCCESS"
    assert seen == [{"timeout": None, "poll_interval": 2.5}]


def test_call_media_cancel_returns_empty_success() -> None:
    outcome = call_media_cancel(
        _provider(_Media()), "video_generation", "model", "{}", _JOB.model_dump_json()
    )

    assert outcome == {"status": "SUCCESS", "result": None}


def test_call_rerank_model_returns_json() -> None:
    """The Java bridge receives the top rerank hit as JSON."""
    outcome = call_rerank_model(
        _Rerank(),
        '{"type": "text", "text": "notes"}',
        '[{"type": "text", "text": "A notebook."}]',
        '{"top_n": 1, "return_documents": true}',
        None,
    )

    assert outcome["status"] == "SUCCESS"
    result = RerankResult.model_validate_json(outcome["result"])
    assert result.results[0].index == 0
    assert result.results[0].document.text == "A notebook."


_OPERATIONS = [
    (call_media_generate, "generate", ('{"prompt":"A notebook."}', None)),
    (call_media_submit, "submit", ('{"prompt":"A notebook."}',)),
    (call_media_fetch, "fetch", (_JOB.model_dump_json(),)),
    (call_media_wait, "wait", (_JOB.model_dump_json(), None)),
    (call_media_cancel, "cancel", (_JOB.model_dump_json(),)),
    (
        call_rerank_model,
        "rerank",
        ('{"type":"text","text":"query"}', "[]", "{}", None),
    ),
]


@pytest.mark.parametrize(("bridge", "method", "args"), _OPERATIONS)
@pytest.mark.parametrize(
    ("error_type", "status"),
    [
        (InterruptedError, "INTERRUPTED"),
        (CancelledError, "CANCELLED"),
        (AsyncCancelledError, "CANCELLED"),
    ],
)
def test_bridge_preserves_cancellation(
    bridge, method, args, error_type, status
) -> None:
    def interrupted(*args: object, **kwargs: object) -> None:
        message = "operation stopped"
        raise error_type(message)

    resource = SimpleNamespace(**{method: interrupted})
    if bridge is not call_rerank_model:
        resource = _provider(resource)
        args = ("video_generation", "model", "{}", *args)
    assert bridge(resource, *args) == {"status": status, "message": "operation stopped"}


@pytest.mark.parametrize(("bridge", "method", "args"), _OPERATIONS)
def test_bridge_leaves_ordinary_errors_unchanged(bridge, method, args) -> None:
    error = ValueError("invalid request")

    def failed(*args: object, **kwargs: object) -> None:
        raise error

    resource = SimpleNamespace(**{method: failed})
    if bridge is not call_rerank_model:
        resource = _provider(resource)
        args = ("video_generation", "model", "{}", *args)
    with pytest.raises(ValueError) as raised:
        bridge(resource, *args)
    assert raised.value is error


@pytest.mark.parametrize(
    ("task", "payload", "expected_field"),
    [
        ("image_generation", '{"prompt":"A notebook.","images":null}', "images"),
        ("video_generation", '{"prompt":null,"media":null}', "media"),
    ],
)
def test_call_media_generate_accepts_null_optional_fields_in_json(
    task: str, payload: str, expected_field: str
) -> None:
    seen: list[tuple[object, dict[str, object]]] = []

    def generate(prompt: str, **kwargs: object) -> MediaResult:
        seen.append((prompt, kwargs))
        return MediaResult[VideoBlock](content=[])

    outcome = call_media_generate(
        _provider(SimpleNamespace(generate=generate), task=task),
        task,
        "model",
        "{}",
        payload,
        None,
        None,
    )

    assert outcome["status"] == "SUCCESS"
    assert len(seen) == 1
    expected_prompt = "A notebook." if task == "image_generation" else ""
    assert seen[0] == (
        expected_prompt,
        {expected_field: [], "model_parameters": None, "timeout": None},
    )


def test_bridge_passes_model_parameters_without_kwarg_unpacking() -> None:
    seen: list[dict[str, object]] = []

    def generate(
        prompt: str,
        *,
        images: object = None,
        model_parameters: dict[str, object] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[ImageBlock]:
        seen.append(
            {
                "prompt": prompt,
                "images": images,
                "model_parameters": model_parameters,
                "timeout": timeout,
            }
        )
        return MediaResult[ImageBlock](content=[])

    payload = {"self": "ok", "prompt": "not-a-collision", "timeout": 99}
    outcome = call_media_generate(
        _provider(SimpleNamespace(generate=generate), task="image_generation"),
        "image_generation",
        "model",
        "{}",
        '{"prompt":"A notebook."}',
        3.0,
        json.dumps(payload),
    )
    assert outcome["status"] == "SUCCESS"
    assert seen == [
        {
            "prompt": "A notebook.",
            "images": [],
            "model_parameters": payload,
            "timeout": 3.0,
        }
    ]
