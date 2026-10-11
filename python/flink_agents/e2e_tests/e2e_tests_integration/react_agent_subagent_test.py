################################################################################
#  Licensed to the Apache Software Foundation (ASF) under one
#  contributor license agreements.  See the NOTICE file distributed with
#  this work for additional information regarding copyright ownership.
#  The ASF licenses this file to you under the Apache License, Version 2.0
#  (the "License"); you may not use this file except in compliance with
#  the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
################################################################################
"""E2E tests for a real ReActAgent running as an internal sub-agent.

The children here are the built-in agent itself, not hand-written echo agents:
its start action, the framework's chat model action and its stop action must
all run inside the child scope, driven by scripted connections instead of a
live model. The model-driven test additionally runs the whole delegation loop:
the parent model issues a ``_subagent_child`` tool call, the framework routes
it to the child, and the child's answer flows back as the tool observation
that closes the loop.

A bare child carries no declared metadata, so these tests also prove the
default schema survives compilation and the child's prompt-free ``{input}``
map path accepts the model's arguments as-is.

The third test lets the child declare a tool of its own and scripts two model
rounds for it: the first reply asks for the tool, the tool executes inside
the child scope, and the second round sees the tool result, closing the
reasoning/tool loop in isolation.
"""

import os
import sysconfig
from pathlib import Path
from typing import Any

from pyflink.common import Encoder
from pyflink.common.typeinfo import Types
from pyflink.datastream import KeySelector, StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import StreamingFileSink

from flink_agents.api.agents.agent import Agent
from flink_agents.api.agents.react_agent import ReActAgent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.tools.tool import Tool, ToolType

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]

CHILD_SCOPE = "child"


class InputKeySelector(KeySelector):
    """Keys every element by itself."""

    def get_key(self, value: Any) -> Any:
        """Return the element itself as key."""
        return value


class ScriptedModelSetup(BaseChatModelSetup):
    """A setup whose behavior lives entirely in its scripted connection."""

    @property
    def model_kwargs(self) -> dict[str, Any]:
        """Return chat model settings."""
        return {}


class ParentScriptedConnection(BaseChatModelConnection):
    """Delegates once, then answers with the observed child output."""

    def chat(
        self,
        messages: Any,
        tools: list | None = None,
        output_schema: Any = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Issue the delegation tool call, then echo the observation back."""
        self._reject_unsupported_output_schema(output_schema)
        tool_messages = [m for m in messages if m.role == MessageRole.TOOL]
        if not tool_messages:
            # The child is declared under its default schema, so the model builds
            # the single "input" argument the agent's prompt-free path accepts.
            function = {
                "name": "_subagent_child",
                "arguments": {"input": "review the diff"},
            }
            tool_call = {
                "id": "call-1",
                "type": ToolType.FUNCTION,
                "function": function,
            }
            return ChatMessage.of(
                MessageRole.ASSISTANT, "delegating", tool_calls=[tool_call]
            )
        return ChatMessage.of(
            MessageRole.ASSISTANT, f"observed: {tool_messages[0].text}"
        )


class ChildScriptedConnection(BaseChatModelConnection):
    """Echoes the last user message, revealing what the child agent received."""

    def chat(
        self,
        messages: Any,
        tools: list | None = None,
        output_schema: Any = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Answer with the child's own user message."""
        self._reject_unsupported_output_schema(output_schema)
        return ChatMessage.of(MessageRole.ASSISTANT, f"child saw: {messages[-1].text}")


def shout(text: str) -> str:
    """Return the text in upper case, marked as tool-produced."""
    return f"{text.upper()}!"


class ToolCallingChildConnection(BaseChatModelConnection):
    """Asks for the child's tool first, then answers from the tool result."""

    def chat(
        self,
        messages: Any,
        tools: list | None = None,
        output_schema: Any = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Emit the tool call on the first round, consume the result on the second."""
        self._reject_unsupported_output_schema(output_schema)
        tool_messages = [m for m in messages if m.role == MessageRole.TOOL]
        if not tool_messages:
            function = {"name": "shout", "arguments": {"text": "hello world"}}
            tool_call = {
                "id": "call-1",
                "type": ToolType.FUNCTION,
                "function": function,
            }
            return ChatMessage.of(
                MessageRole.ASSISTANT, "let me check", tool_calls=[tool_call]
            )
        return ChatMessage.of(
            MessageRole.ASSISTANT, f"tool says: {tool_messages[0].text}"
        )


def _child_react_agent() -> ReActAgent:
    """A bare ReActAgent, whose metadata comes from the defaults.

    The connection is registered on the child itself, keeping the child plan
    self-contained: a child scope resolves resources against the child plan
    only, so the connection must live there.
    """
    child = ReActAgent(
        chat_model=ResourceDescriptor(
            clazz=f"{ScriptedModelSetup.__module__}.{ScriptedModelSetup.__name__}",
            connection="child_connection",
            model="mock-model",
        ),
    )
    child.add_resource(
        "child_connection",
        ResourceType.CHAT_MODEL_CONNECTION,
        ResourceDescriptor(
            clazz=f"{ChildScriptedConnection.__module__}.{ChildScriptedConnection.__name__}",
        ),
    )
    return child


def _tool_calling_child_react_agent() -> ReActAgent:
    """A ReActAgent whose model asks for the child's own tool across two rounds."""
    child = ReActAgent(
        chat_model=ResourceDescriptor(
            clazz=f"{ScriptedModelSetup.__module__}.{ScriptedModelSetup.__name__}",
            connection="tool_child_connection",
            model="mock-model",
            tools=["shout"],
        ),
    )
    child.add_resource(
        "tool_child_connection",
        ResourceType.CHAT_MODEL_CONNECTION,
        ResourceDescriptor(
            clazz=f"{ToolCallingChildConnection.__module__}.{ToolCallingChildConnection.__name__}",
        ),
    )
    child.add_resource("shout", ResourceType.TOOL, Tool.from_callable(shout))
    return child


class CallerRootAgent(Agent):
    """Caller agent awaiting the ReAct child from an async action."""

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    async def orchestrate(event: Event, ctx: RunnerContext) -> None:
        """Await the child and emit its outcome."""
        child = ctx.get_resource(CHILD_SCOPE, ResourceType.AGENT)
        future = await child.submit(ctx, "review the diff")
        result = await future
        if result.success:
            ctx.send_event(OutputEvent(output=result.result[0]))
        else:
            msg = f"failed:{result.error_message}"
            ctx.send_event(OutputEvent(output=msg))


def _delegating_parent() -> ReActAgent:
    """A bare ReActAgent parent that declares the child to its model.

    Sub-classing ReActAgent would trip the plan compiler, which rejects
    inherited ``@action`` methods on a concrete agent, so the parent is a
    plain instance carrying instance-level resources instead.
    """
    parent = ReActAgent(
        chat_model=ResourceDescriptor(
            clazz=f"{ScriptedModelSetup.__module__}.{ScriptedModelSetup.__name__}",
            connection="parent_connection",
            model="mock-model",
            subagents=[CHILD_SCOPE],
        ),
    )
    parent.add_resource(
        "parent_connection",
        ResourceType.CHAT_MODEL_CONNECTION,
        ResourceDescriptor(
            clazz=f"{ParentScriptedConnection.__module__}.{ParentScriptedConnection.__name__}",
        ),
    )
    parent.add_resource(CHILD_SCOPE, ResourceType.AGENT, _child_react_agent())
    return parent


def _run_and_collect(root: Agent, tmp_path: Path) -> str:
    """Run the root agent over one element and collect what the job emitted."""
    env = StreamExecutionEnvironment.get_execution_environment()
    env.set_parallelism(1)
    input_stream = env.from_collection(["hello"])

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output_datastream = (
        agents_env.from_datastream(input=input_stream, key_selector=InputKeySelector())
        .apply(root)
        .to_datastream()
    )

    result_dir = tmp_path / "results"
    result_dir.mkdir(parents=True, exist_ok=True)
    output_datastream.map(str, Types.STRING()).add_sink(
        StreamingFileSink.for_row_format(
            base_path=str(result_dir.absolute()),
            encoder=Encoder.simple_string_encoder(),
        ).build()
    )
    agents_env.execute()
    return "".join(p.read_text() for p in result_dir.rglob("*") if p.is_file())


def test_caller_driven_react_subagent(tmp_path: Path) -> None:
    """A submitted string runs the child's ReAct loop and returns its answer."""
    root = CallerRootAgent()
    root.add_resource(CHILD_SCOPE, ResourceType.AGENT, _child_react_agent())

    contents = _run_and_collect(root, tmp_path)

    # The child connection saw the submitted string as its user message, which is
    # the prompt-free primitive path, and the child's reply is the call result.
    assert "child saw: review the diff" in contents, (
        f"missing child output; collected: {contents!r}"
    )


def test_model_driven_react_subagent_delegation(tmp_path: Path) -> None:
    """A model-issued delegation reaches the bare child and closes the loop."""
    contents = _run_and_collect(_delegating_parent(), tmp_path)

    # The final answer echoes the tool observation, proving the whole loop: the
    # model's {"input": ...} arguments became the child's user message through
    # the prompt-free {input} map path, and the child's reply came back as the
    # observation. A child's own output never reaches the sink directly -- only
    # this parent answer does -- so both fragments here mean the round-trip
    # completed; the assertion stays split because the observation carries the
    # result in the framework's string form, not verbatim.
    assert "observed:" in contents, f"no observation echoed; collected: {contents!r}"
    assert "child saw: review the diff" in contents, (
        f"child output missing from the loop; collected: {contents!r}"
    )


def test_child_tool_call_loop_runs_inside_the_child_scope(tmp_path: Path) -> None:
    """The child's own tool executes inside the child scope across two rounds."""
    root = CallerRootAgent()
    root.add_resource(
        CHILD_SCOPE, ResourceType.AGENT, _tool_calling_child_react_agent()
    )

    contents = _run_and_collect(root, tmp_path)

    # The tool result flowed back into the child's second model round, which
    # answered from it; the child's tool never touches the parent's resources.
    assert "tool says: HELLO WORLD!" in contents, (
        f"tool loop output missing; collected: {contents!r}"
    )
