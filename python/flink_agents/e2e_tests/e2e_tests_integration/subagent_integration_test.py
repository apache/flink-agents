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
"""Integration test: a Python agent using a Python sub-agent.

Runs a real embedded Flink job (MiniCluster) so the Python action executes on
the Java runtime, exercising the sub-agent path end to end: name injection,
deterministic id allocation on submit, the durable await, and the emitted
result flowing back downstream.
"""

import json
import os
import sysconfig
from pathlib import Path

from pyflink.common import Configuration, Encoder
from pyflink.common.typeinfo import Types
from pyflink.datastream import RuntimeExecutionMode, StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import StreamingFileSink

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.e2e_tests.e2e_tests_integration.subagent_integration_agent import (
    build_agent,
)

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]


def test_python_agent_uses_python_subagent(tmp_path: Path) -> None:
    """The Python sub-agent runs end to end on the Java runtime."""
    config = Configuration()
    config.set_string("state.backend.type", "rocksdb")
    config.set_string("execution.checkpointing.interval", "1s")
    config.set_string("restart-strategy.type", "disable")
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)

    input_stream = env.from_collection(["alpha", "beta"])

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output_datastream = (
        agents_env.from_datastream(input=input_stream, key_selector=lambda x: x)
        .apply(build_agent())
        .to_datastream()
    )

    result_dir = tmp_path / "results"
    result_dir.mkdir(parents=True, exist_ok=True)
    output_datastream.map(lambda x: json.dumps(x), Types.STRING()).add_sink(
        StreamingFileSink.for_row_format(
            base_path=str(result_dir.absolute()),
            encoder=Encoder.simple_string_encoder(),
        ).build()
    )

    agents_env.execute()

    results = _read_results(result_dir)
    # The sub-agent echoes the prompt, tagged with the injected resource name,
    # proving name injection + deterministic id allocation happened on the
    # runtime (short-form submit would fail otherwise).
    assert sorted(results) == sorted(
        ['"reviewed[reviewer]:alpha"', '"reviewed[reviewer]:beta"']
    )


def _read_results(result_dir: Path) -> list[str]:
    lines: list[str] = []
    for file in result_dir.rglob("*"):
        if file.is_file():
            with file.open() as f:
                lines.extend(line.strip() for line in f if line.strip())
    return lines
