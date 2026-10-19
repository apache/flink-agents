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
from typing import Any, Callable, Sequence
from unittest.mock import ANY, MagicMock, call
from uuid import uuid4

import pytest
from pydantic import BaseModel

from flink_agents.api.agents.agent import STRUCTURED_OUTPUT
from flink_agents.api.agents.react_agent import OutputSchema
from flink_agents.api.chat_message import (
    ChatMessage,
    ImageBlock,
    MessageRole,
    ToolCallBlock,
)
from flink_agents.api.chat_result import ChatResult, TokenUsage
from flink_agents.api.core_options import (
    AgentExecutionOptions,
)
from flink_agents.api.events.chat_event import ChatResponseEvent
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent
from flink_agents.api.metric_group import Counter, MetricGroup
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


def _response_fixture(role, content, extra_args=None, tool_calls=None) -> ChatResult:
    fields = extra_args or {}
    blocks = list(ChatMessage.assistant(content).blocks)
    blocks.extend(
        ToolCallBlock(
            call_id=c["id"],
            name=c["function"]["name"],
            input=c["function"]["arguments"],
        )
        for c in tool_calls or []
    )
    return ChatResult(
        message=ChatMessage.assistant(blocks),
        model=fields.get("model_name"),
        usage=TokenUsage(
            prompt_tokens=fields.get("promptTokens"),
            completion_tokens=fields.get("completionTokens"),
        ),
        finish_reason=fields.get("finish_reason"),
    )


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
    # Like the runtime, the reserved durable_id keyword is not forwarded to fn.
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
        model.chat.return_value = _response_fixture(
            role=MessageRole.ASSISTANT, content="ok"
        )
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
            return_value=_response_fixture(MessageRole.ASSISTANT, "hello")
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
            ANY,
            ExecutionEntityTypes.LLM,
            chat_model.connection,
            _LLM_METADATA,
        )
        ctx.report_execution_succeeded.assert_called_once_with(
            ANY,
            ExecutionEntityTypes.LLM,
            chat_model.connection,
            _LLM_METADATA,
        )
        ctx.report_execution_failed.assert_not_called()

    @pytest.mark.parametrize("metrics_available", [False, True])
    @pytest.mark.parametrize(
        ("usage", "expected_usage", "records_metrics"),
        [
            (
                TokenUsage(prompt_tokens=100, completion_tokens=50),
                {"promptTokens": 100, "completionTokens": 50},
                True,
            ),
            (
                TokenUsage(prompt_tokens=5, completion_tokens=0),
                {"promptTokens": 5, "completionTokens": 0},
                True,
            ),
            (TokenUsage(completion_tokens=0), {"completionTokens": 0}, False),
            (TokenUsage(prompt_tokens=5), {"promptTokens": 5}, False),
            (TokenUsage(), {}, False),
            (None, {}, False),
        ],
    )
    def test_usage_and_token_metrics_share_response_counts(
        self,
        usage: TokenUsage | None,
        expected_usage: dict,
        records_metrics: bool,
        metrics_available: bool,
    ) -> None:
        chat_model = MagicMock()
        chat_model.chat.return_value = ChatResult(
            message=ChatMessage.assistant("hello"),
            model="metric-model",
            usage=usage,
        )
        ctx, events, metric_group, _ = _create_mock_runner_context(chat_model)
        if not metrics_available:
            ctx.action_metric_group = None
        asyncio.run(chat(uuid4(), "test-model", [], {}, None, ctx))
        expected_metadata = dict(_LLM_METADATA)
        if expected_usage:
            expected_metadata["usage"] = expected_usage
        ctx.report_execution_started.assert_called_once_with(
            ANY, ExecutionEntityTypes.LLM, "test-model", _LLM_METADATA
        )
        ctx.report_execution_succeeded.assert_called_once_with(
            ANY, ExecutionEntityTypes.LLM, "test-model", expected_metadata
        )
        if records_metrics and metrics_available:
            chat_model._record_token_metrics.assert_called_once_with(
                "metric-model",
                usage.prompt_tokens,
                usage.completion_tokens,
                metric_group,
            )
        else:
            chat_model._record_token_metrics.assert_not_called()
        _assert_report_identities(ctx)
        assert not events[0].is_failed

    @pytest.mark.parametrize("chat_async", [False, True])
    def test_every_attempt_uses_the_chat_durable_id(self, chat_async: bool) -> None:
        """Each attempt is keyed by "chat", not by a digest of the messages (#1190)."""
        chat_model = MagicMock()
        chat_model.chat = MagicMock(
            side_effect=[
                RuntimeError("transient"),
                _response_fixture(MessageRole.ASSISTANT, "hello"),
            ]
        )
        ctx, sent_events, _, _ = _create_mock_runner_context(
            chat_model, max_retries=1, retry_wait_interval_sec=0
        )
        if chat_async:
            async_option = AgentExecutionOptions.CHAT_ASYNC
            original_get = ctx.config.get.side_effect
            ctx.config.get = MagicMock(
                side_effect=lambda option: True
                if option is async_option
                else original_get(option)
            )

            async def durable_execute_async(
                fn: Callable[..., Any],
                *args: Any,
                durable_id: str | None = None,
                **kwargs: Any,
            ) -> Any:
                return ctx.durable_execute(fn, *args, durable_id=durable_id, **kwargs)

            ctx.durable_execute_async = MagicMock(side_effect=durable_execute_async)

        asyncio.run(
            chat(
                uuid4(),
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
        assert event.is_success
        assert event.response.text == "hello"
        assert chat_model.chat.call_count == 2
        durable_call = ctx.durable_execute_async if chat_async else ctx.durable_execute
        assert [call.kwargs["durable_id"] for call in durable_call.call_args_list] == [
            "chat",
            "chat",
        ]

    def test_chat_retries_with_exponential_backoff(self) -> None:
        """Fail once then succeed: 1s interval, 1 retry -> wait 1s (1 * 2^0)."""
        call_count = 0

        def mock_chat(messages: Sequence[ChatMessage], **kwargs: Any) -> ChatResult:
            nonlocal call_count
            call_count += 1
            if call_count <= 1:
                err_msg = "transient error"
                raise RuntimeError(err_msg)
            return _response_fixture(MessageRole.ASSISTANT, "success")

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
        _assert_report_identities(ctx)
        assert ctx.report_execution_started.call_count == 2
        ctx.report_execution_failed.assert_called_once()
        failed_args = ctx.report_execution_failed.call_args.args
        assert failed_args[1] == ExecutionEntityTypes.LLM
        assert failed_args[3] == _LLM_METADATA
        assert failed_args[-1] == ExecutionProblemCategories.MODEL_CALL_FAILED
        ctx.report_execution_succeeded.assert_called_once_with(
            ANY,
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
        _assert_report_identities(ctx)
        assert ctx.report_execution_started.call_count == 3
        assert ctx.report_execution_failed.call_count == 3
        for failed_call in ctx.report_execution_failed.call_args_list:
            assert failed_call.args[1] == ExecutionEntityTypes.LLM
            assert failed_call.args[3] == _LLM_METADATA
            assert failed_call.args[-1] == ExecutionProblemCategories.MODEL_CALL_FAILED
        ctx.report_execution_succeeded.assert_not_called()
        model_resource_group = metric_group.get_sub_group(
            "model_resource", "test-model"
        )
        assert model_resource_group.get_counter("retryCount").get_count() == 2
        assert model_resource_group.get_counter("retryWaitSec").get_count() == 0

    @pytest.mark.parametrize("asynchronous", [False, True])
    def test_structured_output_parse_error_retries_without_failing_llm(
        self, asynchronous: bool
    ) -> None:
        chat_model = MagicMock()
        chat_model.chat = MagicMock(
            side_effect=[
                _response_fixture(
                    MessageRole.ASSISTANT, "not-json", extra_args={"promptTokens": 11}
                ),
                _response_fixture(
                    MessageRole.ASSISTANT,
                    '{"result": 42}',
                    extra_args={"completionTokens": 0},
                ),
            ]
        )

        ctx, sent_events, _, _ = _create_mock_runner_context(
            chat_model, max_retries=1, retry_wait_interval_sec=0
        )
        configured_value = ctx.config.get.side_effect
        ctx.config.get.side_effect = (
            lambda option: asynchronous
            if option is AgentExecutionOptions.CHAT_ASYNC
            else configured_value(option)
        )

        async def execute_async(
            func: Any, *args: Any, durable_id: str | None = None, **kwargs: Any
        ) -> Any:
            await asyncio.sleep(0)
            return func(*args, **kwargs)

        ctx.durable_execute_async = execute_async

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
        sent_events[0].response
        assert sent_events[0].structured_output.result == 42

        ctx.report_execution_failed.assert_called_once()
        failed_args = ctx.report_execution_failed.call_args.args
        assert failed_args[1] == ExecutionEntityTypes.PARSER
        assert failed_args[2] == STRUCTURED_OUTPUT
        assert failed_args[-1] == ExecutionProblemCategories.MODEL_OUTPUT_PARSE_ERROR

        _assert_report_identities(ctx)
        assert ctx.report_execution_started.call_count == 4
        assert ctx.report_execution_succeeded.call_count == 3
        llm_successes = [
            report.args[3]
            for report in ctx.report_execution_succeeded.call_args_list
            if report.args[1] == ExecutionEntityTypes.LLM
        ]
        assert llm_successes == [
            {**_LLM_METADATA, "usage": {"promptTokens": 11}},
            {**_LLM_METADATA, "usage": {"completionTokens": 0}},
        ]


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
            return_value=_response_fixture(
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
            return_value=_response_fixture(
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
            return_value=_response_fixture(
                role=MessageRole.ASSISTANT,
                content="",
                tool_calls=[
                    {
                        "id": "call-1",
                        "function": {"name": "f", "arguments": {}},
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
            return_value=_response_fixture(
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
        assert sent_events[0].response.finish_reason == extra_args.get("finish_reason")

    def test_accepted_finish_reason_dispatches_tool_request_event(self) -> None:
        # A response carrying tool calls passes the same finish-reason gate as a
        # text response, so an accepted reason must reach tool dispatch.
        tool_calls = [{"id": "call-1", "function": {"name": "f", "arguments": {}}}]
        chat_model = MagicMock()
        response = _response_fixture(
            role=MessageRole.ASSISTANT,
            content="",
            tool_calls=tool_calls,
            extra_args={"finish_reason": "tool_calls"},
        )
        response.message.metadata["message_key"] = "value"
        response = ChatResult(
            message=response.message,
            model="provider-model",
            response_id="response-id",
            finish_reason="tool_calls",
            metadata={"provider_only": "opaque"},
        )
        chat_model.chat = MagicMock(return_value=response)
        ctx, sent_events, _, memory = _create_mock_runner_context(
            chat_model, max_retries=0, retry_wait_interval_sec=0
        )

        self._run(ctx)

        assert len(sent_events) == 1
        assert isinstance(sent_events[0], ToolRequestEvent)
        assert sent_events[0].tool_calls[0].call_id == "call-1"
        assert sent_events[0].tool_calls[0].name == "f"
        history = next(iter(memory.get("_TOOL_CALL_CONTEXT").values()))
        assistant = ChatMessage.model_validate(history[-1])
        assert assistant.role == MessageRole.ASSISTANT
        assert assistant.blocks == response.message.blocks
        assert assistant.metadata == {"message_key": "value"}
        assert set(history[-1]) == {"role", "blocks", "metadata"}

    def test_default_retry_budget_returns_failed_response(self) -> None:
        # The default retry budget makes one attempt and rejects truncated content.
        chat_model = MagicMock()
        chat_model.chat = MagicMock(
            return_value=_response_fixture(
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
            return_value=_response_fixture(
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
            ANY,
            ExecutionEntityTypes.LLM,
            "test-model",
            {**_LLM_METADATA, "usage": {"promptTokens": 100, "completionTokens": 50}},
        )
        chat_model._record_token_metrics.assert_called_once_with(
            "provider-model", 100, 50, metric_group
        )
        # The parse is never attempted, so nothing about it is reported and no
        # response leaves the action.
        ctx.report_execution_started.assert_called_once_with(
            ANY, ExecutionEntityTypes.LLM, "test-model", _LLM_METADATA
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
            response=_response_fixture(MessageRole.ASSISTANT, "test"),
        )
        assert event.retry_count == 0
        assert event.total_retry_wait_sec == 0

    def test_with_retry_fields(self) -> None:
        """Full construction carries retry info."""
        event = ChatResponseEvent.success(
            request_id=uuid4(),
            response=_response_fixture(MessageRole.ASSISTANT, "test"),
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

        def mock_chat(messages: Sequence[ChatMessage], **kwargs: Any) -> ChatResult:
            captured_prompt_args.append(kwargs.get("prompt_args"))
            return _response_fixture(MessageRole.ASSISTANT, "done")

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

        def mock_chat(messages: Sequence[ChatMessage], **kwargs: Any) -> ChatResult:
            captured_messages.append(messages)
            return _response_fixture(MessageRole.ASSISTANT, "done")

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
            success={tool_call_id: False},
            error={
                tool_call_id: "Missing config for injected tool parameter: tenant_id"
            },
        )

        asyncio.run(process_chat_request_or_tool_response(tool_response_event, ctx))

        assert captured_messages
        tool_message = captured_messages[0][-1]
        assert tool_message.role == MessageRole.TOOL
        assert tool_message.blocks[0].text == "Tool `query_order` execute failed."


def _assert_report_identities(ctx: MagicMock) -> None:
    starts = {
        report.args[0]: report.args[1:3]
        for report in ctx.report_execution_started.call_args_list
    }
    assert len(starts) == ctx.report_execution_started.call_count
    assert all(isinstance(identity, str) and identity for identity in starts)
    terminals = (
        ctx.report_execution_succeeded.call_args_list
        + ctx.report_execution_failed.call_args_list
    )
    assert {report.args[0] for report in terminals} == set(starts)
    for report in terminals:
        assert report.args[1:3] == starts[report.args[0]]


# Spelled out rather than imported from the action, so the assertion pins the exact
# words a provider receives.
_FINALIZE_DIRECTIVE_TEXT = (
    "Convert the response below into the required structured output format."
    " Preserve its meaning and do not add or infer any new information.\n\n"
    "Response:\n"
)
_LLM_SPAN = call(ANY, ExecutionEntityTypes.LLM, "test-model", _LLM_METADATA)


def _assistant(content: str, **extra_args: Any) -> ChatResult:
    return _response_fixture(MessageRole.ASSISTANT, content, extra_args)


def _native_chat_model(
    loop_responses: Any = None, final_responses: Any = None
) -> MagicMock:
    """A chat model whose loop answer is prose and whose finalization is JSON.

    Both calls go through ``chat``. The finalization is the one given ``tools``, and
    each kind of call draws from its own response queue.
    """
    chat_model = MagicMock()
    chat_model.loop_chat = MagicMock(
        side_effect=loop_responses or [_assistant("the answer is 42")]
    )
    chat_model.final_chat = MagicMock(
        side_effect=final_responses or [_assistant('{"result": 42}')]
    )

    def dispatch(*args: Any, **kwargs: Any) -> Any:
        target = chat_model.final_chat if "tools" in kwargs else chat_model.loop_chat
        return target(*args, **kwargs)

    chat_model.chat = MagicMock(side_effect=dispatch)
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

        loop_call, final_call = chat_model.chat.call_args_list
        assert "tools" not in loop_call.kwargs
        assert "output_schema" not in loop_call.kwargs
        assert set(final_call.kwargs) == {"tools", "output_schema"}
        assert final_call.kwargs["tools"] == []
        assert final_call.kwargs["output_schema"] is schema
        (sent_messages,) = final_call.args
        # Only the directive and the answer's text: no part of the loop's request
        # is resent.
        assert [(m.role, m.text) for m in sent_messages] == [
            (MessageRole.USER, _FINALIZE_DIRECTIVE_TEXT + "the answer is 42"),
        ]

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
        assert sent_events[0].structured_output.result == 42

    def test_gate_false_issues_no_finalization_call(self) -> None:
        chat_model = _native_chat_model(loop_responses=[_assistant('{"result": 7}')])
        ctx, sent_events, _, _ = _create_mock_runner_context(
            chat_model, max_retries=0, retry_wait_interval_sec=0
        )
        chat_model.will_apply_native_structured_output = MagicMock(return_value=False)
        schema = OutputSchema(output_schema=_StructuredResult)

        _run_chat(ctx, schema)

        chat_model.will_apply_native_structured_output.assert_called_once_with(schema)
        chat_model.final_chat.assert_not_called()
        assert ctx.report_execution_started.call_args_list.count(_LLM_SPAN) == 1
        assert sent_events[0].structured_output.result == 7

    def test_no_schema_does_not_consult_the_gate(self) -> None:
        chat_model = _native_chat_model()
        ctx, sent_events, _, _ = _native_context(chat_model)
        chat_model.will_apply_native_structured_output.side_effect = TypeError(
            "connection not resolved"
        )

        _run_chat(ctx, None)

        chat_model.will_apply_native_structured_output.assert_not_called()
        chat_model.final_chat.assert_not_called()
        assert sent_events[0].response.text == "the answer is 42"

    def test_tool_call_response_issues_no_finalization_call(self) -> None:
        tool_calls = [{"id": "call-1", "function": {"name": "f", "arguments": {}}}]
        chat_model = _native_chat_model(
            loop_responses=[
                _response_fixture(MessageRole.ASSISTANT, "", tool_calls=tool_calls)
            ]
        )
        ctx, sent_events, _, _ = _native_context(chat_model)

        _run_chat(ctx, OutputSchema(output_schema=_StructuredResult))

        chat_model.final_chat.assert_not_called()
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
        assert failed_args[1:4] == (
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
        _assert_report_identities(ctx)

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
        assert chat_model.loop_chat.call_count == 2
        assert chat_model.final_chat.call_count == 2
        assert sent_events[0].retry_count == 1
        assert sent_events[0].structured_output.result == 42
        _assert_report_identities(ctx)

    def test_parse_failure_after_native_call_consumes_the_retry_budget(self) -> None:
        chat_model = _native_chat_model(
            loop_responses=[_assistant("first"), _assistant("second")],
            final_responses=[
                _assistant("not-json", promptTokens=11),
                _assistant('{"result": 42}', completionTokens=0),
            ],
        )
        ctx, sent_events, _, _ = _native_context(chat_model, max_retries=1)

        _run_chat(ctx, OutputSchema(output_schema=_StructuredResult))

        assert chat_model.final_chat.call_count == 2
        assert sent_events[0].retry_count == 1
        assert sent_events[0].structured_output.result == 42
        _assert_report_identities(ctx)
        ctx.report_execution_failed.assert_called_once()
        failed_args = ctx.report_execution_failed.call_args.args
        assert failed_args[1] == ExecutionEntityTypes.PARSER
        assert failed_args[-1] == ExecutionProblemCategories.MODEL_OUTPUT_PARSE_ERROR

        assert [
            report.args[3]
            for report in ctx.report_execution_succeeded.call_args_list
            if report.args[1] == ExecutionEntityTypes.LLM
        ] == [
            _LLM_METADATA,
            {**_LLM_METADATA, "usage": {"promptTokens": 11}},
            _LLM_METADATA,
            {**_LLM_METADATA, "usage": {"completionTokens": 0}},
        ]

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
        assert [
            report.args[3] for report in ctx.report_execution_succeeded.call_args_list
        ] == [
            _LLM_METADATA,
            {**_LLM_METADATA, "usage": {"promptTokens": 10, "completionTokens": 5}},
        ]
        _assert_report_identities(ctx)
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
        _assert_report_identities(ctx)
        assert [
            report.args[3]
            for report in ctx.report_execution_succeeded.call_args_list
            if report.args[1] == ExecutionEntityTypes.LLM
        ] == [
            {**_LLM_METADATA, "usage": {"promptTokens": 100, "completionTokens": 50}},
            {**_LLM_METADATA, "usage": {"promptTokens": 7, "completionTokens": 3}},
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
        assert sent_events[0].structured_output.result == 42

    def test_gate_failure_fails_the_request_without_a_model_call(self) -> None:
        chat_model = _native_chat_model()
        ctx, sent_events, metric_group, _ = _native_context(chat_model, max_retries=2)
        chat_model.will_apply_native_structured_output.side_effect = ValueError(
            "NATIVE cannot apply the output schema"
        )
        request_id = uuid4()

        _run_chat(ctx, OutputSchema(output_schema=_StructuredResult), request_id)

        chat_model.chat.assert_not_called()
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
        chat_model.final_chat.assert_not_called()

    def test_finalization_runs_on_the_async_durable_seam(self) -> None:
        chat_model = _native_chat_model()
        ctx, sent_events, _, _ = _native_context(chat_model, chat_async=True)

        _run_chat(ctx, OutputSchema(output_schema=_StructuredResult))

        assert ctx.durable_execute_async.call_count == 2
        ctx.durable_execute.assert_not_called()
        assert sent_events[0].structured_output.result == 42
