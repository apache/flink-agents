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
import os
from typing import Any

import pytest

from flink_agents.api.chat_message import AudioBlock, ImageBlock, TextBlock, VideoBlock
from flink_agents.api.media import MediaJobError, MediaResult
from flink_agents.integrations.media_models.dashscope_media_model import (
    DashScopeImageGenerationModelSetup,
    DashScopeMediaModelConnection,
    DashScopeSpeechSynthesisModelSetup,
    DashScopeTranscriptionModelSetup,
    DashScopeVideoGenerationModelSetup,
)
from flink_agents.integrations.media_models.tests.conftest import (
    SingleConnectionContext,
)

pytestmark = pytest.mark.integration

_AUDIO = "https://dashscope.oss-cn-beijing.aliyuncs.com/audios/welcome.mp3"
_api_key_available = "DASHSCOPE_API_KEY" in os.environ


def _assert_http_url(url: str) -> None:
    assert url.startswith(("http://", "https://"))


def _open_setup(
    setup_cls: type[Any], **kwargs: Any
) -> tuple[DashScopeMediaModelConnection, Any]:
    connection = DashScopeMediaModelConnection()
    ctx = SingleConnectionContext(connection)
    model = setup_cls(connection="dashscope", resource_context=ctx, **kwargs)
    return connection, model


@pytest.mark.skipif(not _api_key_available, reason="DashScope API key is not set")
def test_transcription_returns_text() -> None:
    """A short public recording returns transcript text."""
    connection, model = _open_setup(DashScopeTranscriptionModelSetup, model="fun-asr")
    try:
        connection.open()
        model.open()
        result = model.transcribe(
            AudioBlock.from_url("audio/mpeg", _AUDIO), timeout=180
        )

        assert isinstance(result, MediaResult[TextBlock])
        assert result.content
        assert result.content[0].text
        assert "transcription" in result.metadata
    finally:
        model.close()
        connection.close()


@pytest.mark.skipif(not _api_key_available, reason="DashScope API key is not set")
def test_transcription_cancel_returns_or_reports_rejection() -> None:
    """Cancel returns successfully or reports UnsupportedOperation."""
    connection, model = _open_setup(DashScopeTranscriptionModelSetup, model="fun-asr")
    try:
        connection.open()
        model.open()
        job = model.submit(AudioBlock.from_url("audio/mpeg", _AUDIO))
        rejected: MediaJobError | None = None
        try:
            model.cancel(job)
        except MediaJobError as exc:
            rejected = exc
        if rejected is not None:
            assert rejected.code == "UnsupportedOperation"
    finally:
        model.close()
        connection.close()


@pytest.mark.skipif(not _api_key_available, reason="DashScope API key is not set")
def test_speech_synthesis_returns_an_audio_url() -> None:
    """Speech synthesis returns an MP3 URL without a task id."""
    connection, model = _open_setup(
        DashScopeSpeechSynthesisModelSetup,
        model="qwen-audio-3.0-tts-flash",
        voice="longanhuan_v3.6",
    )
    try:
        connection.open()
        model.open()
        result = model.synthesize("Hello.")

        assert isinstance(result, MediaResult[AudioBlock])
        block = result.content[0]
        assert isinstance(block, AudioBlock)
        assert block.media_type == "audio/mpeg"
        _assert_http_url(block.source.url)
    finally:
        model.close()
        connection.close()


@pytest.mark.skipif(not _api_key_available, reason="DashScope API key is not set")
def test_async_image_generation_returns_a_png_url() -> None:
    """Async image generation returns a PNG URL."""
    connection, model = _open_setup(
        DashScopeImageGenerationModelSetup, model="qwen-image-3.0"
    )
    try:
        connection.open()
        model.open()
        result = model.generate(
            "A red square.",
            model_parameters={"size": "512*512", "prompt_extend": False},
            timeout=180,
        )

        assert isinstance(result, MediaResult[ImageBlock])
        block = result.content[0]
        assert isinstance(block, ImageBlock)
        assert block.media_type == "image/png"
        _assert_http_url(block.source.url)
    finally:
        model.close()
        connection.close()


@pytest.mark.skipif(not _api_key_available, reason="DashScope API key is not set")
def test_video_generation_returns_an_mp4_url() -> None:
    """The shortest HappyHorse text-to-video request returns an MP4 URL."""
    connection, model = _open_setup(
        DashScopeVideoGenerationModelSetup, model="happyhorse-1.1-t2v"
    )
    try:
        connection.open()
        model.open()
        result = model.generate(
            "A red square.",
            model_parameters={"resolution": "480P", "ratio": "16:9", "duration": 3},
            timeout=300,
        )

        assert isinstance(result, MediaResult[VideoBlock])
        block = result.content[0]
        assert isinstance(block, VideoBlock)
        assert block.media_type == "video/mp4"
        _assert_http_url(block.source.url)
    finally:
        model.close()
        connection.close()
