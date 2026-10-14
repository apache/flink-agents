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
"""Call a registered decision model from an Action in a real Flink job."""

from typing import TYPE_CHECKING, cast

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decision_models import (
    ChoiceQuestion,
    DecisionRequest,
    PredicateQuestion,
    ScoreQuestion,
)
from flink_agents.api.decorators import action
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.resource import ResourceDescriptor, ResourceName, ResourceType
from flink_agents.api.runner_context import RunnerContext
from flink_agents.integrations.decision_models.openai_decision_model import (
    OpenAIDecisionModelSetup,
)

if TYPE_CHECKING:
    from flink_agents.api.decision_models import BaseDecisionModelSetup


class DecisionModelTestAgent(Agent):
    """Evaluate three independent questions for each incoming record."""

    def __init__(
        self, provider: str, model: str, api_key: str, base_url: str | None = None
    ) -> None:
        super().__init__()
        connection_class, setup_class = {
            "typesafe": (
                ResourceName.DecisionModel.TYPESAFE_CONNECTION,
                ResourceName.DecisionModel.TYPESAFE_SETUP,
            ),
            "dashscope": (
                ResourceName.DecisionModel.TYPESAFE_CONNECTION,
                ResourceName.DecisionModel.TYPESAFE_SETUP,
            ),
            "openai": (
                ResourceName.DecisionModel.OPENAI_CONNECTION,
                ResourceName.DecisionModel.OPENAI_SETUP,
            ),
        }[provider]
        options = {"api_key": api_key}
        if base_url:
            options["base_url"] = base_url
        self.add_resource(
            "connection",
            ResourceType.DECISION_MODEL_CONNECTION,
            ResourceDescriptor(clazz=connection_class, **options),
        )
        self.add_resource(
            "decision",
            ResourceType.DECISION_MODEL,
            ResourceDescriptor(clazz=setup_class, connection="connection", model=model),
        )

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def decide(event: Event, ctx: RunnerContext) -> None:
        model = cast(
            "BaseDecisionModelSetup",
            ctx.get_resource("decision", ResourceType.DECISION_MODEL),
        )
        departments = {
            "billing": "The stated department is billing.",
            "technical": "The stated department is technical.",
        }
        choices = (
            [
                {"value": value, "description": description}
                for value, description in departments.items()
            ]
            if isinstance(model, OpenAIDecisionModelSetup)
            else departments
        )
        text = InputEvent.from_event(event).input
        result = model.decide(
            DecisionRequest(
                input=text,
                questions={
                    "refund": PredicateQuestion(
                        instructions="Does the input explicitly say a refund is required?"
                    ),
                    "department": ChoiceQuestion(
                        instructions="Select the department explicitly stated in the input.",
                        criteria=choices,
                    ),
                    "impact": ScoreQuestion(
                        instructions="Rate the impact explicitly stated in the input.",
                        criteria=[
                            {
                                "label": "low",
                                "description": "The stated impact is low.",
                            },
                            {
                                "label": "medium",
                                "description": "The stated impact is medium.",
                            },
                            {
                                "label": "high",
                                "description": "The stated impact is high.",
                            },
                        ],
                    ),
                },
            )
        )
        ctx.send_event(
            OutputEvent(
                output={"input": text, "result": result.model_dump(mode="json")}
            )
        )
