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
from copy import deepcopy
from typing import Any, Mapping, Sequence
from urllib.parse import urlparse

import dashscope
from pydantic import Field, PrivateAttr
from typing_extensions import override

from flink_agents.api.chat_message import (
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
from flink_agents.integrations.dashscope_utils import (
    create_client,
    media_source_url,
    usage_counters,
    validate_request_timeout,
)

DEFAULT_REQUEST_TIMEOUT = 60.0
DEFAULT_REGION = "cn-beijing"
_PROVIDER_LABEL = "DashScope"


class DashScopeRerankModel(BaseRerankModel):
    """DashScope text and multimodal reranking.

    One instance binds a single ``model``. Every input uses
    ``client.rerank.create``. A request that is only text sends strings. When
    any query or document is an image or video, text is sent as ``{"text":
    ...}`` so the document list has one shape.

    When using this model directly, call ``open()`` before reranking and
    ``close()`` when finished. Flink Agents manages this lifecycle for
    registered models.

    The client is built from these fields. ``~/.dashscope/config.toml`` is not
    read. An unset ``workspace`` or ``base_url`` still follows the SDK rules.
    Automatic retries are disabled.

    Attributes:
    ----------
    api_key : str | None
        DashScope API key. Falls back to ``DASHSCOPE_API_KEY`` when ``open()`` runs.
    workspace : str | None
        Bailian workspace id. With ``region``, it selects the workspace
        gateway.
    region : str
        Gateway region. Defaults to ``cn-beijing``.
    base_url : str | None
        Replaces the SDK-derived address when set.
    request_timeout : float
        Timeout in seconds for one HTTP request. Defaults to 60.
    model_parameters : dict
        Bailian ``parameters`` object. Call arguments override same-named keys.
        ``fps`` stays in this object. ``top_n``, ``return_documents``, and
        ``instruct`` are removed from it and sent as those call options.
    """

    api_key: str | None = Field(
        default=None, repr=False, description="DashScope API key."
    )
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
        allow_inf_nan=False,
        description="Timeout in seconds for one HTTP request.",
    )
    model_parameters: dict[str, Any] = Field(
        default_factory=dict,
        description="Bailian model parameters overridden by call-time model_parameters.",
    )

    _sdk_client: dashscope.Client | None = PrivateAttr(default=None)
    _closed: bool = PrivateAttr(default=False)

    def model_post_init(self, context: Any, /) -> None:
        """Snapshot configured model parameters so caller mutations do not leak."""
        self.model_parameters = deepcopy(self.model_parameters)

    @override
    def open(self) -> None:
        """Create the client before the model accepts requests."""
        if self._closed:
            msg = "The DashScope rerank model is closed."
            raise RuntimeError(msg)
        if self._sdk_client is None:
            api_key = self.api_key or os.environ.get("DASHSCOPE_API_KEY")
            if not api_key:
                msg = (
                    "DashScope API key is not provided. "
                    "Please pass it as an argument or set the "
                    "'DASHSCOPE_API_KEY' environment variable."
                )
                raise ValueError(msg)
            self._sdk_client = create_client(
                api_key=api_key,
                base_url=self.base_url,
                workspace=self.workspace,
                region=self.region,
                timeout=self.request_timeout,
            )

    @override
    def close(self) -> None:
        """End the model's lifetime and close its client, if opened."""
        self._closed = True
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
        model_parameters: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> RerankResult:
        """Rank documents with one DashScope rerank request."""
        client = self._client()
        timeout = validate_request_timeout(timeout)
        if not documents:
            msg = "Rerank requires at least one document."
            raise ValueError(msg)
        parameters = {**self.model_parameters, **(model_parameters or {})}
        top_n = _explicit_or_configured("top_n", top_n, parameters)
        return_documents = _explicit_or_configured(
            "return_documents", return_documents, parameters
        )
        instruct = parameters.pop("instruct", None)
        encoded_query = _query_value(query)
        encoded_documents = [_document_value(document) for document in documents]
        encoded_query, encoded_documents = _uniform_multimodal_values(
            encoded_query, encoded_documents
        )
        created = client.rerank.create(
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
        """Return the client of an open model."""
        if self._closed:
            msg = "The DashScope rerank model is closed."
            raise RuntimeError(msg)
        if self._sdk_client is None:
            msg = (
                "The DashScope rerank model is not open. Call open() before reranking."
            )
            raise RuntimeError(msg)
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
        return {"image": media_source_url(query)}
    reason = "rerank accepts a text or image query"
    raise UnsupportedContentBlockError.for_block(_PROVIDER_LABEL, query, reason)


def _document_value(document: RerankDocument) -> str | dict[str, str]:
    if isinstance(document, TextBlock):
        if not document.text:
            msg = "Rerank documents must contain text."
            raise ValueError(msg)
        return document.text
    if isinstance(document, ImageBlock):
        return {"image": media_source_url(document)}
    if isinstance(document, VideoBlock):
        return {"video": _video_url(document)}
    reason = "rerank accepts text, image, and video documents"
    raise UnsupportedContentBlockError.for_block(_PROVIDER_LABEL, document, reason)


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
    hits = []
    for item in created.results:
        index = item.index
        if not 0 <= index < len(documents):
            msg = (
                f"A rerank result returned document index {index}, but the request "
                f"contained {len(documents)} documents."
            )
            raise ValueError(msg)
        hits.append(
            RerankHit(
                index=index,
                relevance_score=item.relevance_score,
                document=documents[index] if return_documents else None,
            )
        )
    return RerankResult(
        results=hits,
        usage=usage_counters(created.usage),
        request_id=created.request_id or None,
    )
