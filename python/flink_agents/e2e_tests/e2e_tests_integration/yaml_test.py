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
"""E2E test: parse a single YAML file (chat model + function tool),
load it via :func:`AgentsExecutionEnvironment.load_yaml`, and run the
declared agent through the Flink remote runner.

Uses Ollama for the chat backend — the YAML's ``model`` field is
hardcoded to ``qwen3:1.7b`` (same default as the other Ollama e2e
tests). Skipped when the Ollama client/model is not available.
"""

import json
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
from flink_agents.e2e_tests.e2e_tests_integration.yaml_test_actions import (
    YamlChatInput,
    YamlChatKeySelector,
    YamlChatOutput,
)
from flink_agents.e2e_tests.test_utils import pull_model

current_dir = Path(__file__).parent
_RESOURCES = current_dir.parent / "resources"

os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"]

_OLLAMA_MODEL = "qwen3:1.7b"
_client = pull_model(_OLLAMA_MODEL)


@pytest.mark.skipif(
    _client is None,
    reason="Ollama client is not available or test model is missing.",
)
def test_single_yaml_agent(tmp_path: Path) -> None:
    """``load_yaml`` → ``apply(by name)`` through the Flink remote
    runner, exercising both the tool-using math chat model and the
    plain creative chat model declared in the same YAML.
    """
    config = Configuration()
    config.set_string("python.pythonpath", sysconfig.get_paths()["purelib"])
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)

    input_datastream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(),
            f"file:///{_RESOURCES}/yaml_test_input",
        ).build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="yaml_test_source",
    )

    deserialize_datastream = input_datastream.map(
        lambda x: YamlChatInput.model_validate_json(x)
    )

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agents_env.load_yaml(_RESOURCES / "yaml_test_agent.yaml")

    output_datastream = (
        agents_env.from_datastream(
            input=deserialize_datastream, key_selector=YamlChatKeySelector()
        )
        .apply("yaml_test_agent")
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

    answers = _read_answers(result_dir)
    # Math path went through the ``add`` tool: the model's final
    # answer should mention ``3``. Creative path should mention cats.
    assert "3" in answers[1], f"math answer missing '3': {answers[1]!r}"
    assert "cat" in answers[2].lower(), f"creative answer missing 'cat': {answers[2]!r}"


@pytest.mark.skipif(
    _client is None,
    reason="Ollama client is not available or test model is missing.",
)
def test_chained_yaml_agents(tmp_path: Path) -> None:
    """One YAML file declares ``math_agent`` and ``commentator_agent``;
    both reuse a file-level ``ollama_connection`` and the file-level
    ``process_chat_response`` action.

    The two agents register on the environment via ``load_yaml`` and
    run as a single chained Flink pipeline:

        FileSource → math_agent → commentator_agent → StreamingFileSink

    The math agent's output ``DataStream`` is fed straight into the
    commentator agent — same job, same ``agents_env.execute()`` — so
    the test exercises chaining two YAML-loaded agents end-to-end,
    proves the file-level shared connection + shared action are
    reusable across both agents, and asserts the math digit survives
    the second LLM hop.
    """
    config = Configuration()
    config.set_string("python.pythonpath", sysconfig.get_paths()["purelib"])
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)

    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    agents_env.load_yaml(_RESOURCES / "yaml_multi_agent.yaml")

    input_datastream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(),
            f"file:///{_RESOURCES}/yaml_test_math_input",
        ).build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="yaml_chained_source",
    )
    deserialize_datastream = input_datastream.map(
        lambda x: YamlChatInput.model_validate_json(x)
    )

    # Stage 1: math_agent answers the question. Stage 2 reads the
    # YamlChatOutput stream straight from stage 1 and calls a second
    # chat model — both stages reuse the file-level ``ollama_connection``
    # and the file-level ``process_chat_response`` action.
    math_output = (
        agents_env.from_datastream(
            input=deserialize_datastream, key_selector=YamlChatKeySelector()
        )
        .apply("math_agent")
        .to_datastream()
    )
    # ``to_datastream`` serialises ``OutputEvent.output`` into a plain
    # dict at the Flink boundary, so re-validate into the pydantic
    # model before feeding stage 2 (so the key selector and the
    # ``commentary_request`` action both see a typed ``YamlChatOutput``).
    math_output_typed = math_output.map(lambda x: YamlChatOutput.model_validate(x))
    final_output = (
        agents_env.from_datastream(
            input=math_output_typed, key_selector=YamlChatKeySelector()
        )
        .apply("commentator_agent")
        .to_datastream()
    )

    result_dir = tmp_path / "results"
    result_dir.mkdir(parents=True, exist_ok=True)
    final_output.map(lambda x: json.dumps(x), Types.STRING()).add_sink(
        StreamingFileSink.for_row_format(
            base_path=str(result_dir.absolute()),
            encoder=Encoder.simple_string_encoder(),
        ).build()
    )

    agents_env.execute()

    answers = _read_answers(result_dir)
    final_answer = answers[1]
    assert "3" in final_answer, (
        f"math result missing from chained output: {final_answer!r}"
    )


def _read_answers(result_dir: Path) -> dict[int, str]:
    """Collect ``{id: answer}`` from every JSON line under ``result_dir``."""
    answers: dict[int, str] = {}
    for path in result_dir.rglob("*"):
        if not path.is_file():
            continue
        for line in path.read_text().splitlines():
            line = line.strip()
            if not line:
                continue
            record = json.loads(line)
            answers[record["id"]] = record["answer"]
    return answers
