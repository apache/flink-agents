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
import os

import pytest

from flink_agents.api.chat_message import AudioBlock, ImageBlock, TextBlock, VideoBlock
from flink_agents.api.media_models.media_model import MediaJobError, MediaTask
from flink_agents.integrations.media_models.dashscope_media_model import (
    DashScopeMediaModel,
)

pytestmark = pytest.mark.integration

_AUDIO = "https://dashscope.oss-cn-beijing.aliyuncs.com/audios/welcome.mp3"
_api_key_available = "DASHSCOPE_API_KEY" in os.environ


def _model(task: MediaTask, model: str, **overrides: object) -> DashScopeMediaModel:
    values: dict[str, object] = {
        "model": model,
        "task": task,
        "poll_interval": 2,
    }
    values.update(overrides)
    return DashScopeMediaModel(**values)


def _assert_http_url(url: str) -> None:
    assert url.startswith(("http://", "https://"))


@pytest.mark.skipif(not _api_key_available, reason="DashScope API key is not set")
def test_transcription_returns_text() -> None:
    """A short public recording returns transcript text."""
    result = _model(MediaTask.TRANSCRIPTION, "fun-asr", wait_timeout=180).generate(
        [AudioBlock.from_url("audio/mpeg", _AUDIO)]
    )

    assert result.content
    assert result.content[0].text
    assert "transcription" in result.metadata


@pytest.mark.skipif(not _api_key_available, reason="DashScope API key is not set")
def test_transcription_cancel_is_only_accepted_while_pending() -> None:
    """Cancel either stops a pending task or reports that it is no longer pending."""
    model = _model(MediaTask.TRANSCRIPTION, "fun-asr")
    job = model.submit([AudioBlock.from_url("audio/mpeg", _AUDIO)])
    rejected: MediaJobError | None = None
    try:
        model.cancel(job)
    except MediaJobError as exc:
        rejected = exc
    if rejected is not None:
        assert rejected.code == "UnsupportedOperation"


@pytest.mark.skipif(not _api_key_available, reason="DashScope API key is not set")
def test_speech_synthesis_returns_an_audio_url() -> None:
    """Speech synthesis returns an MP3 URL without a task id."""
    result = _model(MediaTask.SPEECH_SYNTHESIS, "qwen-audio-3.0-tts-flash").generate(
        [TextBlock(text="Hello.")],
        voice="longanhuan_v3.6",
    )

    block = result.content[0]
    assert isinstance(block, AudioBlock)
    assert block.media_type == "audio/mpeg"
    _assert_http_url(block.source.url)


@pytest.mark.skipif(not _api_key_available, reason="DashScope API key is not set")
def test_async_image_generation_returns_a_png_url() -> None:
    """Async image generation returns a PNG URL."""
    result = _model(
        MediaTask.IMAGE_GENERATION, "qwen-image-3.0", wait_timeout=180
    ).generate(
        [TextBlock(text="A red square.")],
        size="512*512",
        prompt_extend=False,
    )

    block = result.content[0]
    assert isinstance(block, ImageBlock)
    assert block.media_type == "image/png"
    _assert_http_url(block.source.url)


@pytest.mark.skipif(not _api_key_available, reason="DashScope API key is not set")
def test_video_generation_returns_an_mp4_url() -> None:
    """The shortest HappyHorse text-to-video request returns an MP4 URL."""
    result = _model(
        MediaTask.VIDEO_GENERATION,
        "happyhorse-1.1-t2v",
        wait_timeout=300,
    ).generate(
        [TextBlock(text="A red square.")],
        resolution="480P",
        ratio="16:9",
        duration=3,
    )

    block = result.content[0]
    assert isinstance(block, VideoBlock)
    assert block.media_type == "video/mp4"
    _assert_http_url(block.source.url)
