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
"""Event-backed Python chat calls through a real Flink operator and Pemja bridge."""

import os
import sysconfig
from pathlib import Path
from typing import Any, Sequence

import pytest
from pyflink.common import Configuration, Encoder
from pyflink.common.typeinfo import Types
from pyflink.datastream import StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import StreamingFileSink

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import (
    ChatMessage,
    MessageRole,
    TextBlock,
    ToolCallBlock,
    ToolResultBlock,
)
from flink_agents.api.chat_models.chat_model import BaseChatModelSetup
from flink_agents.api.chat_result import ChatResult
from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.decorators import action, chat_model_setup, tool
from flink_agents.api.events.chat_event import ChatResponseError, ChatResponseEvent
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.tools import InjectedArg
from flink_agents.e2e_tests.e2e_tests_integration.internal_subagent_test import (
    InputKeySelector,
    _run_and_collect,
)

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]


class Model(BaseChatModelSetup):
    """Deterministic provider, with no external services."""

    def open(self) -> None:
        """Resolve tools without opening an external connection."""
        self.tools = [
            self.resource_context.get_resource(name, ResourceType.TOOL)
            for name in self.tools
        ]

    @property
    def model_kwargs(self) -> dict[str, Any]:
        """Return no provider options."""
        return {}

    def chat(self, messages: Sequence[ChatMessage], **kwargs: Any) -> ChatResult:
        """Return one tool call, its final answer, or an ordinary provider failure."""
        if messages[0].text == "fail":
            msg = "provider refused"
            raise ValueError(msg)
        if messages[-1].role == MessageRole.TOOL:
            tool_result = messages[-1].blocks[0]
            assert isinstance(tool_result, ToolResultBlock)
            tool_text = tool_result.blocks[0]
            assert isinstance(tool_text, TextBlock)
            return ChatResult(message=ChatMessage.assistant(f"answer:{tool_text.text}"))
        return ChatResult(
            message=ChatMessage.assistant(
                [ToolCallBlock(call_id="echo-1", name="echo", input={"value": "hello"})]
            )
        )


class ChatAgent(Agent):
    """Calls chat from an async action while keeping event-style handlers available."""

    @chat_model_setup
    @staticmethod
    def model() -> ResourceDescriptor:
        """Declare the deterministic model."""
        return ResourceDescriptor(
            clazz=f"{Model.__module__}.{Model.__name__}",
            model="fake",
            connection="unused",
            tools=["echo"],
        )

    @tool(injected_args={"caller": InjectedArg.from_short_term_memory("caller")})
    @staticmethod
    def echo(value: str, caller: str) -> str:
        """Echo the supplied value."""
        assert caller == "visible"
        return value

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    async def run(event: Event, ctx: RunnerContext) -> None:
        """Exercise sequential calls and the locally cached handle."""
        ctx.short_term_memory.set("caller", "visible")
        ctx.sensory_memory.set("caller", "private")
        ctx.chat("model", [ChatMessage.user("unused")])
        call = ctx.chat("model", [ChatMessage.user("hello")])
        result = await call
        assert (await call) is result
        assert ctx.short_term_memory.get("caller") == "visible"
        assert set(ctx.sensory_memory.get_fields()) == {"caller"}
        with pytest.raises(ChatResponseError, match="provider refused"):
            await ctx.chat("model", [ChatMessage.user("fail")])
        ctx.send_event(OutputEvent(output=result.text))

    @action(ChatResponseEvent.EVENT_TYPE)
    @staticmethod
    def ordinary_response(event: Event, ctx: RunnerContext) -> None:
        """Private responses must not trigger this user handler."""
        msg = "private response leaked"
        raise AssertionError(msg)


def test_python_chat_call_end_to_end(tmp_path: Path) -> None:
    """Await drives the whole chat/tool loop with a single async worker, including JDK 11."""
    config = Configuration()
    config.set_string("restart-strategy.type", "disable")
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_parallelism(1)
    agents = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agents.get_config().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 1)
    output = (
        agents.from_datastream(
            input=env.from_collection(["hello"]), key_selector=InputKeySelector()
        )
        .apply(ChatAgent())
        .to_datastream()
    )
    result_dir = tmp_path / "results"
    output.map(str, Types.STRING()).add_sink(
        StreamingFileSink.for_row_format(
            str(result_dir), Encoder.simple_string_encoder()
        ).build()
    )
    agents.execute()
    result = "".join(
        path.read_text() for path in result_dir.rglob("*") if path.is_file()
    )
    assert result.strip() == "answer:hello"


def test_python_chat_inside_internal_subagent(tmp_path: Path) -> None:
    """Nested chat keeps child resources and private routing on the shared runtime."""
    result = _run_and_collect(ChatAgent(), tmp_path)
    assert result.strip() == "answer:hello"


class MixedCallsAgent(Agent):
    """Exercise one gather across all built-in execution modes."""

    @chat_model_setup
    @staticmethod
    def model() -> ResourceDescriptor:
        """Reuse the deterministic chat model declaration."""
        return ChatAgent.model()

    @tool(injected_args={"caller": InjectedArg.from_short_term_memory("caller")})
    @staticmethod
    def echo(value: str, caller: str) -> str:
        """Exercise an ordinary tool inside the concurrently running chat."""
        return ChatAgent.echo(value, caller)

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    async def run(event: Event, ctx: RunnerContext) -> None:
        ctx.short_term_memory.set("caller", "visible")
        internal = await ctx.get_resource("child", ResourceType.AGENT).submit(
            ctx, "hello"
        )
        deferred = await ctx.get_resource("deferred", ResourceType.AGENT).submit(
            ctx, "hello"
        )
        remote = await ctx.get_resource("remote", ResourceType.AGENT).submit(
            ctx, "hello"
        )
        results = await ctx.gather(
            ctx.execute_async(lambda: "ordinary"),
            ctx.chat("model", [ChatMessage.user("hello")]),
            internal,
            ctx.durable_execute_async(lambda: "durable", durable_id="mixed-durable"),
            deferred,
            remote,
        )
        assert all(result.is_success() for result in results), results
        assert results[0].value == "ordinary"
        assert results[1].value.text == "answer:hello"
        assert results[2].value.result == ["echo:hello"]
        assert results[3].value == "durable"
        assert results[4].value.success
        assert results[5].value.success
        assert await internal is results[2].value
        assert (
            await ctx.durable_execute_async(lambda: "after", durable_id="after")
            == "after"
        )
        ctx.send_event(OutputEvent(output="mixed-ok"))


def test_python_mixed_gather_end_to_end(tmp_path: Path) -> None:
    from flink_agents.api.subagent import InternalSubagentResourceDescriptorBuilder
    from flink_agents.e2e_tests.e2e_tests_integration.internal_subagent_test import (
        EchoChildAgent,
    )
    from flink_agents.e2e_tests.e2e_tests_integration.subagent_external_integration_agent import (
        InMemoryAsyncSubagentSetup,
        InMemoryDeferredSubagentSetup,
    )

    agent = MixedCallsAgent()
    agent.add_resource(
        "child",
        ResourceType.AGENT,
        InternalSubagentResourceDescriptorBuilder().agent(EchoChildAgent()).build(),
    )
    for name, setup in (
        ("deferred", InMemoryDeferredSubagentSetup),
        ("remote", InMemoryAsyncSubagentSetup),
    ):
        agent.add_resource(
            name,
            ResourceType.AGENT,
            ResourceDescriptor(clazz=f"{setup.__module__}.{setup.__name__}"),
        )
    assert _run_and_collect(agent, tmp_path).strip() == "mixed-ok"
