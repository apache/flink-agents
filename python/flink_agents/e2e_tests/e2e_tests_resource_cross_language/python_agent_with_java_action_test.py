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
"""E2E mirror of ``JavaAgentWithPythonActionTest``: Python agent + Java action body."""

import os
import sys
import sysconfig
from pathlib import Path

import pytest
from pyflink.common import Configuration, Encoder
from pyflink.common.typeinfo import Types
from pyflink.datastream import StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import StreamingFileSink

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.e2e_tests.e2e_tests_resource_cross_language.python_agent_with_java_action import (
    InvalidTriggerConditionAgent,
    PythonAgentWithJavaActionAgent,
    SingleKeySelector,
)

current_dir = Path(__file__).parent
_REPO_ROOT = current_dir.parent.parent.parent.parent
_TEST_JAR = (
    _REPO_ROOT
    / "e2e-test"
    / "flink-agents-end-to-end-tests-resource-cross-language"
    / "target"
    / "flink-agents-end-to-end-tests-resource-cross-language-0.4-SNAPSHOT-tests.jar"
)

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]


def test_apply_rejects_invalid_condition_via_java() -> None:
    env = StreamExecutionEnvironment.get_execution_environment()
    input_stream = env.from_collection([1], type_info=Types.LONG())
    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    builder = agents_env.from_datastream(
        input=input_stream,
        key_selector=SingleKeySelector(),
    )

    with pytest.raises(ValueError) as error:
        builder.apply(InvalidTriggerConditionAgent())

    assert str(error.value).startswith(
        "Invalid trigger condition #1 for action 'invalid_condition'"
    )
    assert '"type =="' in str(error.value)


@pytest.mark.skipif(
    not _TEST_JAR.is_file(),
    reason=(
        "Cross-language test-jar is missing; run "
        "'mvn package -DskipTests -pl e2e-test/"
        "flink-agents-end-to-end-tests-resource-cross-language' first."
    ),
)
def test_overlapping_conditions_run_java_action_once(tmp_path: Path) -> None:
    config = Configuration()
    config.set_string("python.pythonpath", sysconfig.get_paths()["purelib"])
    config.set_string("python.executable", sys.executable)
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_parallelism(1)
    env.add_jars(f"file://{_TEST_JAR}")

    input_stream = env.from_collection([1, 3, 5, 7, 9], type_info=Types.LONG()).map(
        lambda x: x
    )

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output_datastream = (
        agents_env.from_datastream(input=input_stream, key_selector=SingleKeySelector())
        .apply(PythonAgentWithJavaActionAgent())
        .to_datastream(Types.LONG())
    )

    result_dir = tmp_path / "results"
    result_dir.mkdir(parents=True, exist_ok=True)
    output_datastream.map(lambda x: str(x), Types.STRING()).add_sink(
        StreamingFileSink.for_row_format(
            base_path=str(result_dir.absolute()),
            encoder=Encoder.simple_string_encoder(),
        ).build()
    )

    agents_env.execute()

    actual: list[int] = []
    for file in result_dir.iterdir():
        if file.is_dir():
            for child in file.iterdir():
                with child.open() as f:
                    actual.extend(int(line.strip()) for line in f if line.strip())
        elif file.is_file():
            with file.open() as f:
                actual.extend(int(line.strip()) for line in f if line.strip())

    actual.sort()
    # 3 is first-only, 5 overlaps (and executes once), 7 is second-only,
    # while 1 and 9 do not match.
    assert actual == [6, 10, 14], f"unexpected outputs: {actual}"
