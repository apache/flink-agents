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
import os
from typing import Any, Dict, List

import pytest
from chromadb.errors import NotFoundError

try:
    import chromadb  # noqa: F401

    chromadb_available = True
except ImportError:
    chromadb_available = False

from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.vector_stores.vector_store import (
    Document,
    VectorStoreQuery,
)
from flink_agents.integrations.vector_stores.chroma.chroma_vector_store import (
    ChromaVectorStore,
)

api_key = os.environ.get("TEST_API_KEY")
tenant = os.environ.get("TEST_TENANT")
database = os.environ.get("TEST_DATABASE")


class MockEmbeddingModel(Resource):  # noqa: D101
    name: str

    @classmethod
    def resource_type(cls) -> ResourceType:  # noqa: D102
        return ResourceType.EMBEDDING_MODEL

    @property
    def model_kwargs(self) -> Dict[str, Any]:  # noqa: D102
        return {}

    def embed(self, text: str, **kwargs: Any) -> list[float]:  # noqa: D102
        if "ChromaDB" in text:
            return [0.2, 0.3, 0.4, 0.5, 0.6]
        else:
            return [0.1, 0.2, 0.3, 0.4, 0.5]


def _populate_test_data(
    vector_store: ChromaVectorStore, collection_name: str | None = None
) -> List[Document]:
    """Private helper method to populate ChromaDB with test data."""
    vector_store.get_or_create_collection(name=collection_name)
    documents = [
        Document(
            id="doc1",
            content="ChromaDB is a vector database for AI applications",
            metadata={"category": "database", "source": "test"},
        ),
        Document(
            id="doc2",
            content="Apache Flink Agents is an Agentic AI framework based on Apache Flink.",
            metadata={"category": "ai-agent", "source": "test"},
        ),
    ]
    vector_store.add(documents=documents, collection_name=collection_name)
    return documents


@pytest.mark.skipif(not chromadb_available, reason="ChromaDB is not available")
def test_local_chroma_vector_store() -> None:
    """Test ChromaDB vector store with embedding model integration."""
    embedding_model = MockEmbeddingModel(name="mock_embeddings")

    def get_resource(name: str, resource_type: ResourceType) -> Resource:
        if resource_type == ResourceType.EMBEDDING_MODEL:
            return embedding_model
        else:
            msg = f"Unknown resource type: {resource_type}"
            raise ValueError(msg)

    vector_store = ChromaVectorStore(
        name="chroma_vector_store",
        embedding_model="mock_embeddings",
        collection="test_collection",
        get_resource=get_resource,
    )

    _populate_test_data(vector_store, "test_collection")

    query = VectorStoreQuery(query_text="What is Flink Agent?", limit=1)

    result = vector_store.query(query)
    assert result is not None
    assert len(result.documents) == 1
    assert result.documents[0].id == "doc2"


@pytest.mark.skipif(not chromadb_available, reason="ChromaDB is not available")
def test_collection_management() -> None:
    """Test ChromaDB vector store with embedding model integration."""
    embedding_model = MockEmbeddingModel(name="mock_embeddings")

    def get_resource(name: str, resource_type: ResourceType) -> Resource:
        if resource_type == ResourceType.EMBEDDING_MODEL:
            return embedding_model
        else:
            msg = f"Unknown resource type: {resource_type}"
            raise ValueError(msg)

    vector_store = ChromaVectorStore(
        name="chroma_vector_store",
        embedding_model="mock_embeddings",
        collection="test_collection",
        get_resource=get_resource,
    )

    vector_store.get_or_create_collection(
        name="collection_management", metadata={"key1": "value1", "key2": "value2"}
    )

    collection = vector_store.get_collection(name="collection_management")

    assert collection is not None
    assert collection.name == "collection_management"
    assert vector_store.size(collection_name="collection_management") == 0
    assert collection.metadata == {"key1": "value1", "key2": "value2"}

    vector_store.delete_collection(name="collection_management")

    with pytest.raises(NotFoundError):
        vector_store.get_collection(name="collection_management")


@pytest.mark.skipif(not chromadb_available, reason="ChromaDB is not available")
def test_document_management() -> None:
    """Test ChromaDB vector store with embedding model integration."""
    embedding_model = MockEmbeddingModel(name="mock_embeddings")

    def get_resource(name: str, resource_type: ResourceType) -> Resource:
        if resource_type == ResourceType.EMBEDDING_MODEL:
            return embedding_model
        else:
            msg = f"Unknown resource type: {resource_type}"
            raise ValueError(msg)

    vector_store = ChromaVectorStore(
        name="chroma_vector_store",
        embedding_model="mock_embeddings",
        collection="test_collection",
        get_resource=get_resource,
    )

    vector_store.get_or_create_collection(
        name="document_management", metadata={"key1": "value1", "key2": "value2"}
    )

    expected_documents = _populate_test_data(vector_store, "document_management")
    expected_documents[0].embedding = None
    expected_documents[1].embedding = None

    # test get all documents
    documents = vector_store.get(collection_name="document_management")
    assert documents == expected_documents

    # test get specific document
    documents = vector_store.get(ids="doc1", collection_name="document_management")
    assert len(documents) == 1
    assert documents[0] == expected_documents[0]

    # test delete specific document
    vector_store.delete(ids="doc1", collection_name="document_management")
    documents = vector_store.get(collection_name="document_management")
    assert len(documents) == 1
    assert documents[0] == expected_documents[1]

    # test delete all documents
    vector_store.delete(collection_name="document_management")
    documents = vector_store.get(collection_name="document_management")
    assert documents == []

    # test delete from empty collection
    vector_store.delete(collection_name="document_management")
    documents = vector_store.get(collection_name="document_management")
    assert documents == []


@pytest.mark.skipif(api_key is None, reason="TEST_API_KEY is not set")
def test_cloud_chroma_vector_store() -> None:
    """Test cloud ChromaDB vector store with embedding model integration."""
    embedding_model = MockEmbeddingModel(name="mock_embeddings")

    def get_resource(name: str, resource_type: ResourceType) -> Resource:
        if resource_type == ResourceType.EMBEDDING_MODEL:
            return embedding_model
        else:
            msg = f"Unknown resource type: {resource_type}"
            raise ValueError(msg)

    vector_store = ChromaVectorStore(
        name="chroma_vector_store",
        embedding_model="mock_embeddings",
        collection="test_collection",
        api_key=api_key,
        tenant=tenant,
        database=database,
        get_resource=get_resource,
    )

    _populate_test_data(vector_store)

    query = VectorStoreQuery(query_text="What is Flink Agent?", limit=1)

    result = vector_store.query(query)
    assert result is not None
    assert len(result.documents) == 1
    assert result.documents[0].id == "doc2"
