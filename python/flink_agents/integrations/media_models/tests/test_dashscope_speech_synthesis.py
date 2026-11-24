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
from types import SimpleNamespace
from typing import Any

import httpx
import pytest

from flink_agents.api.chat_message import AudioBlock
from flink_agents.api.media import BaseSpeechSynthesisModelSetup, MediaResult
from flink_agents.integrations.media_models.dashscope_media_model import (
    DashScopeMediaModelConnection,
    DashScopeSpeechSynthesisModelSetup,
)

_AUDIO = "https://oss.example/out.wav?Expires=1700000000"


@pytest.fixture
def model_factory(
    setup_factory: Callable[..., BaseSpeechSynthesisModelSetup],
) -> Callable[..., BaseSpeechSynthesisModelSetup]:
    def create(**overrides: Any) -> BaseSpeechSynthesisModelSetup:
        values: dict[str, Any] = {
            "model": "qwen-audio-3.0-tts-flash",
            "voice": "longanhuan",
        }
        values.update(overrides)
        return setup_factory(DashScopeSpeechSynthesisModelSetup, **values)

    return create


def _install(model: BaseSpeechSynthesisModelSetup) -> list[httpx.Request]:
    seen: list[httpx.Request] = []

    def send(request: httpx.Request, *_args: Any, **_kwargs: Any) -> httpx.Response:
        seen.append(request)
        payload = json.loads(request.content)
        input_payload = payload["input"]
        audio_format = input_payload.get("format") or "mp3"
        return httpx.Response(
            200,
            json={
                "request_id": "req-1",
                "usage": {"characters": len(input_payload["text"])},
                "output": {
                    "audio": {
                        "url": _AUDIO.replace(".wav", f".{audio_format}"),
                        "expires_at": 1_700_000_000,
                    }
                },
            },
            request=request,
        )

    model._client().transport.http_client.send = send
    return seen


def test_synthesize_sends_text_voice_and_format(
    model_factory: Callable[..., BaseSpeechSynthesisModelSetup],
) -> None:
    model = model_factory(
        format="mp3",
        language_type="Chinese",
        additional_kwargs={"volume": 50, "rate": 1.2},
    )
    seen = _install(model)

    result = model.synthesize(
        "Hello.",
        voice="longanhuan",
        model_parameters={
            "format": "wav",
            "sample_rate": 24000,
            "language_hints": ["zh"],
            "instruction": "Speak calmly.",
        },
    )

    assert len(seen) == 1
    request = seen[0]
    assert request.method == "POST"
    assert request.url.path.endswith("/services/audio/tts/SpeechSynthesizer")
    assert json.loads(request.content) == {
        "model": "qwen-audio-3.0-tts-flash",
        "input": {
            "text": "Hello.",
            "voice": "longanhuan",
            "format": "wav",
            "sample_rate": 24000,
            "language_type": "Chinese",
            "volume": 50,
            "rate": 1.2,
            "language_hints": ["zh"],
            "instruction": "Speak calmly.",
        },
    }
    assert isinstance(result, MediaResult[AudioBlock])
    assert model.voice == "longanhuan"
    assert model.format == "mp3"
    assert model.language_type == "Chinese"
    assert model.model_kwargs["voice"] == "longanhuan"
    block = result.content[0]
    assert isinstance(block, AudioBlock)
    assert block.media_type == "audio/wav"
    assert "out.wav" in block.source.url
    assert result.expires_at_ms == 1_700_000_000_000
    assert result.usage == {"characters": 6}
    assert result.request_id == "req-1"


def test_qwen3_tts_serializes_controls_into_input(
    model_factory: Callable[..., BaseSpeechSynthesisModelSetup],
) -> None:
    model = model_factory(model="qwen3-tts-flash", voice="Cherry")
    seen = _install(model)

    model.synthesize(
        "Hello.",
        model_parameters={
            "rate": 1.1,
            "volume": 80,
            "language_hints": ["en"],
            "instruction": "Warm tone.",
        },
    )

    assert len(seen) == 1
    request = seen[0]
    assert request.method == "POST"
    assert request.url.path.endswith("/services/aigc/multimodal-generation/generation")
    assert json.loads(request.content) == {
        "model": "qwen3-tts-flash",
        "input": {
            "text": "Hello.",
            "voice": "Cherry",
            "rate": 1.1,
            "volume": 80,
            "language_hints": ["en"],
            "instruction": "Warm tone.",
        },
    }


def test_setups_keep_model_voice_and_call_parameters_separate(
    connection_factory: Callable[..., DashScopeMediaModelConnection],
    setup_factory: Callable[..., BaseSpeechSynthesisModelSetup],
) -> None:
    connection = connection_factory()
    first = setup_factory(
        DashScopeSpeechSynthesisModelSetup,
        connection=connection,
        model="tts-a",
        voice="voice-a",
        additional_kwargs={"volume": 10},
    )
    second = setup_factory(
        DashScopeSpeechSynthesisModelSetup,
        connection=connection,
        model="tts-b",
        voice="voice-b",
        additional_kwargs={"volume": 20},
    )
    seen = _install(first)
    first.synthesize("one", model_parameters={"volume": 30})
    second.synthesize("two")

    bodies = [json.loads(req.content) for req in seen]
    assert [body["model"] for body in bodies] == ["tts-a", "tts-b"]
    assert [body["input"]["voice"] for body in bodies] == ["voice-a", "voice-b"]
    assert [body["input"]["volume"] for body in bodies] == [30, 20]
    assert all("parameters" not in body for body in bodies)
    assert first.voice == "voice-a"
    assert first.additional_kwargs == {"volume": 10}
    assert second.voice == "voice-b"
    assert second.additional_kwargs == {"volume": 20}


def test_synthesize_timeout_limits_the_http_request(
    model_factory: Callable[..., BaseSpeechSynthesisModelSetup],
) -> None:
    model = model_factory()
    seen = _install(model)

    model.synthesize("Hello.", timeout=12)

    assert seen[0].extensions["timeout"]["read"] == 12


def test_call_voice_overrides_the_configured_setup_voice(
    model_factory: Callable[..., BaseSpeechSynthesisModelSetup],
) -> None:
    model = model_factory()
    seen = _install(model)

    result = model.synthesize("Hello.", voice="longanyang")

    assert json.loads(seen[0].content) == {
        "model": "qwen-audio-3.0-tts-flash",
        "input": {"text": "Hello.", "voice": "longanyang"},
    }
    assert result.content[0].media_type == "audio/mpeg"


@pytest.mark.parametrize(
    ("setup_voice", "extra_kwargs", "call_voice", "call_parameters", "expected"),
    [
        ("bound", {}, None, None, "bound"),
        (None, {"voice": "generic"}, None, None, "generic"),
        ("bound", {"voice": "generic"}, None, None, "generic"),
        ("bound", {"voice": "generic"}, None, {"voice": "call-param"}, "call-param"),
        (
            "bound",
            {"voice": "generic"},
            "call-arg",
            {"voice": "call-param"},
            "call-arg",
        ),
    ],
)
def test_voice_follows_setup_and_call_precedence(
    model_factory: Callable[..., BaseSpeechSynthesisModelSetup],
    setup_voice: str | None,
    extra_kwargs: dict[str, Any],
    call_voice: str | None,
    call_parameters: dict[str, Any] | None,
    expected: str,
) -> None:
    snapshot = dict(extra_kwargs)
    model = model_factory(voice=setup_voice, additional_kwargs=extra_kwargs)
    seen = _install(model)

    model.synthesize("Hello.", voice=call_voice, model_parameters=call_parameters)

    assert json.loads(seen[0].content)["input"]["voice"] == expected
    assert extra_kwargs == snapshot


@pytest.mark.parametrize(
    ("audio_format", "media_type"),
    [("opus", "audio/ogg"), ("pcm", "audio/pcm")],
)
def test_format_maps_to_media_type(
    model_factory: Callable[..., BaseSpeechSynthesisModelSetup],
    audio_format: str,
    media_type: str,
) -> None:
    model = model_factory()
    _install(model)

    result = model.synthesize("Hello.", model_parameters={"format": audio_format})

    assert result.content[0].media_type == media_type


def test_synthesize_requires_a_voice(
    model_factory: Callable[..., BaseSpeechSynthesisModelSetup],
) -> None:
    model = model_factory(voice=None)
    seen = _install(model)

    with pytest.raises(ValueError, match="requires a voice"):
        model.synthesize("Hello.")

    assert seen == []


def test_synthesize_rejects_empty_text_before_request(
    model_factory: Callable[..., BaseSpeechSynthesisModelSetup],
) -> None:
    model = model_factory()
    seen = _install(model)

    with pytest.raises(ValueError):
        model.synthesize("")

    assert seen == []


@pytest.mark.parametrize(
    ("timeout", "error_type", "pattern"),
    [
        (0, ValueError, "greater than 0"),
        (-1.0, ValueError, "greater than 0"),
        (float("nan"), ValueError, "must be finite"),
        (float("inf"), ValueError, "must be finite"),
        (True, TypeError, "positive finite number"),
    ],
)
def test_synthesize_rejects_invalid_timeout_before_request(
    model_factory: Callable[..., BaseSpeechSynthesisModelSetup],
    timeout: Any,
    error_type: type[Exception],
    pattern: str,
) -> None:
    model = model_factory()
    seen = _install(model)

    with pytest.raises(error_type, match=pattern):
        model.synthesize("Hello.", timeout=timeout)

    assert seen == []


def test_bool_expires_at_falls_back_to_url_expiry(
    model_factory: Callable[..., BaseSpeechSynthesisModelSetup],
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


def test_synthesize_rejects_blank_call_voice_before_request(
    model_factory: Callable[..., BaseSpeechSynthesisModelSetup],
) -> None:
    model = model_factory()
    seen = _install(model)

    with pytest.raises(ValueError, match="requires a voice"):
        model.synthesize("Hello.", voice="   ")

    assert seen == []
