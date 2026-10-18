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
"""Decision Action contracts at the RunnerContext invocation boundary."""

import asyncio
import threading
from collections import defaultdict
from concurrent.futures import CancelledError
from typing import Any, NoReturn
from unittest.mock import AsyncMock, MagicMock, call

import cloudpickle
import pytest

from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.decision_models import (
    DecisionRequest,
    DecisionResult,
    PredicateAnswer,
    PredicateQuestion,
    RefusalAnswer,
    TokenUsage,
)
from flink_agents.api.events.decision_event import (
    DecisionRequestEvent,
    DecisionResponseEvent,
)
from flink_agents.api.resource import ResourceType
from flink_agents.api.trace import ExecutionProblemCategories, ExecutionReporter
from flink_agents.plan.actions.decision_model_action import process_decision_request
from flink_agents.plan.configuration import AgentConfiguration


class _Model:
    def __init__(self, outcomes) -> None:
        self.threads = []
        self.outcomes = iter(outcomes)
        self.decide = MagicMock(side_effect=self._decide)

    @property
    def model(self) -> NoReturn:
        pytest.fail("The Action must not require a configured-model accessor.")

    def _decide(self, request, *, request_params) -> Any:
        self.threads.append(threading.get_ident())
        outcome = next(self.outcomes)
        if isinstance(outcome, BaseException):
            raise outcome
        return outcome


class _Journal:
    """Persist callback outcomes as bytes and replay them without re-invocation."""

    def __init__(self, snapshots=None) -> None:
        self.snapshots = [] if snapshots is None else snapshots
        self.position = 0

    def execute(
        self, function, *args: Any, durable_id: str | None = None, **kwargs: Any
    ) -> tuple:
        if self.position == len(self.snapshots):
            self.snapshots.append(cloudpickle.dumps(function(*args, **kwargs)))
        outcome = cloudpickle.loads(self.snapshots[self.position])
        self.position += 1
        return outcome

    async def execute_async(
        self, function, *args: Any, durable_id: str | None = None, **kwargs: Any
    ) -> tuple:
        return await asyncio.to_thread(
            self.execute, function, *args, durable_id=durable_id, **kwargs
        )


def _request() -> DecisionRequestEvent:
    return DecisionRequestEvent(
        model="review-decision",
        request=DecisionRequest(
            input={"review": "The package arrived damaged."},
            questions={"urgent": PredicateQuestion(instructions="Needs attention?")},
        ),
        request_params={"safety_identifier": "review-user", "timeout": 4.0},
    )


def _result(**kwargs: Any) -> DecisionResult:
    return DecisionResult(
        answers={"urgent": PredicateAnswer(probability_true=0.8)}, **kwargs
    )


def _context(
    model, *, decision_async=True, retries=0, interval=1, journal=None
) -> tuple[MagicMock, _Journal]:
    action_thread = threading.get_ident()

    def resource(name, resource_type) -> _Model:
        assert threading.get_ident() == action_thread
        return model

    ctx = MagicMock(spec=ExecutionReporter)
    ctx.config = AgentConfiguration(
        {
            "decision.async": decision_async,
            "max-retries": retries,
            "retry-wait-interval": interval,
        }
    )
    ctx.get_resource = MagicMock(side_effect=resource)
    ctx.send_event = MagicMock()
    ctx.action_metric_group = MagicMock()
    resource_group = ctx.action_metric_group.get_sub_group.return_value
    resource_group.get_counter.side_effect = defaultdict(MagicMock).__getitem__
    resource_group.get_sub_group.return_value.get_counter.side_effect = defaultdict(
        MagicMock
    ).__getitem__
    journal = journal or _Journal()
    ctx.durable_execute = MagicMock(side_effect=journal.execute)
    ctx.durable_execute_async = AsyncMock(side_effect=journal.execute_async)
    return ctx, journal


def _response(ctx) -> DecisionResponseEvent:
    ctx.send_event.assert_called_once()
    return ctx.send_event.call_args.args[0]


@pytest.mark.parametrize("decision_async", [False, True])
def test_success_uses_selected_durable_path_and_action_owned_context(decision_async):
    event = _request()
    result = _result(model="reported-model", metadata={"request_id": "native-id"})
    model = _Model([result])
    ctx, _ = _context(model, decision_async=decision_async)
    action_thread = threading.get_ident()

    def report(*args: Any) -> None:
        assert threading.get_ident() == action_thread

    ctx.report_execution_started.side_effect = report
    ctx.report_execution_succeeded.side_effect = report
    asyncio.run(process_decision_request(event, ctx))

    response = _response(ctx)
    assert response.is_success
    assert response.request_id == event.id
    assert response.response == result
    assert response.retry_count == 0
    assert response.total_retry_wait_sec == 0
    ctx.get_resource.assert_called_once_with(event.model, ResourceType.DECISION_MODEL)
    model.decide.assert_called_once_with(
        event.request, request_params=event.request_params
    )
    assert (model.threads[0] != action_thread) is decision_async
    assert ctx.durable_execute.call_count == (0 if decision_async else 1)
    assert ctx.durable_execute_async.await_count == (1 if decision_async else 0)
    durable_call = (
        ctx.durable_execute_async.call_args
        if decision_async
        else ctx.durable_execute.call_args
    )
    assert durable_call.kwargs == {"durable_id": "decide"}
    ctx.report_execution_started.assert_called_once()
    execution_id = ctx.report_execution_started.call_args.args[0]
    assert isinstance(execution_id, str)
    assert execution_id
    assert ctx.report_execution_started.call_args.args[1:] == ("llm", event.model, {})
    ctx.report_execution_succeeded.assert_called_once_with(
        execution_id, "llm", event.model, {}
    )
    ctx.report_execution_failed.assert_not_called()


def test_decision_async_defaults_to_enabled():
    option = AgentExecutionOptions.DECISION_ASYNC
    assert option.get_key() == "decision.async"
    assert AgentConfiguration().get(option) is True


@pytest.mark.parametrize("decision_async", [False, True])
def test_provider_failures_retry_with_exponential_wait_and_metrics(
    decision_async, monkeypatch
):
    sleep = MagicMock()
    monkeypatch.setattr(
        "flink_agents.plan.actions.decision_model_action.time.sleep", sleep
    )
    model = _Model([ValueError("first"), RuntimeError("second"), _result()])
    ctx, _ = _context(model, decision_async=decision_async, retries=3, interval=2)
    event = _request()
    asyncio.run(process_decision_request(event, ctx))

    response = _response(ctx)
    assert response.is_success
    assert response.retry_count == 2
    assert response.total_retry_wait_sec == 6
    assert sleep.call_args_list == [call(2), call(4)]
    assert model.decide.call_count == 3
    assert (
        model.decide.call_args_list
        == [call(event.request, request_params=event.request_params)] * 3
    )
    reports = [c[0] for c in ctx.mock_calls if c[0].startswith("report_execution_")]
    assert reports == [
        "report_execution_started",
        "report_execution_failed",
        "report_execution_started",
        "report_execution_failed",
        "report_execution_started",
        "report_execution_succeeded",
    ]
    execution_ids = [
        report.args[0] for report in ctx.report_execution_started.call_args_list
    ]
    assert len(set(execution_ids)) == 3
    assert all(isinstance(value, str) and value for value in execution_ids)
    assert [
        report.args[0] for report in ctx.report_execution_failed.call_args_list
    ] == execution_ids[:2]
    ctx.report_execution_succeeded.assert_called_once_with(
        execution_ids[2], "llm", "review-decision", {}
    )
    failure_report = ctx.report_execution_failed.call_args
    assert failure_report.args[1:4] == ("llm", "review-decision", {})
    assert str(failure_report.args[4]) == "RuntimeError: second"
    assert failure_report.args[5] == ExecutionProblemCategories.MODEL_CALL_FAILED
    group = ctx.action_metric_group.get_sub_group.return_value
    group.get_counter("retryCount").inc.assert_called_once_with(2)
    group.get_counter("retryWaitSec").inc.assert_called_once_with(6)


def test_request_parameters_are_specific_to_each_call():
    first = _request()
    second = DecisionRequestEvent(
        first.model, first.request, request_params={"safety_identifier": "other-user"}
    )
    model = _Model([_result(), _result()])
    for event in (first, second):
        ctx, _ = _context(model)
        asyncio.run(process_decision_request(event, ctx))
        assert _response(ctx).is_success

    assert model.decide.call_args_list == [
        call(first.request, request_params=first.request_params),
        call(second.request, request_params=second.request_params),
    ]


def test_exhausted_retries_emit_one_failure_without_extra_wait(monkeypatch):
    sleep = MagicMock()
    monkeypatch.setattr(
        "flink_agents.plan.actions.decision_model_action.time.sleep", sleep
    )
    event = _request()
    model = _Model([ValueError("unavailable")] * 3)
    ctx, _ = _context(model, retries=2)
    asyncio.run(process_decision_request(event, ctx))

    response = _response(ctx)
    assert response.is_failed
    assert response.request_id == event.id
    assert response.error == "ValueError: unavailable"
    assert response.retry_count == 2
    assert response.total_retry_wait_sec == 3
    assert sleep.call_args_list == [call(1), call(2)]
    assert ctx.report_execution_failed.call_count == 3
    ctx.report_execution_succeeded.assert_not_called()


def test_refusal_is_success_without_retry():
    result = DecisionResult(answers={"urgent": RefusalAnswer(reason="Cannot decide")})
    model = _Model([result])
    ctx, _ = _context(model, retries=3)
    asyncio.run(process_decision_request(_request(), ctx))

    response = _response(ctx)
    assert response.is_success
    assert response.response == result
    assert response.retry_count == 0
    model.decide.assert_called_once()


@pytest.mark.parametrize("result", [None, threading.Lock()])
def test_invalid_result_becomes_serializable_provider_failure(result):
    model = _Model([result])
    ctx, journal = _context(model)
    asyncio.run(process_decision_request(_request(), ctx))

    assert _response(ctx).is_failed
    assert "must return a DecisionResult" in _response(ctx).error
    outcome = cloudpickle.loads(journal.snapshots[0])
    assert outcome[0] is None
    assert isinstance(outcome[1], str)


def test_resource_lookup_failure_propagates_without_provider_retry():
    ctx, _ = _context(_Model([]), retries=3)
    failure = LookupError("missing model")
    ctx.get_resource.side_effect = failure
    with pytest.raises(LookupError) as caught:
        asyncio.run(process_decision_request(_request(), ctx))

    assert caught.value is failure
    ctx.send_event.assert_not_called()
    ctx.durable_execute_async.assert_not_called()
    ctx.report_execution_started.assert_not_called()


@pytest.mark.parametrize(
    ("source", "failure", "decision_async"),
    [
        ("provider", CancelledError("cancelled"), False),
        ("provider", InterruptedError("interrupted"), True),
        ("provider", asyncio.CancelledError(), True),
        ("lookup", InterruptedError("interrupted"), True),
        ("durable", CancelledError("cancelled"), False),
    ],
)
def test_cancellation_propagates_without_response_or_retry(
    source, failure, decision_async
):
    model = _Model([failure])
    ctx, _ = _context(model, decision_async=decision_async, retries=3)
    if source == "lookup":
        ctx.get_resource.side_effect = failure
    elif source == "durable":
        ctx.durable_execute.side_effect = failure

    async def run_action() -> None:
        # Catch at the Action boundary before asyncio.run normalizes cancellation.
        with pytest.raises(type(failure)) as caught:
            await process_decision_request(_request(), ctx)
        assert caught.value is failure

    asyncio.run(run_action())
    ctx.send_event.assert_not_called()
    ctx.report_execution_failed.assert_not_called()
    assert model.decide.call_count == (1 if source == "provider" else 0)
    assert ctx.durable_execute.call_count + ctx.durable_execute_async.call_count <= 1


@pytest.mark.parametrize("decision_async", [False, True])
@pytest.mark.parametrize("after_call", [False, True])
def test_durable_failure_propagates_without_becoming_a_model_failure(
    decision_async,
    after_call,
):
    failure = RuntimeError("journal write failed")
    model = _Model([_result()])
    ctx, _ = _context(model, decision_async=decision_async, retries=3)

    def persist(function, *args: Any, **kwargs: Any) -> NoReturn:
        if after_call:
            kwargs.pop("durable_id", None)
            function(*args, **kwargs)
        raise failure

    async def persist_async(function, *args: Any, **kwargs: Any) -> NoReturn:
        return await asyncio.to_thread(persist, function, *args, **kwargs)

    ctx.durable_execute.side_effect = persist
    ctx.durable_execute_async.side_effect = persist_async
    with pytest.raises(RuntimeError) as caught:
        asyncio.run(process_decision_request(_request(), ctx))

    assert caught.value is failure
    assert model.decide.call_count == int(after_call)
    ctx.send_event.assert_not_called()
    ctx.report_execution_failed.assert_not_called()
    ctx.report_execution_succeeded.assert_not_called()
    ctx.action_metric_group.get_sub_group.assert_not_called()


@pytest.mark.parametrize("decision_async", [False, True])
@pytest.mark.parametrize(
    "failure",
    [
        RuntimeError("persistence failed"),
        CancelledError("cancelled"),
        asyncio.CancelledError(),
    ],
)
def test_started_retry_metrics_survive_durable_failure(
    decision_async, failure, monkeypatch
):
    sleep = MagicMock()
    monkeypatch.setattr(
        "flink_agents.plan.actions.decision_model_action.time.sleep", sleep
    )
    model = _Model([ValueError("provider unavailable")])
    ctx, journal = _context(model, decision_async=decision_async, retries=3, interval=2)

    def execute(function, *args: Any, **kwargs: Any) -> tuple:
        if journal.position == 1:
            raise failure
        return journal.execute(function, *args, **kwargs)

    async def execute_async(function, *args: Any, **kwargs: Any) -> tuple:
        if journal.position == 1:
            raise failure
        return await asyncio.to_thread(journal.execute, function, *args, **kwargs)

    ctx.durable_execute.side_effect = execute
    ctx.durable_execute_async.side_effect = execute_async

    async def run_action() -> None:
        # Catch at the Action boundary before asyncio.run normalizes cancellation.
        with pytest.raises(type(failure)) as caught:
            await process_decision_request(_request(), ctx)
        assert caught.value is failure

    asyncio.run(run_action())
    ctx.send_event.assert_not_called()
    model.decide.assert_called_once()
    assert ctx.durable_execute.call_count + ctx.durable_execute_async.call_count == 2
    sleep.assert_called_once_with(2)
    group = ctx.action_metric_group.get_sub_group.return_value
    assert group.get_counter.call_args_list == [
        call("retryCount"),
        call("retryWaitSec"),
    ]
    group.get_counter("retryCount").inc.assert_called_once_with(1)
    group.get_counter("retryWaitSec").inc.assert_called_once_with(2)
    ctx.report_execution_failed.assert_called_once()
    ctx.report_execution_succeeded.assert_not_called()


@pytest.mark.parametrize("failed_attempt", [1, 2])
def test_interrupted_backoff_preserves_accumulated_retry_metrics(
    failed_attempt, monkeypatch
):
    failure = InterruptedError("backoff interrupted")
    sleep = MagicMock(side_effect=([None] * (failed_attempt - 1)) + [failure])
    monkeypatch.setattr(
        "flink_agents.plan.actions.decision_model_action.time.sleep", sleep
    )
    model = _Model([ValueError("provider unavailable")] * failed_attempt)
    ctx, _ = _context(model, retries=3, interval=2)
    with pytest.raises(InterruptedError) as caught:
        asyncio.run(process_decision_request(_request(), ctx))

    assert caught.value is failure
    ctx.send_event.assert_not_called()
    assert model.decide.call_count == failed_attempt
    group = ctx.action_metric_group.get_sub_group.return_value
    assert group.get_counter.call_args_list == [
        call("retryCount"),
        call("retryWaitSec"),
    ]
    group.get_counter("retryCount").inc.assert_called_once_with(failed_attempt)
    group.get_counter("retryWaitSec").inc.assert_called_once_with(
        2 * (failed_attempt - 1)
    )


@pytest.mark.parametrize("provider_fails", [False, True])
def test_response_delivery_failure_propagates_without_retry(provider_fails):
    failure = RuntimeError("delivery failed")
    model = _Model([ValueError("provider failed") if provider_fails else _result()])
    ctx, _ = _context(model)
    ctx.send_event.side_effect = failure
    with pytest.raises(RuntimeError) as caught:
        asyncio.run(process_decision_request(_request(), ctx))

    assert caught.value is failure
    ctx.send_event.assert_called_once()
    model.decide.assert_called_once()


@pytest.mark.parametrize(
    ("reported_model", "usage", "expected"),
    [
        (
            "actual-model",
            TokenUsage(input_tokens=0, total_tokens=4),
            {"promptTokens": 0, "totalTokens": 4},
        ),
        (None, TokenUsage(output_tokens=2), {"completionTokens": 2}),
    ],
)
def test_partial_usage_records_each_reported_count(reported_model, usage, expected):
    model = _Model([_result(model=reported_model, usage=usage)])
    ctx, _ = _context(model)
    asyncio.run(process_decision_request(_request(), ctx))

    ctx.action_metric_group.get_sub_group.assert_called_once_with(
        "model_resource", "review-decision"
    )
    resource_group = ctx.action_metric_group.get_sub_group.return_value
    if reported_model:
        resource_group.get_sub_group.assert_called_once_with("model", reported_model)
        group = resource_group.get_sub_group.return_value
    else:
        resource_group.get_sub_group.assert_not_called()
        group = resource_group
    assert group.get_counter.call_args_list == [call(name) for name in expected]
    for name, count in expected.items():
        group.get_counter(name).inc.assert_called_once_with(count)


def test_reporter_failures_do_not_change_outcomes_or_add_retries():
    model = _Model([ValueError("retry once"), _result()])
    ctx, _ = _context(model, retries=1, interval=0)
    for report in (
        ctx.report_execution_started,
        ctx.report_execution_failed,
        ctx.report_execution_succeeded,
    ):
        report.side_effect = RuntimeError("reporting failed")
    asyncio.run(process_decision_request(_request(), ctx))

    assert _response(ctx).is_success
    assert _response(ctx).retry_count == 1
    assert model.decide.call_count == 2
