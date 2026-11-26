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


@pytest.mark.parametrize(
    ("section", "resource_type", "clazz", "extra_lines", "default_module"),
    [
        (
            "chat_model_connections",
            ResourceType.CHAT_MODEL_CONNECTION,
            "ollama",
            "",
            "flink_agents.integrations.chat_models.ollama_chat_model",
        ),
        (
            "embedding_model_connections",
            ResourceType.EMBEDDING_MODEL_CONNECTION,
            "openai",
            "        api_key: fake-key\n",
            "flink_agents.integrations.embedding_models.openai_embedding_model",
        ),
    ],
    ids=["ollama-chat", "openai-embedding"],
)
@pytest.mark.parametrize(
    ("timeout_line", "expected"),
    [
        ("", "default"),
        ("        request_timeout: 5\n", 5.0),
        ("        request_timeout: null\n", None),
    ],
    ids=["omitted", "number", "null"],
)
def test_yaml_request_timeout_contract(
    tmp_path: Path,
    section: str,
    resource_type: ResourceType,
    clazz: str,
    extra_lines: str,
    default_module: str,
    timeout_line: str,
    expected: object,
) -> None:
    """A YAML connection's request_timeout, as the runtime provider builds it.

    Omitted uses the default, a number is used as given, and ``null`` is a
    validation error rather than another way to select the default.
    """
    import importlib

    from pydantic import ValidationError

    from flink_agents.api.yaml.loader import build_agents

    path = tmp_path / "agent.yaml"
    path.write_text(
        "agents:\n"
        "  - name: test_agent\n"
        f"    {section}:\n"
        "      - name: conn\n"
        f"        clazz: {clazz}\n"
        f"{extra_lines}"
        f"{timeout_line}"
    )
    agents, _, _ = build_agents(path)
    descriptor = agents["test_agent"].resources[resource_type]["conn"]
    provider = PythonResourceProvider.get("conn", descriptor, resource_type)

    if expected is None:
        with pytest.raises(ValidationError, match="request_timeout"):
            provider.provide(Mock(spec=ResourceContext), AgentConfiguration())
        return
    connection = provider.provide(Mock(spec=ResourceContext), AgentConfiguration())
    if expected == "default":
        expected = importlib.import_module(default_module).DEFAULT_REQUEST_TIMEOUT
    assert connection.request_timeout == expected


_UNSET = object()


@pytest.mark.parametrize(
    ("config_value", "descriptor_value", "expected"),
    [
        (_UNSET, _UNSET, "default"),
        (7, _UNSET, 7.0),
        (7, 5, 5.0),
        (None, 5, 5.0),
        (None, _UNSET, None),
        (7, None, None),
    ],
    ids=[
        "neither",
        "config-only",
        "descriptor-overrides-config",
        "descriptor-overrides-null-config",
        "null-config",
        "null-descriptor-overrides-config",
    ],
)
def test_request_timeout_from_config_and_descriptor(
    config_value: object, descriptor_value: object, expected: object
) -> None:
    """Class-level config and descriptor arguments compose as the runtime does.

    The descriptor argument wins over class-level config, and ``None`` from
    either source is a validation error once it is the effective value.
    """
    from pydantic import ValidationError

    from flink_agents.api.resource import ResourceName
    from flink_agents.integrations.chat_models import ollama_chat_model

    conf = {}
    if config_value is not _UNSET:
        conf["OllamaChatModelConnection.request_timeout"] = config_value
    arguments = {}
    if descriptor_value is not _UNSET:
        arguments["request_timeout"] = descriptor_value
    provider = PythonResourceProvider.get(
        "conn",
        ResourceDescriptor(clazz=ResourceName.ChatModel.OLLAMA_CONNECTION, **arguments),
        ResourceType.CHAT_MODEL_CONNECTION,
    )

    if expected is None:
        with pytest.raises(ValidationError, match="request_timeout"):
            provider.provide(Mock(spec=ResourceContext), AgentConfiguration(conf))
        return
    connection = provider.provide(Mock(spec=ResourceContext), AgentConfiguration(conf))
    if expected == "default":
        expected = ollama_chat_model.DEFAULT_REQUEST_TIMEOUT
    assert connection.request_timeout == expected
