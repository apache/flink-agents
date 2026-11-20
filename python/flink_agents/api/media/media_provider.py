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
from abc import ABC
from typing import TYPE_CHECKING, Any, Mapping

from pydantic import ConfigDict
from typing_extensions import override

from flink_agents.api.resource import Resource, ResourceType

if TYPE_CHECKING:
    from flink_agents.api.media.media_model import (
        ImageGenerationModel,
        SpeechSynthesisModel,
        TranscriptionModel,
        VideoGenerationModel,
    )


class BaseMediaProvider(Resource, ABC):
    """A provider resource that binds models for media capabilities.

    Register this resource with a ``ResourceDescriptor`` and retrieve it with
    ``get_resource``. Its factories should return lightweight capability views
    without independent mutable state so the same ``(model, model_parameters)``
    binding can be recreated safely; shared state belongs on the provider
    itself (or in a persisted ``MediaJob``). Java callers accessing a Python
    provider recreate bindings from immutable configuration rather than
    retaining child Python object handles. The provider owns their shared
    connection and lifetime; capability objects are not separately registered
    or closed.
    """

    model_config = ConfigDict(extra="forbid")

    @classmethod
    @override
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.MEDIA_PROVIDER

    def speech(
        self,
        model: str,
        *,
        voice: str | None = None,
        model_parameters: Mapping[str, Any] | None = None,
    ) -> "SpeechSynthesisModel":
        """Bind a speech model. Raises NotImplementedError when unsupported."""
        msg = "This provider does not support speech synthesis."
        raise NotImplementedError(msg)

    def transcription(
        self,
        model: str,
        *,
        model_parameters: Mapping[str, Any] | None = None,
    ) -> "TranscriptionModel":
        """Bind a transcription model. Raises NotImplementedError when unsupported."""
        msg = "This provider does not support transcription."
        raise NotImplementedError(msg)

    def image(
        self,
        model: str,
        *,
        model_parameters: Mapping[str, Any] | None = None,
    ) -> "ImageGenerationModel":
        """Bind an image model. Raises NotImplementedError when unsupported."""
        msg = "This provider does not support image generation."
        raise NotImplementedError(msg)

    def video(
        self,
        model: str,
        *,
        model_parameters: Mapping[str, Any] | None = None,
    ) -> "VideoGenerationModel":
        """Bind a video model. Raises NotImplementedError when unsupported."""
        msg = "This provider does not support video generation."
        raise NotImplementedError(msg)
