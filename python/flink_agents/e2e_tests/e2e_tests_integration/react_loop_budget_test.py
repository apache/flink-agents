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
"""Per-chat budgets through public Python APIs in a local Flink job."""

from datetime import timedelta
from pathlib import Path
from time import sleep
from typing import Any, Sequence

import pytest
from pyflink.common import Configuration, Encoder
from pyflink.common.typeinfo import Types
from pyflink.datastream import StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import StreamingFileSink

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import ChatMessage, ToolCallBlock
from flink_agents.api.chat_result import ChatResult, TokenUsage
from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.decorators import action, chat_model_setup, tool
from flink_agents.api.events.chat_event import ChatResponseError
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.react_loop_budget import ReActLoopBudget
from flink_agents.api.resource import ResourceDescriptor
from flink_agents.api.runner_context import RunnerContext
from flink_agents.e2e_tests.e2e_tests_integration.chat_call_test import Model
from flink_agents.e2e_tests.e2e_tests_integration.internal_subagent_test import (
    InputKeySelector,
)


class BudgetModel(Model):
    """Always request a tool until the framework disables tools."""

    def chat(self, messages: Sequence[ChatMessage], **kwargs: Any) -> ChatResult:
        if kwargs.get("tools") == []:
            assert "Do not call tools" in messages[-1].text
            assert any(message.role.value == "tool" for message in messages)
            return ChatResult(message=ChatMessage.assistant("closed"))
        if messages[-1].text == "hello:time":
            sleep(11)
        return ChatResult(
            message=ChatMessage.assistant(
                [ToolCallBlock(call_id="echo", name="echo", input={})]
            ),
            usage=TokenUsage(prompt_tokens=3, completion_tokens=2),
        )


class BudgetAgent(Agent):
    @chat_model_setup
    @staticmethod
    def model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=f"{BudgetModel.__module__}.BudgetModel",
            model="fake",
            connection="unused",
            tools=["echo"],
        )

    @tool
    @staticmethod
    def echo() -> str:
        return "tool result"

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    async def run(event: Event, ctx: RunnerContext) -> None:
        mode, limit = event.input.split(":")
        budget = ReActLoopBudget(
            max_turns=1 if limit == "turn" else None,
            max_tokens=5 if limit == "token" else None,
            max_time=timedelta(seconds=10 if limit == "time" else 60),
            mode=mode,
        )
        try:
            result = await ctx.chat(
                "model", [ChatMessage.user(f"hello:{limit}")], budget=budget
            )
            output = result.text
        except ChatResponseError as error:
            output = str(error)
        ctx.send_event(OutputEvent(output=output))


@pytest.mark.parametrize("mode", ["SOFT", "HARD"])
@pytest.mark.parametrize("limit", ["turn", "token", "time"])
def test_python_budget_job(tmp_path: Path, mode: str, limit: str) -> None:
    config = Configuration()
    config.set_string("restart-strategy.type", "disable")
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_parallelism(1)
    agents = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agents.get_config().set(AgentExecutionOptions.CHAT_ASYNC, False)
    agents.get_config().set(AgentExecutionOptions.TOOL_CALL_ASYNC, False)
    agents.get_config().set(AgentExecutionOptions.MAX_RETRIES, 0)
    output = (
        agents.from_datastream(
            input=env.from_collection([f"{mode}:{limit}"]),
            key_selector=InputKeySelector(),
        )
        .apply(BudgetAgent())
        .to_datastream()
    )
    directory = tmp_path / "output"
    output.map(str, Types.STRING()).add_sink(
        StreamingFileSink.for_row_format(
            str(directory), Encoder.simple_string_encoder()
        ).build()
    )
    agents.execute()
    result = "".join(
        path.read_text() for path in directory.rglob("*") if path.is_file()
    ).strip()
    if mode == "SOFT":
        assert result == "closed"
    else:
        assert f"{limit.upper()}_BUDGET_EXCEEDED" in result
