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
import json
from typing import Any

import httpx
import pytest

from flink_agents.api.chat_message import (
    AudioBlock,
    ImageBlock,
    TextBlock,
    UnsupportedContentBlockError,
    VideoBlock,
)
from flink_agents.integrations.rerank_models.dashscope_rerank_model import (
    DashScopeRerankModel,
)

_PATH = "/services/rerank/text-rerank/text-rerank"


def _model(**overrides: Any) -> DashScopeRerankModel:
    values: dict[str, Any] = {
        "model": "qwen3-rerank",
        "api_key": "sk-test",
        "workspace": "ws-1",
    }
    values.update(overrides)
    return DashScopeRerankModel(**values)


@pytest.fixture
def http_calls(
    monkeypatch: pytest.MonkeyPatch,
) -> tuple[list[httpx.Request], list[httpx.Response]]:
    requests: list[httpx.Request] = []
    responses: list[httpx.Response] = []

    def handle_request(
        _transport: httpx.HTTPTransport, request: httpx.Request
    ) -> httpx.Response:
        requests.append(request)
        return responses.pop(0)

    monkeypatch.setattr(httpx.HTTPTransport, "handle_request", handle_request)
    return requests, responses


def _ranked(*indexes: int) -> dict[str, Any]:
    return {
        "request_id": "req-1",
        "usage": {"total_tokens": 12},
        "output": {
            "results": [
                {"index": index, "relevance_score": 1 - index / 10} for index in indexes
            ]
        },
    }


def test_text_rerank_sends_query_documents_and_options(
    http_calls: tuple[list[httpx.Request], list[httpx.Response]],
) -> None:
    requests, responses = http_calls
    responses.append(httpx.Response(200, json=_ranked(1, 0)))
    model = _model(parameters={"top_n": 5, "fps": 0.5})

    result = model.rerank(
        TextBlock(text="What is a text reranking model?"),
        [
            TextBlock(text="Quantum computing studies computation with qubits."),
            TextBlock(text="A reranking model orders passages by relevance."),
        ],
        top_n=2,
        instruct="Retrieve semantically similar text.",
        return_documents=False,
    )

    (request,) = requests
    body = json.loads(request.content)
    assert request.method == "POST"
    assert str(request.url).endswith(_PATH)
    assert body["model"] == "qwen3-rerank"
    assert body["input"] == {
        "query": "What is a text reranking model?",
        "documents": [
            "Quantum computing studies computation with qubits.",
            "A reranking model orders passages by relevance.",
        ],
    }
    assert body["parameters"] == {
        "fps": 0.5,
        "top_n": 2,
        "return_documents": False,
        "instruct": "Retrieve semantically similar text.",
    }
    assert [hit.index for hit in result.results] == [1, 0]
    assert result.results[0].document is None
    assert result.usage == {"total_tokens": 12}
    assert result.request_id == "req-1"


def test_configured_options_are_used_when_the_call_omits_them(
    http_calls: tuple[list[httpx.Request], list[httpx.Response]],
) -> None:
    requests, responses = http_calls
    responses.append(httpx.Response(200, json=_ranked(0)))

    _model(parameters={"top_n": 1, "instruct": "answer"}).rerank(
        TextBlock(text="query"),
        [TextBlock(text="document")],
    )

    body = json.loads(requests[0].content)
    assert body["parameters"]["top_n"] == 1
    assert body["parameters"]["instruct"] == "answer"


def test_multimodal_documents_use_rerank_create(
    http_calls: tuple[list[httpx.Request], list[httpx.Response]],
) -> None:
    requests, responses = http_calls
    responses.append(httpx.Response(200, json=_ranked(2, 0)))
    image = ImageBlock.from_url("image/png", "https://example.test/query.png")
    video = VideoBlock.from_url("video/mp4", "https://example.test/clip.mp4")
    picture = ImageBlock.from_base64("image/jpeg", "YQ==")

    result = _model(model="qwen3-vl-rerank").rerank(
        image,
        [TextBlock(text="Caption of the reference picture."), picture, video],
        return_documents=True,
        fps=0.25,
    )

    body = json.loads(requests[0].content)
    assert str(requests[0].url).endswith(_PATH)
    assert body["model"] == "qwen3-vl-rerank"
    assert body["input"]["query"] == {"image": "https://example.test/query.png"}
    assert body["input"]["documents"] == [
        {"text": "Caption of the reference picture."},
        {"image": "data:image/jpeg;base64,YQ=="},
        {"video": "https://example.test/clip.mp4"},
    ]
    assert body["parameters"] == {"return_documents": True, "fps": 0.25}
    assert result.results[0].document is video
    assert result.results[1].document == TextBlock(
        text="Caption of the reference picture."
    )


def test_text_query_is_an_object_when_a_document_is_media(
    http_calls: tuple[list[httpx.Request], list[httpx.Response]],
) -> None:
    requests, responses = http_calls
    responses.append(httpx.Response(200, json=_ranked(0)))

    _model(model="qwen3-vl-rerank").rerank(
        TextBlock(text="What is a text reranking model?"),
        [
            TextBlock(text="A reranking model orders passages by relevance."),
            ImageBlock.from_url("image/png", "https://example.test/picture.png"),
        ],
    )

    body = json.loads(requests[0].content)
    assert body["input"]["query"] == {"text": "What is a text reranking model?"}
    assert body["input"]["documents"] == [
        {"text": "A reranking model orders passages by relevance."},
        {"image": "https://example.test/picture.png"},
    ]


def test_video_base64_is_rejected() -> None:
    with pytest.raises(TypeError, match="http or https"):
        _model(model="qwen3-vl-rerank").rerank(
            TextBlock(text="query"),
            [VideoBlock.from_base64("video/mp4", "YQ==")],
        )


def test_audio_document_is_rejected() -> None:
    with pytest.raises(UnsupportedContentBlockError):
        _model().rerank(
            TextBlock(text="query"),
            [AudioBlock.from_url("audio/mpeg", "https://example.test/a.mp3")],
        )


def test_empty_documents_are_rejected() -> None:
    with pytest.raises(ValueError, match="at least one document"):
        _model().rerank(TextBlock(text="query"), [])


def test_missing_api_key_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)

    with pytest.raises(ValueError, match="DASHSCOPE_API_KEY"):
        DashScopeRerankModel(model="qwen3-rerank", api_key=None)


@pytest.mark.parametrize("index", [-1, 2])
@pytest.mark.parametrize("return_documents", [False, True])
def test_out_of_range_response_index_is_rejected(
    http_calls: tuple[list[httpx.Request], list[httpx.Response]],
    index: int,
    return_documents: bool,
) -> None:
    _, responses = http_calls
    responses.append(httpx.Response(200, json=_ranked(index)))

    with pytest.raises(ValueError, match=f"document index {index}.*2 documents"):
        _model().rerank(
            TextBlock(text="query"),
            [TextBlock(text="first"), TextBlock(text="second")],
            return_documents=return_documents,
        )


def test_returned_documents_follow_original_indices(
    http_calls: tuple[list[httpx.Request], list[httpx.Response]],
) -> None:
    _, responses = http_calls
    responses.append(httpx.Response(200, json=_ranked(1, 0)))
    documents = [TextBlock(text="first"), TextBlock(text="second")]

    result = _model().rerank(TextBlock(text="query"), documents, return_documents=True)

    assert [hit.index for hit in result.results] == [1, 0]
    assert result.results[0].document is documents[1]
    assert result.results[1].document is documents[0]
