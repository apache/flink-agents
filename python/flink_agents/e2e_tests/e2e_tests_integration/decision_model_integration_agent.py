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
"""Call a registered decision model through built-in events in a real Flink job."""

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decision_models import (
    ChoiceQuestion,
    DecisionRequest,
    PredicateQuestion,
    ScoreQuestion,
)
from flink_agents.api.decorators import (
    action,
    decision_model_connection,
    decision_model_setup,
)
from flink_agents.api.events.decision_event import (
    DecisionRequestEvent,
    DecisionResponseEvent,
)
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.resource import ResourceDescriptor, ResourceName
from flink_agents.api.runner_context import RunnerContext


class DecisionModelTestAgent(Agent):
    """Evaluate three independent questions for each incoming record."""

    def __init__(
        self, provider: str, model: str, api_key: str, base_url: str | None = None
    ) -> None:
        super().__init__()
        self._connection_class, self._setup_class = {
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
        self._model = model
        self._connection_options = {"api_key": api_key}
        if base_url:
            self._connection_options["base_url"] = base_url

    @decision_model_connection
    def connection(self) -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=self._connection_class, **self._connection_options
        )

    @decision_model_setup
    def decision(self) -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=self._setup_class, connection="connection", model=self._model
        )

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def decide(event: Event, ctx: RunnerContext) -> None:
        departments = {
            "billing": "The stated department is billing.",
            "technical": "The stated department is technical.",
        }
        text = InputEvent.from_event(event).input
        request_event = DecisionRequestEvent(
            model="decision",
            request=DecisionRequest(
                input=text,
                questions={
                    "refund": PredicateQuestion(
                        instructions="Does the input explicitly say a refund is required?"
                    ),
                    "department": ChoiceQuestion(
                        instructions="Select the department explicitly stated in the input.",
                        criteria=departments,
                    ),
                    "impact": ScoreQuestion(
                        instructions="Rate the impact explicitly stated in the input.",
                        criteria=[
                            "The stated impact is low.",
                            "The stated impact is medium.",
                            "The stated impact is high.",
                        ],
                    ),
                },
            ),
        )
        ctx.sensory_memory.set(str(request_event.id), text)
        ctx.send_event(request_event)

    @action(DecisionResponseEvent.EVENT_TYPE)
    @staticmethod
    def emit_result(event: Event, ctx: RunnerContext) -> None:
        response = DecisionResponseEvent.from_event(event)
        ctx.send_event(
            OutputEvent(
                output={
                    "input": ctx.sensory_memory.get(str(response.request_id)),
                    "result": response.response.model_dump(mode="json"),
                }
            )
        )
