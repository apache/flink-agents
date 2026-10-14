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
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.resource import ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.examples.quickstart.agents.custom_types_and_resources import (
    ProductReview,
    notify_shipping_manager,
)
from flink_agents.integrations.decision_models.openai_decision_model import (
    OpenAIDecisionModelSetup,
)

if TYPE_CHECKING:
    from flink_agents.api.decision_models import (
        BaseDecisionModelSetup,
        ChoiceAnswer,
        PredicateAnswer,
        ScoreAnswer,
    )


class DecisionReviewAnalysisAgent(Agent):
    """Score satisfaction, classify the main complaint, and flag shipping damage.

    The job registers ``review_analysis_model`` as a shared decision resource.
    The Action supplies the review and criteria, then applies a business threshold
    to the shipping-damage probability. The example notification prints to stdout.
    """

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        """Evaluate one product review and emit its analysis or explicit refusals."""
        review = ProductReview.model_validate(cast("InputEvent", event).input)
        model = cast(
            "BaseDecisionModelSetup",
            ctx.get_resource("review_analysis_model", ResourceType.DECISION_MODEL),
        )

        reasons = {
            "none": "The reviewer expresses no dissatisfaction.",
            "shipping": "Late delivery, shipping damage, or missing delivery.",
            "quality": "The product is defective, unreliable, or poorly made.",
            "fit": "The product's size, fit, or comfort is unsatisfactory.",
            "other": "Dissatisfaction that does not fit another category.",
        }
        # Each provider receives its native Choice criteria format.
        choice_criteria = (
            [
                {"value": value, "description": description}
                for value, description in reasons.items()
            ]
            if isinstance(model, OpenAIDecisionModelSetup)
            else reasons
        )
        result = model.decide(
            DecisionRequest(
                input=review.review,
                questions={
                    "satisfaction": ScoreQuestion(
                        instructions="Rate the satisfaction expressed in this review.",
                        criteria=[
                            {
                                "label": "Very dissatisfied",
                                "description": "Strongly negative.",
                            },
                            {
                                "label": "Dissatisfied",
                                "description": "Mostly negative.",
                            },
                            {"label": "Neutral", "description": "Mixed or neutral."},
                            {"label": "Satisfied", "description": "Mostly positive."},
                            {
                                "label": "Very satisfied",
                                "description": "Strongly positive.",
                            },
                        ],
                    ),
                    "primary_reason": ChoiceQuestion(
                        instructions=(
                            "Select the main reason for dissatisfaction. Choose none "
                            "if the reviewer expresses no dissatisfaction."
                        ),
                        criteria=choice_criteria,
                    ),
                    "shipping_damage": PredicateQuestion(
                        instructions=(
                            "Does the reviewer complain that the product arrived "
                            "damaged during shipping? Product failure after use or "
                            "late delivery alone does not count as shipping damage."
                        ),
                    ),
                },
            )
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
