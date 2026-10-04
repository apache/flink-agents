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
"""Offline ReAct sub-agent jobs exercising Python actions through the JVM operator."""

import sys
from pathlib import Path
from typing import Any, Sequence

import pytest
from pydantic import BaseModel
from pyflink.common import Encoder
from pyflink.common.typeinfo import Types
from pyflink.datastream import KeySelector, StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import StreamingFileSink

from flink_agents.api.agents.agent import Agent
from flink_agents.api.agents.react_agent import ReActAgent
from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.skills import Skills
from flink_agents.api.tools.tool import Tool


class InputKeySelector(KeySelector):
    """Keep the single test record under a stable key."""

    def get_key(self, value: Any) -> Any:
        """Return the input as its key."""
        return value


class Answer(BaseModel):
    """Structured child result."""

    answer: str


class ScriptedSetup(BaseChatModelSetup):
    """Pass the model name to a deterministic connection."""

    @property
    def model_kwargs(self) -> dict[str, Any]:
        """Select the parent or child script."""
        return {"model": self.model}


class ScriptedConnection(BaseChatModelConnection):
    """Drive one delegation and one child tool call, without a model service."""

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: list[Tool] | None = None,
        output_schema: OutputSchema | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Validate scope and return the next scripted response."""
        model = kwargs["model"]
        last = messages[-1]
        if last.role == MessageRole.TOOL:
            expected = (
                "child-only-evidence"
                if model == "child_skill"
                else "child evidence"
                if model.startswith("child")
                else "child answer"
            )
            if expected not in last.text:
                msg = f"Wrong scoped result: {last.text}"
                raise ValueError(msg)
            if model == "parent_json" and last.text != '[{"answer":"child answer"}]':
                msg = f"Structured child output lost its JSON shape: {last.text}"
                raise ValueError(msg)
            content = (
                '{"answer":"child answer"}'
                if model == "child_json"
                else "child answer"
                if model == "child_skill"
                else f"{model} answer"
            )
            return ChatMessage.of(role=MessageRole.ASSISTANT, content=content)
        if model.startswith("child"):
            if (
                not any(
                    message.text == "Literal {prompt} instructions"
                    for message in messages
                )
                or last.text != "investigate"
            ):
                msg = "Child instructions or input were not preserved"
                raise ValueError(msg)
            if model == "child_skill":
                assert any(
                    "Child research instructions" in message.text
                    for message in messages
                )
                assert all(
                    "Parent private instructions" not in message.text
                    for message in messages
                )
                name, arguments = "load_skill", {"name": "research"}
            else:
                name, arguments = "evidence", {}
        else:
            name, arguments = "_subagent_researcher", {"prompt": "investigate"}
        expected_tools = ["load_skill", "bash"] if model == "child_skill" else [name]
        if [tool.name for tool in tools] != expected_tools:
            msg = "Callable metadata was not resolved in the current scope"
            raise ValueError(msg)
        return ChatMessage.of(
            role=MessageRole.ASSISTANT,
            content="",
            tool_calls=[
                {
                    "id": "call-1",
                    "type": "function",
                    "function": {"name": name, "arguments": arguments},
                }
            ],
        )


class ChildTools:
    """Child-local tools."""

    @staticmethod
    def evidence() -> str:
        """Read evidence owned by the child."""
        return "child evidence"


class ParentTools:
    """Parent-local tools with colliding names."""

    @staticmethod
    def evidence() -> str:
        """Read evidence owned by the parent."""
        return "parent evidence"


class ExplicitParent(Agent):
    """Invoke the child from an async action."""

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    async def invoke(event: Event, ctx: RunnerContext) -> None:
        """Emit the full list of child outputs, or its failure reason."""
        child = ctx.get_resource("researcher", ResourceType.AGENT)
        prompt = (
            42 if InputEvent.from_event(event).input == "invalid" else "investigate"
        )
        result = await (await child.submit(ctx, {"prompt": prompt}))
        ctx.send_event(
            OutputEvent(
                output=result.result
                if result.success
                else f"failed:{result.error_message}"
            )
        )


def parent_agent(
    model_driven: bool, structured: bool = False, skills_path: Path | None = None
) -> Agent:
    """Build a parent and a child with colliding model/tool resource names."""
    child = ReActAgent.for_subagent(
        chat_model=ResourceDescriptor(
            clazz=f"{ScriptedSetup.__module__}.{ScriptedSetup.__name__}",
            connection="connection",
            model="child_skill"
            if skills_path
            else "child_json"
            if structured
            else "child",
            tools=[] if skills_path else ["evidence"],
            skills=["research"] if skills_path else None,
        ),
        description="Research a task",
        instructions="Literal {prompt} instructions",
        output_schema=Answer if structured else None,
    )
    child.add_resource(
        "evidence", ResourceType.TOOL, Tool.from_callable(ChildTools.evidence)
    )
    parent = (
        ReActAgent(
            chat_model=ResourceDescriptor(
                clazz=f"{ScriptedSetup.__module__}.{ScriptedSetup.__name__}",
                connection="connection",
                model="parent_json" if structured else "parent",
                subagents=["researcher"],
            )
        )
        if model_driven
        else ExplicitParent()
    )
    parent.add_resource(
        "connection",
        ResourceType.CHAT_MODEL_CONNECTION,
        ResourceDescriptor(
            clazz=f"{ScriptedConnection.__module__}.{ScriptedConnection.__name__}"
        ),
    )
    parent.add_resource(
        "evidence", ResourceType.TOOL, Tool.from_callable(ParentTools.evidence)
    )
    if skills_path:
        child.add_resource(
            "skills",
            ResourceType.SKILLS,
            Skills.from_local_dir(str(skills_path / "child")),
        )
        parent.add_resource(
            "skills",
            ResourceType.SKILLS,
            Skills.from_local_dir(str(skills_path / "parent")),
        )
    parent.add_resource("researcher", ResourceType.AGENT, child)
    return parent


@pytest.mark.parametrize(
    ("model_driven", "structured", "failure", "skills"),
    [
        (False, False, False, False),
        (True, False, False, False),
        (True, True, False, False),
        (False, False, True, False),
        (False, False, False, True),
        (True, False, False, True),
    ],
)
def test_react_subagent_tool_loop(
    tmp_path: Path, model_driven: bool, structured: bool, failure: bool, skills: bool
) -> None:
    """Both invocation paths run the child loop and resume the parent."""
    if skills:
        for scope, description, evidence in [
            ("child", "Child research instructions", "child-only-evidence"),
            ("parent", "Parent private instructions", "parent-only-evidence"),
        ]:
            directory = tmp_path / scope / "research"
            directory.mkdir(parents=True)
            (directory / "SKILL.md").write_text(
                f"---\nname: research\ndescription: {description}\n---\n{evidence}\n"
            )
    env = StreamExecutionEnvironment.get_execution_environment()
    env.set_parallelism(1)
    env.set_python_executable(sys.executable)
    agents = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agents.get_config().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 1)
    output = (
        agents.from_datastream(
            input=env.from_collection(["invalid" if failure else "hello"]),
            key_selector=InputKeySelector(),
        )
        .apply(parent_agent(model_driven, structured, tmp_path if skills else None))
        .to_datastream()
    )
    destination = tmp_path / "results"
    output.map(str, Types.STRING()).add_sink(
        StreamingFileSink.for_row_format(
            base_path=str(destination),
            encoder=Encoder.simple_string_encoder(),
        ).build()
    )
    agents.execute()
    lines = [
        line
        for path in destination.rglob("*")
        if path.is_file()
        for line in path.read_text().splitlines()
    ]
    if failure:
        assert "\n".join(lines).startswith("failed:")
        assert "only a string 'prompt'" in "\n".join(lines)
        return
    assert lines == [
        "parent_json answer"
        if structured
        else "parent answer"
        if model_driven
        else "['child answer']"
    ]
