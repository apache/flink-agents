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
from collections.abc import Callable, Iterator
from contextlib import ExitStack
from types import SimpleNamespace
from typing import Any
from unittest.mock import Mock

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


@pytest.fixture
def model_factory() -> Iterator[Callable[..., DashScopeRerankModel]]:
    with ExitStack() as resources:

        def create(**overrides: Any) -> DashScopeRerankModel:
            values = {
                "model": "qwen3-rerank",
                "api_key": "sk-test",
                "workspace": "ws-1",
                **overrides,
            }
            model = DashScopeRerankModel(**values)
            resources.callback(model.close)
            model.open()
            return model

        yield create


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
    model_factory: Callable[..., DashScopeRerankModel],
) -> None:
    requests, responses = http_calls
    responses.append(httpx.Response(200, json=_ranked(1, 0)))
    model = model_factory(model_parameters={"top_n": 5, "fps": 0.5})

    result = model.rerank(
        TextBlock(text="What is a text reranking model?"),
        [
            TextBlock(text="Quantum computing studies computation with qubits."),
            TextBlock(text="A reranking model orders passages by relevance."),
        ],
        top_n=2,
        return_documents=False,
        model_parameters={"instruct": "Retrieve semantically similar text."},
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
    model_factory: Callable[..., DashScopeRerankModel],
) -> None:
    requests, responses = http_calls
    responses.append(httpx.Response(200, json=_ranked(0)))

    model_factory(model_parameters={"top_n": 1, "instruct": "answer"}).rerank(
        TextBlock(text="query"),
        [TextBlock(text="document")],
    )

    body = json.loads(requests[0].content)
    assert body["parameters"]["top_n"] == 1
    assert body["parameters"]["instruct"] == "answer"


def test_multimodal_documents_use_rerank_create(
    http_calls: tuple[list[httpx.Request], list[httpx.Response]],
    model_factory: Callable[..., DashScopeRerankModel],
) -> None:
    requests, responses = http_calls
    responses.append(httpx.Response(200, json=_ranked(2, 0)))
    image = ImageBlock.from_url("image/png", "https://example.test/query.png")
    video = VideoBlock.from_url("video/mp4", "https://example.test/clip.mp4")
    picture = ImageBlock.from_base64("image/jpeg", "YQ==")

    documents = [TextBlock(text="Caption of the reference picture."), picture, video]
    result = model_factory(model="qwen3-vl-rerank").rerank(
        image,
        documents,
        return_documents=True,
        model_parameters={"fps": 0.25},
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
    assert [hit.index for hit in result.results] == [2, 0]
    assert result.results[0].document is documents[2]
    assert result.results[1].document is documents[0]


def test_text_query_is_an_object_when_a_document_is_media(
    http_calls: tuple[list[httpx.Request], list[httpx.Response]],
    model_factory: Callable[..., DashScopeRerankModel],
) -> None:
    requests, responses = http_calls
    responses.append(httpx.Response(200, json=_ranked(0)))

    model_factory(model="qwen3-vl-rerank").rerank(
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


def test_video_base64_is_rejected(
    model_factory: Callable[..., DashScopeRerankModel],
) -> None:
    with pytest.raises(TypeError, match="http or https"):
        model_factory(model="qwen3-vl-rerank").rerank(
            TextBlock(text="query"),
            [VideoBlock.from_base64("video/mp4", "YQ==")],
        )


def test_audio_document_is_rejected(
    model_factory: Callable[..., DashScopeRerankModel],
) -> None:
    with pytest.raises(UnsupportedContentBlockError):
        model_factory().rerank(
            TextBlock(text="query"),
            [AudioBlock.from_url("audio/mpeg", "https://example.test/a.mp3")],
        )


def test_empty_documents_are_rejected(
    model_factory: Callable[..., DashScopeRerankModel],
) -> None:
    with pytest.raises(ValueError, match="at least one document"):
        model_factory().rerank(TextBlock(text="query"), [])


def test_api_key_is_resolved_when_the_resource_opens(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)
    model = DashScopeRerankModel(model="qwen3-rerank", api_key=None, workspace="ws-1")
    monkeypatch.setenv("DASHSCOPE_API_KEY", "sk-env")
    try:
        model.open()
        monkeypatch.setenv("DASHSCOPE_API_KEY", "changed-after-open")
        assert model._client().config.api_key == "sk-env"
    finally:
        model.close()


def test_missing_api_key_is_rejected_when_the_resource_opens(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)
    model = DashScopeRerankModel(model="qwen3-rerank", api_key=None)

    with pytest.raises(ValueError, match="DASHSCOPE_API_KEY"):
        model.open()


@pytest.mark.parametrize(
    "extra",
    [{"parameters": {"top_n": 1}}, {"req_timeout": 10}],
)
def test_extra_configuration_fields_are_rejected(extra: dict[str, Any]) -> None:
    with pytest.raises(ValueError, match="Extra inputs are not permitted"):
        DashScopeRerankModel(model="qwen3-rerank", api_key="sk-test", **extra)


def test_configured_model_parameters_are_deep_copied() -> None:
    configured: dict[str, Any] = {"top_n": 3, "nested": {"k": 1}}
    model = DashScopeRerankModel(
        model="qwen3-rerank", api_key="sk-test", model_parameters=configured
    )
    configured["nested"]["k"] = 99
    assert model.model_parameters == {"top_n": 3, "nested": {"k": 1}}


def test_rerank_client_is_owned_by_open_and_close(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = Mock()
    client.rerank.create.return_value = SimpleNamespace(
        results=[], usage=None, request_id=None
    )
    create_client = Mock(return_value=client)
    monkeypatch.setattr(
        "flink_agents.integrations.rerank_models.dashscope_rerank_model.create_client",
        create_client,
    )
    model = DashScopeRerankModel(model="qwen3-rerank", api_key="sk-test")
    query = TextBlock(text="query")
    documents = [TextBlock(text="document")]

    with pytest.raises(RuntimeError, match="Call open"):
        model.rerank(query, documents)
    create_client.assert_not_called()

    model.open()
    model.open()
    assert model.rerank(query, documents).results == []
    client.rerank.create.assert_called_once()
    create_client.assert_called_once()

    model.close()
    model.close()
    client.close.assert_called_once_with()
    with pytest.raises(RuntimeError, match="closed"):
        model.rerank(query, documents)
    with pytest.raises(RuntimeError, match="closed"):
        model.open()
    create_client.assert_called_once()
    client.rerank.create.assert_called_once()


@pytest.mark.parametrize("index", [-1, 2])
@pytest.mark.parametrize("return_documents", [False, True])
def test_out_of_range_response_index_is_rejected(
    http_calls: tuple[list[httpx.Request], list[httpx.Response]],
    model_factory: Callable[..., DashScopeRerankModel],
    index: int,
    return_documents: bool,
) -> None:
    _, responses = http_calls
    responses.append(httpx.Response(200, json=_ranked(index)))

    with pytest.raises(ValueError, match=f"document index {index}.*2 documents"):
        model_factory().rerank(
            TextBlock(text="query"),
            [TextBlock(text="first"), TextBlock(text="second")],
            return_documents=return_documents,
        )


@pytest.mark.parametrize("request_timeout", [0, -1.0, float("nan"), float("inf")])
def test_configured_request_timeout_must_be_positive_and_finite(
    request_timeout: float,
) -> None:
    with pytest.raises(ValueError):
        DashScopeRerankModel(
            model="qwen3-rerank",
            api_key="sk-test",
            request_timeout=request_timeout,
        )


@pytest.mark.parametrize(
    ("timeout", "error_type"),
    [
        (0, ValueError),
        (-1.0, ValueError),
        (float("nan"), ValueError),
        (float("inf"), ValueError),
        (True, TypeError),
    ],
)
def test_rerank_rejects_invalid_timeout_before_request(
    http_calls: tuple[list[httpx.Request], list[httpx.Response]],
    model_factory: Callable[..., DashScopeRerankModel],
    timeout: Any,
    error_type: type[Exception],
) -> None:
    requests, _ = http_calls

    with pytest.raises(error_type, match="positive finite number"):
        model_factory().rerank(
            TextBlock(text="query"),
            [TextBlock(text="document")],
            timeout=timeout,
        )

    assert requests == []


def test_rerank_rejects_unexpected_kwargs_outside_model_parameters(
    model_factory: Callable[..., DashScopeRerankModel],
) -> None:
    with pytest.raises(TypeError, match="unexpected keyword argument 'fps'"):
        model_factory().rerank(
            TextBlock(text="query"),
            [TextBlock(text="document")],
            fps=0.25,  # type: ignore[call-arg]
        )


@pytest.mark.parametrize(
    ("call_top_n", "call_parameters", "expected_top_n"),
    [
        (None, None, 5),
        (None, {"top_n": 3}, 3),
        (2, {"top_n": 3}, 2),
    ],
)
def test_rerank_parameter_precedence(
    http_calls: tuple[list[httpx.Request], list[httpx.Response]],
    model_factory: Callable[..., DashScopeRerankModel],
    call_top_n: int | None,
    call_parameters: dict[str, Any] | None,
    expected_top_n: int,
) -> None:
    requests, responses = http_calls
    responses.append(httpx.Response(200, json=_ranked(0)))
    model = model_factory(model_parameters={"top_n": 5})

    model.rerank(
        TextBlock(text="query"),
        [TextBlock(text="document")],
        top_n=call_top_n,
        model_parameters=call_parameters,
    )

    body = json.loads(requests[0].content)
    assert body["parameters"]["top_n"] == expected_top_n
