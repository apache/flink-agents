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
from datetime import timedelta
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
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.tool_event import ToolRequestEvent, ToolResponseEvent
from flink_agents.api.metric_group import Counter, MetricGroup
from flink_agents.api.react_loop_budget import ReActLoopBudget
from flink_agents.api.trace import (
    ExecutionEntityTypes,
    ExecutionProblemCategories,
    ExecutionReporter,
    LLMExecutionMetadataKeys,
)
from flink_agents.plan.actions import react_loop_budget_utils as budget_utils
from flink_agents.plan.actions.chat_model_action import (
    _chat_with_retries,
    _ChatAttemptFailed,
    chat,
    process_chat_request_or_tool_response,
)
from flink_agents.plan.configuration import AgentConfiguration

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
    retry_wait: timedelta | None = None,
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
        id(AgentExecutionOptions.RETRY_WAIT_INTERVAL): (
            retry_wait
            if retry_wait is not None
            else timedelta(seconds=retry_wait_interval_sec)
        ),
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
    ctx.has_resource = MagicMock(return_value=False)
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
        """No retry needed: retry_count=0, total_retry_wait_ms=0, no metrics."""
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
        assert event.total_retry_wait_ms == 0

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
        # 1s interval, one retry: 1000 ms, not a truncated second count.
        assert event.total_retry_wait_ms == 1000
        assert elapsed >= 1.0

        # Retry health belongs to the ChatModel resource.
        model_resource_group = metric_group.get_sub_group(
            "model_resource", "test-model"
        )
        assert model_resource_group.get_counter("retryCount").get_count() == 1
        assert model_resource_group.get_counter("retryWaitMs").get_count() == 1000

    @pytest.mark.parametrize(
        ("micros", "millis"),
        [(100000, 100), (1500, 1), (500, 0), (-1500, 0), (0, 0)],
    )
    def test_chat_retry_wait_matches_recorded_milliseconds(
        self, monkeypatch, micros, millis
    ) -> None:
        sleep = MagicMock()
        monkeypatch.setattr(
            "flink_agents.plan.actions.chat_model_action.time.sleep", sleep
        )
        call_count = 0

        def mock_chat(messages: Sequence[ChatMessage], **kwargs: Any) -> ChatResult:
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                err_msg = "transient error"
                raise RuntimeError(err_msg)
            return _response_fixture(MessageRole.ASSISTANT, "success")

        chat_model = MagicMock()
        chat_model.chat = mock_chat
        ctx, sent_events, metric_group, _ = _create_mock_runner_context(
            chat_model, max_retries=1, retry_wait=timedelta(microseconds=micros)
        )
        asyncio.run(
            chat(
                uuid4(),
                "test-model",
                [ChatMessage.of(MessageRole.USER, "hi")],
                {},
                None,
                ctx,
            )
        )

        event = sent_events[0]
        assert isinstance(event, ChatResponseEvent)
        assert event.retry_count == 1
        assert event.total_retry_wait_ms == millis
        if millis:
            sleep.assert_called_once_with(millis / 1000)
        else:
            sleep.assert_not_called()
        model_resource_group = metric_group.get_sub_group(
            "model_resource", "test-model"
        )
        assert model_resource_group.get_counter("retryCount").get_count() == 1
        assert model_resource_group.get_counter("retryWaitMs").get_count() == millis
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
        assert model_resource_group.get_counter("retryWaitMs").get_count() == 0

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
        """Default construction has retry_count=0, total_retry_wait_ms=0."""
        event = ChatResponseEvent.success(
            request_id=uuid4(),
            response=_response_fixture(MessageRole.ASSISTANT, "test"),
        )
        assert event.retry_count == 0
        assert event.total_retry_wait_ms == 0

    def test_with_retry_fields(self) -> None:
        """Full construction carries retry info."""
        event = ChatResponseEvent.success(
            request_id=uuid4(),
            response=_response_fixture(MessageRole.ASSISTANT, "test"),
            retry_count=5,
            total_retry_wait_ms=31,
        )
        assert event.retry_count == 5
        assert event.total_retry_wait_ms == 31


class TestRetryWaitIntervalConfig:
    """Tests for RETRY_WAIT_INTERVAL configuration."""

    def test_default_value(self) -> None:
        """Default value is 1 second."""
        assert (
            AgentExecutionOptions.RETRY_WAIT_INTERVAL.get_default_value()
            == timedelta(seconds=1)
        )


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


@pytest.mark.parametrize("chat_async", [False, True])
def test_candidate_attempt_exhausts_retries_without_emitting_failure(chat_async):
    model = MagicMock()
    model.chat.side_effect = RuntimeError("candidate unavailable")
    ctx, events, metrics, memory = _create_mock_runner_context(
        model, max_retries=2, retry_wait_interval_sec=0, chat_async=chat_async
    )
    request_id = uuid4()
    with pytest.raises(_ChatAttemptFailed) as caught:
        asyncio.run(
            _chat_with_retries(
                request_id,
                "candidate",
                [ChatMessage.user("hello")],
                None,
                None,
                ctx,
                durable_id="chat:router:candidate",
            )
        )
    assert str(caught.value.error) == "RuntimeError: candidate unavailable"
    assert caught.value.__cause__ is caught.value.error
    assert events == []
    assert model.chat.call_count == 3
    durable = ctx.durable_execute_async if chat_async else ctx.durable_execute
    assert [c.kwargs["durable_id"] for c in durable.call_args_list] == [
        "chat:router:candidate"
    ] * 3
    assert memory.get("_RETRY_STATS_CONTEXT")[str(request_id)]["total_retry_count"] == 2
    assert (
        metrics.get_sub_group("model_resource", "candidate")
        .get_counter("retryCount")
        .get_count()
        == 2
    )


def test_candidate_native_finalization_uses_attempt_identity():
    model = _native_chat_model()
    ctx, events, _, _ = _native_context(model)
    result = asyncio.run(
        _chat_with_retries(
            uuid4(),
            "candidate",
            [ChatMessage.user("hello")],
            None,
            OutputSchema(output_schema=_StructuredResult),
            ctx,
            durable_id="chat:router:candidate",
        )
    )
    assert result.structured_output is not None
    assert events == []
    assert [c.kwargs["durable_id"] for c in ctx.durable_execute.call_args_list] == [
        "chat:router:candidate",
        "chat:router:candidate:final",
    ]


def _budget_model() -> MagicMock:
    """Distinguish ordinary and final requests through the public model API."""
    model = MagicMock()
    model.prepare_request_messages.side_effect = lambda messages, args: messages

    def respond(messages: list[ChatMessage], **kwargs: Any) -> ChatResult:
        if messages and messages[-1].text.startswith("Give your final answer now"):
            return model.final_chat(messages, **kwargs)
        return model.inference(messages, **kwargs)

    model.chat.side_effect = respond
    return model


def test_hard_turn_limit_allows_last_batch_and_stops_next_normal_turn() -> None:
    model = _budget_model()
    calls = [ToolCallBlock(call_id=k, name="lookup", input={}) for k in ("a", "b")]
    model.inference.return_value = ChatResult(message=ChatMessage.assistant(calls))
    ctx, events, _, memory = _create_mock_runner_context(model)
    request_id = uuid4()
    memory.set(
        "_LOOP_BUDGET_CONTEXT", {str(request_id): {"turns_left": 1, "mode": "HARD"}}
    )
    asyncio.run(chat(request_id, "model", [ChatMessage.user("hi")], {}, None, ctx))
    batch = events[0]
    assert isinstance(batch, ToolRequestEvent)
    assert batch.tool_calls == calls
    result = ToolResponseEvent(request_id=batch.id, responses={"a": "A", "b": "B"})
    asyncio.run(process_chat_request_or_tool_response(result, ctx))
    assert len(events) == 2
    assert events[1].request_id == request_id
    assert events[1].is_failed
    assert "TURN_BUDGET_EXCEEDED" in events[1].error
    model.inference.assert_called_once()


@pytest.mark.parametrize("valid", [True, False])
def test_turn_budget_closes_with_structured_conversion_and_retries(valid: bool) -> None:
    model = _budget_model()
    model.inference.return_value = ChatResult(
        message=ChatMessage.assistant(
            [ToolCallBlock(call_id="a", name="lookup", input={})]
        )
    )
    model.final_chat.return_value = ChatResult(
        message=ChatMessage.assistant('{"result":42}' if valid else "invalid"),
        usage=TokenUsage(prompt_tokens=3, completion_tokens=2),
    )
    tool_result = model.inference.return_value
    model.inference.side_effect = lambda *args, **kwargs: (
        ChatResult(
            message=ChatMessage.assistant('{"result":42}' if valid else "invalid")
        )
        if "output_schema" in kwargs
        else tool_result
    )
    ctx, events, _, memory = _create_mock_runner_context(
        model, max_retries=1, retry_wait_interval_sec=0
    )
    model.will_apply_native_structured_output.return_value = True
    schema = OutputSchema(output_schema=_StructuredResult)
    request_id = uuid4()
    memory.set(
        "_LOOP_BUDGET_CONTEXT",
        {str(request_id): {"turns_left": 1, "tokens_left": 1, "mode": "SOFT"}},
    )
    asyncio.run(chat(request_id, "model", [ChatMessage.user("hi")], {}, schema, ctx))
    asyncio.run(
        process_chat_request_or_tool_response(
            ToolResponseEvent(request_id=events[0].id, responses={"a": "42"}), ctx
        )
    )
    assert len(events) == 2
    assert events[1].is_failed == (not valid)
    if valid:
        assert events[1].attributes["structured_output"].result == 42
    else:
        assert "TURN_BUDGET_EXCEEDED" in events[1].error
    assert model.inference.call_count == 1
    assert model.final_chat.call_count == (1 if valid else 2)
    (history,) = model.final_chat.call_args.args
    assert history[-2].role == MessageRole.TOOL


@pytest.mark.parametrize("soft", [False, True])
def test_token_limit_allows_current_round_native_schema_conversion(soft: bool) -> None:
    model = _budget_model()
    model.inference.return_value = ChatResult(
        message=ChatMessage.assistant("the answer is 42"),
        usage=TokenUsage(prompt_tokens=3, completion_tokens=2),
    )
    model.inference.side_effect = [
        model.inference.return_value,
        ChatResult(message=ChatMessage.assistant('{"result":42}')),
    ]
    ctx, events, _, memory = _create_mock_runner_context(model)
    model.will_apply_native_structured_output.return_value = True
    request_id = uuid4()
    memory.set(
        "_LOOP_BUDGET_CONTEXT",
        {str(request_id): {"tokens_left": 5, "mode": "SOFT" if soft else "HARD"}},
    )
    asyncio.run(
        chat(
            request_id,
            "model",
            [ChatMessage.user("compute")],
            {},
            OutputSchema(output_schema=_StructuredResult),
            ctx,
            user_context={"task": "budgeted"},
        )
    )
    assert events[0].user_context == {"task": "budgeted"}
    assert len(events) == 1
    assert not events[0].is_failed
    assert events[0].attributes["structured_output"].result == 42
    assert model.inference.call_count == 2
    model.final_chat.assert_not_called()


@pytest.mark.parametrize(
    "request_budget",
    [None, ReActLoopBudget(max_turns=10, max_time=timedelta(seconds=60))],
)
def test_budget_initialization_preserves_existing_state_without_reading_config(
    request_budget,
) -> None:
    ctx, _, _, memory = _create_mock_runner_context(MagicMock())
    request = ChatRequestEvent(
        model="model", messages=[ChatMessage.user("retry")], budget=request_budget
    )
    original = {
        "turns_left": 1,
        "tokens_left": 3,
        "max_time_ms": 40,
        "started_at_ms": 20,
        "mode": "HARD",
    }
    budget_utils.save_budget_state(memory, request.id, original)
    ctx.config = MagicMock()
    ctx.config.get.side_effect = AssertionError("Must not read config on recovery")
    memory.set = MagicMock(wraps=memory.set)

    budget_utils.initialize(ctx, request)

    ctx.config.get.assert_not_called()
    ctx.durable_execute.assert_not_called()
    memory.set.assert_not_called()
    assert budget_utils.get_budget_state(memory, request.id) == original


def test_restored_loop_keeps_start_time_and_includes_recovery(monkeypatch) -> None:
    model = _budget_model()
    ctx, events, _, memory = _create_mock_runner_context(model)
    request_id = uuid4()
    memory.set(
        "_LOOP_BUDGET_CONTEXT",
        {str(request_id): {"max_time_ms": 40, "started_at_ms": 20, "mode": "HARD"}},
    )
    monkeypatch.setattr(
        "flink_agents.plan.actions.react_loop_budget_utils.time.time_ns",
        lambda: 1_000_000_000,
    )
    asyncio.run(chat(request_id, "model", [ChatMessage.user("retry")], {}, None, ctx))
    model.inference.assert_not_called()
    assert len(events) == 1
    assert events[0].is_failed
    assert "TIME_BUDGET_EXCEEDED" in events[0].error


@pytest.mark.parametrize("mode", ["soft", "hard"])
def test_final_answer_after_time_limit_is_accepted(mode, monkeypatch) -> None:
    now = 0
    monkeypatch.setattr(
        "flink_agents.plan.actions.react_loop_budget_utils.time.time_ns", lambda: now
    )
    model = _budget_model()

    def answer(*args: object, **kwargs: object) -> ChatResult:
        nonlocal now
        now = 2_000_000
        return ChatResult(message=ChatMessage.assistant("done"))

    model.inference.side_effect = answer
    ctx, events, _, _ = _create_mock_runner_context(model)
    request = ChatRequestEvent(
        "model",
        [ChatMessage.user("hi")],
        budget=ReActLoopBudget(max_time="1 ms", mode=mode),
    )
    asyncio.run(process_chat_request_or_tool_response(request, ctx))
    assert len(events) == 1
    assert events[0].is_success
    model.inference.assert_called_once()
    model.final_chat.assert_not_called()


@pytest.mark.parametrize(
    ("override", "limited"),
    [
        (None, True),
        (ReActLoopBudget(), False),
        (ReActLoopBudget(max_tokens=100), False),
    ],
)
def test_request_budget_replaces_global_limits(override, limited: bool) -> None:
    model = _budget_model()
    model.inference.side_effect = [
        ChatResult(
            message=ChatMessage.assistant(
                [ToolCallBlock(call_id="a", name="lookup", input={})]
            )
        ),
        ChatResult(message=ChatMessage.assistant("done")),
    ]
    ctx, events, _, _ = _create_mock_runner_context(model)
    ctx.config = AgentConfiguration(
        {
            "react-loop.budget.max-turns": 1,
            "react-loop.budget.max-time": "60 s",
            "react-loop.budget.mode": "hard",
        }
    )
    ctx.config.set(AgentExecutionOptions.CHAT_ASYNC, False)
    request = ChatRequestEvent("model", [ChatMessage.user("hi")], budget=override)
    asyncio.run(process_chat_request_or_tool_response(request, ctx))
    batch = events[0]
    asyncio.run(
        process_chat_request_or_tool_response(
            ToolResponseEvent(request_id=batch.id, responses={"a": "42"}), ctx
        )
    )
    assert events[1].is_failed == limited
    assert model.inference.call_count == (1 if limited else 2)


@pytest.mark.parametrize(
    ("turns", "tokens", "elapsed", "reason"),
    [
        (1, 10, 0, "TURN_BUDGET_EXCEEDED"),
        (2, 5, 0, "TOKEN_BUDGET_EXCEEDED"),
        (2, 10, 1, "TIME_BUDGET_EXCEEDED"),
    ],
)
def test_first_limit_stops_the_next_round(
    turns, tokens, elapsed, reason, monkeypatch
) -> None:
    model = _budget_model()
    model.inference.return_value = ChatResult(
        message=ChatMessage.assistant(
            [ToolCallBlock(call_id="a", name="lookup", input={})]
        ),
        usage=TokenUsage(prompt_tokens=2, completion_tokens=3),
    )
    ctx, events, _, _ = _create_mock_runner_context(model)
    now = 0
    monkeypatch.setattr(
        "flink_agents.plan.actions.react_loop_budget_utils.time.time_ns",
        lambda: now * 1_000_000,
    )
    budget = ReActLoopBudget(
        max_turns=turns, max_tokens=tokens, max_time="1 ms", mode="hard"
    )
    request = ChatRequestEvent("model", [ChatMessage.user("hi")], budget=budget)
    asyncio.run(process_chat_request_or_tool_response(request, ctx))
    (batch,) = events
    assert isinstance(batch, ToolRequestEvent)
    now = elapsed
    asyncio.run(
        process_chat_request_or_tool_response(
            ToolResponseEvent(request_id=batch.id, responses={"a": "42"}), ctx
        )
    )
    assert len(events) == 2
    assert events[1].request_id == request.id
    assert reason in events[1].error
    model.inference.assert_called_once()
    model.final_chat.assert_not_called()


@pytest.mark.parametrize("final_answer", [True, False])
def test_token_usage_spans_rounds_missing_reports_and_retries(
    final_answer, caplog
) -> None:
    model = _budget_model()
    batch = ChatMessage.assistant([ToolCallBlock(call_id="a", name="lookup", input={})])
    model.inference.side_effect = [
        ChatResult(
            message=batch, usage=TokenUsage(prompt_tokens=1, completion_tokens=1)
        ),
        ChatResult(message=batch),
        ChatResult(
            message=ChatMessage.assistant("partial"),
            finish_reason="length",
            usage=TokenUsage(prompt_tokens=2, completion_tokens=1),
        ),
        ChatResult(
            message=ChatMessage.assistant("done") if final_answer else batch,
            usage=TokenUsage(prompt_tokens=2, completion_tokens=3),
        ),
    ]
    ctx, events, _, _ = _create_mock_runner_context(model, retry_wait_interval_sec=0)
    request = ChatRequestEvent(
        "model",
        [ChatMessage.user("hi")],
        budget=ReActLoopBudget(max_tokens=5, mode="hard"),
    )
    asyncio.run(process_chat_request_or_tool_response(request, ctx))
    for _ in range(2 if final_answer else 3):
        assert isinstance(events[-1], ToolRequestEvent)
        asyncio.run(
            process_chat_request_or_tool_response(
                ToolResponseEvent(request_id=events[-1].id, responses={"a": "42"}), ctx
            )
        )
    end = events[-1]
    assert end.request_id == request.id
    assert end.is_failed == (not final_answer)
    if not final_answer:
        assert "TOKEN_BUDGET_EXCEEDED" in end.error
    assert "Missing token usage" in caplog.text
    assert model.inference.call_count == 4
    model.final_chat.assert_not_called()


def test_closing_tool_request_fails_without_dispatch() -> None:
    model = _budget_model()
    tool_reply = ChatResult(
        message=ChatMessage.assistant(
            [ToolCallBlock(call_id="unexpected", name="lookup", input={})]
        )
    )
    model.inference.return_value = tool_reply
    model.final_chat.return_value = tool_reply
    ctx, events, _, _ = _create_mock_runner_context(
        model, max_retries=1, retry_wait_interval_sec=0
    )
    request = ChatRequestEvent(
        "model", [ChatMessage.user("hi")], budget=ReActLoopBudget(max_turns=1)
    )
    asyncio.run(process_chat_request_or_tool_response(request, ctx))
    (first,) = events
    asyncio.run(
        process_chat_request_or_tool_response(
            ToolResponseEvent(request_id=first.id, responses={"unexpected": "42"}), ctx
        )
    )
    assert len(events) == 2
    assert isinstance(events[1], ChatResponseEvent)
    assert events[1].is_failed
    assert "TURN_BUDGET_EXCEEDED" in events[1].error
    assert model.final_chat.call_count == 1


@pytest.mark.parametrize("failure_stage", ["resource", "schema", "prepare", "model"])
def test_soft_failure_retains_cause_and_budget_reason(failure_stage: str) -> None:
    model = _budget_model()
    ctx, events, _, memory = _create_mock_runner_context(
        model, retry_wait_interval_sec=0
    )
    request_id = uuid4()
    memory.set(
        "_LOOP_BUDGET_CONTEXT", {str(request_id): {"turns_left": 0, "mode": "SOFT"}}
    )
    schema = None
    if failure_stage == "resource":
        ctx.get_resource.side_effect = ValueError("missing model")
        detail = "missing model"
    elif failure_stage == "schema":
        schema = OutputSchema(output_schema=_StructuredResult)
        model.will_apply_native_structured_output.side_effect = ValueError(
            "unsupported schema"
        )
        detail = "unsupported schema"
    elif failure_stage == "prepare":
        model.prepare_request_messages.side_effect = ValueError("invalid prompt")
        detail = "invalid prompt"
    else:
        model.final_chat.side_effect = ValueError("provider failed")
        detail = "provider failed"
    asyncio.run(chat(request_id, "model", [ChatMessage.user("hi")], {}, schema, ctx))
    assert len(events) == 1
    assert events[0].is_failed
    assert events[0].error.count("Soft finalization failed:") == 1
    assert detail in events[0].error
    assert "budget reason: TURN_BUDGET_EXCEEDED" in events[0].error
    model.inference.assert_not_called()


@pytest.mark.parametrize("soft", [False, True])
def test_time_limit_allows_round_retries_and_conversion(soft, monkeypatch) -> None:
    model = _budget_model()
    ctx, events, _, memory = _create_mock_runner_context(
        model, max_retries=1, retry_wait=timedelta(milliseconds=100)
    )
    model.will_apply_native_structured_output.return_value = True
    now = 0
    calls = 0
    monkeypatch.setattr(
        "flink_agents.plan.actions.react_loop_budget_utils.time.time_ns",
        lambda: now * 1_000_000,
    )

    def sleep(seconds: float) -> None:
        nonlocal now
        assert seconds == 0.1
        now += 100

    monkeypatch.setattr("flink_agents.plan.actions.chat_model_action.time.sleep", sleep)

    def respond(*args: Any, **kwargs: Any) -> ChatResult:
        nonlocal calls
        calls += 1
        if calls == 1:
            message = "retry"
            raise RuntimeError(message)
        return ChatResult(
            message=ChatMessage.assistant(
                '{"result":42}' if "output_schema" in kwargs else "the answer is 42"
            )
        )

    model.inference.side_effect = respond
    request_id = uuid4()
    memory.set(
        "_LOOP_BUDGET_CONTEXT",
        {
            str(request_id): {
                "max_time_ms": 60,
                "started_at_ms": 0,
                "turns_left": 1,
                "mode": "SOFT" if soft else "HARD",
            }
        },
    )
    asyncio.run(
        chat(
            request_id,
            "model",
            [ChatMessage.user("hi")],
            {},
            OutputSchema(output_schema=_StructuredResult),
            ctx,
        )
    )
    assert len(events) == 1
    assert not events[0].is_failed
    assert events[0].structured_output.result == 42
    assert events[0].retry_count == 1
    assert events[0].total_retry_wait_ms == 100
    assert now == 100
    assert model.inference.call_count == 3
    model.final_chat.assert_not_called()


@pytest.mark.parametrize(
    "reason", ["TIME_BUDGET_EXCEEDED", "TOKEN_BUDGET_EXCEEDED", "TURN_BUDGET_EXCEEDED"]
)
def test_exhausted_budget_does_not_consume_turn(reason: str) -> None:
    memory = _MockMemoryObject()
    request_id = uuid4()
    budget_state = {
        "turns_left": 0 if reason == "TURN_BUDGET_EXCEEDED" else 2,
        "tokens_left": 0 if reason == "TOKEN_BUDGET_EXCEEDED" else 10,
        "max_time_ms": 60,
        "started_at_ms": 0,
        "mode": "HARD",
    }
    budget_utils.save_budget_state(memory, request_id, budget_state)
    original = dict(budget_state)
    now = 60 if reason == "TIME_BUDGET_EXCEEDED" else 20
    assert budget_utils._try_start_turn(memory, request_id, now) == reason
    assert budget_state == original
    assert budget_utils.get_budget_state(memory, request_id) == original


def test_unlimited_turn_does_not_create_state() -> None:
    memory = _MockMemoryObject()
    request_id = uuid4()
    assert budget_utils._try_start_turn(memory, request_id, 0) is None
    assert budget_utils.get_budget_state(memory, request_id) is None


@pytest.mark.parametrize("native_conversion", [False, True])
def test_final_turn_tool_calls_fail_without_another_conversion(
    native_conversion: bool,
) -> None:
    model = _budget_model()
    tool_reply = ChatResult(
        message=ChatMessage.assistant(
            [ToolCallBlock(call_id="unexpected", name="lookup", input={})]
        )
    )
    model.final_chat.return_value = tool_reply
    model.inference.return_value = tool_reply
    ctx, events, _, memory = _create_mock_runner_context(
        model, max_retries=3, retry_wait_interval_sec=0
    )
    model.will_apply_native_structured_output.return_value = native_conversion
    request_id = uuid4()
    budget_utils.save_budget_state(
        memory, request_id, {"turns_left": 0, "mode": "SOFT"}
    )
    schema = OutputSchema(output_schema=_StructuredResult)
    asyncio.run(
        chat(
            request_id,
            "model",
            [ChatMessage.user("hi")],
            {},
            schema,
            ctx,
        )
    )
    assert len(events) == 1
    assert isinstance(events[0], ChatResponseEvent)
    assert events[0].is_failed
    assert "returned tool calls" in events[0].error
    assert "TURN_BUDGET_EXCEEDED" in events[0].error
    model.final_chat.assert_called_once()
    assert model.final_chat.call_args.kwargs["tools"] == []
    assert model.final_chat.call_args.kwargs["output_schema"] is (
        schema if native_conversion else None
    )
    assert model.inference.call_count == 0
    assert budget_utils.get_budget_state(memory, request_id) is None


def test_reused_response_reconstructs_token_and_turn_usage() -> None:
    """A cached response is charged once in the rebuilt pre-action memory."""
    model = _budget_model()
    model.inference.return_value = ChatResult(
        message=ChatMessage.assistant("done"),
        usage=TokenUsage(prompt_tokens=3, completion_tokens=2),
    )
    request_id = uuid4()
    original = {"turns_left": 2, "tokens_left": 20, "mode": "HARD"}
    ctx, events, _, memory = _create_mock_runner_context(model)
    budget_utils.save_budget_state(memory, request_id, dict(original))
    cached = []

    class Crash(BaseException):
        pass

    def persist_then_crash(
        fn: Callable, *args: Any, durable_id: str | None = None, **kwargs: Any
    ) -> None:
        cached.append((durable_id, fn(*args, **kwargs)))
        raise Crash

    ctx.durable_execute.side_effect = persist_then_crash
    with pytest.raises(Crash):
        asyncio.run(chat(request_id, "model", [ChatMessage.user("hi")], {}, None, ctx))
    assert events == []
    restored, events, _, memory = _create_mock_runner_context(model)
    budget_utils.save_budget_state(memory, request_id, dict(original))

    def replay(
        fn: Callable, *args: Any, durable_id: str | None = None, **kwargs: Any
    ) -> Any:
        assert len(cached) == 1
        recorded_id, result = cached.pop()
        assert durable_id == recorded_id
        return result

    restored.durable_execute.side_effect = replay
    states = []
    original_set = memory.set

    def capture(path: str, value: Any) -> None:
        if path == "_LOOP_BUDGET_CONTEXT" and str(request_id) in value:
            states.append(dict(value[str(request_id)]))
        original_set(path, value)

    memory.set = capture
    asyncio.run(chat(request_id, "model", [ChatMessage.user("hi")], {}, None, restored))
    assert not cached
    model.inference.assert_called_once()
    assert len(events) == 1
    assert states[-1]["turns_left"] == 1
    assert states[-1]["tokens_left"] == 15


def test_budget_updates_read_latest_state_and_preserve_memory_snapshots() -> None:
    memory = _MockMemoryObject()
    request_id, other_id = uuid4(), uuid4()
    budget_utils.save_budget_state(
        memory, request_id, {"turns_left": 2, "tokens_left": 20, "mode": "HARD"}
    )
    budget_utils.save_budget_state(
        memory, other_id, {"turns_left": 4, "tokens_left": 50, "mode": "SOFT"}
    )
    before = memory.get("_LOOP_BUDGET_CONTEXT")
    assert budget_utils._try_start_turn(memory, request_id, 0) is None
    budget_utils.update_token_budget_state(
        memory,
        request_id,
        ChatResult(
            message=ChatMessage.assistant("judge"),
            usage=TokenUsage(prompt_tokens=10, completion_tokens=5),
        ),
    )
    after_judge = budget_utils.get_budget_state(memory, request_id)
    budget_utils.update_token_budget_state(
        memory,
        request_id,
        ChatResult(
            message=ChatMessage.assistant("target"),
            usage=TokenUsage(prompt_tokens=1, completion_tokens=1),
        ),
    )
    assert budget_utils.get_budget_state(memory, request_id) == {
        "turns_left": 1,
        "tokens_left": 3,
        "mode": "HARD",
    }
    budget_utils.update_token_budget_state(
        memory,
        request_id,
        ChatResult(
            message=ChatMessage.assistant("last"),
            usage=TokenUsage(prompt_tokens=4, completion_tokens=3),
        ),
    )
    assert budget_utils.get_budget_state(memory, request_id) == {
        "turns_left": 1,
        "tokens_left": 0,
        "mode": "HARD",
    }
    assert (
        budget_utils._try_start_turn(memory, request_id, 0) == "TOKEN_BUDGET_EXCEEDED"
    )
    assert after_judge == {"turns_left": 1, "tokens_left": 5, "mode": "HARD"}
    assert before[str(request_id)] == {
        "turns_left": 2,
        "tokens_left": 20,
        "mode": "HARD",
    }
    assert budget_utils.get_budget_state(memory, other_id) == {
        "turns_left": 4,
        "tokens_left": 50,
        "mode": "SOFT",
    }


@pytest.mark.parametrize(
    ("crash_after", "mode", "expired", "chat_async"),
    [
        pytest.param(1, "hard", False, False, id="start-before-deadline"),
        pytest.param(1, "hard", True, False, id="start-hard-timeout"),
        pytest.param(1, "soft", True, True, id="start-soft-timeout"),
        pytest.param(2, "hard", True, False, id="admission-sync-after-deadline"),
        pytest.param(2, "soft", True, True, id="admission-async-after-deadline"),
        pytest.param(3, "soft", True, False, id="response-sync-after-deadline"),
        pytest.param(3, "hard", True, True, id="response-async-after-deadline"),
    ],
)
def test_first_action_restores_durable_start_time(
    monkeypatch, crash_after, mode, expired, chat_async
):
    model = _budget_model()
    model.inference.return_value = ChatResult(message=ChatMessage.assistant("done"))
    model.final_chat.return_value = ChatResult(message=ChatMessage.assistant("closed"))
    now = 1000
    monkeypatch.setattr(
        "flink_agents.plan.actions.react_loop_budget_utils.time.time_ns",
        lambda: now * 1_000_000,
    )
    request = ChatRequestEvent(
        "model",
        [ChatMessage.user("hi")],
        budget=ReActLoopBudget(max_time="300 ms", mode=mode),
    )
    ctx, events, _, _ = _create_mock_runner_context(model, chat_async=chat_async)
    journal = []

    class Crash(BaseException):
        pass

    def persist(
        fn: Callable, *args: Any, durable_id: str | None = None, **kwargs: Any
    ) -> Any:
        result = fn(*args, **kwargs)
        journal.append((durable_id, result))
        if len(journal) == crash_after:
            raise Crash
        return result

    async def persist_async(fn: Callable, *args: Any, **kwargs: Any) -> Any:
        return persist(fn, *args, **kwargs)

    ctx.durable_execute_async.side_effect = persist_async
    ctx.durable_execute.side_effect = persist
    with pytest.raises(Crash):
        asyncio.run(process_chat_request_or_tool_response(request, ctx))
    assert events == []
    assert journal[0] == ("react-loop:budget-start", 1000)
    assert model.inference.call_count == (1 if crash_after == 3 else 0)
    assert ctx.durable_execute_async.call_count == (
        1 if chat_async and crash_after == 3 else 0
    )
    now = 1400 if expired else 1100
    restored, events, _, _ = _create_mock_runner_context(model, chat_async=chat_async)

    def replay(
        fn: Callable, *args: Any, durable_id: str | None = None, **kwargs: Any
    ) -> Any:
        if journal:
            assert durable_id == journal[0][0]
            return journal.pop(0)[1]
        return fn(*args, **kwargs)

    async def replay_async(fn: Callable, *args: Any, **kwargs: Any) -> Any:
        return replay(fn, *args, **kwargs)

    restored.durable_execute_async.side_effect = replay_async
    restored.durable_execute.side_effect = replay
    asyncio.run(process_chat_request_or_tool_response(request, restored))
    assert journal == []
    timed_out = expired and crash_after == 1
    assert model.inference.call_count == (0 if timed_out else 1)
    assert len(events) == 1
    if not timed_out:
        assert not events[0].is_failed
        model.final_chat.assert_not_called()
    elif mode == "hard":
        assert "TIME_BUDGET_EXCEEDED" in events[0].error
        model.final_chat.assert_not_called()
    else:
        model.final_chat.assert_called_once()
        assert not events[0].is_failed
