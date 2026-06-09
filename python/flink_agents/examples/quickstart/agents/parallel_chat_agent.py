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
import json
import os
import time
from typing import Any, Dict, Tuple

from pydantic import BaseModel
from pyflink.common import Row
from pyflink.datastream import KeySelector

from flink_agents.api.agents.agent import STRUCTURED_OUTPUT, Agent
from flink_agents.api.agents.types import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.decorators import action, chat_model_setup
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.resource import ResourceDescriptor, ResourceName
from flink_agents.api.runner_context import RunnerContext

OLLAMA_MODEL = os.environ.get("PARALLEL_CHAT_OLLAMA_MODEL", "qwen3:1.7b")

INPUT_TEXT = "The food here is great, but the service is too slow"
ASPECTS: Tuple[str, ...] = ("taste", "service", "price")
N_ASPECTS = len(ASPECTS)

PARALLEL_SYSTEM_PROMPT = (
    "You are a sentiment analysis assistant. Return JSON: "
    '{"aspect":"<dimension>", "result":"<positive|negative|not_mentioned>"}'
    " — no explanation, no extra fields."
)
AGGREGATE_SYSTEM_PROMPT = (
    "You are a summary assistant. Based on the sentiment judgments for three "
    "dimensions, compose a brief one-line evaluation. Return JSON: "
    '{"summary":"taste: service: price:"} — return only this JSON.'
)


class AspectResponse(BaseModel):
    """LLM response for a single aspect judgment."""

    aspect: str
    result: str


class SummaryResponse(BaseModel):
    """LLM response for the aggregation phase."""

    summary: str


class ParallelChatKeySelector(KeySelector):
    """Key selector that extracts the id field from the input row."""

    def get_key(self, value: Row) -> int:
        """Extract key from row."""
        return value[0]


def _init_row(event: Event) -> Dict[str, Any]:
    """Build a row skeleton from the InputEvent."""
    payload = InputEvent.from_event(event).input
    return {"id": payload["id"], "text": payload["text"], "sentiments": {}}


def _save_row(ctx: RunnerContext, row: Dict[str, Any]) -> None:
    """Write the row to sensory memory."""
    ctx.sensory_memory.set("res", json.dumps(row, ensure_ascii=False))


def _load_row(ctx: RunnerContext) -> Dict[str, Any]:
    """Read the row from sensory memory."""
    return json.loads(ctx.sensory_memory.get("res"))


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


def _build_summarize_request(row: Dict[str, Any]) -> ChatRequestEvent:
    """Build a ChatRequestEvent for the aggregation phase."""
    sentiments = row["sentiments"]
    body = (
        f"Original: {row['text']}\n"
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


def _build_output_event(row: Dict[str, Any], parsed: SummaryResponse) -> OutputEvent:
    """Pack row fields and summary into the final OutputEvent."""
    return OutputEvent(
        output=Row(id=row["id"], text=row["text"], summary=parsed.summary)
    )


def _parse_response(event: Event) -> AspectResponse | SummaryResponse:
    """Parse a ChatResponseEvent into a structured response object."""
    response = ChatResponseEvent.from_event(event).response
    raw = response.extra_args[STRUCTURED_OUTPUT]
    if isinstance(raw, BaseModel):
        return raw
    if "summary" in raw:
        return SummaryResponse.model_validate(raw)
    return AspectResponse.model_validate(raw)


def _is_final(parsed: AspectResponse | SummaryResponse) -> bool:
    """Return True if the parsed response is from the aggregation phase."""
    return isinstance(parsed, SummaryResponse)


def _all_aspects_received(row: Dict[str, Any]) -> bool:
    """Return True if all aspect judgments have been collected."""
    return len(row["sentiments"]) == N_ASPECTS


class ParallelChatAgent(Agent):
    """An agent that demonstrates parallel LLM invocations via fan-out of
    multiple ChatRequestEvent events.

    This agent receives a restaurant review and uses an LLM to judge sentiment
    along multiple dimensions in parallel, then aggregates the results into a
    one-line summary with a final LLM call. It handles prompt construction,
    parallel chat dispatch, response accumulation, and output assembly.
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
        """Process input event and send chat requests for each aspect."""
        row = _init_row(event)
        _save_row(ctx, row)
        for aspect in ASPECTS:
            ctx.send_event(_build_aspect_request(row["text"], aspect))

    @action(ChatResponseEvent.EVENT_TYPE)
    @staticmethod
    def handle_response(event: Event, ctx: RunnerContext) -> None:
        """Process chat response event and send output event."""
        parsed = _parse_response(event)
        row = _load_row(ctx)
        if _is_final(parsed):
            print(f"FINAL summary={parsed.summary} (t={time.monotonic():.3f})")
            ctx.send_event(_build_output_event(row, parsed))
            return
        print(f"ASPECT {parsed.aspect}={parsed.result} (t={time.monotonic():.3f})")
        row["sentiments"][parsed.aspect] = parsed.result
        _save_row(ctx, row)
        if _all_aspects_received(row):
            ctx.send_event(_build_summarize_request(row))
