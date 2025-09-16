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

import pytest

from flink_agents.api.resource import Resource, ResourceType
from flink_agents.integrations.embedding_models.openai_embedding_model import (
    OpenAIEmbeddingModelConnection,
    OpenAIEmbeddingModelSetup,
)

test_model = os.environ.get("TEST_EMBEDDING_MODEL", "text-embedding-3-small")
api_key = os.environ.get("TEST_API_KEY")


@pytest.mark.skipif(api_key is None, reason="TEST_API_KEY is not set")
def test_openai_embedding_model() -> None:  # noqa: D103
    connection = OpenAIEmbeddingModelConnection(
        name="openai", api_key=api_key
    )

    def get_resource(name: str, type: ResourceType) -> Resource:
        if type == ResourceType.EMBEDDING_MODEL_CONNECTION:
            return connection
        else:
            msg = f"Unknown resource type: {type}"
            raise ValueError(msg)

    embedding_model = OpenAIEmbeddingModelSetup(
        name="openai", model=test_model, connection="openai", get_resource=get_resource
    )

    response = embedding_model.embed("Hello, Flink Agent!")
    assert response is not None
    assert isinstance(response, list)
    assert len(response) > 0
    assert all(isinstance(x, float) for x in response)  #
