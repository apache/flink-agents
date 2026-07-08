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
from typing import Any, Dict, Sequence
from unittest.mock import MagicMock

import pytest

from flink_agents.api.embedding_models.embedding_model import (
    BaseEmbeddingModelConnection,
    BaseEmbeddingModelSetup,
    EmbeddingResult,
    EmbeddingTokenUsage,
)
from flink_agents.api.metric_group import Counter, MetricGroup
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.resource_context import ResourceContext


class FakeEmbeddingModelConnection(BaseEmbeddingModelConnection):
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


class FakeEmbeddingModelConnectionWithoutUsage(BaseEmbeddingModelConnection):
    def embed(
        self, text: str | Sequence[str], **kwargs: Any
    ) -> list[float] | list[list[float]]:
        if isinstance(text, str):
            return [0.1, 0.2]
        return [[0.1, 0.2] for _ in text]


class ThrowThenReportUsageConnection(BaseEmbeddingModelConnection):
    def __init__(self, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self._calls = 0

    def embed(
        self, text: str | Sequence[str], **kwargs: Any
    ) -> list[float] | list[list[float]]:
        if isinstance(text, str):
            return [0.1, 0.2]
        return [[0.1, 0.2] for _ in text]

    def embed_with_usage(
        self, text: str | Sequence[str], **kwargs: Any
    ) -> EmbeddingResult[list[float] | list[list[float]]]:
        self._calls += 1
        if self._calls == 1:
            msg = "provider failure"
            raise RuntimeError(msg)
        return EmbeddingResult(
            embeddings=self.embed(text, **kwargs),
            token_usage=EmbeddingTokenUsage(prompt_tokens=3, total_tokens=4),
        )


class FakeEmbeddingModelSetup(BaseEmbeddingModelSetup):
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


def _make_setup(connection: BaseEmbeddingModelConnection) -> FakeEmbeddingModelSetup:
    def get_resource(name: str, resource_type: ResourceType) -> Resource:
        assert name == "mock-connection"
        assert resource_type == ResourceType.EMBEDDING_MODEL_CONNECTION
        return connection

    ctx = MagicMock(spec=ResourceContext)
    ctx.get_resource = get_resource
    setup = FakeEmbeddingModelSetup(
        name="embedding",
        connection="mock-connection",
        model="mock-model",
        resource_context=ctx,
    )
    setup.open()
    return setup


def test_embedding_token_metrics_are_recorded_when_usage_is_reported() -> None:
    setup = _make_setup(FakeEmbeddingModelConnection(name="connection"))
    metric_group = _MockMetricGroup()

    result = setup.embed_with_usage("hello")
    setup.record_token_metrics(metric_group, result.token_usage)
    assert result.embeddings == [0.1, 0.2]

    model_group = metric_group.get_sub_group("model", "mock-model")
    assert model_group.get_counter("promptTokens").get_count() == 7
    assert model_group.get_counter("totalTokens").get_count() == 9


def test_embedding_token_metrics_are_noop_when_usage_is_absent() -> None:
    setup = _make_setup(FakeEmbeddingModelConnectionWithoutUsage(name="connection"))
    metric_group = _MockMetricGroup()

    result = setup.embed_with_usage("hello")
    setup.record_token_metrics(metric_group, result.token_usage)
    assert result.embeddings == [0.1, 0.2]

    model_group = metric_group.get_sub_group("model", "mock-model")
    assert "promptTokens" not in model_group._counters
    assert "totalTokens" not in model_group._counters


def test_embedding_token_metrics_are_noop_when_metric_group_is_absent() -> None:
    setup = _make_setup(FakeEmbeddingModelConnection(name="connection"))
    bound_metric_group = _MockMetricGroup()
    setup.set_metric_group(bound_metric_group)

    result = setup.embed_with_usage("hello")
    setup.record_token_metrics(None, result.token_usage)

    model_group = bound_metric_group.get_sub_group("model", "mock-model")
    assert model_group.get_counter("promptTokens").get_count() == 0
    assert model_group.get_counter("totalTokens").get_count() == 0


def test_embedding_token_metrics_use_request_scoped_metric_group() -> None:
    setup = _make_setup(FakeEmbeddingModelConnection(name="connection"))
    action_a_metric_group = _MockMetricGroup()
    action_b_metric_group = _MockMetricGroup()
    setup.set_metric_group(action_b_metric_group)

    result = setup.embed_with_usage("hello")
    setup.record_token_metrics(action_a_metric_group, result.token_usage)
    assert result.embeddings == [0.1, 0.2]

    action_a_model_group = action_a_metric_group.get_sub_group("model", "mock-model")
    assert action_a_model_group.get_counter("promptTokens").get_count() == 7
    assert action_a_model_group.get_counter("totalTokens").get_count() == 9

    action_b_model_group = action_b_metric_group.get_sub_group("model", "mock-model")
    assert action_b_model_group.get_counter("promptTokens").get_count() == 0
    assert action_b_model_group.get_counter("totalTokens").get_count() == 0


def test_embedding_token_metrics_accumulate_across_requests() -> None:
    setup = _make_setup(FakeEmbeddingModelConnection(name="connection"))
    metric_group = _MockMetricGroup()

    first = setup.embed_with_usage("hello")
    setup.record_token_metrics(metric_group, first.token_usage)
    second = setup.embed_with_usage(["hello", "flink"])
    setup.record_token_metrics(metric_group, second.token_usage)

    model_group = metric_group.get_sub_group("model", "mock-model")
    assert model_group.get_counter("promptTokens").get_count() == 14
    assert model_group.get_counter("totalTokens").get_count() == 18


def test_embedding_token_metrics_do_not_leak_after_provider_failure() -> None:
    setup = _make_setup(ThrowThenReportUsageConnection(name="connection"))
    metric_group = _MockMetricGroup()

    with pytest.raises(RuntimeError, match="provider failure"):
        setup.embed_with_usage("first")

    result = setup.embed_with_usage("second")
    setup.record_token_metrics(metric_group, result.token_usage)
    assert result.embeddings == [0.1, 0.2]

    model_group = metric_group.get_sub_group("model", "mock-model")
    assert model_group.get_counter("promptTokens").get_count() == 3
    assert model_group.get_counter("totalTokens").get_count() == 4


def test_embedding_without_explicit_metric_group_does_not_read_bound_group() -> None:
    setup = _make_setup(FakeEmbeddingModelConnection(name="connection"))
    bound_metric_group = _MockMetricGroup()
    setup.set_metric_group(bound_metric_group)

    assert setup.embed("hello") == [0.1, 0.2]

    model_group = bound_metric_group.get_sub_group("model", "mock-model")
    assert model_group.get_counter("promptTokens").get_count() == 0
    assert model_group.get_counter("totalTokens").get_count() == 0
