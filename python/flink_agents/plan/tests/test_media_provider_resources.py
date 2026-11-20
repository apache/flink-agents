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
from unittest.mock import Mock

import pytest

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import AudioBlock
from flink_agents.api.decorators import media_provider
from flink_agents.api.media.media_model import (
    MediaResult,
)
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType
from flink_agents.integrations.media_providers.dashscope_media_provider import (
    DashScopeMediaProvider,
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

    @media_provider
    def media(self) -> ResourceDescriptor:
        return self._descriptor


@pytest.mark.parametrize("registration", ["decorator", "agent", "environment"])
def test_media_provider_plan_and_shared_client_lifecycle(
    registration: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A worker provider owns one client shared by its bound media capabilities."""
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
        "flink_agents.integrations.media_providers.dashscope_media_provider.create_client",
        client_factory,
    )
    monkeypatch.setenv("DASHSCOPE_API_KEY", "submitter-key")
    descriptor = ResourceDescriptor(
        clazz=ResourceName.MediaProvider.DASHSCOPE,
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
        owner.add_resource("media", ResourceType.MEDIA_PROVIDER, descriptor)

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
    providers = restored.resource_providers[ResourceType.MEDIA_PROVIDER]
    assert set(providers) == {"media"}
    assert isinstance(providers["media"], PythonResourceProvider)
    assert providers["media"].descriptor.clazz is DashScopeMediaProvider
    assert providers["media"].descriptor.arguments == {
        "workspace": "workspace-1",
        "request_timeout": 12,
    }

    monkeypatch.setenv("DASHSCOPE_API_KEY", "worker-key")
    cache = ResourceCache(restored.resource_providers, config=AgentConfiguration())
    try:
        provider = cache.get_resource("media", ResourceType.MEDIA_PROVIDER)
        assert isinstance(provider, DashScopeMediaProvider)
        assert provider.resource_context is cache.get_resource_context()
        assert provider.workspace == "workspace-1"
        assert provider.request_timeout == 12
        assert cache.get_resource("media", ResourceType.MEDIA_PROVIDER) is provider
        speech = provider.speech(
            "speech-model", voice="speaker", model_parameters={"volume": 50}
        )
        video = provider.video("video-model", model_parameters={"duration": 3})
        other_video = provider.video(
            "other-video-model", model_parameters={"duration": 6}
        )
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
        client.close.assert_not_called()
    finally:
        cache.close()

    client.close.assert_called_once_with()
