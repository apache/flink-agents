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
from unittest.mock import MagicMock

import pytest
from chromadb.errors import NotFoundError

from flink_agents.api.resource_context import ResourceContext

try:
    import chromadb  # noqa: F401

    chromadb_available = True
except ImportError:
    chromadb_available = False

from flink_agents.api.embedding_models.embedding_model import BaseEmbeddingModelSetup
from flink_agents.api.resource import Resource, ResourceType
from flink_agents.api.vector_stores.vector_store import (
    Document,
    VectorStoreQuery,
)
from flink_agents.integrations.vector_stores.chroma.chroma_vector_store import (
    ChromaVectorStore,
    _translate_filters_to_chroma_where,
)

api_key = os.environ.get("TEST_API_KEY")
tenant = os.environ.get("TEST_TENANT")
database = os.environ.get("TEST_DATABASE")


class MockEmbeddingModel(BaseEmbeddingModelSetup):
    name: str
    connection: str = "mock"
    model: str = "mock"

    def open(self) -> None:
        pass

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        return {}

    def embed(self, text: str, **kwargs: Any) -> list[float]:
        if "ChromaDB" in text:
            return [0.2, 0.3, 0.4, 0.5, 0.6]
        else:
            return [0.1, 0.2, 0.3, 0.4, 0.5]


def _populate_test_data(
    vector_store: ChromaVectorStore, collection_name: str | None = None
) -> List[Document]:
    """Private helper method to populate ChromaDB with test data."""
    vector_store.create_collection_if_not_exists(name=collection_name)
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

    mock_ctx = MagicMock(spec=ResourceContext)
    mock_ctx.get_resource = get_resource

    vector_store = ChromaVectorStore(
        name="chroma_vector_store",
        embedding_model="mock_embeddings",
        collection="test_collection",
        resource_context=mock_ctx,
    )

    vector_store.open()

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

    mock_ctx = MagicMock(spec=ResourceContext)
    mock_ctx.get_resource = get_resource

    vector_store = ChromaVectorStore(
        name="chroma_vector_store",
        embedding_model="mock_embeddings",
        collection="test_collection",
        resource_context=mock_ctx,
    )

    vector_store.open()

    vector_store.create_collection_if_not_exists(
        name="collection_management", metadata={"key1": "value1", "key2": "value2"}
    )
    assert vector_store.get(collection_name="collection_management") == []

    vector_store.delete_collection(name="collection_management")

    with pytest.raises(NotFoundError):
        vector_store.get(collection_name="collection_management")


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

    mock_ctx = MagicMock(spec=ResourceContext)
    mock_ctx.get_resource = get_resource

    vector_store = ChromaVectorStore(
        name="chroma_vector_store",
        embedding_model="mock_embeddings",
        collection="test_collection",
        resource_context=mock_ctx,
    )

    vector_store.open()

    vector_store.create_collection_if_not_exists(
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


# ---------------------------------------------------------------------------
# Filter DSL → ChromaDB `where` translator (pure-function unit tests, no
# ChromaDB client needed).
# ---------------------------------------------------------------------------
def test_translate_filters_empty_and_none() -> None:
    assert _translate_filters_to_chroma_where(None) is None
    assert _translate_filters_to_chroma_where({}) is None


def test_translate_filters_single_key_equality() -> None:
    assert _translate_filters_to_chroma_where({"k": "v"}) == {"k": "v"}


def test_translate_filters_multi_key_wraps_in_and() -> None:
    # Multi-key top-level → ChromaDB rejects without $and; translator adds it.
    out = _translate_filters_to_chroma_where({"k": "v", "n": 1})
    assert out == {"$and": [{"k": "v"}, {"n": 1}]}


@pytest.mark.parametrize(
    "filters",
    [
        {"n": {"$gt": 1}},
        {"$and": [{"k": "v"}, {"n": 1}]},
        {"$or": [{"k": "v"}, {"k": "x"}]},
        {"$not": {"k": "v"}},
    ],
)
def test_translate_filters_rejects_operators(filters: Dict[str, Any]) -> None:
    # Anything beyond equality shorthand is out-of-spec in the unified DSL.
    with pytest.raises(NotImplementedError, match=r"equality shorthand"):
        _translate_filters_to_chroma_where(filters)


# ---------------------------------------------------------------------------
# End-to-end filter coverage (against an in-memory ChromaDB).
# ---------------------------------------------------------------------------
class _FixedVecEmbeddingModel(MockEmbeddingModel):
    """Always returns the query vector that matches ``doc1`` exactly —
    lets the filter tests assert a deterministic score distribution
    without having to pass vectors manually.
    """

    def embed(self, text: str, **kwargs: Any) -> list[float]:
        return [1.0, 0.0, 0.0, 0.0, 0.0]


def _filter_test_store() -> ChromaVectorStore:
    """Build a small ChromaVectorStore populated with three documents
    spanning enough metadata to exercise each filter shape.
    """
    embedding_model = _FixedVecEmbeddingModel(name="mock_embeddings")

    def get_resource(name: str, resource_type: ResourceType) -> Resource:
        if resource_type == ResourceType.EMBEDDING_MODEL:
            return embedding_model
        msg = f"Unknown resource type: {resource_type}"
        raise ValueError(msg)

    mock_ctx = MagicMock(spec=ResourceContext)
    mock_ctx.get_resource = get_resource

    vector_store = ChromaVectorStore(
        name="chroma_vector_store",
        embedding_model="mock_embeddings",
        collection="filter_tests",
        resource_context=mock_ctx,
    )
    vector_store.open()
    vector_store.create_collection_if_not_exists(name="filter_tests")
    vector_store.add(
        documents=[
            Document(
                id="doc1",
                content="alpha",
                metadata={"category": "a", "score": 10},
                embedding=[1.0, 0.0, 0.0, 0.0, 0.0],
            ),
            Document(
                id="doc2",
                content="beta",
                metadata={"category": "a", "score": 20},
                embedding=[0.0, 1.0, 0.0, 0.0, 0.0],
            ),
            Document(
                id="doc3",
                content="gamma",
                metadata={"category": "b", "score": 30},
                embedding=[0.0, 0.0, 1.0, 0.0, 0.0],
            ),
        ],
        collection_name="filter_tests",
    )
    return vector_store


@pytest.mark.skipif(not chromadb_available, reason="ChromaDB is not available")
def test_query_with_shorthand_equality_filter() -> None:
    vs = _filter_test_store()
    result = vs.query(
        VectorStoreQuery(
            query_text="x",
            limit=5,
            collection_name="filter_tests",
            filters={"category": "a"},
        )
    )
    assert sorted(d.id for d in result.documents) == ["doc1", "doc2"]


@pytest.mark.skipif(not chromadb_available, reason="ChromaDB is not available")
def test_query_with_multi_key_implicit_and_filter() -> None:
    # Translator must wrap this into $and; raw ChromaDB would reject it.
    vs = _filter_test_store()
    result = vs.query(
        VectorStoreQuery(
            query_text="x",
            limit=5,
            collection_name="filter_tests",
            filters={"category": "a", "score": 20},
        )
    )
    assert [d.id for d in result.documents] == ["doc2"]


@pytest.mark.skipif(not chromadb_available, reason="ChromaDB is not available")
def test_query_with_native_where_kwarg() -> None:
    # Callers needing ChromaDB-native operators bypass the unified DSL by
    # passing `where=` through extra_args; the translator leaves it alone.
    vs = _filter_test_store()
    result = vs.query(
        VectorStoreQuery(
            query_text="x",
            limit=5,
            collection_name="filter_tests",
            extra_args={"where": {"score": {"$gte": 20}}},
        )
    )
    assert sorted(d.id for d in result.documents) == ["doc2", "doc3"]


@pytest.mark.skipif(not chromadb_available, reason="ChromaDB is not available")
def test_query_scores_populated() -> None:
    vs = _filter_test_store()
    result = vs.query(
        VectorStoreQuery(
            query_text="x",
            limit=3,
            collection_name="filter_tests",
        )
    )
    # doc1's embedding matches the query exactly → distance 0; the other two
    # are orthogonal → strictly positive. All three scores must be populated.
    scores = {d.id: d.score for d in result.documents}
    assert scores["doc1"] == pytest.approx(0.0, abs=1e-6)
    assert scores["doc2"] is not None
    assert scores["doc2"] > 0
    assert scores["doc3"] is not None
    assert scores["doc3"] > 0


@pytest.mark.skipif(not chromadb_available, reason="ChromaDB is not available")
def test_get_with_filter() -> None:
    vs = _filter_test_store()
    got = vs.get(collection_name="filter_tests", filters={"category": "b"})
    assert [d.id for d in got] == ["doc3"]


@pytest.mark.skipif(not chromadb_available, reason="ChromaDB is not available")
def test_get_respects_explicit_limit() -> None:
    vs = _filter_test_store()
    got = vs.get(collection_name="filter_tests", limit=2)
    assert len(got) == 2


@pytest.mark.skipif(not chromadb_available, reason="ChromaDB is not available")
def test_get_unbounded_when_limit_is_none() -> None:
    vs = _filter_test_store()
    got = vs.get(collection_name="filter_tests", limit=None)
    # All three test docs come back when the caller opts out of the default.
    assert len(got) == 3


@pytest.mark.skipif(not chromadb_available, reason="ChromaDB is not available")
def test_delete_with_filter() -> None:
    vs = _filter_test_store()
    vs.delete(collection_name="filter_tests", filters={"category": "a"})
    remaining = vs.get(collection_name="filter_tests")
    assert [d.id for d in remaining] == ["doc3"]


@pytest.mark.skipif(not chromadb_available, reason="ChromaDB is not available")
def test_query_raises_on_operator_filter() -> None:
    vs = _filter_test_store()
    with pytest.raises(NotImplementedError, match=r"equality shorthand"):
        vs.query(
            VectorStoreQuery(
                query_text="x",
                limit=5,
                collection_name="filter_tests",
                filters={"score": {"$gte": 20}},
            )
        )


# ---------------------------------------------------------------------------
# Update + collection discovery (other newly-added surface).
# ---------------------------------------------------------------------------
@pytest.mark.skipif(not chromadb_available, reason="ChromaDB is not available")
def test_update_document() -> None:
    vs = _filter_test_store()
    updated = Document(
        id="doc1",
        content="alpha-v2",
        metadata={"category": "a", "score": 99},
        embedding=[0.5, 0.5, 0.0, 0.0, 0.0],
    )
    vs.update(documents=updated, collection_name="filter_tests")

    got = vs.get(ids="doc1", collection_name="filter_tests")
    assert len(got) == 1
    assert got[0].content == "alpha-v2"
    assert got[0].metadata["score"] == 99


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

    mock_ctx = MagicMock(spec=ResourceContext)
    mock_ctx.get_resource = get_resource

    vector_store = ChromaVectorStore(
        name="chroma_vector_store",
        embedding_model="mock_embeddings",
        collection="test_collection",
        api_key=api_key,
        tenant=tenant,
        database=database,
        resource_context=mock_ctx,
    )

    vector_store.open()

    _populate_test_data(vector_store)

    query = VectorStoreQuery(query_text="What is Flink Agent?", limit=1)

    result = vector_store.query(query)
    assert result is not None
    assert len(result.documents) == 1
    assert result.documents[0].id == "doc2"
