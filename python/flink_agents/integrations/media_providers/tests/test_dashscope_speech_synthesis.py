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

import pytest

from flink_agents.api.chat_message import AudioBlock
from flink_agents.api.media.media_model import (
    MediaResult,
    SpeechSynthesisModel,
)
from flink_agents.integrations.media_providers.dashscope_media_provider import (
    DashScopeMediaProvider,
)

_AUDIO = "https://oss.example/out.wav?Expires=1700000000"


@pytest.fixture
def model_factory(
    provider_factory: Callable[..., DashScopeMediaProvider],
) -> Callable[..., SpeechSynthesisModel]:
    def create(**overrides: Any) -> SpeechSynthesisModel:
        values: dict[str, Any] = {
            "model": "qwen-audio-3.0-tts-flash",
            "voice": "longanhuan",
        }
        values.update(overrides)
        provider = provider_factory()
        provider.open()
        return provider.speech(**values)

    return create


def _install(model: SpeechSynthesisModel) -> list[tuple[str, dict[str, Any]]]:
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


def test_synthesize_sends_text_voice_and_format(
    model_factory: Callable[..., SpeechSynthesisModel],
) -> None:
    model_parameters = {
        "volume": 50,
        "voice": "configured-voice",
        "format": "mp3",
        "language_type": "Chinese",
    }
    model = model_factory(model_parameters=model_parameters)
    seen = _install(model)

    result = model.synthesize(
        "Hello.",
        voice="longanhuan",
        model_parameters={"format": "wav", "sample_rate": 24000},
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
    assert isinstance(result, MediaResult[AudioBlock])
    assert model.model_parameters == {**model_parameters, "voice": "longanhuan"}
    block = result.content[0]
    assert isinstance(block, AudioBlock)
    assert block.media_type == "audio/wav"
    assert "out.wav" in block.source.url
    assert result.expires_at_ms == 1_700_000_000_000
    assert result.usage == {"characters": 6}
    assert result.request_id == "req-1"


def test_bound_models_keep_model_voice_and_call_parameters_separate(
    provider_factory: Callable[..., DashScopeMediaProvider],
) -> None:
    provider = provider_factory()
    provider.open()
    first = provider.speech("tts-a", voice="voice-a", model_parameters={"volume": 10})
    second = provider.speech("tts-b", voice="voice-b", model_parameters={"volume": 20})
    seen = _install(first)
    try:
        first.synthesize("one", model_parameters={"volume": 30})
        second.synthesize("two")

        assert [call[1]["model"] for call in seen] == ["tts-a", "tts-b"]
        assert [call[1]["voice"] for call in seen] == ["voice-a", "voice-b"]
        assert [call[1]["parameters"] for call in seen] == [
            {"volume": 30},
            {"volume": 20},
        ]
        assert first.model_parameters == {"volume": 10, "voice": "voice-a"}
        assert second.model_parameters == {"volume": 20, "voice": "voice-b"}
    finally:
        provider.close()


def test_synthesize_timeout_limits_the_http_request(
    model_factory: Callable[..., SpeechSynthesisModel],
) -> None:
    model = model_factory()
    seen = _install(model)

    model.synthesize("Hello.", timeout=12)

    assert seen[0][1]["timeout"] == 12


def test_call_voice_overrides_the_bound_model_voice(
    model_factory: Callable[..., SpeechSynthesisModel],
) -> None:
    model = model_factory()
    seen = _install(model)

    result = model.synthesize("Hello.", voice="longanyang")

    assert seen[0][1]["voice"] == "longanyang"
    assert seen[0][1]["parameters"] is None
    assert result.content[0].media_type == "audio/mpeg"


@pytest.mark.parametrize(
    ("bound_voice", "call_voice", "call_parameters", "expected"),
    [
        (None, None, None, "generic"),
        ("bound", None, None, "bound"),
        ("bound", None, {"voice": "call-param"}, "call-param"),
        ("bound", "call-arg", {"voice": "call-param"}, "call-arg"),
    ],
)
def test_voice_follows_binding_and_call_precedence(
    model_factory: Callable[..., SpeechSynthesisModel],
    bound_voice: str | None,
    call_voice: str | None,
    call_parameters: dict[str, Any] | None,
    expected: str,
) -> None:
    model_parameters = {"voice": "generic"}
    model = model_factory(voice=bound_voice, model_parameters=model_parameters)
    seen = _install(model)

    model.synthesize("Hello.", voice=call_voice, model_parameters=call_parameters)

    assert seen[0][1]["voice"] == expected
    assert model_parameters == {"voice": "generic"}


@pytest.mark.parametrize(
    ("audio_format", "media_type"),
    [("opus", "audio/ogg"), ("pcm", "audio/pcm")],
)
def test_format_maps_to_media_type(
    model_factory: Callable[..., SpeechSynthesisModel],
    audio_format: str,
    media_type: str,
) -> None:
    model = model_factory()
    _install(model)

    result = model.synthesize("Hello.", model_parameters={"format": audio_format})

    assert result.content[0].media_type == media_type


def test_synthesize_requires_a_voice(
    model_factory: Callable[..., SpeechSynthesisModel],
) -> None:
    model = model_factory(voice=None)
    seen = _install(model)

    with pytest.raises(ValueError, match="requires a voice"):
        model.synthesize("Hello.")

    assert seen == []


def test_synthesize_rejects_empty_text_before_request(
    model_factory: Callable[..., SpeechSynthesisModel],
) -> None:
    model = model_factory()
    seen = _install(model)

    with pytest.raises(ValueError):
        model.synthesize("")

    assert seen == []


@pytest.mark.parametrize(
    ("timeout", "error_type"),
    [
        (0, ValueError),
        (-1.0, ValueError),
        (float("nan"), ValueError),
        (float("inf"), ValueError),
        (True, TypeError),
    ],
)
def test_synthesize_rejects_invalid_timeout_before_request(
    model_factory: Callable[..., SpeechSynthesisModel],
    timeout: Any,
    error_type: type[Exception],
) -> None:
    model = model_factory()
    seen = _install(model)

    with pytest.raises(error_type, match="positive finite number"):
        model.synthesize("Hello.", timeout=timeout)

    assert seen == []


def test_bool_expires_at_falls_back_to_url_expiry(
    model_factory: Callable[..., SpeechSynthesisModel],
) -> None:
    model = model_factory()
    model._client().audio.tts.http.synthesize = lambda *_a, **_kw: SimpleNamespace(
        audio_url=_AUDIO,
        expires_at=True,
        usage=None,
        request_id="req-1",
    )

    result = model.synthesize("Hello.")

    assert result.expires_at_ms == 1_700_000_000_000
