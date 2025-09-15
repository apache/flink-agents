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
#  limitations under the License.
################################################################################
from typing import Any, Dict

import pytest

try:
    import chromadb  # noqa: F401

    chromadb_available = True
except ImportError:
    chromadb_available = False

from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.vector_stores.vector_store import (
    VectorStoreQuery,
)
from flink_agents.integrations.vector_stores.chroma.chroma_vector_store import (
    ChromaVectorStoreConnection,
    ChromaVectorStoreSetup,
)


class MockEmbeddingModel(Resource):  # noqa: D101
    @classmethod
    def resource_type(cls) -> ResourceType:  # noqa: D102
        return ResourceType.EMBEDDING_MODEL

    @property
    def model_kwargs(self) -> Dict[str, Any]:  # noqa: D102
        return {}

    def embed(self, text: str, **kwargs: Any) -> list[float]:  # noqa: D102
        return [0.1, 0.2, 0.3, 0.4, 0.5]


def _populate_test_data(connection: ChromaVectorStoreConnection) -> None:
    """Private helper method to populate ChromaDB with test data."""
    collection = connection.client.get_or_create_collection(
        name="test_collection",
        metadata=None,
    )
    test_data = {
        "documents": [
            "ChromaDB is a vector database for AI applications",
            "Apache Flink Agents is an Agentic AI framework based on Apache Flink.",
        ],
        "embeddings": [
            [0.2, 0.3, 0.4, 0.5, 0.6],
            [0.1, 0.2, 0.3, 0.4, 0.5],
        ],
        "metadatas": [
            {"category": "database", "source": "test"},
            {"category": "ai-agent", "source": "test"},
        ],
        "ids": ["doc1", "doc2"]
    }

    collection.add(**test_data)


@pytest.mark.skipif(
    not chromadb_available, reason="ChromaDB is not available"
)
def test_chroma_vector_store_setup() -> None:
    """Test ChromaDB vector store setup with embedding model integration."""
    connection = ChromaVectorStoreConnection(name="chroma_conn")
    embedding_model = MockEmbeddingModel(name="mock_embeddings")

    def get_resource(name: str, resource_type: ResourceType) -> Resource:
        if resource_type == ResourceType.VECTOR_STORE_CONNECTION:
            return connection
        elif resource_type == ResourceType.EMBEDDING_MODEL:
            return embedding_model
        else:
            msg = f"Unknown resource type: {resource_type}"
            raise ValueError(msg)

    setup = ChromaVectorStoreSetup(
        name="chroma_setup",
        connection="chroma_conn",
        embedding_model="mock_embeddings",
        collection="test_collection",
        get_resource=get_resource
    )

    _populate_test_data(connection)

    query = VectorStoreQuery(
        query_text="What is Flink Agent?",
        limit=1
    )

    result = setup.query(query)
    assert result is not None
    assert len(result.documents) == 1
    assert result.documents[0].id == "doc2"
