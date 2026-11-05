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
from typing import Any

import pytest

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_models.routing import ModelRouter, Strategies
from flink_agents.api.resource import (
    JavaResourceDescriptor,
    ResourceDescriptor,
    ResourceType,
)
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.resource_provider import PythonResourceProvider
from flink_agents.plan.routing.validation import validate_routing
from flink_agents.plan.tests.actions.test_model_routing import NoArgs, Select


def descriptor(**changes: Any):
    args = {
        "candidates": ["small", "big"],
        "strategy_type": "rule_based",
        "strategy_args": {"rules": {"big": "code"}},
    }
    args.update(changes)
    return ResourceDescriptor(
        clazz="flink_agents.api.chat_models.routing.ModelRouter", **args
    )


def compile_router(router):
    agent = Agent().add_resource("router", ResourceType.MODEL_ROUTER, router)
    return AgentPlan.from_agent(agent, AgentConfiguration())


@pytest.mark.parametrize("executor", [Select, NoArgs])
def test_custom_plan_round_trip(executor):
    router = ModelRouter.of("small").strategy(Strategies.custom(executor)).build()
    plan = compile_router(router)
    restored = AgentPlan.model_validate_json(plan.model_dump_json())
    validate_routing(restored.resource_providers)
    provider = restored.resource_providers[ResourceType.MODEL_ROUTER]["router"]
    assert isinstance(provider, PythonResourceProvider)
    assert provider.descriptor.arguments == router.arguments


@pytest.mark.parametrize(
    "changes",
    [
        {"candidates": []},
        {"candidates": ["small", "small"]},
        {"default_model": "absent"},
        {"strategy_type": "unknown"},
        {
            "strategy_type": "decision_model",
            "strategy_args": {"decision_model": "decider"},
        },
        {"strategy_args": {"rules": {"absent": "x"}}},
        {"strategy_args": {"rules": {"small": "["}}},
        {"strategy_args": {"rules": {"small": r"\p{Alpha}"}}},
        {"strategy_type": "custom", "strategy_executor_class": "builtins.str"},
        {
            "strategy_type": "custom",
            "strategy_executor_class": "missing.module.Executor",
        },
    ],
)
def test_invalid_raw_descriptor_fails_during_plan_compilation(changes):
    with pytest.raises((ValueError, ImportError, AttributeError)):
        compile_router(descriptor(**changes))


def test_java_router_rejected_only_when_compiling_python_agent():
    with pytest.raises(TypeError, match="Python descriptor"):
        compile_router(
            JavaResourceDescriptor(
                clazz="org.apache.flink.agents.api.chat.model.routing.ModelRouter",
                candidates=["small"],
                strategy_type="rule_based",
            )
        )


def test_plan_detects_collision_across_registration_sources():
    agent = Agent()
    agent.resources[ResourceType.MODEL_ROUTER]["same"] = descriptor()
    agent.resources[ResourceType.CHAT_MODEL]["same"] = ResourceDescriptor(
        clazz="some.module.Setup"
    )
    with pytest.raises(ValueError, match="both a chat model and a model router"):
        AgentPlan.from_agent(agent, AgentConfiguration())


def test_python_rules_router_matches_snapshot():
    snapshot = (
        Path(__file__).resolve().parents[4]
        / "e2e-test/cross-language-agent-plan-snapshots/python/agent_plan_with_rules_router.json"
    )
    plan = compile_router(descriptor())
    assert json.loads(plan.model_dump_json()) == json.loads(snapshot.read_text())
