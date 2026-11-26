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
from unittest.mock import PropertyMock, patch

import pytest
from ollama import EmbedResponse

from flink_agents.integrations.embedding_models.local.ollama_embedding_model import (
    OllamaEmbeddingModelConnection,
)


@pytest.mark.parametrize("count", [7, 0, None])
@pytest.mark.parametrize("batch", [False, True])
def test_preserves_sdk_token_usage(count: int | None, batch: bool) -> None:
    """Keep reported zero counts and distinguish absent Python SDK usage."""
    vectors = [[0.1, 0.2], [0.3, 0.4]] if batch else [[0.1, 0.2]]
    fields = {"embeddings": vectors}
    if count is not None:
        fields["prompt_eval_count"] = count
    response = EmbedResponse(**fields)
    with patch.object(
        OllamaEmbeddingModelConnection, "client", new_callable=PropertyMock
    ) as client:
        client.return_value.embed.return_value = response
        connection = OllamaEmbeddingModelConnection()
        content = ["first", "second"] if batch else "first"
        result = connection.embed(content, model="fixture")

        assert result.embeddings == (vectors if batch else vectors[0])
        if count is None:
            assert result.token_usage is None
        else:
            assert result.token_usage is not None
            assert result.token_usage.prompt_tokens == count
            assert result.token_usage.total_tokens == count
        client.return_value.embed.assert_called_once_with(
            model="fixture", input=content, truncate=True, keep_alive=None, options={}
        )
