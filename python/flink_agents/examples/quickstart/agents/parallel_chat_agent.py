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
import os
from typing import ClassVar, Dict

from flink_agents.api.agents.agent import STRUCTURED_OUTPUT, Agent
from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.decorators import action, chat_model_setup
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.resource import ResourceDescriptor, ResourceName
from flink_agents.api.runner_context import RunnerContext
from flink_agents.examples.quickstart.agents.custom_types_and_resources import (
    AspectResponse,
    SummaryResponse,
)

OLLAMA_MODEL = os.environ.get("PARALLEL_CHAT_OLLAMA_MODEL", "qwen3:1.7b")

INPUT_TEXT = "The food here is great, but the service is too slow"
ASPECTS: tuple = ("taste", "service")
N_ASPECTS = len(ASPECTS)

PARALLEL_SYSTEM_PROMPT = (
    "You are a sentiment analysis assistant. Return JSON: "
    '{"aspect":"<dimension>", "result":"<positive|negative|not_mentioned>"}'
    " — no explanation, no extra fields."
)
AGGREGATE_SYSTEM_PROMPT = (
    "You are a summary assistant. Based on the sentiment judgments for two "
    "dimensions, compose a brief one-line evaluation. Return JSON: "
    '{"summary":"taste:<positive/negative/not_mentioned>, '
    'service:<positive/negative/not_mentioned>"} — return only this JSON.'
)


class SentimentInputEvent(Event):
    """Intermediate event that broadcasts the review input to all aspect handlers."""

    EVENT_TYPE: ClassVar[str] = "SentimentInputEvent"

    def __init__(self, input_id: int, text: str) -> None:
        """Initialize with the review id and text."""
        super().__init__(
            type=SentimentInputEvent.EVENT_TYPE,
            attributes={"input_id": input_id, "text": text},
        )


def _build_aspect_request(text: str, aspect: str) -> ChatRequestEvent:
    """Build a ChatRequestEvent for a single aspect dimension."""
    return ChatRequestEvent(
        model="sentiment_model",
        messages=[
            ChatMessage(role=MessageRole.SYSTEM, content=PARALLEL_SYSTEM_PROMPT),
            ChatMessage(
                role=MessageRole.USER,
                content=f'Judge the "{aspect}" dimension: {text}',
            ),
        ],
        output_schema=OutputSchema(output_schema=AspectResponse),
    )


def _build_summarize_request(text: str, sentiments: Dict[str, str]) -> ChatRequestEvent:
    """Build a ChatRequestEvent for the aggregation phase."""
    body = (
        f"Original: {text}\n"
        + "Judgments: "
        + " ".join(f"{a}:{sentiments[a]}" for a in ASPECTS)
    )
    return ChatRequestEvent(
        model="sentiment_model",
        messages=[
            ChatMessage(role=MessageRole.SYSTEM, content=AGGREGATE_SYSTEM_PROMPT),
            ChatMessage(role=MessageRole.USER, content=body),
        ],
        output_schema=OutputSchema(output_schema=SummaryResponse),
    )


def _build_output_event(row_id: int, text: str, parsed: SummaryResponse) -> OutputEvent:
    """Build the final OutputEvent from the aggregated row."""
    return OutputEvent(output={"id": row_id, "text": text, "summary": parsed.summary})


class ParallelChatAgent(Agent):
    """An agent that demonstrates parallel LLM invocations via fan-out of
    multiple ChatRequestEvent events.

    This agent receives a restaurant review and uses an LLM to judge sentiment
    along multiple dimensions in parallel, then aggregates the results into a
    one-line summary with a final LLM call. It handles prompt construction,
    parallel chat dispatch, response accumulation, and output assembly.

    Event flow:
      1. InputEvent → request_aspect_judgments → emits SentimentInputEvent
      2. SentimentInputEvent triggers handlers in parallel:
           - handle_taste_input   → ChatRequestEvent (taste LLM call)
           - handle_service_input → ChatRequestEvent (service LLM call)
      3. Each ChatResponseEvent → handle_response (accumulates aspect results)
      4. Once all aspects received → aggregation LLM call → OutputEvent
    """

    @chat_model_setup
    @staticmethod
    def sentiment_model() -> ResourceDescriptor:
        """ChatModel for sentiment analysis."""
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_SETUP,
            connection="ollama_server",
            model=OLLAMA_MODEL,
            extract_reasoning=True,
        )

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def request_aspect_judgments(event: Event, ctx: RunnerContext) -> None:
        """Process input event and dispatch SentimentInputEvent to aspect handlers."""
        payload = InputEvent.from_event(event).input
        # Primitive types (int, str) cross the Pemja JVM boundary without serialization.
        ctx.sensory_memory.set("id", payload["id"])
        ctx.sensory_memory.set("text", payload["text"])
        ctx.send_event(SentimentInputEvent(input_id=payload["id"], text=payload["text"]))

    @action(SentimentInputEvent.EVENT_TYPE)
    @staticmethod
    def handle_taste_input(event: Event, ctx: RunnerContext) -> None:
        """Handle taste aspect: build and send ChatRequestEvent for taste judgment."""
        req = _build_aspect_request(event.get_attr("text"), "taste")
        ctx.sensory_memory.set(f"aspect_map.{req.id}", "taste")
        ctx.send_event(req)

    @action(SentimentInputEvent.EVENT_TYPE)
    @staticmethod
    def handle_service_input(event: Event, ctx: RunnerContext) -> None:
        """Handle service aspect: build and send ChatRequestEvent for service."""
        req = _build_aspect_request(event.get_attr("text"), "service")
        ctx.sensory_memory.set(f"aspect_map.{req.id}", "service")
        ctx.send_event(req)

    @action(ChatResponseEvent.EVENT_TYPE)
    @staticmethod
    def handle_response(event: Event, ctx: RunnerContext) -> None:
        """Process chat response event and send output event."""
        response_event = ChatResponseEvent.from_event(event)
        parsed = response_event.response.extra_args[STRUCTURED_OUTPUT]
        if isinstance(parsed, dict):
            parsed = SummaryResponse(**parsed) if "summary" in parsed else AspectResponse(**parsed)
        if isinstance(parsed, SummaryResponse):
            ctx.send_event(
                _build_output_event(
                    ctx.sensory_memory.get("id"),
                    ctx.sensory_memory.get("text"),
                    parsed,
                )
            )
            return
        aspect = ctx.sensory_memory.get(f"aspect_map.{response_event.request_id}")
        ctx.sensory_memory.set(f"sentiments.{aspect}", parsed.result)
        if all(ctx.sensory_memory.is_exist(f"sentiments.{a}") for a in ASPECTS):
            text = ctx.sensory_memory.get("text")
            sentiments = {a: ctx.sensory_memory.get(f"sentiments.{a}") for a in ASPECTS}
            ctx.send_event(_build_summarize_request(text, sentiments))
