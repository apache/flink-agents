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
    DataContentBlock,
    DocumentBlock,
    ImageBlock,
    TextBlock,
    VideoBlock,
)
from flink_agents.api.media_models.media_model import MediaJobStatus, MediaTask
from flink_agents.integrations.media_models.dashscope_media_model import (
    DashScopeMediaModel,
)

_IMAGE = "https://example.test/frame.png"
_VIDEO = "https://oss.example/out.mp4?Expires=1700000000"


def _model(**overrides: Any) -> DashScopeMediaModel:
    values: dict[str, Any] = {
        "model": "custom-video-deployment",
        "task": MediaTask.VIDEO_GENERATION,
        "api_key": "sk-test",
        "workspace": "ws-1",
    }
    values.update(overrides)
    return DashScopeMediaModel(**values)


def test_text_submission_forwards_model_and_parameters(
    sdk_jobs: SimpleNamespace,
) -> None:
    parameters = {"resolution": "720P", "duration": 3}
    model = _model(parameters=parameters)
    model._client().videos.jobs = sdk_jobs

    job = model.submit([TextBlock(text="a cat")], duration=5)

    assert job.job_id == "job-1"
    assert job.provider == "dashscope"
    assert job.model == "custom-video-deployment"
    assert job.task is MediaTask.VIDEO_GENERATION
    assert job.expires_at_ms == job.submitted_at_ms + 24 * 60 * 60 * 1000
    assert sdk_jobs.created == [
        {
            "model": "custom-video-deployment",
            "prompt": "a cat",
            "media": None,
            "parameters": {"resolution": "720P", "duration": 5},
        }
    ]
    assert model.parameters == parameters


@pytest.mark.parametrize(
    ("kwargs", "role"),
    [
        ({}, "reference_image"),
        ({"video_media_roles": ("first_frame",)}, "first_frame"),
    ],
)
def test_media_roles_use_configuration_or_call_override(
    sdk_jobs: SimpleNamespace, kwargs: dict[str, Any], role: str
) -> None:
    parameters = {"video_media_roles": ["reference_image"], "format": "mp4"}
    model = _model(parameters=parameters)
    model._client().videos.jobs = sdk_jobs

    model.submit([ImageBlock.from_url("image/png", _IMAGE)], **kwargs)

    assert sdk_jobs.created[0]["prompt"] is None
    assert sdk_jobs.created[0]["media"] == [{"type": role, "url": _IMAGE}]
    assert sdk_jobs.created[0]["parameters"] == {"format": "mp4"}
    assert model.parameters == parameters


def test_mixed_media_preserves_sources_order_and_roles(
    sdk_jobs: SimpleNamespace,
) -> None:
    model = _model()
    model._client().videos.jobs = sdk_jobs
    audio = "https://example.test/drive.mp3"
    clip = "https://example.test/clip.mp4"
    document = "oss://dashscope-instant/demo/notes.pdf"
    page = "https://example.test/article"

    model.submit(
        [
            ImageBlock.from_url("image/png", _IMAGE),
            TextBlock(text="Animate these references."),
            ImageBlock.from_base64("image/png", "YQ=="),
            AudioBlock.from_url("audio/mpeg", audio),
            AudioBlock.from_base64("audio/mpeg", "YQ=="),
            VideoBlock.from_url("video/mp4", clip),
            DocumentBlock.from_url("application/pdf", document),
            DocumentBlock.from_url("text/html", page),
        ],
        video_media_roles=[
            "reference_image",
            "reference_image",
            "driving_audio",
            "driving_audio",
            "first_clip",
            "file",
            "custom-provider-role",
        ],
    )

    assert sdk_jobs.created == [
        {
            "model": "custom-video-deployment",
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
            "parameters": None,
        }
    ]


@pytest.mark.parametrize(
    ("content", "kwargs", "message"),
    [
        ([], {}, "requires a text block or at least one media block"),
        ([TextBlock(text="a"), TextBlock(text="b")], {}, "accepts at most one"),
        (
            [ImageBlock.from_url("image/png", _IMAGE)],
            {},
            "video_media_roles is required",
        ),
        (
            [ImageBlock.from_url("image/png", _IMAGE)],
            {"video_media_roles": ["first_frame", "last_frame"]},
            "one entry for each media block",
        ),
    ],
)
def test_invalid_input_is_rejected_before_submission(
    sdk_jobs: SimpleNamespace,
    content: list[DataContentBlock],
    kwargs: dict[str, Any],
    message: str,
) -> None:
    model = _model()
    model._client().videos.jobs = sdk_jobs

    with pytest.raises(ValueError, match=message):
        model.submit(content, **kwargs)

    assert sdk_jobs.created == []


@pytest.mark.parametrize(
    "roles",
    ["first_frame", {"first_frame"}, {"first_frame": "reference_image"}, [1], [""]],
)
def test_roles_require_an_ordered_sequence_of_nonempty_strings(
    sdk_jobs: SimpleNamespace, roles: Any
) -> None:
    model = _model()
    model._client().videos.jobs = sdk_jobs

    with pytest.raises(TypeError, match="video_media_roles"):
        model.submit(
            [ImageBlock.from_url("image/png", _IMAGE)], video_media_roles=roles
        )

    assert sdk_jobs.created == []


def test_fetch_reads_the_video_result(
    sdk_jobs: SimpleNamespace,
    task_responses: Callable[..., list[dict[str, Any]]],
) -> None:
    model = _model()
    model._client().videos.jobs = sdk_jobs
    calls = task_responses(
        model,
        httpx.Response(
            200,
            json={
                "request_id": "req-1",
                "usage": {"duration": 5},
                "output": {"task_status": "SUCCEEDED", "video_url": _VIDEO},
            },
        ),
    )
    job = model.submit([TextBlock(text="a cat")])

    state = model.fetch(job)

    assert [(call["method"], call["path"]) for call in calls] == [
        ("GET", "/tasks/job-1")
    ]
    assert state.status is MediaJobStatus.SUCCEEDED
    assert state.result is not None
    assert len(state.result.content) == 1
    block = state.result.content[0]
    assert isinstance(block, VideoBlock)
    assert block.media_type == "video/mp4"
    assert block.source.url == _VIDEO
    assert state.result.expires_at_ms == 1_700_000_000_000
    assert state.result.usage == {"duration": 5}
    assert state.result.request_id == "req-1"
