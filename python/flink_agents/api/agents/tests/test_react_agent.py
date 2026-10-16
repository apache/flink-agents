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
from typing import Any, Callable
from unittest.mock import MagicMock

import pytest
from pydantic import BaseModel, Field
from pyflink.common.typeinfo import Types

from flink_agents.api.agents.react_agent import (
    _DEFAULT_CHAT_MODEL,
    _DEFAULT_NATIVE_SCHEMA_PROMPT,
    _DEFAULT_SCHEMA_PROMPT,
    ReActAgent,
)
from flink_agents.api.agents.types import render_row_type_json_schema
from flink_agents.api.chat_message import MessageRole
from flink_agents.api.events.event import InputEvent
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import (
    JavaResourceDescriptor,
    ResourceDescriptor,
    ResourceType,
)
from flink_agents.api.skills import Skills

# Named rather than imported so building an agent needs no chat model on the path;
# a descriptor records the class and resolves it only when the resource is created.
_CHAT_MODEL_CLASS = (
    "flink_agents.integrations.chat_models.ollama_chat_model.OllamaChatModelSetup"
)

# Pinned as a literal, identical to the Java test's, so the two languages cannot drift.
_NATIVE_SCHEMA_INSTRUCTION = (
    "The final response must include the information for every required field of"
    " the following schema. It does not need to be in JSON format. Schema: "
)


class Person(BaseModel):
    """A representative BaseModel output schema."""

    name: str
    age: int


class Unrenderable(BaseModel):
    """A schema carrying a member that no JSON Schema can express."""

    cb: Callable[[int], int]


class FieldLess(BaseModel):
    """A schema declaring no fields."""


class Described(BaseModel):
    """A schema with a described required field and an optional one."""

    name: str = Field(description="Full legal name of the person")
    nickname: str | None = None


def _agent(output_schema: Any) -> ReActAgent:
    return ReActAgent(
        chat_model=ResourceDescriptor(
            clazz=_CHAT_MODEL_CLASS, connection="ollama_connection", model="qwen3:8b"
        ),
        output_schema=output_schema,
    )


def _schema_prompt(agent: ReActAgent) -> str:
    """The prompt text the agent derived from the output schema."""
    return agent._resources[ResourceType.PROMPT][_DEFAULT_SCHEMA_PROMPT].template


def _native_schema_prompt(agent: ReActAgent) -> str:
    """The prompt text the agent uses when the schema is applied natively."""
    return agent._resources[ResourceType.PROMPT][_DEFAULT_NATIVE_SCHEMA_PROMPT].template


def _expected_prompt(rendered: Any) -> str:
    return f"The final response should be json format, and match the schema {rendered}."


def _bare_agent() -> ReActAgent:
    """A ReActAgent with neither a prompt nor an output schema."""
    return ReActAgent(
        chat_model=ResourceDescriptor(clazz=_CHAT_MODEL_CLASS, model="qwen3:8b")
    )


def _context_without_prompt() -> tuple[MagicMock, list]:
    """A mock context owning no prompt resource, collecting sent events."""
    ctx = MagicMock()
    ctx.get_resource = MagicMock(side_effect=KeyError("no such resource"))
    ctx.get_action_config_value = MagicMock(return_value=None)
    sent_events: list = []
    ctx.send_event = MagicMock(side_effect=lambda e: sent_events.append(e))
    return ctx, sent_events


def _sole_user_text(sent_events: list) -> str:
    """The text of the single user message of the emitted chat request."""
    assert len(sent_events) == 1
    messages = sent_events[0].messages
    assert len(messages) == 1
    assert messages[0].role == MessageRole.USER
    return messages[0].text


def _start_action_config(agent: ReActAgent) -> dict:
    """The action config the agent registered for its start action."""
    return agent._actions["start_action"][2]


# --- built-in prompt scoping ---


def test_configured_agent_declares_built_in_prompts_in_action_config() -> None:
    """A configured agent declares its built-in prompts in the start action config."""
    agent = ReActAgent(
        chat_model=ResourceDescriptor(clazz=_CHAT_MODEL_CLASS, model="qwen3:8b"),
        prompt=Prompt.from_text("Task: {input}"),
        output_schema=Person,
    )

    config = _start_action_config(agent)
    assert config["has_user_prompt"] is True
    assert config["output_schema"] is not None


def test_bare_agent_declares_no_built_in_prompt() -> None:
    """A bare agent declares no built-in prompt in the start action config."""
    config = _start_action_config(_bare_agent())

    assert config["has_user_prompt"] is False
    assert config["output_schema"] is None


def test_undeclared_prompts_are_not_looked_up() -> None:
    """A prompt reachable but not declared by this agent is not looked up."""
    ctx, sent_events = _context_without_prompt()
    # A runtime whose child scope falls back to the root agent's resources makes a
    # parent ReActAgent's prompts reachable under the same names.
    ctx.get_resource = MagicMock(return_value=Prompt.from_text("PARENT ONLY: {input}"))

    ReActAgent.start_action(InputEvent(input="review the diff"), ctx)

    assert _sole_user_text(sent_events) == "review the diff"
    ctx.get_resource.assert_not_called()


def test_declared_output_schema_inserts_schema_instruction() -> None:
    """A declared output schema inserts the schema instruction before the user message."""
    ctx, sent_events = _context_without_prompt()
    output_schema = _start_action_config(_agent(Person))["output_schema"]
    ctx.get_action_config_value = MagicMock(
        side_effect=lambda key: output_schema if key == "output_schema" else None
    )
    ctx.get_resource = MagicMock(return_value=Prompt.from_text("match the schema"))

    ReActAgent.start_action(InputEvent(input="review the diff"), ctx)

    messages = sent_events[0].messages
    assert [m.text for m in messages] == ["match the schema", "review the diff"]
    assert messages[1].role == MessageRole.USER
    ctx.get_resource.assert_any_call(_DEFAULT_SCHEMA_PROMPT, ResourceType.PROMPT)


def test_declared_prompt_that_cannot_be_resolved_is_reported() -> None:
    """A declared prompt that cannot be resolved is reported, not dropped."""
    ctx, _ = _context_without_prompt()
    ctx.get_action_config_value = MagicMock(
        side_effect=lambda key: key == "has_user_prompt" or None
    )

    with pytest.raises(RuntimeError, match="_default_user_prompt"):
        ReActAgent.start_action(InputEvent(input="review the diff"), ctx)


# --- start_action input dispatch ---


def test_single_input_map_without_prompt_becomes_user_message() -> None:
    """A single-input map without a prompt passes the value through."""
    ctx, sent_events = _context_without_prompt()

    ReActAgent.start_action(InputEvent(input={"input": "analyze this review"}), ctx)

    assert _sole_user_text(sent_events) == "analyze this review"


def test_multi_key_map_without_prompt_still_reports_prompt_requirement() -> None:
    """A multi-key map without a prompt still reports that a prompt is required."""
    ctx, _ = _context_without_prompt()

    with pytest.raises(RuntimeError, match="not primitive types"):
        ReActAgent.start_action(InputEvent(input={"input": "x", "other": "y"}), ctx)


def test_unrenderable_output_schema_raises_naming_the_model() -> None:
    """A schema that cannot be rendered fails at construction, not at the provider."""
    with pytest.raises(TypeError, match="Unrenderable cannot be rendered"):
        _agent(Unrenderable)


def test_field_less_output_schema_reaches_the_schema_prompt() -> None:
    """A schema declaring no fields renders, and reaches the prompt as rendered."""
    assert _schema_prompt(_agent(FieldLess)) == _expected_prompt(
        FieldLess.model_json_schema()
    )


def test_renderable_output_schema_keeps_the_schema_prompt() -> None:
    """An ordinary schema yields the prompt built from its rendered JSON Schema."""
    assert _schema_prompt(_agent(Person)) == _expected_prompt(
        Person.model_json_schema()
    )


def test_row_type_info_output_schema_renders_json_schema_in_the_prompt() -> None:
    """A flat RowTypeInfo reaches the prompt as its JSON Schema."""
    row_type = Types.ROW_NAMED(["name"], [Types.STRING()])
    assert _schema_prompt(_agent(row_type)) == _expected_prompt(
        '{"type":"object","properties":{"name":{"type":"string"}},'
        '"required":["name"],"additionalProperties":false}'
    )


def test_row_type_info_without_json_type_keeps_the_prompt_fallback() -> None:
    """A row with a field no JSON Schema type fits keeps its own prompt text."""
    row_type = Types.ROW_NAMED(["initial"], [Types.CHAR()])
    agent = _agent(row_type)
    assert _schema_prompt(agent) == _expected_prompt(row_type)
    assert _native_schema_prompt(agent) == f"{_NATIVE_SCHEMA_INSTRUCTION}{row_type}"


def test_row_type_info_with_duplicate_names_keeps_the_prompt_fallback() -> None:
    """A row with duplicate field names keeps its own prompt text."""
    row_type = Types.ROW_NAMED(["x", "x"], [Types.STRING(), Types.INT()])
    agent = _agent(row_type)
    assert _schema_prompt(agent) == _expected_prompt(row_type)
    assert _native_schema_prompt(agent) == f"{_NATIVE_SCHEMA_INSTRUCTION}{row_type}"


def test_renderable_output_schema_registers_the_native_schema_prompt() -> None:
    """The native prompt carries the same rendered schema as the JSON prompt."""
    assert (
        _native_schema_prompt(_agent(Person))
        == f"{_NATIVE_SCHEMA_INSTRUCTION}{Person.model_json_schema()}"
    )


def test_native_schema_prompt_shows_field_descriptions_and_required_fields() -> None:
    """The native prompt keeps each field's description and the required list."""
    prompt = _native_schema_prompt(_agent(Described))

    assert "'description': 'Full legal name of the person'" in prompt
    assert "'required': ['name']" in prompt


def test_row_type_info_output_schema_registers_the_native_schema_prompt() -> None:
    """A flat RowTypeInfo reaches the native prompt as its JSON Schema."""
    row_type = Types.ROW_NAMED(["name"], [Types.STRING()])
    assert (
        _native_schema_prompt(_agent(row_type))
        == f"{_NATIVE_SCHEMA_INSTRUCTION}{render_row_type_json_schema(row_type)}"
    )


def test_unsupported_output_schema_type_reports_the_type() -> None:
    """A schema of neither supported kind is rejected, named by the type received."""
    with pytest.raises(TypeError, match=r"<class 'str'> is not supported"):
        _agent("not-a-schema")


def _descriptor(**arguments: Any) -> ResourceDescriptor:
    return ResourceDescriptor(clazz=_CHAT_MODEL_CLASS, **arguments)


def _chat_model(chat_model: ResourceDescriptor, skills: Any) -> ResourceDescriptor:
    """The chat model descriptor an agent registers for the given skills."""
    agent = ReActAgent(chat_model=chat_model, skills=skills)
    return agent._resources[ResourceType.CHAT_MODEL][_DEFAULT_CHAT_MODEL]


def test_constructor_only_skills_become_the_chat_model_skills() -> None:
    """Constructor skills alone become the chat model's skills."""
    actual = _chat_model(_descriptor(model="qwen3:8b"), ["a", "b"])

    assert actual.arguments == {"model": "qwen3:8b", "skills": ["a", "b"]}


@pytest.mark.parametrize("skills", [None, [], ()])
def test_without_constructor_skills_the_descriptor_is_used_as_given(
    skills: Any,
) -> None:
    """No constructor skills add nothing, so an absent ``skills`` stays absent."""
    with_skills = _descriptor(skills=["a"])
    without_skills = _descriptor(model="qwen3:8b")

    assert _chat_model(with_skills, skills) is with_skills
    assert _chat_model(without_skills, skills) is without_skills
    assert "skills" not in without_skills.arguments


def test_combined_skills_are_ordered_and_deduplicated() -> None:
    """Descriptor skills come first, then constructor skills, each name kept once."""
    chat_model = _descriptor(skills=["a", "b"])

    actual = _chat_model(chat_model, ["b", "c", "a", "c"])

    assert actual.arguments["skills"] == ["a", "b", "c"]
    assert chat_model.arguments["skills"] == ["a", "b"]


def test_descriptor_shared_across_agents_stays_independent() -> None:
    """Agents sharing one descriptor each get only their own constructor skills."""
    chat_model = _descriptor(skills=["shared"])

    first = _chat_model(chat_model, ["a"])
    second = _chat_model(chat_model, ["b"])

    assert first.arguments["skills"] == ["shared", "a"]
    assert second.arguments["skills"] == ["shared", "b"]
    assert chat_model.arguments["skills"] == ["shared"]


def test_constructor_skills_keep_allowed_commands() -> None:
    """Constructor skills leave the descriptor's command policy untouched."""
    chat_model = _descriptor(
        skills=["a"],
        allowed_commands=["echo", "bc"],
        allowed_script_dirs=["/opt/scripts"],
    )

    actual = _chat_model(chat_model, ["b"])

    assert actual.arguments["allowed_commands"] == ["echo", "bc"]
    assert actual.arguments["allowed_script_dirs"] == ["/opt/scripts"]


def test_constructor_skills_keep_the_descriptor_language() -> None:
    """Constructor skills on a Java chat model keep it a Java declaration."""
    chat_model = JavaResourceDescriptor(clazz="com.example.ChatModel", skills=["a"])

    actual = _chat_model(chat_model, ["b"])

    assert isinstance(actual, JavaResourceDescriptor)
    assert (actual.target_module, actual.target_clazz) == ("", "com.example.ChatModel")
    assert actual.arguments["skills"] == ["a", "b"]


def test_bare_string_skills_are_rejected() -> None:
    """A bare string is rejected rather than split into one-letter skill names."""
    with pytest.raises(TypeError, match="not the string 'math-calculator'"):
        _chat_model(_descriptor(), "math-calculator")


def _chat_model_of(agent: ReActAgent) -> ResourceDescriptor:
    """The chat model descriptor an agent registered."""
    return agent._resources[ResourceType.CHAT_MODEL][_DEFAULT_CHAT_MODEL]


def test_skills_object_records_sources_and_registers_the_resource() -> None:
    """A Skills object becomes a source marker plus an agent-level resource."""
    chat_model = _descriptor(model="qwen3:8b")
    skills = Skills.from_local_dir("/tmp/skill-a", "/tmp/skill-b")

    agent = ReActAgent(chat_model=chat_model, skills=skills)
    actual = _chat_model_of(agent)

    sources = actual.arguments["skills_sources"]
    assert [spec.scheme for spec in sources] == ["local", "local"]
    assert sources[0].params == {"path": "/tmp/skill-a"}
    assert sources[1].params == {"path": "/tmp/skill-b"}
    # The original descriptor is never modified.
    assert "skills_sources" not in chat_model.arguments
    # The Skills object is registered under the reserved agent-level name.
    assert "_react_agent_skills" in agent.resources[ResourceType.SKILLS]


def test_empty_skills_object_is_a_no_op() -> None:
    """A Skills object with no sources adds neither a marker nor a resource."""
    chat_model = _descriptor(model="qwen3:8b")

    agent = ReActAgent(chat_model=chat_model, skills=Skills())
    actual = _chat_model_of(agent)

    assert actual is chat_model
    assert "skills_sources" not in actual.arguments
    assert "_react_agent_skills" not in agent.resources[ResourceType.SKILLS]


def test_skills_object_and_descriptor_skills_coexist() -> None:
    """A recorded source marker leaves the descriptor's explicit skills as given."""
    chat_model = _descriptor(skills=["a", "b"], model="qwen3:8b")

    agent = ReActAgent(
        chat_model=chat_model, skills=Skills.from_local_dir("/tmp/skill-c")
    )
    actual = _chat_model_of(agent)

    # Explicit skills are preserved; the marker carries the sources for the
    # runtime to resolve, and the two are merged when the setup is opened.
    assert actual.arguments["skills"] == ["a", "b"]
    assert len(actual.arguments["skills_sources"]) == 1
    assert "skills_sources" not in chat_model.arguments


def test_skills_object_keeps_the_descriptor_language() -> None:
    """Sources recorded on a Java chat model keep it a Java declaration."""
    chat_model = JavaResourceDescriptor(clazz="com.example.ChatModel", skills=["a"])

    agent = ReActAgent(
        chat_model=chat_model, skills=Skills.from_local_dir("/tmp/skill-b")
    )
    actual = _chat_model_of(agent)

    assert isinstance(actual, JavaResourceDescriptor)
    assert (actual.target_module, actual.target_clazz) == ("", "com.example.ChatModel")
    assert len(actual.arguments["skills_sources"]) == 1
