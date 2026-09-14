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
"""Integration tests for the Python external sub-agent modes.

Each mode runs a real embedded Flink job (MiniCluster) so the Python action
executes on the Java runtime, exercising the async (durable pub/sub) and
deferred external setups end to end, including their success and failure
outcomes.
"""

import json
import os
import sysconfig
from collections.abc import Callable
from pathlib import Path

from pyflink.common import Configuration, Encoder
from pyflink.common.typeinfo import Types
from pyflink.datastream import RuntimeExecutionMode, StreamExecutionEnvironment

from flink_agents.api.agents.agent import Agent
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.e2e_tests.e2e_tests_integration.subagent_external_integration_agent import (
    build_async_agent,
    build_deferred_agent,
)

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]


def _run_agent(agent_factory: Callable[[], Agent], result_dir: Path) -> list[str]:
    from pyflink.datastream.connectors.file_system import StreamingFileSink

    config = Configuration()
    config.set_string("state.backend.type", "rocksdb")
    config.set_string("execution.checkpointing.interval", "1s")
    config.set_string("restart-strategy.type", "disable")
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)

    input_stream = env.from_collection(["ok-input", "please-fail"])

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output_datastream = (
        agents_env.from_datastream(input=input_stream, key_selector=lambda x: x)
        .apply(agent_factory())
        .to_datastream()
    )

    result_dir.mkdir(parents=True, exist_ok=True)
    output_datastream.map(lambda x: json.dumps(x), Types.STRING()).add_sink(
        StreamingFileSink.for_row_format(
            base_path=str(result_dir.absolute()),
            encoder=Encoder.simple_string_encoder(),
        ).build()
    )
    agents_env.execute()

    lines: list[str] = []
    for file in result_dir.rglob("*"):
        if file.is_file():
            with file.open() as f:
                lines.extend(line.strip() for line in f if line.strip())
    return lines


def test_async_external_subagent(tmp_path: Path) -> None:
    """The async external sub-agent completes and fails on the Java runtime."""
    results = _run_agent(build_async_agent, tmp_path / "results")
    assert sorted(results) == sorted(
        ['"async[reviewer]:ok-input"', '"ERR:async run failed on demand"']
    )


def test_deferred_external_subagent(tmp_path: Path) -> None:
    """The deferred external sub-agent completes and fails on the Java runtime."""
    results = _run_agent(build_deferred_agent, tmp_path / "results")
    assert sorted(results) == sorted(
        ['"deferred[reviewer]:ok-input"', '"ERR:deferred run failed on demand"']
    )
