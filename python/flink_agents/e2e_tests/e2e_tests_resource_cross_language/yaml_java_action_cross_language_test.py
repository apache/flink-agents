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
"""E2E test: a YAML-declared agent whose action body is a Java method.

Companion to ``yaml_cross_language_test.py`` (Java *tool*) — this covers
the Java *action* path. Parses ``resources/yaml_cross_language_java_action.yaml``
via ``AgentsExecutionEnvironment.load_yaml`` and runs the declared agent.
The single ``type: java`` action dispatches into
``org.apache.flink.agents.resource.test.JavaActionHandler.multiplyByTwo``,
mirroring ``python_agent_with_java_action_test`` but driven by YAML. The
YAML omits ``parameter_types`` (actions have a fixed signature), so this
also guards the loader's auto-fill. No chat model is involved, so it runs
whenever the cross-language test-jar is present.
"""

import os
import sysconfig
from pathlib import Path

import pytest
from pyflink.common import Configuration, Encoder
from pyflink.common.typeinfo import Types
from pyflink.datastream import StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import StreamingFileSink

from flink_agents.api.execution_environment import AgentsExecutionEnvironment

current_dir = Path(__file__).parent
_RESOURCES = current_dir.parent / "resources"
_REPO_ROOT = current_dir.parent.parent.parent.parent
_TEST_JAR = (
    _REPO_ROOT
    / "e2e-test"
    / "flink-agents-end-to-end-tests-resource-cross-language"
    / "target"
    / "flink-agents-end-to-end-tests-resource-cross-language-0.3-SNAPSHOT-tests.jar"
)

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]


@pytest.mark.skipif(
    not _TEST_JAR.is_file(),
    reason=(
        "Cross-language test-jar is missing; run "
        "'mvn package -DskipTests -pl e2e-test/"
        "flink-agents-end-to-end-tests-resource-cross-language' first."
    ),
)
def test_yaml_agent_dispatches_java_action_body(tmp_path: Path) -> None:
    """``load_yaml`` → ``apply(by name)`` with a YAML-declared Java action."""
    config = Configuration()
    config.set_string("python.pythonpath", sysconfig.get_paths()["purelib"])
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_parallelism(1)
    env.add_jars(f"file://{_TEST_JAR}")

    input_stream = env.from_collection([1, 2, 3, 4, 5], type_info=Types.LONG()).map(
        lambda x: x
    )

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agents_env.load_yaml(_RESOURCES / "yaml_cross_language_java_action.yaml")
    output_datastream = (
        agents_env.from_datastream(input=input_stream, key_selector=lambda x: 0)
        .apply("yaml_cross_language_java_action_agent")
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
    assert actual == [2, 4, 6, 8, 10], f"unexpected outputs: {actual}"
