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
from typing import Any, Dict, Sequence
from unittest.mock import MagicMock

from flink_agents.api.embedding_models.embedding_model import (
    BaseEmbeddingModelConnection,
    BaseEmbeddingModelSetup,
    EmbeddingResult,
    EmbeddingTokenUsage,
)
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


class DispatchAwareEmbeddingModelConnection(BaseEmbeddingModelConnection):
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
            embeddings=[0.3, 0.4]
            if isinstance(text, str)
            else [[0.3, 0.4] for _ in text],
            token_usage=EmbeddingTokenUsage(prompt_tokens=7, total_tokens=9),
        )


class FakeEmbeddingModelSetup(BaseEmbeddingModelSetup):
    @property
    def model_kwargs(self) -> Dict[str, Any]:
        return {}


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


def test_embedding_result_returns_provider_usage() -> None:
    setup = _make_setup(FakeEmbeddingModelConnection(name="connection"))

    result = setup.embed_with_usage("hello")

    assert result.embeddings == [0.1, 0.2]
    assert result.token_usage == EmbeddingTokenUsage(prompt_tokens=7, total_tokens=9)


def test_embedding_result_defaults_to_no_usage() -> None:
    setup = _make_setup(FakeEmbeddingModelConnectionWithoutUsage(name="connection"))

    result = setup.embed_with_usage("hello")

    assert result.embeddings == [0.1, 0.2]
    assert result.token_usage is None


def test_embed_preserves_existing_embedding_only_api() -> None:
    setup = _make_setup(FakeEmbeddingModelConnection(name="connection"))

    assert setup.embed("hello") == [0.1, 0.2]


def test_embed_preserves_connection_embed_dispatch() -> None:
    setup = _make_setup(DispatchAwareEmbeddingModelConnection(name="connection"))

    assert setup.embed("hello") == [0.1, 0.2]
