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
from unittest.mock import MagicMock
from uuid import uuid4

import pytest

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.core_options import (
    AgentExecutionOptions,
    ErrorHandlingStrategy,
)
from flink_agents.api.events.chat_event import ChatResponseEvent
from flink_agents.api.metric_group import Counter, MetricGroup
from flink_agents.plan.actions.chat_model_action import chat

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

    def get_sub_group(self, name: str) -> "_MockMetricGroup":
        if name not in self._sub_groups:
            self._sub_groups[name] = _MockMetricGroup()
        return self._sub_groups[name]

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
        side_effect=lambda option: option_values.get(id(option), option.get_default_value())
    )

    ctx = MagicMock()
    ctx.config = config
    ctx.sensory_memory = sensory_memory
    ctx.action_metric_group = metric_group
    ctx.send_event = MagicMock(side_effect=lambda e: sent_events.append(e))
    ctx.get_resource = MagicMock(return_value=chat_model)
    ctx.durable_execute = MagicMock(side_effect=lambda fn, *args, **kwargs: fn(*args, **kwargs))

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
            chat(request_id, chat_model.connection, [ChatMessage(role=MessageRole.USER, content="hi")], None, ctx)
        )

        assert len(sent_events) == 1
        event = sent_events[0]
        assert isinstance(event, ChatResponseEvent)
        assert event.retry_count == 0
        assert event.total_retry_wait_sec == 0

        # No retry metrics should be recorded
        assert len(metric_group._sub_groups) == 0

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
            chat(request_id, "test-model", [ChatMessage(role=MessageRole.USER, content="hi")], None, ctx)
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
        model_group = metric_group.get_sub_group(chat_model.connection)
        assert model_group.get_counter("retryCount").get_count() == 1
        assert model_group.get_counter("retryWaitSec").get_count() == 1

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
                chat(request_id, "test-model", [ChatMessage(role=MessageRole.USER, content="hi")], None, ctx)
            )

        assert len(sent_events) == 0


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
