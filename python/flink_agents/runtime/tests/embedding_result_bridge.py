# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
"""Deterministic providers for the real embedding result bridge test."""

from typing import Any

from flink_agents.api.chat_message import ImageBlock, MediaBlock, TextBlock
from flink_agents.api.embedding_models.embedding_model import (
    BaseEmbeddingModelConnection,
    BaseEmbeddingModelSetup,
    EmbeddingContent,
    EmbeddingResult,
    EmbeddingTokenUsage,
)
from flink_agents.plan.resource.java.java_embedding_model import (
    JavaEmbeddingModelConnection,
    JavaEmbeddingModelSetup,
)
from flink_agents.runtime.java_resource_adapter import JavaResourceAdapterImpl


class FixtureConnection(BaseEmbeddingModelConnection):
    """Produce one deterministic vector for each input without a remote service."""

    def embed(
        self, content: EmbeddingContent, **kwargs: Any
    ) -> EmbeddingResult[list[float] | list[list[float]]]:
        assert kwargs["dimension"] == 1
        single = isinstance(content, str | TextBlock | MediaBlock)
        inputs = [content] if single else content
        vectors = [
            [9.0]
            if isinstance(item, MediaBlock)
            else [float(len(item.text if isinstance(item, TextBlock) else item))]
            for item in inputs
        ]
        usage = EmbeddingTokenUsage(7, 9) if kwargs.get("report_usage", True) else None
        return EmbeddingResult(vectors[0] if single else vectors, usage)


class FixtureSetup(BaseEmbeddingModelSetup):
    """Provide defaults through the ordinary setup merge path."""

    @property
    def model_kwargs(self) -> dict[str, Any]:
        return {"dimension": 1, "report_usage": True}


def create_model(setup: bool) -> BaseEmbeddingModelConnection | BaseEmbeddingModelSetup:
    """Create an initialized local connection or setup for the Java wrapper."""
    connection = FixtureConnection(name="connection")
    if not setup:
        return connection
    return FixtureSetup(name="setup", connection=connection, model="fixture")


def check_java_model(resource: Any, adapter: Any, setup: bool) -> bool:
    """Exercise actual JNI overload dispatch and result conversion in Python."""
    wrapper = JavaEmbeddingModelSetup if setup else JavaEmbeddingModelConnection
    model = wrapper(resource, JavaResourceAdapterImpl(adapter))
    image = ImageBlock.from_base64("image/png", "aGk=")
    cases = [
        ("abc", [3.0]),
        (TextBlock(text="abc"), [3.0]),
        (image, [9.0]),
        (["abc"], [[3.0]]),
        (["abc", "a"], [[3.0], [1.0]]),
        (["abc", image], [[3.0], [9.0]]),
        ([], []),
    ]
    for content, expected in cases:
        for report_usage in (False, True):
            result = model.embed(content, dimension=1, report_usage=report_usage)
            assert result.embeddings == expected
            assert result.token_usage == (
                EmbeddingTokenUsage(7, 9) if report_usage else None
            )
    return True
