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
from typing import Any, Dict

from ollama import Client
from pydantic import Field

from flink_agents.api.embedding_models.embedding_model import (
    BaseEmbeddingModelConnection,
    BaseEmbeddingModelSetup,
)

DEFAULT_REQUEST_TIMEOUT = 30.0


class OllamaEmbeddingModelConnection(BaseEmbeddingModelConnection):
    """Ollama Embedding Model Connection which manages connection to Ollama server.

    Visit https://ollama.com/ to download and install Ollama.

    Run `ollama serve` to start a server.

    Run `ollama pull <model_name>` to download an embedding model
    (e.g. nomic-embed-text, all-minilm, etc) to run.

    Attributes:
    ----------
    base_url : str
        Base url the Ollama server is hosted under.
    request_timeout : float
        The timeout for making http request to Ollama API server.
    """

    base_url: str = Field(
        default="http://localhost:11434",
        description="Base url the Ollama server is hosted under.",
    )
    request_timeout: float = Field(
        default=DEFAULT_REQUEST_TIMEOUT,
        description="The timeout for making http request to Ollama API server.",
    )

    __client: Client = None

    def __init__(
            self,
            base_url: str = "http://localhost:11434",
            request_timeout: float | None = DEFAULT_REQUEST_TIMEOUT,
            **kwargs: Any,
    ) -> None:
        """Init method."""
        super().__init__(
            base_url=base_url,
            request_timeout=request_timeout,
            **kwargs,
        )

    @property
    def client(self) -> Client:
        """Return ollama client."""
        if self.__client is None:
            self.__client = Client(host=self.base_url, timeout=self.request_timeout)
        return self.__client

    def embed(self, text: str, **kwargs: Any) -> list[float]:
        """Generate embedding vector for a single text query."""
        # Extract specific parameters
        model = kwargs.pop("model")
        keep_alive = kwargs.pop("keep_alive", None)
        truncate = kwargs.pop("truncate", True)

        # Remaining kwargs become options for the model
        response = self.client.embed(
            model=model,
            input=text,
            truncate=truncate,
            keep_alive=keep_alive,
            options=kwargs,
        )
        return list(response.embeddings[0])


class OllamaEmbeddingModelSetup(BaseEmbeddingModelSetup):
    """Ollama embedding model setup which manages embedding configuration
    and will internally call ollama embedding model connection to generate embeddings.

    Attributes:
    ----------
    connection : str
        Name of the referenced connection. (Inherited from BaseEmbeddingModelSetup)
    model : str
        Name of the embedding model to use. (Inherited from BaseEmbeddingModelSetup)
    truncate : bool
        Controls what happens if input text exceeds model's maximum length
        (default: True).
    keep_alive : Optional[Union[float, str]]
        Controls how long the model will stay loaded into memory following the
        request (default: 5m)
    additional_kwargs : Dict[str, Any]
        Additional model parameters for the Ollama embeddings API,
        e.g. num_ctx, temperature, etc.
    """
    truncate: bool = Field(
        default=True,
        description="Controls what happens if input text exceeds model's maximum length (default: True).",
    )
    keep_alive: float | str | None = Field(
        default="5m",
        description="Controls how long the model will stay loaded into memory following the "
                    "request(default: 5m)",
    )
    additional_kwargs: Dict[str, Any] = Field(
        default_factory=dict,
        description="Additional model parameters for the Ollama embeddings API.",
    )

    def __init__(
            self,
            *,
            connection: str,
            model: str,
            truncate: bool = True,
            additional_kwargs: Dict[str, Any] | None = None,
            keep_alive: float | str | None = None,
            **kwargs: Any,
    ) -> None:
        """Init method."""
        if additional_kwargs is None:
            additional_kwargs = {}
        super().__init__(
            connection=connection,
            model=model,
            truncate=truncate,
            additional_kwargs=additional_kwargs,
            keep_alive=keep_alive,
            **kwargs,
        )

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return ollama embedding model configuration."""
        base_kwargs = {
            "model": self.model,
            "truncate": self.truncate,
            "keep_alive": self.keep_alive,
        }
        return {
            **base_kwargs,
            **self.additional_kwargs,
        }
