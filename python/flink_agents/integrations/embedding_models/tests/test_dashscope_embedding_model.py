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
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest
from dashscope import TextEmbedding
from dashscope.api_entities.dashscope_response import DashScopeAPIResponse
from dashscope.client.base_api import BaseApi
from dashscope.utils.oss_utils import OssUtils

from flink_agents.api.chat_message import (
    ImageBlock,
    TextBlock,
    UnsupportedContentBlockError,
    VideoBlock,
)
from flink_agents.api.embedding_models.embedding_model import EmbeddingTokenUsage
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.resource_context import ResourceContext
from flink_agents.integrations.embedding_models.dashscope_embedding_model import (
    DashScopeEmbeddingModelConnection,
    DashScopeEmbeddingModelSetup,
)

test_model = os.environ.get("DASHSCOPE_EMBEDDING_MODEL", "text-embedding-v4")
api_key_available = "DASHSCOPE_API_KEY" in os.environ


def _make_ctx(get_resource) -> ResourceContext:
    ctx = MagicMock(spec=ResourceContext)
    ctx.get_resource = get_resource
    return ctx


@pytest.mark.integration
@pytest.mark.skipif(not api_key_available, reason="DashScope API key is not set")
def test_dashscope_embedding_model() -> None:
    """Test basic embedding functionality of DashScopeEmbeddingModelConnection."""
    connection = DashScopeEmbeddingModelConnection(name="dashscope")

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.EMBEDDING_MODEL_CONNECTION:
            return connection
        else:
            msg = f"Unknown resource type: {type}"
            raise ValueError(msg)

    embedding_model = DashScopeEmbeddingModelSetup(
        name="dashscope",
        model=test_model,
        connection="dashscope",
        resource_context=_make_ctx(get_resource),
    )
    embedding_model.open()

    response = embedding_model.embed(
        "The quality of the clothes is excellent, very beautiful, worth the wait, I like it and will buy here again"
    )
    assert response is not None
    assert isinstance(response, list)
    assert len(response) > 0
    assert all(isinstance(x, float) for x in response)


@pytest.mark.integration
@pytest.mark.skipif(not api_key_available, reason="DashScope API key is not set")
def test_dashscope_embedding_with_text_type() -> None:
    """Test embedding with text_type parameter."""
    connection = DashScopeEmbeddingModelConnection(name="dashscope")

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.EMBEDDING_MODEL_CONNECTION:
            return connection
        else:
            msg = f"Unknown resource type: {type}"
            raise ValueError(msg)

    embedding_model_query = DashScopeEmbeddingModelSetup(
        name="dashscope",
        model=test_model,
        connection="dashscope",
        text_type="query",
        resource_context=_make_ctx(get_resource),
    )
    embedding_model_query.open()

    response_query = embedding_model_query.embed("Hello, Flink Agent!")
    assert response_query is not None
    assert isinstance(response_query, list)
    assert len(response_query) > 0

    embedding_model_doc = DashScopeEmbeddingModelSetup(
        name="dashscope",
        model=test_model,
        connection="dashscope",
        text_type="document",
        resource_context=_make_ctx(get_resource),
    )
    embedding_model_doc.open()

    response_doc = embedding_model_doc.embed("Hello, Flink Agent!")
    assert response_doc is not None
    assert isinstance(response_doc, list)
    assert len(response_doc) > 0


def test_dashscope_embedding_mock(monkeypatch: pytest.MonkeyPatch) -> None:
    """Test embedding functionality with mocked DashScope API."""
    mock_embedding = [0.1, 0.2, 0.3, 0.4, 0.5]

    mocked_response = SimpleNamespace(
        status_code=HTTPStatus.OK,
        output={"embeddings": [{"embedding": mock_embedding}]},
        message="Success",
    )

    mock_call = MagicMock(return_value=mocked_response)

    monkeypatch.setattr(
        "flink_agents.integrations.embedding_models.dashscope_embedding_model.dashscope.TextEmbedding.call",
        mock_call,
    )

    connection = DashScopeEmbeddingModelConnection(
        name="dashscope",
        api_key="fake-key",
    )

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.EMBEDDING_MODEL_CONNECTION:
            return connection
        else:
            msg = f"Unknown resource type: {type}"
            raise ValueError(msg)

    embedding_model = DashScopeEmbeddingModelSetup(
        name="dashscope",
        connection="dashscope",
        resource_context=_make_ctx(get_resource),
    )
    embedding_model.open()

    response = embedding_model.embed("Test text")

    mock_call.assert_called_once()
    assert mock_call.call_args.kwargs["model"] == "text-embedding-v4"
    assert mock_call.call_args.kwargs["input"] == "Test text"
    assert response == mock_embedding
    assert len(response) == 5


def test_dashscope_embedding_returns_token_usage(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Test DashScope embedding usage is recorded as model token metrics."""
    mock_embedding = [0.1, 0.2, 0.3]
    mocked_response = SimpleNamespace(
        status_code=HTTPStatus.OK,
        output={
            "embeddings": [{"embedding": mock_embedding}],
        },
        usage={"total_tokens": 6},
        message="Success",
    )
    mock_call = MagicMock(return_value=mocked_response)
    monkeypatch.setattr(
        "flink_agents.integrations.embedding_models.dashscope_embedding_model.dashscope.TextEmbedding.call",
        mock_call,
    )

    connection = DashScopeEmbeddingModelConnection(
        name="dashscope",
        api_key="fake-key",
    )

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.EMBEDDING_MODEL_CONNECTION:
            return connection
        else:
            msg = f"Unknown resource type: {type}"
            raise ValueError(msg)

    embedding_model = DashScopeEmbeddingModelSetup(
        name="dashscope",
        model=test_model,
        connection="dashscope",
        resource_context=_make_ctx(get_resource),
    )
    embedding_model.open()

    result = embedding_model.embed_with_usage("Test text")
    assert result.embeddings == mock_embedding
    assert result.token_usage is not None
    assert result.token_usage.prompt_tokens == 6
    assert result.token_usage.total_tokens == 6


def test_dashscope_embedding_batch_mock(monkeypatch: pytest.MonkeyPatch) -> None:
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
        "flink_agents.integrations.embedding_models.dashscope_embedding_model.dashscope.TextEmbedding.call",
        mock_call,
    )

    connection = DashScopeEmbeddingModelConnection(
        name="dashscope",
        api_key="fake-key",
    )

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.EMBEDDING_MODEL_CONNECTION:
            return connection
        else:
            msg = f"Unknown resource type: {type}"
            raise ValueError(msg)

    embedding_model = DashScopeEmbeddingModelSetup(
        name="dashscope",
        model=test_model,
        connection="dashscope",
        resource_context=_make_ctx(get_resource),
    )
    embedding_model.open()

    response = embedding_model.embed(["Text one", "Text two"])

    mock_call.assert_called_once()
    assert response == mock_embeddings
    assert len(response) == 2


def test_dashscope_embedding_error_handling(monkeypatch: pytest.MonkeyPatch) -> None:
    """Test error handling when API call fails."""
    mocked_response = SimpleNamespace(
        status_code=HTTPStatus.BAD_REQUEST,
        message="Invalid API key",
    )

    mock_call = MagicMock(return_value=mocked_response)

    monkeypatch.setattr(
        "flink_agents.integrations.embedding_models.dashscope_embedding_model.dashscope.TextEmbedding.call",
        mock_call,
    )

    connection = DashScopeEmbeddingModelConnection(
        name="dashscope",
        api_key="invalid-key",
    )

    with pytest.raises(RuntimeError, match="DashScope TextEmbedding call failed"):
        connection.embed("Test text", model=test_model)


def test_dashscope_embedding_without_api_key() -> None:
    """Test that ValueError is raised when API key is not provided."""
    original_api_key = os.environ.get("DASHSCOPE_API_KEY")

    if "DASHSCOPE_API_KEY" in os.environ:
        del os.environ["DASHSCOPE_API_KEY"]

    try:
        with pytest.raises(ValueError, match="DashScope API key is not provided"):
            DashScopeEmbeddingModelConnection(name="dashscope")
    finally:
        if original_api_key is not None:
            os.environ["DASHSCOPE_API_KEY"] = original_api_key


@pytest.fixture
def multimodal_embedding(
    monkeypatch: pytest.MonkeyPatch,
) -> tuple[DashScopeEmbeddingModelSetup, MagicMock]:
    call = MagicMock()
    monkeypatch.setattr(BaseApi, "call", call)
    monkeypatch.setattr(
        OssUtils, "upload", MagicMock(side_effect=AssertionError("Unexpected upload"))
    )
    monkeypatch.setattr(
        TextEmbedding,
        "call",
        MagicMock(side_effect=AssertionError("Expected multimodal embedding")),
    )
    connection = DashScopeEmbeddingModelConnection(name="dashscope", api_key="fake-key")
    setup = DashScopeEmbeddingModelSetup(
        name="dashscope_multimodal",
        connection="dashscope",
        api_type="multimodal",
        dimension=256,
        resource_context=_make_ctx(lambda *_: connection),
    )
    setup.open()
    return setup, call


def test_dashscope_multimodal_embedding_returns_one_ordered_vector_per_input(
    multimodal_embedding: tuple[DashScopeEmbeddingModelSetup, MagicMock],
    tmp_path: Path,
) -> None:
    setup, call = multimodal_embedding
    query = tmp_path / "query.txt"
    query.write_text("This local file must stay local.", encoding="utf-8")
    image = ImageBlock.from_base64("image/png", "aW1hZ2U=")
    video_url = "https://example.invalid/video.mp4"
    video = VideoBlock.from_url("video/mp4", video_url)
    vectors = [[value] * 256 for value in (0.1, 0.2, 0.3)]
    call.return_value = DashScopeAPIResponse(
        status_code=HTTPStatus.OK,
        output={
            "embeddings": [
                {"index": index, "embedding": vectors[index]} for index in (2, 0, 1)
            ]
        },
        usage={"input_tokens": 7, "total_tokens": 12},
    )

    result = setup.embed_with_usage([TextBlock(text=str(query)), image, video])

    assert result.embeddings == vectors
    assert result.token_usage == EmbeddingTokenUsage(prompt_tokens=7, total_tokens=12)
    call.assert_called_once()
    request = call.call_args.kwargs
    assert request["model"] == "qwen3-vl-embedding"
    assert request["dimension"] == 256
    assert request["enable_fusion"] is False
    assert request["input"] == {
        "contents": [
            {"text": str(query)},
            {"image": "data:image/png;base64,aW1hZ2U="},
            {"video": video_url},
        ]
    }


def test_dashscope_multimodal_embedding_returns_one_vector_for_text_query(
    multimodal_embedding: tuple[DashScopeEmbeddingModelSetup, MagicMock],
) -> None:
    setup, call = multimodal_embedding
    vector = [0.5] * 256
    call.return_value = DashScopeAPIResponse(
        status_code=HTTPStatus.OK,
        output={"embeddings": [{"index": 0, "embedding": vector}]},
    )

    assert setup.embed("a tree") == vector
    call.assert_called_once()
    assert call.call_args.kwargs["model"] == "qwen3-vl-embedding"
    assert call.call_args.kwargs["input"] == {"contents": [{"text": "a tree"}]}


def test_dashscope_multimodal_embedding_rejects_duplicate_result_indices(
    multimodal_embedding: tuple[DashScopeEmbeddingModelSetup, MagicMock],
) -> None:
    setup, call = multimodal_embedding
    call.return_value = DashScopeAPIResponse(
        status_code=HTTPStatus.OK,
        output={
            "embeddings": [
                {"index": 0, "embedding": [0.1] * 256},
                {"index": 0, "embedding": [0.2] * 256},
            ]
        },
    )

    with pytest.raises(RuntimeError, match="invalid or duplicate indices"):
        setup.embed(["first", "second"])


def test_dashscope_multimodal_embedding_rejects_inline_video_before_request(
    multimodal_embedding: tuple[DashScopeEmbeddingModelSetup, MagicMock],
) -> None:
    setup, call = multimodal_embedding
    video = VideoBlock.from_base64("video/mp4", "dmlkZW8=")

    with pytest.raises(UnsupportedContentBlockError, match=r"videos require HTTP\(S\)"):
        setup.embed(video)

    call.assert_not_called()
