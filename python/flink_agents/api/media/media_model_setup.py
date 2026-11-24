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
"""Capability-specific media model setup base classes."""

from abc import ABC, abstractmethod
from collections.abc import Mapping, Sequence
from enum import Enum
from typing import Any, ClassVar

from pydantic import (
    ConfigDict,
    Field,
    PrivateAttr,
    field_validator,
)
from typing_extensions import override

from flink_agents.api.chat_message import (
    AudioBlock,
    DocumentBlock,
    ImageBlock,
    TextBlock,
    VideoBlock,
)
from flink_agents.api.media.media_model_connection import BaseMediaModelConnection
from flink_agents.api.media.media_result import MediaResult
from flink_agents.api.resource import Resource, ResourceType


class _MediaTask(str, Enum):
    """Internal task identifier used for cross-language runtime dispatch."""

    SPEECH_SYNTHESIS = "speech_synthesis"
    TRANSCRIPTION = "transcription"
    IMAGE_GENERATION = "image_generation"
    VIDEO_GENERATION = "video_generation"


class BaseMediaModelSetup(Resource, ABC):
    """Abstract base class for declared media model setups.

    Concrete capability setups extend one of the four task-specific subclasses
    (``BaseSpeechSynthesisModelSetup``, ``BaseTranscriptionModelSetup``,
    ``BaseImageGenerationModelSetup``, or ``BaseVideoGenerationModelSetup``) and
    also implement ``AsyncJobModel`` when backed by resumable provider jobs.

    Attributes:
    ----------
    connection : str
        Name of the ``MEDIA_MODEL_CONNECTION`` resource used by this setup.
    model : str
        Target media model identifier.
    """

    model_config = ConfigDict(extra="forbid", arbitrary_types_allowed=True)

    _task: ClassVar[_MediaTask]

    connection: str = Field(
        description="Name of the MEDIA_MODEL_CONNECTION resource.",
    )
    model: str = Field(description="Target media model identifier.")
    _resolved_connection: BaseMediaModelConnection | None = PrivateAttr(default=None)

    @field_validator("connection")
    @classmethod
    def _check_connection(cls, value: str) -> str:
        if not value.strip():
            msg = "connection must be a non-blank string."
            raise ValueError(msg)
        return value

    @field_validator("model")
    @classmethod
    def _check_model(cls, value: str) -> str:
        if not value.strip():
            msg = "model must be a non-blank string."
            raise ValueError(msg)
        return value

    @property
    def model_kwargs(self) -> dict[str, Any]:
        """Return default provider parameters configured on this setup."""
        return {"model": self.model}

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return resource type identifier for media model setups."""
        return ResourceType.MEDIA_MODEL

    @override
    def open(self) -> None:
        """Resolve the configured connection."""
        if self._resolved_connection is not None:
            return
        if self.resource_context is None:
            msg = (
                "ResourceContext is required to resolve media model connection "
                f"'{self.connection}'."
            )
            raise RuntimeError(msg)
        resource = self.resource_context.get_resource(
            self.connection, ResourceType.MEDIA_MODEL_CONNECTION
        )
        if not isinstance(resource, BaseMediaModelConnection):
            msg = (
                f"Resource '{self.connection}' of type MEDIA_MODEL_CONNECTION "
                f"is not a BaseMediaModelConnection: {type(resource).__name__}."
            )
            raise TypeError(msg)
        self._resolved_connection = resource

    @override
    def close(self) -> None:
        """Detach the connection reference without closing the shared connection."""
        self._resolved_connection = None

    def _get_connection(self) -> BaseMediaModelConnection:
        if self._resolved_connection is None:
            msg = "Media model setup is not open. Call open() before using it."
            raise TypeError(msg)
        return self._resolved_connection


class BaseSpeechSynthesisModelSetup(BaseMediaModelSetup, ABC):
    """Base setup class for text-to-speech synthesis capabilities."""

    _task: ClassVar[_MediaTask] = _MediaTask.SPEECH_SYNTHESIS

    voice: str | None = Field(
        default=None,
        description="Optional default voice identifier for speech synthesis.",
    )

    @field_validator("voice")
    @classmethod
    def _check_voice(cls, value: str | None) -> str | None:
        if value is not None and not value.strip():
            msg = "voice must be a non-blank string."
            raise ValueError(msg)
        return value

    @abstractmethod
    def synthesize(
        self,
        text: str,
        *,
        voice: str | None = None,
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[AudioBlock]:
        """Synthesize speech audio from input text."""


class BaseTranscriptionModelSetup(BaseMediaModelSetup, ABC):
    """Base setup class for audio or video transcription capabilities."""

    _task: ClassVar[_MediaTask] = _MediaTask.TRANSCRIPTION

    @abstractmethod
    def transcribe(
        self,
        audio: AudioBlock | VideoBlock,
        *,
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[TextBlock]:
        """Transcribe spoken content from an audio or video block."""


class BaseImageGenerationModelSetup(BaseMediaModelSetup, ABC):
    """Base setup class for image generation and image editing capabilities."""

    _task: ClassVar[_MediaTask] = _MediaTask.IMAGE_GENERATION

    @abstractmethod
    def generate(
        self,
        prompt: str,
        *,
        images: Sequence[ImageBlock] | None = None,
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[ImageBlock]:
        """Generate or edit images from a prompt and optional reference images."""


class BaseVideoGenerationModelSetup(BaseMediaModelSetup, ABC):
    """Base setup class for video generation capabilities."""

    _task: ClassVar[_MediaTask] = _MediaTask.VIDEO_GENERATION

    @abstractmethod
    def generate(
        self,
        prompt: str = "",
        *,
        media: Sequence[ImageBlock | AudioBlock | VideoBlock | DocumentBlock]
        | None = None,
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> MediaResult[VideoBlock]:
        """Generate video from a text prompt, ordered media inputs, or both."""
