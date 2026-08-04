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
"""E2E tests for external sub-agents on a real Flink job.

Covers registration, invocation, failure reporting via ``Result``, and
YAML-declared registration.
"""
import os
import sysconfig
from pathlib import Path

from pyflink.common import Encoder
from pyflink.common.typeinfo import Types
from pyflink.datastream import StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import StreamingFileSink

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.api.resource import ResourceType
from flink_agents.e2e_tests.e2e_tests_integration.external_subagent_agent import (
    SUBAGENT_NAME,
    ExternalSubagentAgent,
    InputKeySelector,
    MockExternalSubagentSetup,
)

current_dir = Path(__file__).parent
_RESOURCES = current_dir.parent / "resources"

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]


def _run_and_collect(agents_env, output_datastream, tmp_path: Path) -> str:
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


def test_python_external_subagent_call_end_to_end(tmp_path: Path) -> None:
    """Registration -> execution -> sub-agent response in the job output."""
    env = StreamExecutionEnvironment.get_execution_environment()
    env.set_parallelism(1)
    input_stream = env.from_collection(["a", "b"])

    agent = ExternalSubagentAgent()
    agent.add_resource(
        SUBAGENT_NAME,
        ResourceType.AGENT,
        MockExternalSubagentSetup(endpoint_url="http://ext:8080"),
    )

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output = (
        agents_env.from_datastream(input=input_stream, key_selector=InputKeySelector())
        .apply(agent)
        .to_datastream()
    )

    contents = _run_and_collect(agents_env, output, tmp_path)
    for prompt in ("a", "b"):
        assert f"HTTP response for: {prompt} from http://ext:8080" in contents


def test_python_external_subagent_failure_surfaces_via_result(
    tmp_path: Path,
) -> None:
    """A failing endpoint surfaces through Result, not a job failure."""
    env = StreamExecutionEnvironment.get_execution_environment()
    env.set_parallelism(1)
    input_stream = env.from_collection(["a"])

    agent = ExternalSubagentAgent()
    agent.add_resource(
        SUBAGENT_NAME,
        ResourceType.AGENT,
        MockExternalSubagentSetup(endpoint_url="http://down:8080", fail_on_call=True),
    )

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output = (
        agents_env.from_datastream(input=input_stream, key_selector=InputKeySelector())
        .apply(agent)
        .to_datastream()
    )

    contents = _run_and_collect(agents_env, output, tmp_path)
    # error_message is the full stack trace, which contains the original message.
    assert "endpoint http://down:8080 is down" in contents


def test_yaml_external_subagent_call_end_to_end(tmp_path: Path) -> None:
    """A YAML-declared sub-agent resolves and executes in the job."""
    env = StreamExecutionEnvironment.get_execution_environment()
    env.set_parallelism(1)
    input_stream = env.from_collection(["a", "b"])

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agents_env.load_yaml(str(_RESOURCES / "external_subagent_agent.yaml"))

    output = (
        agents_env.from_datastream(input=input_stream, key_selector=InputKeySelector())
        .apply("external_subagent_yaml_agent")
        .to_datastream()
    )

    contents = _run_and_collect(agents_env, output, tmp_path)
    for prompt in ("a", "b"):
        assert (
            f"HTTP response for: {prompt} from http://yaml-endpoint:8080" in contents
        )
