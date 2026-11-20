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
from typing import Any, Dict
from unittest.mock import MagicMock

import pytest

from flink_agents.api.chat_message import (
    ImageBlock,
    TextBlock,
    UnsupportedContentBlockError,
)
from flink_agents.api.embedding_models.embedding_model import (
    BaseEmbeddingModelConnection,
    BaseEmbeddingModelSetup,
    EmbeddingContent,
    EmbeddingResult,
    EmbeddingTokenUsage,
    _text_embedding_input,
)
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.resource_context import ResourceContext


class FakeEmbeddingModelConnection(BaseEmbeddingModelConnection):
    usage: EmbeddingTokenUsage | None = None

    def embed(
        self, content: EmbeddingContent, **kwargs: Any
    ) -> EmbeddingResult[list[float] | list[list[float]]]:
        text = _text_embedding_input(content, "test")
        assert kwargs == {"model": "mock-model", "dimension": 2}
        vectors = (
            [float(len(text))]
            if isinstance(text, str)
            else [[float(len(t))] for t in text]
        )
        return EmbeddingResult(vectors, self.usage)


class FakeEmbeddingModelSetup(BaseEmbeddingModelSetup):
    @property
    def model_kwargs(self) -> Dict[str, Any]:
        return {"model": self.model, "dimension": 1}


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


@pytest.mark.parametrize("usage", [None, EmbeddingTokenUsage(7, 9)])
@pytest.mark.parametrize(
    ("content", "expected"),
    [
        ("hello", [5.0]),
        (TextBlock(text="hello"), [5.0]),
        (["hello", TextBlock(text="next")], [[5.0], [4.0]]),
        ([], []),
    ],
)
def test_setup_preserves_shape_usage_and_parameter_overrides(
    content, expected, usage
) -> None:
    connection = FakeEmbeddingModelConnection(name="connection", usage=usage)
    setup = _make_setup(connection)
    result = setup.embed(content, dimension=2)
    assert result.embeddings == expected
    assert result.token_usage == usage
    assert setup.model_kwargs == {"model": "mock-model", "dimension": 1}


def test_text_only_connection_rejects_media() -> None:
    setup = _make_setup(FakeEmbeddingModelConnection(name="connection"))
    with pytest.raises(UnsupportedContentBlockError):
        setup.embed(ImageBlock.from_base64("image/png", "aGk="), dimension=2)
