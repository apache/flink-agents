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
"""YAML model routers use the same plan checks and routing execution."""

import asyncio
from pathlib import Path
from unittest.mock import MagicMock
from uuid import uuid4

import pytest

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.chat_models.routing import (
    CustomRoutingExecutor,
    ModelRouter,
    RoutingDecision,
)
from flink_agents.api.events.model_routing_event import ModelRoutingEvent
from flink_agents.api.resource import ResourceType
from flink_agents.api.yaml.loader import build_agents
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.routing.resolved_route import ResolvedModelRoute
from flink_agents.plan.routing.resolver import resolve


class SelectBig(CustomRoutingExecutor):
    """Select big so a YAML custom strategy can run with no constructor arguments."""

    def route(self, strategy, context):
        return RoutingDecision.of("big")


def _merge(agent: Agent, shared: dict) -> Agent:
    for resource_type, items in shared.items():
        agent_level = agent.resources[resource_type]
        merged = {
            name: resource
            for name, resource in items.items()
            if name not in agent_level
        }
        merged.update(agent_level)
        agent.resources[resource_type] = merged
    return agent


def _load(tmp_path: Path, text: str) -> tuple[Agent, dict]:
    path = tmp_path / "routers.yaml"
    path.write_text(text)
    agents, shared, _ = build_agents(path)
    return _merge(agents["routed"], shared), shared


def _router(plan: AgentPlan, name: str) -> ModelRouter:
    provider = plan.resource_providers[ResourceType.MODEL_ROUTER][name]
    return provider.provide(None, AgentConfiguration())


def _resolve(
    router: ModelRouter, name: str, text: str
) -> tuple[ResolvedModelRoute, ModelRoutingEvent]:
    ctx = MagicMock()
    ctx.has_resource.return_value = True
    ctx.get_resource.return_value = router
    ctx.durable_execute.side_effect = lambda fn, *args, **kwargs: fn(*args)
    ctx.action_metric_group = None
    route = asyncio.run(resolve(uuid4(), name, [ChatMessage.user(text)], {}, ctx))
    event = ctx.send_event.call_args.args[0]
    assert isinstance(event, ModelRoutingEvent)
    return route, event


def test_yaml_routers_execute_rules_and_custom_strategies(tmp_path: Path) -> None:
    executor = f"{SelectBig.__module__}.{SelectBig.__qualname__}"
    agent, _ = _load(
        tmp_path,
        f"""
model_routers:
  - name: shared_router
    clazz: router
    candidates: [small, big]
    strategy_type: rule_based
    strategy_args:
      rules:
        big: sql
    default_model: small
agents:
  - name: routed
    model_routers:
      - name: local_router
        clazz: flink_agents.api.chat_models.routing.ModelRouter
        candidates: [small, big]
        candidate_descriptions:
          small: cheap model
          big: code model
        strategy_type: rule_based
        strategy_args:
          rules:
            big: code
        default_model: small
        fallback: true
      - name: custom_router
        clazz: router
        candidates: [small, big]
        strategy_type: custom
        strategy_executor_class: {executor}
""",
    )
    plan = AgentPlan.from_agent(agent, AgentConfiguration())
    local = _router(plan, "local_router")
    assert local.candidate_descriptions == {
        "small": "cheap model",
        "big": "code model",
    }
    assert local.default_model == "small"
    assert local.fallback is True

    matched, event = _resolve(local, "local_router", "please write code")
    assert matched.selected_model == event.selected_model == "big"
    assert matched.decision_source == event.decision_source == "strategy"
    assert matched.fallback_enabled is True
    assert matched.attempt_order() == ["big", "small"]
    assert matched.reason == "matched rule: code"

    abstained, event = _resolve(
        _router(plan, "shared_router"), "shared_router", "hello"
    )
    assert abstained.selected_model == event.selected_model == "small"
    assert abstained.decision_source == "default"

    custom, event = _resolve(_router(plan, "custom_router"), "custom_router", "hello")
    assert custom.selected_model == event.selected_model == "big"
    assert custom.reason == event.get_attr("reason")


@pytest.mark.parametrize(
    "router",
    [
        """
      - name: router
        clazz: router
        candidates: [small, big]
        strategy_type: llm_judge
        strategy_args:
          judge_model: judge
""",
        """
      - name: router
        clazz: router
        candidates: [small, big]
        strategy_type: decision_model
        strategy_args:
          decision_model: decider
""",
        """
      - name: router
        clazz: router
        candidates: [small, big]
        strategy_type: rule_based
        strategy_args:
          rules:
            missing: x
""",
        """
      - name: router
        clazz: router
        candidates: [small, big]
        strategy_type: custom
        strategy_executor_class: missing.module.Executor
""",
        """
      - name: router
        clazz: router
        candidates: [small, big]
""",
    ],
)
def test_invalid_yaml_fails_like_the_same_programmatic_descriptor(
    tmp_path: Path, router: str
) -> None:
    text = f"agents:\n  - name: routed\n    model_routers:\n{router}"
    agent, _ = _load(tmp_path, text)
    descriptor = agent.resources[ResourceType.MODEL_ROUTER]["router"]
    programmatic = Agent().add_resource("router", ResourceType.MODEL_ROUTER, descriptor)

    with pytest.raises(Exception) as yaml_error:
        AgentPlan.from_agent(agent, AgentConfiguration())
    with pytest.raises(type(yaml_error.value), match=".+") as programmatic_error:
        AgentPlan.from_agent(programmatic, AgentConfiguration())
    assert str(programmatic_error.value) == str(yaml_error.value)
