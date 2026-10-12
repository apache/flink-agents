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
from unittest.mock import Mock

import pytest
from pydantic import ValidationError

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import chat_model_setup
from flink_agents.api.resource import (
    JavaResourceDescriptor,
    ResourceDescriptor,
    ResourceType,
)
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.resource.java.java_chat_model import JavaChatModelSetup
from flink_agents.plan.resource_provider import (
    JavaResourceProvider,
    PythonResourceProvider,
)


def test_java_descriptor_round_trip_without_importing_target():
    descriptor = JavaResourceDescriptor(clazz="custom.vendor.Chat", model="demo")
    wire = descriptor.model_dump_json()
    assert json.loads(wire) == {
        "language": "java",
        "target_module": "",
        "target_clazz": "custom.vendor.Chat",
        "arguments": {"model": "demo"},
    }
    restored = ResourceDescriptor.model_validate_json(wire)
    assert isinstance(restored, JavaResourceDescriptor)
    assert restored == descriptor
    with pytest.raises(TypeError, match="Java provider"):
        _ = restored.clazz


def test_python_descriptor_uses_real_module_and_class():
    descriptor = ResourceDescriptor(clazz="custom.vendor.Chat", model="demo")
    assert descriptor.language == "python"
    assert descriptor.target_module == "custom.vendor"
    assert descriptor.target_clazz == "Chat"
    assert (
        ResourceDescriptor.model_validate_json(descriptor.model_dump_json())
        == descriptor
    )


@pytest.mark.parametrize("clazz", ["", "Chat", "module.", ".Chat"])
def test_python_descriptor_rejects_incomplete_target(clazz):
    with pytest.raises(ValueError):
        ResourceDescriptor(clazz=clazz)


def test_cross_language_descriptor_cannot_change_language():
    with pytest.raises(ValueError):
        JavaResourceDescriptor(clazz="custom.Chat", language="python")


def test_native_constructor_cannot_silently_create_a_java_descriptor():
    with pytest.raises(ValueError, match="descriptor class"):
        ResourceDescriptor(clazz="custom.Chat", language="java")


@pytest.mark.parametrize(
    ("provider", "descriptor"),
    [
        (JavaResourceProvider, ResourceDescriptor(clazz="custom.Chat")),
        (PythonResourceProvider, JavaResourceDescriptor(clazz="custom.Chat")),
    ],
)
def test_provider_rejects_conflicting_wire_language(provider, descriptor):
    with pytest.raises(ValidationError, match="requires a"):
        provider.model_validate(
            {
                "name": "chat",
                "type": "chat_model",
                "descriptor": descriptor.model_dump(),
            }
        )


class CrossLanguageAgent(Agent):
    calls = 0

    @chat_model_setup
    @staticmethod
    def chat() -> JavaResourceDescriptor:
        CrossLanguageAgent.calls += 1
        return JavaResourceDescriptor(clazz="custom.vendor.Chat", model="demo")


def test_decorator_routes_without_importing_java_and_runs_once():
    CrossLanguageAgent.calls = 0
    plan = AgentPlan.from_agent(CrossLanguageAgent(), AgentConfiguration())
    provider = plan.resource_providers[ResourceType.CHAT_MODEL]["chat"]
    assert isinstance(provider, JavaResourceProvider)
    assert provider.descriptor.target_clazz == "custom.vendor.Chat"
    assert CrossLanguageAgent.calls == 1


def test_provider_creates_plan_wrapper_and_passes_metric_binding():
    descriptor = JavaResourceDescriptor(clazz="custom.vendor.Chat", model="demo")
    provider = JavaResourceProvider.get("chat", descriptor, ResourceType.CHAT_MODEL)
    adapter = Mock()
    provider.set_java_resource_adapter(adapter)
    resource = provider.provide(None, None)
    assert isinstance(resource, JavaChatModelSetup)
    adapter.getResource.assert_called_once_with("chat", "chat_model")
    metrics = Mock()
    resource.set_metric_group(metrics)
    adapter.set_metric_group.assert_called_once_with(
        adapter.getResource.return_value, metrics
    )
    assert descriptor.arguments == {"model": "demo"}
