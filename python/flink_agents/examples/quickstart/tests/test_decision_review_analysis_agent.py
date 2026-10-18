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
"""Review example behavior through the built-in Decision event chain."""

import asyncio
from unittest.mock import MagicMock, call

import pytest

from flink_agents.api.decision_models import (
    BaseDecisionModelSetup,
    ChoiceAnswer,
    DecisionRequest,
    DecisionResult,
    PredicateAnswer,
    RefusalAnswer,
    ScoreAnswer,
)
from flink_agents.api.events.decision_event import (
    DecisionRequestEvent,
    DecisionResponseError,
    DecisionResponseEvent,
)
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.memory_object import validate_memory_value
from flink_agents.api.runner_context import RunnerContext
from flink_agents.examples.quickstart.agents.decision_review_analysis_agent import (
    DecisionReviewAnalysisAgent,
)
from flink_agents.plan.actions.decision_model_action import process_decision_request
from flink_agents.plan.configuration import AgentConfiguration


def _context(model: MagicMock) -> tuple[MagicMock, list[Event]]:
    events = []
    memory = {}
    ctx = MagicMock(spec=RunnerContext)
    ctx.config = AgentConfiguration({"decision.async": False})
    ctx.action_metric_group = None
    ctx.get_resource.return_value = model
    ctx.send_event.side_effect = events.append
    ctx.durable_execute.side_effect = lambda function, *args, durable_id=None: function(
        *args
    )

    def remember(path: str, value: object) -> None:
        validate_memory_value(path, value)
        memory[path] = value

    ctx.sensory_memory.set.side_effect = remember
    ctx.sensory_memory.get.side_effect = memory.__getitem__
    return ctx, events


def _analysis(
    request: DecisionRequest, score: float, reason: str, damage: float
) -> DecisionResult:
    lower_level = int(score)
    return DecisionResult(
        answers={
            "satisfaction": ScoreAnswer(
                score=score,
                probabilities={
                    level: (
                        1 - (score - lower_level)
                        if level == lower_level
                        else score - lower_level
                        if level == lower_level + 1
                        else 0.0
                    )
                    for level in range(5)
                },
                legend=dict(enumerate(request.questions["satisfaction"].criteria)),
            ),
            "primary_reason": ChoiceAnswer(
                choice=reason,
                probabilities={
                    value: float(value == reason)
                    for value in ("none", "shipping", "quality", "fit", "other")
                },
            ),
            "shipping_damage": PredicateAnswer(probability_true=damage),
        }
    )


def _submit(review, ctx, events) -> DecisionRequestEvent:
    DecisionReviewAnalysisAgent.process_input(InputEvent(input=review), ctx)
    request = events[-1]
    assert isinstance(request, DecisionRequestEvent)
    return request


def _resolve(request, ctx, events) -> DecisionResponseEvent:
    asyncio.run(process_decision_request(request, ctx))
    response = events[-1]
    assert isinstance(response, DecisionResponseEvent)
    return response


def test_review_responses_remain_correlated_when_they_arrive_out_of_order(monkeypatch):
    notify = MagicMock()
    monkeypatch.setattr(
        "flink_agents.examples.quickstart.agents.decision_review_analysis_agent.notify_shipping_manager",
        notify,
    )
    model = MagicMock(spec=BaseDecisionModelSetup)
    reviews = [
        {"id": "book", "review": "The package arrived crushed."},
        {"id": "headphones", "review": "The headphones stopped working."},
    ]
    results = {
        reviews[0]["review"]: (0.5, "shipping", 0.8),
        reviews[1]["review"]: (1.5, "quality", 0.79),
    }
    model.decide.side_effect = lambda request, *, request_params=None: _analysis(
        request, *results[request.input]
    )
    ctx, events = _context(model)
    requests = [_submit(review, ctx, events) for review in reviews]
    model.decide.assert_not_called()
    for request in requests:
        assert request.model == "review_analysis_model"
        criteria = request.request.questions["primary_reason"].criteria
        assert list(criteria) == [
            "none",
            "shipping",
            "quality",
            "fit",
            "other",
        ]
        assert request.request.questions["satisfaction"].criteria == [
            "Very dissatisfied: strongly negative.",
            "Dissatisfied: mostly negative.",
            "Neutral: mixed or neutral.",
            "Satisfied: mostly positive.",
            "Very satisfied: strongly positive.",
        ]

    responses = [_resolve(request, ctx, events) for request in reversed(requests)]
    for response in responses:
        DecisionReviewAnalysisAgent.process_decision_response(response, ctx)

    outputs = [event.output for event in events if isinstance(event, OutputEvent)]
    assert outputs == [
        {
            "id": "headphones",
            "score": 2.5,
            "primary_reason": "quality",
            "shipping_damage_probability": 0.79,
        },
        {
            "id": "book",
            "score": 1.5,
            "primary_reason": "shipping",
            "shipping_damage_probability": 0.8,
        },
    ]
    notify.assert_called_once_with(**reviews[0])
    assert model.decide.call_args_list == [
        call(requests[1].request, request_params={}),
        call(requests[0].request, request_params={}),
    ]


def test_review_refusal_emits_correlated_refusals_without_notification(monkeypatch):
    notify = MagicMock()
    monkeypatch.setattr(
        "flink_agents.examples.quickstart.agents.decision_review_analysis_agent.notify_shipping_manager",
        notify,
    )
    model = MagicMock(spec=BaseDecisionModelSetup)
    ctx, events = _context(model)
    request = _submit({"id": "book", "review": "Damaged package"}, ctx, events)
    answers = _analysis(request.request, 0.5, "shipping", 0.9).answers
    model.decide.return_value = DecisionResult(
        answers={**answers, "satisfaction": RefusalAnswer(reason="Not enough evidence")}
    )
    response = _resolve(request, ctx, events)

    DecisionReviewAnalysisAgent.process_decision_response(response, ctx)

    assert events[-1].output == {
        "id": "book",
        "refusals": {"satisfaction": "Not enough evidence"},
    }
    notify.assert_not_called()


def test_review_provider_failure_propagates_from_the_response_action(monkeypatch):
    notify = MagicMock()
    monkeypatch.setattr(
        "flink_agents.examples.quickstart.agents.decision_review_analysis_agent.notify_shipping_manager",
        notify,
    )
    model = MagicMock(spec=BaseDecisionModelSetup)
    model.decide.side_effect = RuntimeError("provider unavailable")
    ctx, events = _context(model)
    request = _submit({"id": "book", "review": "Damaged package"}, ctx, events)
    response = _resolve(request, ctx, events)

    with pytest.raises(DecisionResponseError, match="provider unavailable") as failure:
        DecisionReviewAnalysisAgent.process_decision_response(response, ctx)

    assert failure.value.request_id == request.id
    assert not any(isinstance(event, OutputEvent) for event in events)
    notify.assert_not_called()
