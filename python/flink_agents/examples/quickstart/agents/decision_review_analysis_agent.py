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
"""Analyze product reviews with score, choice, and predicate decisions."""

from typing import TYPE_CHECKING, cast

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decision_models import (
    ChoiceQuestion,
    DecisionRequest,
    PredicateQuestion,
    RefusalAnswer,
    ScoreQuestion,
)
from flink_agents.api.decorators import action
from flink_agents.api.events.decision_event import (
    DecisionRequestEvent,
    DecisionResponseEvent,
)
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.runner_context import RunnerContext
from flink_agents.examples.quickstart.agents.custom_types_and_resources import (
    ProductReview,
    notify_shipping_manager,
)

if TYPE_CHECKING:
    from flink_agents.api.decision_models import (
        ChoiceAnswer,
        PredicateAnswer,
        ScoreAnswer,
    )


class DecisionReviewAnalysisAgent(Agent):
    """Score satisfaction, classify the main complaint, and flag shipping damage.

    The job registers ``review_analysis_model`` as a shared decision resource.
    The input Action submits the review and criteria to the built-in Decision Action.
    The response Action applies a business threshold to the shipping-damage probability.
    The example notification prints to stdout.
    """

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        """Submit one product review to the built-in Decision Action."""
        review = ProductReview.model_validate(InputEvent.from_event(event).input)
        reasons = {
            "none": "The reviewer expresses no dissatisfaction.",
            "shipping": "Late delivery, shipping damage, or missing delivery.",
            "quality": "The product is defective, unreliable, or poorly made.",
            "fit": "The product's size, fit, or comfort is unsatisfactory.",
            "other": "Dissatisfaction that does not fit another category.",
        }
        request = DecisionRequestEvent(
            model="review_analysis_model",
            request=DecisionRequest(
                input=review.review,
                questions={
                    "satisfaction": ScoreQuestion(
                        instructions="Rate the satisfaction expressed in this review.",
                        criteria=[
                            "Very dissatisfied: strongly negative.",
                            "Dissatisfied: mostly negative.",
                            "Neutral: mixed or neutral.",
                            "Satisfied: mostly positive.",
                            "Very satisfied: strongly positive.",
                        ],
                    ),
                    "primary_reason": ChoiceQuestion(
                        instructions=(
                            "Select the main reason for dissatisfaction. Choose none "
                            "if the reviewer expresses no dissatisfaction."
                        ),
                        criteria=reasons,
                    ),
                    "shipping_damage": PredicateQuestion(
                        instructions=(
                            "Does the reviewer complain that the product arrived "
                            "damaged during shipping? Product failure after use or "
                            "late delivery alone does not count as shipping damage."
                        ),
                    ),
                },
            ),
        )
        ctx.sensory_memory.set(str(request.id), review.model_dump(mode="json"))
        ctx.send_event(request)

    @action(DecisionResponseEvent.EVENT_TYPE)
    @staticmethod
    def process_decision_response(event: Event, ctx: RunnerContext) -> None:
        """Handle the correlated analysis or refusals and apply business policy."""
        response = DecisionResponseEvent.from_event(event)
        result = response.response
        review = ProductReview.model_validate(
            ctx.sensory_memory.get(str(response.request_id))
        )

        refusals = {
            name: answer.reason
            for name, answer in result.answers.items()
            if isinstance(answer, RefusalAnswer)
        }
        if refusals:
            ctx.send_event(OutputEvent(output={"id": review.id, "refusals": refusals}))
            return

        satisfaction = cast("ScoreAnswer", result.answers["satisfaction"])
        reason = cast("ChoiceAnswer", result.answers["primary_reason"])
        shipping_damage = cast("PredicateAnswer", result.answers["shipping_damage"])

        # The Action chooses the threshold and the follow-up, not the model.
        if shipping_damage.probability_true >= 0.8:
            notify_shipping_manager(id=review.id, review=review.review)

        ctx.send_event(
            OutputEvent(
                output={
                    "id": review.id,
                    # Five levels have indices 0..4; retain the fractional score.
                    "score": satisfaction.score + 1,
                    "primary_reason": reason.choice,
                    "shipping_damage_probability": shipping_damage.probability_true,
                }
            )
        )
