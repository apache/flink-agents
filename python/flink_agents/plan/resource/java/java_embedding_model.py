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
from typing import Any, Dict

from typing_extensions import override

from flink_agents.api.chat_message import MediaBlock, TextBlock
from flink_agents.api.embedding_models.embedding_model import (
    BaseEmbeddingModelConnection,
    BaseEmbeddingModelSetup,
    EmbeddingContent,
    EmbeddingResult,
    EmbeddingTokenUsage,
)
from flink_agents.plan.resource.java.java_resource_adapter import JavaResourceAdapter


def _call_java_embedding(
    resource: Any,
    adapter: JavaResourceAdapter,
    content: EmbeddingContent,
    kwargs: Dict[str, Any],
    *,
    with_usage: bool,
) -> EmbeddingResult[list[float] | list[list[float]]]:
    """Call the Java model and adapt its result to the Python input shape."""
    single = isinstance(content, str | TextBlock | MediaBlock)
    inputs = [content] if single else list(content)
    argument = content if single else inputs
    j_result = None

    if any(isinstance(item, TextBlock | MediaBlock) for item in inputs):
        blocks = [
            TextBlock(text=item) if isinstance(item, str) else item for item in inputs
        ]
        java_blocks = [
            adapter.fromPythonDataContentBlock(block.model_dump(mode="json"))
            for block in blocks
        ]
        j_result = resource.embedBlocksWithUsage(java_blocks, kwargs)
        j_embeddings = j_result.getEmbeddings()
        if single:
            j_embeddings = j_embeddings[0]
    elif with_usage:
        j_result = resource.embedWithUsage(argument, kwargs)
        j_embeddings = j_result.getEmbeddings()
    else:
        j_embeddings = resource.embed(argument, kwargs)

    embeddings = (
        list(j_embeddings)
        if single
        else [list(embedding) for embedding in j_embeddings]
    )
    j_usage = j_result.getTokenUsage() if with_usage else None
    token_usage = (
        None
        if j_usage is None
        else EmbeddingTokenUsage(
            prompt_tokens=j_usage.getPromptTokens(),
            total_tokens=j_usage.getTotalTokens(),
        )
    )
    return EmbeddingResult(embeddings=embeddings, token_usage=token_usage)


class JavaEmbeddingModelConnection(BaseEmbeddingModelConnection):
    """Java-based implementation of EmbeddingModelConnection that wraps a Java embedding
    model object.
    This class serves as a bridge between Python and Java embedding model environments,
    but unlike JavaEmbeddingModelSetup, it does not provide direct embedding
    functionality in Python.
    """

    _j_resource: Any
    _j_resource_adapter: JavaResourceAdapter

    def __init__(
        self, j_resource: Any, j_resource_adapter: JavaResourceAdapter, **kwargs: Any
    ) -> None:
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
        self._j_resource_adapter.set_metric_group(self._j_resource, metric_group)

    def embed(
        self, content: EmbeddingContent, **kwargs: Any
    ) -> list[float] | list[list[float]]:
        """Generate vectors for one input or an ordered batch through Java."""
        return _call_java_embedding(
            self._j_resource,
            self._j_resource_adapter,
            content,
            kwargs,
            with_usage=False,
        ).embeddings

    @override
    def embed_with_usage(
        self, content: EmbeddingContent, **kwargs: Any
    ) -> EmbeddingResult[list[float] | list[list[float]]]:
        """Generate embeddings through Java and preserve provider token usage."""
        return _call_java_embedding(
            self._j_resource, self._j_resource_adapter, content, kwargs, with_usage=True
        )


class JavaEmbeddingModelSetup(BaseEmbeddingModelSetup):
    """Java-based implementation of EmbeddingModelSetup that wraps a Java embedding
    model object.
    This class serves as a bridge between Python and Java embedding model environments,
    but unlike JavaEmbeddingModelConnection, it does not provide direct embedding
    functionality in Python.
    """

    _j_resource: Any
    _j_resource_adapter: JavaResourceAdapter

    def __init__(
        self, j_resource: Any, j_resource_adapter: JavaResourceAdapter, **kwargs: Any
    ) -> None:
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
        self._j_resource_adapter.set_metric_group(self._j_resource, metric_group)

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
        self, content: EmbeddingContent, **kwargs: Any
    ) -> list[float] | list[list[float]]:
        """Generate vectors for one input or an ordered batch through Java."""
        return _call_java_embedding(
            self._j_resource,
            self._j_resource_adapter,
            content,
            kwargs,
            with_usage=False,
        ).embeddings

    @override
    def embed_with_usage(
        self, content: EmbeddingContent, **kwargs: Any
    ) -> EmbeddingResult[list[float] | list[list[float]]]:
        """Generate embeddings through Java and preserve provider token usage."""
        return _call_java_embedding(
            self._j_resource, self._j_resource_adapter, content, kwargs, with_usage=True
        )
