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
################################################################################
import os
from http import HTTPStatus
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

from flink_agents.api.resource import Resource, ResourceType
from flink_agents.integrations.embedding_models.tongyi_embedding_model import (
    TongyiEmbeddingModelConnection,
    TongyiEmbeddingModelSetup,
)

test_model = os.environ.get("TONGYI_EMBEDDING_MODEL", "text-embedding-v4")
api_key_available = "DASHSCOPE_API_KEY" in os.environ


@pytest.mark.skipif(not api_key_available, reason="DashScope API key is not set")
def test_tongyi_embedding_model() -> None:
    """Test basic embedding functionality of TongyiEmbeddingModelConnection."""
    connection = TongyiEmbeddingModelConnection(name="tongyi")

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.EMBEDDING_MODEL_CONNECTION:
            return connection
        else:
            msg = f"Unknown resource type: {type}"
            raise ValueError(msg)

    embedding_model = TongyiEmbeddingModelSetup(
        name="tongyi", model=test_model, connection="tongyi", get_resource=get_resource
    )
    embedding_model.open()

    response = embedding_model.embed("The quality of the clothes is excellent, very beautiful, worth the wait, I like it and will buy here again")
    assert response is not None
    assert isinstance(response, list)
    assert len(response) > 0
    assert all(isinstance(x, float) for x in response)


@pytest.mark.skipif(not api_key_available, reason="DashScope API key is not set")
def test_tongyi_embedding_with_text_type() -> None:
    """Test embedding with text_type parameter."""
    connection = TongyiEmbeddingModelConnection(name="tongyi")

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.EMBEDDING_MODEL_CONNECTION:
            return connection
        else:
            msg = f"Unknown resource type: {type}"
            raise ValueError(msg)

    embedding_model_query = TongyiEmbeddingModelSetup(
        name="tongyi",
        model=test_model,
        connection="tongyi",
        text_type="query",
        get_resource=get_resource,
    )
    embedding_model_query.open()

    response_query = embedding_model_query.embed("Hello, Flink Agent!")
    assert response_query is not None
    assert isinstance(response_query, list)
    assert len(response_query) > 0

    embedding_model_doc = TongyiEmbeddingModelSetup(
        name="tongyi",
        model=test_model,
        connection="tongyi",
        text_type="document",
        get_resource=get_resource,
    )
    embedding_model_doc.open()

    response_doc = embedding_model_doc.embed("Hello, Flink Agent!")
    assert response_doc is not None
    assert isinstance(response_doc, list)
    assert len(response_doc) > 0


def test_tongyi_embedding_mock(monkeypatch: pytest.MonkeyPatch) -> None:
    """Test embedding functionality with mocked DashScope API."""
    mock_embedding = [0.1, 0.2, 0.3, 0.4, 0.5]

    mocked_response = SimpleNamespace(
        status_code=HTTPStatus.OK,
        output={"embeddings": [{"embedding": mock_embedding}]},
        message="Success",
    )

    mock_call = MagicMock(return_value=mocked_response)

    monkeypatch.setattr(
        "flink_agents.integrations.embedding_models.tongyi_embedding_model.dashscope.TextEmbedding.call",
        mock_call,
    )

    connection = TongyiEmbeddingModelConnection(
        name="tongyi",
        api_key="fake-key",
    )

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.EMBEDDING_MODEL_CONNECTION:
            return connection
        else:
            msg = f"Unknown resource type: {type}"
            raise ValueError(msg)

    embedding_model = TongyiEmbeddingModelSetup(
        name="tongyi", model=test_model, connection="tongyi", get_resource=get_resource
    )
    embedding_model.open()

    response = embedding_model.embed("Test text")

    mock_call.assert_called_once()
    assert response == mock_embedding
    assert len(response) == 5


def test_tongyi_embedding_batch_mock(monkeypatch: pytest.MonkeyPatch) -> None:
    """Test batch embedding functionality with mocked DashScope API."""
    mock_embeddings = [[0.1, 0.2, 0.3], [0.4, 0.5, 0.6]]

    mocked_response = SimpleNamespace(
        status_code=HTTPStatus.OK,
        output={
            "embeddings": [
                {"embedding": mock_embeddings[0]},
                {"embedding": mock_embeddings[1]},
            ]
        },
        message="Success",
    )

    mock_call = MagicMock(return_value=mocked_response)

    monkeypatch.setattr(
        "flink_agents.integrations.embedding_models.tongyi_embedding_model.dashscope.TextEmbedding.call",
        mock_call,
    )

    connection = TongyiEmbeddingModelConnection(
        name="tongyi",
        api_key="fake-key",
    )

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.EMBEDDING_MODEL_CONNECTION:
            return connection
        else:
            msg = f"Unknown resource type: {type}"
            raise ValueError(msg)

    embedding_model = TongyiEmbeddingModelSetup(
        name="tongyi", model=test_model, connection="tongyi", get_resource=get_resource
    )
    embedding_model.open()

    response = embedding_model.embed(["Text one", "Text two"])

    mock_call.assert_called_once()
    assert response == mock_embeddings
    assert len(response) == 2


def test_tongyi_embedding_error_handling(monkeypatch: pytest.MonkeyPatch) -> None:
    """Test error handling when API call fails."""
    mocked_response = SimpleNamespace(
        status_code=HTTPStatus.BAD_REQUEST,
        message="Invalid API key",
    )

    mock_call = MagicMock(return_value=mocked_response)

    monkeypatch.setattr(
        "flink_agents.integrations.embedding_models.tongyi_embedding_model.dashscope.TextEmbedding.call",
        mock_call,
    )

    connection = TongyiEmbeddingModelConnection(
        name="tongyi",
        api_key="invalid-key",
    )

    with pytest.raises(RuntimeError, match="DashScope TextEmbedding call failed"):
        connection.embed("Test text", model=test_model)


def test_tongyi_embedding_without_api_key() -> None:
    """Test that ValueError is raised when API key is not provided."""
    original_api_key = os.environ.get("DASHSCOPE_API_KEY")

    if "DASHSCOPE_API_KEY" in os.environ:
        del os.environ["DASHSCOPE_API_KEY"]

    try:
        with pytest.raises(ValueError, match="DashScope API key is not provided"):
            TongyiEmbeddingModelConnection(name="tongyi")
    finally:
        if original_api_key is not None:
            os.environ["DASHSCOPE_API_KEY"] = original_api_key
