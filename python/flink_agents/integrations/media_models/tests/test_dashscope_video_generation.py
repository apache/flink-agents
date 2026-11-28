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
from collections.abc import Callable
from typing import Any

import httpx
import pytest

from flink_agents.api.chat_message import (
    AudioBlock,
    DocumentBlock,
    ImageBlock,
    TextBlock,
    UnsupportedContentBlockError,
    VideoBlock,
)
from flink_agents.api.media import (
    BaseVideoGenerationModelSetup,
    MediaJob,
    MediaJobStatus,
    MediaResult,
)
from flink_agents.integrations.media_models.dashscope_media_model import (
    DashScopeVideoGenerationModelSetup,
)

_IMAGE = "https://example.test/frame.png"
_VIDEO = "https://oss.example/out.mp4?Expires=1700000000"


@pytest.fixture
def model_factory(
    setup_factory: Callable[..., BaseVideoGenerationModelSetup],
) -> Callable[..., BaseVideoGenerationModelSetup]:
    def create(**overrides: Any) -> BaseVideoGenerationModelSetup:
        values: dict[str, Any] = {"model": "custom-video-deployment"}
        values.update(overrides)
        return setup_factory(DashScopeVideoGenerationModelSetup, **values)

    return create


def test_text_submission_forwards_model_and_parameters(
    model_factory: Callable[..., BaseVideoGenerationModelSetup],
    http_calls,
) -> None:
    model = model_factory(resolution="720P", duration=3)
    requests, responses = http_calls
    responses.append(httpx.Response(200, json={"output": {"task_id": "job-1"}}))

    job = model.submit("a cat", model_parameters={"duration": 5})

    assert job.job_id == "job-1"
    assert job.expires_at_ms == job.submitted_at_ms + 24 * 60 * 60 * 1000
    [request] = requests
    assert json.loads(request.content) == {
        "model": "custom-video-deployment",
        "input": {"prompt": "a cat"},
        "parameters": {"resolution": "720P", "duration": 5},
    }
    assert model.model_kwargs == {
        "model": "custom-video-deployment",
        "resolution": "720P",
        "duration": 3,
    }


@pytest.mark.parametrize(
    ("call_parameters", "role"),
    [
        (None, "reference_image"),
        ({"video_media_roles": ("first_frame",)}, "first_frame"),
        # Only a media URL can request OSS resolution; role strings are opaque.
        ({"video_media_roles": ["oss://role"]}, "oss://role"),
    ],
)
def test_media_roles_use_configuration_or_call_override(
    model_factory: Callable[..., BaseVideoGenerationModelSetup],
    http_calls,
    call_parameters: dict[str, Any] | None,
    role: str,
) -> None:
    model = model_factory(
        model="wan2.7-i2v",
        video_media_roles=["reference_image"],
        additional_kwargs={"format": "mp4"},
    )
    requests, responses = http_calls
    responses.append(httpx.Response(200, json={"output": {"task_id": "job-1"}}))

    model.submit(
        media=[ImageBlock.from_url("image/png", _IMAGE)],
        model_parameters=call_parameters,
    )

    [request] = requests
    assert request.method == "POST"
    assert request.url.path == "/api/v1/services/aigc/video-generation/video-synthesis"
    assert request.headers["X-DashScope-Async"] == "enable"
    assert "X-DashScope-OssResourceResolve" not in request.headers
    assert json.loads(request.content) == {
        "model": "wan2.7-i2v",
        "input": {"media": [{"type": role, "url": _IMAGE}]},
        "parameters": {"format": "mp4"},
    }
    assert model.video_media_roles == ["reference_image"]
    assert model.additional_kwargs == {"format": "mp4"}


def test_mixed_media_preserves_sources_order_and_roles(
    model_factory: Callable[..., BaseVideoGenerationModelSetup],
    http_calls,
) -> None:
    model = model_factory()
    requests, responses = http_calls
    responses.append(httpx.Response(200, json={"output": {"task_id": "job-1"}}))
    audio = "https://example.test/drive.mp3"
    clip = "https://example.test/clip.mp4"
    document = "oss://dashscope-instant/demo/notes.pdf"
    page = "https://example.test/article"

    model.submit(
        "Animate these references.",
        media=[
            ImageBlock.from_url("image/png", _IMAGE),
            ImageBlock.from_base64("image/png", "YQ=="),
            AudioBlock.from_url("audio/mpeg", audio),
            AudioBlock.from_base64("audio/mpeg", "YQ=="),
            VideoBlock.from_url("video/mp4", clip),
            DocumentBlock.from_url("application/pdf", document),
            DocumentBlock.from_url("text/html", page),
        ],
        model_parameters={
            "video_media_roles": [
                "reference_image",
                "reference_image",
                "driving_audio",
                "driving_audio",
                "first_clip",
                "file",
                "custom-provider-role",
            ]
        },
    )

    [request] = requests
    assert request.headers["X-DashScope-OssResourceResolve"] == "enable"
    assert json.loads(request.content) == {
        "model": "custom-video-deployment",
        "input": {
            "prompt": "Animate these references.",
            "media": [
                {"type": "reference_image", "url": _IMAGE},
                {"type": "reference_image", "url": "data:image/png;base64,YQ=="},
                {"type": "driving_audio", "url": audio},
                {"type": "driving_audio", "url": "data:audio/mpeg;base64,YQ=="},
                {"type": "first_clip", "url": clip},
                {"type": "file", "url": document},
                {"type": "custom-provider-role", "url": page},
            ],
        },
    }


def test_empty_request_is_rejected_before_submission(
    model_factory: Callable[..., BaseVideoGenerationModelSetup],
    http_calls,
) -> None:
    model = model_factory()
    requests, _ = http_calls

    with pytest.raises(ValueError, match="requires a prompt or at least one media"):
        model.submit()

    assert requests == []


@pytest.mark.parametrize(
    ("prompt", "media"),
    [
        (None, None),
        ("Animate this.", [TextBlock(text="Not a media input.")]),
        ("Animate this.", ImageBlock.from_url("image/png", _IMAGE)),
    ],
)
def test_submit_rejects_invalid_inputs_before_request(
    model_factory: Callable[..., BaseVideoGenerationModelSetup],
    http_calls,
    prompt: Any,
    media: Any,
) -> None:
    model = model_factory()
    requests, _ = http_calls

    with pytest.raises(TypeError):
        model.submit(
            prompt,
            media=media,
            model_parameters={"video_media_roles": ["reference_image"]},
        )

    assert requests == []


@pytest.mark.parametrize(
    ("roles", "message"),
    [
        (None, "video_media_roles is required"),
        (["first_frame", "last_frame"], "one entry for each media block"),
    ],
)
def test_media_requires_one_role_per_block(
    model_factory: Callable[..., BaseVideoGenerationModelSetup],
    http_calls,
    roles: list[str] | None,
    message: str,
) -> None:
    model = model_factory()
    requests, _ = http_calls
    media = [ImageBlock.from_url("image/png", _IMAGE)]

    with pytest.raises(ValueError, match=message):
        model.submit(media=media, model_parameters={"video_media_roles": roles})

    assert requests == []


@pytest.mark.parametrize(
    "roles",
    ["first_frame", {"first_frame"}, {"first_frame": "reference_image"}, [1], [""]],
)
def test_roles_require_an_ordered_sequence_of_nonempty_strings(
    model_factory: Callable[..., BaseVideoGenerationModelSetup],
    http_calls,
    roles: Any,
) -> None:
    model = model_factory()
    requests, _ = http_calls

    with pytest.raises(TypeError, match="video_media_roles"):
        model.submit(
            media=[ImageBlock.from_url("image/png", _IMAGE)],
            model_parameters={"video_media_roles": roles},
        )

    assert requests == []


def test_fetch_reads_the_video_result(
    model_factory: Callable[..., BaseVideoGenerationModelSetup],
    http_calls,
) -> None:
    model = model_factory()
    calls, responses = http_calls
    responses.append(
        httpx.Response(
            200,
            json={
                "request_id": "req-1",
                "usage": {"duration": 5},
                "output": {"task_status": "SUCCEEDED", "video_url": _VIDEO},
            },
        )
    )
    job = MediaJob(job_id="job-1", submitted_at_ms=0)

    state = model.fetch(job)

    assert [(call.method, call.url.path) for call in calls] == [
        ("GET", "/api/v1/tasks/job-1")
    ]
    assert state.status is MediaJobStatus.SUCCEEDED
    assert isinstance(state.result, MediaResult[VideoBlock])
    restored = type(state).model_validate_json(state.model_dump_json())
    assert isinstance(restored.result, MediaResult[VideoBlock])
    assert len(state.result.content) == 1
    block = state.result.content[0]
    assert isinstance(block, VideoBlock)
    assert block.media_type == "video/mp4"
    assert block.source.url == _VIDEO
    assert state.result.expires_at_ms == 1_700_000_000_000
    assert state.result.usage == {"duration": 5}
    assert state.result.request_id == "req-1"


@pytest.mark.parametrize("source", ["/tmp/frame.png", "file:///tmp/frame.png"])
def test_submit_rejects_local_media_before_request(
    model_factory: Callable[..., BaseVideoGenerationModelSetup],
    http_calls,
    source: str,
) -> None:
    model = model_factory()
    requests, _ = http_calls

    with pytest.raises(UnsupportedContentBlockError):
        model.submit(
            "Animate this.",
            media=[ImageBlock.from_url("image/png", source)],
            model_parameters={"video_media_roles": ["first_frame"]},
        )

    assert requests == []


def test_text_submission_ignores_configured_video_media_roles_without_media(
    model_factory: Callable[..., BaseVideoGenerationModelSetup],
    http_calls,
) -> None:
    model = model_factory(video_media_roles=["first_frame"])
    requests, responses = http_calls
    responses.append(httpx.Response(200, json={"output": {"task_id": "job-1"}}))

    model.submit("a cat")

    [request] = requests
    assert json.loads(request.content) == {
        "model": "custom-video-deployment",
        "input": {"prompt": "a cat"},
    }


def test_explicit_call_video_media_roles_without_media_is_rejected(
    model_factory: Callable[..., BaseVideoGenerationModelSetup],
    http_calls,
) -> None:
    model = model_factory()
    requests, _ = http_calls

    with pytest.raises(ValueError, match="video_media_roles requires media blocks"):
        model.submit("a cat", model_parameters={"video_media_roles": ["first_frame"]})

    assert requests == []
