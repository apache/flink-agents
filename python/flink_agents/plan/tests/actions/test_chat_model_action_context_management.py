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
"""Tests for context management through the built-in chat action.

They cover what the model is sent under a policy, that the stored conversation stays
complete, where the policy comes from, and what is reported. The scenarios and the
expected texts are the same as in the Java test
``ChatModelActionContextManagementTest``.
"""

import asyncio
import re
from collections import deque
from typing import Any, Deque, Dict, List, Sequence
from unittest.mock import ANY, MagicMock, PropertyMock
from uuid import UUID

import pytest
from pydantic import BaseModel, PrivateAttr

from flink_agents.api.agents.react_agent import OutputSchema
from flink_agents.api.chat_message import (
    ChatMessage,
    ImageBlock,
    MessageRole,
    TextBlock,
    ToolCallBlock,
    ToolResultBlock,
)
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.chat_models.routing import ModelRouter, Strategies
from flink_agents.api.chat_models.token_estimate import (
    TokenEstimate,
    approximate_token_estimate,
)
from flink_agents.api.chat_result import ChatResult, TokenUsage
from flink_agents.api.configuration import ConfigOption
from flink_agents.api.context_management import ContextManagementPolicy
from flink_agents.api.core_options import AgentExecutionOptions
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.context_management_event import ContextManagementEvent
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.resource import ResourceType
from flink_agents.api.tools.tool import Tool
from flink_agents.api.tools.tool_response import ToolResponse
from flink_agents.api.trace import (
    ExecutionEntityTypes,
    ExecutionProblemCategories,
    LLMExecutionMetadataKeys,
)
from flink_agents.plan.actions.chat_model_action import (
    process_chat_request_or_tool_response,
)
from flink_agents.plan.context_management import compaction, manager
from flink_agents.plan.exceptions import UncapturableException
from flink_agents.plan.resource.java.java_chat_model import (
    JavaChatModelConnection,
    JavaChatModelSetup,
)
from flink_agents.plan.tests.actions.test_chat_model_action_retry import (
    _create_mock_runner_context,
)

MODEL = "model"
TOOL_CALL_CONTEXT = "_TOOL_CALL_CONTEXT"


def approximate(messages: Sequence[ChatMessage]) -> int:
    return approximate_token_estimate(messages, []).tokens


def answer(text: str) -> ChatResult:
    return ChatResult(message=ChatMessage.assistant(text))


def call_tool(call_id: str, text: str | None = None) -> ChatResult:
    """A model reply that calls the lookup tool once.

    With ``text`` the reply says it beside the call.
    """
    call = ToolCallBlock(call_id=call_id, name="lookup", input={"q": call_id})
    return ChatResult(
        message=ChatMessage.assistant(
            [call] if text is None else [TextBlock(text=text), call]
        )
    )


class ScriptedModel(BaseChatModelSetup):
    """A chat model that records what it is sent.

    ``requests`` are the requests of the tool-call loop, ``summary_requests`` the
    tool-free requests a summarizer receives.
    """

    _requests: List[List[ChatMessage]] = PrivateAttr(default_factory=list)
    _summary_requests: List[List[ChatMessage]] = PrivateAttr(default_factory=list)
    _outcomes: Deque[Any] = PrivateAttr(default_factory=deque)
    _summaries: Deque[Any] = PrivateAttr(default_factory=deque)
    _usage_factor: int = PrivateAttr(default=0)
    _usage_offset: int = PrivateAttr(default=0)
    _fixed_usage: int | None = PrivateAttr(default=None)

    def __init__(self, *outcomes: Any, prompt: Prompt | None = None) -> None:
        super().__init__(connection="unused", model="provider-model", prompt=prompt)
        self._outcomes.extend(outcomes)

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        return {}

    @property
    def requests(self) -> List[List[ChatMessage]]:
        return self._requests

    @property
    def summary_requests(self) -> List[List[ChatMessage]]:
        return self._summary_requests

    def summaries(self, *scripted: Any) -> "ScriptedModel":
        """Script the summarizer.

        A str is written as the line of every round the request shows, unless it is
        blank or has lines of its own, which are returned as they are. A ChatResult
        or a ChatMessage is returned as it is, and an error is raised.
        """
        self._summaries.extend(scripted)
        return self

    def reporting_usage_times(self, factor: int) -> "ScriptedModel":
        """Report an input size of ``factor`` times the approximate estimate."""
        self._usage_factor = factor
        return self

    def reporting_usage_plus(self, tokens: int) -> "ScriptedModel":
        """Report an input size of the approximate estimate plus ``tokens``."""
        self._usage_offset = tokens
        return self

    def reporting_usage_of(self, prompt_tokens: int) -> "ScriptedModel":
        """Report the same input size for every call, whatever the request holds."""
        self._fixed_usage = prompt_tokens
        return self

    def chat_without_tools(self, messages: Sequence[ChatMessage]) -> ChatResult:
        self._summary_requests.append(list(messages))
        scripted = self._summaries.popleft() if self._summaries else "notes"
        if isinstance(scripted, Exception):
            raise scripted
        if isinstance(scripted, ChatResult | ChatMessage):
            return scripted
        return answer(lines_for(messages[1].text, scripted))

    def chat(
        self,
        messages: Sequence[ChatMessage],
        prompt_args: Any = None,
        *,
        tools: Any = None,
        output_schema: Any = None,
        **kwargs: Any,
    ) -> ChatResult:
        assert tools is None, "the tool-call loop prepares its request from the setup"
        self._requests.append(list(messages))
        outcome = self._outcomes.popleft()
        if isinstance(outcome, Exception):
            raise outcome
        if (
            self._usage_factor == 0
            and self._usage_offset == 0
            and self._fixed_usage is None
        ):
            return outcome
        prompt_tokens = (
            self._fixed_usage
            if self._fixed_usage is not None
            else max(self._usage_factor, 1) * approximate(messages) + self._usage_offset
        )
        return ChatResult(
            message=outcome.message,
            model="provider-model",
            usage=TokenUsage(prompt_tokens=prompt_tokens, completion_tokens=1),
            finish_reason=outcome.finish_reason,
        )


class ExactModel(ScriptedModel):
    """A chat model whose connection counts tokens itself."""

    def estimate_input_tokens(
        self, messages: Sequence[ChatMessage], prompt_args: Any = None
    ) -> TokenEstimate:
        return TokenEstimate.exact(approximate(messages))


class HalfScaleModel(ScriptedModel):
    """A chat model that counts half the tokens of the approximation.

    Its estimates are on that scale, and so is the input size it reports.
    """

    def estimate_input_tokens(
        self, messages: Sequence[ChatMessage], prompt_args: Any = None
    ) -> TokenEstimate:
        return TokenEstimate.approximate(approximate(messages) // 2)

    def chat(self, messages: Sequence[ChatMessage], *args: Any, **kwargs: Any) -> Any:
        outcome = super().chat(messages, *args, **kwargs)
        return ChatResult(
            message=outcome.message,
            model="provider-model",
            usage=TokenUsage(
                prompt_tokens=approximate(messages) // 2, completion_tokens=1
            ),
            finish_reason=outcome.finish_reason,
        )


class Context:
    """A runner context for one test: its models, configuration and sent events."""

    def __init__(
        self,
        router: ModelRouter | None = None,
        *,
        chat_async: bool = False,
        **models: Any,
    ) -> None:
        first = next(iter(models.values()))
        self.ctx, self.sent, self.metrics, self.memory = _create_mock_runner_context(
            first, max_retries=0, retry_wait_interval_sec=0, chat_async=chat_async
        )
        self.models = models
        self.options: Dict[str, Any] = {}
        self.durable_store: Dict[str, Any] = {}
        self.durable_call_ids: List[str] = []
        self.durable_async_call_ids: List[str] = []
        # What the durable calls returned when they were called, with their ids.
        self.durable_results: List[tuple[str, Any]] = []
        # Names of chat models that cannot be resolved.
        self.unresolvable: set[str] = set()
        configured = self.ctx.config.get.side_effect
        self.ctx.config.get = MagicMock(
            side_effect=lambda option: self.options.get(
                option.get_key(), configured(option)
            )
        )
        self.ctx.has_resource.side_effect = lambda name, kind: (
            router is not None
            and kind == ResourceType.MODEL_ROUTER
            and name == "router"
        )

        def get_resource(name: str, kind: ResourceType) -> Any:
            if kind == ResourceType.MODEL_ROUTER:
                return router
            if name in self.unresolvable:
                msg = f"resource not found: {name}"
                raise ValueError(msg)
            return self.models[name]

        self.ctx.get_resource.side_effect = get_resource

        def durable(fn: Any, *args: Any, durable_id: str, **kwargs: Any) -> Any:
            self.durable_call_ids.append(durable_id)
            if durable_id in self.durable_store:
                return self.durable_store[durable_id]
            result = fn(*args, **kwargs)
            self.durable_results.append((durable_id, result))
            return result

        async def durable_async(
            fn: Any, *args: Any, durable_id: str, **kwargs: Any
        ) -> Any:
            self.durable_async_call_ids.append(durable_id)
            return durable(fn, *args, durable_id=durable_id, **kwargs)

        self.ctx.durable_execute = MagicMock(side_effect=durable)
        self.ctx.durable_execute_async = MagicMock(side_effect=durable_async)

    def with_option(self, option: ConfigOption, value: Any) -> "Context":
        self.options[option.get_key()] = value
        return self

    def send(self, event: ChatRequestEvent) -> None:
        asyncio.run(process_chat_request_or_tool_response(event, self.ctx))

    def tool_result(self, call_id: str, text: str) -> None:
        """Answer the latest tool request with one result."""
        latest = [e for e in self.sent if isinstance(e, ToolRequestEvent)][-1]
        asyncio.run(
            process_chat_request_or_tool_response(
                ToolResponseEvent(
                    request_id=latest.id, responses={call_id: ToolResponse.text(text)}
                ),
                self.ctx,
            )
        )

    def events(self) -> List[ContextManagementEvent]:
        return [e for e in self.sent if isinstance(e, ContextManagementEvent)]

    def chat_response(self) -> ChatResponseEvent:
        responses = [e for e in self.sent if isinstance(e, ChatResponseEvent)]
        assert len(responses) == 1
        return responses[0]

    def has_chat_response(self) -> bool:
        return any(isinstance(e, ChatResponseEvent) for e in self.sent)

    def stored(self, request_id: UUID) -> List[ChatMessage]:
        stored = self.memory.get(TOOL_CALL_CONTEXT)[str(request_id)]
        return [ChatMessage.model_validate(message) for message in stored]

    def state(self, request_id: UUID) -> Dict[str, Any] | None:
        """What is kept for a request, at its own memory path; None when nothing."""
        return self.memory.get(manager.state_path(request_id)) or None

    def state_paths(self) -> List[str]:
        """The memory paths that hold context management state."""
        return [path for path in self.memory._store if manager.STATE_KEY in path]

    def estimates(self) -> List[manager.EstimateOutcome]:
        """The recorded estimates, in order."""
        return [
            manager.EstimateOutcome(*result)
            for durable_id, result in self.durable_results
            if durable_id == manager.ESTIMATE_CALL_ID
        ]


def request(policy: ContextManagementPolicy | None = None) -> ChatRequestEvent:
    return ChatRequestEvent(
        model=MODEL,
        messages=[ChatMessage.system("You are terse."), ChatMessage.user("go")],
        context_management=policy,
    )


def tool_text(message: ChatMessage) -> str:
    block = message.blocks[0]
    assert isinstance(block, ToolResultBlock)
    return block.text


def result(call_id: str, text: str) -> ChatMessage:
    """A tool message with the text result of one call."""
    return ChatMessage.tool(
        ToolResultBlock(call_id=call_id, blocks=[TextBlock(text=text)])
    )


def results(event: ContextManagementEvent) -> List[Dict[str, Any]]:
    return event.details["results"]


def lines_for(asked: str, text: str) -> str:
    """The answer of a scripted summarizer to a request.

    That is ``text`` as the line of every round the request shows, or ``text`` itself
    when it is blank or already has the line of a round.
    """
    if not text.strip() or "Round " in text:
        return text
    shown = re.findall(r"^Round (\d+):$", asked, re.MULTILINE)
    return "\n".join(f"Round {number}: {text}" for number in shown)


def notes(*texts: str) -> str:
    """The notes on rounds 1, 2 and so on: one line for each text."""
    return "\n".join(
        f"Round {number}: {text}" for number, text in enumerate(texts, start=1)
    )


def summary_block(rounds: int, *texts: str) -> str:
    """The text block a summary adds to the user message in front of its rounds.

    ``texts`` are the lines of rounds 1 to ``rounds``; one text stands for every
    round.
    """
    lines = texts if len(texts) == rounds else texts * rounds
    assert len(lines) == rounds
    return compaction.SUMMARY_SEPARATOR + compaction.summary_text(notes(*lines))


# ── Off by default ──────────────────────────────────────────────────────


def test_without_a_policy_the_model_is_sent_the_whole_conversation() -> None:
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final"))
    ctx = Context(model=model)

    ctx.send(request())
    ctx.tool_result("c1", "1" * 5000)
    ctx.tool_result("c2", "2" * 5000)

    assert ctx.chat_response().response.text == "final"
    assert len(model.requests) == 3
    assert len(model.requests[2]) == 6
    assert tool_text(model.requests[2][3]) == "1" * 5000
    assert tool_text(model.requests[2][5]) == "2" * 5000
    # Nothing is reported and nothing is kept.
    assert ctx.events() == []
    assert ctx.state_paths() == []
    assert ctx.durable_call_ids == ["chat", "chat", "chat"]


def test_a_request_without_a_round_is_sent_as_it_is_whatever_the_policy() -> None:
    model = ScriptedModel(answer("first"), call_tool("c1"))
    # A limit below the minimum: reading this policy fails.
    ctx = Context(model=model).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 5
    )

    # Only tool-call rounds are changed, so a request that has none is not looked at.
    chat_request = request()
    ctx.send(chat_request)
    assert ctx.chat_response().response.text == "first"
    assert model.requests[0] == chat_request.messages
    assert ctx.state_paths() == []

    # The policy is read once a request has a round. A job is never in this state:
    # its options are checked when it is submitted.
    ctx.send(request())
    with pytest.raises(ValueError, match="context-management.tool-results.max-chars"):
        ctx.tool_result("c1", "result")


# ── Truncation ──────────────────────────────────────────────────────────


def test_an_oversized_result_is_truncated_stored_in_full_and_reported_once() -> None:
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final"))
    ctx = Context(model=model).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 100
    )
    chat_request = request()

    ctx.send(chat_request)
    ctx.tool_result("c1", "a" * 1000)

    # The second model call sees the result cut to the limit.
    sent = tool_text(model.requests[1][3])
    assert "[935 characters truncated]" in sent
    assert len(sent) <= 100
    # The stored conversation keeps the whole result.
    assert tool_text(ctx.stored(chat_request.id)[3]) == "a" * 1000

    after_first_round = ctx.events()
    assert len(after_first_round) == 1
    event = after_first_round[0]
    assert event.request_id == chat_request.id
    assert event.model == MODEL
    assert event.step == ContextManagementEvent.STEP_TRUNCATE
    assert event.details["max_chars"] == 100
    assert results(event) == [
        {
            "round": 1,
            "call_id": "c1",
            "tool": "lookup",
            "original_chars": 1000,
            "sent_chars": len(sent),
        }
    ]

    ctx.tool_result("c2", "short")

    # The result is still sent truncated, and is not reported again.
    assert tool_text(model.requests[2][3]) == sent
    assert tool_text(model.requests[2][5]) == "short"
    assert len(ctx.events()) == 1
    assert ctx.chat_response().response.text == "final"


def test_rounds_that_arrive_with_the_request_are_stored_in_full() -> None:
    model = ScriptedModel(call_tool("c1"), answer("final"))
    ctx = Context(model=model).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 100
    )
    history = [
        ChatMessage.user("go"),
        call_tool("h1").message,
        ChatMessage.tool(
            ToolResultBlock(call_id="h1", blocks=[TextBlock(text="a" * 1000)])
        ),
    ]
    chat_request = ChatRequestEvent(model=MODEL, messages=history)

    ctx.send(chat_request)

    # The first model call is sent the round cut to the limit, and the conversation
    # that is stored for the later calls holds the round as it arrived.
    assert "[935 characters truncated]" in tool_text(model.requests[0][2])
    assert tool_text(ctx.stored(chat_request.id)[2]) == "a" * 1000

    ctx.tool_result("c1", "short")
    assert "[935 characters truncated]" in tool_text(model.requests[1][2])
    assert ctx.chat_response().response.text == "final"


# ── Masking ─────────────────────────────────────────────────────────────


def test_older_results_are_masked_and_each_round_is_reported_once() -> None:
    model = ScriptedModel(
        call_tool("c1"), call_tool("c2"), call_tool("c3"), answer("final")
    )
    ctx = Context(model=model).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS, 1
    )
    chat_request = request()

    ctx.send(chat_request)
    ctx.tool_result("c1", "1" * 300)
    # One round: nothing to mask yet.
    assert tool_text(model.requests[1][3]) == "1" * 300
    assert ctx.events() == []

    ctx.tool_result("c2", "2" * 300)
    # Two rounds: the first result is a placeholder, the latest is sent as it is.
    third = model.requests[2]
    assert len(third) == 6
    assert tool_text(third[3]) == (
        '[Tool result omitted by context management: lookup({"q":"c1"})'
        " returned 300 characters]"
    )
    assert third[2].tool_calls[0].call_id == "c1"
    assert tool_text(third[5]) == "2" * 300
    assert len(ctx.events()) == 1
    first = ctx.events()[0]
    assert first.step == ContextManagementEvent.STEP_MASK
    assert first.details["keep_last_rounds"] == 1
    assert first.details["step_rounds"] == 1
    assert first.details["rounds"] == 1
    assert results(first) == [
        {"round": 1, "call_id": "c1", "tool": "lookup", "omitted_chars": 300}
    ]
    # The stored conversation keeps both results.
    assert tool_text(ctx.stored(chat_request.id)[3]) == "1" * 300

    ctx.tool_result("c3", "3" * 300)
    fourth = model.requests[3]
    assert tool_text(fourth[3]).startswith("[Tool result omitted")
    assert tool_text(fourth[5]).startswith("[Tool result omitted")
    assert tool_text(fourth[7]) == "3" * 300
    # Only the round that is newly masked is reported.
    assert len(ctx.events()) == 2
    assert results(ctx.events()[1]) == [
        {"round": 2, "call_id": "c2", "tool": "lookup", "omitted_chars": 300}
    ]

    assert ctx.chat_response().response.text == "final"
    # Masking adds no durable call.
    assert ctx.durable_call_ids == ["chat", "chat", "chat", "chat"]


def test_masking_in_steps_waits_until_the_step_is_full() -> None:
    model = ScriptedModel(
        call_tool("c1"), call_tool("c2"), call_tool("c3"), answer("final")
    )
    ctx = (
        Context(model=model)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS, 1
        )
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MASK_STEP_ROUNDS, 2
        )
    )

    ctx.send(request())
    ctx.tool_result("c1", "1" * 300)
    ctx.tool_result("c2", "2" * 300)
    # Two rounds, one eligible: the step of two is not full, nothing is masked.
    assert tool_text(model.requests[2][3]) == "1" * 300
    assert ctx.events() == []

    ctx.tool_result("c3", "3" * 300)
    # Three rounds, two eligible: both are masked in one step.
    fourth = model.requests[3]
    assert tool_text(fourth[3]).startswith("[Tool result omitted")
    assert tool_text(fourth[5]).startswith("[Tool result omitted")
    assert tool_text(fourth[7]) == "3" * 300
    assert len(ctx.events()) == 1
    assert ctx.events()[0].details["step_rounds"] == 2
    assert ctx.events()[0].details["rounds"] == 2
    # Each result tells its round, counted from 1: a provider can give the calls of
    # two rounds the same id.
    assert [
        (result["round"], result["call_id"]) for result in results(ctx.events()[0])
    ] == [(1, "c1"), (2, "c2")]


def test_rounds_that_arrive_with_the_request_are_masked_on_the_first_call() -> None:
    model = ScriptedModel(answer("final"))
    ctx = Context(model=model).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS, 1
    )
    history = [
        ChatMessage.user("go"),
        call_tool("h1").message,
        ChatMessage.tool(
            ToolResultBlock(call_id="h1", blocks=[TextBlock(text="1" * 300)])
        ),
        call_tool("h2").message,
        ChatMessage.tool(
            ToolResultBlock(call_id="h2", blocks=[TextBlock(text="2" * 300)])
        ),
    ]

    ctx.send(ChatRequestEvent(model=MODEL, messages=history))

    assert tool_text(model.requests[0][2]).startswith("[Tool result omitted")
    assert tool_text(model.requests[0][4]) == "2" * 300
    assert len(ctx.events()) == 1
    assert [result["call_id"] for result in results(ctx.events()[0])] == ["h1"]


# ── Where the policy comes from ─────────────────────────────────────────


def test_a_policy_on_the_request_replaces_the_agents_default_for_every_round() -> None:
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final"))
    # The agent masks all but the last round; this request switches that off.
    ctx = Context(model=model).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS, 1
    )
    chat_request = request(ContextManagementPolicy.disabled())

    ctx.send(chat_request)
    ctx.tool_result("c1", "1" * 300)
    # The request's policy is kept for its tool rounds.
    assert ctx.state(chat_request.id) == {"policy": {}}
    ctx.tool_result("c2", "2" * 300)

    assert tool_text(model.requests[2][3]) == "1" * 300
    assert ctx.events() == []
    assert ctx.chat_response().response.text == "final"
    # What was kept for the request is removed when it completes.
    assert ctx.state(chat_request.id) is None


def test_a_policy_on_the_request_applies_without_any_configuration() -> None:
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final"))
    ctx = Context(model=model)
    chat_request = request(ContextManagementPolicy(tool_result_max_chars=100))

    ctx.send(chat_request)
    ctx.tool_result("c1", "a" * 1000)
    ctx.tool_result("c2", "b" * 1000)

    # Both tool rounds use the request's policy.
    assert "characters truncated" in tool_text(model.requests[1][3])
    assert "characters truncated" in tool_text(model.requests[2][5])
    assert len(ctx.events()) == 2
    assert ctx.state(chat_request.id) is None


def test_a_request_without_a_policy_uses_the_default_and_another_can_differ() -> None:
    model = ScriptedModel(
        call_tool("a1"), answer("first done"), call_tool("b1"), answer("second done")
    )
    ctx = Context(model=model).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 100
    )

    # First request: no policy of its own, the configured limit of 100 applies.
    ctx.send(request())
    ctx.tool_result("a1", "a" * 1000)
    assert len(tool_text(model.requests[1][3])) <= 100

    # Second request: its own limit of 500 applies instead.
    ctx.send(request(ContextManagementPolicy(tool_result_max_chars=500)))
    ctx.tool_result("b1", "b" * 1000)
    assert 100 < len(tool_text(model.requests[3][3])) <= 500


# ── Summarization ───────────────────────────────────────────────────────


def summarizing(**models: Any) -> Context:
    """Budget of 1000 tokens, summarize above 800, keep the last round."""
    return (
        Context(**models)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 1000)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )


def test_older_rounds_are_replaced_by_a_summary_of_the_stored_rounds() -> None:
    model = ScriptedModel(
        call_tool("c1"), call_tool("c2"), call_tool("c3"), answer("final")
    ).summaries("Account c1 holds 10.", "Account c2 holds 20.")
    ctx = summarizing(model=model)
    chat_request = request()

    ctx.send(chat_request)
    # One round of about 500 tokens: under the trigger, and the last round is always
    # kept.
    ctx.tool_result("c1", "1" * 2000)
    assert model.summary_requests == []
    assert len(model.requests[1]) == 4

    # Two rounds, about 1040 tokens: round 1 is folded into a summary before the
    # model call.
    ctx.tool_result("c2", "2" * 2000)
    assert len(model.summary_requests) == 1
    summary_request = model.summary_requests[0]
    assert len(summary_request) == 2
    assert summary_request[0].role == MessageRole.SYSTEM
    assert summary_request[0].text == manager.SUMMARIZER_INSTRUCTIONS
    asked = summary_request[1].text
    # The summarizer reads the request and the stored round, in full.
    assert asked == (
        "The agent's instructions, for context only:\nYou are terse.\n\n"
        "The request the agent is working on, for context only:\ngo\n\nRounds to write notes for:\n"
        'Round 1:\nlookup({"q":"c1"}) returned: '
        + "1"
        * 2000
        + "\n\nWrite one line for each round above, at most 30 words each. "
        "Use only what the rounds above contain."
    )

    # The model is sent the summary in place of round 1, and round 2 as it is.
    third = model.requests[2]
    assert len(third) == 4
    assert third[0].text == "You are terse."
    assert third[1].role == MessageRole.USER
    assert len(third[1].blocks) == 2
    assert third[1].blocks[0].text == "go"
    assert third[1].blocks[1].text == summary_block(1, "Account c1 holds 10.")
    assert third[2].tool_calls[0].call_id == "c2"
    assert tool_text(third[3]) == "2" * 2000
    # The stored conversation still has both rounds, and by now the third call.
    stored = ctx.stored(chat_request.id)
    assert len(stored) == 7
    two_rounds = stored[:6]
    assert tool_text(two_rounds[3]) == "1" * 2000

    assert len(ctx.events()) == 1
    event = ctx.events()[0]
    assert event.step == ContextManagementEvent.STEP_SUMMARIZE
    assert event.model == MODEL
    assert event.details == {
        "summarizer": MODEL,
        "rounds": 1,
        "summarized_rounds": 1,
        "max_input_tokens": 1000,
        "estimated_input_tokens_before": approximate(two_rounds),
        "estimate_approximate": True,
        "summary_chars": len("Round 1: Account c1 holds 10."),
        "estimated_input_tokens_after": approximate(third),
    }
    assert event.details["estimated_input_tokens_before"] == 1037
    assert event.details["estimated_input_tokens_before"] > 800

    # Three rounds: the summary and two rounds are over the trigger again, so the
    # line of round 2 is added to the notes. The summarizer is shown round 2 alone:
    # the line of round 1 stays as it was written.
    ctx.tool_result("c3", "3" * 2000)
    assert len(model.summary_requests) == 2
    asked_again = model.summary_requests[1][1].text
    assert "Account c1 holds 10." not in asked_again
    assert "Round 2:\n" in asked_again
    assert "2" * 2000 in asked_again
    assert "Round 1:" not in asked_again
    assert "Round 3:" not in asked_again
    fourth = model.requests[3]
    assert len(fourth) == 4
    assert fourth[1].blocks[1].text == summary_block(
        2, "Account c1 holds 10.", "Account c2 holds 20."
    )
    assert fourth[2].tool_calls[0].call_id == "c3"
    assert len(ctx.events()) == 2
    assert ctx.events()[1].details["rounds"] == 1
    assert ctx.events()[1].details["summarized_rounds"] == 2

    assert ctx.chat_response().response.text == "final"
    # The input is estimated for a call that has a round due for its line, which the
    # first two calls do not. The estimate is a durable call, and a summary is one of
    # its own, made before the round's model call.
    assert ctx.durable_call_ids == [
        "chat",
        "chat",
        "estimate",
        "summarize",
        "chat",
        "estimate",
        "summarize",
        "chat",
    ]
    assert ctx.state(chat_request.id) is None


def test_the_summarizer_is_told_every_user_message_of_the_request() -> None:
    model = ScriptedModel(call_tool("c1"), answer("final")).summaries(
        "notes on the earlier turn"
    )
    ctx = summarizing(model=model)
    # A conversation that already has a turn with a tool round, and a new question
    # after it.
    history = [
        ChatMessage.user("first question"),
        call_tool("h1").message,
        ChatMessage.tool(
            ToolResultBlock(call_id="h1", blocks=[TextBlock(text="1" * 2000)])
        ),
        ChatMessage.assistant("first answer"),
        ChatMessage.user("second question"),
    ]

    ctx.send(ChatRequestEvent(model=MODEL, messages=history))
    # One round so far, and it is the kept one.
    assert model.summary_requests == []
    ctx.tool_result("c1", "2" * 2000)

    # The notes have to serve the latest question, so the summarizer is told both.
    assert len(model.summary_requests) == 1
    assert model.summary_requests[0][1].text.startswith(
        "The request the agent is working on, for context only:\nfirst question\nsecond question\n\n"
        "Rounds to write notes for:\nRound 1:\n"
    )
    # The summary takes the place of the earlier round, in front of the turns that
    # followed.
    sent = model.requests[1]
    assert [message.role for message in sent] == [
        MessageRole.USER,
        MessageRole.ASSISTANT,
        MessageRole.USER,
        MessageRole.ASSISTANT,
        MessageRole.TOOL,
    ]
    assert sent[0].text == "first question" + summary_block(
        1, "notes on the earlier turn"
    )
    assert sent[2].text == "second question"


def test_the_summarizer_is_shown_the_instructions_of_the_bound_prompt() -> None:
    # The instructions of this chat model are in its prompt, not in the request's
    # messages.
    model = ScriptedModel(
        call_tool("c1"),
        call_tool("c2"),
        answer("final"),
        prompt=Prompt.from_messages(
            [
                ChatMessage.system(
                    "Audit the accounts of {region}. Reply with the total."
                )
            ]
        ),
    ).summaries("notes")
    ctx = summarizing(model=model)

    ctx.send(
        ChatRequestEvent(
            model=MODEL,
            messages=[ChatMessage.user("go")],
            prompt_args={"region": "EMEA"},
        )
    )
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    # Without them a summarizer cannot tell what the request still needs.
    assert len(model.summary_requests) == 1
    assert model.summary_requests[0][1].text.startswith(
        "The agent's instructions, for context only:\n"
        "Audit the accounts of EMEA. Reply with the total.\n\n"
        "The request the agent is working on, for context only:\ngo\n\n"
        "Rounds to write notes for:\nRound 1:\n"
    )


def test_long_instructions_in_any_script_leave_room_for_the_rounds() -> None:
    # 2000 characters of instructions that are a token each, as many tokens as the
    # whole budget.
    model = ScriptedModel(
        call_tool("c1"),
        call_tool("c2"),
        answer("final"),
        prompt=Prompt.from_messages([ChatMessage.system("帐" * 2000)]),
    ).summaries("notes on round 1")
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 2000)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO, 0.1
        )
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )

    ctx.send(ChatRequestEvent(model=MODEL, messages=[ChatMessage.user("go")]))
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    # The summarizer is shown an eighth of the budget of them, 250 tokens, counted
    # in tokens and not in characters, and the round fits beside them.
    assert len(model.summary_requests) == 1
    asked = model.summary_requests[0][1].text
    assert asked.count("帐") == 241
    assert "[1759 characters truncated]" in asked
    assert "1" * 2000 in asked
    assert approximate(model.summary_requests[0]) <= 2000
    assert "error" not in ctx.events()[0].details


def test_the_configured_summarizer_writes_the_summary() -> None:
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final"))
    summarizer = ScriptedModel().summaries("notes from the small model")
    ctx = summarizing(model=model, small=summarizer).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL, "small"
    )

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    assert len(summarizer.summary_requests) == 1
    assert model.summary_requests == []
    assert model.requests[2][1].blocks[1].text == summary_block(
        1, "notes from the small model"
    )
    assert ctx.events()[0].details["summarizer"] == "small"


def test_a_summary_that_cannot_be_written_is_skipped_reported_and_tried_again() -> None:
    model = ScriptedModel(
        call_tool("c1"), call_tool("c2"), call_tool("c3"), answer("final")
    ).summaries(
        RuntimeError("summarizer is down"),
        "notes on round 1",
        "notes on round 2",
    )
    ctx = summarizing(model=model)

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    # The request goes on with the whole conversation.
    assert len(model.summary_requests) == 1
    assert len(model.requests[2]) == 6
    assert not ctx.has_chat_response()
    assert len(ctx.events()) == 1
    failed = ctx.events()[0].details
    assert failed["rounds"] == 0
    assert failed["summarized_rounds"] == 0
    assert "summary_chars" not in failed
    assert "estimated_input_tokens_after" not in failed
    assert "summarizer is down" in failed["error"]

    # The next round asks again, for both rounds that are now due. Together they do
    # not fit the budget of one summarizer request, so they are folded one after the
    # other.
    ctx.tool_result("c3", "3" * 2000)
    assert len(model.summary_requests) == 3
    second = model.summary_requests[1][1].text
    assert "Round 1:\n" in second
    assert "Round 2:" not in second
    third = model.summary_requests[2][1].text
    assert "notes on round 1" not in third
    assert "Round 2:\n" in third
    assert "Round 1:" not in third
    assert "Round 3:" not in third
    assert len(model.requests[3]) == 4
    assert model.requests[3][1].blocks[1].text == summary_block(
        2, "notes on round 1", "notes on round 2"
    )
    assert ctx.events()[1].details["rounds"] == 2
    assert ctx.events()[1].details["summarized_rounds"] == 2
    assert "error" not in ctx.events()[1].details
    assert ctx.chat_response().response.text == "final"


def test_a_summary_that_keeps_failing_is_tried_again_after_one_two_four_and_then_eight_rounds() -> (
    None
):
    replies = [call_tool(f"c{number}") for number in range(1, 26)]
    model = ScriptedModel(*replies, answer("final")).summaries(
        *[RuntimeError("summarizer is down")] * 5
    )
    ctx = summarizing(model=model)
    # The request alone takes about 900 of the 1000 tokens of the budget, so every
    # call with a round that is due is over the trigger.
    chat_request = ChatRequestEvent(
        model=MODEL, messages=[ChatMessage.user("u" * 3600)]
    )

    ctx.send(chat_request)
    # The step is tried with 2 rounds, and after each failure the request waits
    # longer: 1 round, then 2, 4 and 8, and no longer than 8.
    tried = [2, 3, 5, 9, 17]
    failures = 0
    for rounds in range(1, 25):
        ctx.tool_result(f"c{rounds}", "r")
        if rounds in tried:
            failures += 1
        assert len(model.summary_requests) == failures, f"with {rounds} rounds"
        # While the request waits, its input is not estimated either.
        assert len(ctx.estimates()) == failures, f"with {rounds} rounds"
    state = ctx.state(chat_request.id)
    assert state["failed_steps"] == 5
    assert state["retry_at_rounds"] == 25
    assert len(ctx.events()) == 5
    assert all("summarizer is down" in event.details["error"] for event in ctx.events())

    # With 25 rounds the summarizer answers, which ends the wait.
    ctx.tool_result("c25", "r")
    assert len(model.summary_requests) > 5
    assert len(ctx.events()) == 6
    assert ctx.events()[5].details["rounds"] == 24
    assert "error" not in ctx.events()[5].details
    assert ctx.chat_response().response.text == "final"


def test_a_summary_that_is_written_ends_the_wait() -> None:
    down = RuntimeError("summarizer is down")
    model = ScriptedModel(
        *[call_tool(f"c{number}") for number in range(1, 8)]
    ).summaries(down, down, "ok", down)
    ctx = summarizing(model=model)
    # The request alone takes about 900 of the 1000 tokens of the budget, so every
    # call with a round that is due is over the trigger.
    chat_request = ChatRequestEvent(
        model=MODEL, messages=[ChatMessage.user("u" * 3600)]
    )

    ctx.send(chat_request)
    ctx.tool_result("c1", "r")
    ctx.tool_result("c2", "r")
    ctx.tool_result("c3", "r")
    # Two failures in a row: the request waits two rounds.
    state = ctx.state(chat_request.id)
    assert state["failed_steps"] == 2
    assert state["retry_at_rounds"] == 5
    ctx.tool_result("c4", "r")
    assert len(model.summary_requests) == 2

    # With 5 rounds the lines of rounds 1 to 4 are written, which ends the wait.
    ctx.tool_result("c5", "r")
    assert len(model.summary_requests) == 3
    state = ctx.state(chat_request.id)
    assert state["written_rounds"] == 4
    assert "failed_steps" not in state
    assert "retry_at_rounds" not in state

    # So the next failure is a first one again, and the request waits one round.
    ctx.tool_result("c6", "r")
    assert len(model.summary_requests) == 4
    state = ctx.state(chat_request.id)
    assert state["failed_steps"] == 1
    assert state["retry_at_rounds"] == 7


class SilentAboutRoundOne(ScriptedModel):
    """A summarizer that writes no line for round 1, whatever else it is shown."""

    def chat_without_tools(self, messages: Sequence[ChatMessage]) -> ChatResult:
        lines = super().chat_without_tools(messages)
        if "Round 1:\n" in messages[1].text:
            return answer("I cannot write about this.")
        return lines


def test_a_round_whose_answer_cannot_be_used_in_three_steps_in_a_row_gets_no_line() -> (
    None
):
    model = SilentAboutRoundOne(
        *[call_tool(f"c{number}") for number in range(1, 10)], answer("final")
    )
    ctx = summarizing(model=model)
    # The request alone takes about 900 of the 1000 tokens of the budget, so every
    # call with a round that is due is over the trigger.
    chat_request = ChatRequestEvent(
        model=MODEL, messages=[ChatMessage.user("u" * 3600)]
    )

    ctx.send(chat_request)
    # The steps with 2 and 3 rounds stop at round 1.
    for rounds in range(1, 5):
        ctx.tool_result(f"c{rounds}", "r" * 600)
    assert len(ctx.events()) == 2
    for event in ctx.events():
        assert event.details["error"] == "the summarizer wrote no line for round 1"
        assert "passed_over_round" not in event.details
    state = ctx.state(chat_request.id)
    assert state["unusable_answers"] == 2
    assert "written_rounds" not in state

    # The step with 5 rounds is the third in a row that stops there. Round 1 gets no
    # line from now on and stays in the messages.
    ctx.tool_result("c5", "r" * 600)
    assert len(ctx.events()) == 3
    details = ctx.events()[2].details
    assert details["error"] == "the summarizer wrote no line for round 1"
    assert details["passed_over_round"] == 1
    state = ctx.state(chat_request.id)
    assert state["written_rounds"] == 1
    assert "unusable_answers" not in state
    # What held the steps up is behind, so the request does not wait as it does after
    # a third failure. The count of failed steps in a row stays until a summary is
    # written.
    assert state["failed_steps"] == 3
    assert "retry_at_rounds" not in state

    # The next model call summarizes the rounds after it: rounds 2 to 5 with 6
    # rounds, and one more with each round after that.
    ctx.tool_result("c6", "r" * 600)
    assert "failed_steps" not in ctx.state(chat_request.id)
    assert len(ctx.events()) == 4
    details = ctx.events()[3].details
    assert details["rounds"] == 4
    assert details["summarized_rounds"] == 4
    assert "error" not in details
    assert "passed_over_round" not in details
    for rounds in range(7, 10):
        ctx.tool_result(f"c{rounds}", "r" * 600)
    assert len(ctx.events()) == 7
    assert ctx.events()[6].details["summarized_rounds"] == 7
    last = model.requests[9]
    assert len(last) == 5
    assert last[1].tool_calls[0].call_id == "c1"
    assert tool_text(last[2]) == "r" * 600
    assert last[3].tool_calls[0].call_id == "c9"
    assert ctx.chat_response().response.text == "final"


def test_the_count_of_unusable_answers_starts_again_at_another_round() -> None:
    other = "Round 9: a line for a round that was not shown"
    # The steps with 2 and 3 rounds get no line for round 1. The step with 5 rounds
    # gets it, and then no line for round 2.
    model = ScriptedModel(
        *[call_tool(f"c{number}") for number in range(1, 7)]
    ).summaries(
        other,
        other,
        other,
        "Round 1: notes on round 1",
        "Round 1: notes on round 1",
        other,
    )
    ctx = summarizing(model=model)
    chat_request = ChatRequestEvent(
        model=MODEL, messages=[ChatMessage.user("u" * 3600)]
    )

    ctx.send(chat_request)
    for rounds in range(1, 5):
        ctx.tool_result(f"c{rounds}", "r" * 600)
    assert ctx.state(chat_request.id)["unusable_answers"] == 2
    ctx.tool_result("c5", "r" * 600)

    # The third step in a row stops with an answer that cannot be used, but at round
    # 2: it is the first such answer for that round, which is not given up.
    assert len(ctx.events()) == 3
    details = ctx.events()[2].details
    assert details["error"] == "the summarizer wrote no line for round 2"
    assert details["rounds"] == 1
    assert "passed_over_round" not in details
    state = ctx.state(chat_request.id)
    assert state["unusable_answers"] == 1
    assert state["written_rounds"] == 1


class Silent(ScriptedModel):
    """A summarizer that writes no line at all."""

    def chat_without_tools(self, messages: Sequence[ChatMessage]) -> ChatResult:
        super().chat_without_tools(messages)
        return answer("I cannot write about this.")


class WrongShapeSummarizer(ScriptedModel):
    """A summarizer whose answers are never the lines of rounds."""

    def chat_without_tools(self, messages: Sequence[ChatMessage]) -> ChatResult:
        self.summary_requests.append(list(messages))
        return answer("Here is what happened: the tool returned some numbers.")


def test_a_summarizer_whose_answers_can_never_be_used_is_not_called_for_every_round() -> (
    None
):
    model = WrongShapeSummarizer(*[call_tool(f"c{number}") for number in range(1, 32)])
    ctx = Context(model=model).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 4000
    )

    ctx.send(request())
    asked_before_call = []
    for number in range(1, 31):
        asked = len(model.summary_requests)
        ctx.tool_result(f"c{number}", str(number % 10) * 2000)
        if len(model.summary_requests) > asked:
            asked_before_call.append(number + 1)

    # The steps before calls 8, 9 and 11 stop at round 1, which is passed over with
    # the third. The round after it is tried with the next call, and the count of
    # failed steps goes on: after the fourth failure the request waits eight rounds,
    # as it does for a summarizer that keeps failing. Round 2 is passed over before
    # call 28.
    assert asked_before_call == [8, 9, 11, 12, 20, 28, 29]
    passed_over = [
        event.details["passed_over_round"]
        for event in ctx.events()
        if "passed_over_round" in event.details
    ]
    assert passed_over == [1, 2]


def test_the_count_of_unusable_answers_goes_with_a_round_that_is_passed_over() -> None:
    model = Silent(*[call_tool(f"c{number}") for number in range(1, 7)])
    # The last three rounds keep their results, and a summary would leave the last
    # round.
    ctx = summarizing(model=model).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS, 3
    )
    chat_request = ChatRequestEvent(
        model=MODEL, messages=[ChatMessage.user("u" * 3600)]
    )

    ctx.send(chat_request)
    # The steps with 2 and 3 rounds stop at round 1.
    for rounds in range(1, 4):
        ctx.tool_result(f"c{rounds}", "r" * 600)
    assert ctx.state(chat_request.id)["unusable_answers"] == 2

    # With 4 rounds, round 1 is past the masking boundary and gets no line any more.
    # The two answers were about it, so their count goes with it.
    ctx.tool_result("c4", "r" * 600)
    state = ctx.state(chat_request.id)
    assert state["written_rounds"] == 1
    assert "unusable_answers" not in state

    # With 5 rounds the next step is tried, and round 2 is masked too. It stops at
    # round 3 with the first answer that cannot be used for that round, which is not
    # given up.
    ctx.tool_result("c5", "r" * 600)
    assert len(ctx.events()) > 2
    details = [e.details for e in ctx.events() if e.step == "summarize"][-1]
    assert details["error"] == "the summarizer wrote no line for round 3"
    assert "passed_over_round" not in details
    state = ctx.state(chat_request.id)
    assert state["unusable_answers"] == 1
    assert state["written_rounds"] == 2


class DownOnTheSecondCall(ScriptedModel):
    """A summarizer that writes no line, and that fails its second call."""

    _asked: int = PrivateAttr(default=0)

    def chat_without_tools(self, messages: Sequence[ChatMessage]) -> ChatResult:
        self._asked += 1
        if self._asked == 2:
            msg = "summarizer is down"
            raise RuntimeError(msg)
        super().chat_without_tools(messages)
        return answer("I cannot write about this.")


def test_a_step_that_fails_for_another_reason_starts_the_count_again() -> None:
    model = DownOnTheSecondCall(*[call_tool(f"c{number}") for number in range(1, 11)])
    ctx = summarizing(model=model)
    chat_request = ChatRequestEvent(
        model=MODEL, messages=[ChatMessage.user("u" * 3600)]
    )

    ctx.send(chat_request)
    # Steps are tried with 2, 3, 5 and 9 rounds. The second fails because the
    # summarizer cannot be reached, which says nothing about round 1.
    for rounds in range(1, 10):
        ctx.tool_result(f"c{rounds}", "r" * 600)

    assert len(ctx.events()) == 4
    assert "summarizer is down" in ctx.events()[1].details["error"]
    # So the answers of the steps with 5 and 9 rounds are the first and the second in
    # a row, and round 1 is not given up.
    for event in ctx.events():
        assert "passed_over_round" not in event.details
    state = ctx.state(chat_request.id)
    assert state["unusable_answers"] == 2
    assert state["failed_steps"] == 4
    assert "written_rounds" not in state


@pytest.mark.parametrize(
    ("answered", "error"),
    [
        ("", "the chat model returned no text"),
        ("   ", "the chat model returned no text"),
        (" \t\n\n \r\n", "the chat model returned no text"),
        # White space is the same list of characters in Java and in Python: an em
        # space is white space, and a control character is text, read as a line.
        ("\u2003 \u00a0", "the chat model returned no text"),
        ("\x1f", "the summarizer wrote no line for round 1"),
    ],
)
def test_an_empty_summary_is_not_used(answered: str, error: str) -> None:
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), call_tool("c3")).summaries(
        answer(answered)
    )
    ctx = summarizing(model=model)
    chat_request = request()

    ctx.send(chat_request)
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    assert len(model.requests[2]) == 6
    assert ctx.events()[0].details["error"] == error
    # An answer without text is an answer that cannot be used for the round.
    assert ctx.state(chat_request.id)["unusable_answers"] == 1


def test_a_chat_model_declared_in_java_cannot_write_the_summary() -> None:
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final"))
    ctx = summarizing(model=model, java=MagicMock(spec=JavaChatModelSetup)).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL, "java"
    )

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    assert len(model.requests[2]) == 6
    error = ctx.events()[0].details["error"]
    assert "'java' is declared in Java" in error
    assert "context-management.summarization.model" in error
    assert ctx.chat_response().response.text == "final"
    # The refusal is the outcome of the summary's durable call.
    assert ctx.durable_call_ids == ["chat", "chat", "estimate", "summarize", "chat"]


def test_a_resource_that_is_no_chat_model_setup_cannot_write_the_summary() -> None:
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final"))
    # A connection that is declared as a chat model, by mistake.
    ctx = summarizing(
        model=model, small=MagicMock(spec=BaseChatModelConnection)
    ).with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL, "small")

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    # The summary is skipped and the request goes on.
    assert len(model.requests[2]) == 6
    error = ctx.events()[0].details["error"]
    assert error.startswith("chat model 'small' cannot be used: it is a ")
    assert error.endswith(", not a chat model setup")
    assert ctx.chat_response().response.text == "final"
    assert ctx.durable_call_ids == ["chat", "chat", "estimate", "summarize", "chat"]


def test_a_summarizer_that_cannot_be_resolved_still_takes_the_durable_call() -> None:
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final"))
    ctx = summarizing(model=model).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL, "small"
    )
    ctx.unresolvable.add("small")

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    assert len(model.requests[2]) == 6
    details = ctx.events()[0].details
    assert details["error"] == (
        "chat model 'small' cannot be used: ValueError: resource not found: small"
    )
    assert details["rounds"] == 0
    # The durable call records why there is no summary. A replay then makes the same
    # calls in the same order, whether or not the summarizer can be resolved by then.
    assert ctx.durable_call_ids == ["chat", "chat", "estimate", "summarize", "chat"]
    # No model was called for the summary, so no LLM call is reported for it.
    assert all(
        call.args[2] != "small"
        for call in ctx.ctx.report_execution_started.call_args_list
    )
    ctx.ctx.report_execution_failed.assert_not_called()
    assert ctx.chat_response().response.text == "final"


def test_nothing_is_summarized_while_only_the_kept_rounds_are_left() -> None:
    model = ScriptedModel(call_tool("c1"), answer("final"))
    # One huge round is far over the budget, but it is the last round and is kept.
    ctx = summarizing(model=model)

    ctx.send(request())
    ctx.tool_result("c1", "1" * 20_000)

    assert model.summary_requests == []
    assert len(model.requests[1]) == 4
    assert ctx.events() == []


def test_rounds_that_fit_one_summarizer_request_are_folded_in_one_call() -> None:
    model = ScriptedModel(
        call_tool("c1"), call_tool("c2"), call_tool("c3"), answer("final")
    ).summaries("notes on both rounds")
    ctx = summarizing(model=model)

    ctx.send(request())
    # Rounds of about 310 tokens: the third one crosses the trigger of 800.
    ctx.tool_result("c1", "1" * 1200)
    ctx.tool_result("c2", "2" * 1200)
    assert model.summary_requests == []
    ctx.tool_result("c3", "3" * 1200)

    assert len(model.summary_requests) == 1
    asked = model.summary_requests[0][1].text
    assert "Round 1:\n" in asked
    assert "Round 2:\n" in asked
    assert "Round 3:" not in asked
    assert len(ctx.events()) == 1
    assert ctx.events()[0].details["rounds"] == 2
    assert ctx.events()[0].details["summarized_rounds"] == 2
    assert len(model.requests[3]) == 4


def test_one_summarizer_request_shows_ten_rounds_at_most() -> None:
    model = ScriptedModel(answer("final")).summaries("first ten", "the eleventh")
    # A budget that has room for many rounds in one request, and a low trigger.
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 20_000)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO, 0.01
        )
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    # Twelve rounds arrive with the request, so eleven are due at once.
    history = [ChatMessage.user("go")]
    for number in range(1, 13):
        history.append(call_tool(f"h{number}").message)
        history.append(
            ChatMessage.tool(
                ToolResultBlock(
                    call_id=f"h{number}", blocks=[TextBlock(text=str(number) * 100)]
                )
            )
        )

    ctx.send(ChatRequestEvent(model=MODEL, messages=history))

    # A summarizer that is asked for many lines at once is more likely to leave one
    # out, so the rounds are asked about ten at a time.
    assert len(model.summary_requests) == 2
    first = model.summary_requests[0][1].text
    assert "Round 10:\n" in first
    assert "Round 11:" not in first
    second = model.summary_requests[1][1].text
    assert "Round 11:\n" in second
    assert "Round 10:" not in second
    assert "Round 12:" not in second
    sent = model.requests[0]
    assert sent[0].blocks[1].text == summary_block(
        11, *["first ten"] * 10, "the eleventh"
    )
    assert ctx.events()[0].details["rounds"] == 11
    assert ctx.events()[0].details["summarized_rounds"] == 11


def test_truncation_masking_and_summarization_combine_in_that_order() -> None:
    model = ScriptedModel(
        call_tool("c1"),
        call_tool("c2"),
        call_tool("c3"),
        call_tool("c4"),
        answer("final"),
    ).summaries("notes on round 3")
    # Results are cut to 1000 characters, the last two rounds keep their results, and
    # rounds before the last one are summarized above 590 estimated tokens.
    ctx = (
        Context(model=model)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 1000
        )
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS, 2
        )
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 1000)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO, 0.59
        )
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    chat_request = request()

    ctx.send(chat_request)
    # One round, cut.
    ctx.tool_result("c1", "1" * 4000)
    assert len(tool_text(model.requests[1][3])) == 1000

    # Two rounds, both cut: under the trigger.
    ctx.tool_result("c2", "2" * 4000)
    assert len(tool_text(model.requests[2][3])) == 1000
    assert len(tool_text(model.requests[2][5])) == 1000

    # Three rounds. Cut alone they would be over the trigger. The first is masked, and
    # what is left is under it, so nothing is summarized.
    ctx.tool_result("c3", "3" * 4000)
    fourth = model.requests[3]
    assert tool_text(fourth[3]).startswith("[Tool result omitted")
    assert approximate(fourth) < 590
    assert approximate(ctx.stored(chat_request.id)[:8]) > 590
    assert model.summary_requests == []

    # Four rounds, two of them masked: over the trigger. Round 3 is the oldest round
    # that still has its result and is not kept, so it is the one that is summarized.
    ctx.tool_result("c4", "4" * 4000)
    assert len(model.summary_requests) == 1
    # The summarizer reads the stored result, cut to the same limit.
    asked = model.summary_requests[0][1].text
    assert 'Round 3:\nlookup({"q":"c3"}) returned: 3333' in asked
    assert "[3035 characters truncated]" in asked
    assert "Round 1:" not in asked
    assert "Round 2:" not in asked
    assert "Tool result omitted" not in asked
    fifth = model.requests[4]
    assert len(fifth) == 8
    assert fifth[1].blocks[1].text == (
        compaction.SUMMARY_SEPARATOR
        + compaction.summary_text("Round 3: notes on round 3")
    )
    assert tool_text(fifth[3]).startswith("[Tool result omitted")
    assert tool_text(fifth[5]).startswith("[Tool result omitted")
    assert fifth[6].tool_calls[0].call_id == "c4"
    assert len(tool_text(fifth[7])) == 1000
    assert "characters truncated" in tool_text(fifth[7])

    assert [event.step for event in ctx.events()] == [
        "truncate",
        "truncate",
        "truncate",
        "mask",
        "summarize",
        "truncate",
        "mask",
    ]


@pytest.mark.parametrize(
    "finish_reason",
    # No reason, the reasons of the connections for a complete answer, and the
    # end-of-sequence token as endpoints that speak the OpenAI protocol name it.
    [None, "", "stop", "STOP", "end_turn", "eos", "eos_token"],
)
def test_a_summary_is_used_when_the_model_ended_its_answer_by_itself(
    finish_reason: str | None,
) -> None:
    finished = ChatResult(
        message=ChatMessage.assistant("Round 1: account c1 holds 10"),
        finish_reason=finish_reason,
    )
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final")).summaries(
        finished
    )
    ctx = summarizing(model=model)

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    assert "error" not in ctx.events()[0].details
    assert ctx.events()[0].details["rounds"] == 1
    assert len(model.requests[2]) == 4


@pytest.mark.parametrize(
    "finish_reason",
    # The token limit as the chat action reads it and as providers spell it, a
    # context window, a filter, a pause, a sequence of the request at which the
    # answer stopped, and a reason that is not known.
    [
        "length",
        "max_tokens",
        "MAX_TOKENS",
        "model_length",
        "model_context_window_exceeded",
        "content_filter",
        "pause_turn",
        "tool_use",
        "stop_sequence",
        "something_new",
    ],
)
def test_a_summary_the_provider_did_not_finish_is_not_used(finish_reason: str) -> None:
    cut_off = ChatResult(
        message=ChatMessage.assistant("Round 1: account c1 ho"),
        finish_reason=finish_reason,
    )
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final")).summaries(
        cut_off
    )
    ctx = summarizing(model=model)

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    # The answer has the line of the round, and the line stops in the middle.
    assert len(model.requests[2]) == 6
    assert ctx.events()[0].details["error"] == (
        f"the summary is incomplete (finish_reason='{finish_reason}')"
    )
    assert ctx.events()[0].details["rounds"] == 0


def test_a_summary_that_asks_for_a_tool_call_is_not_used() -> None:
    # A summarizer is called without tools. This one answers with the line of the
    # round and a tool call all the same, and says that it ended by itself.
    with_call = ChatResult(
        message=ChatMessage.assistant(
            [
                TextBlock(text="Round 1: account c1 holds 10"),
                ToolCallBlock(call_id="x", name="lookup", input={"q": "c9"}),
            ]
        ),
        finish_reason="stop",
    )
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final")).summaries(
        with_call
    )
    ctx = summarizing(model=model)

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    # The round stays in the messages.
    assert len(model.requests[2]) == 6
    assert ctx.events()[0].details["error"] == "the summary asks for a tool call"
    assert ctx.events()[0].details["rounds"] == 0


def test_only_the_rounds_of_an_answer_that_cannot_be_used_are_asked_one_at_a_time() -> (
    None
):
    # The summarizer's output limit ends its answer for ten rounds in the second
    # line.
    cut_off = ChatResult(
        message=ChatMessage.assistant("Round 1: account h1 holds 10\nRound 2: acc"),
        finish_reason="length",
    )
    model = ScriptedModel(answer("final")).summaries(cut_off)
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 20_000)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO, 0.01
        )
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    # Twenty-one rounds arrive with the request, so twenty are due at once.
    history = [ChatMessage.user("go")]
    for number in range(1, 22):
        history.append(call_tool(f"h{number}").message)
        history.append(result(f"h{number}", str(number % 10) * 400))

    ctx.send(ChatRequestEvent(model=MODEL, messages=history))

    shown = [
        [int(number) for number in re.findall(r"(?m)^Round (\d+):$", asked[1].text)]
        for asked in model.summary_requests
    ]
    # The first request shows ten rounds, and its answer cannot be used. Those ten
    # rounds are asked about one at a time. A summarizer that could not answer for
    # ten rounds is then asked about five: the ten rounds after them take two
    # requests, not ten.
    assert shown == [
        list(range(1, 11)),
        *([number] for number in range(1, 11)),
        list(range(11, 16)),
        list(range(16, 21)),
    ]
    details = ctx.events()[0].details
    assert details["rounds"] == 20
    assert "error" not in details


def test_a_very_long_result_is_shown_no_longer_than_a_request_has_room_for(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    counted = []
    text_tokens = manager._text_tokens

    def counting(text: str) -> int:
        counted.append(len(text))
        return text_tokens(text)

    monkeypatch.setattr(manager, "_text_tokens", counting)
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final")).summaries(
        "notes on the large round"
    )
    ctx = summarizing(model=model)

    ctx.send(request())
    # Two million characters that take a token each, with no limit on a result.
    ctx.tool_result("c1", "\u8d26" * 2_000_000)
    ctx.tool_result("c2", "2" * 100)

    assert len(model.summary_requests) == 1
    assert approximate(model.summary_requests[0]) == 999
    assert "[1999340 characters truncated]" in model.summary_requests[0][1].text
    assert ctx.events()[0].details["rounds"] == 1
    # The request has room for a few hundred of them. The round is put together and
    # counted in that size, not in the size of the result.
    assert sum(counted) < 100_000


def test_an_answer_for_several_rounds_that_was_not_finished_is_asked_again() -> None:
    # The summarizer's output limit ends its answer for three rounds in the second
    # line.
    cut_off = ChatResult(
        message=ChatMessage.assistant("Round 1: account h1 holds 10\nRound 2: acc"),
        finish_reason="length",
    )
    model = ScriptedModel(answer("final")).summaries(cut_off)
    # A budget that has room for many rounds in one request, and a low trigger.
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 20_000)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO, 0.01
        )
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    # Four rounds arrive with the request, so three are due at once.
    history = [ChatMessage.user("go")]
    for number in range(1, 5):
        history.append(call_tool(f"h{number}").message)
        history.append(result(f"h{number}", str(number) * 400))

    ctx.send(ChatRequestEvent(model=MODEL, messages=history))

    # Nothing is kept of the answer that was cut off, not even its first line: each
    # round is asked about by itself, and the step does not fail.
    assert len(model.summary_requests) == 4
    first = model.summary_requests[0][1].text
    assert all(f"Round {number}:\n" in first for number in (1, 2, 3))
    for number in (1, 2, 3):
        shown = re.findall(
            r"(?m)^Round (\d+):$", model.summary_requests[number][1].text
        )
        assert shown == [str(number)]
    assert len(ctx.events()) == 1
    details = ctx.events()[0].details
    assert details["rounds"] == 3
    assert details["summarized_rounds"] == 3
    assert "error" not in details
    sent = model.requests[0]
    assert len(sent) == 3
    assert sent[0].blocks[1].text == summary_block(3, "notes")


def test_a_round_too_large_for_one_summarizer_request_has_its_results_cut() -> None:
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final")).summaries(
        "notes on the large round"
    )
    ctx = summarizing(model=model)

    ctx.send(request())
    # About 5000 tokens, five times the budget. It is the last round, so it is kept.
    ctx.tool_result("c1", "1" * 20_000)
    assert model.summary_requests == []
    ctx.tool_result("c2", "2" * 100)

    # The round is summarized from a request that fits the budget: the limit on its
    # result is halved until it does, here three times, and then raised to the
    # longest one that fits, so the summarizer is shown as much as the budget holds.
    assert len(model.summary_requests) == 1
    assert approximate(model.summary_requests[0]) == 1000
    asked = model.summary_requests[0][1].text
    assert "Round 1:\n" in asked
    assert "[17355 characters truncated]" in asked
    assert model.requests[2][1].blocks[1].text == summary_block(
        1, "notes on the large round"
    )
    assert ctx.events()[0].details["rounds"] == 1
    assert "error" not in ctx.events()[0].details


def test_a_summary_is_its_own_llm_call_with_token_metrics() -> None:
    notes = ChatResult(
        message=ChatMessage.assistant("Round 1: notes"),
        model="small-provider-model",
        usage=TokenUsage(prompt_tokens=700, completion_tokens=12),
    )
    model = ScriptedModel(
        call_tool("c1"), call_tool("c2"), call_tool("c3"), answer("final")
    )
    summarizer = ScriptedModel().summaries(notes, RuntimeError("summarizer is down"))
    ctx = summarizing(model=model, small=summarizer).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL, "small"
    )
    metadata = {LLMExecutionMetadataKeys.MODEL: "provider-model"}

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    # The summary is reported under the summarizer's name, with its own usage.
    ctx.ctx.report_execution_started.assert_any_call(
        ANY, ExecutionEntityTypes.LLM, "small", metadata
    )
    ctx.ctx.report_execution_succeeded.assert_any_call(
        ANY,
        ExecutionEntityTypes.LLM,
        "small",
        {
            **metadata,
            LLMExecutionMetadataKeys.USAGE: {
                "promptTokens": 700,
                "completionTokens": 12,
            },
        },
    )
    tokens = ctx.metrics.get_sub_group("model", "small-provider-model")
    assert tokens.get_counter("promptTokens").get_count() == 700
    assert tokens.get_counter("completionTokens").get_count() == 12
    ctx.ctx.report_execution_failed.assert_not_called()

    # A summary that fails is reported as a failed model call.
    ctx.tool_result("c3", "3" * 2000)
    ctx.ctx.report_execution_failed.assert_called_once()
    failed = ctx.ctx.report_execution_failed.call_args.args
    assert failed[1:4] == (ExecutionEntityTypes.LLM, "small", metadata)
    assert "summarizer is down" in str(failed[4])
    assert failed[5] == ExecutionProblemCategories.MODEL_CALL_FAILED


def test_a_summary_is_written_asynchronously_when_chat_calls_are() -> None:
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final")).summaries(
        "notes written asynchronously"
    )
    ctx = (
        Context(chat_async=True, model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 1000)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    assert model.requests[2][1].blocks[1].text == summary_block(
        1, "notes written asynchronously"
    )
    # The estimate is made off the operator's thread too: it renders the bound
    # prompt.
    assert ctx.durable_async_call_ids == [
        "chat",
        "chat",
        "estimate",
        "summarize",
        "chat",
    ]
    assert ctx.durable_call_ids == ctx.durable_async_call_ids


def test_a_summarizer_with_a_java_connection_is_called_on_the_operators_thread(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # A Flink version that cannot call a Java resource from another thread.
    monkeypatch.setattr(manager, "support_async", lambda: False)
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final")).summaries(
        "notes"
    )
    # The chat model is declared in Python and its connection in Java.
    ctx = (
        Context(
            chat_async=True,
            model=model,
            unused=MagicMock(spec=JavaChatModelConnection),
        )
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 1000)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    assert model.requests[2][1].blocks[1].text == summary_block(1, "notes")
    assert ctx.durable_call_ids == ["chat", "chat", "estimate", "summarize", "chat"]
    assert "estimate" not in ctx.durable_async_call_ids
    assert "summarize" not in ctx.durable_async_call_ids


@pytest.mark.parametrize(
    ("chat_async", "async_supported", "chat_model", "connection", "expected"),
    [
        # Without chat.async nothing leaves the operator's thread.
        (False, True, "python", "python", False),
        # Where Java can be called from another thread, every call follows
        # chat.async.
        (True, True, "java", "java", True),
        (True, True, "python", "java", True),
        # Where it cannot, a call that reaches Java stays on the operator's thread.
        (True, False, "java", "java", False),
        (True, False, "python", "java", False),
        (True, False, "python", "python", True),
        (True, False, "python", "unresolvable", True),
        (True, False, None, None, True),
    ],
)
def test_a_durable_call_follows_chat_async_unless_it_cannot_reach_java_that_way(
    monkeypatch: pytest.MonkeyPatch,
    chat_async: bool,
    async_supported: bool,
    chat_model: str | None,
    connection: str | None,
    expected: bool,
) -> None:
    monkeypatch.setattr(manager, "support_async", lambda: async_supported)
    setup = {
        None: None,
        "java": MagicMock(spec=JavaChatModelSetup),
        "python": ScriptedModel(),
    }[chat_model]
    ctx = MagicMock()
    ctx.config.get.side_effect = lambda option: (
        chat_async if option is AgentExecutionOptions.CHAT_ASYNC else None
    )

    def get_resource(name: str, kind: ResourceType) -> Any:
        assert (name, kind) == ("unused", ResourceType.CHAT_MODEL_CONNECTION)
        if connection == "unresolvable":
            msg = "resource not found: unused"
            raise ValueError(msg)
        return MagicMock(
            spec=JavaChatModelConnection
            if connection == "java"
            else BaseChatModelConnection
        )

    ctx.get_resource.side_effect = get_resource

    assert manager._runs_async(ctx, setup) is expected
    if async_supported or not chat_async:
        # The connection is looked at only where it decides.
        ctx.get_resource.assert_not_called()


def test_an_estimate_on_another_scale_is_lowered_by_the_share_the_lines_save() -> None:
    model = HalfScaleModel(call_tool("c1"), call_tool("c2"), call_tool("c3")).summaries(
        "notes on round 1"
    )
    ctx = summarizing(model=model)
    chat_request = request()

    ctx.send(chat_request)
    ctx.tool_result("c1", "1" * 4000)
    ctx.tool_result("c2", "2" * 4000)

    # The estimate of 1018 tokens is half of what the approximation counts. The line
    # saves 928 tokens of the approximation, which would leave 90; the messages are
    # left with 54 percent of their size, and so is the estimate.
    details = ctx.events()[0].details
    assert details["estimated_input_tokens_before"] == 1018
    assert details["estimated_input_tokens_after"] == 553
    # The model call reported 554 tokens, so the estimate was right and next to
    # nothing is corrected. Lowered by the difference, the estimate would have been
    # corrected four times.
    assert ctx.state(chat_request.id)["calibration"] == pytest.approx(1.0, abs=0.01)


@pytest.mark.parametrize(
    ("bound", "here", "expected"),
    [("java", "python", False), ("python", "java", True)],
)
def test_the_connection_is_the_one_the_chat_model_was_opened_with(
    monkeypatch: pytest.MonkeyPatch,
    bound: str,
    here: str,
    expected: bool,
) -> None:
    monkeypatch.setattr(manager, "support_async", lambda: False)
    kinds = {"java": JavaChatModelConnection, "python": BaseChatModelConnection}
    # An agent that inherits a chat model can declare a connection of its own under
    # the name of the connection the chat model was opened with.
    setup = ScriptedModel()
    setup.resource_context = MagicMock()
    setup.resource_context.get_resource.return_value = MagicMock(spec=kinds[bound])
    ctx = MagicMock()
    ctx.config.get.side_effect = lambda option: (
        option is AgentExecutionOptions.CHAT_ASYNC or None
    )
    ctx.get_resource.return_value = MagicMock(spec=kinds[here])

    assert manager._runs_async(ctx, setup) is expected
    setup.resource_context.get_resource.assert_called_once_with(
        "unused", ResourceType.CHAT_MODEL_CONNECTION
    )
    ctx.get_resource.assert_not_called()


def test_a_round_the_summary_covers_is_not_reported_as_masked() -> None:
    model = ScriptedModel(
        call_tool("c1"), call_tool("c2"), call_tool("c3"), answer("final")
    ).summaries("notes on round 1")
    # Masking moves two rounds at a time, and a summary leaves the last round.
    ctx = (
        Context(model=model)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS, 1
        )
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MASK_STEP_ROUNDS, 2
        )
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 600)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    assert ctx.events() == []
    # Two rounds are over the trigger of 480 tokens and neither is masked yet: round 1
    # is summarized.
    ctx.tool_result("c2", "2" * 2000)
    assert [event.step for event in ctx.events()] == ["summarize"]
    ctx.tool_result("c3", "3" * 2000)

    # Rounds 1 and 2 cross the masking boundary together. Round 1 is in the summary,
    # so only round 2 is sent as a placeholder and reported as masked.
    fourth = model.requests[3]
    assert len(fourth) == 6
    assert fourth[1].blocks[1].text == summary_block(1, "notes on round 1")
    assert tool_text(fourth[3]).startswith("[Tool result omitted")
    assert tool_text(fourth[5]) == "3" * 2000
    assert [event.step for event in ctx.events()] == ["summarize", "mask"]
    assert ctx.events()[1].details["rounds"] == 1
    assert [result["call_id"] for result in results(ctx.events()[1])] == ["c2"]
    # Round 2 is masked now, so it gets no line.
    assert len(model.summary_requests) == 1


def test_a_round_whose_results_are_masked_gets_no_line() -> None:
    model = ScriptedModel(
        call_tool("c1"), call_tool("c2"), call_tool("c3"), answer("final")
    )
    # Only the last round keeps its results, and a summary would leave the last round
    # too.
    ctx = summarizing(model=model).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS, 1
    )
    # The request alone takes about 900 of the 1000 tokens of the budget, so every
    # call is over the trigger.
    chat_request = ChatRequestEvent(
        model=MODEL, messages=[ChatMessage.user("u" * 3600)]
    )

    ctx.send(chat_request)
    ctx.tool_result("c1", "1" * 600)
    ctx.tool_result("c2", "2" * 600)
    # How far the rounds without a line go is kept, so they are looked at once.
    assert ctx.state(chat_request.id) == {"written_rounds": 1}
    ctx.tool_result("c3", "3" * 600)

    # A round is masked from the call on at which it would be due for its line. Its
    # results are placeholders by then, and a line would be about as long as they are:
    # the summarizer is not asked, and the input is not estimated for it.
    assert model.summary_requests == []
    assert ctx.estimates() == []
    assert ctx.durable_call_ids == ["chat", "chat", "chat", "chat"]
    assert [event.step for event in ctx.events()] == ["mask", "mask"]
    fourth = model.requests[3]
    assert len(fourth) == 7
    assert len(fourth[0].blocks) == 1
    assert tool_text(fourth[2]).startswith("[Tool result omitted")
    assert tool_text(fourth[4]).startswith("[Tool result omitted")
    assert tool_text(fourth[6]) == "3" * 600
    assert ctx.chat_response().response.text == "final"


def test_a_round_past_the_masking_boundary_that_is_still_long_gets_its_line() -> None:
    model = ScriptedModel(
        # The model says 5000 characters beside its first tool call.
        call_tool("c1", "t" * 5000),
        call_tool("c2"),
        call_tool("c3"),
        answer("final"),
    ).summaries("notes on round 1")
    # Only the last round keeps its results, and a summary would leave the last round
    # too.
    ctx = summarizing(model=model).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS, 1
    )

    ctx.send(request())
    # The result of round 1 is shorter than its placeholder, so it is not masked.
    ctx.tool_result("c1", "ok")
    ctx.tool_result("c2", "2" * 600)

    # Round 1 is past the masking boundary, and masking left it as long as it was:
    # about 1270 tokens. That is more than a line takes, so the round is due, the
    # input is estimated and the round gets its line.
    assert len(model.summary_requests) == 1
    assert "Round 1:\n" in model.summary_requests[0][1].text
    third = model.requests[2]
    assert len(third) == 4
    assert third[1].blocks[1].text == summary_block(1, "notes on round 1")
    assert third[2].tool_calls[0].call_id == "c2"
    assert [event.step for event in ctx.events()] == ["summarize"]

    # Round 2 is past the boundary next. Its result is a placeholder and its call is
    # short: it takes no more than a line, so it gets none.
    ctx.tool_result("c3", "3" * 600)
    assert len(model.summary_requests) == 1
    assert tool_text(model.requests[3][3]).startswith("[Tool result omitted")
    assert ctx.durable_call_ids == [
        "chat",
        "chat",
        "estimate",
        "summarize",
        "chat",
        "chat",
    ]


def test_a_round_past_the_masking_boundary_is_short_by_the_same_size_with_any_budget() -> (
    None
):
    # The reply of round 1 has 800 characters, about 200 tokens.
    model = ScriptedModel(
        call_tool("c1", "t" * 800), call_tool("c2"), call_tool("c3")
    ).summaries("notes on round 1")
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 30_000)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS, 1
        )
    )
    # The request alone is over the trigger of 24000 tokens.
    chat_request = ChatRequestEvent(
        model=MODEL, messages=[ChatMessage.user("u" * 100_000)]
    )

    ctx.send(chat_request)
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    # Round 1 is past the masking boundary: its result is a placeholder and its reply
    # is sent. With this budget a line may take 455 tokens, and the round takes less.
    # It is still not short, which is 95 tokens with any budget, so it gets its line.
    assert len(model.summary_requests) == 1
    assert ctx.state(chat_request.id)["summary"] == "Round 1: notes on round 1"


def test_a_short_round_past_the_masking_boundary_is_not_shown_beside_a_long_one() -> (
    None
):
    model = ScriptedModel(answer("final")).summaries("notes on round 1")
    ctx = summarizing(model=model).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_KEEP_LAST_ROUNDS, 1
    )
    # Four rounds arrive with a request that takes about 900 of the 1000 tokens of the
    # budget by itself. The first three rounds are past the masking boundary: round 1
    # with 1000 characters of the model's own text, rounds 2 and 3 with nothing but a
    # short call and the placeholder of their result.
    history = [
        ChatMessage.user("u" * 3600),
        call_tool("h1", "t" * 1000).message,
        result("h1", "ok"),
        call_tool("h2").message,
        result("h2", "2" * 600),
        call_tool("h3").message,
        result("h3", "3" * 600),
        call_tool("h4").message,
        result("h4", "4"),
    ]

    ctx.send(ChatRequestEvent(model=MODEL, messages=history))

    # Round 1 gets its line. Rounds 2 and 3 get none, so the request that asks about
    # round 1 does not show them, although it has room for them.
    assert len(model.summary_requests) == 1
    asked = model.summary_requests[0][1].text
    # Round 1 is shown whole: it is not a round that fills a request by itself.
    assert "t" * 1000 in asked
    assert "Round 1:\n" in asked
    assert "Round 2:" not in asked
    assert "Round 3:" not in asked
    assert ctx.events()[0].details["rounds"] == 1
    assert ctx.events()[0].details["summarized_rounds"] == 1
    sent = model.requests[0]
    assert len(sent) == 7
    assert sent[0].blocks[1].text == summary_block(1, "notes on round 1")
    assert tool_text(sent[2]).startswith("[Tool result omitted")
    assert tool_text(sent[4]).startswith("[Tool result omitted")
    assert tool_text(sent[6]) == "4"


def test_a_round_that_loads_a_skill_is_not_summarized() -> None:
    load_skill = ChatResult(
        message=ChatMessage.assistant(
            [ToolCallBlock(call_id="s1", name="load_skill", input={"name": "audit"})]
        )
    )
    model = ScriptedModel(
        call_tool("c1"), load_skill, call_tool("c3"), answer("final")
    ).summaries("notes on round 1")
    ctx = summarizing(model=model)

    ctx.send(request())
    ctx.tool_result("c1", "1" * 1500)
    ctx.tool_result("s1", "i" * 1500)
    ctx.tool_result("c3", "3" * 1500)

    # Rounds 1 and 2 are due. The summarizer is shown round 1 only; round 2 holds the
    # instructions of a skill and stays in the model's input as it is.
    assert len(model.summary_requests) == 1
    asked = model.summary_requests[0][1].text
    assert "Round 1:\n" in asked
    assert "Round 2:" not in asked
    assert "load_skill" not in asked
    # The summary joins the user message, in front of the round that stays.
    fourth = model.requests[3]
    assert len(fourth) == 6
    assert fourth[1].blocks[1].text == (
        compaction.SUMMARY_SEPARATOR
        + compaction.summary_text("Round 1: notes on round 1")
    )
    assert fourth[2].tool_calls[0].name == "load_skill"
    assert tool_text(fourth[3]) == "i" * 1500
    assert tool_text(fourth[5]) == "3" * 1500
    # One round got its line, and the summary has the line of that round alone.
    assert ctx.events()[0].details["rounds"] == 1
    assert ctx.events()[0].details["summarized_rounds"] == 1


def test_a_round_that_loads_a_skill_is_passed_over_without_asking_the_summarizer() -> (
    None
):
    load_skill = ChatResult(
        message=ChatMessage.assistant(
            [ToolCallBlock(call_id="s1", name="load_skill", input={"name": "audit"})]
        )
    )
    model = ScriptedModel(
        load_skill, call_tool("c2"), call_tool("c3"), answer("final")
    ).summaries("notes on round 2")
    ctx = summarizing(model=model)

    ctx.send(request())
    ctx.tool_result("s1", "i" * 2500)
    ctx.tool_result("c2", "2" * 2000)

    # Round 1 is due, and it loads a skill: there is nothing to estimate, nothing to
    # ask the summarizer and nothing to report. The model is sent both rounds as they
    # are.
    assert model.summary_requests == []
    assert ctx.events() == []
    assert len(model.requests[2]) == 6
    assert ctx.durable_call_ids == ["chat", "chat", "chat"]

    # Round 2 is due next. The summary has its line alone, and round 1 stays.
    ctx.tool_result("c3", "3" * 2000)
    assert len(model.summary_requests) == 1
    asked = model.summary_requests[0][1].text
    assert "Round 2:\n" in asked
    assert "Round 1:" not in asked
    fourth = model.requests[3]
    assert len(fourth) == 6
    assert fourth[1].blocks[1].text == (
        compaction.SUMMARY_SEPARATOR
        + compaction.summary_text("Round 2: notes on round 2")
    )
    assert tool_text(fourth[3]) == "i" * 2500
    assert fourth[4].tool_calls[0].call_id == "c3"
    assert len(ctx.events()) == 1
    assert ctx.events()[0].details["rounds"] == 1
    assert ctx.events()[0].details["summarized_rounds"] == 1


def test_rounds_after_an_oversized_one_start_a_batch_of_their_own() -> None:
    model = ScriptedModel(answer("final")).summaries(
        "notes on round 1", "notes on round 2"
    )
    ctx = summarizing(model=model)
    # Three rounds arrive with the request: one with two results of 3000 characters,
    # one with a result of 1000 characters, and the kept one.
    history = [
        ChatMessage.user("go"),
        ChatMessage.assistant(
            [
                ToolCallBlock(call_id="h1a", name="lookup", input={"q": "h1a"}),
                ToolCallBlock(call_id="h1b", name="lookup", input={"q": "h1b"}),
            ]
        ),
    ]
    for call_id, text in (("h1a", "a" * 3000), ("h1b", "b" * 3000)):
        history.append(
            ChatMessage.tool(
                ToolResultBlock(call_id=call_id, blocks=[TextBlock(text=text)])
            )
        )
    for call_id, text in (("h2", "2" * 1000), ("h3", "3" * 100)):
        history.append(call_tool(call_id).message)
        history.append(
            ChatMessage.tool(
                ToolResultBlock(call_id=call_id, blocks=[TextBlock(text=text)])
            )
        )

    ctx.send(ChatRequestEvent(model=MODEL, messages=history))

    # Round 1 fits a request only with its results cut to about 750 characters, and
    # is the only round of that request. That limit is not applied to round 2, which
    # is shown whole in a request of its own.
    assert len(model.summary_requests) == 2
    first = model.summary_requests[0][1].text
    assert "Round 1:\n" in first
    assert first.count("characters truncated") == 2
    assert "Round 2:" not in first
    second = model.summary_requests[1][1].text
    assert "notes on round 1" not in second
    assert "2" * 1000 in second
    assert "characters truncated" not in second
    assert all(approximate(asked) <= 1000 for asked in model.summary_requests)
    assert ctx.events()[0].details["rounds"] == 2
    assert ctx.events()[0].details["summarized_rounds"] == 2
    assert ctx.durable_call_ids == ["estimate", "summarize", "summarize", "chat"]


def test_a_round_whose_text_is_too_long_even_with_cut_results_is_cut_as_a_whole() -> (
    None
):
    # The reply that asks for the tool also carries 7000 characters of text.
    wordy = ChatResult(
        message=ChatMessage.assistant(
            [
                TextBlock(text="w" * 7000),
                ToolCallBlock(call_id="c1", name="lookup", input={"q": "c1"}),
            ]
        )
    )
    model = ScriptedModel(wordy, call_tool("c2"), answer("final")).summaries(
        "notes on the wordy round"
    )
    ctx = summarizing(model=model)

    ctx.send(request())
    ctx.tool_result("c1", "1" * 300)
    ctx.tool_result("c2", "2" * 300)

    assert len(model.summary_requests) == 1
    assert approximate(model.summary_requests[0]) <= 1000
    asked = model.summary_requests[0][1].text
    assert "Round 1:\nAssistant: www" in asked
    assert "characters truncated" in asked
    assert "error" not in ctx.events()[0].details


def test_a_round_that_is_cut_more_than_once_tells_how_much_of_it_is_missing() -> None:
    # 3000 characters that count as a token each: cutting the round to four
    # characters for a token of room is not enough, so it is cut again.
    wordy = ChatResult(
        message=ChatMessage.assistant(
            [
                TextBlock(text="é" * 3000),
                ToolCallBlock(call_id="c1", name="lookup", input={"q": "c1"}),
            ]
        )
    )
    model = ScriptedModel(wordy, call_tool("c2"), answer("final")).summaries(
        "notes on the wordy round"
    )
    ctx = summarizing(model=model)

    ctx.send(request())
    ctx.tool_result("c1", "ten")
    ctx.tool_result("c2", "twenty")

    assert len(model.summary_requests) == 1
    assert approximate(model.summary_requests[0]) <= 1000
    asked = model.summary_requests[0][1].text
    markers = re.findall(r"\[(\d+) characters truncated\]", asked)
    # Every cut is made from the whole round, so the one marker accounts for all of
    # it.
    assert len(markers) == 1
    assert int(markers[0]) + asked.count("é") == 3000
    assert "Round 1:\nAssistant: é" in asked
    assert "returned: ten\n" in asked


def test_a_budget_too_small_for_a_summarizer_request_fails_the_summary() -> None:
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final"))
    # The summarizer's instructions alone are about 250 tokens, and what it is told
    # about this request takes 100 more.
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 400)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )

    ctx.send(
        ChatRequestEvent(
            model=MODEL,
            messages=[ChatMessage.system("s" * 400), ChatMessage.user("u" * 400)],
        )
    )
    ctx.tool_result("c1", "1" * 500)
    ctx.tool_result("c2", "2" * 500)

    assert model.summary_requests == []
    details = ctx.events()[0].details
    assert details["error"] == (
        "a summarizer request has no room for a round within max_input_tokens=400"
    )
    assert details["rounds"] == 0
    assert len(model.requests[2]) == 6
    assert ctx.durable_call_ids == ["chat", "chat", "estimate", "chat"]


def test_only_the_line_of_each_round_shown_is_kept() -> None:
    # The summarizer adds a preamble, a line for a round it was not shown, a total
    # and a next step.
    answered = (
        "Here are the notes:\n"
        "- **Round 1:** account c1 holds 10\n"
        "Round 7: account c7 holds 70\n"
        "Total so far: 80\n"
        "Next, look up c2."
    )
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final")).summaries(
        answered
    )
    ctx = summarizing(model=model)

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    # The notes hold the line of round 1 and nothing else.
    assert model.requests[2][1].blocks[1].text == summary_block(
        1, "account c1 holds 10"
    )
    assert ctx.events()[0].details["summary_chars"] == len(
        "Round 1: account c1 holds 10"
    )
    assert "error" not in ctx.events()[0].details


def test_an_answer_without_the_line_of_a_round_is_not_used() -> None:
    model = ScriptedModel(
        call_tool("c1"), call_tool("c2"), call_tool("c3"), answer("final")
    ).summaries(
        "Round 2: the summarizer mixed up the rounds",
        "notes on round 1",
        "notes on round 2",
    )
    ctx = summarizing(model=model)
    chat_request = request()

    ctx.send(chat_request)
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    # Nothing is kept of an answer that leaves out a round it was shown.
    assert len(model.requests[2]) == 6
    details = ctx.events()[0].details
    assert details["error"] == "the summarizer wrote no line for round 1"
    assert details["rounds"] == 0
    # What is kept is that the step failed, from how many rounds on it is tried
    # again, and that it stopped at a round whose answer could not be used.
    assert ctx.state(chat_request.id) == {
        "failed_steps": 1,
        "retry_at_rounds": 3,
        "unusable_answers": 1,
    }

    # The next model call asks again.
    ctx.tool_result("c3", "3" * 2000)
    assert model.requests[3][1].blocks[1].text == summary_block(
        2, "notes on round 1", "notes on round 2"
    )


def test_rounds_are_asked_about_one_at_a_time_after_an_answer_that_left_one_out() -> (
    None
):
    model = ScriptedModel(answer("final")).summaries(
        "Round 1: the summarizer wrote one line for two rounds", "a", "b"
    )
    # A budget that has room for both rounds in one request, and a low trigger.
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 20_000)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO, 0.01
        )
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    history = [ChatMessage.user("go")]
    for number in range(1, 4):
        history.append(call_tool(f"h{number}").message)
        history.append(
            ChatMessage.tool(
                ToolResultBlock(
                    call_id=f"h{number}", blocks=[TextBlock(text=str(number) * 400)]
                )
            )
        )

    ctx.send(ChatRequestEvent(model=MODEL, messages=history))

    # Nothing is kept of the first answer. Each round is then shown alone, where
    # its line cannot be left out for another.
    assert len(model.summary_requests) == 3
    assert "Round 2:\n" in model.summary_requests[0][1].text
    second = model.summary_requests[1][1].text
    assert "Round 1:\n" in second
    assert "Round 2:" not in second
    third = model.summary_requests[2][1].text
    assert "Round 2:\n" in third
    assert "Round 1:" not in third
    assert model.requests[0][0].blocks[1].text == summary_block(2, "a", "b")
    details = ctx.events()[0].details
    assert details["rounds"] == 2
    assert "error" not in details
    assert ctx.durable_call_ids == [
        "estimate",
        "summarize",
        "summarize",
        "summarize",
        "chat",
    ]


def test_a_summarizer_request_with_room_for_less_than_a_round_is_not_sent() -> None:
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final"))
    # The summarizer's instructions and what it is told about this request take
    # about 430 tokens, which leaves about 20: less than a round needs.
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 450)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )

    ctx.send(
        ChatRequestEvent(
            model=MODEL,
            messages=[ChatMessage.system("s" * 400), ChatMessage.user("u" * 400)],
        )
    )
    # Round 1 is short enough for what is left. The request is not sent all the
    # same: whether rounds can be shown does not depend on the round.
    ctx.tool_result("c1", "ok")
    ctx.tool_result("c2", "2" * 1200)

    assert model.summary_requests == []
    details = ctx.events()[0].details
    assert details["error"] == (
        "a summarizer request has no room for a round within max_input_tokens=450"
    )
    assert details["rounds"] == 0


def parallel_round(*accounts: int, chars: int) -> List[ChatMessage]:
    """One round that looks up several accounts at once.

    Each result starts with the amount of its account and has ``chars`` characters.
    """
    calls = [
        ToolCallBlock(call_id=f"a{account}", name="lookup", input={"account": account})
        for account in accounts
    ]
    return [
        ChatMessage.assistant(calls),
        *[
            result(f"a{account}", f"amount={account}00 ".ljust(chars, "x"))
            for account in accounts
        ],
    ]


def test_what_is_left_of_a_result_stays_with_the_call_it_answers() -> None:
    model = ScriptedModel(answer("final")).summaries("notes on round 1")
    # A budget that leaves a summarizer request about 115 tokens for rounds.
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 390)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    # Round 1 has two calls. Its results do not fit even at their shortest limit.
    history = [
        ChatMessage.user("go"),
        *parallel_round(1, 2, chars=600),
        call_tool("h2").message,
        result("h2", "2" * 1200),
    ]

    ctx.send(ChatRequestEvent(model=MODEL, messages=history))

    # Each result is cut by itself, so its amount stands in the line of its own call.
    # Cut as one text, the round would show the call for account 1 and then the end
    # of the result for account 2.
    assert len(model.summary_requests) == 1
    asked = model.summary_requests[0][1].text
    shown = asked[asked.index("Round 1:\n") :].split("\n")
    assert shown[0] == "Round 1:"
    assert shown[1].startswith('lookup({"account":1}) returned: amount=100 x')
    assert shown[3].startswith("x")
    assert shown[4].startswith('lookup({"account":2}) returned: amount=200 x')
    assert asked.count("characters truncated") == 2
    assert approximate(model.summary_requests[0]) <= 390
    assert ctx.events()[0].details["rounds"] == 1


def test_results_that_do_not_fit_in_their_shortest_form_are_left_out_and_counted() -> (
    None
):
    model = ScriptedModel(answer("final")).summaries("notes on round 1")
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 600)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    # Round 1 has 60 calls: more than the room of about 325 tokens holds, even with
    # every result cut to the marker of its cut.
    history = [
        ChatMessage.user("go"),
        *parallel_round(*range(1, 61), chars=600),
        call_tool("h2").message,
        result("h2", "2" * 1200),
    ]

    ctx.send(ChatRequestEvent(model=MODEL, messages=history))

    assert len(model.summary_requests) == 1
    asked = model.summary_requests[0][1].text
    lines = (
        asked[asked.index("Round 1:\n") :].split("\n\nWrite one line")[0].split("\n")
    )
    # The first results are shown, each with its call, and the last line tells how
    # many lines of the round are not shown.
    shown = [line for line in lines if line.startswith("lookup(")]
    assert 0 < len(shown) < 60
    assert [line.split(")")[0] for line in shown] == [
        f'lookup({{"account":{account}}}' for account in range(1, len(shown) + 1)
    ]
    assert lines[-1] == f"[{60 - len(shown)} more lines of this round are not shown]"
    assert approximate(model.summary_requests[0]) <= 600
    assert "error" not in ctx.events()[0].details


def test_a_round_that_is_cut_part_by_part_keeps_the_limit_on_a_tool_result() -> None:
    model = ScriptedModel(
        call_tool("c1", "a" * 40_000), call_tool("c2"), answer("final")
    ).summaries("notes on round 1")
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 1500)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 100
        )
    )

    ctx.send(request())
    ctx.tool_result("c1", "z" * 606)
    ctx.tool_result("c2", "2" * 50)

    # The model is sent 100 characters of the first result.
    sent = tool_text(model.requests[1][3])
    assert len(sent) == 100
    # The reply of round 1 is far too long for a summarizer request, so the round is
    # cut part by part. The summarizer is shown the result as the model was, and no
    # more of it.
    assert len(model.summary_requests) == 1
    asked = model.summary_requests[0][1].text
    assert sent in asked
    assert asked.count("z") == sent.count("z")
    assert approximate(model.summary_requests[0]) == 1500


def test_a_round_that_is_cut_part_by_part_shows_a_result_that_fits_whole() -> None:
    model = ScriptedModel(
        call_tool("c1", "a" * 40_000), call_tool("c2"), answer("final")
    ).summaries("notes on round 1")
    # No limit on a tool result.
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 1500)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )

    ctx.send(request())
    ctx.tool_result("c1", "z" * 606)
    ctx.tool_result("c2", "2" * 50)

    # The reply is what is too long. It is cut, and the result, which has room, is
    # shown whole: it is not cut to the shortest limit that results are tried with.
    assert len(model.summary_requests) == 1
    asked = model.summary_requests[0][1].text
    assert asked.count("z") == 606
    assert "[35976 characters truncated]" in asked
    assert approximate(model.summary_requests[0]) == 1499


def test_a_round_that_cannot_fit_a_request_is_not_put_together_for_it(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    counted = []
    text_tokens = manager._text_tokens

    def counting(text: str) -> int:
        counted.append(len(text))
        return text_tokens(text)

    monkeypatch.setattr(manager, "_text_tokens", counting)
    model = ScriptedModel(answer("final")).summaries("notes on round 1")
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 16_000)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    # Round 1 has 64 calls with results of 70,000 characters each.
    history = [
        ChatMessage.user("go"),
        *parallel_round(*range(1, 65), chars=70_000),
        call_tool("h2").message,
        result("h2", "2" * 1200),
    ]

    ctx.send(ChatRequestEvent(model=MODEL, messages=history))

    assert len(model.summary_requests) == 1
    assert approximate(model.summary_requests[0]) == 15_985
    asked = model.summary_requests[0][1].text
    assert asked.count("[69090 characters truncated]") == 64
    assert ctx.events()[0].details["rounds"] == 1
    # About 4.5 million characters of results. What is counted to size the request
    # is in the size of the request.
    assert sum(counted) < 200_000


def test_a_result_is_shown_before_the_text_the_assistant_wrote_with_its_call() -> None:
    model = ScriptedModel(answer("final")).summaries("notes on round 1")
    # A budget that leaves a summarizer request 51 tokens for rounds: room for the
    # label, one part and the line that counts what is left out.
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 356)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    # Round 1 has a long text beside its call, and one result.
    history = [
        ChatMessage.user("go"),
        call_tool("h1", "t" * 2000).message,
        result("h1", "amount=100 " + "x" * 600),
        call_tool("h2").message,
        result("h2", "2" * 1200),
    ]

    ctx.send(ChatRequestEvent(model=MODEL, messages=history))

    # There is room for one part. It is the result with its call: the text the
    # assistant wrote says less about what the round found.
    assert len(model.summary_requests) == 1
    asked = model.summary_requests[0][1].text
    shown = asked[asked.index("Round 1:\n") :].split("\n\nWrite one line")[0]
    assert shown.startswith('Round 1:\nlookup({"q":"h1"}) returned: amount=100 x')
    assert shown.endswith("\n[1 more lines of this round are not shown]")
    assert "Assistant:" not in shown
    assert approximate(model.summary_requests[0]) <= 356


def test_a_round_of_any_script_is_cut_to_the_room_of_a_summarizer_request() -> None:
    model = ScriptedModel(answer("final")).summaries("notes on round 1")
    # A budget that leaves a summarizer request about 60 tokens for rounds.
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 500)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    # Round 1 is in a script that takes a token for each character, so 100
    # characters of it do not fit. Round 2 is kept.
    history = [
        ChatMessage.system("s" * 400),
        ChatMessage.user("u" * 400),
        call_tool("h1", "\u8d26" * 300).message,
        result("h1", "\u8d26" * 300),
        call_tool("h2").message,
        result("h2", "2" * 1200),
    ]

    ctx.send(ChatRequestEvent(model=MODEL, messages=history))

    # The round is cut to what fits and gets its line, so it does not hold up the
    # rounds after it.
    assert len(model.summary_requests) == 1
    asked = model.summary_requests[0][1].text
    assert "Round 1:\n" in asked
    assert "characters truncated" in asked
    assert 0 < asked.count("\u8d26") < 60
    assert approximate(model.summary_requests[0]) <= 500
    details = ctx.events()[0].details
    assert "error" not in details
    assert details["rounds"] == 1
    assert details["summarized_rounds"] == 1


def test_a_line_that_is_far_longer_than_asked_is_cut() -> None:
    # The summarizer is asked for 30 words and copies 2000 characters of the result.
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final")).summaries(
        "Round 1: " + "n" * 2000
    )
    ctx = summarizing(model=model)

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    # Three tokens for each word asked are kept of a line: 90 here, 360 characters.
    # The marker of the cut is on the line: the note of a round stays one line.
    line = "n" * 163 + " ...[1675 characters truncated]... " + "n" * 162
    assert model.requests[2][1].blocks[1].text == summary_block(1, line)
    assert ctx.events()[0].details["summary_chars"] == len("Round 1: ") + 360


def test_notes_that_are_full_take_no_more_rounds() -> None:
    # Every line is as long as a line may be, and the notes may take half of the
    # budget of 1000 tokens: four lines of 92 tokens fit beside the heading.
    model = ScriptedModel(*[call_tool(f"c{number}") for number in range(1, 9)])
    model.summaries(*["n" * 400] * 6)
    ctx = summarizing(model=model)
    chat_request = request()

    ctx.send(chat_request)
    for number in range(1, 8):
        ctx.tool_result(f"c{number}", str(number) * 2000)

    # Rounds 1 to 4 are in the notes. A fifth line of the most tokens a line can take
    # would not fit, so the summarizer is not asked about round 5, which stays in the
    # input with the rounds after it.
    summaries = [event.details for event in ctx.events()]
    assert [details["summarized_rounds"] for details in summaries] == [1, 2, 3, 4, 4, 4]
    assert [details["rounds"] for details in summaries] == [1, 1, 1, 1, 0, 0]
    assert len(model.summary_requests) == 4
    assert summaries[5]["error"] == (
        "the summary is full: it takes 446 of the 500 estimated tokens it may take "
        "with max_input_tokens=1000, which leaves no room for the line of another "
        "round"
    )
    assert ctx.state(chat_request.id)["written_rounds"] == 4
    assert len(model.requests[7]) == 2 + 2 + 2 + 2


def test_a_round_in_a_script_that_takes_a_token_for_each_character_is_cut_to_fit() -> (
    None
):
    model = ScriptedModel(answer("final")).summaries("notes on round 1")
    # A budget that leaves a summarizer request 155 tokens for rounds.
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 460)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    # Round 1 has 150 characters that are a token each: with its call it takes
    # about 160 tokens.
    history = [ChatMessage.user("go")]
    for call_id, text in (("h1", "账" * 150), ("h2", "2" * 1200)):
        history.append(call_tool(call_id).message)
        history.append(
            ChatMessage.tool(
                ToolResultBlock(call_id=call_id, blocks=[TextBlock(text=text)])
            )
        )

    ctx.send(ChatRequestEvent(model=MODEL, messages=history))

    # Four characters for a token of room would be the whole round, which does not
    # fit; the longest cut that fits keeps most of it.
    assert len(model.summary_requests) == 1
    asked = model.summary_requests[0][1].text
    assert 'Round 1:\nlookup({"q":"h1"}) returned: 账' in asked
    assert "characters truncated" in asked
    assert asked.count("账") > 100
    assert approximate(model.summary_requests[0]) <= 460
    assert "error" not in ctx.events()[0].details
    assert ctx.events()[0].details["rounds"] == 1


# ── Token estimates ─────────────────────────────────────────────────────


def test_an_approximate_estimate_is_corrected_by_the_reported_input_size() -> None:
    # The provider reports three times the approximate estimate, so the trigger of
    # 3200 is crossed at about 1560 estimated tokens.
    model = ScriptedModel(
        call_tool("c1"), call_tool("c2"), call_tool("c3"), call_tool("c4")
    ).reporting_usage_times(3)
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 4000)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    chat_request = request()

    ctx.send(chat_request)
    ctx.tool_result("c1", "1" * 2000)
    # Nothing was estimated for the call with one round, so there is nothing to
    # compare.
    assert ctx.state(chat_request.id) is None
    # Two rounds, about 1040 estimated tokens: the first estimate, not corrected yet.
    ctx.tool_result("c2", "2" * 2000)
    assert model.summary_requests == []
    assert ctx.state(chat_request.id)["calibration"] == 3.0
    ctx.tool_result("c3", "3" * 2000)

    assert len(model.summary_requests) > 0
    three_rounds = approximate(ctx.stored(chat_request.id)[:8])
    assert three_rounds < 3200
    details = ctx.events()[0].details
    assert details["estimated_input_tokens_before"] == 3 * three_rounds
    assert details["estimate_approximate"] is True


def test_a_reported_input_size_below_the_estimate_does_not_lower_it() -> None:
    # A provider that reports cached input tokens separately can report a small input
    # size for a large request. Taken at face value it would hold the estimate of
    # three rounds at a fraction of its 1550 tokens and the trigger of 1200 would not
    # be reached.
    model = ScriptedModel(
        call_tool("c1"), call_tool("c2"), call_tool("c3"), answer("final")
    ).reporting_usage_of(10)
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 1500)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    chat_request = request()

    ctx.send(chat_request)
    ctx.tool_result("c1", "1" * 2000)
    # Two rounds are 1037 estimated tokens, under the trigger, and 10 reported ones.
    ctx.tool_result("c2", "2" * 2000)
    assert model.summary_requests == []
    assert ctx.state(chat_request.id)["calibration"] == 1.0
    ctx.tool_result("c3", "3" * 2000)

    assert len(model.summary_requests) > 0
    assert ctx.events()[0].details["estimated_input_tokens_before"] == 1550


def test_an_exact_estimate_is_used_as_it_is() -> None:
    # The same request and reported usage as above, but the chat model counts tokens
    # itself: about 1040 tokens stay under the trigger of 1600.
    model = ExactModel(call_tool("c1"), call_tool("c2"), answer("final"))
    model.reporting_usage_times(3)
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 2000)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    chat_request = request()

    ctx.send(chat_request)
    ctx.tool_result("c1", "1" * 2000)
    # An exact estimate needs no correction, so none is kept.
    assert ctx.state(chat_request.id) is None
    ctx.tool_result("c2", "2" * 2000)

    assert model.summary_requests == []
    assert ctx.chat_response().response.text == "final"


class CachingModel(ScriptedModel):
    """A chat model whose provider counts the input it served from its cache apart.

    The first call whose input is estimated, the one with two rounds, is billed in
    full, three times the estimate. The later ones report 10 input tokens.
    """

    def chat(
        self, messages: Sequence[ChatMessage], prompt_args: Any = None, **kwargs: Any
    ) -> ChatResult:
        if len(self.requests) >= 3:
            self.reporting_usage_of(10)
        return super().chat(messages, prompt_args, **kwargs)


def test_a_later_smaller_report_does_not_take_back_the_correction() -> None:
    model = CachingModel(
        call_tool("c1"),
        call_tool("c2"),
        call_tool("c3"),
        call_tool("c4"),
        answer("final"),
    )
    model.reporting_usage_times(3)
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 4000)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    chat_request = request()

    ctx.send(chat_request)
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)
    assert model.summary_requests == []
    assert ctx.state(chat_request.id)["calibration"] == 3.0
    # Three rounds, about 1560 estimated tokens, count three times and cross the
    # trigger of 3200: rounds 1 and 2 are summarized.
    ctx.tool_result("c3", "3" * 2000)
    summary_requests = len(model.summary_requests)
    assert summary_requests > 0
    # The call that followed reported 10 tokens. The factor of 3 stays.
    assert ctx.state(chat_request.id)["calibration"] == 3.0

    # So the summary and two rounds, about 1090 estimated tokens, still count three
    # times and cross the trigger again.
    ctx.tool_result("c4", "4" * 2000)
    assert approximate(model.requests[3]) < 3200
    assert len(model.summary_requests) == summary_requests + 1


def test_the_correction_comes_from_the_call_with_the_largest_reported_input() -> None:
    # The provider counts 2000 tokens the estimate does not cover, a prompt and tools
    # bound on the other language's side for example.
    model = ScriptedModel(
        call_tool("c1"), call_tool("c2"), call_tool("c3"), call_tool("c4")
    ).reporting_usage_plus(2000)
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 100_000)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    chat_request = request()

    ctx.send(chat_request)
    ctx.tool_result("c1", "1" * 500)
    ctx.tool_result("c2", "2" * 500)
    # Two rounds: about 290 estimated and 2290 reported tokens, which is the most the
    # factor can be.
    assert approximate(model.requests[2]) < 500
    assert ctx.state(chat_request.id)["calibration"] == 4.0

    # Three rounds, the third a large one: the fixed part weighs less in a larger
    # request, and the factor follows it down.
    ctx.tool_result("c3", "3" * 4000)
    estimated = approximate(model.requests[3])
    state = ctx.state(chat_request.id)
    assert state["calibration"] == (estimated + 2000) / estimated
    assert state["calibration"] < 3.0
    assert state["calibration_tokens"] == estimated + 2000


def test_a_shorter_input_with_a_larger_factor_raises_the_correction() -> None:
    # The provider counts 6000 tokens the estimate does not cover (see above). Two
    # large results cross the trigger of 8000 at once, so the factor is first learned
    # from a request in which that fixed part weighs little.
    model = ScriptedModel(
        *[call_tool(f"c{number}") for number in range(1, 14)]
    ).reporting_usage_plus(6000)
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 10_000)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    chat_request = request()

    ctx.send(chat_request)
    ctx.tool_result("c1", "1" * 80_000)
    ctx.tool_result("c2", "2" * 80_000)
    state = ctx.state(chat_request.id)
    large = state["calibration_tokens"]
    assert large == approximate(model.requests[2]) + 6000
    assert large > 26_000
    assert 1.29 < state["calibration"] < 1.31

    # With both large rounds summarized the input is short, and most of what the
    # provider counts is the fixed part. The call reports less than the one before
    # and more than four times its estimate: its factor is the larger one now, and
    # the most a factor can be. The largest reported input is still that of the
    # large call, with its factor.
    ctx.tool_result("c3", "3" * 1200)
    short = approximate(model.requests[3]) + 6000
    assert short < 7000
    after = ctx.state(chat_request.id)
    assert after["calibration"] == 4.0
    assert after["calibration_tokens"] == large
    assert after["calibration_at_largest"] == state["calibration"]
    summary_requests = len(model.summary_requests)

    # The factor of the large request, 1.3, would hold the estimates of the rounds
    # that follow under the trigger until the reported input is half over it, and
    # over the budget. With the factor of the short request a summary is written when
    # the reported input comes to the trigger.
    for number in range(4, 13):
        ctx.tool_result(f"c{number}", str(number % 10) * 1200)
    assert len(model.summary_requests) > summary_requests
    reported = [approximate(sent) + 6000 for sent in model.requests[3:]]
    assert max(reported) <= 8000
    # None of these calls reported more than the large one did.
    assert ctx.state(chat_request.id)["calibration"] == 4.0
    assert ctx.state(chat_request.id)["calibration_tokens"] == large


def test_a_report_that_is_not_the_largest_does_not_lower_the_correction() -> None:
    # As above: the large rounds give a factor of about 1.3, and the short input that
    # their summaries leave gives one of 4.
    model = ScriptedModel(
        *[call_tool(f"c{number}") for number in range(1, 6)]
    ).reporting_usage_plus(6000)
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 10_000)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    chat_request = request()

    ctx.send(chat_request)
    ctx.tool_result("c1", "1" * 80_000)
    ctx.tool_result("c2", "2" * 80_000)
    ctx.tool_result("c3", "3" * 1200)
    state = ctx.state(chat_request.id)
    assert state["calibration"] == 4.0
    at_largest = state["calibration_at_largest"]
    assert 1.29 < at_largest < 1.31

    # From here the provider reports the size of the request and no more, as one does
    # that counts the input it served from its cache apart. The next call reports
    # more than the short one did and less than the large one, with a factor of 1.
    # It does not lower the correction below that of the large call.
    model.reporting_usage_plus(0).reporting_usage_times(1)
    ctx.tool_result("c4", "4" * 40_000)
    reported = approximate(model.requests[4])
    state = ctx.state(chat_request.id)
    assert (
        approximate(model.requests[3]) + 6000 < reported < state["calibration_tokens"]
    )
    assert state["calibration"] == at_largest


def test_the_later_of_two_calls_that_report_the_same_input_gives_the_factor() -> None:
    model = ScriptedModel(
        call_tool("c1"), call_tool("c2"), call_tool("c3"), call_tool("c4")
    ).reporting_usage_of(5000)
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 100_000)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    chat_request = request()

    ctx.send(chat_request)
    ctx.tool_result("c1", "1" * 4000)
    ctx.tool_result("c2", "2" * 4000)
    first = approximate(model.requests[2])
    assert ctx.state(chat_request.id)["calibration"] == 5000 / first

    # The same reported input for a longer request: the later call is the one with
    # the largest reported input, and its factor is the smaller one.
    ctx.tool_result("c3", "3" * 4000)
    second = approximate(model.requests[3])
    assert second > first
    assert ctx.state(chat_request.id) == {
        "calibration": 5000 / second,
        "calibration_tokens": 5000,
        "calibration_at_largest": 5000 / second,
    }


def test_the_input_size_a_fallback_candidate_reports_does_not_correct_the_estimate() -> (
    None
):
    small = ScriptedModel(RuntimeError("small is down"))
    big = ScriptedModel(call_tool("c2"), call_tool("c3")).reporting_usage_times(3)
    ctx = (
        routed(fallback=True, small=small, big=big)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 100_000)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    chat_request = ChatRequestEvent(
        model="router",
        messages=[
            ChatMessage.user("go"),
            call_tool("h1").message,
            ChatMessage.tool(
                ToolResultBlock(call_id="h1", blocks=[TextBlock(text="result")])
            ),
            call_tool("h2").message,
            ChatMessage.tool(
                ToolResultBlock(call_id="h2", blocks=[TextBlock(text="result")])
            ),
        ],
    )

    ctx.send(chat_request)
    # The request came with two rounds, so its input was estimated, for small. What
    # big reports for the same messages is no measure of it, so nothing is kept.
    assert len(ctx.estimates()) == 1
    assert len(big.requests) == 1
    assert ctx.state(chat_request.id) is None

    # The tool round stays on big, and what big reports now corrects its own
    # estimate.
    ctx.tool_result("c2", "result")
    assert len(big.requests) == 2
    assert ctx.state(chat_request.id)["calibration"] == 3.0


def test_without_a_policy_the_rounds_of_a_request_are_not_listed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def listed(messages: Sequence[ChatMessage]) -> List[compaction.Round]:
        pytest.fail("the rounds of a request without a policy were listed")

    monkeypatch.setattr(compaction, "find_rounds", listed)
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final"))
    ctx = Context(model=model)

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    assert ctx.chat_response().response.text == "final"
    assert tool_text(model.requests[2][3]) == "1" * 2000


def test_a_message_that_is_none_is_left_to_the_model_call() -> None:
    # A list with such a message did not come through a ChatRequestEvent, which checks
    # its messages. Without context management the model call reports it, and the
    # request fails; nothing here raises before that call.
    messages = [
        ChatMessage.user("go"),
        call_tool("c1").message,
        ChatMessage.tool(
            ToolResultBlock(call_id="c1", blocks=[TextBlock(text="1" * 2000)])
        ),
        None,
    ]
    for ctx in (
        Context(model=ScriptedModel()),
        Context(model=ScriptedModel()).with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 100
        ),
    ):
        prepared = asyncio.run(
            manager.prepare(
                request().id,
                MODEL,
                messages,
                None,
                ctx.ctx,
                first_call=True,
                candidates=[MODEL],
                output_schema=None,
            )
        )
        assert prepared.messages is messages
    assert compaction.has_round([None, ChatMessage.user("go")]) is False


def test_an_estimate_the_chat_model_cannot_give_still_counts_its_bound_prompt() -> None:
    # This chat model has no connection to estimate with, and 1600 characters of
    # instructions in its prompt: about 400 tokens the request's own messages do not
    # show.
    model = ScriptedModel(
        call_tool("c1"),
        call_tool("c2"),
        call_tool("c3"),
        prompt=Prompt.from_messages([ChatMessage.system("i" * 1600)]),
    ).summaries("notes on round 1")
    ctx = summarizing(model=model)
    chat_request = ChatRequestEvent(model=MODEL, messages=[ChatMessage.user("go")])

    ctx.send(chat_request)
    ctx.tool_result("c1", "1" * 1000)
    ctx.tool_result("c2", "2" * 1000)

    # Two rounds are about 530 tokens, under the trigger of 800. With the prompt the
    # model is sent about 930, so round 1 is summarized.
    two_rounds = ctx.stored(chat_request.id)[:5]
    assert approximate(two_rounds) < 800
    assert len(model.summary_requests) == 1
    details = ctx.events()[0].details
    assert details["estimated_input_tokens_before"] == approximate(
        model.prepare_request_messages(two_rounds, {})
    )
    assert details["estimate_approximate"] is True


# ── Lines that are held, and the recorded estimate ──────────────────────


def test_lines_that_do_not_make_the_input_smaller_are_held_until_they_do() -> None:
    model = ScriptedModel(
        call_tool("c1"),
        call_tool("c2"),
        call_tool("c3"),
        call_tool("c4"),
        call_tool("c5"),
    ).summaries("ok", "ok", "ok")
    ctx = summarizing(model=model)
    # The request alone takes about 900 of the 1000 tokens of the budget, so every
    # call is over the trigger, and the first two rounds are a few tokens each.
    chat_request = ChatRequestEvent(
        model=MODEL, messages=[ChatMessage.user("u" * 3600)]
    )

    ctx.send(chat_request)
    ctx.tool_result("c1", "1")
    assert model.summary_requests == []
    ctx.tool_result("c2", "2")

    # Round 1 is due and gets its line. The heading and the line take more than the
    # round they would replace, so the model is still sent the round, and the line is
    # held.
    assert len(model.summary_requests) == 1
    third = model.requests[2]
    assert len(third) == 5
    assert len(third[0].blocks) == 1
    assert tool_text(third[2]) == "1"
    assert ctx.state(chat_request.id) == {
        "pending_summary": "Round 1: ok",
        "written_rounds": 1,
    }
    assert len(ctx.events()) == 1
    details = ctx.events()[0].details
    assert details["rounds"] == 1
    assert details["summarized_rounds"] == 0
    assert details["pending_rounds"] == 1
    assert details["estimated_input_tokens_before"] == approximate(third)
    assert not {"summary_chars", "estimated_input_tokens_after", "error"} & set(details)

    # Round 2 is due next. Round 1 has its line and is not asked about again, and two
    # lines are still longer than two short rounds.
    ctx.tool_result("c3", "3" * 600)
    assert len(model.summary_requests) == 2
    asked = model.summary_requests[1][1].text
    assert "Round 2:\n" in asked
    assert "Round 1:" not in asked
    assert len(model.requests[3]) == 7
    assert len(model.requests[3][0].blocks) == 1
    assert ctx.state(chat_request.id) == {
        "pending_summary": "Round 1: ok\nRound 2: ok",
        "written_rounds": 2,
    }
    assert ctx.events()[1].details["rounds"] == 1
    assert ctx.events()[1].details["summarized_rounds"] == 0
    assert ctx.events()[1].details["pending_rounds"] == 2

    # Round 3 holds 600 characters. With its line, the three lines are shorter than
    # the three rounds, and the model is sent them from this call on.
    ctx.tool_result("c4", "4")
    assert len(model.summary_requests) == 3
    asked = model.summary_requests[2][1].text
    assert "Round 3:\n" in asked
    assert "Round 1:" not in asked
    assert "Round 2:" not in asked
    fifth = model.requests[4]
    assert len(fifth) == 3
    assert fifth[0].blocks[1].text == summary_block(3, "ok")
    assert fifth[1].tool_calls[0].call_id == "c4"
    assert ctx.state(chat_request.id) == {
        "summary": "Round 1: ok\nRound 2: ok\nRound 3: ok",
        "written_rounds": 3,
    }
    details = ctx.events()[2].details
    assert details["rounds"] == 1
    assert details["summarized_rounds"] == 3
    assert details["summary_chars"] == len("Round 1: ok\nRound 2: ok\nRound 3: ok")
    assert details["estimated_input_tokens_after"] == approximate(fifth)
    assert "pending_rounds" not in details
    assert approximate(fifth) < approximate(model.requests[3])

    # The two inputs are compared by the size of their messages, which takes no call.
    assert ctx.durable_call_ids == [
        "chat",
        "chat",
        *["estimate", "summarize", "chat"] * 3,
    ]


def test_what_the_agent_was_asked_is_recorded_with_the_estimate_that_asks() -> None:
    model = ScriptedModel(
        call_tool("c1"), call_tool("c2"), call_tool("c3"), answer("final")
    )
    # Summarize above 1200 tokens, keep the last round.
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 1500)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)
    ctx.tool_result("c3", "3" * 2000)

    estimates = ctx.estimates()
    # One estimate for a model call that has a round due, also for the call that a
    # summary is written for.
    assert len(estimates) == 2
    # Two rounds, under the trigger: the number alone.
    assert estimates[0] == manager.EstimateOutcome(
        approximate(model.requests[2]), approximate=True
    )
    # Three rounds, over the trigger: what the summarizer is told about the request
    # is recorded too, so a replay asks without rendering the prompt again.
    assert estimates[1].tokens > 1200
    assert estimates[1].instructions == "You are terse."
    assert estimates[1].request == "go"
    # The input with the new lines is not estimated again: it is the estimate before,
    # less what the lines save.
    details = ctx.events()[0].details
    assert details["estimated_input_tokens_before"] == estimates[1].tokens
    assert details["estimated_input_tokens_after"] == approximate(model.requests[3])
    # What the durable call records is a tuple of plain values.
    recorded = [result for call, result in ctx.durable_results if call == "estimate"]
    assert recorded[1] == (estimates[1].tokens, True, "You are terse.", "go")


class CountingModel(ScriptedModel):
    """A chat model that counts how often it is asked for its request or an estimate."""

    _estimates: int = PrivateAttr(default=0)
    _renders: int = PrivateAttr(default=0)

    @property
    def estimates(self) -> int:
        return self._estimates

    @property
    def renders(self) -> int:
        return self._renders

    def estimate_input_tokens(
        self, messages: Sequence[ChatMessage], prompt_args: Any = None
    ) -> TokenEstimate:
        self._estimates += 1
        return super().estimate_input_tokens(messages, prompt_args)

    def prepare_request_messages(
        self, messages: Sequence[ChatMessage], prompt_args: Any = None
    ) -> List[ChatMessage]:
        self._renders += 1
        return super().prepare_request_messages(messages, prompt_args)


def test_a_recorded_estimate_decides_without_estimating_or_rendering_again() -> None:
    model = CountingModel(call_tool("c1"), call_tool("c2"), answer("final")).summaries(
        "notes on round 1"
    )
    ctx = summarizing(model=model)
    # The first run estimated 900 tokens and recorded what the agent was asked. By its
    # own size the conversation of the replay is far below the trigger of 800.
    ctx.durable_store[manager.ESTIMATE_CALL_ID] = (
        900,
        True,
        "recorded instructions",
        "recorded request",
    )

    ctx.send(request())
    ctx.tool_result("c1", "1")
    ctx.tool_result("c2", "2")

    # The decision follows the record: round 1 is due, and the summarizer is asked
    # with the recorded text.
    assert len(model.summary_requests) == 1
    assert model.summary_requests[0][1].text.startswith(
        "The agent's instructions, for context only:\nrecorded instructions\n\n"
        "The request the agent is working on, for context only:\nrecorded request\n\n"
    )
    assert ctx.events()[0].details["estimated_input_tokens_before"] == 900
    assert model.estimates == 0
    assert model.renders == 0
    # Round 1 is a few tokens, fewer than the heading and the line take, so the line
    # is held and the model is sent both rounds.
    assert ctx.events()[0].details["pending_rounds"] == 1
    assert len(model.requests[2]) == 6
    assert ctx.durable_call_ids == ["chat", "chat", "estimate", "summarize", "chat"]


def test_lines_are_used_exactly_when_the_messages_are_smaller_with_them() -> None:
    # One round with a result of a given size, a second round, and the line "ok" for
    # the first. Around the size at which the heading and the line take what the round
    # takes, the model is sent the line exactly when the messages are smaller with it.
    seen = set()
    for chars in range(250, 361):
        model = ScriptedModel(
            call_tool("c1"), call_tool("c2"), call_tool("c3")
        ).summaries("ok")
        # A trigger of 10 tokens: every call with a round is over it.
        ctx = summarizing(model=model).with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_TRIGGER_RATIO, 0.01
        )
        chat_request = request()

        ctx.send(chat_request)
        ctx.tool_result("c1", "1" * chars)
        ctx.tool_result("c2", "2")

        stored = ctx.stored(chat_request.id)[:6]
        with_line = [
            stored[0],
            ChatMessage.user(
                [TextBlock(text="go"), TextBlock(text=summary_block(1, "ok"))]
            ),
            stored[4],
            stored[5],
        ]
        without_tokens = approximate(stored)
        with_tokens = approximate(with_line)
        third = model.requests[2]
        assert (len(third) == 4) == (with_tokens < without_tokens), (
            f"a result of {chars} characters: {with_tokens} tokens with the line, "
            f"{without_tokens} without"
        )
        assert approximate(third) == min(with_tokens, without_tokens)
        seen.add((with_tokens > without_tokens) - (with_tokens < without_tokens))
    assert seen == {-1, 0, 1}


def test_a_round_with_a_media_result_is_not_summarized() -> None:
    model = ScriptedModel(answer("final")).summaries(
        "notes on round 1", "notes on round 3"
    )
    ctx = summarizing(model=model)
    chart = ChatMessage.tool(
        ToolResultBlock(
            call_id="h2",
            blocks=[
                TextBlock(text="the chart of account 2"),
                ImageBlock.from_url("image/png", "https://example.org/2"),
            ],
        )
    )
    history = [
        ChatMessage.user("go"),
        call_tool("h1").message,
        result("h1", "1" * 2000),
        call_tool("h2").message,
        chart,
        call_tool("h3").message,
        result("h3", "3" * 2000),
        call_tool("h4").message,
        result("h4", "4"),
    ]

    ctx.send(ChatRequestEvent(model=MODEL, messages=history))

    # A summarizer is shown text only. Round 2 returned an image, so it is not shown
    # to the summarizer and stays in the model's input as it is, after the summary.
    assert len(model.summary_requests) == 2
    for asked in model.summary_requests:
        assert "Round 2:" not in asked[1].text
        assert "the chart" not in asked[1].text
    sent = model.requests[0]
    assert len(sent) == 5
    assert sent[0].blocks[1].text == (
        compaction.SUMMARY_SEPARATOR
        + compaction.summary_text(
            "Round 1: notes on round 1\nRound 3: notes on round 3"
        )
    )
    assert sent[2] == chart
    assert sent[3].tool_calls[0].call_id == "h4"
    details = ctx.events()[0].details
    assert details["rounds"] == 2
    assert details["summarized_rounds"] == 2
    assert "pending_rounds" not in details


def test_the_input_is_estimated_once_for_a_model_call_with_a_summary() -> None:
    model = CountingModel(call_tool("c1"), call_tool("c2"), answer("final")).summaries(
        "notes on round 1"
    )
    ctx = summarizing(model=model)

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    # With one round none is due for a line, so nothing is estimated.
    assert model.estimates == 0
    ctx.tool_result("c2", "2" * 2000)

    # The call with two rounds wrote a summary and sent it. A count of the provider's
    # own is asked for once: the input with the new line is not counted a second time.
    assert len(model.requests[2]) == 4
    assert model.estimates == 1


def test_a_summarizer_request_is_sized_with_the_correction_of_the_estimates() -> None:
    # The provider reports twice the approximate estimate.
    model = ScriptedModel(
        call_tool("c1"), call_tool("c2"), call_tool("c3"), call_tool("c4")
    ).reporting_usage_times(2)
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 2000)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    chat_request = request()

    ctx.send(chat_request)
    ctx.tool_result("c1", "1" * 4800)
    # Two rounds, about 1480 estimated tokens: under the trigger of 1600 as long as
    # the estimate is not corrected.
    ctx.tool_result("c2", "2" * 1000)
    assert model.summary_requests == []
    assert ctx.state(chat_request.id)["calibration"] == 2.0
    ctx.tool_result("c3", "3" * 1000)

    # Round 1 is about 1200 tokens by the approximation, which the provider counts as
    # 2400. The request to the summarizer may take 2000 of the provider's tokens, so
    # it is put together within 1000 of the approximation, and round 1 is cut to fit.
    assert len(model.summary_requests) > 0
    assert approximate(model.summary_requests[0]) <= 1000
    assert "characters truncated" in model.summary_requests[0][1].text
    assert "error" not in ctx.events()[0].details


class NoEstimateModel(ScriptedModel):
    """A chat model whose estimate returns nothing."""

    def estimate_input_tokens(
        self, messages: Sequence[ChatMessage], prompt_args: Any = None
    ) -> TokenEstimate:
        return None


def test_an_estimate_the_chat_model_does_not_return_is_replaced() -> None:
    model = NoEstimateModel(call_tool("c1"), call_tool("c2"), answer("final"))
    ctx = summarizing(model=model)

    ctx.send(request())
    ctx.tool_result("c1", "1")
    ctx.tool_result("c2", "2")

    # No count is not a failure of the request: the size of the request is used.
    assert ctx.chat_response().response.text == "final"
    assert ctx.estimates() == [
        manager.EstimateOutcome(approximate(model.requests[2]), approximate=True)
    ]


class InsertingModel(ScriptedModel):
    """A chat model that puts a system message into the list it is handed."""

    def prepare_request_messages(
        self, messages: Sequence[ChatMessage], prompt_args: Any = None
    ) -> Sequence[ChatMessage]:
        messages.insert(0, ChatMessage.system("Answer briefly."))
        return messages

    def estimate_input_tokens(
        self, messages: Sequence[ChatMessage], prompt_args: Any = None
    ) -> TokenEstimate:
        # As a chat model does whose connection counts tokens: it counts the request
        # it prepares.
        prepared = self.prepare_request_messages(messages, prompt_args)
        return TokenEstimate.exact(approximate(prepared))


def test_what_a_chat_model_does_with_the_list_it_is_handed_stays_with_it() -> None:
    def four_rounds(model: ScriptedModel) -> tuple[List[ChatMessage], List[bool]]:
        model.summaries("Account c1 holds 10.", "Account c2 holds 20.")
        ctx = summarizing(model=model)
        chat_request = request()
        ctx.send(chat_request)
        ctx.tool_result("c1", "1" * 2000)
        ctx.tool_result("c2", "2" * 2000)
        ctx.tool_result("c3", "3" * 2000)
        # A short round: the input is estimated for the call that follows it, and it
        # is under the trigger, so the call is sent the messages that were estimated.
        ctx.tool_result("c4", "4")
        approximate_estimates = [
            event.details["estimate_approximate"] for event in ctx.events()
        ]
        return ctx.stored(chat_request.id), approximate_estimates

    def rounds_asked(model: ScriptedModel) -> List[List[str]]:
        return [
            re.findall(r"(?m)^Round (\d+):$", asked[1].text)
            for asked in model.summary_requests
        ]

    script = [call_tool(f"c{number}") for number in range(1, 6)]
    plain = ScriptedModel(*script)
    inserting = InsertingModel(*script)
    stored, approximate_estimates = four_rounds(plain)
    assert approximate_estimates == [True, True]

    # The chat model's estimate and its request are prepared from lists of their own.
    # The message it puts into them reaches neither the stored conversation, whose
    # rounds the request is managed by, nor the messages the model is sent.
    assert four_rounds(inserting) == (stored, [False, False])
    assert len(plain.requests) == 5
    assert inserting.requests == plain.requests
    assert rounds_asked(inserting) == rounds_asked(plain) == [["1"], ["2"]]
    # The summarizer is shown it with the chat model's other instructions.
    assert "Answer briefly.\nYou are terse." in inserting.summary_requests[0][1].text


class MalformedEstimateModel(ScriptedModel):
    """A chat model whose estimate has a text where its flag belongs."""

    def estimate_input_tokens(
        self, messages: Sequence[ChatMessage], prompt_args: Any = None
    ) -> TokenEstimate:
        return TokenEstimate(tokens=1, is_approximate="false")


def test_an_estimate_with_a_flag_that_is_not_a_boolean_is_replaced() -> None:
    model = MalformedEstimateModel(call_tool("c1"), call_tool("c2"), answer("final"))
    ctx = summarizing(model=model)

    ctx.send(request())
    ctx.tool_result("c1", "1")
    ctx.tool_result("c2", "2")

    # Such an estimate cannot be built: a text such as "false" would count as true
    # where the estimate is corrected. The size of the request is used.
    assert ctx.chat_response().response.text == "final"
    assert ctx.estimates() == [
        manager.EstimateOutcome(approximate(model.requests[2]), approximate=True)
    ]


class InstructedModel(ScriptedModel):
    """A chat model that gives its provider instructions as a parameter."""

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        return {"instructions": "Audit the accounts."}


def test_what_the_summarizer_is_told_about_the_request_is_cut_with_the_correction() -> (
    None
):
    # The provider reports four times the approximate estimate.
    model = ScriptedModel(
        call_tool("c1"), call_tool("c2"), call_tool("c3"), call_tool("c4")
    ).reporting_usage_times(4)
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 4000)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    # Instructions and a request of 400 tokens each.
    chat_request = ChatRequestEvent(
        model=MODEL,
        messages=[ChatMessage.system("s" * 1600), ChatMessage.user("u" * 1600)],
    )

    ctx.send(chat_request)
    ctx.tool_result("c1", "1" * 400)
    ctx.tool_result("c2", "2" * 400)
    assert model.summary_requests == []
    assert ctx.state(chat_request.id)["calibration"] == 4.0
    ctx.tool_result("c3", "3" * 400)

    # A summarizer request may take 4000 of the provider's tokens, which are 1000 of
    # the approximation. The instructions and the request text are each cut to an
    # eighth of that, so the rounds still fit beside them.
    assert len(model.summary_requests) > 0
    for asked in model.summary_requests:
        assert approximate(asked) <= 1000
    first = model.summary_requests[0][1].text
    assert "characters truncated" in first
    assert "Round 1:\n" in first
    assert "error" not in ctx.events()[0].details


class InstructedInsideAdditionalKwargs(ScriptedModel):
    """A chat model that gives its provider instructions inside additional_kwargs."""

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        return {"additional_kwargs": {"instructions": "Audit the accounts."}}


def test_instructions_inside_additional_kwargs_are_shown_to_the_summarizer() -> None:
    model = InstructedInsideAdditionalKwargs(
        call_tool("c1"), call_tool("c2"), answer("final")
    ).summaries("notes on round 1")
    ctx = summarizing(model=model)

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    assert len(model.summary_requests) == 1
    assert model.summary_requests[0][1].text.startswith(
        "The agent's instructions, for context only:\n"
        "Audit the accounts.\nYou are terse.\n\n"
    )
    assert ctx.estimates()[0].instructions == "Audit the accounts.\nYou are terse."


class LongInstructionsModel(ScriptedModel):
    """A chat model that gives its provider 1600 characters of instructions."""

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        return {"instructions": "i" * 1600}


def test_an_estimate_the_chat_model_cannot_give_still_counts_provider_instructions() -> (
    None
):
    # This chat model has no connection to estimate with, and gives its provider 1600
    # characters of instructions as a parameter: about 400 tokens the messages do not
    # show.
    model = LongInstructionsModel(
        call_tool("c1"), call_tool("c2"), call_tool("c3")
    ).summaries("notes on round 1")
    ctx = summarizing(model=model)
    chat_request = ChatRequestEvent(model=MODEL, messages=[ChatMessage.user("go")])

    ctx.send(chat_request)
    ctx.tool_result("c1", "1" * 1000)
    ctx.tool_result("c2", "2" * 1000)

    # Two rounds are about 530 tokens, under the trigger of 800. With the instructions
    # the provider is sent about 930, so round 1 is summarized.
    two_rounds = ctx.stored(chat_request.id)[:5]
    assert approximate(two_rounds) < 800
    assert len(model.summary_requests) == 1
    assert ctx.events()[0].details["estimated_input_tokens_before"] == approximate(
        [ChatMessage.system("i" * 1600), *two_rounds]
    )


def test_tools_that_cannot_be_measured_are_left_out_of_the_fallback_estimate() -> None:
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), call_tool("c3")).summaries(
        "notes on round 1"
    )
    # A bound tool whose description cannot be read.
    broken = MagicMock(spec=Tool)
    type(broken).metadata = PropertyMock(side_effect=RuntimeError("no metadata"))
    model.tools = [broken]
    ctx = summarizing(model=model)
    chat_request = request()

    ctx.send(chat_request)
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    # This chat model has no connection to estimate with, so the approximation is
    # used. It counts the request without the tools, and the request goes on.
    assert ctx.estimates()[0].tokens == approximate(ctx.stored(chat_request.id)[:6])
    assert len(model.summary_requests) == 1
    assert ctx.state(chat_request.id)["summary"] == "Round 1: notes on round 1"


class FaultyEstimateModel(ScriptedModel):
    """A chat model whose estimate raises what it is given."""

    _fault: Exception = PrivateAttr()

    def failing_with(self, fault: Exception) -> "FaultyEstimateModel":
        self._fault = fault
        return self

    def estimate_input_tokens(
        self, messages: Sequence[ChatMessage], prompt_args: Any = None
    ) -> TokenEstimate:
        raise self._fault


def _java_failure(class_name: str) -> RuntimeError:
    """Build the error Pemja raises for a Java call that failed with ``class_name``."""

    def java_class(name: str | None) -> Any:
        if name is None:
            return None
        return type(
            "PyJObject",
            (),
            {
                "__module__": "pemja",
                "getName": lambda self: name,
                "getSuperclass": lambda self: None,
            },
        )()

    throwable = type(
        "PyJObject",
        (),
        {
            "__module__": "pemja",
            "getClass": lambda self: java_class(class_name),
            "getCause": lambda self: None,
        },
    )()
    return RuntimeError(throwable)


@pytest.mark.parametrize(
    ("fault", "raised_as"),
    [
        (UncapturableException("the framework failed"), UncapturableException),
        (_java_failure("java.lang.InterruptedException"), RuntimeError),
    ],
    ids=["framework fault", "java interruption"],
)
def test_what_is_no_provider_failure_is_not_taken_for_a_failed_summary(
    fault: Exception, raised_as: type
) -> None:
    # In a summarizer call.
    summarizer = ScriptedModel(
        call_tool("c1"), call_tool("c2"), answer("final")
    ).summaries(fault)
    ctx = summarizing(model=summarizer)
    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)

    with pytest.raises(raised_as) as raised:
        ctx.tool_result("c2", "2" * 2000)
    assert raised.value is fault or raised.value.__cause__ is fault
    # Nothing is reported as a summary that could not be written, nothing is recorded
    # as the outcome of the call, and no model call follows.
    assert ctx.events() == []
    assert [call for call, _ in ctx.durable_results if call == "summarize"] == []
    assert len(summarizer.requests) == 2

    # In an estimate.
    estimating = FaultyEstimateModel(
        call_tool("c1"), call_tool("c2"), answer("final")
    ).failing_with(fault)
    other = summarizing(model=estimating)
    other.send(request())
    other.tool_result("c1", "1" * 2000)

    with pytest.raises(raised_as) as raised:
        other.tool_result("c2", "2" * 2000)
    assert raised.value is fault or raised.value.__cause__ is fault
    assert len(estimating.requests) == 2


class FaultySchemaCheck(ScriptedModel):
    """A chat model whose check of an output schema fails with a given error."""

    _fault: Exception | None = PrivateAttr(default=None)

    def failing_with(self, fault: Exception) -> "FaultySchemaCheck":
        self._fault = fault
        return self

    def will_apply_native_structured_output(self, output_schema: Any) -> bool:
        raise self._fault


@pytest.mark.parametrize(
    ("fault", "raised_as"),
    [
        (UncapturableException("the framework failed"), UncapturableException),
        (_java_failure("java.lang.InterruptedException"), RuntimeError),
    ],
    ids=["framework fault", "java interruption"],
)
def test_what_is_no_provider_failure_is_not_taken_for_a_schema_that_cannot_be_carried(
    fault: Exception, raised_as: type
) -> None:
    model = FaultySchemaCheck(answer("final")).failing_with(fault)
    ctx = summarizing(model=model)

    with pytest.raises(raised_as) as raised:
        ctx.send(
            ChatRequestEvent(
                model=MODEL,
                messages=two_long_rounds(),
                output_schema=OutputSchema(output_schema=Balance),
            )
        )
    assert raised.value is fault or raised.value.__cause__ is fault
    # Nothing is recorded in place of an estimate, and no call follows.
    assert ctx.durable_call_ids == []
    assert model.requests == []


def test_a_java_call_that_was_cancelled_is_a_failed_call_like_any_other() -> None:
    # Durable execution keeps a framework fault and a Java interruption out of its
    # record, and records every other failure of a call. A cancellation raised for a
    # cancelled Java call would be recorded too, and raised again after every
    # recovery. So the call is a failed one, as it is for the chat call.
    cancelled = _java_failure("java.util.concurrent.CancellationException")
    # In a summarizer call: the summary is skipped and the request goes on.
    summarizer = ScriptedModel(
        call_tool("c1"), call_tool("c2"), answer("final")
    ).summaries(cancelled)
    ctx = summarizing(model=summarizer)
    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    assert len(summarizer.requests) == 3
    assert ctx.events()[0].details["rounds"] == 0
    assert "error" in ctx.events()[0].details
    recorded = [result for call, result in ctx.durable_results if call == "summarize"]
    assert len(recorded) == 1
    assert recorded[0][0] is None

    # In an estimate: the approximation is used.
    estimating = FaultyEstimateModel(
        call_tool("c1"), call_tool("c2"), answer("final")
    ).failing_with(cancelled)
    other = summarizing(model=estimating)
    other.send(request())
    other.tool_result("c1", "1" * 2000)
    other.tool_result("c2", "2" * 2000)

    assert len(estimating.requests) == 3
    assert other.estimates()[-1].approximate is True

    # In the check of an output schema: the chat model cannot be asked.
    checking = FaultySchemaCheck(answer("final")).failing_with(cancelled)
    third = summarizing(model=checking)
    third.send(
        ChatRequestEvent(
            model=MODEL,
            messages=two_long_rounds(),
            output_schema=OutputSchema(output_schema=Balance),
        )
    )

    assert third.chat_response().is_failed
    assert third.estimates() == [manager.EstimateOutcome(-1, approximate=False)]


def test_the_summarizer_is_shown_the_instructions_given_to_the_provider() -> None:
    model = InstructedModel(
        call_tool("c1"), call_tool("c2"), answer("final")
    ).summaries("notes on round 1")
    ctx = summarizing(model=model)

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    assert len(model.summary_requests) == 1
    assert model.summary_requests[0][1].text.startswith(
        "The agent's instructions, for context only:\n"
        "Audit the accounts.\nYou are terse.\n\n"
        "The request the agent is working on, for context only:\ngo\n\n"
    )
    assert ctx.estimates()[0].instructions == "Audit the accounts.\nYou are terse."


def test_a_budget_without_room_for_one_line_says_so() -> None:
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final"))
    # Half of the budget is 170 tokens. The heading of the summary takes 77 of them and
    # a line may take 95.
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 340)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    # No line was written yet, so the summary is not full: the budget is too small.
    assert model.summary_requests == []
    assert ctx.events()[0].details["error"] == (
        "the summary has no room for a line: it may take 170 estimated tokens with "
        "max_input_tokens=340, and its heading takes 77 of them"
    )


def test_notes_that_are_full_fail_the_step_before_a_round_is_rendered() -> None:
    # A budget that no summarizer request fits, and notes that have no room for a
    # line: with their heading they take more than the 150 tokens they may take.
    model = ScriptedModel(answer("final"))
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 300)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    history = [
        ChatMessage.user("go"),
        call_tool("h1").message,
        result("h1", "1"),
        call_tool("h2").message,
        result("h2", "2" * 2000),
        call_tool("h3").message,
        result("h3", "3" * 2000),
    ]
    chat_request = ChatRequestEvent(model=MODEL, messages=history)
    ctx.memory.set(
        manager.state_path(chat_request.id),
        {"summary": "Round 1: " + "n" * 391, "written_rounds": 1},
    )

    ctx.send(chat_request)

    # Round 2 is due. Without room for its line nothing is put together for the
    # summarizer, so the step fails for the notes and not for a round that fits no
    # request.
    assert model.summary_requests == []
    details = ctx.events()[0].details
    assert details["error"].startswith(
        "the summary is full: it takes 177 of the 150 estimated tokens"
    )
    assert details["rounds"] == 0
    assert details["summarized_rounds"] == 1
    assert "pending_rounds" not in details


def test_lines_that_are_held_are_given_up_when_the_notes_have_no_room() -> None:
    # The summarizer writes lines as long as a line may be.
    model = ScriptedModel(
        *[call_tool(f"c{number}") for number in range(1, 8)]
    ).summaries(*["n" * 400] * 5)
    ctx = summarizing(model=model)
    # The request alone takes about 900 of the 1000 tokens of the budget, so every
    # call with a round that is due is over the trigger.
    chat_request = ChatRequestEvent(
        model=MODEL, messages=[ChatMessage.user("u" * 3600)]
    )

    ctx.send(chat_request)
    # Four short rounds. Their lines are longer than they are, so the lines are held,
    # and with them the notes have no room for another line.
    for number in range(1, 5):
        ctx.tool_result(f"c{number}", "ok")
    ctx.tool_result("c5", "5" * 10_000)
    state = ctx.state(chat_request.id)
    assert state["pending_summary"].count("\n") == 3
    assert "summary" not in state

    # Round 5 is long and due. The lines that are held were never sent: they are given
    # up, so the line of round 5 has room, and their rounds stay in the messages.
    ctx.tool_result("c6", "ok")
    details = ctx.events()[-1].details
    assert "error" not in details
    assert details["rounds"] == 1
    assert details["summarized_rounds"] == 1
    assert "pending_rounds" not in details
    state = ctx.state(chat_request.id)
    assert state["summary"].startswith("Round 5: ")
    assert "\n" not in state["summary"]
    assert "pending_summary" not in state
    assert state["written_rounds"] == 5
    sent = model.requests[6]
    assert len(sent) == 1 + 4 * 2 + 2
    assert [tool_text(message) for message in sent[2:9:2]] == ["ok"] * 4
    assert sent[9].tool_calls[0].call_id == "c6"


def test_lines_that_are_given_up_are_gone_when_the_step_fails_after_that() -> None:
    # The summarizer writes lines as long as a line may be, and then it is down.
    model = ScriptedModel(
        *[call_tool(f"c{number}") for number in range(1, 8)]
    ).summaries(*["n" * 400] * 4, RuntimeError("down"))
    ctx = summarizing(model=model)
    chat_request = ChatRequestEvent(
        model=MODEL, messages=[ChatMessage.user("u" * 3600)]
    )

    ctx.send(chat_request)
    # Four short rounds, whose lines are held and leave the notes no room.
    for number in range(1, 5):
        ctx.tool_result(f"c{number}", "ok")
    ctx.tool_result("c5", "5" * 10_000)
    assert ctx.state(chat_request.id)["pending_summary"].count("\n") == 3

    # Round 5 is due. The lines that are held are given up for the room of its line,
    # and the summarizer does not write it.
    ctx.tool_result("c6", "ok")
    details = ctx.events()[-1].details
    assert details["error"] == "RuntimeError: down"
    assert details["rounds"] == 0
    assert "pending_rounds" not in details
    state = ctx.state(chat_request.id)
    assert "pending_summary" not in state
    assert "summary" not in state
    assert state["written_rounds"] == 4


def test_lines_written_in_a_step_stay_when_the_notes_have_no_room_for_more() -> None:
    # The summarizer writes lines as long as a line may be.
    model = ScriptedModel(call_tool("c7")).summaries(*["n" * 400] * 5)
    ctx = summarizing(model=model)
    # Six rounds arrive with the request, and five of them are due at once. Each is
    # a summarizer request of its own.
    history = [ChatMessage.user("go")]
    for number in range(1, 7):
        history.append(call_tool(f"h{number}").message)
        history.append(result(f"h{number}", str(number) * 2000))
    chat_request = ChatRequestEvent(model=MODEL, messages=history)

    ctx.send(chat_request)

    # The notes have room for four lines. The step ends there: the four lines were
    # written in it, so they are kept, and the model is sent them.
    assert len(model.summary_requests) == 4
    details = ctx.events()[0].details
    assert details["error"].startswith(
        "the summary is full: it takes 446 of the 500 estimated tokens"
    )
    assert details["rounds"] == 4
    assert details["summarized_rounds"] == 4
    assert "pending_rounds" not in details
    state = ctx.state(chat_request.id)
    assert [line[:9] for line in state["summary"].split("\n")] == [
        f"Round {number}: " for number in range(1, 5)
    ]
    assert "pending_summary" not in state
    assert state["written_rounds"] == 4
    sent = model.requests[0]
    assert len(sent) == 1 + 2 * 2
    assert [tool_text(message) for message in sent[2::2]] == ["5" * 2000, "6" * 2000]


def test_only_lines_that_an_earlier_step_left_held_are_given_up_for_room() -> None:
    # The summarizer writes lines as long as a line may be, and is down for one call.
    model = ScriptedModel(
        *[call_tool(f"c{number}") for number in range(1, 8)]
    ).summaries(*["n" * 400] * 3, RuntimeError("down"), *["n" * 400] * 2)
    ctx = summarizing(model=model)
    # The request alone takes about 900 of the 1000 tokens of the budget, so every
    # call with a round that is due is over the trigger.
    chat_request = ChatRequestEvent(
        model=MODEL, messages=[ChatMessage.user("u" * 3600)]
    )

    ctx.send(chat_request)
    # Three short rounds. Their lines are longer than they are, so the lines are
    # held, and with them the notes have room for one more line.
    for number in range(1, 4):
        ctx.tool_result(f"c{number}", "ok")
    ctx.tool_result("c4", "4" * 2000)
    assert ctx.state(chat_request.id)["pending_summary"].count("\n") == 2
    # The step for round 4 fails, so the next step finds two long rounds due.
    ctx.tool_result("c5", "5" * 2000)
    assert ctx.events()[-1].details["error"] == "RuntimeError: down"

    ctx.tool_result("c6", "ok")

    # The line of round 4 takes the room that was left. For the line of round 5 the
    # lines of rounds 1 to 3 are given up, which an earlier step left held. The
    # line of round 4 was written in this step and stays.
    assert len(model.summary_requests) == 6
    details = ctx.events()[-1].details
    assert "error" not in details
    assert details["rounds"] == 2
    assert details["summarized_rounds"] == 2
    assert "pending_rounds" not in details
    state = ctx.state(chat_request.id)
    assert [line[:9] for line in state["summary"].split("\n")] == [
        "Round 4: ",
        "Round 5: ",
    ]
    assert "pending_summary" not in state
    assert state["written_rounds"] == 5
    sent = model.requests[6]
    assert len(sent) == 1 + 3 * 2 + 2
    assert [tool_text(message) for message in sent[2:7:2]] == ["ok"] * 3
    assert sent[7].tool_calls[0].call_id == "c6"


def test_short_rounds_get_no_line_while_held_lines_fill_the_notes() -> None:
    # The summarizer writes lines as long as a line may be.
    model = ScriptedModel(
        *[call_tool(f"c{number}") for number in range(1, 14)]
    ).summaries(*["n" * 400] * 5)
    ctx = summarizing(model=model)
    # The request alone takes about 900 of the 1000 tokens of the budget, so every
    # call with a round that is due is over the trigger.
    chat_request = ChatRequestEvent(
        model=MODEL, messages=[ChatMessage.user("u" * 3600)]
    )

    ctx.send(chat_request)
    # Four short rounds. Their lines are longer than they are, so the lines are held,
    # and with them the notes have no room for another line.
    for number in range(1, 6):
        ctx.tool_result(f"c{number}", "ok")
    assert len(model.summary_requests) == 4
    estimates = ctx.durable_call_ids.count("estimate")

    # More short rounds. A line for one of them would be held like the others, and
    # asking for it would give those up for nothing: the rounds get no line, and
    # nothing is estimated or asked for them.
    for number in range(6, 10):
        ctx.tool_result(f"c{number}", "ok")
    assert len(model.summary_requests) == 4
    assert ctx.durable_call_ids.count("estimate") == estimates
    state = ctx.state(chat_request.id)
    assert state["written_rounds"] == 8
    assert state["pending_summary"].count("\n") == 3

    # A long round is due after them. The held lines are given up for its line.
    ctx.tool_result("c10", "x" * 10_000)
    ctx.tool_result("c11", "ok")
    assert len(model.summary_requests) == 5
    state = ctx.state(chat_request.id)
    assert state["summary"].startswith("Round 10: ")
    assert "\n" not in state["summary"]
    assert "pending_summary" not in state


def test_a_round_that_stays_is_not_counted_among_the_rounds_that_got_a_line() -> None:
    model = ScriptedModel(answer("final")).summaries(
        "notes on round 2", "notes on round 3"
    )
    ctx = summarizing(model=model)
    # The first round of the request loads a skill; rounds 2 and 3 are due with it.
    history = [
        ChatMessage.user("go"),
        ChatMessage.assistant(
            [ToolCallBlock(call_id="s1", name="load_skill", input={"name": "audit"})]
        ),
        result("s1", "i" * 500),
        call_tool("h2").message,
        result("h2", "2" * 1500),
        call_tool("h3").message,
        result("h3", "3" * 1500),
        call_tool("h4").message,
        result("h4", "4"),
    ]

    ctx.send(ChatRequestEvent(model=MODEL, messages=history))

    # Three rounds were gone through. Two of them got a line, and only they are
    # counted.
    assert len(model.summary_requests) == 2
    assert len(ctx.events()) == 1
    assert ctx.events()[0].details["rounds"] == 2
    assert ctx.events()[0].details["summarized_rounds"] == 2
    assert len(model.requests[0]) == 5


def test_rounds_that_were_passed_over_are_not_reported_as_held() -> None:
    load_skill = ChatResult(
        message=ChatMessage.assistant(
            [ToolCallBlock(call_id="s1", name="load_skill", input={"name": "audit"})]
        )
    )
    model = ScriptedModel(
        load_skill, call_tool("c2"), call_tool("c3"), answer("final")
    ).summaries(RuntimeError("summarizer is down"))
    ctx = summarizing(model=model)

    ctx.send(request())
    ctx.tool_result("s1", "i" * 2500)
    ctx.tool_result("c2", "2" * 2000)
    ctx.tool_result("c3", "3" * 2000)

    # Round 1 loads a skill and was passed over, and the line of round 2 could not be
    # written. No line is held, so no held round is reported.
    assert len(ctx.events()) == 1
    details = ctx.events()[0].details
    assert details["rounds"] == 0
    assert details["summarized_rounds"] == 0
    assert "error" in details
    assert "pending_rounds" not in details


def test_an_estimate_is_made_where_it_is_not_recorded() -> None:
    # The counterpart of the test above: without a record the chat model is asked.
    model = CountingModel(call_tool("c1"), call_tool("c2"), answer("final"))
    ctx = summarizing(model=model)

    ctx.send(request())
    ctx.tool_result("c1", "1")
    # No round is due for a line yet.
    assert model.estimates == 0
    ctx.tool_result("c2", "2")

    assert model.estimates == 1
    assert ctx.durable_call_ids == ["chat", "chat", "estimate", "chat"]


def test_a_report_that_changes_nothing_does_not_write_the_state() -> None:
    # A provider that reports the same small input for every call.
    model = ScriptedModel(
        call_tool("c1"), call_tool("c2"), call_tool("c3"), call_tool("c4")
    ).reporting_usage_of(10)
    ctx = (
        Context(model=model)
        .with_option(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 100000)
        .with_option(
            AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
        )
    )
    chat_request = request()
    path = manager.state_path(chat_request.id)

    ctx.send(chat_request)
    ctx.tool_result("c1", "1")
    # The first call whose input is estimated is the one with two rounds.
    ctx.tool_result("c2", "2")
    assert ctx.state(chat_request.id) == {
        "calibration": 1.0,
        "calibration_tokens": 10,
        "calibration_at_largest": 1.0,
    }
    written = ctx.memory.get(path)

    # The next call reports the same: the factor and its measure stay, and so does
    # the stored state.
    ctx.tool_result("c3", "3")
    assert len(ctx.estimates()) == 2
    assert ctx.memory.get(path) is written


def test_a_summarizer_that_returns_no_chat_result_is_a_failed_summary() -> None:
    # What a provider that is not migrated returns: a message instead of a result.
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final")).summaries(
        ChatMessage.assistant("Round 1: notes on round 1")
    )
    ctx = summarizing(model=model)

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    # The summary fails inside its durable call, which records why. The request goes
    # on: the model is sent the whole conversation and answers.
    assert len(model.requests[2]) == 6
    assert ctx.events()[0].details["rounds"] == 0
    assert ctx.events()[0].details["error"] == (
        "TypeError: Chat model provider must return ChatResult; this provider has "
        "not been migrated."
    )
    assert [result for call, result in ctx.durable_results if call == "summarize"] == [
        (None, ctx.events()[0].details["error"])
    ]
    assert ctx.chat_response().response.text == "final"
    assert ctx.durable_call_ids == ["chat", "chat", "estimate", "summarize", "chat"]


class SilentSummarizer(ScriptedModel):
    """A chat model whose provider returns nothing for a tool-free request."""

    def chat_without_tools(self, messages: Sequence[ChatMessage]) -> ChatResult:
        self.summary_requests.append(list(messages))
        return None


def test_a_summarizer_that_returns_nothing_is_a_failed_summary() -> None:
    model = SilentSummarizer(call_tool("c1"), call_tool("c2"), answer("final"))
    ctx = summarizing(model=model)

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    # No response is recorded as none. The step reports it with the same text as on
    # the Java side, and the request goes on with the whole conversation.
    assert len(model.requests[2]) == 6
    assert ctx.events()[0].details["error"] == "the chat model returned no response"
    assert [result for call, result in ctx.durable_results if call == "summarize"] == [
        (None, None)
    ]
    assert ctx.chat_response().response.text == "final"


# ── Replay ──────────────────────────────────────────────────────────────


def test_a_recorded_summary_is_reused_without_calling_the_summarizer() -> None:
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final"))
    ctx = summarizing(model=model)
    ctx.durable_store[manager.SUMMARIZE_CALL_ID] = (
        answer("Round 1: recorded notes"),
        None,
    )

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    assert model.summary_requests == []
    assert model.requests[2][1].blocks[1].text == summary_block(1, "recorded notes")
    assert ctx.durable_call_ids == ["chat", "chat", "estimate", "summarize", "chat"]


def test_a_recorded_summary_is_reused_when_the_summarizer_cannot_be_resolved() -> None:
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final"))
    ctx = summarizing(model=model).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL, "small"
    )
    ctx.unresolvable.add("small")
    ctx.durable_store[manager.SUMMARIZE_CALL_ID] = (
        answer("Round 1: recorded notes"),
        None,
    )

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    assert model.requests[2][1].blocks[1].text == summary_block(1, "recorded notes")
    assert ctx.events()[0].details["rounds"] == 1
    assert "error" not in ctx.events()[0].details
    assert ctx.durable_call_ids == ["chat", "chat", "estimate", "summarize", "chat"]


def test_a_recorded_summary_failure_is_reused_without_calling_the_summarizer() -> None:
    # The first run recorded that the summarizer could not be used. By the replay it
    # can.
    model = ScriptedModel(call_tool("c1"), call_tool("c2"), answer("final")).summaries(
        "notes the replay must not ask for"
    )
    ctx = summarizing(model=model)
    recorded = "chat model 'model' cannot be used: resource not found: model"
    ctx.durable_store[manager.SUMMARIZE_CALL_ID] = (None, recorded)

    ctx.send(request())
    ctx.tool_result("c1", "1" * 2000)
    ctx.tool_result("c2", "2" * 2000)

    # The replay sends what the first run sent: the whole conversation.
    assert model.summary_requests == []
    assert len(model.requests[2]) == 6
    assert ctx.events()[0].details["error"] == recorded
    assert ctx.events()[0].details["rounds"] == 0
    assert ctx.durable_call_ids == ["chat", "chat", "estimate", "summarize", "chat"]


# ── Cleanup and routing ─────────────────────────────────────────────────


def test_what_is_kept_for_a_request_is_removed_when_the_request_fails() -> None:
    model = ScriptedModel(call_tool("c1"), RuntimeError("model is down"))
    ctx = Context(model=model)
    chat_request = request(ContextManagementPolicy(tool_result_max_chars=100))

    ctx.send(chat_request)
    assert ctx.state(chat_request.id) is not None
    ctx.tool_result("c1", "a" * 1000)

    assert ctx.chat_response().is_failed
    assert ctx.state(chat_request.id) is None


def test_the_state_of_a_request_has_a_memory_path_of_its_own() -> None:
    model = ScriptedModel(call_tool("a1"), answer("b is done"), answer("a is done"))
    ctx = Context(model=model)
    cut = ContextManagementPolicy(tool_result_max_chars=100)
    a = request(cut)
    b = request(ContextManagementPolicy(tool_result_max_chars=200))

    # Request a waits for its tool while request b of the same key runs and completes.
    ctx.send(a)
    ctx.send(b)

    # Each request keeps its state at a path of its own: no value holds the state of
    # both, so what one request writes or clears cannot replace the state of the
    # other.
    assert sorted(ctx.state_paths()) == sorted(
        [manager.state_path(a.id), manager.state_path(b.id)]
    )
    assert ctx.state(b.id) is None
    assert ctx.state(a.id) == {"policy": cut.to_map()}

    # Request a still has its own policy, and its state goes when it completes.
    ctx.tool_result("a1", "a" * 1000)
    sent = tool_text(model.requests[2][3])
    assert "characters truncated" in sent
    assert len(sent) <= 100
    assert ctx.state(a.id) is None


class Balance(BaseModel):
    """The answer a request asks for."""

    amount: int


class NoNativeSchema(ScriptedModel):
    """A chat model that cannot be asked for an output schema.

    As a setup with the NATIVE strategy whose connection cannot carry the schema.
    """

    def will_apply_native_structured_output(self, output_schema: Any) -> bool:
        msg = "the connection cannot apply the output schema natively"
        raise ValueError(msg)


class SchemaInThePrompt(ScriptedModel):
    """A chat model that describes an output schema in its prompt."""

    def will_apply_native_structured_output(self, output_schema: Any) -> bool:
        return False


def two_long_rounds() -> List[ChatMessage]:
    """A conversation of two rounds that is over the trigger of ``summarizing``."""
    return [
        ChatMessage.system("You are terse."),
        ChatMessage.user("go"),
        call_tool("c1").message,
        result("c1", "1" * 2000),
        call_tool("c2").message,
        result("c2", "2" * 2000),
    ]


def test_a_request_whose_schema_no_chat_model_can_carry_is_not_summarized() -> None:
    model = NoNativeSchema(answer("final"))
    ctx = summarizing(model=model).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 1900
    )
    chat_request = ChatRequestEvent(
        model=MODEL,
        messages=two_long_rounds(),
        output_schema=OutputSchema(output_schema=Balance),
    )

    ctx.send(chat_request)

    # The request fails before its model call, as it does without a policy. No
    # provider is asked anything for it: the input is not estimated, which is
    # recorded in place of an estimate, and nothing is summarized. Its results are
    # cut as for any request, and nothing is kept.
    assert ctx.chat_response().is_failed
    assert model.requests == []
    assert model.summary_requests == []
    assert ctx.durable_call_ids == ["estimate"]
    assert ctx.estimates() == [manager.EstimateOutcome(-1, approximate=False)]
    assert [event.step for event in ctx.events()] == ["truncate"]
    assert ctx.state(chat_request.id) is None


def test_a_request_whose_chat_model_is_not_declared_is_not_summarized() -> None:
    small = ScriptedModel()
    ctx = summarizing(small=small).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_MODEL, "small"
    )
    ctx.unresolvable.add(MODEL)

    ctx.send(ChatRequestEvent(model=MODEL, messages=two_long_rounds()))

    # The summarizer of the policy resolves, but the request has no chat model to go
    # to: it fails, and the summarizer is not asked.
    assert ctx.chat_response().is_failed
    assert small.summary_requests == []
    assert ctx.durable_call_ids == ["estimate"]
    assert ctx.estimates() == [manager.EstimateOutcome(-1, approximate=False)]
    assert ctx.events() == []


def test_an_estimate_that_was_not_made_is_reused_after_a_recovery() -> None:
    # The first run recorded that no chat model could be asked for the request. By
    # the replay one can.
    model = SchemaInThePrompt(answer('{"amount": 30}')).summaries(
        "notes the replay must not ask for"
    )
    ctx = summarizing(model=model)
    ctx.durable_store[manager.ESTIMATE_CALL_ID] = (-1, False, None, None)

    ctx.send(
        ChatRequestEvent(
            model=MODEL,
            messages=two_long_rounds(),
            output_schema=OutputSchema(output_schema=Balance),
        )
    )

    # The replay makes the durable calls of the first run, in their order: nothing
    # is summarized, and the model is sent the rounds as they are.
    assert not ctx.chat_response().is_failed
    assert model.summary_requests == []
    assert len(model.requests[0]) == 6
    assert ctx.durable_call_ids == ["estimate", "chat"]
    assert ctx.events() == []


def test_a_chat_model_that_does_not_resolve_at_first_is_still_sent_managed_messages() -> (
    None
):
    model = SchemaInThePrompt(answer('{"amount": 30}'))
    ctx = summarizing(model=model).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 100
    )
    resolve = ctx.ctx.get_resource.side_effect
    failed = []

    def failing_once(name: str, kind: ResourceType) -> Any:
        if kind == ResourceType.CHAT_MODEL and not failed:
            failed.append(name)
            msg = "the resource is not ready"
            raise RuntimeError(msg)
        return resolve(name, kind)

    ctx.ctx.get_resource.side_effect = failing_once

    ctx.send(
        ChatRequestEvent(
            model=MODEL,
            messages=two_long_rounds(),
            output_schema=OutputSchema(output_schema=Balance),
        )
    )

    # The chat model does not resolve when it is first looked up, for the estimate,
    # and does after that. Whatever the lookups give, the results are cut: the model
    # call is made with the messages the policy asks for.
    assert failed == [MODEL]
    assert not ctx.chat_response().is_failed
    assert "characters truncated" in tool_text(model.requests[0][3])
    assert "characters truncated" in tool_text(model.requests[0][5])
    assert ctx.durable_call_ids == ["estimate", "chat"]
    assert [event.step for event in ctx.events()] == ["truncate"]


def test_no_chat_model_is_looked_up_or_asked_for_a_schema_while_no_estimate_is_due() -> (
    None
):
    model = CountingSchemaChecks(answer('{"amount": 30}'))
    # Results are cut, and there is no budget: nothing is estimated.
    ctx = Context(model=model).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 100
    )
    resolve = ctx.ctx.get_resource.side_effect
    looked_up = []

    def counting(name: str, kind: ResourceType) -> Any:
        if kind == ResourceType.CHAT_MODEL:
            looked_up.append(name)
        return resolve(name, kind)

    ctx.ctx.get_resource.side_effect = counting

    ctx.send(
        ChatRequestEvent(
            model=MODEL,
            messages=two_long_rounds(),
            output_schema=OutputSchema(output_schema=Balance),
        )
    )

    # The attempt looks its chat model up and asks it for the schema, once each.
    assert not ctx.chat_response().is_failed
    assert "characters truncated" in tool_text(model.requests[0][3])
    assert looked_up == [MODEL]
    assert model._asked == 1


def test_a_result_that_another_message_separates_from_its_call_keeps_its_call() -> None:
    model = ScriptedModel(answer("final")).summaries("notes that leave the call out")
    ctx = summarizing(model=model)
    messages = [
        ChatMessage.system("You are terse."),
        ChatMessage.user("go"),
        call_tool("c1").message,
        ChatMessage.system("Keep the answer short."),
        result("c1", "1" * 2000),
        call_tool("c2").message,
        result("c2", "2" * 2000),
    ]

    ctx.send(ChatRequestEvent(model=MODEL, messages=messages))

    # The first round is due for its line, and its result follows a system message.
    # A provider that is given system messages apart from the conversation takes
    # such a history. The round stays, so the result still has its call: nothing is
    # summarized, and the model is sent the messages as they are.
    assert ctx.chat_response().response.text == "final"
    assert model.summary_requests == []
    assert model.requests[0] == messages
    assert ctx.durable_call_ids == ["chat"]
    assert ctx.events() == []


def test_results_that_follow_several_assistant_messages_keep_their_calls() -> None:
    model = ScriptedModel(answer("final")).summaries("notes that part a call")
    ctx = summarizing(model=model)
    messages = [
        ChatMessage.system("You are terse."),
        ChatMessage.user("go"),
        call_tool("c1").message,
        call_tool("c2").message,
        result("c1", "1" * 2000),
        result("c2", "2" * 2000),
        call_tool("c3").message,
        result("c3", "3" * 2000),
    ]

    ctx.send(ChatRequestEvent(model=MODEL, messages=messages))

    # The first two rounds are due for their lines. The result of the first call
    # stands in the round of the second, so each of the two rounds holds half of a
    # call and its result: both stay, nothing is summarized, and the model is sent
    # the messages as they are.
    assert ctx.chat_response().response.text == "final"
    assert model.summary_requests == []
    assert model.requests[0] == messages
    assert ctx.durable_call_ids == ["chat"]
    assert ctx.events() == []


class CountingSchemaChecks(SchemaInThePrompt):
    """Counts how often it is asked whether it can carry an output schema."""

    _asked: int = PrivateAttr(default=0)

    def will_apply_native_structured_output(self, output_schema: Any) -> bool:
        self._asked += 1
        return super().will_apply_native_structured_output(output_schema)


def test_without_a_policy_a_chat_model_is_asked_for_the_schema_by_its_attempt_only() -> (
    None
):
    model = CountingSchemaChecks(answer('{"amount": 30}'))
    ctx = Context(model=model)

    ctx.send(
        ChatRequestEvent(
            model=MODEL,
            messages=two_long_rounds(),
            output_schema=OutputSchema(output_schema=Balance),
        )
    )

    # The request has rounds and no policy: nothing asks whether it can be attempted.
    assert not ctx.chat_response().is_failed
    assert model._asked == 1


def test_a_request_that_a_fallback_candidate_can_answer_is_still_summarized() -> None:
    small = NoNativeSchema().summaries("Account c1 holds 10.")
    big = SchemaInThePrompt(answer('{"amount": 30}'))
    ctx = routed(fallback=True, small=small, big=big)
    ctx.options.pop(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS.get_key()
    )
    ctx.with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 1000
    ).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1
    )

    ctx.send(
        ChatRequestEvent(
            model="router",
            messages=two_long_rounds(),
            output_schema=OutputSchema(output_schema=Balance),
        )
    )

    # The selected model cannot be asked for the schema, the other candidate can: the
    # policy is applied for the selected model, which also writes the summary, and
    # the candidate that answers is sent the managed messages.
    assert not ctx.chat_response().is_failed
    assert small.requests == []
    assert len(small.summary_requests) == 1
    assert len(big.requests) == 1
    assert big.requests[0][1].blocks[1].text == summary_block(1, "Account c1 holds 10.")
    assert ctx.durable_call_ids == [
        "route:router",
        "estimate",
        "summarize",
        "chat:router:big",
    ]


def routed(*, fallback: bool, **models: Any) -> Context:
    """A router over small and big that starts on small."""
    builder = (
        ModelRouter.of("small", "big")
        .strategy(Strategies.rules({"big": "sql"}))
        .default_model("small")
        .fallback(enabled=fallback)
    )
    router = ModelRouter(**builder.build().arguments)
    return Context(router, **models).with_option(
        AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 100
    )


def test_every_candidate_of_a_routed_request_is_sent_the_same_managed_messages() -> (
    None
):
    small = ScriptedModel(RuntimeError("small is down"))
    big = ScriptedModel(answer("final from big"))
    ctx = routed(fallback=True, small=small, big=big)
    history = [
        ChatMessage.user("go"),
        call_tool("h1").message,
        ChatMessage.tool(
            ToolResultBlock(call_id="h1", blocks=[TextBlock(text="a" * 1000)])
        ),
    ]

    ctx.send(ChatRequestEvent(model="router", messages=history))

    # The policy is applied once, for the selected model, and the fallback candidate
    # is sent the same messages.
    assert ctx.chat_response().response.text == "final from big"
    assert "characters truncated" in tool_text(small.requests[0][2])
    assert big.requests[0] == small.requests[0]
    assert len(ctx.events()) == 1
    assert ctx.events()[0].model == "small"


def test_the_tool_rounds_of_a_routed_request_use_the_policy_too() -> None:
    small = ScriptedModel(call_tool("c1"), answer("final from small"))
    big = ScriptedModel()
    ctx = routed(fallback=False, small=small, big=big)

    ctx.send(ChatRequestEvent(model="router", messages=[ChatMessage.user("go")]))
    ctx.tool_result("c1", "a" * 1000)

    # The tool round stays on the model that answered; the policy applies there.
    assert ctx.chat_response().response.text == "final from small"
    assert "characters truncated" in tool_text(small.requests[1][2])
    assert big.requests == []
    assert len(ctx.events()) == 1
    assert ctx.events()[0].model == "small"
