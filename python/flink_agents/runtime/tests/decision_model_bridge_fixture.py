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
"""Deterministic decision provider for tests through the real Java/Python bridge."""

import json
from asyncio import CancelledError as AsyncCancelledError
from collections.abc import Mapping
from concurrent.futures import CancelledError

from pydantic import JsonValue

from flink_agents.api.decision_models import (
    BaseDecisionModelConnection,
    BaseDecisionModelSetup,
    ChoiceAnswer,
    DecisionRequest,
    DecisionResult,
    PredicateAnswer,
    RefusalAnswer,
    ScoreAnswer,
    TokenUsage,
)
from flink_agents.api.decision_models.decision_model import _validate_request_params

_lifecycle = {"opens": 0, "closes": 0, "calls": 0, "request_params": []}


def reset() -> None:
    _lifecycle.update(opens=0, closes=0, calls=0, request_params=[])


def snapshot() -> str:
    return json.dumps(_lifecycle)


class BridgeDecisionConnection(BaseDecisionModelConnection):
    connection_id: str = "connection"

    def open(self) -> None:
        _lifecycle["opens"] += 1

    def close(self) -> None:
        _lifecycle["closes"] += 1

    def decide(
        self,
        request: DecisionRequest,
        *,
        model: str,
        request_params: Mapping[str, JsonValue] | None = None,
    ) -> DecisionResult:
        params = _validate_request_params(request_params)
        _lifecycle["calls"] += 1
        _lifecycle["request_params"].append(params)
        if request.input == "fail":
            msg = "decision provider failed"
            raise RuntimeError(msg)
        # Use the same message so bridge classification must depend on the type.
        cancellation_message = "provider stopped"
        if request.input == "cancel":
            raise CancelledError(cancellation_message)
        if request.input == "async-cancel":
            raise AsyncCancelledError(cancellation_message)
        if request.input == "interrupt":
            raise InterruptedError(cancellation_message)
        return DecisionResult(
            answers={
                "eligible": PredicateAnswer(probability_true=0.9),
                "route": ChoiceAnswer(
                    choice="true",
                    probabilities={"true": 0.75, "false": 0.25},
                    provider_confidence=0.5,
                ),
                "impact": ScoreAnswer(
                    score=0.75,
                    probabilities={0: 0.25, 1: 0.75},
                    legend=dict(enumerate(request.questions["impact"].criteria)),
                ),
                "blocked": RefusalAnswer(),
            },
            model="reported-" + model,
            usage=TokenUsage(input_tokens=7),
            metadata={
                "connection": self.connection_id,
                "request": request.model_dump(mode="json"),
                "request_params": params,
            },
        )


class BridgeDecisionSetup(BaseDecisionModelSetup):
    connection: str = "connection"
    model: str = "default-model"
