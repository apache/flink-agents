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
from typing import Any, Dict, Sequence

import dashscope
from pydantic import Field

from flink_agents.api.embedding_models.embedding_model import (
    BaseEmbeddingModelConnection,
    BaseEmbeddingModelSetup,
)

DEFAULT_REQUEST_TIMEOUT = 30.0
DEFAULT_MODEL = "text-embedding-v4"


class TongyiEmbeddingModelConnection(BaseEmbeddingModelConnection):
    """Tongyi Embedding Model Connection which manages connection to DashScope API.

    Visit https://dashscope.console.aliyun.com/ to get your API key.

    Attributes:
    ----------
    api_key : str
        DashScope API key for authentication.
    request_timeout : float
        The timeout for making http request to Tongyi API server.
    """

    api_key: str | None = Field(
        default=None,
        description="Your DashScope API key.",
    )
    request_timeout: float = Field(
        default=DEFAULT_REQUEST_TIMEOUT,
        description="The timeout for making http request to Tongyi API server.",
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
        self, text: str | Sequence[str], **kwargs: Any
    ) -> list[float] | list[list[float]]:
        """Generate embedding vector for text input."""
        model = kwargs.pop("model", DEFAULT_MODEL)
        text_type = kwargs.pop("text_type", None)
        dimension = kwargs.pop("dimension", None)
        req_api_key = kwargs.pop("api_key", self.api_key)

        call_params: Dict[str, Any] = {
            "model": model,
            "input": text,
            "api_key": req_api_key,
            "timeout": self.request_timeout,
        }

        if text_type is not None:
            call_params["text_type"] = text_type
        if dimension is not None:
            call_params["dimension"] = dimension

        call_params.update(kwargs)

        response = dashscope.TextEmbedding.call(**call_params)

        if response.status_code != HTTPStatus.OK:
            msg = f"DashScope TextEmbedding call failed: {response.message}"
            raise RuntimeError(msg)

        embeddings = [e["embedding"] for e in response.output["embeddings"]]
        return embeddings[0] if isinstance(text, str) else embeddings


class TongyiEmbeddingModelSetup(BaseEmbeddingModelSetup):
    """The settings for Tongyi embedding model.

    Attributes:
    ----------
    connection : str
        Name of the referenced connection. (Inherited from BaseEmbeddingModelSetup)
    model : str
        Name of the embedding model to use. (Inherited from BaseEmbeddingModelSetup)
        Available models: text-embedding-v1, text-embedding-v2, text-embedding-v3,
            text-embedding-v4
    text_type : str | None
        The type of input text. Optional values: "query" or "document".
        Used to optimize embeddings for different use cases.
    dimension : int | None
        The number of dimensions for the output embedding vector.
        Only supported in certain models (e.g., text-embedding-v3 and later).
    additional_kwargs : Dict[str, Any]
        Additional parameters for the DashScope TextEmbedding API.
    """

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
        description="Additional parameters for the DashScope TextEmbedding API.",
    )

    def __init__(
        self,
        *,
        connection: str,
        model: str = DEFAULT_MODEL,
        text_type: str | None = None,
        dimension: int | None = None,
        additional_kwargs: Dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        if additional_kwargs is None:
            additional_kwargs = {}
        super().__init__(
            connection=connection,
            model=model,
            text_type=text_type,
            dimension=dimension,
            additional_kwargs=additional_kwargs,
            **kwargs,
        )

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return Tongyi embedding model configuration."""
        base_kwargs: Dict[str, Any] = {
            "model": self.model,
        }

        if self.text_type is not None:
            base_kwargs["text_type"] = self.text_type

        if self.dimension is not None:
            base_kwargs["dimension"] = self.dimension

        return {
            **base_kwargs,
            **self.additional_kwargs,
        }
