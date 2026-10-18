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
"""Decision Action replay through the FlinkRunnerContext durable call journal."""

import json
import os
import subprocess
import sys
import threading
from pathlib import Path
from unittest.mock import MagicMock, patch

import cloudpickle
import pytest

from flink_agents.api.decision_models import (
    DecisionRequest,
    DecisionResult,
    PredicateAnswer,
    PredicateQuestion,
)
from flink_agents.api.events.decision_event import DecisionRequestEvent
from flink_agents.api.events.event import Event
from flink_agents.plan.actions.decision_model_action import process_decision_request
from flink_agents.plan.configuration import AgentConfiguration
from flink_agents.runtime.flink_runner_context import FlinkRunnerContext
from flink_agents.runtime.tests.test_flink_runner_context_reconcilable import (
    _close_runner_context,
    _create_runner_context,
    _FakeJavaRunnerContext,
    _run_async,
)


@pytest.mark.parametrize("asynchronous", [False, True])
@pytest.mark.parametrize("provider_fails", [False, True])
def test_decision_action_replays_success_or_failure_across_processes(
    asynchronous: bool, provider_fails: bool, tmp_path: Path
) -> None:
    request = DecisionRequest(
        input="A broken package",
        questions={"urgent": PredicateQuestion(instructions="Needs attention?")},
    )
    request_params = {"timeout": 4.0, "extra_headers": {"X-Trace-ID": "review-1"}}
    event = DecisionRequestEvent(
        "review-decision", request, request_params=request_params
    )
    result = DecisionResult(
        answers={"urgent": PredicateAnswer(probability_true=0.8)},
        metadata={"provider": {"id": "native-request"}},
    )
    (tmp_path / "event.json").write_text(event.model_dump_json())
    (tmp_path / "result.json").write_text(result.model_dump_json())
    worker = (
        "import json\n"
        "import sys\n"
        "from flink_agents.runtime.tests.test_decision_model_action_recovery "
        "import _run_decision_action\n"
        "_run_decision_action(**json.loads(sys.argv[1]))"
    )
    responses = []
    for phase, seed in (("first", "1"), ("replay", "2")):
        completed = subprocess.run(
            [
                sys.executable,
                "-c",
                worker,
                json.dumps(
                    {
                        "directory": str(tmp_path),
                        "asynchronous": asynchronous,
                        "provider_fails": provider_fails,
                        "phase": phase,
                    }
                ),
            ],
            cwd=Path(__file__).resolve().parents[3],
            env={**os.environ, "PYTHONHASHSEED": seed},
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
        assert completed.returncode == 0, completed.stdout + completed.stderr
        responses.append(Event.from_json((tmp_path / f"{phase}.json").read_text()))

    calls = [
        json.loads(line)
        for line in (tmp_path / "provider_calls.jsonl").read_text().splitlines()
    ]
    assert calls == [
        {"request": request.model_dump(mode="json"), "request_params": request_params}
    ]
    store = cloudpickle.loads((tmp_path / "journal.pkl").read_bytes())
    assert len(store.call_results) == 1
    stored = store.call_results[0]
    assert stored.status == "SUCCEEDED"
    assert stored.exception_payload is None
    outcome = cloudpickle.loads(stored.result_payload)
    assert responses[0].request_id == responses[1].request_id == event.id
    if provider_fails:
        assert outcome == (None, "ValueError: provider unavailable")
        assert responses[0].is_failed
        assert responses[1].is_failed
        assert responses[0].error == responses[1].error == outcome[1]
    else:
        assert outcome == (result, None)
        assert responses[0].is_success
        assert responses[1].is_success
        assert responses[0].response == responses[1].response == result


def _run_decision_action(
    directory: str, asynchronous: bool, provider_fails: bool, phase: str
) -> None:
    path = Path(directory)
    event = DecisionRequestEvent.from_event(
        Event.from_json((path / "event.json").read_text())
    )

    class Model:
        def decide(
            self, actual_request: DecisionRequest, *, request_params
        ) -> DecisionResult:
            with (path / "provider_calls.jsonl").open("a") as calls:
                calls.write(
                    json.dumps(
                        {
                            "request": actual_request.model_dump(mode="json"),
                            "request_params": request_params,
                        }
                    )
                    + "\n"
                )
            if provider_fails:
                error = ValueError("provider unavailable")
                error.unserializable = threading.Lock()
                raise error
            return DecisionResult.model_validate_json(
                (path / "result.json").read_text()
            )

    journal = path / "journal.pkl"
    store = (
        cloudpickle.loads(journal.read_bytes())
        if journal.exists()
        else _FakeJavaRunnerContext()
    )
    store.current_call_index = 0
    config = AgentConfiguration({"decision.async": asynchronous, "max-retries": 0})
    ctx = _create_runner_context(store, config)
    ctx.get_resource = MagicMock(return_value=Model())
    ctx.send_event = MagicMock()
    try:
        with patch.object(
            FlinkRunnerContext, "action_metric_group", property(lambda _: None)
        ):
            _run_async(process_decision_request(event, ctx))
        ctx.send_event.assert_called_once()
        response = ctx.send_event.call_args.args[0]
        (path / f"{phase}.json").write_text(response.model_dump_json())
        journal.write_bytes(cloudpickle.dumps(store))
    finally:
        _close_runner_context(ctx)
