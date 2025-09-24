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

from openai import NOT_GIVEN, OpenAI
from pydantic import Field

from flink_agents.api.embedding_models.embedding_model import (
    BaseEmbeddingModelConnection,
    BaseEmbeddingModelSetup,
)

DEFAULT_REQUEST_TIMEOUT = 30.0
DEFAULT_BASE_URL = "https://api.openai.com/v1"
DEFAULT_MAX_RETRIES = 3


class OpenAIEmbeddingModelConnection(BaseEmbeddingModelConnection):
    """OpenAI Embedding Model Connection which manages connection to OpenAI API.

    Visit https://platform.openai.com/ to get your API key.

    Attributes:
    ----------
    api_key : str
        OpenAI API key for authentication.
    base_url : str
        Base URL for the OpenAI API (default: https://api.openai.com/v1).
    request_timeout : float
        The timeout for making HTTP requests to OpenAI API.
    max_retries : int
        Maximum number of retries for failed requests.
    organization : Optional[str]
        Optional organization ID for API requests.
    project : Optional[str]
        Optional project ID for API requests.
    """

    api_key: str = Field(description="OpenAI API key for authentication.")
    base_url: str = Field(
        default=DEFAULT_BASE_URL,
        description="Base URL for the OpenAI API.",
    )
    request_timeout: float = Field(
        default=DEFAULT_REQUEST_TIMEOUT,
        description="The timeout for making HTTP requests to OpenAI API.",
    )
    max_retries: int = Field(
        default=DEFAULT_MAX_RETRIES,
        description="Maximum number of retries for failed requests.",
    )
    organization: str | None = Field(
        default=None,
        description="Optional organization ID for API requests.",
    )
    project: str | None = Field(
        default=None,
        description="Optional project ID for API requests.",
    )

    def __init__(
        self,
        api_key: str,
        base_url: str = DEFAULT_BASE_URL,
        request_timeout: float | None = DEFAULT_REQUEST_TIMEOUT,
        max_retries: int = DEFAULT_MAX_RETRIES,
        organization: str | None = None,
        project: str | None = None,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        super().__init__(
            api_key=api_key,
            base_url=base_url,
            request_timeout=request_timeout,
            max_retries=max_retries,
            organization=organization,
            project=project,
            **kwargs,
        )

    __client: OpenAI = None

    @property
    def client(self) -> OpenAI:
        """Return OpenAI client."""
        if self.__client is None:
            self.__client = OpenAI(
                api_key=self.api_key,
                base_url=self.base_url,
                timeout=self.request_timeout,
                organization=self.organization,
                project=self.project,
                max_retries=self.max_retries,
            )
        return self.__client

    def embed(self, text: str, **kwargs: Any) -> list[float]:
        """Generate embedding vector for a single text query."""
        # Extract OpenAI specific parameters
        model = kwargs.pop("model")
        encoding_format = kwargs.pop("encoding_format", None)
        dimensions = kwargs.pop("dimensions", None)
        user = kwargs.pop("user", None)

        # Create the embedding request
        response = self.client.embeddings.create(
            model=model,
            input=text,
            encoding_format=encoding_format if encoding_format is not None else NOT_GIVEN,
            dimensions=dimensions if dimensions is not None else NOT_GIVEN,
            user=user if user is not None else NOT_GIVEN,
        )

        return list(response.data[0].embedding)


class OpenAIEmbeddingModelSetup(BaseEmbeddingModelSetup):
    """The settings for OpenAI embedding model.

    Attributes:
    ----------
    connection : str
        Name of the referenced connection. (Inherited from BaseEmbeddingModelSetup)
    model : str
        Name of the embedding model to use. (Inherited from BaseEmbeddingModelSetup)
    encoding_format : str
        The format to return the embeddings in (default: "float").
        Can be either "float" or "base64".
    dimensions : Optional[int]
        The number of dimensions the resulting output embeddings should have.
        Only supported in text-embedding-3 and later models.
    user : Optional[str]
        A unique identifier representing your end-user, which can help OpenAI
        to monitor and detect abuse.
    additional_kwargs : Dict[str, Any]
        Additional parameters for the OpenAI embeddings API.
    """

    encoding_format: str = Field(
        default="float",
        description='The format to return the embeddings in (default: "float").',
    )
    dimensions: int | None = Field(
        default=None,
        description="The number of dimensions the resulting output embeddings should have.",
    )
    user: str | None = Field(
        default=None,
        description="A unique identifier representing your end-user.",
    )
    additional_kwargs: Dict[str, Any] = Field(
        default_factory=dict,
        description="Additional parameters for the OpenAI embeddings API.",
    )

    def __init__(
        self,
        *,
        connection: str,
        model: str,
        encoding_format: str = "float",
        dimensions: int | None = None,
        user: str | None = None,
        additional_kwargs: Dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> None:
        """Init method."""
        if additional_kwargs is None:
            additional_kwargs = {}
        super().__init__(
            connection=connection,
            model=model,
            encoding_format=encoding_format,
            dimensions=dimensions,
            user=user,
            additional_kwargs=additional_kwargs,
            **kwargs,
        )

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return OpenAI embedding model configuration."""
        base_kwargs = {
            "model": self.model,
            "encoding_format": self.encoding_format,
        }

        if self.dimensions is not None:
            base_kwargs["dimensions"] = self.dimensions

        if self.user is not None:
            base_kwargs["user"] = self.user

        return {
            **base_kwargs,
            **self.additional_kwargs,
        }
