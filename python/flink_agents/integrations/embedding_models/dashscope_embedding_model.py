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
from typing import Any, Dict, Literal
from urllib.parse import urlsplit

import dashscope
from pydantic import Field
from typing_extensions import override

from flink_agents.api.chat_message import (
    Base64Source,
    DataContentBlock,
    ImageBlock,
    MediaBlock,
    TextBlock,
    UnsupportedContentBlockError,
    UrlSource,
    VideoBlock,
)
from flink_agents.api.embedding_models.embedding_model import (
    BaseEmbeddingModelConnection,
    BaseEmbeddingModelSetup,
    EmbeddingContent,
    EmbeddingResult,
    EmbeddingTokenUsage,
    _text_embedding_input,
)

DEFAULT_REQUEST_TIMEOUT = 30.0
DEFAULT_MODEL = "text-embedding-v4"
DEFAULT_MULTIMODAL_MODEL = "qwen3-vl-embedding"


def _encode_embedding_content(content: str | DataContentBlock) -> dict[str, str]:
    if isinstance(content, str):
        return {"text": content}
    if isinstance(content, TextBlock):
        return {"text": content.text}
    if not isinstance(content, MediaBlock):
        msg = "Each embedding input must be a string or a data content block"
        raise TypeError(msg)
    if isinstance(content, ImageBlock | VideoBlock):
        source = content.source
        if isinstance(source, UrlSource) and urlsplit(source.url).scheme in {
            "http",
            "https",
        }:
            return {content.type: source.url}
        if isinstance(content, ImageBlock) and isinstance(source, Base64Source):
            return {"image": f"data:{content.media_type};base64,{source.data}"}
        reason = (
            "images require HTTP(S) URLs or Base64 data, videos require HTTP(S) URLs"
        )
    else:
        reason = "multimodal embeddings support text, images, and videos"
    provider = "DashScope MultiModalEmbedding"
    raise UnsupportedContentBlockError.for_block(provider, content, reason)


def _order_multimodal_embeddings(
    entries: list[dict[str, Any]], expected: int
) -> list[list[float]]:
    if len(entries) != expected:
        msg = (
            f"DashScope MultiModalEmbedding returned {len(entries)} vectors "
            f"for {expected} inputs"
        )
        raise RuntimeError(msg)
    vectors: dict[int, list[float]] = {}
    for entry in entries:
        index = entry.get("index")
        if (
            not isinstance(index, int)
            or isinstance(index, bool)
            or not 0 <= index < expected
            or index in vectors
        ):
            msg = "DashScope MultiModalEmbedding returned invalid or duplicate indices"
            raise RuntimeError(msg)
        vectors[index] = entry["embedding"]
    return [vectors[index] for index in range(expected)]


def _get_usage_value(obj: Any, *names: str) -> int | None:
    """Read a token usage value from dict-like or object-like provider responses."""
    if obj is None:
        return None
    for name in names:
        if isinstance(obj, dict) and name in obj:
            return int(obj[name])
        value = getattr(obj, name, None)
        if value is not None:
            return int(value)
    return None


class _MultiModalEmbedding(dashscope.MultiModalEmbedding):
    @classmethod
    @override
    def _preprocess_message_inputs(
        cls, model: str, input: list[dict[str, str]], api_key: str
    ) -> bool:
        # Sources are already HTTP(S) URLs or inline images. The SDK otherwise
        # treats text fields that match local paths as files to upload.
        return False


class DashScopeEmbeddingModelConnection(BaseEmbeddingModelConnection):
    """DashScope Embedding Model Connection which manages connection to DashScope API.

    Visit https://dashscope.console.aliyun.com/ to get your API key.

    Attributes:
    ----------
    api_key : str
        DashScope API key for authentication.
    request_timeout : float
        The timeout for making http request to DashScope API server.
    """

    api_key: str | None = Field(
        default=None,
        description="Your DashScope API key.",
    )
    request_timeout: float = Field(
        default=DEFAULT_REQUEST_TIMEOUT,
        description="The timeout for making http request to DashScope API server.",
    )

    def __init__(
        self,
        api_key: str | None = None,
        request_timeout: float | None = DEFAULT_REQUEST_TIMEOUT,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        resolved_api_key = api_key or os.environ.get("DASHSCOPE_API_KEY")
        if not resolved_api_key:
            msg = (
                "DashScope API key is not provided. "
                "Please pass it as an argument or set the 'DASHSCOPE_API_KEY' environment variable."
            )
            raise ValueError(msg)

        super().__init__(
            api_key=resolved_api_key,
            request_timeout=request_timeout,
            **kwargs,
        )

    def embed(
        self, content: EmbeddingContent, **kwargs: Any
    ) -> list[float] | list[list[float]]:
        """Generate vectors for one input or an ordered batch."""
        return self.embed_with_usage(content, **kwargs).embeddings

    def embed_with_usage(
        self, content: EmbeddingContent, **kwargs: Any
    ) -> EmbeddingResult[list[float] | list[list[float]]]:
        """Generate embeddings and return DashScope token usage when available."""
        api_type = kwargs.pop("api_type", "text")
        if api_type not in ("text", "multimodal"):
            msg = "DashScope embedding api_type must be 'text' or 'multimodal'"
            raise ValueError(msg)
        multimodal = api_type == "multimodal"
        provider = (
            "DashScope MultiModalEmbedding" if multimodal else "DashScope TextEmbedding"
        )
        default_model = DEFAULT_MULTIMODAL_MODEL if multimodal else DEFAULT_MODEL
        model = kwargs.pop("model", default_model)
        text_type = kwargs.pop("text_type", None)
        dimension = kwargs.pop("dimension", None)
        req_api_key = kwargs.pop("api_key", self.api_key)

        call_params: Dict[str, Any] = {
            "model": model,
            "api_key": req_api_key,
            "timeout": self.request_timeout,
        }

        if dimension is not None:
            call_params["dimension"] = dimension
        if multimodal:
            if text_type is not None:
                msg = "DashScope text_type is only supported by the text embedding API"
                raise ValueError(msg)
            if kwargs.pop("enable_fusion", False) is not False:
                msg = "DashScope requires enable_fusion=False to return one vector per input"
                raise ValueError(msg)
            if "input" in kwargs:
                msg = "Pass the embedding input as content"
                raise ValueError(msg)
            single = isinstance(content, str | TextBlock | MediaBlock)
            items = [content] if single else content
            # DashScope requires every item in a mixed batch to use the object form.
            contents = [_encode_embedding_content(item) for item in items]
            if not contents:
                return EmbeddingResult(embeddings=[])
            call_params["input"] = contents
            call_params["enable_fusion"] = False
            api = _MultiModalEmbedding
        else:
            text = _text_embedding_input(content, provider)
            single = isinstance(text, str)
            call_params["input"] = text
            if text_type is not None:
                call_params["text_type"] = text_type
            api = dashscope.TextEmbedding

        call_params.update(kwargs)

        response = api.call(**call_params)

        if response.status_code != HTTPStatus.OK:
            msg = f"{provider} call failed: {response.message}"
            raise RuntimeError(msg)

        usage = getattr(response, "usage", None)
        if usage is None and isinstance(response.output, dict):
            usage = response.output.get("usage")
        prompt_tokens = _get_usage_value(usage, "input_tokens", "prompt_tokens")
        total_tokens = _get_usage_value(usage, "total_tokens")
        if prompt_tokens is None:
            prompt_tokens = total_tokens
        token_usage = None
        if prompt_tokens is not None or total_tokens is not None:
            token_usage = EmbeddingTokenUsage(
                prompt_tokens=int(prompt_tokens or 0),
                total_tokens=int(
                    total_tokens if total_tokens is not None else prompt_tokens
                ),
            )

        entries = response.output["embeddings"]
        embeddings = (
            _order_multimodal_embeddings(entries, len(contents))
            if multimodal
            else [entry["embedding"] for entry in entries]
        )
        return EmbeddingResult(
            embeddings=embeddings[0] if single else embeddings,
            token_usage=token_usage,
        )


class DashScopeEmbeddingModelSetup(BaseEmbeddingModelSetup):
    """The settings for DashScope embedding model.

    Attributes:
    ----------
    connection : str
        Name of the referenced connection. (Inherited from BaseEmbeddingModelSetup)
    model : str
        Name of the embedding model to use. (Inherited from BaseEmbeddingModelSetup)
    api_type : Literal["text", "multimodal"]
        Select the DashScope text or multimodal embedding API.
    text_type : str | None
        The type of input text. Optional values: "query" or "document".
        Used to optimize embeddings for different use cases on the text API.
    dimension : int | None
        The number of dimensions for the output embedding vector.
        Only supported in certain models (e.g., text-embedding-v3 and later).
    additional_kwargs : Dict[str, Any]
        Additional parameters for the selected DashScope embedding API.
    """

    api_type: Literal["text", "multimodal"] = Field(
        default="text",
        description="The DashScope embedding API to call.",
    )
    text_type: str | None = Field(
        default=None,
        description='The type of input text. Optional values: "query" or "document".',
    )
    dimension: int | None = Field(
        default=None,
        description="The number of dimensions for the output embedding vector.",
    )
    additional_kwargs: Dict[str, Any] = Field(
        default_factory=dict,
        description="Additional parameters for the selected DashScope embedding API.",
    )

    def __init__(
        self,
        *,
        connection: str,
        model: str | None = None,
        api_type: Literal["text", "multimodal"] = "text",
        text_type: str | None = None,
        dimension: int | None = None,
        additional_kwargs: Dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        if additional_kwargs is None:
            additional_kwargs = {}
        if model is None:
            model = (
                DEFAULT_MULTIMODAL_MODEL if api_type == "multimodal" else DEFAULT_MODEL
            )
        super().__init__(
            connection=connection,
            model=model,
            api_type=api_type,
            text_type=text_type,
            dimension=dimension,
            additional_kwargs=additional_kwargs,
            **kwargs,
        )

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return DashScope embedding model configuration."""
        base_kwargs: Dict[str, Any] = {
            "model": self.model,
            "api_type": self.api_type,
        }

        if self.text_type is not None:
            base_kwargs["text_type"] = self.text_type

        if self.dimension is not None:
            base_kwargs["dimension"] = self.dimension

        return {
            **base_kwargs,
            **self.additional_kwargs,
        }
