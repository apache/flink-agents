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
"""Agent routing chat requests through Python model routers with mock models."""

from typing import Any, Dict, Sequence

from pydantic import BaseModel
from pyflink.datastream import KeySelector

from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.chat_models.routing import (
    CustomRoutingExecutor,
    ModelRouter,
    RoutingContext,
    RoutingDecision,
    RoutingStrategy,
    Strategies,
)
from flink_agents.api.chat_result import ChatResult
from flink_agents.api.decorators import action, chat_model_connection
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.resource import ResourceDescriptor, ResourceType
from flink_agents.api.runner_context import RunnerContext


class RoutingInput(BaseModel):
    """A request sent to one of the routers."""

    id: int
    router: str
    content: str


class RoutingOutput(BaseModel):
    """The answer and the routing metadata reported for a request."""

    id: int
    answer: str
    selected_model: str
    final_model: str
    decision_source: str
    fallback_models_tried: list[str]


class RoutingKeySelector(KeySelector):
    """Partition requests by id."""

    def get_key(self, value: RoutingInput) -> int:
        """Return the request id."""
        return value.id


class EchoConnection(BaseChatModelConnection):
    """Answer with the model name, or fail for the model named ``broken``."""

    def chat(
        self, messages: Sequence[ChatMessage], tools: Any = None, **kwargs: Any
    ) -> ChatResult:
        """Echo the latest message prefixed by the model name."""
        model = kwargs["model"]
        if model == "broken":
            msg = "broken model is unavailable"
            raise RuntimeError(msg)
        return ChatResult(
            message=ChatMessage.assistant(f"{model}: {messages[-1].text}")
        )


class EchoChatModel(BaseChatModelSetup):
    """Chat model setup passing its model name to the connection."""

    def open(self) -> None:
        """Do nothing."""

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return the model name."""
        return {"model": self.model}

    def chat(self, messages: Sequence[ChatMessage], **kwargs: Any) -> ChatResult:
        """Delegate to the connection."""
        connection = self.resource_context.get_resource(
            self.connection, ResourceType.CHAT_MODEL_CONNECTION
        )
        return connection.chat(messages, **self.model_kwargs)


class UrgentExecutor(CustomRoutingExecutor):
    """Select the configured model for urgent requests and abstain otherwise."""

    def __init__(self, args: Dict[str, Any]) -> None:
        """Keep the strategy arguments."""
        self.args = args

    def route(
        self, strategy: RoutingStrategy, context: RoutingContext
    ) -> RoutingDecision:
        """Select by keyword."""
        if self.args["keyword"] in context.last_user_message():
            return RoutingDecision.of(self.args["model"])
        return RoutingDecision.abstain()


def _chat_model(model: str) -> ResourceDescriptor:
    return ResourceDescriptor(
        clazz=f"{EchoChatModel.__module__}.{EchoChatModel.__name__}",
        connection="echo_connection",
        model=model,
    )


class ModelRoutingAgent(Agent):
    """Send each input to the router it names and report the routing result."""

    def __init__(self) -> None:
        """Register three chat models and two routers over them."""
        super().__init__()
        for model in ("small", "big", "broken"):
            self.add_resource(model, ResourceType.CHAT_MODEL, _chat_model(model))
        self.add_resource(
            "rule_router",
            ResourceType.MODEL_ROUTER,
            ModelRouter.of("small", "big", "broken")
            .strategy(Strategies.rules({"broken": "sql", "big": "code"}))
            .default_model("small")
            .fallback(enabled=True)
            .build(),
        )
        self.add_resource(
            "custom_router",
            ResourceType.MODEL_ROUTER,
            ModelRouter.of("small", "big")
            .strategy(
                Strategies.custom(UrgentExecutor, {"keyword": "urgent", "model": "big"})
            )
            .default_model("small")
            .build(),
        )

    @chat_model_connection
    @staticmethod
    def echo_connection() -> ResourceDescriptor:
        """Connection shared by the mock chat models."""
        return ResourceDescriptor(
            clazz=f"{EchoConnection.__module__}.{EchoConnection.__name__}"
        )

    @action(EventType.InputEvent)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        """Send the request to the router named by the input."""
        request = RoutingInput.model_validate(InputEvent.from_event(event).input)
        ctx.short_term_memory.set("input_id", request.id)
        ctx.send_event(
            ChatRequestEvent(
                model=request.router,
                messages=[ChatMessage.of(MessageRole.USER, request.content)],
            )
        )

    @action(EventType.ChatResponseEvent)
    @staticmethod
    def process_chat_response(event: Event, ctx: RunnerContext) -> None:
        """Emit the answer with the routing metadata of the final response."""
        response = ChatResponseEvent.from_event(event)
        routing = response.get_attr("model_routing")
        ctx.send_event(
            OutputEvent(
                output=RoutingOutput(
                    id=ctx.short_term_memory.get("input_id"),
                    answer=response.response.text,
                    selected_model=routing["selected_model"],
                    final_model=routing["final_model"],
                    decision_source=routing["decision_source"],
                    fallback_models_tried=routing["fallback_models_tried"],
                )
            )
        )
