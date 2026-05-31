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
"""E2E test: a YAML-declared agent whose function tool is a Java method.

Parses ``resources/yaml_cross_language_agent.yaml`` via
``AgentsExecutionEnvironment.load_yaml`` and runs the declared agent
through the Flink remote runner. The math chat model resolves a tool
named ``calculateBMI`` that is backed by the Java static method
``org.apache.flink.agents.resource.test.ChatModelCrossLanguageAgent.calculateBMI``,
exercising the cross-language path from a Python chat model to a Java
function tool.

Skipped when the Ollama client/model is not available.
"""

import os
import sysconfig
from pathlib import Path

import pytest
from pyflink.common import Configuration, Encoder, WatermarkStrategy
from pyflink.common.typeinfo import Types
from pyflink.datastream import (
    RuntimeExecutionMode,
    StreamExecutionEnvironment,
)
from pyflink.datastream.connectors.file_system import (
    FileSource,
    StreamFormat,
    StreamingFileSink,
)

from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.e2e_tests.test_utils import pull_model

current_dir = Path(__file__).parent
_RESOURCES = current_dir.parent / "resources"

# Locate the Java test-jar produced by the same e2e module. It ships the
# ``@Tool``-annotated static methods declared in the module's
# ``src/test/java`` (e.g. ``ChatModelCrossLanguageAgent.calculateBMI``).
# Building this jar is opted-in by the ``maven-jar-plugin`` ``test-jar``
# execution in the module's ``pom.xml``; the Python test skips itself when
# the jar isn't present (i.e. the user hasn't run ``mvn package`` yet).
_REPO_ROOT = current_dir.parent.parent.parent.parent
_TEST_JAR = (
    _REPO_ROOT
    / "e2e-test"
    / "flink-agents-end-to-end-tests-resource-cross-language"
    / "target"
    / "flink-agents-end-to-end-tests-resource-cross-language-0.3-SNAPSHOT-tests.jar"
)

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]

OLLAMA_MODEL = os.environ.get("OLLAMA_CHAT_MODEL", "qwen3:1.7b")

_client = pull_model(OLLAMA_MODEL)


@pytest.mark.skipif(
    _client is None,
    reason="Ollama client is not available or test model is missing.",
)
@pytest.mark.skipif(
    not _TEST_JAR.is_file(),
    reason=(
        "Cross-language test-jar is missing; run "
        "'mvn package -DskipTests -pl e2e-test/"
        "flink-agents-end-to-end-tests-resource-cross-language' first."
    ),
)
def test_yaml_cross_language_agent(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """``load_yaml`` → ``apply(by name)`` with a YAML-declared Java tool.

    Exercises a Python Ollama chat model that calls a Java
    ``calculateBMI`` tool declared in YAML and resolved against the
    cross-language test JAR.
    """
    monkeypatch.setenv("OLLAMA_CHAT_MODEL", OLLAMA_MODEL)
    config = Configuration()
    config.set_string("python.pythonpath", sysconfig.get_paths()["purelib"])
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)
    # Make the Java ``@Tool`` static methods declared in this module's
    # ``src/test/java`` visible to the Flink classpath.
    env.add_jars(f"file://{_TEST_JAR}")

    input_datastream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(),
            f"file:///{_RESOURCES}/yaml_cross_language_input",
        ).build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="yaml_cross_language_source",
    )

    deserialize_datastream = input_datastream.map(lambda x: str(x))

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agents_env.load_yaml(_RESOURCES / "yaml_cross_language_agent.yaml")

    output_datastream = (
        agents_env.from_datastream(
            input=deserialize_datastream, key_selector=lambda x: "orderKey"
        )
        .apply("yaml_cross_language_agent")
        .to_datastream()
    )

    result_dir = tmp_path / "results"
    result_dir.mkdir(parents=True, exist_ok=True)

    (
        output_datastream.map(
            lambda x: str(x).replace("\n", "").replace("\r", ""), Types.STRING()
        ).add_sink(
            StreamingFileSink.for_row_format(
                base_path=str(result_dir.absolute()),
                encoder=Encoder.simple_string_encoder(),
            ).build()
        )
    )

    agents_env.execute()

    actual_result = []
    for file in result_dir.iterdir():
        if file.is_dir():
            for child in file.iterdir():
                with child.open() as f:
                    actual_result.extend(f.readlines())
        if file.is_file():
            with file.open() as f:
                actual_result.extend(f.readlines())

    # Math path went through the Java ``calculateBMI`` tool:
    # 70 / (1.75 * 1.75) ≈ 22.86, so the final answer should mention 22.
    # Creative path doesn't use any tool.
    # NOTE: We join all results and search without relying on order, because
    # StreamingFileSink may produce multiple part files and iterdir() does not
    # guarantee a deterministic traversal order across platforms.
    joined = "\n".join(actual_result).lower()
    assert "22" in joined, f"math answer missing '22': {actual_result!r}"
    assert "cat" in joined, f"creative answer missing 'cat': {actual_result!r}"
