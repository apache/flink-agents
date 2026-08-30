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
from pydantic import BaseModel
from pyflink.common.typeinfo import Types

from flink_agents.api.agents.react_agent import (
    _DEFAULT_SCHEMA_PROMPT,
    ReActAgent,
)
from flink_agents.api.resource import ResourceDescriptor, ResourceType

# Named rather than imported so building an agent needs no chat model on the path;
# a descriptor records the class and resolves it only when the resource is created.
_CHAT_MODEL_CLASS = (
    "flink_agents.integrations.chat_models.ollama_chat_model.OllamaChatModelSetup"
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


def test_unsupported_output_schema_type_reports_the_type() -> None:
    """A schema of neither supported kind is rejected, named by the type received."""
    with pytest.raises(TypeError, match=r"<class 'str'> is not supported"):
        _agent("not-a-schema")
