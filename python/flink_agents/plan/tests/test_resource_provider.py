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

import pytest

from flink_agents.api.resource import Resource, ResourceDescriptor, ResourceType
from flink_agents.plan.resource_provider import (
    JavaResourceProvider,
    PythonResourceProvider,
    ResourceProvider,
)

current_dir = Path(__file__).parent


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
                "target_module": "",
                "target_clazz": "org.apache.flink.agents.integrations.chatmodels.bedrock.BedrockChatModelSetup",
                "arguments": {
                    "java_clazz": "org.apache.flink.agents.integrations.chatmodels.bedrock.BedrockChatModelSetup",
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
