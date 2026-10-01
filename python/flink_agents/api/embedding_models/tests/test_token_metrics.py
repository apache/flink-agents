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
#  limitations under the License.
#################################################################################
"""Test cases for BaseEmbeddingModelSetup token usage metrics.

Mirrors ``test_token_metrics.py`` for the chat side: embedding providers already
populate ``EmbeddingTokenUsage`` on the returned ``EmbeddingResult``, but nothing
records it until this setup reads it back inside ``embed_with_usage``.
"""

from typing import Any, Dict, Sequence
from unittest.mock import MagicMock

from flink_agents.api.embedding_models.embedding_model import (
    BaseEmbeddingModelConnection,
    BaseEmbeddingModelSetup,
    EmbeddingResult,
    EmbeddingTokenUsage,
)
from flink_agents.api.metric_group import Counter, MetricGroup
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.resource_context import ResourceContext


class _EmbeddingConnectionWithUsage(BaseEmbeddingModelConnection):
    def embed(
        self, text: str | Sequence[str], **kwargs: Any
    ) -> list[float] | list[list[float]]:
        if isinstance(text, str):
            return [0.1, 0.2]
        return [[0.1, 0.2] for _ in text]

    def embed_with_usage(
        self, text: str | Sequence[str], **kwargs: Any
    ) -> EmbeddingResult[list[float] | list[list[float]]]:
        return EmbeddingResult(
            embeddings=self.embed(text, **kwargs),
            token_usage=EmbeddingTokenUsage(prompt_tokens=7, total_tokens=9),
        )


class _EmbeddingConnectionWithoutUsage(BaseEmbeddingModelConnection):
    def embed(
        self, text: str | Sequence[str], **kwargs: Any
    ) -> list[float] | list[list[float]]:
        if isinstance(text, str):
            return [0.1, 0.2]
        return [[0.1, 0.2] for _ in text]


class _TestEmbeddingModelSetup(BaseEmbeddingModelSetup):
    @property
    def model_kwargs(self) -> Dict[str, Any]:
        return {}


class _MockCounter(Counter):
    def __init__(self) -> None:
        self._count = 0

    def inc(self, n: int = 1) -> None:
        self._count += n

    def dec(self, n: int = 1) -> None:
        self._count -= n

    def get_count(self) -> int:
        return self._count


class _MockMetricGroup(MetricGroup):
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


def _make_setup(connection: BaseEmbeddingModelConnection) -> _TestEmbeddingModelSetup:
    def get_resource(name: str, resource_type: ResourceType) -> Resource:
        assert name == "mock-connection"
        assert resource_type == ResourceType.EMBEDDING_MODEL_CONNECTION
        return connection

    ctx = MagicMock(spec=ResourceContext)
    ctx.get_resource = get_resource
    setup = _TestEmbeddingModelSetup(
        name="embedding",
        connection="mock-connection",
        model="mock-model",
        resource_context=ctx,
    )
    setup.open()
    return setup


def test_embed_with_usage_records_token_metrics() -> None:
    """embed_with_usage records provider usage onto the model metric group."""
    setup = _make_setup(_EmbeddingConnectionWithUsage(name="connection"))
    mock_metric_group = _MockMetricGroup()
    setup.set_metric_group(mock_metric_group)

    result = setup.embed_with_usage("hello")

    # usage still flows back to the caller ...
    assert result.token_usage == EmbeddingTokenUsage(prompt_tokens=7, total_tokens=9)
    # ... and the same usage was recorded as metrics
    model_group = mock_metric_group.get_sub_group("model", "mock-model")
    assert model_group.get_counter("promptTokens").get_count() == 7
    assert model_group.get_counter("totalTokens").get_count() == 9


def test_embed_with_usage_records_token_metrics_batch() -> None:
    """embed_with_usage records provider usage for batch inputs too."""
    setup = _make_setup(_EmbeddingConnectionWithUsage(name="connection"))
    mock_metric_group = _MockMetricGroup()
    setup.set_metric_group(mock_metric_group)

    setup.embed_with_usage(["hello", "world"])

    model_group = mock_metric_group.get_sub_group("model", "mock-model")
    assert model_group.get_counter("promptTokens").get_count() == 7
    assert model_group.get_counter("totalTokens").get_count() == 9


def test_embed_with_usage_without_usage_records_nothing() -> None:
    """When the provider reports no usage, no metrics are recorded."""
    setup = _make_setup(_EmbeddingConnectionWithoutUsage(name="connection"))
    mock_metric_group = _MockMetricGroup()
    setup.set_metric_group(mock_metric_group)

    setup.embed_with_usage("hello")

    # model group is only created when a counter is requested; absent means nothing recorded
    assert "model=mock-model" not in mock_metric_group._sub_groups


def test_embed_with_usage_without_metric_group_returns_usage() -> None:
    """Without a bound metric group, usage still flows back; only metrics are skipped."""
    setup = _make_setup(_EmbeddingConnectionWithUsage(name="connection"))
    # no set_metric_group call

    result = setup.embed_with_usage("hello")

    assert result.token_usage == EmbeddingTokenUsage(prompt_tokens=7, total_tokens=9)


def test_token_metrics_accumulate() -> None:
    """Counters accumulate across multiple embedding calls."""
    setup = _make_setup(_EmbeddingConnectionWithUsage(name="connection"))
    mock_metric_group = _MockMetricGroup()
    setup.set_metric_group(mock_metric_group)

    setup.embed_with_usage("a")
    setup.embed_with_usage("b")

    model_group = mock_metric_group.get_sub_group("model", "mock-model")
    assert model_group.get_counter("promptTokens").get_count() == 14
    assert model_group.get_counter("totalTokens").get_count() == 18


def test_token_metrics_without_metric_group_is_noop() -> None:
    """record_token_metrics must not throw when no metric group is bound."""
    setup = _make_setup(_EmbeddingConnectionWithUsage(name="connection"))

    # no set_metric_group call -> metric_group is None
    setup._record_token_metrics("mock-model", 7, 9)
    # no exception raised
