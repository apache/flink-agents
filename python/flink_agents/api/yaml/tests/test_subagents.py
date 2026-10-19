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
"""YAML declarations of internal and external sub-agents."""

import json
from pathlib import Path

import pytest
import yaml
from pydantic import ValidationError

from flink_agents.api.resource import ResourceType
from flink_agents.api.subagent import INTERNAL_SUBAGENT_SETUP_CLASS
from flink_agents.api.yaml.loader import build_agents, load_yaml
from flink_agents.api.yaml.specs import YamlAgentsDocument
from flink_agents.api.yaml.tests.test_loader import _registration_env


def _write(tmp_path: Path, document: dict, filename: str = "agents.yaml") -> Path:
    path = tmp_path / filename
    path.write_text(yaml.safe_dump(document))
    return path


@pytest.mark.parametrize("child_first", [False, True])
def test_internal_reference_preserves_child_and_metadata(
    tmp_path: Path, child_first: bool
) -> None:
    schema = {"type": "object", "properties": {"code": {"type": "string"}}}
    parent = {
        "name": "parent",
        "subagents": [
            {
                "name": "reviewer",
                "agent": "child",
                "description": "Review code",
                "input_schema": schema,
            }
        ],
    }
    child = {
        "name": "child",
        "prompts": [{"name": "child_prompt", "text": "Review {code}"}],
    }
    agents, _, _ = build_agents(
        _write(
            tmp_path, {"agents": [child, parent] if child_first else [parent, child]}
        )
    )
    descriptor = agents["parent"].resources[ResourceType.AGENT]["reviewer"]
    assert descriptor.target_clazz == INTERNAL_SUBAGENT_SETUP_CLASS
    assert descriptor.arguments["agent"] is agents["child"]
    assert descriptor.arguments["description"] == "Review code"
    assert json.loads(descriptor.arguments["input_schema"]) == schema
    assert "child_prompt" in agents["child"].resources[ResourceType.PROMPT]


def test_external_subagents_preserve_provider_arguments(tmp_path: Path) -> None:
    external = {"name": "remote", "clazz": "my_app.RemoteSetup", "agent": "remote-id"}
    agents, resources, _ = build_agents(
        _write(
            tmp_path,
            {
                "agents": [{"name": "parent", "subagents": [external]}],
                "subagents": [external],
            },
        )
    )
    assert resources[ResourceType.AGENT]["remote"].arguments == {"agent": "remote-id"}
    assert agents["parent"].resources[ResourceType.AGENT]["remote"].arguments == {
        "agent": "remote-id"
    }


@pytest.mark.parametrize("metadata", [{}, {"description": None}, {"description": ""}])
def test_optional_internal_metadata(tmp_path: Path, metadata: dict) -> None:
    agents, _, _ = build_agents(
        _write(
            tmp_path,
            {
                "agents": [
                    {
                        "name": "parent",
                        "subagents": [{"name": "child", "agent": "child", **metadata}],
                    },
                    {"name": "child"},
                ]
            },
        )
    )
    arguments = agents["parent"].resources[ResourceType.AGENT]["child"].arguments
    assert arguments["description"] == ""
    assert "input_schema" not in arguments


def test_shared_internal_reference_rejected_before_registration(tmp_path: Path) -> None:
    env = _registration_env()
    with pytest.raises(ValidationError, match="clazz"):
        load_yaml(
            env,
            _write(
                tmp_path,
                {
                    "agents": [{"name": "child"}],
                    "subagents": [{"name": "reviewer", "agent": "child"}],
                },
            ),
        )
    assert not env._agents
    assert all(not entries for entries in env.resources.values())


def test_two_parents_share_child_definition(tmp_path: Path) -> None:
    agents, _, _ = build_agents(
        _write(
            tmp_path,
            {
                "agents": [
                    {
                        "name": name,
                        "subagents": [{"name": "reviewer", "agent": "child"}],
                    }
                    for name in ["one", "two"]
                ]
                + [{"name": "child"}]
            },
        )
    )
    for name in ["one", "two"]:
        assert (
            agents[name].resources[ResourceType.AGENT]["reviewer"].arguments["agent"]
            is agents["child"]
        )


def test_unknown_child_does_not_register_partial_state(tmp_path: Path) -> None:
    document = {"agents": [{"name": "parent"}]}
    document["agents"][0]["subagents"] = [{"name": "reviewer", "agent": "missing"}]
    env = _registration_env()
    with pytest.raises(
        ValueError, match="reviewer.*unknown agent.*missing.*same YAML file"
    ):
        load_yaml(env, _write(tmp_path, document))
    assert not env._agents
    assert all(not entries for entries in env.resources.values())


@pytest.mark.parametrize(
    "entry",
    [
        {"name": "reviewer", "agent": ""},
        {"name": "reviewer", "agent": "   "},
        {"name": "reviewer", "agent": None},
        {"name": "reviewer", "agent": 123},
        {"name": "reviewer", "agent": True},
        {"name": None, "agent": "child"},
        {"name": 123, "agent": "child"},
        {"name": "reviewer", "agent": "child", "description": 123},
        {"name": "reviewer", "agent": "child", "type": "python"},
        {"name": "reviewer", "agent": "child", "input_schema": "{}"},
        {"name": "reviewer", "agent": "child", "unexpected": True},
        {"name": "reviewer"},
    ],
)
def test_rejects_invalid_subagent_declarations(entry: dict) -> None:
    with pytest.raises(ValidationError):
        YamlAgentsDocument.model_validate(
            {"agents": [{"name": "parent", "subagents": [entry]}]}
        )


@pytest.mark.parametrize("shared", [False, True])
def test_rejects_duplicate_subagent_resource_names(
    tmp_path: Path, shared: bool
) -> None:
    document = {"agents": [{"name": "parent"}, {"name": "child"}]}
    target = document if shared else document["agents"][0]
    entry = (
        {"name": "reviewer", "clazz": "my_app.RemoteSetup"}
        if shared
        else {"name": "reviewer", "agent": "child"}
    )
    target["subagents"] = [entry] * 2
    with pytest.raises(ValueError, match="[Dd]uplicate|already"):
        build_agents(_write(tmp_path, document))


@pytest.mark.parametrize("separate_calls", [False, True])
def test_child_in_previously_loaded_file_is_resolved(
    tmp_path: Path, separate_calls: bool
) -> None:
    child_path = _write(tmp_path, {"agents": [{"name": "child"}]}, "child.yaml")
    parent_path = _write(
        tmp_path,
        {
            "agents": [
                {
                    "name": "parent",
                    "subagents": [{"name": "reviewer", "agent": "child"}],
                }
            ]
        },
        "parent.yaml",
    )
    env = _registration_env()
    if separate_calls:
        load_yaml(env, child_path)
        load_yaml(env, parent_path)
    else:
        load_yaml(env, [child_path, parent_path])
    descriptor = env._agents["parent"].resources[ResourceType.AGENT]["reviewer"]
    assert descriptor.arguments["agent"] is env._agents["child"]
    assert set(env._agents) == {"parent", "child"}
    assert not env._agents["child"].resources[ResourceType.AGENT]
    # Registrations belong to one environment, not to a process-global registry.
    with pytest.raises(ValueError, match="unknown agent.*child"):
        load_yaml(_registration_env(), parent_path)


def test_child_must_be_loaded_before_parent_and_failure_preserves_state(
    tmp_path: Path,
) -> None:
    env = _registration_env()
    load_yaml(
        env,
        _write(
            tmp_path,
            {
                "agents": [{"name": "existing"}],
                "prompts": [{"name": "existing_prompt", "text": "keep"}],
            },
        ),
    )
    original = env._agents["existing"]
    prompt = env.resources[ResourceType.PROMPT]["existing_prompt"]
    child_path = _write(tmp_path, {"agents": [{"name": "child"}]}, "child.yaml")
    parent_path = _write(
        tmp_path,
        {
            "agents": [
                {
                    "name": "parent",
                    "subagents": [{"name": "reviewer", "agent": "child"}],
                }
            ],
            "prompts": [{"name": "new_prompt", "text": "not committed"}],
        },
        "parent.yaml",
    )
    with pytest.raises(
        ValueError, match="reviewer.*unknown agent.*child.*already registered"
    ):
        load_yaml(env, [parent_path, child_path])
    assert env._agents == {"existing": original}
    assert env.resources[ResourceType.PROMPT] == {"existing_prompt": prompt}
    load_yaml(env, [child_path, parent_path])
    assert set(env._agents) == {"existing", "child", "parent"}


def test_current_file_cannot_replace_registered_child(tmp_path: Path) -> None:
    env = _registration_env()
    load_yaml(env, _write(tmp_path, {"agents": [{"name": "child"}]}))
    original = env._agents["child"]
    with pytest.raises(ValueError, match="Duplicate agent name.*child"):
        load_yaml(
            env,
            _write(
                tmp_path,
                {
                    "agents": [
                        {
                            "name": "parent",
                            "subagents": [{"name": "reviewer", "agent": "child"}],
                        },
                        {
                            "name": "child",
                            "prompts": [{"name": "replacement", "text": "bad"}],
                        },
                    ],
                    "prompts": [{"name": "new_prompt", "text": "not committed"}],
                },
            ),
        )
    assert env._agents == {"child": original}
    assert not original.resources[ResourceType.PROMPT]
    assert all(not entries for entries in env.resources.values())
