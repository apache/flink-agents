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
"""Credential-gated DecisionModel E2E tests using real Flink and provider services.

Set TYPESAFE_API_KEY, OPENAI_API_KEY or DASHSCOPE_API_KEY for the provider.
Optional <PROVIDER>_DECISION_MODEL selects an accessible model. TYPESAFE_BASE_URL
and OPENAI_BASE_URL override their endpoints. Bailian additionally requires
DASHSCOPE_DECISION_BASE_URL, the API root ending in /compatible-mode.
Built Flink Agents dist JARs must be available, as for the ChatModel remote E2E
tests.
"""

import json
import os
import sysconfig
from pathlib import Path

import pytest
from pyflink.common import Configuration, WatermarkStrategy
from pyflink.datastream import RuntimeExecutionMode, StreamExecutionEnvironment
from pyflink.datastream.connectors.file_system import (
    FileSource,
    StreamFormat,
)

from flink_agents.api.decision_models import (
    ChoiceAnswer,
    DecisionResult,
    PredicateAnswer,
    ScoreAnswer,
)
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from flink_agents.e2e_tests.e2e_tests_integration.decision_model_integration_agent import (
    DecisionModelTestAgent,
)

pytestmark = pytest.mark.integration


@pytest.mark.parametrize(
    ("provider", "default_model"),
    [
        ("typesafe", "jev-latest"),
        ("openai", "gpt-6-luna"),
        ("dashscope", "decision-model-preview"),
    ],
)
def test_decision_model_integration_remote(
    provider: str,
    default_model: str,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The built-in Decision Action emits correlated real-provider answers on Flink."""
    prefix = provider.upper()
    api_key = os.environ.get(f"{prefix}_API_KEY")
    if not api_key:
        pytest.skip(f"{prefix}_API_KEY is not set.")
    base_url = os.environ.get(f"{prefix}_BASE_URL")
    if provider == "dashscope":
        base_url = os.environ.get("DASHSCOPE_DECISION_BASE_URL")
        if not base_url:
            pytest.skip("DASHSCOPE_DECISION_BASE_URL is not set.")
    monkeypatch.setenv("PYTHONPATH", sysconfig.get_paths()["purelib"])
    agent = DecisionModelTestAgent(
        provider=provider,
        model=os.environ.get(f"{prefix}_DECISION_MODEL", default_model),
        api_key=api_key,
        base_url=base_url,
    )
    cases = {
        "A refund is required. Department: billing. Impact: high.": (
            True,
            "billing",
            2,
        ),
        "No refund is required. Department: technical. Impact: low.": (
            False,
            "technical",
            0,
        ),
    }
    input_dir = tmp_path / "input"
    input_dir.mkdir()
    (input_dir / "records.txt").write_text("\n".join(cases) + "\n")

    config = Configuration()
    config.set_string("restart-strategy.type", "disable")
    env = StreamExecutionEnvironment.get_execution_environment(config)
    env.set_runtime_mode(RuntimeExecutionMode.STREAMING)
    env.set_parallelism(1)
    input_stream = env.from_source(
        source=FileSource.for_record_stream_format(
            StreamFormat.text_line_format(), input_dir.as_uri()
        ).build(),
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="decision_model_source",
    )
    agents_env = AgentsExecutionEnvironment.get_execution_environment(env=env)
    output_stream = (
        agents_env.from_datastream(
            input=input_stream.map(lambda value: str(value)),
            key_selector=lambda _: "decisions",
        )
        .apply(agent)
        .to_datastream()
    )
    records = list(output_stream.execute_and_collect())
    assert len(records) == len(cases)
    assert {record["input"] for record in records} == set(cases)
    for record in records:
        requires_refund, department, impact = cases[record["input"]]
        result = DecisionResult.model_validate_json(json.dumps(record["result"]))
        assert set(result.answers) == {"refund", "department", "impact"}
        refund_answer = result.answers["refund"]
        assert isinstance(refund_answer, PredicateAnswer)
        if requires_refund:
            assert refund_answer.probability_true > 0.5
        else:
            assert refund_answer.probability_true < 0.5

        department_answer = result.answers["department"]
        assert isinstance(department_answer, ChoiceAnswer)
        assert department_answer.choice == department
        assert set(department_answer.probabilities) == {
            "billing",
            "technical",
        }
        assert sum(department_answer.probabilities.values()) == pytest.approx(
            1.0, abs=1e-3
        )

        impact_answer = result.answers["impact"]
        assert isinstance(impact_answer, ScoreAnswer)
        assert list(impact_answer.probabilities) == [0, 1, 2]
        assert impact_answer.legend == {
            index: f"The stated impact is {level}."
            for index, level in enumerate(("low", "medium", "high"))
        }
        assert (
            max(impact_answer.probabilities.items(), key=lambda entry: entry[1])[0]
            == impact
        )
        assert sum(impact_answer.probabilities.values()) == pytest.approx(1.0, abs=1e-3)
        assert impact_answer.score == pytest.approx(
            sum(
                index * probability
                for index, probability in impact_answer.probabilities.items()
            ),
            abs=1e-3,
        )
