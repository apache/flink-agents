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
################################################################################
"""Compile YAML parent/child declarations through the production plan path."""

import json
from pathlib import Path

import pytest
import yaml

from flink_agents.api.resource import ResourceType
from flink_agents.api.yaml.loader import build_agents, load_yaml
from flink_agents.api.yaml.tests.test_loader import _registration_env
from flink_agents.plan.agent_plan import AgentPlan
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.plan.subagent import decode_plan


def _write(tmp_path: Path, agents: list[dict], **shared: object) -> Path:
    path = tmp_path / "agents.yaml"
    path.write_text(yaml.safe_dump({"agents": agents, **shared}))
    return path


@pytest.mark.parametrize("separate_files", [False, True])
def test_yaml_child_plan_preserves_actions_metadata_and_local_resources(
    tmp_path: Path,
    separate_files: bool,
) -> None:
    path = _write(
        tmp_path,
        [
            {
                "name": "parent",
                "prompts": [{"name": "parent_only", "text": "parent"}],
                "subagents": [
                    {
                        "name": "reviewer",
                        "agent": "child",
                        "description": "Review code",
                        "input_schema": {
                            "type": "object",
                            "properties": {"code": {"type": "string"}},
                        },
                    }
                ],
            },
            {
                "name": "child",
                "actions": ["increment"],
                "prompts": [{"name": "child_only", "text": "child"}],
            },
        ],
        actions=[
            {
                "name": "increment",
                "function": "flink_agents.api.yaml.tests.fixtures.loader_targets:increment",
                "trigger_conditions": ["input"],
            }
        ],
    )
    env = _registration_env()
    if separate_files:
        document = yaml.safe_load(path.read_text())
        parent = document["agents"].pop(0)
        child_path = tmp_path / "child.yaml"
        child_path.write_text(yaml.safe_dump(document))
        path.write_text(yaml.safe_dump({"agents": [parent]}))
        load_yaml(env, [child_path, path])
    else:
        load_yaml(env, path)
    original = env._agents["parent"].resources[ResourceType.AGENT]["reviewer"]
    plan = AgentPlan.from_agent(env._agents["parent"], AgentConfiguration())
    restored = AgentPlan.model_validate_json(
        plan.model_dump_json(serialize_as_any=True)
    )
    descriptor = restored.resource_providers[ResourceType.AGENT]["reviewer"].descriptor
    assert descriptor.arguments["scope"] == "reviewer"
    assert descriptor.arguments["description"] == "Review code"
    assert "code" in json.loads(descriptor.arguments["input_schema"])["properties"]
    child = decode_plan(descriptor)
    assert "increment" in child.actions
    assert "child_only" in child.resource_providers[ResourceType.PROMPT]
    assert "parent_only" not in child.resource_providers[ResourceType.PROMPT]
    assert original.arguments["agent"] is env._agents["child"]
    assert "plan" not in original.arguments


@pytest.mark.parametrize(
    "edges",
    [
        {"parent": "parent"},
        {"parent": "child", "child": "parent"},
        {"parent": "a", "a": "b", "b": "a"},
    ],
)
def test_yaml_cycles_use_existing_plan_validation(tmp_path: Path, edges: dict) -> None:
    path = _write(
        tmp_path,
        [
            {"name": name, "subagents": [{"name": child, "agent": child}]}
            for name, child in edges.items()
        ],
    )
    agents, _, _ = build_agents(path)
    with pytest.raises(ValueError, match="Cyclic sub-agent definition"):
        AgentPlan.from_agent(agents["parent"], AgentConfiguration())


def test_yaml_nested_children_and_reused_child_compile(tmp_path: Path) -> None:
    path = _write(
        tmp_path,
        [
            {
                "name": "parent",
                "subagents": [
                    {"name": "first", "agent": "child"},
                    {"name": "second", "agent": "child"},
                ],
            },
            {"name": "child", "subagents": [{"name": "leaf", "agent": "leaf"}]},
            {"name": "leaf"},
        ],
    )
    agents, _, _ = build_agents(path)
    plan = AgentPlan.from_agent(agents["parent"], AgentConfiguration())
    resources = plan.resource_providers[ResourceType.AGENT]
    assert (
        resources["first"].descriptor.arguments["plan"]
        == resources["second"].descriptor.arguments["plan"]
    )
    child = decode_plan(resources["first"].descriptor)
    assert "leaf" in child.resource_providers[ResourceType.AGENT]
