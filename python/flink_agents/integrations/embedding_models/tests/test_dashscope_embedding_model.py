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
import json
import os
from collections.abc import Iterator
from pathlib import Path
from unittest.mock import MagicMock

import httpx
import pytest
from dashscope.errors import DashScopeError

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
from flink_agents.integrations.tests.dashscope_http import (
    record_dashscope_requests as record_dashscope_requests,
)

test_model = os.environ.get("DASHSCOPE_EMBEDDING_MODEL", "text-embedding-v4")
api_key_available = "DASHSCOPE_API_KEY" in os.environ


def _make_ctx(get_resource) -> ResourceContext:
    ctx = MagicMock(spec=ResourceContext)
    ctx.get_resource = get_resource
    return ctx


@pytest.fixture
def live_connection() -> Iterator[DashScopeEmbeddingModelConnection]:
    connection = DashScopeEmbeddingModelConnection(name="dashscope")
    try:
        yield connection
    finally:
        connection.close()


@pytest.mark.integration
@pytest.mark.skipif(not api_key_available, reason="DashScope API key is not set")
def test_dashscope_embedding_model(
    live_connection: DashScopeEmbeddingModelConnection,
) -> None:
    """Test basic embedding functionality of DashScopeEmbeddingModelConnection."""

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.EMBEDDING_MODEL_CONNECTION:
            return live_connection
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
def test_dashscope_embedding_with_text_type(
    live_connection: DashScopeEmbeddingModelConnection,
) -> None:
    """Test embedding with text_type parameter."""

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.EMBEDDING_MODEL_CONNECTION:
            return live_connection
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


@pytest.fixture
def connection() -> Iterator[DashScopeEmbeddingModelConnection]:
    conn = DashScopeEmbeddingModelConnection(api_key="fake-key")
    try:
        yield conn
    finally:
        conn.close()


def test_text_embedding_sends_parameters_and_returns_vector(
    connection: DashScopeEmbeddingModelConnection, http_calls
) -> None:
    requests, responses = http_calls
    responses.append(
        httpx.Response(200, json={"output": {"embeddings": [{"embedding": [0.1]}]}})
    )
    setup = DashScopeEmbeddingModelSetup(
        connection="dashscope",
        resource_context=_make_ctx(lambda *_: connection),
        dimension=256,
        text_type="query",
        additional_kwargs={"instruct": "find rivers", "dimension": 512},
    )
    setup.open()

    assert setup.embed(
        "a river", dimension=768, headers={"x-example": "one"}, api_key="override"
    ) == [0.1]
    [request] = requests
    assert request.url.path.endswith(
        "/services/embeddings/text-embedding/text-embedding"
    )
    assert request.headers["authorization"] == "Bearer override"
    assert request.headers["x-example"] == "one"
    assert json.loads(request.content) == {
        "model": "text-embedding-v4",
        "input": {"texts": ["a river"]},
        "parameters": {
            "instruct": "find rivers",
            "dimension": 768,
            "text_type": "query",
        },
    }


@pytest.mark.parametrize(
    ("usage", "expected"),
    [
        (None, None),
        ({"total_tokens": 6}, EmbeddingTokenUsage(6, 6)),
        ({"input_tokens": 0, "total_tokens": 6}, EmbeddingTokenUsage(0, 6)),
        ({"prompt_tokens": 4}, EmbeddingTokenUsage(4, 4)),
        ({"input_tokens": 3}, EmbeddingTokenUsage(3, 3)),
        ({"prompt_tokens": "4"}, EmbeddingTokenUsage(4, 4)),
        ({"prompt_tokens": "unknown"}, None),
        ({"prompt_tokens": "unknown", "total_tokens": 5}, EmbeddingTokenUsage(5, 5)),
    ],
)
def test_batch_text_embedding_usage(
    connection: DashScopeEmbeddingModelConnection, http_calls, usage, expected
) -> None:
    requests, responses = http_calls
    output = {"embeddings": [{"embedding": [0.1]}, {"embedding": [0.2]}]}
    responses.append(httpx.Response(200, json={"output": output, "usage": usage}))

    result = connection.embed_with_usage(["Text one", "Text two"])

    assert result.embeddings == [[0.1], [0.2]]
    assert result.token_usage == expected
    assert json.loads(requests[0].content)["input"] == {
        "texts": ["Text one", "Text two"]
    }


@pytest.mark.parametrize(
    ("api_type", "provider"),
    [("text", "TextEmbedding"), ("multimodal", "MultiModalEmbedding")],
)
def test_dashscope_embedding_error_handling(
    connection: DashScopeEmbeddingModelConnection, http_calls, api_type, provider
) -> None:
    _, responses = http_calls
    responses.append(
        httpx.Response(429, json={"code": "Throttling", "message": "Rate limit"})
    )
    with pytest.raises(
        RuntimeError, match=f"DashScope {provider} call failed: .*Rate limit"
    ) as error:
        connection.embed("Test text", api_type=api_type)
    assert isinstance(error.value.__cause__, DashScopeError)


def test_dashscope_embedding_without_api_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)
    with pytest.raises(ValueError, match="DashScope API key is not provided"):
        DashScopeEmbeddingModelConnection(name="dashscope")


@pytest.mark.parametrize(
    ("api_type", "content", "error"),
    [
        (
            "text",
            [1, 2],
            "Each embedding input must be a string or a data content block",
        ),
        ("text", {"text": "caption"}, "an ordered sequence of inputs"),
        ("multimodal", {"text": "caption"}, "an ordered sequence of inputs"),
    ],
)
def test_dashscope_embedding_rejects_invalid_content_before_request(
    connection: DashScopeEmbeddingModelConnection,
    http_calls,
    api_type: str,
    content: list[int] | dict[str, str],
    error: str,
) -> None:
    requests, _ = http_calls
    with pytest.raises(TypeError, match=error):
        connection.embed(content, api_type=api_type)
    assert not requests


@pytest.fixture
def multimodal_embedding(
    connection: DashScopeEmbeddingModelConnection,
) -> DashScopeEmbeddingModelSetup:
    setup = DashScopeEmbeddingModelSetup(
        name="dashscope_multimodal",
        connection="dashscope",
        api_type="multimodal",
        dimension=256,
        resource_context=_make_ctx(lambda *_: connection),
    )
    setup.open()
    return setup


def test_dashscope_multimodal_embedding_returns_one_ordered_vector_per_input(
    multimodal_embedding: DashScopeEmbeddingModelSetup, tmp_path: Path, http_calls
) -> None:
    requests, responses = http_calls
    query = tmp_path / "query.txt"
    query.write_text("This local file must stay local.", encoding="utf-8")
    image = ImageBlock.from_base64("image/png", "aW1hZ2U=")
    video_url = "https://example.invalid/video.mp4"
    video = VideoBlock.from_url("video/mp4", video_url)
    vectors = [[value] * 256 for value in (0.1, 0.2, 0.3)]
    responses.append(
        httpx.Response(
            200,
            json={
                "output": {
                    "embeddings": [
                        {"index": index, "embedding": vectors[index]}
                        for index in (2, 0, 1)
                    ]
                },
                "usage": {"input_tokens": 7, "total_tokens": 12},
            },
        )
    )

    result = multimodal_embedding.embed_with_usage(
        [TextBlock(text=str(query)), image, video], fps=0.5
    )

    assert result.embeddings == vectors
    assert result.token_usage == EmbeddingTokenUsage(prompt_tokens=7, total_tokens=12)
    [request] = requests
    assert request.url.path.endswith(
        "/services/embeddings/multimodal-embedding/multimodal-embedding"
    )
    assert json.loads(request.content) == {
        "model": "qwen3-vl-embedding",
        "parameters": {"dimension": 256, "enable_fusion": False, "fps": 0.5},
        "input": {
            "contents": [
                {"text": str(query)},
                {"image": "data:image/png;base64,aW1hZ2U="},
                {"video": video_url},
            ]
        },
    }


def test_dashscope_multimodal_embedding_returns_one_vector_for_text_query(
    multimodal_embedding: DashScopeEmbeddingModelSetup,
    http_calls,
) -> None:
    requests, responses = http_calls
    responses.append(
        httpx.Response(
            200, json={"output": {"embeddings": [{"index": 0, "embedding": [0.5]}]}}
        )
    )

    assert multimodal_embedding.embed("a tree") == [0.5]
    assert json.loads(requests[0].content)["input"] == {
        "contents": [{"text": "a tree"}]
    }


@pytest.mark.parametrize("first_index", ["0", False])
def test_multimodal_embedding_accepts_complete_sdk_indices(
    multimodal_embedding: DashScopeEmbeddingModelSetup,
    http_calls,
    first_index,
) -> None:
    _, responses = http_calls
    entries = [
        {"index": 1, "embedding": [0.2]},
        {"index": first_index, "embedding": [0.1]},
    ]
    responses.append(httpx.Response(200, json={"output": {"embeddings": entries}}))
    assert multimodal_embedding.embed(["first", "second"]) == [[0.1], [0.2]]


@pytest.mark.parametrize("api_type", ["text", "multimodal"])
@pytest.mark.parametrize(
    "vector", [{}, {"embedding": []}, {"embedding": None}, {"embedding": "bad"}]
)
def test_embedding_rejects_missing_or_empty_vector(
    connection: DashScopeEmbeddingModelConnection, http_calls, api_type, vector
) -> None:
    _, responses = http_calls
    entry = {"index": 0, **vector}
    responses.append(httpx.Response(200, json={"output": {"embeddings": [entry]}}))
    with pytest.raises(RuntimeError, match="missing an embedding vector"):
        connection.embed("first", api_type=api_type)


@pytest.mark.parametrize("api_type", ["text", "multimodal"])
def test_empty_embedding_batch_needs_no_request(
    connection: DashScopeEmbeddingModelConnection, http_calls, api_type
) -> None:
    requests, _ = http_calls
    result = connection.embed_with_usage([], api_type=api_type)
    assert result.embeddings == []
    assert result.token_usage is None
    assert not requests


def test_text_embedding_restores_input_order(
    connection: DashScopeEmbeddingModelConnection, http_calls
) -> None:
    _, responses = http_calls
    entries = [
        {"text_index": 2, "embedding": [0.3]},
        {"embedding": [0.2]},
        {"text_index": 0, "embedding": [0.1]},
    ]
    responses.append(httpx.Response(200, json={"output": {"embeddings": entries}}))
    assert connection.embed(["first", "second", "third"]) == [[0.1], [0.2], [0.3]]


@pytest.mark.parametrize(
    ("content", "indices", "error"),
    [
        ("first", [], "returned 0 vectors for 1 inputs"),
        (["first", "second"], [{"text_index": 0}], "returned 1 vectors for 2 inputs"),
        (["first", "second"], [{"text_index": 1}, {}], "invalid or duplicate indices"),
        ("first", [{"text_index": 1}], "invalid or duplicate indices"),
    ],
)
def test_text_embedding_rejects_results_that_cannot_be_mapped_to_inputs(
    connection: DashScopeEmbeddingModelConnection, http_calls, content, indices, error
) -> None:
    _, responses = http_calls
    entries = [{**entry, "embedding": [0.1]} for entry in indices]
    responses.append(httpx.Response(200, json={"output": {"embeddings": entries}}))
    with pytest.raises(RuntimeError, match=error):
        connection.embed(content)


@pytest.mark.parametrize(
    ("indices", "error"),
    [
        ([{"index": 0}, {"index": 0}], "invalid or duplicate indices"),
        ([{}, {"index": 1}], "without an index"),
        ([{"index": -1}, {"index": 1}], "invalid or duplicate indices"),
        ([{"index": 0}, {"index": 2}], "invalid or duplicate indices"),
        ([{"index": 0}], "returned 1 vectors for 2 inputs"),
    ],
)
def test_dashscope_multimodal_embedding_rejects_invalid_results(
    multimodal_embedding: DashScopeEmbeddingModelSetup, http_calls, indices, error
) -> None:
    _, responses = http_calls
    entries = [{**entry, "embedding": [0.1]} for entry in indices]
    responses.append(httpx.Response(200, json={"output": {"embeddings": entries}}))

    with pytest.raises(RuntimeError, match=error):
        multimodal_embedding.embed(["first", "second"])


def test_dashscope_multimodal_embedding_rejects_inline_video_before_request(
    multimodal_embedding: DashScopeEmbeddingModelSetup,
    http_calls,
) -> None:
    requests, _ = http_calls
    video = VideoBlock.from_base64("video/mp4", "dmlkZW8=")
    with pytest.raises(UnsupportedContentBlockError, match=r"videos require HTTP\(S\)"):
        multimodal_embedding.embed(video)
    assert not requests


@pytest.mark.parametrize("api_type", ["text", "multimodal"])
@pytest.mark.parametrize(
    ("connection_options", "call_options", "expected"),
    [
        ({}, {}, 30.0),
        ({"request_timeout": 1.25}, {}, 1.25),
        ({"request_timeout": 1.25}, {"request_timeout": 2.5}, 2.5),
    ],
)
def test_request_timeout_reaches_sdk_transport(
    http_calls,
    api_type: str,
    connection_options: dict,
    call_options: dict,
    expected: float,
) -> None:
    connection = DashScopeEmbeddingModelConnection(
        api_key="fake-key", **connection_options
    )
    output = {"embeddings": [{"index": 0, "embedding": [1.0]}]}
    requests, responses = http_calls
    responses.append(httpx.Response(200, json={"output": output}))
    try:
        assert connection.embed("Hello", api_type=api_type, **call_options) == [1.0]
        [request] = requests
        assert set(request.extensions["timeout"].values()) == {expected}
    finally:
        connection.close()


@pytest.mark.parametrize("api_type", ["text", "multimodal"])
@pytest.mark.parametrize(
    ("options", "error"),
    [
        ({"input": ["override"]}, "as content"),
        ({"stream": True}, "stream=False"),
        ({"api_protocol": "websocket"}, "api_protocol"),
        ({"max_retries": 2}, "max_retries"),
        ({"extra_query": {}}, "extra_query"),
    ],
)
def test_embedding_rejects_unsupported_options_before_request(
    connection: DashScopeEmbeddingModelConnection, http_calls, api_type, options, error
) -> None:
    requests, _ = http_calls
    with pytest.raises(ValueError, match=error):
        connection.embed("Hello", api_type=api_type, **options)
    assert not requests
