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
from collections.abc import Mapping, Sequence
from typing import Any

import pytest
from pydantic import ValidationError

from flink_agents.api.chat_message import (
    AudioBlock,
    DocumentBlock,
    ImageBlock,
    TextBlock,
    VideoBlock,
)
from flink_agents.api.media import (
    AsyncJobModel,
    BaseImageGenerationModelSetup,
    BaseMediaModelConnection,
    BaseSpeechSynthesisModelSetup,
    BaseTranscriptionModelSetup,
    BaseVideoGenerationModelSetup,
    MediaResult,
)
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.resource_context import ResourceContext
from flink_agents.api.tools.tool import Tool


class _RecordingConnection(BaseMediaModelConnection):
    closed: bool = False

    def close(self) -> None:
        self.closed = True


class _FakeSpeech(BaseSpeechSynthesisModelSetup):
    def synthesize(
        self,
        text: str,
        *,
        voice: str | None = None,
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[AudioBlock]:
        return MediaResult[AudioBlock](
            content=[
                AudioBlock.from_url("audio/wav", "https://example.test/speech.wav")
            ],
            metadata={"text": text, "timeout": timeout},
        )


class _FakeTranscription(BaseTranscriptionModelSetup):
    def transcribe(
        self,
        audio: AudioBlock | VideoBlock,
        *,
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[TextBlock]:
        return MediaResult[TextBlock](content=[TextBlock(text="ok")])


class _FakeImage(BaseImageGenerationModelSetup):
    def generate(
        self,
        prompt: str,
        *,
        images: Sequence[ImageBlock] | None = None,
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[ImageBlock]:
        return MediaResult[ImageBlock](
            content=[ImageBlock.from_url("image/png", "https://example.test/img.png")],
            metadata={"prompt": prompt},
        )


class _FakeVideo(BaseVideoGenerationModelSetup):
    def generate(
        self,
        prompt: str = "",
        *,
        media: Sequence[ImageBlock | AudioBlock | VideoBlock | DocumentBlock]
        | None = None,
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[VideoBlock]:
        return MediaResult[VideoBlock](content=[])


class _MappingContext(ResourceContext):
    def __init__(self, resources: dict[tuple[str, ResourceType], Resource]) -> None:
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


def test_setup_validation_and_defaults() -> None:
    for cls in (_FakeSpeech, _FakeTranscription, _FakeImage, _FakeVideo):
        assert cls.resource_type() is ResourceType.MEDIA_MODEL
        assert "model_parameters" not in cls.model_fields
        with pytest.raises(ValidationError):
            cls(model="m")
        with pytest.raises(ValueError, match="non-blank"):
            cls(connection="   ", model="m")
        with pytest.raises(ValueError, match="non-blank"):
            cls(connection="c", model="")
        with pytest.raises(ValueError, match="Extra inputs are not permitted"):
            cls(connection="c", model="m", unknown_field=1)
        assert cls(connection="c", model="m").model_kwargs == {"model": "m"}

    with pytest.raises(ValueError, match="voice"):
        _FakeSpeech(connection="c", model="m", voice="   ")
    with pytest.raises(ValueError):
        _FakeImage(connection="c", model="m", voice="unsupported")


def test_capability_identity_is_not_a_configuration_field() -> None:
    model = _FakeImage(connection="media", model="qwen-image")
    assert not hasattr(model, "task")
    assert "_task" not in model.model_dump()
    with pytest.raises(ValueError, match="Extra inputs are not permitted"):
        _FakeImage(connection="media", model="qwen-image", _task="transcription")


def test_sync_setups_have_no_job_operations() -> None:
    speech = _FakeSpeech(connection="media", model="fake-speech")
    image = _FakeImage(connection="media", model="fake-image")
    assert speech.synthesize("Hello", timeout=10).metadata == {
        "text": "Hello",
        "timeout": 10,
    }
    for model in (speech, image):
        assert not isinstance(model, AsyncJobModel)
        assert not any(
            hasattr(model, name) for name in ("submit", "fetch", "wait", "cancel")
        )


def test_connection_resolution_and_lifecycle() -> None:
    setup = _FakeImage(connection="media", model="qwen-image-3.0")
    with pytest.raises(RuntimeError, match="ResourceContext is required"):
        setup.open()

    wrong_ctx = _MappingContext(
        {
            ("media", ResourceType.MEDIA_MODEL_CONNECTION): _FakeImage(
                connection="other", model="x"
            )
        }  # type: ignore[dict-item]
    )
    with pytest.raises(TypeError, match="not a BaseMediaModelConnection"):
        _FakeImage(
            connection="media", model="qwen-image-3.0", resource_context=wrong_ctx
        ).open()

    conn = _RecordingConnection()
    ok_ctx = _MappingContext({("media", ResourceType.MEDIA_MODEL_CONNECTION): conn})
    declarative = _FakeImage(
        connection="media", model="qwen-image-3.0", resource_context=ok_ctx
    )
    with pytest.raises(TypeError, match=r"Call open\(\)"):
        declarative._get_connection()

    declarative.open()
    declarative.open()
    assert declarative._get_connection() is conn
    declarative.close()
    assert conn.closed is False
    with pytest.raises(TypeError, match=r"Call open\(\)"):
        declarative._get_connection()


def test_connection_rejects_extra_fields() -> None:
    assert _RecordingConnection.resource_type() is ResourceType.MEDIA_MODEL_CONNECTION
    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        _RecordingConnection(unknown_field="x")  # type: ignore[call-arg]
