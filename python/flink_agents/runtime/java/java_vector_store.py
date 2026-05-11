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

from typing import Any, Dict, List

from typing_extensions import override

from flink_agents.api.vector_stores.java_vector_store import (
    JavaCollectionManageableVectorStore,
)
from flink_agents.api.vector_stores.vector_store import (
    Document,
    VectorStoreQuery,
    VectorStoreQueryResult,
    _maybe_cast_to_list,
)
from flink_agents.runtime.python_java_utils import (
    from_java_document,
    from_java_vector_store_query_result,
)


class JavaVectorStoreImpl(JavaCollectionManageableVectorStore):
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
        # embedding_model are required parameters for BaseVectorStore
        embedding_model = kwargs.pop("embedding_model", "")
        super().__init__(embedding_model=embedding_model, **kwargs)

        self._j_resource = j_resource
        self._j_resource_adapter = j_resource_adapter

    @property
    @override
    def store_kwargs(self) -> Dict[str, Any]:
        return {}

    @override
    def open(self) -> None:
        self._j_resource.open()

    @override
    def add(
        self,
        documents: Document | List[Document],
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> List[str]:
        documents = _maybe_cast_to_list(documents)
        j_documents = [
            _to_j_document(self._j_resource_adapter, doc) for doc in documents
        ]

        return self._j_resource.add(j_documents, collection_name, kwargs)

    @override
    def query(self, query: VectorStoreQuery) -> VectorStoreQueryResult:
        j_query = self._j_resource_adapter.fromPythonVectorStoreQuery(query)
        j_query_result = self._j_resource.query(j_query)
        return from_java_vector_store_query_result(j_query_result)

    @override
    def get(
        self,
        ids: str | List[str] | None = None,
        collection_name: str | None = None,
        filters: Dict[str, Any] | None = None,
        limit: int | None = 100,
        **kwargs: Any,
    ) -> List[Document]:
        ids = _maybe_cast_to_list(ids)
        j_documents = self._j_resource.get(ids, collection_name, filters, limit, kwargs)
        return [from_java_document(j_document) for j_document in j_documents]

    @override
    def delete(
        self,
        ids: str | List[str] | None = None,
        collection_name: str | None = None,
        filters: Dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> List[str]:
        ids = _maybe_cast_to_list(ids)
        return self._j_resource.delete(ids, collection_name, filters, kwargs)

    @override
    def update(
        self,
        documents: Document | List[Document],
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> None:
        documents = _maybe_cast_to_list(documents)
        j_documents = [
            _to_j_document(self._j_resource_adapter, doc) for doc in documents
        ]
        self._j_resource.update(j_documents, collection_name, kwargs)

    @override
    def create_collection_if_not_exists(self, name: str, **kwargs: Any) -> None:
        """Forward to the Java side, passing all kwargs through as a map; the Java
        ``createCollectionIfNotExists(name, kwargs)`` ignores keys it does not
        understand (e.g. ``metadata`` for backends that do not support it).
        """
        self._j_resource.createCollectionIfNotExists(name, kwargs)

    @override
    def delete_collection(self, name: str) -> None:
        self._j_resource.deleteCollection(name)

    @override
    def _add_embedding(
        self,
        *,
        documents: List[Document],
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> List[str]:
        j_documents = [
            _to_j_document(self._j_resource_adapter, doc) for doc in documents
        ]
        return self._j_resource.addEmbedding(j_documents, collection_name, kwargs)

    @override
    def _query_embedding(
        self,
        embedding: list[float],
        limit: int = 10,
        collection_name: str | None = None,
        filters: Dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> list[Document]:
        # Pemja's Python -> Java float[] converter goes through
        # JcpPyTuple_AsJObject, which uses PyTuple_Size / PyTuple_GetItem.
        # Those APIs only work on actual tuples; passing a list (or numpy
        # array) leads to a JVM segfault inside jni_GetFloatArrayElements.
        # Coerce to tuple here.
        j_documents = self._j_resource.queryEmbedding(
            tuple(embedding), limit, collection_name, filters, kwargs
        )
        return [from_java_document(j_document) for j_document in j_documents]

    @override
    def _update_embedding(
        self,
        *,
        documents: List[Document],
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> None:
        j_documents = [
            _to_j_document(self._j_resource_adapter, doc) for doc in documents
        ]
        self._j_resource.updateEmbedding(j_documents, collection_name, kwargs)


def _to_j_document(j_resource_adapter: Any, doc: Document) -> Any:
    """Convert a Python ``Document`` to a Java ``Document`` by passing the
    fields through to the Java adapter as primitives. This avoids the reverse
    Java→Python ``getAttr`` path, which crashes the JVM when the call
    originates from a thread that mem0 (or any other Python consumer) span up
    outside of Pemja's main interpreter thread state.
    """
    embedding = tuple(doc.embedding) if doc.embedding is not None else None
    return j_resource_adapter.fromPythonDocument(
        doc.content, doc.metadata, doc.id, embedding, doc.score
    )
