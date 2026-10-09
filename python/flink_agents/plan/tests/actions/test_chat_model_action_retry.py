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
from pydantic import BaseModel, Field

from flink_agents.api.agents.agent import STRUCTURED_OUTPUT
from flink_agents.api.agents.react_agent import OutputSchema
from flink_agents.api.chat_message import ChatMessage, ImageBlock, MessageRole
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelConnection,
    BaseChatModelSetup,
)
from flink_agents.api.core_options import (
    AgentExecutionOptions,
)
from flink_agents.api.events.chat_event import ChatResponseEvent
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent
from flink_agents.api.metric_group import Counter, MetricGroup
from flink_agents.api.prompts.prompt import Prompt
from flink_agents.api.tools.tool import Tool
from flink_agents.api.trace import (
    ExecutionEntityTypes,
    ExecutionProblemCategories,
    ExecutionReporter,
    LLMExecutionMetadataKeys,
)
from flink_agents.plan.actions.chat_model_action import (
    chat,
    process_chat_request_or_tool_response,
)

_LLM_METADATA = {LLMExecutionMetadataKeys.MODEL: "configured-model"}

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
    *,
    chat_async: bool = False,
) -> tuple[MagicMock, list, _MockMetricGroup, _MockMemoryObject]:
    """Create a mock RunnerContext with configurable retry settings.

    Returns (ctx, sent_events, action_metric_group, sensory_memory).
    """
    sent_events = []
    metric_group = _MockMetricGroup()
    sensory_memory = _MockMemoryObject()
    chat_model.model = "configured-model"
    # An unstubbed MagicMock answers the gate with a truthy mock, which would send
    # every schema-carrying test down the native finalization path. Tests that want
    # that path override this after the helper returns.
    if isinstance(chat_model, MagicMock):
        chat_model.will_apply_native_structured_output = MagicMock(return_value=False)
        chat_model.prepare_request_messages = MagicMock(
            side_effect=lambda messages, prompt_args=None: list(messages)
        )

    config = MagicMock()
    option_values = {
        id(AgentExecutionOptions.MAX_RETRIES): max_retries,
        id(AgentExecutionOptions.RETRY_WAIT_INTERVAL): retry_wait_interval_sec,
        id(AgentExecutionOptions.CHAT_ASYNC): chat_async,
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
        side_effect=lambda fn, *args, durable_id=None, **kwargs: fn(*args, **kwargs)
    )

    async def _dispatch_async(
        fn: Any, *args: Any, durable_id: str | None = None, **kwargs: Any
    ) -> Any:
        return fn(*args, **kwargs)

    ctx.durable_execute_async = MagicMock(side_effect=_dispatch_async)

    return ctx, sent_events, metric_group, sensory_memory


# ============================================================================
# Tests
# ============================================================================


class TestChatModelActionRetry:
    """Tests for retry behavior in chat()."""

    def test_failure_debug_log_contains_only_message_count(self, caplog) -> None:
        model = MagicMock()
        model.chat.side_effect = RuntimeError("provider unavailable")
        ctx, events, _, _ = _create_mock_runner_context(model, max_retries=0)
        messages = [
            ChatMessage.user(
                [
                    ImageBlock.from_base64("image/png", "c2VjcmV0"),
                    ImageBlock.from_url(
                        "image/png", "https://u:password@example.org/x?token=secret"
                    ),
                ]
            )
        ]
        with caplog.at_level(
            "DEBUG", logger="flink_agents.plan.actions.chat_model_action"
        ):
            asyncio.run(chat(uuid4(), "test-model", messages, {}, None, ctx))
        assert events[0].is_failed
        assert "1 input messages" in caplog.text
        assert "c2VjcmV0" not in caplog.text
        assert "password" not in caplog.text
        assert "token=secret" not in caplog.text

    @pytest.mark.parametrize(
        "failure",
        [
            RuntimeError("state failed"),
            InterruptedError("cancelled"),
            asyncio.CancelledError(),
        ],
    )
    def test_durable_failure_propagates_without_response(self, failure) -> None:
        model = MagicMock()
        ctx, events, _, _ = _create_mock_runner_context(model)
        ctx.durable_execute.side_effect = failure
        with pytest.raises(type(failure)):
            asyncio.run(chat(uuid4(), "test-model", [], {}, None, ctx))
        assert not events
        assert ctx.durable_execute.call_count == 1
        model.chat.assert_not_called()

    @pytest.mark.parametrize("provider_fails", [False, True])
    def test_terminal_delivery_failure_propagates(self, provider_fails: bool) -> None:
        model = MagicMock()
        model.chat.return_value = ChatMessage.of(role=MessageRole.ASSISTANT, content="ok")
        if provider_fails:
            model.chat.side_effect = ValueError("provider failure")
        ctx, events, _, _ = _create_mock_runner_context(model, max_retries=0)
        ctx.send_event.side_effect = RuntimeError("delivery failed")
        with pytest.raises(RuntimeError, match="delivery failed"):
            asyncio.run(chat(uuid4(), "test-model", [], {}, None, ctx))
        assert ctx.send_event.call_count == 1
        assert not events

    def test_provider_cancellation_propagates(self) -> None:
        from concurrent.futures import CancelledError

        model = MagicMock()
        model.chat.side_effect = CancelledError("cancelled")
        ctx, events, _, _ = _create_mock_runner_context(model)
        with pytest.raises(CancelledError):
            asyncio.run(chat(uuid4(), "test-model", [], {}, None, ctx))
        assert not events
        assert model.chat.call_count == 1

    def test_resource_failure_returns_correlated_failure(self) -> None:
        ctx, events, _, _ = _create_mock_runner_context(MagicMock())
        ctx.get_resource.side_effect = ValueError("unknown model")
        request_id = uuid4()
        asyncio.run(chat(request_id, "test-model", [], {}, None, ctx))
        assert len(events) == 1
        assert events[0].request_id == request_id
        assert events[0].error == "ValueError: unknown model"

    def test_chat_succeeds_without_retry(self) -> None:
        """No retry needed: retry_count=0, total_retry_wait_sec=0, no metrics."""
        chat_model = MagicMock()
        chat_model.chat = MagicMock(
            return_value=ChatMessage.of(MessageRole.ASSISTANT, "hello")
        )

        ctx, sent_events, metric_group, _ = _create_mock_runner_context(chat_model)
        request_id = uuid4()

        asyncio.run(
            chat(
                request_id,
                chat_model.connection,
                [ChatMessage.of(MessageRole.USER, "hi")],
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
            ExecutionEntityTypes.LLM,
            chat_model.connection,
            _LLM_METADATA,
        )
        ctx.report_execution_succeeded.assert_called_once_with(
            ExecutionEntityTypes.LLM,
            chat_model.connection,
            _LLM_METADATA,
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
            return ChatMessage.of(MessageRole.ASSISTANT, "success")

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
                [ChatMessage.of(MessageRole.USER, "hi")],
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

        # Retry health belongs to the ChatModel resource.
        model_resource_group = metric_group.get_sub_group(
            "model_resource", "test-model"
        )
        assert model_resource_group.get_counter("retryCount").get_count() == 1
        assert model_resource_group.get_counter("retryWaitSec").get_count() == 1
        assert ctx.report_execution_started.call_count == 2
        ctx.report_execution_failed.assert_called_once()
        failed_args = ctx.report_execution_failed.call_args.args
        assert failed_args[0] == ExecutionEntityTypes.LLM
        assert failed_args[2] == _LLM_METADATA
        assert failed_args[-1] == ExecutionProblemCategories.MODEL_CALL_FAILED
        ctx.report_execution_succeeded.assert_called_once_with(
            ExecutionEntityTypes.LLM,
            "test-model",
            _LLM_METADATA,
        )

    def test_chat_exhausts_retries_and_returns_failure(self) -> None:
        """All retries exhausted: one correlated failure event is sent."""
        chat_model = MagicMock()
        chat_model.chat = MagicMock(side_effect=RuntimeError("persistent error"))

        ctx, sent_events, metric_group, _ = _create_mock_runner_context(
            chat_model, max_retries=2, retry_wait_interval_sec=0
        )
        request_id = uuid4()

        asyncio.run(
            chat(
                request_id,
                "test-model",
                [ChatMessage.of(role=MessageRole.USER, content="hi")],
                {},
                None,
                ctx,
            )
        )

        assert sent_events[0].is_failed
        assert "persistent error" in sent_events[0].error
        assert len(sent_events) == 1
        assert sent_events[0].is_failed
        assert ctx.report_execution_started.call_count == 3
        assert ctx.report_execution_failed.call_count == 3
        for failed_call in ctx.report_execution_failed.call_args_list:
            assert failed_call.args[0] == ExecutionEntityTypes.LLM
            assert failed_call.args[2] == _LLM_METADATA
            assert failed_call.args[-1] == ExecutionProblemCategories.MODEL_CALL_FAILED
        ctx.report_execution_succeeded.assert_not_called()
        model_resource_group = metric_group.get_sub_group(
            "model_resource", "test-model"
        )
        assert model_resource_group.get_counter("retryCount").get_count() == 2
        assert model_resource_group.get_counter("retryWaitSec").get_count() == 0

    def test_structured_output_parse_error_retries_without_failing_llm(
        self,
    ) -> None:
        chat_model = MagicMock()
        chat_model.chat = MagicMock(
            side_effect=[
                ChatMessage.of(MessageRole.ASSISTANT, "not-json"),
                ChatMessage.of(MessageRole.ASSISTANT, '{"result": 42}'),
            ]
        )

        ctx, sent_events, _, _ = _create_mock_runner_context(
            chat_model, max_retries=1, retry_wait_interval_sec=0
        )

        asyncio.run(
            chat(
                uuid4(),
                "test-model",
                [ChatMessage.of(MessageRole.USER, "hi")],
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
                call(
                    ExecutionEntityTypes.LLM,
                    "test-model",
                    _LLM_METADATA,
                )
            )
            == 2
        )


class TestChatModelActionFinishReason:
    """Tests for the finish-reason gate on the common chat-response path."""

    def _run(self, ctx, output_schema=None) -> None:
        asyncio.run(
            chat(
                uuid4(),
                "test-model",
                [ChatMessage.of(role=MessageRole.USER, content="hi")],
                {},
                output_schema,
                ctx,
            )
        )

    def test_truncated_text_response_rejected(self) -> None:
        chat_model = MagicMock()
        chat_model.chat = MagicMock(
            return_value=ChatMessage.of(
                role=MessageRole.ASSISTANT,
                content="partial answ",
                extra_args={"finish_reason": "length"},
            )
        )
        ctx, sent_events, _, _ = _create_mock_runner_context(
            chat_model, max_retries=0, retry_wait_interval_sec=0
        )

        self._run(ctx)
        assert "truncat" in sent_events[0].error.lower()
        assert "token" in sent_events[0].error.lower()
        assert len(sent_events) == 1
        assert sent_events[0].is_failed

    def test_content_filtered_text_response_rejected(self) -> None:
        # Matches a word unique to the filtering message. Both messages
        # interpolate the finish reason, so "content_filter" appears in either
        # one and cannot tell them apart.
        chat_model = MagicMock()
        chat_model.chat = MagicMock(
            return_value=ChatMessage.of(
                role=MessageRole.ASSISTANT,
                content="",
                extra_args={"finish_reason": "content_filter"},
            )
        )
        ctx, sent_events, _, _ = _create_mock_runner_context(
            chat_model, max_retries=0, retry_wait_interval_sec=0
        )

        self._run(ctx)
        assert "withheld" in sent_events[0].error.lower()

        assert len(sent_events) == 1
        assert sent_events[0].is_failed

    def test_truncated_tool_call_response_rejected_before_tool_dispatch(self) -> None:
        chat_model = MagicMock()
        chat_model.chat = MagicMock(
            return_value=ChatMessage.of(
                role=MessageRole.ASSISTANT,
                content="",
                tool_calls=[
                    {
                        "id": "call-1",
                        "function": {"name": "f", "arguments": ""},
                    }
                ],
                extra_args={"finish_reason": "length"},
            )
        )
        ctx, sent_events, _, _ = _create_mock_runner_context(
            chat_model, max_retries=0, retry_wait_interval_sec=0
        )

        self._run(ctx)
        assert "truncat" in sent_events[0].error.lower()

        # A truncated tool call carries arguments the model never finished
        # writing, so no ToolRequestEvent may leave the action.
        assert len(sent_events) == 1
        assert sent_events[0].is_failed

    @pytest.mark.parametrize(
        "extra_args",
        [
            {"finish_reason": "stop"},
            {"finish_reason": "tool_calls"},
            {"finish_reason": "some_vendor_reason"},
            {},
        ],
        ids=["stop", "tool_calls", "unrecognized", "absent"],
    )
    def test_accepted_finish_reason_reaches_the_response_event(
        self, extra_args: dict
    ) -> None:
        chat_model = MagicMock()
        chat_model.chat = MagicMock(
            return_value=ChatMessage.of(
                role=MessageRole.ASSISTANT,
                content="hello",
                extra_args=extra_args,
            )
        )
        ctx, sent_events, _, _ = _create_mock_runner_context(
            chat_model, max_retries=0, retry_wait_interval_sec=0
        )

        self._run(ctx)

        assert len(sent_events) == 1
        assert isinstance(sent_events[0], ChatResponseEvent)
        assert sent_events[0].response.text == "hello"

    def test_accepted_finish_reason_dispatches_tool_request_event(self) -> None:
        # A response carrying tool calls passes the same finish-reason gate as a
        # text response, so an accepted reason must reach tool dispatch.
        tool_calls = [{"id": "call-1", "function": {"name": "f", "arguments": {}}}]
        chat_model = MagicMock()
        chat_model.chat = MagicMock(
            return_value=ChatMessage.of(
                role=MessageRole.ASSISTANT,
                content="",
                tool_calls=tool_calls,
                extra_args={"finish_reason": "tool_calls"},
            )
        )
        ctx, sent_events, _, _ = _create_mock_runner_context(
            chat_model, max_retries=0, retry_wait_interval_sec=0
        )

        self._run(ctx)

        assert len(sent_events) == 1
        assert isinstance(sent_events[0], ToolRequestEvent)
        assert sent_events[0].tool_calls == tool_calls

    def test_default_retry_budget_returns_failed_response(self) -> None:
        # The default retry budget makes one attempt and rejects truncated content.
        chat_model = MagicMock()
        chat_model.chat = MagicMock(
            return_value=ChatMessage.of(
                role=MessageRole.ASSISTANT,
                content="partial answ",
                extra_args={"finish_reason": "length"},
            )
        )
        ctx, sent_events, _, _ = _create_mock_runner_context(
            chat_model,
            max_retries=AgentExecutionOptions.MAX_RETRIES.get_default_value(),
            retry_wait_interval_sec=0,
        )

        self._run(ctx)

        chat_model.chat.assert_called_once()
        assert AgentExecutionOptions.MAX_RETRIES.get_default_value() == 0
        assert len(sent_events) == 1
        assert sent_events[0].is_failed

    @pytest.mark.parametrize("finish_reason", ["length", "content_filter"])
    def test_rejected_finish_reason_skips_structured_output(
        self, finish_reason: str
    ) -> None:
        chat_model = MagicMock()
        chat_model.chat = MagicMock(
            return_value=ChatMessage.of(
                role=MessageRole.ASSISTANT,
                content='{"result": 42}',
                extra_args={
                    "finish_reason": finish_reason,
                    "model_name": "provider-model",
                    "promptTokens": 100,
                    "completionTokens": 50,
                },
            )
        )
        ctx, sent_events, metric_group, _ = _create_mock_runner_context(
            chat_model, max_retries=0, retry_wait_interval_sec=0
        )

        self._run(ctx, OutputSchema(output_schema=_StructuredResult))

        # The model call itself succeeded and spent its full token budget, so
        # both must be recorded before the response is rejected.
        ctx.report_execution_succeeded.assert_called_once_with(
            ExecutionEntityTypes.LLM, "test-model", _LLM_METADATA
        )
        chat_model._record_token_metrics.assert_called_once_with(
            "provider-model", 100, 50, metric_group
        )
        # The parse is never attempted, so nothing about it is reported and no
        # response leaves the action.
        ctx.report_execution_started.assert_called_once_with(
            ExecutionEntityTypes.LLM, "test-model", _LLM_METADATA
        )
        ctx.report_execution_failed.assert_not_called()
        assert len(sent_events) == 1
        assert sent_events[0].is_failed


class TestChatResponseEventRetryFields:
    """Tests for ChatResponseEvent retry fields."""

    def test_default_retry_fields(self) -> None:
        """Default construction has retry_count=0, total_retry_wait_sec=0."""
        event = ChatResponseEvent.success(
            request_id=uuid4(),
            response=ChatMessage.of(MessageRole.ASSISTANT, "test"),
        )
        assert event.retry_count == 0
        assert event.total_retry_wait_sec == 0

    def test_with_retry_fields(self) -> None:
        """Full construction carries retry info."""
        event = ChatResponseEvent.success(
            request_id=uuid4(),
            response=ChatMessage.of(MessageRole.ASSISTANT, "test"),
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
            return ChatMessage.of(MessageRole.ASSISTANT, "done")

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
                    ChatMessage.of(MessageRole.USER, "hi").model_dump(mode="json")
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
            return ChatMessage.of(MessageRole.ASSISTANT, "done")

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
                    ChatMessage.of(MessageRole.USER, "hi").model_dump(mode="json")
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
        assert tool_message.text == "Tool `query_order` execute failed."


# Spelled out rather than imported from the action, so the assertion pins the exact
# words a provider receives.
_FINALIZE_DIRECTIVE_TEXT = (
    "Convert the previous assistant response into the required structured output"
    " format. Preserve its meaning and do not add or infer any new information."
)
_LLM_SPAN = call(ExecutionEntityTypes.LLM, "test-model", _LLM_METADATA)


def _assistant(content: str, **extra_args: Any) -> ChatMessage:
    return ChatMessage.of(MessageRole.ASSISTANT, content, extra_args=extra_args)


def _native_chat_model(
    loop_responses: Any = None, final_responses: Any = None
) -> MagicMock:
    """A chat model whose loop answer is prose and whose finalization is JSON."""
    chat_model = MagicMock()
    chat_model.chat = MagicMock(
        side_effect=loop_responses or [_assistant("the answer is 42")]
    )
    chat_model.chat_structured = MagicMock(
        side_effect=final_responses or [_assistant('{"result": 42}')]
    )
    return chat_model


def _native_context(
    chat_model: MagicMock, max_retries: int = 0, **kwargs: Any
) -> tuple:
    ctx = _create_mock_runner_context(
        chat_model, max_retries=max_retries, retry_wait_interval_sec=0, **kwargs
    )
    chat_model.will_apply_native_structured_output = MagicMock(return_value=True)
    return ctx


def _run_chat(
    ctx: Any, output_schema: OutputSchema | None, request_id: Any = None
) -> None:
    asyncio.run(
        chat(
            request_id or uuid4(),
            "test-model",
            [ChatMessage.of(MessageRole.USER, "hi")],
            {},
            output_schema,
            ctx,
        )
    )


class TestNativeStructuredOutputFinalization:
    """The schema-carrying call issued once the loop settles on a final answer."""

    def test_finalization_call_carries_answer_directive_and_schema(self) -> None:
        loop_answer = _assistant("the answer is 42")
        chat_model = _native_chat_model(loop_responses=[loop_answer])
        ctx, _, _, _ = _native_context(chat_model)
        schema = OutputSchema(output_schema=_StructuredResult)

        _run_chat(ctx, schema)

        chat_model.chat_structured.assert_called_once()
        sent_args = chat_model.chat_structured.call_args
        assert sent_args.kwargs == {}
        sent_messages, sent_schema = sent_args.args
        assert [(m.role, m.text) for m in sent_messages] == [
            (MessageRole.USER, "hi"),
            (MessageRole.ASSISTANT, "the answer is 42"),
            (MessageRole.USER, _FINALIZE_DIRECTIVE_TEXT),
        ]
        assert sent_messages[1] is loop_answer
        assert sent_schema is schema
        assert chat_model.chat.call_count == 1

    def test_structured_output_is_parsed_from_the_finalization_response(
        self,
    ) -> None:
        chat_model = _native_chat_model()
        ctx, sent_events, _, _ = _native_context(chat_model)

        _run_chat(ctx, OutputSchema(output_schema=_StructuredResult))

        # The loop answer is prose no parser could read, so only the finalization
        # response can produce this.
        assert len(sent_events) == 1
        assert sent_events[0].response.text == '{"result": 42}'
        assert sent_events[0].response.extra_args[STRUCTURED_OUTPUT].result == 42

    def test_gate_false_issues_no_finalization_call(self) -> None:
        chat_model = _native_chat_model(loop_responses=[_assistant('{"result": 7}')])
        ctx, sent_events, _, _ = _create_mock_runner_context(
            chat_model, max_retries=0, retry_wait_interval_sec=0
        )
        chat_model.will_apply_native_structured_output = MagicMock(return_value=False)
        schema = OutputSchema(output_schema=_StructuredResult)

        _run_chat(ctx, schema)

        chat_model.will_apply_native_structured_output.assert_called_once_with(schema)
        chat_model.chat_structured.assert_not_called()
        assert ctx.report_execution_started.call_args_list.count(_LLM_SPAN) == 1
        assert sent_events[0].response.extra_args[STRUCTURED_OUTPUT].result == 7

    def test_no_schema_does_not_consult_the_gate(self) -> None:
        chat_model = _native_chat_model()
        ctx, sent_events, _, _ = _native_context(chat_model)
        chat_model.will_apply_native_structured_output.side_effect = TypeError(
            "connection not resolved"
        )

        _run_chat(ctx, None)

        chat_model.will_apply_native_structured_output.assert_not_called()
        chat_model.chat_structured.assert_not_called()
        assert sent_events[0].response.text == "the answer is 42"

    def test_tool_call_response_issues_no_finalization_call(self) -> None:
        tool_calls = [{"id": "call-1", "function": {"name": "f", "arguments": {}}}]
        chat_model = _native_chat_model(
            loop_responses=[
                ChatMessage.of(MessageRole.ASSISTANT, "", tool_calls=tool_calls)
            ]
        )
        ctx, sent_events, _, _ = _native_context(chat_model)

        _run_chat(ctx, OutputSchema(output_schema=_StructuredResult))

        chat_model.chat_structured.assert_not_called()
        assert len(sent_events) == 1
        assert isinstance(sent_events[0], ToolRequestEvent)

    def test_finalization_failure_reports_its_own_llm_failure(self) -> None:
        chat_model = _native_chat_model(
            final_responses=RuntimeError("conversion call exploded")
        )
        ctx, sent_events, _, _ = _native_context(chat_model)

        _run_chat(ctx, OutputSchema(output_schema=_StructuredResult))

        ctx.report_execution_failed.assert_called_once()
        failed_args = ctx.report_execution_failed.call_args.args
        assert failed_args[:3] == (
            ExecutionEntityTypes.LLM,
            "test-model",
            _LLM_METADATA,
        )
        assert failed_args[-1] == ExecutionProblemCategories.MODEL_CALL_FAILED
        assert ctx.report_execution_started.call_args_list.count(_LLM_SPAN) == 2
        assert ctx.report_execution_succeeded.call_args_list.count(_LLM_SPAN) == 1
        assert len(sent_events) == 1
        assert sent_events[0].is_failed
        assert "conversion call exploded" in sent_events[0].error

    def test_finalization_failure_consumes_the_retry_budget(self) -> None:
        chat_model = _native_chat_model(
            loop_responses=[_assistant("first"), _assistant("second")],
            final_responses=[
                RuntimeError("conversion call exploded"),
                _assistant('{"result": 42}'),
            ],
        )
        ctx, sent_events, _, _ = _native_context(chat_model, max_retries=1)

        _run_chat(ctx, OutputSchema(output_schema=_StructuredResult))

        # The retry repeats the whole attempt, loop call included.
        assert chat_model.chat.call_count == 2
        assert chat_model.chat_structured.call_count == 2
        assert sent_events[0].retry_count == 1
        assert sent_events[0].response.extra_args[STRUCTURED_OUTPUT].result == 42

    def test_parse_failure_after_native_call_consumes_the_retry_budget(self) -> None:
        chat_model = _native_chat_model(
            loop_responses=[_assistant("first"), _assistant("second")],
            final_responses=[_assistant("not-json"), _assistant('{"result": 42}')],
        )
        ctx, sent_events, _, _ = _native_context(chat_model, max_retries=1)

        _run_chat(ctx, OutputSchema(output_schema=_StructuredResult))

        assert chat_model.chat_structured.call_count == 2
        assert sent_events[0].retry_count == 1
        assert sent_events[0].response.extra_args[STRUCTURED_OUTPUT].result == 42
        ctx.report_execution_failed.assert_called_once()
        failed_args = ctx.report_execution_failed.call_args.args
        assert failed_args[0] == ExecutionEntityTypes.PARSER
        assert failed_args[-1] == ExecutionProblemCategories.MODEL_OUTPUT_PARSE_ERROR

    def test_truncated_finalization_response_is_rejected(self) -> None:
        chat_model = _native_chat_model(
            final_responses=[
                _assistant(
                    '{"result": 4',
                    finish_reason="length",
                    model_name="provider-model",
                    promptTokens=10,
                    completionTokens=5,
                )
            ]
        )
        ctx, sent_events, metric_group, _ = _native_context(chat_model)

        _run_chat(ctx, OutputSchema(output_schema=_StructuredResult))

        assert len(sent_events) == 1
        assert sent_events[0].is_failed
        assert "truncat" in sent_events[0].error.lower()
        # The call itself succeeded and spent its budget before being rejected.
        assert ctx.report_execution_succeeded.call_args_list.count(_LLM_SPAN) == 2
        chat_model._record_token_metrics.assert_called_once_with(
            "provider-model", 10, 5, metric_group
        )

    def test_finalization_records_its_token_metrics(self) -> None:
        chat_model = _native_chat_model(
            loop_responses=[
                _assistant(
                    "the answer is 42",
                    model_name="provider-model",
                    promptTokens=100,
                    completionTokens=50,
                )
            ],
            final_responses=[
                _assistant(
                    '{"result": 42}',
                    model_name="provider-model",
                    promptTokens=7,
                    completionTokens=3,
                )
            ],
        )
        ctx, _, metric_group, _ = _native_context(chat_model)

        _run_chat(ctx, OutputSchema(output_schema=_StructuredResult))

        assert chat_model._record_token_metrics.call_args_list == [
            call("provider-model", 100, 50, metric_group),
            call("provider-model", 7, 3, metric_group),
        ]

    def test_gate_is_evaluated_once_across_retries(self) -> None:
        chat_model = _native_chat_model(
            loop_responses=[
                RuntimeError("transient"),
                RuntimeError("transient"),
                _assistant("the answer is 42"),
            ]
        )
        ctx, sent_events, _, _ = _native_context(chat_model, max_retries=2)

        _run_chat(ctx, OutputSchema(output_schema=_StructuredResult))

        chat_model.will_apply_native_structured_output.assert_called_once()
        assert sent_events[0].retry_count == 2
        assert sent_events[0].response.extra_args[STRUCTURED_OUTPUT].result == 42

    def test_gate_failure_fails_the_request_without_a_model_call(self) -> None:
        chat_model = _native_chat_model()
        ctx, sent_events, metric_group, _ = _native_context(chat_model, max_retries=2)
        chat_model.will_apply_native_structured_output.side_effect = ValueError(
            "NATIVE cannot apply the output schema"
        )
        request_id = uuid4()

        _run_chat(ctx, OutputSchema(output_schema=_StructuredResult), request_id)

        chat_model.chat.assert_not_called()
        chat_model.chat_structured.assert_not_called()
        ctx.report_execution_started.assert_not_called()
        assert len(sent_events) == 1
        assert sent_events[0].request_id == request_id
        assert sent_events[0].is_failed
        assert sent_events[0].error == (
            "ValueError: NATIVE cannot apply the output schema"
        )
        assert sent_events[0].retry_count == 0
        assert len(metric_group._sub_groups) == 0

    @pytest.mark.parametrize(
        "failure",
        [InterruptedError("cancelled"), asyncio.CancelledError()],
    )
    def test_gate_cancellation_propagates(self, failure: BaseException) -> None:
        chat_model = _native_chat_model()
        ctx, sent_events, _, _ = _native_context(chat_model)
        chat_model.will_apply_native_structured_output.side_effect = failure

        with pytest.raises(type(failure)):
            _run_chat(ctx, OutputSchema(output_schema=_StructuredResult))

        assert not sent_events
        chat_model.chat.assert_not_called()

    @pytest.mark.parametrize(
        "failure",
        [
            RuntimeError("state failed"),
            InterruptedError("cancelled"),
            asyncio.CancelledError(),
        ],
    )
    def test_finalization_durable_failure_propagates_without_response(
        self, failure: BaseException
    ) -> None:
        chat_model = _native_chat_model()
        ctx, sent_events, _, _ = _native_context(chat_model)
        calls: list = []

        def dispatch(
            fn: Any, *args: Any, durable_id: str | None = None, **kwargs: Any
        ) -> Any:
            calls.append(fn)
            if len(calls) == 2:
                raise failure
            return fn(*args, **kwargs)

        ctx.durable_execute.side_effect = dispatch

        with pytest.raises(type(failure)):
            _run_chat(ctx, OutputSchema(output_schema=_StructuredResult))

        # Persistence and recovery failures escape the retry budget and the
        # request-failure path alike.
        assert not sent_events
        assert len(calls) == 2
        chat_model.chat_structured.assert_not_called()

    def test_finalization_runs_on_the_async_durable_seam(self) -> None:
        chat_model = _native_chat_model()
        ctx, sent_events, _, _ = _native_context(chat_model, chat_async=True)

        _run_chat(ctx, OutputSchema(output_schema=_StructuredResult))

        assert ctx.durable_execute_async.call_count == 2
        ctx.durable_execute.assert_not_called()
        assert sent_events[0].response.extra_args[STRUCTURED_OUTPUT].result == 42


class _RecordingConnection(BaseChatModelConnection):
    """Records every request, answering prose without a schema and JSON with one."""

    unconstrained_requests: list = Field(default_factory=list)
    schema_carrying_requests: list = Field(default_factory=list)

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: list[Tool] | None = None,
        output_schema: OutputSchema | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        if output_schema is None:
            self.unconstrained_requests.append(list(messages))
            return _assistant("the answer is 42")
        self.schema_carrying_requests.append(list(messages))
        return _assistant('{"result": 42}')


class _NativePromptSetup(BaseChatModelSetup):
    @property
    def model_kwargs(self) -> dict[str, Any]:
        return {}

    def will_apply_native_structured_output(
        self, output_schema: OutputSchema | None
    ) -> bool:
        return output_schema is not None


class TestNativeFinalizationHistory:
    """The finalization replays the history the loop call actually sent."""

    def test_finalization_sends_the_prepared_loop_history(self) -> None:
        connection = _RecordingConnection()
        chat_model = _NativePromptSetup(
            connection="c",
            model="m",
            prompt=Prompt.from_messages(
                messages=[
                    ChatMessage.of(MessageRole.SYSTEM, "You are terse."),
                    ChatMessage.of(MessageRole.USER, "Task: {task}"),
                ]
            ),
            skill_discovery_prompt="Available skills",
        )
        chat_model._resolved_connection = connection
        ctx, sent_events, _, _ = _create_mock_runner_context(
            chat_model, max_retries=0, retry_wait_interval_sec=0
        )

        asyncio.run(
            chat(
                uuid4(),
                "test-model",
                [ChatMessage(role=MessageRole.USER)],
                {"task": "add 2 and 3"},
                OutputSchema(output_schema=_StructuredResult),
                ctx,
            )
        )

        (loop_request,) = connection.unconstrained_requests
        (final_request,) = connection.schema_carrying_requests
        expected = [(m.role, m.text) for m in loop_request] + [
            (MessageRole.ASSISTANT, "the answer is 42"),
            (MessageRole.USER, _FINALIZE_DIRECTIVE_TEXT),
        ]
        assert [(m.role, m.text) for m in final_request] == expected
        assert all(m.text for m in final_request)
        assert sent_events[0].response.extra_args[STRUCTURED_OUTPUT].result == 42


class _ConnectionlessSetup(BaseChatModelSetup):
    """Answers through its own chat() and resolves no connection."""

    @property
    def model_kwargs(self) -> dict[str, Any]:
        return {}

    def chat(
        self,
        messages: Sequence[ChatMessage],
        prompt_args: dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        return _assistant('{"result": 7}')


def test_connectionless_setup_takes_the_prompt_path_for_a_schema() -> None:
    """Contract: a setup that overrides chat() and resolves no connection parses
    its own chat response for a schema-carrying request under the default
    strategy, with no finalization call.
    """
    chat_model = _ConnectionlessSetup(connection="c", model="m")
    ctx, sent_events, _, _ = _create_mock_runner_context(
        chat_model, max_retries=0, retry_wait_interval_sec=0
    )

    _run_chat(ctx, OutputSchema(output_schema=_StructuredResult))

    assert ctx.durable_execute.call_count == 1
    (event,) = sent_events
    assert isinstance(event, ChatResponseEvent)
    assert event.response.extra_args[STRUCTURED_OUTPUT].result == 7
