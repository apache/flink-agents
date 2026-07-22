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

from typing import Any, Dict, Sequence
from unittest.mock import MagicMock

from flink_agents.api.chat_message import ChatMessage, MessageRole
from flink_agents.api.chat_models.chat_model import (
    BaseChatModelSetup,
)
from flink_agents.api.metric_group import Counter, MetricGroup
from flink_agents.api.resource import ResourceType


class TestChatModelSetup(BaseChatModelSetup):
    """Test implementation of BaseChatModelConnection for testing purposes."""

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return model kwargs."""
        return {}

    @classmethod
    def resource_type(cls) -> ResourceType:
        """Return resource type of class."""
        return ResourceType.CHAT_MODEL

    def chat(self, messages: Sequence[ChatMessage], **kwargs: Any) -> ChatMessage:
        """Simple test implementation."""
        return ChatMessage(role=MessageRole.ASSISTANT, content="Test response")

    def test_record_token_metrics(
        self,
        model_name: str,
        prompt_tokens: int,
        completion_tokens: int,
        metric_group: MetricGroup | None,
    ) -> None:
        """Expose protected method for testing."""
        self._record_token_metrics(
            model_name, prompt_tokens, completion_tokens, metric_group
        )


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


class TestBaseChatModelTokenMetrics:
    """Test cases for BaseChatModelConnection token metrics functionality."""

    def test_record_token_metrics_with_metric_group(self) -> None:
        """Test token metrics are recorded when metric group is set."""
        chat_model = TestChatModelSetup(connection="mock", model="mock-model")
        mock_metric_group = _MockMetricGroup()

        # Record token metrics
        chat_model.test_record_token_metrics("gpt-4", 100, 50, mock_metric_group)

        # Verify the metrics were recorded
        model_group = mock_metric_group.get_sub_group("model", "gpt-4")
        assert model_group.get_counter("promptTokens").get_count() == 100
        assert model_group.get_counter("completionTokens").get_count() == 50

    def test_record_token_metrics_skips_when_metric_group_missing(self) -> None:
        """Test token metrics are not recorded when metric group is missing."""
        chat_model = TestChatModelSetup(connection="mock", model="mock-model")
        bound_metric_group = _MockMetricGroup()
        chat_model.set_metric_group(bound_metric_group)

        chat_model.test_record_token_metrics("gpt-4", 100, 50, None)

        model_group = bound_metric_group.get_sub_group("model", "gpt-4")
        assert model_group.get_counter("promptTokens").get_count() == 0
        assert model_group.get_counter("completionTokens").get_count() == 0

    def test_record_token_metrics_with_request_scoped_metric_group(self) -> None:
        """Token metrics use the metric group captured when the request started."""
        chat_model = TestChatModelSetup(connection="mock", model="mock-model")
        action_a_metric_group = _MockMetricGroup()
        action_b_metric_group = _MockMetricGroup()

        chat_model.set_metric_group(action_a_metric_group)
        request_metric_group = chat_model.metric_group

        chat_model.set_metric_group(action_b_metric_group)
        chat_model.test_record_token_metrics(
            "gpt-4", 100, 50, metric_group=request_metric_group
        )

        action_a_model_group = action_a_metric_group.get_sub_group("model", "gpt-4")
        assert action_a_model_group.get_counter("promptTokens").get_count() == 100
        assert action_a_model_group.get_counter("completionTokens").get_count() == 50

        action_b_model_group = action_b_metric_group.get_sub_group("model", "gpt-4")
        assert action_b_model_group.get_counter("promptTokens").get_count() == 0
        assert action_b_model_group.get_counter("completionTokens").get_count() == 0

    def test_token_metrics_hierarchy(self) -> None:
        """Test token metrics hierarchy: actionMetricGroup -> modelName -> counters."""
        chat_model = TestChatModelSetup(connection="mock", model="mock-model")
        mock_metric_group = _MockMetricGroup()

        # Record for gpt-4
        chat_model.test_record_token_metrics("gpt-4", 100, 50, mock_metric_group)

        # Record for gpt-3.5-turbo
        chat_model.test_record_token_metrics(
            "gpt-3.5-turbo", 200, 100, mock_metric_group
        )

        # Verify each model has its own counters
        gpt4_group = mock_metric_group.get_sub_group("model", "gpt-4")
        gpt35_group = mock_metric_group.get_sub_group("model", "gpt-3.5-turbo")

        assert gpt4_group.get_counter("promptTokens").get_count() == 100
        assert gpt4_group.get_counter("completionTokens").get_count() == 50
        assert gpt35_group.get_counter("promptTokens").get_count() == 200
        assert gpt35_group.get_counter("completionTokens").get_count() == 100

    def test_token_metrics_accumulation(self) -> None:
        """Test that token metrics accumulate across multiple calls."""
        chat_model = TestChatModelSetup(connection="mock", model="mock-model")
        mock_metric_group = _MockMetricGroup()

        # Record multiple times for the same model
        chat_model.test_record_token_metrics("gpt-4", 100, 50, mock_metric_group)
        chat_model.test_record_token_metrics("gpt-4", 150, 75, mock_metric_group)

        # Verify the metrics accumulated
        model_group = mock_metric_group.get_sub_group("model", "gpt-4")
        assert model_group.get_counter("promptTokens").get_count() == 250
        assert model_group.get_counter("completionTokens").get_count() == 125

    def test_resource_type(self) -> None:
        """Test resource type is CHAT_MODEL_CONNECTION."""
        chat_model = TestChatModelSetup(connection="mock", model="mock-model")
        assert chat_model.resource_type() == ResourceType.CHAT_MODEL

    def test_bound_metric_group_property(self) -> None:
        """Test bound_metric_group property."""
        chat_model = TestChatModelSetup(connection="mock", model="mock-model")

        # Initially should be None
        assert chat_model.metric_group is None

        # Set metric group
        mock_metric_group = _MockMetricGroup()
        chat_model.set_metric_group(mock_metric_group)

        # Now should return the set metric group
        assert chat_model.metric_group is mock_metric_group
