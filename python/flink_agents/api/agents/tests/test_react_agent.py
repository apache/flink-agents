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

import pytest
from pydantic import BaseModel, Field
from pyflink.common.typeinfo import Types

from flink_agents.api.agents.react_agent import (
    _DEFAULT_CHAT_MODEL,
    _DEFAULT_NATIVE_SCHEMA_PROMPT,
    _DEFAULT_SCHEMA_PROMPT,
    ReActAgent,
)
from flink_agents.api.resource import (
    JavaResourceDescriptor,
    ResourceDescriptor,
    ResourceType,
)

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


def test_row_type_info_output_schema_keeps_the_prompt_fallback() -> None:
    """A RowTypeInfo has no JSON Schema render and keeps its own prompt text."""
    row_type = Types.ROW_NAMED(["name"], [Types.STRING()])
    assert _schema_prompt(_agent(row_type)) == _expected_prompt(row_type)


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
    """A RowTypeInfo reaches the native prompt as its own text."""
    row_type = Types.ROW_NAMED(["name"], [Types.STRING()])
    assert (
        _native_schema_prompt(_agent(row_type))
        == f"{_NATIVE_SCHEMA_INSTRUCTION}{row_type}"
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
