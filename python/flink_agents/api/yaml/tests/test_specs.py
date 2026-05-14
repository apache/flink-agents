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
from pathlib import Path

import pytest
from pydantic import ValidationError

from flink_agents.api.yaml.specs import (
    ActionSpec,
    AgentSpec,
    DescriptorSpec,
    MessageRole,
    PromptMessage,
    PromptSpec,
    SkillsSpec,
    ToolSpec,
    YamlAgentsDocument,
    export,
)


def test_descriptor_spec_requires_name_and_clazz() -> None:
    with pytest.raises(ValidationError):
        DescriptorSpec.model_validate({"clazz": "x.Y"})
    with pytest.raises(ValidationError):
        DescriptorSpec.model_validate({"name": "n"})


def test_descriptor_spec_passes_extras_through() -> None:
    spec = DescriptorSpec.model_validate(
        {"name": "n", "clazz": "x.Y", "base_url": "http://x", "timeout": 5}
    )
    assert spec.name == "n"
    assert spec.clazz == "x.Y"
    assert spec.model_extra == {"base_url": "http://x", "timeout": 5}


def test_descriptor_spec_type_defaults_to_none() -> None:
    spec = DescriptorSpec.model_validate({"name": "n", "clazz": "x.Y"})
    assert spec.type is None


def test_descriptor_spec_accepts_python_and_java() -> None:
    py = DescriptorSpec.model_validate({"name": "n", "clazz": "x.Y", "type": "python"})
    java = DescriptorSpec.model_validate({"name": "n", "clazz": "x.Y", "type": "java"})
    assert py.type == "python"
    assert java.type == "java"


def test_descriptor_spec_rejects_unknown_type() -> None:
    with pytest.raises(ValidationError):
        DescriptorSpec.model_validate({"name": "n", "clazz": "x.Y", "type": "go"})


def test_message_role_values() -> None:
    assert MessageRole.SYSTEM.value == "system"
    assert MessageRole.USER.value == "user"
    assert MessageRole.ASSISTANT.value == "assistant"
    assert MessageRole.TOOL.value == "tool"


def test_prompt_message_defaults_to_user() -> None:
    msg = PromptMessage.model_validate({"content": "hi"})
    assert msg.role == MessageRole.USER
    assert msg.content == "hi"


def test_prompt_spec_with_text() -> None:
    spec = PromptSpec.model_validate({"name": "p1", "text": "hello {x}"})
    assert spec.text == "hello {x}"
    assert spec.messages is None


def test_prompt_spec_with_messages() -> None:
    spec = PromptSpec.model_validate(
        {
            "name": "p1",
            "messages": [
                {"role": "system", "content": "be nice"},
                {"role": "user", "content": "{input}"},
            ],
        }
    )
    assert spec.messages is not None
    assert spec.messages[0].role == MessageRole.SYSTEM
    assert spec.text is None


def test_prompt_spec_requires_text_xor_messages() -> None:
    with pytest.raises(ValidationError):
        PromptSpec.model_validate({"name": "p1"})
    with pytest.raises(ValidationError):
        PromptSpec.model_validate(
            {"name": "p1", "text": "x", "messages": [{"content": "y"}]}
        )


def test_prompt_spec_rejects_empty_text_or_messages() -> None:
    # ``text: ""`` and ``messages: []`` used to slip past the "exactly one"
    # check because the prior implementation tested ``is None`` rather than
    # truthiness. Either alone, or together, must now be rejected so an
    # empty prompt cannot be built silently.
    with pytest.raises(ValidationError):
        PromptSpec.model_validate({"name": "p1", "messages": []})
    with pytest.raises(ValidationError):
        PromptSpec.model_validate({"name": "p1", "text": ""})
    with pytest.raises(ValidationError):
        PromptSpec.model_validate({"name": "p1", "text": "", "messages": []})


def test_tool_spec_name_only() -> None:
    spec = ToolSpec.model_validate({"name": "t1"})
    assert spec.name == "t1"
    assert spec.function is None


def test_tool_spec_with_function() -> None:
    spec = ToolSpec.model_validate({"name": "t1", "function": "m.f"})
    assert spec.function == "m.f"


def test_tool_spec_forbids_extras() -> None:
    with pytest.raises(ValidationError):
        ToolSpec.model_validate({"name": "t1", "unknown": 1})


def test_action_spec_requires_listen_to() -> None:
    with pytest.raises(ValidationError):
        ActionSpec.model_validate({"name": "a1"})


def test_action_spec_rejects_empty_listen_to() -> None:
    # An empty ``listen_to: []`` would silently register a dead action that
    # never fires. The minimum-length constraint forces the mistake to
    # surface at YAML validation time.
    with pytest.raises(ValidationError):
        ActionSpec.model_validate({"name": "a1", "listen_to": []})


def test_action_spec_defaults() -> None:
    spec = ActionSpec.model_validate({"name": "a1", "listen_to": ["input"]})
    assert spec.listen_to == ["input"]
    assert spec.function is None
    assert spec.config is None


def test_action_spec_with_config() -> None:
    spec = ActionSpec.model_validate(
        {"name": "a1", "listen_to": ["input"], "config": {"k": 1}}
    )
    assert spec.config == {"k": 1}


def test_action_spec_accepts_type() -> None:
    spec = ActionSpec.model_validate(
        {"name": "a1", "listen_to": ["input"], "type": "java"}
    )
    assert spec.type == "java"


def test_action_spec_type_defaults_to_none() -> None:
    spec = ActionSpec.model_validate({"name": "a1", "listen_to": ["input"]})
    assert spec.type is None


def test_action_spec_rejects_unknown_type() -> None:
    with pytest.raises(ValidationError):
        ActionSpec.model_validate(
            {"name": "a1", "listen_to": ["input"], "type": "rust"}
        )


def test_tool_spec_accepts_type() -> None:
    spec = ToolSpec.model_validate({"name": "t1", "type": "java"})
    assert spec.type == "java"


def test_tool_spec_type_defaults_to_none() -> None:
    spec = ToolSpec.model_validate({"name": "t1"})
    assert spec.type is None


def test_agent_spec_requires_name() -> None:
    with pytest.raises(ValidationError):
        AgentSpec.model_validate({})


def test_agent_spec_minimal() -> None:
    spec = AgentSpec.model_validate({"name": "a"})
    assert spec.name == "a"
    assert spec.description is None
    assert spec.actions == []
    assert spec.prompts == []
    assert spec.tools == []
    assert spec.chat_model_connections == []


def test_agent_spec_action_can_be_string_reference() -> None:
    spec = AgentSpec.model_validate(
        {
            "name": "a",
            "actions": [
                "shared_action1",
                {"name": "x", "listen_to": ["input"]},
            ],
        }
    )
    assert spec.actions[0] == "shared_action1"
    assert isinstance(spec.actions[1], ActionSpec)


def test_yaml_document_requires_agents() -> None:
    with pytest.raises(ValidationError):
        YamlAgentsDocument.model_validate({})


def test_yaml_document_minimal() -> None:
    doc = YamlAgentsDocument.model_validate({"agents": [{"name": "a"}]})
    assert len(doc.agents) == 1
    assert doc.agents[0].name == "a"
    assert doc.chat_model_connections == []
    assert doc.actions == []


def test_yaml_document_with_shared_resources_and_actions() -> None:
    doc = YamlAgentsDocument.model_validate(
        {
            "agents": [{"name": "a"}],
            "chat_model_connections": [{"name": "c", "clazz": "x.Y"}],
            "actions": [{"name": "shared", "listen_to": ["input"]}],
        }
    )
    assert doc.chat_model_connections[0].name == "c"
    assert isinstance(doc.actions[0], ActionSpec)
    assert doc.actions[0].name == "shared"


def test_skills_spec_requires_paths() -> None:
    with pytest.raises(ValidationError):
        SkillsSpec.model_validate({"name": "s"})


def test_skills_spec_with_paths() -> None:
    spec = SkillsSpec.model_validate({"name": "s", "paths": ["./a", "./b"]})
    assert spec.paths == ["./a", "./b"]


def test_skills_spec_forbids_extras() -> None:
    with pytest.raises(ValidationError):
        SkillsSpec.model_validate({"name": "s", "paths": ["./a"], "extra": 1})


def test_agent_spec_has_skills_field() -> None:
    spec = AgentSpec.model_validate({"name": "a"})
    assert spec.skills == []


def test_yaml_document_has_skills_field() -> None:
    doc = YamlAgentsDocument.model_validate({"agents": [{"name": "a"}]})
    assert doc.skills == []


def test_yaml_document_and_agent_reject_events() -> None:
    # ``events:`` declarations used to be accepted silently and dropped by
    # the loader, at both the document level and the agent level. Both
    # levels now forbid the key outright so the mistake surfaces at
    # validation time.
    with pytest.raises(ValidationError):
        YamlAgentsDocument.model_validate(
            {"agents": [{"name": "a"}], "events": [{"name": "evt"}]}
        )
    with pytest.raises(ValidationError):
        AgentSpec.model_validate({"name": "a", "events": [{"name": "evt"}]})


_SCHEMA_FILE = Path(__file__).parents[5] / "docs" / "yaml-schema.json"


def test_action_spec_rejects_parameter_types() -> None:
    # Action signatures are fixed; parameter_types is not exposed.
    import pytest

    with pytest.raises(ValueError, match="parameter_types"):
        ActionSpec.model_validate(
            {
                "name": "a1",
                "listen_to": ["input"],
                "type": "java",
                "parameter_types": ["x.Y"],
            }
        )


def test_tool_spec_accepts_parameter_types() -> None:
    spec = ToolSpec.model_validate(
        {"name": "t1", "type": "java", "parameter_types": ["a.B", "a.C"]}
    )
    assert spec.parameter_types == ["a.B", "a.C"]


def test_tool_spec_parameter_types_defaults_to_none() -> None:
    spec = ToolSpec.model_validate({"name": "t1"})
    assert spec.parameter_types is None


def test_checked_in_schema_matches_pydantic_models() -> None:
    on_disk = _SCHEMA_FILE.read_text()
    fresh = export()
    assert on_disk == fresh, (
        "docs/yaml-schema.json is out of sync with Pydantic models. "
        "Run: python -m flink_agents.api.yaml.specs "
        "> docs/yaml-schema.json"
    )
