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
from typing import Any

import pytest

from flink_agents.api.chat_message import AudioBlock, ImageBlock, TextBlock, VideoBlock
from flink_agents.api.media import (
    AsyncJobModel,
    BaseImageGenerationModelSetup,
    BaseMediaModelConnection,
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
from flink_agents.api.rerank_models.rerank_model import RerankHit, RerankResult
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.resource_context import ResourceContext
from flink_agents.api.tools.tool import Tool
from flink_agents.runtime.durable_execution import durable_identity_for_call
from flink_agents.runtime.python_java_utils import (
    call_media_model_cancel,
    call_media_model_fetch,
    call_media_model_generate,
    call_media_model_submit,
    call_rerank_model,
    create_resource,
    inspect_media_model,
)

_JOB = MediaJob(job_id="job-1", submitted_at_ms=1)


class _Media:
    def fetch(self, job: MediaJob) -> None:
        raise MediaJobError(job, MediaJobStatus.FAILED, "BadRequest", "invalid prompt")

    def cancel(self, job: MediaJob) -> None:
        assert job.job_id == "job-1"


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
def test_call_media_model_generate_decodes_capability_input(
    task, content, result
) -> None:
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

    method = {
        "speech_synthesis": "synthesize",
        "transcription": "transcribe",
        "image_generation": "generate",
        "video_generation": "generate",
    }[task]
    resource = SimpleNamespace(**{method: invoke})
    serialized = json.dumps(
        content, default=lambda block: block.model_dump(mode="json")
    )
    outcome = call_media_model_generate(resource, task, serialized, 1.5, '{"seed":1}')

    assert outcome["status"] == "SUCCESS"
    assert type(result).model_validate_json(outcome["result"]) == result


def test_call_media_model_fetch_serializes_the_typed_result() -> None:
    result = MediaResult[VideoBlock](
        content=[VideoBlock.from_url("video/mp4", "https://example.test/out.mp4")]
    )
    state = MediaJobState[MediaResult[VideoBlock]](
        job=_JOB, status=MediaJobStatus.SUCCEEDED, result=result
    )
    resource = SimpleNamespace(fetch=lambda job: state)

    outcome = call_media_model_fetch(resource, _JOB.model_dump_json())

    assert outcome["status"] == "SUCCESS"
    decoded = MediaJobState[MediaResult[VideoBlock]].model_validate_json(
        outcome["result"]
    )
    assert isinstance(decoded.result, MediaResult[VideoBlock])
    assert decoded.result == result


def test_call_media_model_fetch_returns_job_error() -> None:
    outcome = call_media_model_fetch(_Media(), _JOB.model_dump_json())

    assert outcome["status"] == "JOB_ERROR"
    assert outcome["job_status"] == "failed"
    assert outcome["code"] == "BadRequest"
    assert outcome["message"] == "invalid prompt"
    assert MediaJob.model_validate_json(outcome["job"]).job_id == "job-1"


def test_call_media_model_fetch_returns_timeout() -> None:
    def fetch(job: MediaJob) -> None:
        raise MediaJobTimeoutError(job, 5.0, MediaJobStatus.PENDING)

    outcome = call_media_model_fetch(
        SimpleNamespace(fetch=fetch), _JOB.model_dump_json()
    )

    assert outcome["status"] == "TIMEOUT"
    assert outcome["job_status"] == "pending"
    assert outcome["timeout"] == 5.0
    assert outcome["message"] == "Media job job-1 timed out after 5.0s"


def test_call_media_model_cancel_returns_empty_success() -> None:
    outcome = call_media_model_cancel(_Media(), _JOB.model_dump_json())

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
    (
        call_media_model_generate,
        "generate",
        ("video_generation", '{"prompt":"A notebook."}', None),
    ),
    (
        call_media_model_submit,
        "submit",
        ("video_generation", '{"prompt":"A notebook."}'),
    ),
    (call_media_model_fetch, "fetch", (_JOB.model_dump_json(),)),
    (call_media_model_cancel, "cancel", (_JOB.model_dump_json(),)),
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
    assert bridge(resource, *args) == {"status": status, "message": "operation stopped"}


@pytest.mark.parametrize(("bridge", "method", "args"), _OPERATIONS)
def test_bridge_leaves_ordinary_errors_unchanged(bridge, method, args) -> None:
    error = ValueError("invalid request")

    def failed(*args: object, **kwargs: object) -> None:
        raise error

    resource = SimpleNamespace(**{method: failed})
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
def test_call_media_model_generate_accepts_null_optional_fields_in_json(
    task: str, payload: str, expected_field: str
) -> None:
    seen: list[tuple[object, dict[str, object]]] = []

    def generate(prompt: str, **kwargs: object) -> MediaResult:
        seen.append((prompt, kwargs))
        return MediaResult[VideoBlock](content=[])

    outcome = call_media_model_generate(
        SimpleNamespace(generate=generate),
        task,
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
    outcome = call_media_model_generate(
        SimpleNamespace(generate=generate),
        "image_generation",
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


class _FakeCtx(ResourceContext):
    def __init__(self, resources: dict[tuple[str, ResourceType], Any]) -> None:
        self._resources = resources

    def get_resource(
        self, name: str, resource_type: ResourceType, metric_group: Any = None
    ) -> Resource:
        return self._resources[(name, resource_type)]

    def get_subagent_tool(self, name: str) -> Tool:
        raise NotImplementedError

    def generate_available_skills_prompt(self, *skill_names: str) -> str:
        return ""

    def get_skill_dirs(self, *skill_names: str) -> list[str]:
        return []


def test_create_resource_inspect_and_open_setup(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class _CustomConnection(BaseMediaModelConnection):
        pass

    class _CustomImageSetup(
        BaseImageGenerationModelSetup, AsyncJobModel[MediaResult[ImageBlock]]
    ):
        opened_with_connection: _CustomConnection | None = None

        def open(self) -> None:
            super().open()
            resolved = self._get_connection()
            assert isinstance(resolved, _CustomConnection)
            self.opened_with_connection = resolved

        def generate(self, *args: object, **kwargs: object) -> MediaResult[ImageBlock]:
            return MediaResult[ImageBlock](content=[])

        def submit(self, *args: object, **kwargs: object) -> MediaJob:
            return _JOB

        def fetch(self, job: MediaJob) -> MediaJobState[MediaResult[ImageBlock]]:
            return MediaJobState[MediaResult[ImageBlock]](
                job=job, status=MediaJobStatus.PENDING
            )

        def cancel(self, job: MediaJob) -> None:
            pass

    fake_module = SimpleNamespace(_CustomImageSetup=_CustomImageSetup)
    monkeypatch.setitem(
        __import__("sys").modules, "test_custom_media_setup_mod", fake_module
    )
    connection = _CustomConnection()
    ctx = _FakeCtx({("dashscope", ResourceType.MEDIA_MODEL_CONNECTION): connection})
    setup = create_resource(
        "test_custom_media_setup_mod",
        "_CustomImageSetup",
        {"connection": "dashscope", "model": "qwen-image-3.0", "resource_context": ctx},
    )
    assert isinstance(setup, BaseImageGenerationModelSetup)
    assert isinstance(setup, AsyncJobModel)
    assert inspect_media_model(setup) == {
        "task": "image_generation",
        "async": True,
        "model": "qwen-image-3.0",
        "connection": "dashscope",
    }
    assert isinstance(
        durable_identity_for_call(_CustomImageSetup.resource_type, (), {}), str
    )

    setup.open()
    assert setup.opened_with_connection is connection
    setup.close()
    assert setup._resolved_connection is None

    class _CustomSpeech(BaseSpeechSynthesisModelSetup):
        opened: bool = False

        def open(self) -> None:
            self.opened = True

        def synthesize(
            self,
            text: str,
            *,
            voice: str | None = None,
            model_parameters: object = None,
            timeout: float | None = None,
        ) -> MediaResult[AudioBlock]:
            return MediaResult[AudioBlock](
                content=[AudioBlock.from_url("audio/wav", "https://example.test/s.wav")]
            )

    class _CustomAsyncImage(
        BaseImageGenerationModelSetup, AsyncJobModel[MediaResult[ImageBlock]]
    ):
        def generate(
            self,
            prompt: str,
            *,
            images: object = None,
            model_parameters: object = None,
            timeout: float | None = None,
        ) -> MediaResult[ImageBlock]:
            return self._submit_and_wait(
                prompt,
                images=images,
                model_parameters=model_parameters,
                timeout=timeout,
            )

        def submit(self, *args: object, **kwargs: object) -> MediaJob:
            return _JOB

        def fetch(self, job: MediaJob) -> MediaJobState[MediaResult[ImageBlock]]:
            return MediaJobState[MediaResult[ImageBlock]](
                job=job, status=MediaJobStatus.PENDING
            )

        def cancel(self, job: MediaJob) -> None:
            pass

    custom_mod = SimpleNamespace(
        _CustomSpeech=_CustomSpeech,
        _CustomAsyncImage=_CustomAsyncImage,
    )
    monkeypatch.setitem(
        __import__("sys").modules, "test_custom_speech_img_mod", custom_mod
    )

    speech = create_resource(
        "test_custom_speech_img_mod",
        "_CustomSpeech",
        {"connection": "fake-conn", "model": "custom-tts", "voice": "Cherry"},
    )
    assert isinstance(speech, BaseSpeechSynthesisModelSetup)
    assert not isinstance(speech, AsyncJobModel)
    assert inspect_media_model(speech) == {
        "task": "speech_synthesis",
        "async": False,
        "model": "custom-tts",
        "connection": "fake-conn",
        "voice": "Cherry",
    }
    speech.open()
    assert speech.opened is True

    image = create_resource(
        "test_custom_speech_img_mod",
        "_CustomAsyncImage",
        {"connection": "fake-conn", "model": "custom-img"},
    )
    assert isinstance(image, BaseImageGenerationModelSetup)
    assert isinstance(image, AsyncJobModel)
    assert inspect_media_model(image) == {
        "task": "image_generation",
        "async": True,
        "model": "custom-img",
        "connection": "fake-conn",
    }

    with pytest.raises(TypeError, match="exactly one media capability"):
        inspect_media_model(object())

    class _Both(BaseSpeechSynthesisModelSetup, BaseTranscriptionModelSetup):
        def synthesize(
            self, *args: object, **kwargs: object
        ) -> MediaResult[AudioBlock]:
            raise NotImplementedError

        def transcribe(self, *args: object, **kwargs: object) -> MediaResult[TextBlock]:
            raise NotImplementedError

    with pytest.raises(TypeError, match="found 2"):
        inspect_media_model(_Both(connection="fake-conn", model="m"))


def test_call_media_model_bridge_operations_and_cancellation() -> None:
    class _Model(BaseVideoGenerationModelSetup, AsyncJobModel[MediaResult[VideoBlock]]):
        def generate(
            self,
            prompt: str = "",
            *,
            media: object = None,
            model_parameters: object = None,
            timeout: float | None = None,
        ) -> MediaResult[VideoBlock]:
            return MediaResult[VideoBlock](
                content=[VideoBlock.from_url("video/mp4", "https://example.test/v.mp4")]
            )

        def submit(
            self,
            prompt: str = "",
            *,
            media: object = None,
            model_parameters: object = None,
        ) -> MediaJob:
            return _JOB

        def fetch(self, job: MediaJob) -> MediaJobState[MediaResult[VideoBlock]]:
            return MediaJobState[MediaResult[VideoBlock]](
                job=job, status=MediaJobStatus.PENDING
            )

        def cancel(self, job: MediaJob) -> None:
            assert job.job_id == "job-1"

    model = _Model(connection="fake-conn", model="wan2.7-i2v")
    assert inspect_media_model(model) == {
        "task": "video_generation",
        "async": True,
        "model": "wan2.7-i2v",
        "connection": "fake-conn",
    }

    gen = call_media_model_generate(
        model, "video_generation", '{"prompt":"hi"}', 2.0, '{"seed":1}'
    )
    assert gen["status"] == "SUCCESS"
    assert (
        MediaResult[VideoBlock].model_validate_json(gen["result"]).content[0].source.url
        == "https://example.test/v.mp4"
    )

    sub = call_media_model_submit(model, "video_generation", '{"prompt":"hi"}', "{}")
    assert sub["status"] == "SUCCESS"
    assert MediaJob.model_validate_json(sub["result"]) == _JOB

    fetched = call_media_model_fetch(model, _JOB.model_dump_json())
    assert fetched["status"] == "SUCCESS"
    assert call_media_model_cancel(model, _JOB.model_dump_json()) == {
        "status": "SUCCESS",
        "result": None,
    }

    def interrupted(*args: object, **kwargs: object) -> None:
        msg = "stopped"
        raise InterruptedError(msg)

    assert call_media_model_fetch(
        SimpleNamespace(fetch=interrupted), _JOB.model_dump_json()
    ) == {
        "status": "INTERRUPTED",
        "message": "stopped",
    }


def test_call_media_model_generate_forwards_speech_voice_kwarg() -> None:
    seen: list[dict[str, object]] = []

    def synthesize(
        text: str,
        *,
        voice: str | None = None,
        model_parameters: dict[str, object] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[AudioBlock]:
        seen.append(
            {
                "text": text,
                "voice": voice,
                "model_parameters": model_parameters,
                "timeout": timeout,
            }
        )
        return MediaResult[AudioBlock](content=[])

    outcome = call_media_model_generate(
        SimpleNamespace(synthesize=synthesize),
        "speech_synthesis",
        '"Hello."',
        2.0,
        '{"voice": "Cherry", "format": "mp3"}',
    )

    assert outcome["status"] == "SUCCESS"
    assert seen == [
        {
            "text": "Hello.",
            "voice": "Cherry",
            "model_parameters": {"format": "mp3"},
            "timeout": 2.0,
        }
    ]


def test_call_media_model_submit_forwards_speech_voice_kwarg() -> None:
    seen: list[dict[str, object]] = []

    def submit(
        text: str,
        *,
        voice: str | None = None,
        model_parameters: dict[str, object] | None = None,
    ) -> MediaJob:
        seen.append(
            {
                "text": text,
                "voice": voice,
                "model_parameters": model_parameters,
            }
        )
        return _JOB

    outcome = call_media_model_submit(
        SimpleNamespace(submit=submit),
        "speech_synthesis",
        '"Hello."',
        '{"voice": "Cherry", "format": "mp3"}',
    )

    assert outcome["status"] == "SUCCESS"
    assert MediaJob.model_validate_json(outcome["result"]) == _JOB
    assert seen == [
        {
            "text": "Hello.",
            "voice": "Cherry",
            "model_parameters": {"format": "mp3"},
        }
    ]
