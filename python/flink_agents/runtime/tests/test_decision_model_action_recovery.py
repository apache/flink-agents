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

import threading
from unittest.mock import MagicMock

import cloudpickle
import pytest

from flink_agents.api.decision_models import (
    DecisionRequest,
    DecisionResult,
    PredicateAnswer,
    PredicateQuestion,
)
from flink_agents.api.events.decision_event import DecisionRequestEvent
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
def test_decision_action_replays_serialized_success_or_failure(
    asynchronous: bool, provider_fails: bool, monkeypatch: pytest.MonkeyPatch
) -> None:
    request = DecisionRequest(
        input="A broken package",
        questions={"urgent": PredicateQuestion(instructions="Needs attention?")},
    )
    event = DecisionRequestEvent("review-decision", request)
    result = DecisionResult(
        answers={"urgent": PredicateAnswer(probability_true=0.8)},
        metadata={"provider": {"id": "native-request"}},
    )
    calls = []

    class Model:
        def decide(self, actual_request: DecisionRequest) -> DecisionResult:
            calls.append(actual_request)
            if provider_fails:
                error = ValueError("provider unavailable")
                error.unserializable = threading.Lock()
                raise error
            return result

    model = Model()
    config = AgentConfiguration({"decision.async": asynchronous})
    store = _FakeJavaRunnerContext()
    monkeypatch.setattr(
        FlinkRunnerContext, "action_metric_group", property(lambda _: None)
    )
    responses = []
    for _ in range(2):
        store.current_call_index = 0
        ctx = _create_runner_context(store, config)
        ctx.get_resource = MagicMock(return_value=model)
        ctx.send_event = MagicMock()
        try:
            _run_async(process_decision_request(event, ctx))
            ctx.send_event.assert_called_once()
            responses.append(ctx.send_event.call_args.args[0])
        finally:
            _close_runner_context(ctx)

    assert calls == [request]
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
