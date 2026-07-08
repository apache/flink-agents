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
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.resource_context import ResourceContext
from flink_agents.integrations.embedding_models.openai_embedding_model import (
    OpenAIEmbeddingModelConnection,
    OpenAIEmbeddingModelSetup,
)

pytestmark = pytest.mark.integration

test_model = os.environ.get("TEST_EMBEDDING_MODEL", "text-embedding-3-small")
api_key = os.environ.get("TEST_API_KEY")


@pytest.mark.skipif(api_key is None, reason="TEST_API_KEY is not set")
def test_openai_embedding_model() -> None:
    connection = OpenAIEmbeddingModelConnection(name="openai", api_key=api_key)

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.EMBEDDING_MODEL_CONNECTION:
            return connection
        else:
            msg = f"Unknown resource type: {type}"
            raise ValueError(msg)

    mock_ctx = MagicMock(spec=ResourceContext)
    mock_ctx.get_resource = get_resource

    embedding_model = OpenAIEmbeddingModelSetup(
        name="openai", model=test_model, connection="openai", resource_context=mock_ctx
    )
    embedding_model.open()

    response = embedding_model.embed("Hello, Flink Agent!")
    assert response is not None
    assert isinstance(response, list)
    assert len(response) > 0
    assert all(isinstance(x, float) for x in response)  #


def test_openai_embedding_model_records_token_metrics() -> None:
    """Test OpenAI embedding usage is recorded as model token metrics."""
    connection = OpenAIEmbeddingModelConnection(name="openai", api_key="fake-key")
    mock_client = MagicMock()
    mock_client.embeddings.create.return_value = SimpleNamespace(
        data=[SimpleNamespace(embedding=[0.1, 0.2, 0.3])],
        usage=SimpleNamespace(prompt_tokens=5, total_tokens=5),
    )
    connection._OpenAIEmbeddingModelConnection__client = mock_client

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.EMBEDDING_MODEL_CONNECTION:
            return connection
        else:
            msg = f"Unknown resource type: {type}"
            raise ValueError(msg)

    mock_ctx = MagicMock(spec=ResourceContext)
    mock_ctx.get_resource = get_resource
    embedding_model = OpenAIEmbeddingModelSetup(
        name="openai", model=test_model, connection="openai", resource_context=mock_ctx
    )
    metric_group = MagicMock()
    model_group = MagicMock()
    prompt_counter = MagicMock()
    total_counter = MagicMock()
    metric_group.get_sub_group.return_value = model_group
    model_group.get_counter.side_effect = {
        "promptTokens": prompt_counter,
        "totalTokens": total_counter,
    }.__getitem__

    embedding_model.open()

    result = embedding_model.embed_with_usage("Hello, Flink Agent!")
    embedding_model.record_token_metrics(metric_group, result.token_usage)
    assert result.embeddings == [0.1, 0.2, 0.3]

    metric_group.get_sub_group.assert_called_once_with("model", test_model)
    prompt_counter.inc.assert_called_once_with(5)
    total_counter.inc.assert_called_once_with(5)
