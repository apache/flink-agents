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
"""Tests for retry behavior in chat_model_action."""

import asyncio
import time
from typing import Any, Sequence
from unittest.mock import MagicMock, call
from uuid import uuid4

import pytest
from pydantic import BaseModel

from flink_agents.api.agents.agent import STRUCTURED_OUTPUT
from flink_agents.api.agents.react_agent import OutputSchema
from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.core_options import (
    AgentExecutionOptions,
    ErrorHandlingStrategy,
)
from flink_agents.api.events.chat_event import ChatResponseEvent
from flink_agents.api.events.tool_event import ToolResponseEvent
from flink_agents.api.metric_group import Counter, MetricGroup
from flink_agents.api.trace import (
    ExecutionEntityTypes,
    ExecutionProblemCategories,
    ExecutionReporter,
)
from flink_agents.plan.actions.chat_model_action import (
    chat,
    process_chat_request_or_tool_response,
)

# ============================================================================
# Mock infrastructure
# ============================================================================


class _MockCounter(Counter):
    """Mock counter that tracks inc calls."""

    def __init__(self) -> None:
        self._count = 0

    def inc(self, n: int = 1) -> None:
        self._count += n

    def dec(self, n: int = 1) -> None:
        self._count -= n

    def get_count(self) -> int:
        return self._count


class _MockMetricGroup(MetricGroup):
    """Mock metric group that tracks sub-groups and counters."""

    def __init__(self) -> None:
        self._sub_groups: dict[str, _MockMetricGroup] = {}
        self._counters: dict[str, _MockCounter] = {}

    def get_sub_group(self, name: str, value: str | None = None) -> "_MockMetricGroup":
        key = f"{name}={value}" if value is not None else name
        if key not in self._sub_groups:
            self._sub_groups[key] = _MockMetricGroup()
        return self._sub_groups[key]

    def get_counter(self, name: str) -> _MockCounter:
        if name not in self._counters:
            self._counters[name] = _MockCounter()
        return self._counters[name]

    def get_meter(self, name: str) -> Any:
        return MagicMock()

    def get_gauge(self, name: str) -> Any:
        return MagicMock()

    def get_histogram(self, name: str, window_size: int = 100) -> Any:
        return MagicMock()


class _MockMemoryObject:
    """Simple dict-backed memory object for testing."""

    def __init__(self) -> None:
        self._store: dict[str, Any] = {}

    def get(self, path: str) -> Any:
        return self._store.get(path)

    def set(self, path: str, value: Any) -> None:
        self._store[path] = value


class _StructuredResult(BaseModel):
    result: int


def _create_mock_runner_context(
    chat_model: Any,
    max_retries: int = 3,
    retry_wait_interval_sec: int = 1,
) -> tuple[MagicMock, list, _MockMetricGroup, _MockMemoryObject]:
    """Create a mock RunnerContext with configurable retry settings.

    Returns (ctx, sent_events, action_metric_group, sensory_memory).
    """
    sent_events = []
    metric_group = _MockMetricGroup()
    sensory_memory = _MockMemoryObject()

    config = MagicMock()
    option_values = {
        id(AgentExecutionOptions.ERROR_HANDLING_STRATEGY): ErrorHandlingStrategy.RETRY,
        id(AgentExecutionOptions.MAX_RETRIES): max_retries,
        id(AgentExecutionOptions.RETRY_WAIT_INTERVAL): retry_wait_interval_sec,
        id(AgentExecutionOptions.CHAT_ASYNC): False,
    }
    config.get = MagicMock(
        side_effect=lambda option: option_values.get(
            id(option), option.get_default_value()
        )
    )

    ctx = MagicMock(spec=ExecutionReporter)
    ctx.config = config
    ctx.sensory_memory = sensory_memory
    ctx.action_metric_group = metric_group
    ctx.send_event = MagicMock(side_effect=lambda e: sent_events.append(e))
    ctx.get_resource = MagicMock(return_value=chat_model)
    ctx.durable_execute = MagicMock(
        side_effect=lambda fn, *args, **kwargs: fn(*args, **kwargs)
    )

    return ctx, sent_events, metric_group, sensory_memory


# ============================================================================
# Tests
# ============================================================================


class TestChatModelActionRetry:
    """Tests for retry behavior in chat()."""

    def test_chat_succeeds_without_retry(self) -> None:
        """No retry needed: retry_count=0, total_retry_wait_sec=0, no metrics."""
        chat_model = MagicMock()
        chat_model.chat = MagicMock(
            return_value=ChatMessage(role=MessageRole.ASSISTANT, content="hello")
        )

        ctx, sent_events, metric_group, _ = _create_mock_runner_context(chat_model)
        request_id = uuid4()

        asyncio.run(
            chat(
                request_id,
                chat_model.connection,
                [ChatMessage(role=MessageRole.USER, content="hi")],
                {},
                None,
                ctx,
            )
        )

        assert len(sent_events) == 1
        event = sent_events[0]
        assert isinstance(event, ChatResponseEvent)
        assert event.retry_count == 0
        assert event.total_retry_wait_sec == 0

        # No retry metrics should be recorded
        assert len(metric_group._sub_groups) == 0
        ctx.report_execution_started.assert_called_once_with(
            ExecutionEntityTypes.LLM, chat_model.connection, {}
        )
        ctx.report_execution_succeeded.assert_called_once_with(
            ExecutionEntityTypes.LLM, chat_model.connection, {}
        )
        ctx.report_execution_failed.assert_not_called()

    def test_chat_retries_with_exponential_backoff(self) -> None:
        """Fail once then succeed: 1s interval, 1 retry -> wait 1s (1 * 2^0)."""
        call_count = 0

        def mock_chat(messages: Sequence[ChatMessage], **kwargs: Any) -> ChatMessage:
            nonlocal call_count
            call_count += 1
            if call_count <= 1:
                err_msg = "transient error"
                raise RuntimeError(err_msg)
            return ChatMessage(role=MessageRole.ASSISTANT, content="success")

        chat_model = MagicMock()
        chat_model.chat = mock_chat

        ctx, sent_events, metric_group, _ = _create_mock_runner_context(
            chat_model, max_retries=3, retry_wait_interval_sec=1
        )
        request_id = uuid4()

        start = time.monotonic()
        asyncio.run(
            chat(
                request_id,
                "test-model",
                [ChatMessage(role=MessageRole.USER, content="hi")],
                {},
                None,
                ctx,
            )
        )
        elapsed = time.monotonic() - start

        assert len(sent_events) == 1
        event = sent_events[0]
        assert isinstance(event, ChatResponseEvent)
        assert event.retry_count == 1
        # 1s config. Exponential: 1s (2^0) = 1s total
        assert event.total_retry_wait_sec == 1
        assert elapsed >= 1.0

        # Verify metrics recorded under connection name
        model_group = metric_group.get_sub_group("model", chat_model.connection)
        assert model_group.get_counter("retryCount").get_count() == 1
        assert model_group.get_counter("retryWaitSec").get_count() == 1
        assert ctx.report_execution_started.call_count == 2
        ctx.report_execution_failed.assert_called_once()
        failed_args = ctx.report_execution_failed.call_args.args
        assert failed_args[0] == ExecutionEntityTypes.LLM
        assert failed_args[-1] == ExecutionProblemCategories.MODEL_CALL_FAILED
        ctx.report_execution_succeeded.assert_called_once_with(
            ExecutionEntityTypes.LLM, "test-model", {}
        )

    def test_chat_exhausts_retries_and_raises(self) -> None:
        """All retries exhausted: exception raised, no event sent."""
        chat_model = MagicMock()
        chat_model.chat = MagicMock(side_effect=RuntimeError("persistent error"))

        ctx, sent_events, _, _ = _create_mock_runner_context(
            chat_model, max_retries=2, retry_wait_interval_sec=0
        )
        request_id = uuid4()

        with pytest.raises(RuntimeError, match="persistent error"):
            asyncio.run(
                chat(
                    request_id,
                    "test-model",
                    [ChatMessage(role=MessageRole.USER, content="hi")],
                    {},
                    None,
                    ctx,
                )
            )

        assert len(sent_events) == 0
        assert ctx.report_execution_started.call_count == 3
        assert ctx.report_execution_failed.call_count == 3
        for failed_call in ctx.report_execution_failed.call_args_list:
            assert failed_call.args[0] == ExecutionEntityTypes.LLM
            assert failed_call.args[-1] == ExecutionProblemCategories.MODEL_CALL_FAILED
        ctx.report_execution_succeeded.assert_not_called()

    def test_structured_output_parse_error_retries_without_failing_llm(
        self,
    ) -> None:
        chat_model = MagicMock()
        chat_model.chat = MagicMock(
            side_effect=[
                ChatMessage(role=MessageRole.ASSISTANT, content="not-json"),
                ChatMessage(role=MessageRole.ASSISTANT, content='{"result": 42}'),
            ]
        )

        ctx, sent_events, _, _ = _create_mock_runner_context(
            chat_model, max_retries=1, retry_wait_interval_sec=0
        )

        asyncio.run(
            chat(
                uuid4(),
                "test-model",
                [ChatMessage(role=MessageRole.USER, content="hi")],
                {},
                OutputSchema(output_schema=_StructuredResult),
                ctx,
            )
        )

        assert chat_model.chat.call_count == 2
        assert len(sent_events) == 1
        response = sent_events[0].response
        assert response.extra_args[STRUCTURED_OUTPUT].result == 42

        ctx.report_execution_failed.assert_called_once()
        failed_args = ctx.report_execution_failed.call_args.args
        assert failed_args[0] == ExecutionEntityTypes.PARSER
        assert failed_args[1] == STRUCTURED_OUTPUT
        assert failed_args[-1] == ExecutionProblemCategories.MODEL_OUTPUT_PARSE_ERROR

        assert ctx.report_execution_started.call_count == 4
        assert ctx.report_execution_succeeded.call_count == 3
        assert (
            ctx.report_execution_succeeded.call_args_list.count(
                call(ExecutionEntityTypes.LLM, "test-model", {})
            )
            == 2
        )


class TestChatResponseEventRetryFields:
    """Tests for ChatResponseEvent retry fields."""

    def test_default_retry_fields(self) -> None:
        """Default construction has retry_count=0, total_retry_wait_sec=0."""
        event = ChatResponseEvent(
            request_id=uuid4(),
            response=ChatMessage(role=MessageRole.ASSISTANT, content="test"),
        )
        assert event.retry_count == 0
        assert event.total_retry_wait_sec == 0

    def test_with_retry_fields(self) -> None:
        """Full construction carries retry info."""
        event = ChatResponseEvent(
            request_id=uuid4(),
            response=ChatMessage(role=MessageRole.ASSISTANT, content="test"),
            retry_count=5,
            total_retry_wait_sec=31,
        )
        assert event.retry_count == 5
        assert event.total_retry_wait_sec == 31


class TestRetryWaitIntervalConfig:
    """Tests for RETRY_WAIT_INTERVAL configuration."""

    def test_default_value(self) -> None:
        """Default value is 1 second."""
        assert AgentExecutionOptions.RETRY_WAIT_INTERVAL.get_default_value() == 1


class TestProcessToolResponsePromptArgsForwarding:
    """Locks the contract that `_process_tool_response` forwards the saved
    `prompt_args` from the tool-request-event context into the round-2 call
    to `chat_model.chat(...)`.
    """

    def test_forwards_saved_prompt_args_to_chat(self) -> None:
        initial_request_id = uuid4()
        tool_request_event_id = uuid4()
        tool_call_id = "call-1"
        saved_prompt_args = {"k": "v"}

        captured_prompt_args: list[dict] = []

        def mock_chat(messages: Sequence[ChatMessage], **kwargs: Any) -> ChatMessage:
            captured_prompt_args.append(kwargs.get("prompt_args"))
            return ChatMessage(role=MessageRole.ASSISTANT, content="done")

        chat_model = MagicMock()
        chat_model.chat = mock_chat

        ctx, sent_events, _, sensory_memory = _create_mock_runner_context(
            chat_model, max_retries=0, retry_wait_interval_sec=0
        )

        # Pre-seed the tool-request-event context with saved prompt args so
        # _process_tool_response can look them up.
        sensory_memory.set(
            "_TOOL_REQUEST_EVENT_CONTEXT",
            {
                str(tool_request_event_id): {
                    "initial_request_id": str(initial_request_id),
                    "model": "test-model",
                    "prompt_args": saved_prompt_args,
                    "output_schema": None,
                }
            },
        )

        # Pre-seed the tool-call context with prior messages so
        # _update_tool_call_context can extend them with the tool response.
        sensory_memory.set(
            "_TOOL_CALL_CONTEXT",
            {
                str(initial_request_id): [
                    ChatMessage(role=MessageRole.USER, content="hi").model_dump(
                        mode="json"
                    )
                ]
            },
        )

        tool_response_event = ToolResponseEvent(
            request_id=tool_request_event_id,
            responses={tool_call_id: "42"},
            external_ids={},
        )

        asyncio.run(process_chat_request_or_tool_response(tool_response_event, ctx))

        assert len(captured_prompt_args) == 1
        assert captured_prompt_args[0] == saved_prompt_args
        assert len(sent_events) == 1
        assert isinstance(sent_events[0], ChatResponseEvent)

    def test_failed_tool_response_uses_generic_response_message(self) -> None:
        initial_request_id = uuid4()
        tool_request_event_id = uuid4()
        tool_call_id = "call-1"

        captured_messages: list[Sequence[ChatMessage]] = []

        def mock_chat(messages: Sequence[ChatMessage], **kwargs: Any) -> ChatMessage:
            captured_messages.append(messages)
            return ChatMessage(role=MessageRole.ASSISTANT, content="done")

        chat_model = MagicMock()
        chat_model.chat = mock_chat

        ctx, _, _, sensory_memory = _create_mock_runner_context(
            chat_model, max_retries=0, retry_wait_interval_sec=0
        )
        sensory_memory.set(
            "_TOOL_REQUEST_EVENT_CONTEXT",
            {
                str(tool_request_event_id): {
                    "initial_request_id": str(initial_request_id),
                    "model": "test-model",
                    "prompt_args": {},
                    "output_schema": None,
                }
            },
        )
        sensory_memory.set(
            "_TOOL_CALL_CONTEXT",
            {
                str(initial_request_id): [
                    ChatMessage(role=MessageRole.USER, content="hi").model_dump(
                        mode="json"
                    )
                ]
            },
        )

        tool_response_event = ToolResponseEvent(
            request_id=tool_request_event_id,
            responses={tool_call_id: "Tool `query_order` execute failed."},
            external_ids={},
            success={tool_call_id: False},
            error={
                tool_call_id: "Missing config for injected tool parameter: tenant_id"
            },
        )

        asyncio.run(process_chat_request_or_tool_response(tool_response_event, ctx))

        assert captured_messages
        tool_message = captured_messages[0][-1]
        assert tool_message.role == MessageRole.TOOL
        assert tool_message.content == "Tool `query_order` execute failed."
