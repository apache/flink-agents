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


def test_subagent_factory_validates_input_and_keeps_instructions_literal() -> None:
    """The callable object schema matches the actual start-action contract."""
    import json
    from unittest.mock import Mock

    from flink_agents.api.events.event import InputEvent
    from flink_agents.api.runner_context import RunnerContext

    child = ReActAgent.for_subagent(
        chat_model=ResourceDescriptor(clazz=_CHAT_MODEL_CLASS),
        description="Research a task",
        instructions="Literal {prompt} instructions",
    )
    assert json.loads(child.subagent_metadata.input_schema) == {
        "type": "object",
        "properties": {"prompt": {"type": "string"}},
        "required": ["prompt"],
        "additionalProperties": False,
    }
    from flink_agents.api.chat_models.subagent_tool import SubagentTool
    from flink_agents.integrations.chat_models.chat_model_utils import to_openai_tool

    callable_tool = SubagentTool.of(
        "researcher",
        child.subagent_metadata.description,
        child.subagent_metadata.input_schema,
    )
    assert (
        to_openai_tool(metadata=callable_tool.metadata)["function"]["parameters"][
            "additionalProperties"
        ]
        is False
    )
    ctx = Mock(spec=RunnerContext)
    ctx.get_action_config_value.side_effect = lambda key: child.actions["start_action"][
        2
    ].get(key)
    ctx.get_resource.side_effect = lambda name, kind: child.resources[kind][name]
    ReActAgent.start_action(InputEvent(input={"prompt": "investigate"}), ctx)
    request = ctx.send_event.call_args.args[0]
    assert [message.text for message in request.messages] == [
        "Literal {prompt} instructions",
        "investigate",
    ]


@pytest.mark.parametrize(
    "value", [None, "task", {}, {"prompt": 1}, {"prompt": "task", "extra": 1}]
)
def test_subagent_rejects_input_outside_its_schema(value: Any) -> None:
    """Invalid requests fail before any model request is emitted."""
    from unittest.mock import Mock

    from flink_agents.api.events.event import InputEvent
    from flink_agents.api.runner_context import RunnerContext

    child = ReActAgent.for_subagent(
        chat_model=ResourceDescriptor(clazz=_CHAT_MODEL_CLASS), description="Research"
    )
    ctx = Mock(spec=RunnerContext)
    ctx.get_action_config_value.side_effect = lambda key: child.actions["start_action"][
        2
    ].get(key)
    with pytest.raises(ValueError, match="only a string 'prompt'"):
        ReActAgent.start_action(InputEvent(input=value), ctx)
    ctx.send_event.assert_not_called()


@pytest.mark.parametrize("schema", ["{} {}", "[]", "null", '{"type":"string"}'])
def test_subagent_metadata_requires_one_object_schema(schema: str) -> None:
    """Reject schemas the model cannot use to construct object arguments."""
    from flink_agents.api.subagent import SubagentMetadata

    with pytest.raises(ValueError):
        SubagentMetadata(description="Research", input_schema=schema)


def test_subagent_factory_rejects_row_output_schema() -> None:
    """The convenience accepts JSON-model schemas that delegation can normalize."""
    with pytest.raises(TypeError, match="BaseModel subclass"):
        ReActAgent.for_subagent(
            chat_model=ResourceDescriptor(clazz=_CHAT_MODEL_CLASS),
            description="Research",
            output_schema=Types.ROW_NAMED(["answer"], [Types.STRING()]),
        )
