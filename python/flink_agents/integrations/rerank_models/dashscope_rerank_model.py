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
import os
from typing import Any, Mapping, Sequence
from urllib.parse import urlparse

import dashscope
from pydantic import Field, PrivateAttr
from typing_extensions import override

from flink_agents.api.chat_message import (
    Base64Source,
    ImageBlock,
    TextBlock,
    UnsupportedContentBlockError,
    UrlSource,
    VideoBlock,
)
from flink_agents.api.rerank_models.rerank_model import (
    BaseRerankModel,
    RerankDocument,
    RerankHit,
    RerankQuery,
    RerankResult,
)
from flink_agents.integrations.dashscope_utils import create_client, usage_counters

DEFAULT_REQUEST_TIMEOUT = 60.0
DEFAULT_REGION = "cn-beijing"
_PROVIDER_LABEL = "DashScope"


class DashScopeRerankModel(BaseRerankModel):
    """DashScope text and multimodal reranking.

    One instance binds a single ``model``. Every input uses
    ``client.rerank.create``. A request that is only text sends strings. When
    any query or document is an image or video, text is sent as ``{"text":
    ...}`` so the document list has one shape.

    The client is built from these fields. ``~/.dashscope/config.toml`` is not
    read. An unset ``workspace`` or ``base_url`` still follows the SDK rules.
    Automatic retries are disabled.

    Attributes:
    ----------
    api_key : str
        DashScope API key. Falls back to ``DASHSCOPE_API_KEY``.
    workspace : str | None
        Bailian workspace id. With ``region``, it selects the workspace
        gateway.
    region : str
        Gateway region. Defaults to ``cn-beijing``.
    base_url : str | None
        Replaces the SDK-derived address when set.
    request_timeout : float
        Timeout in seconds for one HTTP request. Defaults to 60.
    parameters : dict
        Bailian ``parameters`` object. Call arguments override same-named keys.
        ``fps`` stays in this object. ``top_n``, ``return_documents``, and
        ``instruct`` are removed from it and sent as those call options.
    """

    api_key: str = Field(repr=False, description="DashScope API key.")
    workspace: str | None = Field(
        default=None,
        description="Bailian workspace id used in the gateway host.",
    )
    region: str = Field(
        default=DEFAULT_REGION,
        description="DashScope gateway region.",
    )
    base_url: str | None = Field(
        default=None,
        description="API root that replaces the SDK-derived address.",
    )
    request_timeout: float = Field(
        default=DEFAULT_REQUEST_TIMEOUT,
        gt=0,
        description="Timeout in seconds for one HTTP request.",
    )
    parameters: dict[str, Any] = Field(
        default_factory=dict,
        description="Bailian parameters overridden by call-time values.",
    )

    _sdk_client: dashscope.Client | None = PrivateAttr(default=None)

    def __init__(
        self,
        model: str,
        api_key: str | None = None,
        workspace: str | None = None,
        region: str | None = None,
        base_url: str | None = None,
        request_timeout: float = DEFAULT_REQUEST_TIMEOUT,
        parameters: Mapping[str, Any] | None = None,
        **kwargs: Any,
    ) -> None:
        """Resolve the API key, then store the remaining configuration."""
        resolved_api_key = api_key or os.environ.get("DASHSCOPE_API_KEY")
        if not resolved_api_key:
            msg = (
                "DashScope API key is not provided. "
                "Please pass it as an argument or set the "
                "'DASHSCOPE_API_KEY' environment variable."
            )
            raise ValueError(msg)
        super().__init__(
            model=model,
            api_key=resolved_api_key,
            workspace=workspace,
            region=region or DEFAULT_REGION,
            base_url=base_url,
            request_timeout=request_timeout,
            parameters=dict(parameters or {}),
            **kwargs,
        )

    @override
    def close(self) -> None:
        """Close the SDK client if it was created."""
        client = self._sdk_client
        self._sdk_client = None
        if client is not None:
            client.close()

    @override
    def rerank(
        self,
        query: RerankQuery,
        documents: Sequence[RerankDocument],
        *,
        top_n: int | None = None,
        return_documents: bool | None = None,
        instruct: str | None = None,
        timeout: float | None = None,
        **kwargs: Any,
    ) -> RerankResult:
        """Rank documents with one DashScope text-rerank request."""
        if not documents:
            msg = "Rerank requires at least one document."
            raise ValueError(msg)
        parameters = {**self.parameters, **kwargs}
        top_n = _explicit_or_configured("top_n", top_n, parameters)
        return_documents = _explicit_or_configured(
            "return_documents", return_documents, parameters
        )
        instruct = _explicit_or_configured("instruct", instruct, parameters)
        encoded_query = _query_value(query)
        encoded_documents = [_document_value(document) for document in documents]
        encoded_query, encoded_documents = _uniform_multimodal_values(
            encoded_query, encoded_documents
        )
        created = self._client().rerank.create(
            model=self.model,
            query=encoded_query,
            documents=encoded_documents,
            top_n=top_n,
            return_documents=return_documents,
            instruct=instruct,
            parameters=parameters or None,
            timeout=timeout,
        )
        return _result_from_sdk(
            created,
            documents,
            return_documents=return_documents,
        )

    def _client(self) -> dashscope.Client:
        """Return the SDK client for this resource, creating it once."""
        if self._sdk_client is None:
            self._sdk_client = create_client(
                api_key=self.api_key,
                base_url=self.base_url,
                workspace=self.workspace,
                region=self.region,
                timeout=self.request_timeout,
            )
        return self._sdk_client


def _explicit_or_configured(
    name: str, explicit: Any, parameters: dict[str, Any]
) -> Any:
    """Use the call argument, otherwise the configured parameter."""
    if explicit is not None:
        parameters.pop(name, None)
        return explicit
    return parameters.pop(name, None)


def _uniform_multimodal_values(
    query: str | dict[str, str],
    documents: Sequence[str | dict[str, str]],
) -> tuple[str | dict[str, str], list[str | dict[str, str]]]:
    """Use one document shape when any input is an image or video."""
    if isinstance(query, str) and all(isinstance(item, str) for item in documents):
        return query, list(documents)
    if isinstance(query, str):
        query = {"text": query}
    return query, [
        {"text": item} if isinstance(item, str) else item for item in documents
    ]


def _query_value(query: RerankQuery) -> str | dict[str, str]:
    if isinstance(query, TextBlock):
        if not query.text:
            msg = "Rerank requires a non-empty query."
            raise ValueError(msg)
        return query.text
    if isinstance(query, ImageBlock):
        return {"image": _block_url(query)}
    reason = "rerank accepts a text or image query"
    raise UnsupportedContentBlockError.for_block(_PROVIDER_LABEL, query, reason)


def _document_value(document: RerankDocument) -> str | dict[str, str]:
    if isinstance(document, TextBlock):
        if not document.text:
            msg = "Rerank documents must contain text."
            raise ValueError(msg)
        return document.text
    if isinstance(document, ImageBlock):
        return {"image": _block_url(document)}
    if isinstance(document, VideoBlock):
        return {"video": _video_url(document)}
    reason = "rerank accepts text, image, and video documents"
    raise UnsupportedContentBlockError.for_block(_PROVIDER_LABEL, document, reason)


def _block_url(block: ImageBlock) -> str:
    source = block.source
    if isinstance(source, UrlSource):
        return source.url
    if isinstance(source, Base64Source):
        return f"data:{block.media_type};base64,{source.data}"
    msg = "An image requires a URL or Base64 source."
    raise TypeError(msg)


def _video_url(block: VideoBlock) -> str:
    if not isinstance(block.source, UrlSource):
        msg = "A rerank video requires an http or https URL."
        raise TypeError(msg)
    scheme = urlparse(block.source.url).scheme
    if scheme not in ("http", "https"):
        msg = "A rerank video requires an http or https URL."
        raise ValueError(msg)
    return block.source.url


def _result_from_sdk(
    created: Any,
    documents: Sequence[RerankDocument],
    *,
    return_documents: bool | None,
) -> RerankResult:
    hits = [
        _hit(
            item.index,
            item.relevance_score,
            documents,
            return_documents=return_documents,
        )
        for item in created.results
    ]
    return RerankResult(
        results=hits,
        usage=usage_counters(created.usage),
        request_id=created.request_id or None,
    )


def _hit(
    index: int,
    score: float,
    documents: Sequence[RerankDocument],
    *,
    return_documents: bool | None,
) -> RerankHit:
    if not 0 <= index < len(documents):
        msg = (
            f"A rerank result returned document index {index}, but the request "
            f"contained {len(documents)} documents."
        )
        raise ValueError(msg)
    return RerankHit(
        index=index,
        relevance_score=score,
        document=documents[index] if return_documents else None,
    )
