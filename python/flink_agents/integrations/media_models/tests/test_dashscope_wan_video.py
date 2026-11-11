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

import pytest

from flink_agents.api.chat_message import (
    AudioBlock,
    DocumentBlock,
    ImageBlock,
    TextBlock,
    VideoBlock,
)
from flink_agents.api.media_models.media_model import MediaTask
from flink_agents.integrations.media_models.dashscope_media_model import (
    DashScopeMediaModel,
)

_IMAGE = "https://example.test/frame.png"
_AUDIO = "https://example.test/drive.mp3"
_CLIP = "https://example.test/clip.mp4"
_FILE = "oss://dashscope-instant/demo/notes.pdf"
_PAGE = "https://example.test/article"


class _Jobs:
    def __init__(self) -> None:
        self.created: list[dict[str, Any]] = []

    def create(self, **kwargs: Any) -> SimpleNamespace:
        self.created.append(kwargs)
        return SimpleNamespace(task_id="job-1")


def _model(model: str) -> DashScopeMediaModel:
    return DashScopeMediaModel(
        model=model,
        task=MediaTask.VIDEO_GENERATION,
        api_key="sk-test",
        workspace="ws-1",
    )


def _install(model: DashScopeMediaModel) -> _Jobs:
    jobs = _Jobs()
    model._client().videos.jobs = jobs
    return jobs


def test_wan27_sends_a_first_frame() -> None:
    model = _model("wan2.7-i2v-2026-04-25")
    jobs = _install(model)

    model.submit(
        [ImageBlock.from_url("image/png", _IMAGE)],
        video_media_roles=["first_frame"],
        duration=2,
        resolution="720P",
    )

    assert jobs.created[0]["model"] == "wan2.7-i2v-2026-04-25"
    assert jobs.created[0]["prompt"] is None
    assert jobs.created[0]["media"] == [{"type": "first_frame", "url": _IMAGE}]
    assert jobs.created[0]["parameters"] == {"duration": 2, "resolution": "720P"}


def test_wan27_sends_frames_and_driving_audio() -> None:
    model = _model("wan2.7-i2v")
    jobs = _install(model)

    model.submit(
        [
            TextBlock(text="Walk from the first frame to the last."),
            ImageBlock.from_url("image/png", _IMAGE),
            ImageBlock.from_url("image/png", "https://example.test/last.png"),
            AudioBlock.from_url("audio/mpeg", _AUDIO),
        ],
        video_media_roles=["first_frame", "last_frame", "driving_audio"],
    )

    assert jobs.created[0]["prompt"] == "Walk from the first frame to the last."
    assert jobs.created[0]["media"] == [
        {"type": "first_frame", "url": _IMAGE},
        {"type": "last_frame", "url": "https://example.test/last.png"},
        {"type": "driving_audio", "url": _AUDIO},
    ]


def test_wan27_sends_a_first_clip() -> None:
    model = _model("wan2.7-i2v")
    jobs = _install(model)

    model.submit(
        [VideoBlock.from_url("video/mp4", _CLIP)],
        video_media_roles=["first_clip"],
    )

    assert jobs.created[0]["media"] == [{"type": "first_clip", "url": _CLIP}]


def test_wan27_forwards_an_image_with_a_video() -> None:
    model = _model("wan2.7-i2v")
    jobs = _install(model)

    model.submit(
        [
            ImageBlock.from_url("image/png", _IMAGE),
            VideoBlock.from_url("video/mp4", _CLIP),
        ],
        video_media_roles=["first_frame", "first_clip"],
    )

    assert [item["type"] for item in jobs.created[0]["media"]] == [
        "first_frame",
        "first_clip",
    ]


def test_wan27_forwards_text_without_media() -> None:
    model = _model("wan2.7-i2v")
    jobs = _install(model)

    model.submit([TextBlock(text="A person walking toward the camera.")])

    assert jobs.created[0]["prompt"] == "A person walking toward the camera."
    assert jobs.created[0]["media"] is None


def test_base64_audio_is_forwarded_as_a_data_url() -> None:
    model = _model("wan2.7-i2v")
    jobs = _install(model)

    model.submit(
        [
            ImageBlock.from_url("image/png", _IMAGE),
            AudioBlock.from_base64("audio/mpeg", "YQ=="),
        ],
        video_media_roles=["first_frame", "driving_audio"],
    )

    assert jobs.created[0]["media"][1] == {
        "type": "driving_audio",
        "url": "data:audio/mpeg;base64,YQ==",
    }


def test_wan3_accepts_text_without_media() -> None:
    model = _model("wan3.0-video")
    jobs = _install(model)

    model.submit(
        [TextBlock(text="A cat walking across the grass.")],
        duration=2,
        resolution="480P",
    )

    assert jobs.created[0]["prompt"] == "A cat walking across the grass."
    assert jobs.created[0]["media"] is None
    assert jobs.created[0]["parameters"] == {"duration": 2, "resolution": "480P"}


def test_wan3_requires_explicit_roles_for_media() -> None:
    model = _model("wan3.0-video-prime")
    _install(model)

    with pytest.raises(ValueError, match="video_media_roles"):
        model.submit([ImageBlock.from_url("image/png", _IMAGE)])


def test_wan3_sends_file_and_reference_image() -> None:
    model = _model("wan3.0-video")
    jobs = _install(model)

    model.submit(
        [
            TextBlock(text="Follow the storyboard in the file."),
            DocumentBlock.from_url("application/pdf", _FILE),
            ImageBlock.from_url("image/png", _IMAGE),
        ],
        video_media_roles=["file", "reference_image"],
    )

    assert jobs.created[0]["media"] == [
        {"type": "file", "url": _FILE},
        {"type": "reference_image", "url": _IMAGE},
    ]


def test_wan3_forwards_roles_without_combination_checks() -> None:
    model = _model("wan3.0-video")
    jobs = _install(model)

    model.submit(
        [
            ImageBlock.from_url("image/png", _IMAGE),
            DocumentBlock.from_url("text/html", _PAGE),
        ],
        video_media_roles=["first_frame", "file"],
    )
    model.submit(
        [
            DocumentBlock.from_url("text/html", _PAGE),
            DocumentBlock.from_url("application/pdf", _FILE),
        ],
        video_media_roles=["link", "file"],
    )

    assert [item["type"] for item in jobs.created[0]["media"]] == [
        "first_frame",
        "file",
    ]
    assert [item["type"] for item in jobs.created[1]["media"]] == ["link", "file"]
    assert jobs.created[1]["media"][1]["url"] == _FILE
