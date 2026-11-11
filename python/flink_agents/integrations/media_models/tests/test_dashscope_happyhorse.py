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
from types import SimpleNamespace
from typing import Any

import httpx
import pytest

from flink_agents.api.chat_message import ImageBlock, TextBlock, VideoBlock
from flink_agents.api.media_models.media_model import (
    MediaJobStatus,
    MediaTask,
)
from flink_agents.integrations.media_models.dashscope_media_model import (
    DashScopeMediaModel,
)

_IMAGE = "https://example.test/frame.png"
_VIDEO = "https://oss.example/out.mp4?Expires=1700000000"


class _Jobs:
    def __init__(self) -> None:
        self.created: list[dict[str, Any]] = []

    def create(self, **kwargs: Any) -> SimpleNamespace:
        self.created.append(kwargs)
        return SimpleNamespace(task_id="job-1")


def _model(model: str = "happyhorse-1.1-t2v", **overrides: Any) -> DashScopeMediaModel:
    values: dict[str, Any] = {
        "model": model,
        "task": MediaTask.VIDEO_GENERATION,
        "api_key": "sk-test",
        "workspace": "ws-1",
    }
    values.update(overrides)
    return DashScopeMediaModel(**values)


def _install(model: DashScopeMediaModel, jobs: _Jobs) -> None:
    model._client().videos.jobs = jobs


def test_text_to_video_sends_only_the_prompt() -> None:
    model = _model(parameters={"resolution": "720P"})
    jobs = _Jobs()
    _install(model, jobs)

    job = model.submit([TextBlock(text="a cat")], duration=5)

    assert job.provider == "dashscope"
    assert job.task is MediaTask.VIDEO_GENERATION
    assert job.expires_at_ms == job.submitted_at_ms + 24 * 60 * 60 * 1000
    assert jobs.created == [
        {
            "model": "happyhorse-1.1-t2v",
            "prompt": "a cat",
            "media": None,
            "parameters": {"resolution": "720P", "duration": 5},
        }
    ]


@pytest.mark.parametrize(
    ("kwargs", "role"),
    [
        ({}, "reference_image"),
        ({"video_media_roles": ("first_frame",)}, "first_frame"),
    ],
)
def test_image_to_video_uses_configured_or_call_time_roles(
    kwargs: dict[str, Any],
    role: str,
) -> None:
    parameters = {"video_media_roles": ["reference_image"], "format": "mp4"}
    model = _model(model="happyhorse-1.1-i2v", parameters=parameters)
    jobs = _Jobs()
    _install(model, jobs)

    model.submit(
        [ImageBlock.from_url("image/png", _IMAGE)],
        **kwargs,
    )

    assert jobs.created[0]["prompt"] is None
    assert jobs.created[0]["media"] == [{"type": role, "url": _IMAGE}]
    assert jobs.created[0]["parameters"] == {"format": "mp4"}
    assert model.parameters == parameters


def test_image_to_video_sends_base64_as_a_data_url() -> None:
    model = _model(model="happyhorse-1.1-i2v")
    jobs = _Jobs()
    _install(model, jobs)

    model.submit(
        [
            TextBlock(text="move"),
            ImageBlock.from_base64("image/png", "YQ=="),
        ],
        video_media_roles=["first_frame"],
    )

    assert jobs.created[0]["prompt"] == "move"
    assert jobs.created[0]["media"] == [
        {"type": "first_frame", "url": "data:image/png;base64,YQ=="}
    ]


def test_reference_to_video_keeps_image_order() -> None:
    model = _model(model="happyhorse-1.1-r2v")
    jobs = _Jobs()
    _install(model, jobs)
    images = [
        ImageBlock.from_url("image/png", f"https://example.test/{index}.png")
        for index in range(2)
    ]

    model.submit(
        [TextBlock(text="Keep the first reference ahead of the second."), *images],
        video_media_roles=["reference_image", "reference_image"],
    )

    assert [item["type"] for item in jobs.created[0]["media"]] == [
        "reference_image",
        "reference_image",
    ]
    assert [item["url"] for item in jobs.created[0]["media"]] == [
        "https://example.test/0.png",
        "https://example.test/1.png",
    ]


def test_reference_role_is_sent_as_given() -> None:
    model = _model(model="happyhorse-1.1-r2v")
    jobs = _Jobs()
    _install(model, jobs)

    model.submit(
        [
            TextBlock(text="Animate the reference image."),
            ImageBlock.from_url("image/png", _IMAGE),
        ],
        video_media_roles=["reference_image"],
    )

    assert jobs.created[0]["media"] == [{"type": "reference_image", "url": _IMAGE}]
    assert jobs.created[0]["parameters"] is None


@pytest.mark.parametrize(
    ("model_name", "content", "error"),
    [
        ("happyhorse-1.2-t2v", [], ValueError),
        (
            "happyhorse-1.2-t2v",
            [TextBlock(text="a cat"), ImageBlock.from_url("image/png", _IMAGE)],
            ValueError,
        ),
        (
            "happyhorse-1.2-t2v",
            [TextBlock(text="a cat"), TextBlock(text="again")],
            ValueError,
        ),
    ],
)
def test_submit_rejects_invalid_input(
    model_name: str, content: list, error: type[Exception]
) -> None:
    model = _model(model=model_name)
    _install(model, _Jobs())

    with pytest.raises(error):
        model.submit(content)


def test_media_requires_video_media_roles() -> None:
    model = _model(model="happyhorse-1.1-i2v")
    _install(model, _Jobs())

    with pytest.raises(ValueError, match="video_media_roles is required"):
        model.submit([ImageBlock.from_url("image/png", _IMAGE)])


def test_role_list_must_match_images() -> None:
    model = _model(model="happyhorse-1.1-i2v")
    _install(model, _Jobs())

    with pytest.raises(ValueError, match="each media block"):
        model.submit(
            [ImageBlock.from_url("image/png", _IMAGE)],
            video_media_roles=["first_frame", "last_frame"],
        )


@pytest.mark.parametrize(
    "roles",
    [
        "first_frame",
        {"first_frame"},
        {"first_frame": "reference_image"},
        [1],
        [""],
    ],
)
def test_video_media_roles_require_an_ordered_sequence_of_strings(roles: Any) -> None:
    model = _model(model="happyhorse-1.1-i2v")
    _install(model, _Jobs())

    with pytest.raises(TypeError, match="video_media_roles"):
        model.submit(
            [ImageBlock.from_url("image/png", _IMAGE)],
            video_media_roles=roles,
        )


def test_any_role_is_forwarded_for_an_image() -> None:
    model = _model(model="happyhorse-1.2-t2v")
    jobs = _Jobs()
    _install(model, jobs)

    model.submit(
        [ImageBlock.from_url("image/png", _IMAGE)],
        video_media_roles=["reference_image"],
    )

    assert jobs.created[0]["model"] == "happyhorse-1.2-t2v"
    assert jobs.created[0]["media"] == [{"type": "reference_image", "url": _IMAGE}]


def test_an_unlisted_model_name_is_forwarded() -> None:
    model = _model(model="wan2.6-t2v")
    jobs = _Jobs()
    _install(model, jobs)

    model.submit([TextBlock(text="a cat")])

    assert jobs.created[0]["model"] == "wan2.6-t2v"
    assert jobs.created[0]["prompt"] == "a cat"


def test_fetch_reads_the_video_url() -> None:
    model = _model()
    _install(model, _Jobs())

    def request(_method: str, _path: str, **_kwargs: Any) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "request_id": "req-1",
                "usage": {"duration": 5},
                "output": {"task_status": "SUCCEEDED", "video_url": _VIDEO},
            },
        )

    model._client().transport.request = request
    job = model.submit([TextBlock(text="a cat")])

    state = model.fetch(job)

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
