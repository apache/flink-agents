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
import os
import sysconfig
import time
from pathlib import Path
from typing import Any

from pydantic import BaseModel
from pyflink.common import Configuration
from pyflink.datastream import KeySelector, StreamExecutionEnvironment

from flink_agents.api.agents.agent import Agent
from flink_agents.api.core_options import (
    AgentExecutionOptions,
    ShortTermMemoryTtlUpdate,
    ShortTermMemoryTtlVisibility,
)
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.runner_context import RunnerContext

current_dir = Path(__file__).parent
os.environ["PYTHONPATH"] = (
    f"{current_dir.parent.parent.parent}:{sysconfig.get_paths()['purelib']}"
)


class TtlTestInput(BaseModel):
    event_key: str
    sleep_ms: int


class TtlTestKeySelector(KeySelector):
    def get_key(self, value: TtlTestInput) -> str:
        return "test_key"


class ShortTermMemoryTtlTestAgent(Agent):
    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def input(event: Event, ctx: RunnerContext) -> None:
        input_data = TtlTestInput.model_validate(InputEvent.from_event(event).input)

        short_term_memory = ctx.short_term_memory
        existing_value = short_term_memory.get(input_data.event_key)
        current_count = 0
        if isinstance(existing_value, int):
            current_count = existing_value
        elif isinstance(existing_value, float):
            current_count = int(existing_value)

        short_term_memory.set(input_data.event_key, current_count + 1)
        time.sleep(input_data.sleep_ms / 1000)
        ctx.send_event(
            OutputEvent(
                output=(
                    f"{input_data.event_key}|"
                    f"{'NEW' if existing_value is None else 'EXISTING'}"
                )
            )
        )


def run_scenario(
    ttl_ms: int,
    sleep_ms: int,
    *,
    configure_ttl_ms: bool,
    configure_ttl_options: bool,
) -> list[Any]:
    config = Configuration()
    config.set_string("python.pythonpath", os.environ["PYTHONPATH"])
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_parallelism(1)

    input_stream = env.from_collection(
        [
            TtlTestInput(event_key="event1", sleep_ms=sleep_ms),
            TtlTestInput(event_key="event2", sleep_ms=sleep_ms),
            TtlTestInput(event_key="event1", sleep_ms=sleep_ms),
        ]
    )

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agents_config = agents_env.get_config()
    if configure_ttl_ms:
        agents_config.set(AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_MS, ttl_ms)
    if configure_ttl_options:
        agents_config.set(
            AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_UPDATE_TYPE,
            ShortTermMemoryTtlUpdate.ON_CREATE_AND_WRITE,
        )
        agents_config.set(
            AgentExecutionOptions.SHORT_TERM_MEMORY_STATE_TTL_VISIBILITY,
            ShortTermMemoryTtlVisibility.NEVER_RETURN_EXPIRED,
        )

    output_datastream = (
        agents_env.from_datastream(
            input=input_stream, key_selector=TtlTestKeySelector()
        )
        .apply(ShortTermMemoryTtlTestAgent())
        .to_datastream()
    )

    return list(output_datastream.execute_and_collect())


def test_value_still_visible_before_ttl_expiry() -> None:
    results = run_scenario(
        1000,
        0,
        configure_ttl_ms=True,
        configure_ttl_options=True,
    )

    assert results == ["event1|NEW", "event2|NEW", "event1|EXISTING"]


def test_ttl_configuration_disabled_with_zero_ttl() -> None:
    results = run_scenario(
        0,
        50,
        configure_ttl_ms=True,
        configure_ttl_options=True,
    )

    assert results == ["event1|NEW", "event2|NEW", "event1|EXISTING"]


def test_ttl_configuration_disabled_by_default() -> None:
    results = run_scenario(
        0,
        50,
        configure_ttl_ms=False,
        configure_ttl_options=True,
    )

    assert results == ["event1|NEW", "event2|NEW", "event1|EXISTING"]


def test_value_expires_after_ttl() -> None:
    results = run_scenario(
        50,
        200,
        configure_ttl_ms=True,
        configure_ttl_options=True,
    )

    assert results == ["event1|NEW", "event2|NEW", "event1|NEW"]


def test_ttl_configuration_applied_with_default_update_type_and_visibility() -> None:
    results = run_scenario(
        50,
        200,
        configure_ttl_ms=True,
        configure_ttl_options=False,
    )

    assert results == ["event1|NEW", "event2|NEW", "event1|NEW"]
