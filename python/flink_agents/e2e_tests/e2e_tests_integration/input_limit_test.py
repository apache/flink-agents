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
"""Input admission limits through the Python async execution path."""

import json
import time

import pytest
from pyflink.common import Configuration
from pyflink.datastream import RuntimeExecutionMode, StreamExecutionEnvironment

from flink_agents.api.agents.agent import Agent
from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.runner_context import RunnerContext


class InputLimitAgent(Agent):
    """Hold each input in an async call and record its per-key memory and timing."""

    @action(EventType.InputEvent)
    @staticmethod
    async def process(event: Event, ctx: RunnerContext) -> None:
        key = InputEvent.from_event(event).input
        started = time.monotonic_ns()
        await ctx.durable_execute_async(time.sleep, 1.0)
        count = int(ctx.short_term_memory.get("count") or 0) + 1
        ctx.short_term_memory.set("count", count)
        ctx.send_event(
            OutputEvent(output=json.dumps([key, count, started, time.monotonic_ns()]))
        )


@pytest.mark.parametrize("max_in_flight", [1, 2])
def test_input_limit_controls_async_overlap(max_in_flight: int) -> None:
    """Enforce the cap while allowing async completions and preserving keyed state."""
    config = Configuration()
    config.set_string("restart-strategy.type", "disable")
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)
    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agents_env.get_config().set(
        AgentExecutionOptions.MAX_IN_FLIGHT_INPUT_RECORDS, max_in_flight
    )
    agents_env.get_config().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2)
    output = (
        agents_env.from_datastream(
            input=env.from_collection([1, 2, 1]), key_selector=lambda value: value
        )
        .apply(InputLimitAgent())
        .to_datastream()
    )
    with output.execute_and_collect() as results:
        spans = sorted(json.loads(value) for value in results)

    assert [(key, count) for key, count, _, _ in spans] == [(1, 1), (1, 2), (2, 1)]
    first, repeated, other_key = spans
    assert repeated[2] >= first[3]
    if max_in_flight == 1:
        assert other_key[2] >= first[3]
        assert repeated[2] >= other_key[3]
    else:
        # Positive control: the fixture must actually overlap independent keys at cap 2.
        assert max(first[2], other_key[2]) < min(first[3], other_key[3])
