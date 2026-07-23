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
from typing import Any, Dict, Sequence

from typing_extensions import override

from flink_agents.api.embedding_models.embedding_model import (
    EmbeddingResult,
    EmbeddingTokenUsage,
)
from flink_agents.api.embedding_models.java_embedding_model import (
    JavaEmbeddingModelConnection,
    JavaEmbeddingModelSetup,
)
from flink_agents.runtime.java.java_resource_wrapper import (
    set_java_resource_metric_group,
)


def _from_java_embedding_result(
    j_result: Any, text: str | Sequence[str]
) -> EmbeddingResult[list[float] | list[list[float]]]:
    """Convert a Java EmbeddingResult into the Python result contract."""
    j_embeddings = j_result.getEmbeddings()
    embeddings = (
        list(j_embeddings)
        if isinstance(text, str)
        else [list(embedding) for embedding in j_embeddings]
    )
    j_usage = j_result.getTokenUsage()
    token_usage = (
        None
        if j_usage is None
        else EmbeddingTokenUsage(
            prompt_tokens=j_usage.getPromptTokens(),
            total_tokens=j_usage.getTotalTokens(),
        )
    )
    return EmbeddingResult(embeddings=embeddings, token_usage=token_usage)


class JavaEmbeddingModelConnectionImpl(JavaEmbeddingModelConnection):
    """Java-based implementation of EmbeddingModelConnection that wraps a Java embedding
    model object.
    This class serves as a bridge between Python and Java embedding model environments,
    but unlike JavaEmbeddingModelSetup, it does not provide direct embedding
    functionality in Python.
    """

    _j_resource: Any
    _j_resource_adapter: Any

    def __init__(self, j_resource: Any, j_resource_adapter: Any, **kwargs: Any) -> None:
        """Creates a new JavaEmbeddingModelConnection.

        Args:
            j_resource: The Java resource object
            j_resource_adapter: The Java resource adapter for method invocation
            **kwargs: Additional keyword arguments
        """
        super().__init__(**kwargs)
        self._j_resource = j_resource
        self._j_resource_adapter = j_resource_adapter

    @override
    def set_metric_group(self, metric_group: Any) -> None:
        super().set_metric_group(metric_group)
        set_java_resource_metric_group(self._j_resource, metric_group)

    def embed(
        self, text: str | Sequence[str], **kwargs: Any
    ) -> list[float] | list[list[float]]:
        """Generate embedding vector for a single text input.
        Converts the input text into a high-dimensional vector representation
        suitable for semantic similarity search and retrieval operations.

        Args:
            text: The text string to convert into an embedding vector.
            **kwargs: Additional parameters passed to the embedding model.
        """
        result = self._j_resource.embed(
            text if isinstance(text, str) else list(text), kwargs
        )
        return list(result) if isinstance(text, str) else [list(emb) for emb in result]

    @override
    def embed_with_usage(
        self, text: str | Sequence[str], **kwargs: Any
    ) -> EmbeddingResult[list[float] | list[list[float]]]:
        """Generate embeddings through Java and preserve provider token usage."""
        result = self._j_resource.embedWithUsage(
            text if isinstance(text, str) else list(text), kwargs
        )
        return _from_java_embedding_result(result, text)


class JavaEmbeddingModelSetupImpl(JavaEmbeddingModelSetup):
    """Java-based implementation of EmbeddingModelSetup that wraps a Java embedding
    model object.
    This class serves as a bridge between Python and Java embedding model environments,
    but unlike JavaEmbeddingModelConnection, it does not provide direct embedding
    functionality in Python.
    """

    _j_resource: Any
    _j_resource_adapter: Any

    def __init__(self, j_resource: Any, j_resource_adapter: Any, **kwargs: Any) -> None:
        """Creates a new JavaEmbeddingModelSetup.

        Args:
            j_resource: The Java resource object
            j_resource_adapter: The Java resource adapter for method invocation
            **kwargs: Additional keyword arguments
        """
        # connection,model are required parameters for BaseEmbeddingModelSetup
        connection = kwargs.pop("connection", "")
        model = kwargs.pop("model", "")
        super().__init__(connection=connection, model=model, **kwargs)

        self._j_resource = j_resource
        self._j_resource_adapter = j_resource_adapter

    @override
    def set_metric_group(self, metric_group: Any) -> None:
        super().set_metric_group(metric_group)
        set_java_resource_metric_group(self._j_resource, metric_group)

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        """Return embedding model settings.

        Returns:
            Empty dictionary as parameters are managed by Java side
        """
        return {}

    @override
    def open(self) -> None:
        """Open the java resource."""
        self._j_resource.open()

    def embed(
        self, text: str | Sequence[str], **kwargs: Any
    ) -> list[float] | list[list[float]]:
        """Generate embedding vector for a single text query.
        Converts the input text into a high-dimensional vector representation
        suitable for semantic similarity search and retrieval operations.

        Args:
            text: The text string to convert into an embedding vector.
            **kwargs: Additional parameters passed to the embedding model.
        """
        result = self._j_resource.embed(
            text if isinstance(text, str) else list(text), kwargs
        )
        return list(result) if isinstance(text, str) else [list(emb) for emb in result]

    @override
    def embed_with_usage(
        self, text: str | Sequence[str], **kwargs: Any
    ) -> EmbeddingResult[list[float] | list[list[float]]]:
        """Generate embeddings through Java and preserve provider token usage."""
        result = self._j_resource.embedWithUsage(
            text if isinstance(text, str) else list(text), kwargs
        )
        return _from_java_embedding_result(result, text)
