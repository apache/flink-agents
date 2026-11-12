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
"""Exercise native timers through the Python Agent and PyFlink job entry points."""

import sys
import sysconfig
from itertools import islice
from pathlib import Path

import pytest
from pyflink.common import Configuration, Duration, WatermarkStrategy
from pyflink.datastream import RuntimeExecutionMode, StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import FileSource, StreamFormat

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.e2e_tests.e2e_tests_integration.native_timer_agent import (
    NativeTimerAgent,
)


def test_timers_drive_idle_keys_without_further_input(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    python_path = f"{Path(__file__).parents[3]}:{sysconfig.get_paths()['purelib']}"
    monkeypatch.setenv("PYTHONPATH", python_path)
    input_file = tmp_path / "input.txt"
    input_file.write_text("1\n2\n")

    config = Configuration()
    config.set_string("python.pythonpath", python_path)
    config.set_string("restart-strategy.type", "disable")
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_python_executable(sys.executable)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(2)
    inputs = (
        env.from_source(
            source=FileSource.for_record_stream_format(
                StreamFormat.text_line_format(), input_file.as_uri()
            )
            .monitor_continuously(Duration.of_millis(100))
            .build(),
            watermark_strategy=WatermarkStrategy.no_watermarks(),
            source_name="timer_input",
        )
        .set_parallelism(1)
        .map(lambda value: int(value))
    )
    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output = (
        agents_env.from_datastream(inputs, key_selector=lambda value: value)
        .apply(NativeTimerAgent())
        .to_datastream()
    )

    # Closing the iterator cancels the continuous job after all expected timer outputs arrive.
    with output.execute_and_collect() as results:
        observed = list(islice(results, 8))

    expected = [
        f"{key}:{suffix}"
        for key in (1, 2)
        for suffix in ("tick:1", "tick:2", "tick:3", "done")
    ]
    assert sorted(observed) == sorted(expected)
    for key in (1, 2):
        assert [value for value in observed if value.startswith(f"{key}:")] == [
            f"{key}:tick:1",
            f"{key}:tick:2",
            f"{key}:tick:3",
            f"{key}:done",
        ]
