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
    ImageBlock,
    TextBlock,
    UnsupportedContentBlockError,
)
from flink_agents.api.media_models.media_model import MediaTask
from flink_agents.integrations.media_models.dashscope_media_model import (
    DashScopeMediaModel,
)

_AUDIO = "https://oss.example/out.wav?Expires=1700000000"


def _model(**overrides: Any) -> DashScopeMediaModel:
    values: dict[str, Any] = {
        "model": "qwen-audio-3.0-tts-flash",
        "task": MediaTask.SPEECH_SYNTHESIS,
        "api_key": "sk-test",
        "workspace": "ws-1",
        "voice": "longanhuan",
    }
    values.update(overrides)
    return DashScopeMediaModel(**values)


def _install(model: DashScopeMediaModel) -> list[tuple[str, dict[str, Any]]]:
    seen: list[tuple[str, dict[str, Any]]] = []

    def synthesize(text: str, **kwargs: Any) -> SimpleNamespace:
        seen.append((text, kwargs))
        audio_format = kwargs.get("format") or "mp3"
        return SimpleNamespace(
            audio_url=_AUDIO.replace(".wav", f".{audio_format}"),
            expires_at=1_700_000_000,
            usage={"characters": len(text)},
            request_id="req-1",
        )

    model._client().audio.tts.http.synthesize = synthesize
    return seen


def test_generate_sends_text_voice_and_format() -> None:
    parameters = {
        "volume": 50,
        "voice": "configured-voice",
        "format": "mp3",
        "language_type": "Chinese",
    }
    model = _model(parameters=parameters)
    seen = _install(model)

    result = model.generate(
        [TextBlock(text="Hello.")],
        voice="longanhuan",
        format="wav",
        sample_rate=24000,
    )

    assert seen == [
        (
            "Hello.",
            {
                "model": "qwen-audio-3.0-tts-flash",
                "voice": "longanhuan",
                "format": "wav",
                "sample_rate": 24000,
                "language_type": "Chinese",
                "parameters": {"volume": 50},
                "timeout": None,
            },
        )
    ]
    assert model.parameters == parameters
    block = result.content[0]
    assert isinstance(block, AudioBlock)
    assert block.media_type == "audio/wav"
    assert "out.wav" in block.source.url
    assert result.expires_at_ms == 1_700_000_000_000
    assert result.usage == {"characters": 6}
    assert result.request_id == "req-1"


def test_generate_timeout_limits_the_http_request() -> None:
    model = _model()
    seen = _install(model)

    model.generate([TextBlock(text="Hello.")], timeout=12)

    assert seen[0][1]["timeout"] == 12


def test_call_voice_overrides_the_resource_voice() -> None:
    model = _model()
    seen = _install(model)

    result = model.generate([TextBlock(text="Hello.")], voice="longanyang")

    assert seen[0][1]["voice"] == "longanyang"
    assert seen[0][1]["parameters"] is None
    assert result.content[0].media_type == "audio/mpeg"


@pytest.mark.parametrize(
    ("audio_format", "media_type"),
    [("opus", "audio/ogg"), ("pcm", "audio/pcm")],
)
def test_format_maps_to_media_type(audio_format: str, media_type: str) -> None:
    model = _model()
    _install(model)

    result = model.generate([TextBlock(text="Hello.")], format=audio_format)

    assert result.content[0].media_type == media_type


def test_generate_requires_text_and_voice() -> None:
    model = _model(voice=None)
    _install(model)

    with pytest.raises(ValueError, match="requires a voice"):
        model.generate([TextBlock(text="Hello.")])
    model.voice = "longanhuan"
    with pytest.raises(ValueError, match="requires a text block"):
        model.generate([])
    with pytest.raises(ValueError, match="Speech synthesis accepts at most one"):
        model.generate([TextBlock(text="a"), TextBlock(text="b")])


def test_generate_rejects_non_text_blocks() -> None:
    model = _model()
    _install(model)

    with pytest.raises(UnsupportedContentBlockError):
        model.generate(
            [TextBlock(text="Hello."), ImageBlock.from_url("image/png", "https://x")]
        )
