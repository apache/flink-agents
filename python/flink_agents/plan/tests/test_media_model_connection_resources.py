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
from types import SimpleNamespace
from unittest.mock import Mock

import httpx
import pytest

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import AudioBlock
from flink_agents.api.decorators import media_model_connection, media_model_setup
from flink_agents.api.media import (
    AsyncJobModel,
    BaseImageGenerationModelSetup,
    BaseSpeechSynthesisModelSetup,
    BaseVideoGenerationModelSetup,
    MediaResult,
)
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType
from flink_agents.integrations.media_models.dashscope_media_model import (
    DashScopeMediaModelConnection,
    DashScopeSpeechSynthesisModelSetup,
    DashScopeVideoGenerationModelSetup,
)
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.resource_provider import PythonResourceProvider
from flink_agents.runtime.remote_execution_environment import (
    RemoteAgentBuilder,
    RemoteExecutionEnvironment,
)
from flink_agents.runtime.resource_cache import ResourceCache


class _MediaAgent(Agent):
    def __init__(self, descriptor: ResourceDescriptor) -> None:
        super().__init__()
        self._descriptor = descriptor

    @media_model_connection
    def media(self) -> ResourceDescriptor:
        return self._descriptor


@pytest.mark.parametrize("registration", ["decorator", "agent", "environment"])
def test_media_model_connection_plan_and_shared_client_lifecycle(
    registration: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A worker connection owns one client shared by its bound media capabilities."""
    client = Mock()
    client.videos.jobs.create.return_value = SimpleNamespace(task_id="job-1")
    client.audio.tts.http.synthesize.return_value = SimpleNamespace(
        audio_url="https://example.test/speech.wav",
        expires_at=None,
        usage=None,
        request_id="speech-1",
    )
    client_factory = Mock(return_value=client)
    monkeypatch.setattr(
        "flink_agents.integrations.media_models.dashscope_media_model.create_client",
        client_factory,
    )
    monkeypatch.setenv("DASHSCOPE_API_KEY", "submitter-key")
    descriptor = ResourceDescriptor(
        clazz=ResourceName.MediaModel.DASHSCOPE_CONNECTION,
        workspace="workspace-1",
        request_timeout=12,
    )
    if registration == "decorator":
        agent = _MediaAgent(descriptor)
    else:
        agent = Agent()
        if registration == "agent":
            owner = agent
        else:
            monkeypatch.setattr(
                "flink_agents.runtime.remote_execution_environment.load_agent_configuration",
                lambda _: AgentConfiguration(),
            )
            owner = RemoteExecutionEnvironment(env=Mock())
        owner.add_resource("media", ResourceType.MEDIA_MODEL_CONNECTION, descriptor)

    if registration == "environment":
        # Keep environment merging and Python plan compilation real; replace the
        # JVM validator rather than starting a Flink cluster for this transfer test.
        validate_plan = Mock()
        monkeypatch.setattr(
            RemoteAgentBuilder,
            "_RemoteAgentBuilder__validate_agent_plan_json",
            staticmethod(validate_plan),
        )
        owner.from_datastream(Mock(), key_selector=lambda value: value).apply(agent)
        wire = validate_plan.call_args.args[0]
    else:
        wire = AgentPlan.from_agent(agent, AgentConfiguration()).model_dump_json(
            serialize_as_any=True
        )

    client_factory.assert_not_called()
    assert "submitter-key" not in wire
    restored = AgentPlan.model_validate_json(wire)
    connections = restored.resource_providers[ResourceType.MEDIA_MODEL_CONNECTION]
    assert set(connections) == {"media"}
    assert isinstance(connections["media"], PythonResourceProvider)
    assert connections["media"].descriptor.clazz is DashScopeMediaModelConnection
    assert connections["media"].descriptor.arguments == {
        "workspace": "workspace-1",
        "request_timeout": 12,
    }

    monkeypatch.setenv("DASHSCOPE_API_KEY", "worker-key")
    cache = ResourceCache(restored.resource_providers, config=AgentConfiguration())
    try:
        connection = cache.get_resource("media", ResourceType.MEDIA_MODEL_CONNECTION)
        assert isinstance(connection, DashScopeMediaModelConnection)
        assert connection.resource_context is cache.get_resource_context()
        assert connection.workspace == "workspace-1"
        assert connection.request_timeout == 12
        assert (
            cache.get_resource("media", ResourceType.MEDIA_MODEL_CONNECTION)
            is connection
        )
        speech = DashScopeSpeechSynthesisModelSetup(
            connection="media",
            model="speech-model",
            voice="speaker",
            additional_kwargs={"volume": 50},
            resource_context=cache.get_resource_context(),
        )
        video = DashScopeVideoGenerationModelSetup(
            connection="media",
            model="video-model",
            duration=3,
            resource_context=cache.get_resource_context(),
        )
        other_video = DashScopeVideoGenerationModelSetup(
            connection="media",
            model="other-video-model",
            duration=6,
            resource_context=cache.get_resource_context(),
        )
        for model in (speech, video, other_video):
            model.open()
        client_factory.assert_called_once_with(
            api_key="worker-key",
            base_url=None,
            workspace="workspace-1",
            region="cn-beijing",
            timeout=12,
        )

        monkeypatch.setenv("DASHSCOPE_API_KEY", "changed-after-open")
        result = speech.synthesize("Hello", model_parameters={"format": "wav"})
        assert isinstance(result, MediaResult[AudioBlock])
        assert result.content[0].source.url == "https://example.test/speech.wav"
        video.submit("A cat", model_parameters={"duration": 4})
        other_video.submit("A dog")
        video.submit("A bird")
        client_factory.assert_called_once_with(
            api_key="worker-key",
            base_url=None,
            workspace="workspace-1",
            region="cn-beijing",
            timeout=12,
        )
        calls = client.videos.jobs.create.call_args_list
        assert [call.kwargs["model"] for call in calls] == [
            "video-model",
            "other-video-model",
            "video-model",
        ]
        assert [call.kwargs["parameters"] for call in calls] == [
            {"duration": 4},
            {"duration": 6},
            {"duration": 3},
        ]
        for model in (speech, video, other_video):
            model.close()
        client.close.assert_not_called()
    finally:
        cache.close()

    client.close.assert_called_once_with()


class _MediaWithSetupsAgent(Agent):
    @media_model_connection
    @staticmethod
    def media() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.MediaModel.DASHSCOPE_CONNECTION,
            workspace="workspace-1",
            request_timeout=12,
        )

    @media_model_setup
    @staticmethod
    def narrator() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.MediaModel.DASHSCOPE_SPEECH_SYNTHESIS_SETUP,
            connection="media",
            model="cosyvoice-v3-plus",
            voice="longanyang",
            additional_kwargs={"rate": 1.1},
        )


def test_media_model_setups_plan_resolution_and_shared_connection_lifecycle(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Declarative MEDIA_MODEL setups bind to one shared connection and preserve async semantics."""
    client = Mock()
    client.audio.tts.http.synthesize.return_value = SimpleNamespace(
        audio_url="https://example.test/speech.wav",
        expires_at=None,
        usage=None,
        request_id="speech-1",
    )
    client.transport.request.return_value = httpx.Response(
        200, json={"output": {"task_id": "img-job-1", "task_status": "PENDING"}}
    )
    client.videos.jobs.create.return_value = SimpleNamespace(task_id="vid-job-1")
    client_factory = Mock(return_value=client)
    monkeypatch.setattr(
        "flink_agents.integrations.media_models.dashscope_media_model.create_client",
        client_factory,
    )
    monkeypatch.setenv("DASHSCOPE_API_KEY", "worker-key")

    agent = _MediaWithSetupsAgent()
    agent.add_resource(
        "poster_image",
        ResourceType.MEDIA_MODEL,
        ResourceDescriptor(
            clazz=ResourceName.MediaModel.DASHSCOPE_IMAGE_GENERATION_SETUP,
            connection="media",
            model="qwen-image-3.0",
            size="512*512",
        ),
    )

    monkeypatch.setattr(
        "flink_agents.runtime.remote_execution_environment.load_agent_configuration",
        lambda _: AgentConfiguration(),
    )
    env = RemoteExecutionEnvironment(env=Mock())
    env.add_resource(
        "promo_video",
        ResourceType.MEDIA_MODEL,
        ResourceDescriptor(
            clazz=ResourceName.MediaModel.DASHSCOPE_VIDEO_GENERATION_SETUP,
            connection="media",
            model="wan2.7-i2v",
            duration=3,
        ),
    )
    validate_plan = Mock()
    monkeypatch.setattr(
        RemoteAgentBuilder,
        "_RemoteAgentBuilder__validate_agent_plan_json",
        staticmethod(validate_plan),
    )
    env.from_datastream(Mock(), key_selector=lambda value: value).apply(agent)
    restored = AgentPlan.model_validate_json(validate_plan.call_args.args[0])
    assert set(restored.resource_providers[ResourceType.MEDIA_MODEL]) == {
        "narrator",
        "poster_image",
        "promo_video",
    }

    cache = ResourceCache(restored.resource_providers, config=AgentConfiguration())
    try:
        narrator = cache.get_resource("narrator", ResourceType.MEDIA_MODEL)
        poster = cache.get_resource("poster_image", ResourceType.MEDIA_MODEL)
        promo = cache.get_resource("promo_video", ResourceType.MEDIA_MODEL)
        assert isinstance(narrator, BaseSpeechSynthesisModelSetup)
        assert not isinstance(narrator, AsyncJobModel)
        assert isinstance(poster, BaseImageGenerationModelSetup)
        assert isinstance(poster, AsyncJobModel)
        assert isinstance(promo, BaseVideoGenerationModelSetup)
        assert isinstance(promo, AsyncJobModel)
        assert cache.get_resource("poster_image", ResourceType.MEDIA_MODEL) is poster

        client_factory.assert_called_once()
        speech_result = narrator.synthesize("Hello")
        assert speech_result.content[0].source.url == "https://example.test/speech.wav"
        poster.submit("A notebook")
        promo.submit("A video")
        assert client.transport.request.call_args.kwargs["json"]["parameters"] == {
            "size": "512*512"
        }
        assert client.videos.jobs.create.call_args.kwargs["parameters"] == {
            "duration": 3
        }
        client.close.assert_not_called()
    finally:
        cache.close()

    client.close.assert_called_once_with()
