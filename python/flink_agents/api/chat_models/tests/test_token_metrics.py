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
"""Test cases for BaseChatModelConnection token metrics functionality."""
from typing import Any, List, Sequence
from unittest.mock import MagicMock

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import BaseChatModelConnection
from flink_agents.api.metric_group import Counter, MetricGroup
from flink_agents.api.resource import ResourceType
from flink_agents.api.tools.tool import Tool


class TestChatModelConnection(BaseChatModelConnection):
    """Test implementation of BaseChatModelConnection for testing purposes."""

    @classmethod
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.CHAT_MODEL_CONNECTION

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool] | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        """Simple test implementation."""
        return ChatMessage(role=MessageRole.ASSISTANT, content="Test response")

    def test_record_token_metrics(
        self, model_name: str, prompt_tokens: int, completion_tokens: int
    ) -> None:
        """Expose protected method for testing."""
        self._record_token_metrics(model_name, prompt_tokens, completion_tokens)


class _MockCounter(Counter):
    """Mock implementation of Counter for testing."""

    def __init__(self) -> None:
        self._count = 0

    def inc(self, n: int = 1) -> None:
        self._count += n

    def dec(self, n: int = 1) -> None:
        self._count -= n

    def get_count(self) -> int:
        return self._count


class _MockMetricGroup(MetricGroup):
    """Mock implementation of MetricGroup for testing."""

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


class TestBaseChatModelConnectionTokenMetrics:
    """Test cases for BaseChatModelConnection token metrics functionality."""

    def test_record_token_metrics_with_metric_group(self) -> None:
        """Test token metrics are recorded when metric group is set."""
        connection = TestChatModelConnection()
        mock_metric_group = _MockMetricGroup()

        # Set the metric group
        connection.set_metric_group(mock_metric_group)

        # Record token metrics
        connection.test_record_token_metrics("gpt-4", 100, 50)

        # Verify the metrics were recorded
        model_group = mock_metric_group.get_sub_group("gpt-4")
        assert model_group.get_counter("promptTokens").get_count() == 100
        assert model_group.get_counter("completionTokens").get_count() == 50

    def test_record_token_metrics_without_metric_group(self) -> None:
        """Test token metrics are not recorded when metric group is null."""
        connection = TestChatModelConnection()

        # Do not set metric group (should be None by default)
        # Record token metrics - should not throw
        connection.test_record_token_metrics("gpt-4", 100, 50)
        # No exception should be raised

    def test_token_metrics_hierarchy(self) -> None:
        """Test token metrics hierarchy: actionMetricGroup -> modelName -> counters."""
        connection = TestChatModelConnection()
        mock_metric_group = _MockMetricGroup()

        # Set the metric group
        connection.set_metric_group(mock_metric_group)

        # Record for gpt-4
        connection.test_record_token_metrics("gpt-4", 100, 50)

        # Record for gpt-3.5-turbo
        connection.test_record_token_metrics("gpt-3.5-turbo", 200, 100)

        # Verify each model has its own counters
        gpt4_group = mock_metric_group.get_sub_group("gpt-4")
        gpt35_group = mock_metric_group.get_sub_group("gpt-3.5-turbo")

        assert gpt4_group.get_counter("promptTokens").get_count() == 100
        assert gpt4_group.get_counter("completionTokens").get_count() == 50
        assert gpt35_group.get_counter("promptTokens").get_count() == 200
        assert gpt35_group.get_counter("completionTokens").get_count() == 100

    def test_token_metrics_accumulation(self) -> None:
        """Test that token metrics accumulate across multiple calls."""
        connection = TestChatModelConnection()
        mock_metric_group = _MockMetricGroup()

        # Set the metric group
        connection.set_metric_group(mock_metric_group)

        # Record multiple times for the same model
        connection.test_record_token_metrics("gpt-4", 100, 50)
        connection.test_record_token_metrics("gpt-4", 150, 75)

        # Verify the metrics accumulated
        model_group = mock_metric_group.get_sub_group("gpt-4")
        assert model_group.get_counter("promptTokens").get_count() == 250
        assert model_group.get_counter("completionTokens").get_count() == 125

    def test_resource_type(self) -> None:
        """Test resource type is CHAT_MODEL_CONNECTION."""
        connection = TestChatModelConnection()
        assert connection.resource_type() == ResourceType.CHAT_MODEL_CONNECTION

    def test_bound_metric_group_property(self) -> None:
        """Test bound_metric_group property."""
        connection = TestChatModelConnection()

        # Initially should be None
        assert connection.metric_group is None

        # Set metric group
        mock_metric_group = _MockMetricGroup()
        connection.set_metric_group(mock_metric_group)

        # Now should return the set metric group
        assert connection.metric_group is mock_metric_group

