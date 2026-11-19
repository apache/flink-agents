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
from pathlib import Path
from unittest.mock import Mock

import pytest

from flink_agents.api.prompts.prompt import LocalPrompt
from flink_agents.api.resource import Resource, ResourceDescriptor, ResourceType
from flink_agents.api.resource_context import ResourceContext
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.resource_provider import (
    JavaResourceProvider,
    PythonResourceProvider,
    PythonSerializableResourceProvider,
    ResourceProvider,
    is_python_owned,
)

current_dir = Path(__file__).parent


class ContextDependentPrompt(LocalPrompt):
    def open(self) -> None:
        self.resource_context.get_resource("dependency", ResourceType.PROMPT)


@pytest.mark.parametrize("deserialize", [False, True])
def test_serializable_provider_binds_context_before_open(deserialize: bool) -> None:
    resource = ContextDependentPrompt(template="Hello")
    provider = PythonSerializableResourceProvider.from_resource("prompt", resource)
    if deserialize:
        provider = PythonSerializableResourceProvider.model_validate_json(
            provider.model_dump_json()
        )
    context = Mock(spec=ResourceContext)

    provided = provider.provide(context, AgentConfiguration())

    if deserialize:
        assert provided is not resource
    else:
        assert provided is resource
    assert provided.resource_context is context
    provided.open()
    context.get_resource.assert_called_once_with("dependency", ResourceType.PROMPT)


def test_serializable_provider_binds_current_context() -> None:
    resource = LocalPrompt(template="Hello")
    provider = PythonSerializableResourceProvider.from_resource("prompt", resource)
    config = AgentConfiguration()
    previous_context = Mock(spec=ResourceContext)
    current_context = Mock(spec=ResourceContext)
    provider.provide(previous_context, config)
    assert resource.resource_context is previous_context

    provided = provider.provide(current_context, config)

    assert provided is resource
    assert provided.resource_context is current_context


class MockChatModelImpl(Resource):
    host: str
    desc: str

    @classmethod
    def resource_type(cls) -> ResourceType:
        return ResourceType.CHAT_MODEL


@pytest.fixture(scope="module")
def resource_provider() -> ResourceProvider:
    return PythonResourceProvider(
        name="mock",
        type=MockChatModelImpl.resource_type(),
        descriptor=ResourceDescriptor(
            clazz=f"{MockChatModelImpl.__module__}.{MockChatModelImpl.__name__}",
            host="8.8.8.8",
            desc="mock chat model",
        ),
    )


def test_python_resource_provider_serialize(
    resource_provider: ResourceProvider,
) -> None:
    json_value = resource_provider.model_dump_json(serialize_as_any=True)
    with Path.open(Path(f"{current_dir}/resources/resource_provider.json")) as f:
        expected_json = f.read()
    actual = json.loads(json_value)
    expected = json.loads(expected_json)
    assert actual == expected


def test_python_resource_provider_deserialize(
    resource_provider: ResourceProvider,
) -> None:
    with Path.open(Path(f"{current_dir}/resources/resource_provider.json")) as f:
        expected_json = f.read()
    expected_resource_provider = PythonResourceProvider.model_validate_json(
        expected_json
    )
    assert resource_provider == expected_resource_provider


def test_python_can_deserialize_java_resource_provider_wire_shape() -> None:
    json_str = json.dumps(
        {
            "name": "bedrock_chat",
            "type": "chat_model",
            "descriptor": {
                "language": "java",
                "target_module": "",
                "target_clazz": "org.apache.flink.agents.integrations.chatmodels.bedrock.BedrockChatModelSetup",
                "arguments": {
                    "model": "anthropic.claude-3-haiku",
                    "max_tokens": 1024,
                },
            },
            "__resource_provider_type__": "JavaResourceProvider",
        }
    )
    provider = JavaResourceProvider.model_validate_json(json_str)

    assert provider.name == "bedrock_chat"
    assert provider.type == ResourceType.CHAT_MODEL
    assert provider.descriptor.target_module == ""
    assert provider.descriptor.target_clazz == (
        "org.apache.flink.agents.integrations.chatmodels.bedrock.BedrockChatModelSetup"
    )
    assert provider.descriptor.arguments["model"] == "anthropic.claude-3-haiku"
    assert provider.descriptor.arguments["max_tokens"] == 1024


def test_internal_descriptor_is_java_owned(
    resource_provider: PythonResourceProvider,
) -> None:
    """Runtime ownership follows the provider language, without payload heuristics."""
    from flink_agents.api.agents.agent import Agent
    from flink_agents.api.subagent import InternalSubagentResourceDescriptorBuilder
    from flink_agents.plan.agent_plan import AgentPlan
    from flink_agents.plan.configuration import AgentConfiguration

    assert is_python_owned(resource_provider)
    root = Agent()
    root.add_resource(
        "child",
        ResourceType.AGENT,
        InternalSubagentResourceDescriptorBuilder().agent(Agent()).build(),
    )
    provider = AgentPlan.from_agent(root, AgentConfiguration()).resource_providers[
        ResourceType.AGENT
    ]["child"]
    assert not is_python_owned(provider)
