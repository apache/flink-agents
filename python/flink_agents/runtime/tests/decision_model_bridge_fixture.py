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

from flink_agents.api.decision_models import (
    BaseDecisionModelConnection,
    ChoiceAnswer,
    ChoiceProbability,
    DecisionRequest,
    DecisionResult,
    PredicateAnswer,
    RefusalAnswer,
    ScoreAnswer,
    ScoreProbability,
    TokenUsage,
)

_lifecycle = {"opens": 0, "closes": 0, "calls": 0}


def reset() -> None:
    for key in _lifecycle:
        _lifecycle[key] = 0


def snapshot() -> str:
    return json.dumps(_lifecycle)


class BridgeDecisionConnection(BaseDecisionModelConnection):
    def open(self) -> None:
        _lifecycle["opens"] += 1

    def close(self) -> None:
        _lifecycle["closes"] += 1

    def decide(self, request: DecisionRequest, *, model: str) -> DecisionResult:
        _lifecycle["calls"] += 1
        if request.input == "fail":
            msg = "decision provider failed"
            raise RuntimeError(msg)
        return DecisionResult(
            answers={
                "eligible": PredicateAnswer(probability_true=0.9),
                "route": ChoiceAnswer(
                    choice=True,
                    probabilities=[
                        ChoiceProbability(value=True, probability=0.75),
                        ChoiceProbability(value="true", probability=0.25),
                    ],
                    provider_confidence=0.5,
                ),
                "impact": ScoreAnswer(
                    score=0.75,
                    probabilities=[
                        ScoreProbability(
                            value=0, label={"description": "轻微"}, probability=0.25
                        ),
                        ScoreProbability(value=1, label=["严重"], probability=0.75),
                    ],
                ),
                "blocked": RefusalAnswer(),
            },
            model="reported-" + model,
            usage=TokenUsage(input_tokens=7),
            metadata={"request": request.model_dump(mode="json")},
        )
