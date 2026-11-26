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
import sys
import sysconfig
from pathlib import Path

import pytest
from pyflink.common import Configuration, Encoder
from pyflink.common.typeinfo import Types
from pyflink.datastream import StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import StreamingFileSink

from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.e2e_tests.e2e_tests_resource_cross_language.embedding_async_cross_language_agent import (
    JavaEmbeddingAgent,
)


@pytest.mark.parametrize("asynchronous", [False, True])
def test_python_agent_calls_java_embedding_resources(
    tmp_path: Path, asynchronous: bool
) -> None:
    """Exercise result and usage conversion through real sync and async runtime paths."""
    root = Path(__file__).resolve().parents[4]
    test_jars = list(
        (
            root
            / "e2e-test/flink-agents-end-to-end-tests-resource-cross-language/target"
        ).glob("*-tests.jar")
    )
    assert len(test_jars) == 1, (
        "Build the cross-language test JAR before running this test"
    )
    config = Configuration()
    config.set_string("python.executable", sys.executable)
    config.set_string(
        "python.pythonpath",
        os.pathsep.join([str(root / "python"), sysconfig.get_paths()["purelib"]]),
    )
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_parallelism(1)
    env.add_jars(test_jars[0].as_uri())
    agents = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agents.get_config().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2)
    agents.get_config().set(AgentExecutionOptions.ASYNC_BATCH_PARALLELISM, 2)
    output = (
        agents.from_datastream(
            input=env.from_collection([asynchronous]),
            key_selector=lambda _: "key",
        )
        .apply(JavaEmbeddingAgent())
        .to_datastream(Types.STRING())
    )
    result_dir = tmp_path / "results"
    output.add_sink(
        StreamingFileSink.for_row_format(
            str(result_dir), Encoder.simple_string_encoder()
        ).build()
    )
    agents.execute()
    results = [
        line.strip()
        for path in result_dir.rglob("*")
        if path.is_file() and not path.name.endswith(".crc")
        for line in path.read_text().splitlines()
        if line.strip()
    ]
    assert results == ["passed"]
