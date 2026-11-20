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
"""Validate event-time timer timestamps and watermark ordering in a PyFlink job."""

import sys
import sysconfig
from pathlib import Path

import pytest
from pyflink.common import Configuration, Types
from pyflink.datastream import RuntimeExecutionMode, StreamExecutionEnvironment
from pyflink.datastream.functions import ProcessFunction

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.e2e_tests.e2e_tests_integration.event_time_timer_agent import (
    EventTimeTimerAgent,
)


class VerifyTimestamp(ProcessFunction):
    def process_element(self, value, ctx):
        deadline = int(value.split(":")[1])
        assert ctx.timestamp() == deadline
        assert ctx.timer_service().current_watermark() < deadline
        yield value


def test_event_timer_results_precede_terminal_watermark(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    python_path = f"{Path(__file__).parents[3]}:{sysconfig.get_paths()['purelib']}"
    monkeypatch.setenv("PYTHONPATH", python_path)
    config = Configuration()
    config.set_string("python.pythonpath", python_path)
    config.set_string("restart-strategy.type", "disable")
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_python_executable(sys.executable)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(2)
    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output = (
        agents_env.from_datastream(
            env.from_collection([1, 2]),
            key_selector=lambda value: value,
        )
        .apply(EventTimeTimerAgent())
        .to_datastream()
        .process(VerifyTimestamp(), output_type=Types.STRING())
    )
    with output.execute_and_collect() as results:
        observed = list(results)
    assert sorted(observed) == ["1:10", "1:9", "2:10", "2:9"]
