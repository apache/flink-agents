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
    Collection,
    Document,
    VectorStoreQuery,
    VectorStoreQueryResult,
    _maybe_cast_to_list,
)
from flink_agents.runtime.python_java_utils import (
    from_java_collection,
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
        super().__init__(embedding_model = embedding_model, **kwargs)

        self._j_resource=j_resource
        self._j_resource_adapter=j_resource_adapter

    @override
    @property
    def store_kwargs(self) -> Dict[str, Any]:
        return {}

    @override
    def add(
            self,
            documents: Document | List[Document],
            collection_name: str | None = None,
            **kwargs: Any,
    ) -> List[str]:

        documents = _maybe_cast_to_list(documents)
        j_documents = [
            self._j_resource_adapter.fromPythonDocument(document)
            for document in documents
        ]

        return self._j_resource.add(j_documents, collection_name, kwargs)

    @override
    def query(self, query: VectorStoreQuery) -> VectorStoreQueryResult:
        j_query = self._j_resource_adapter.fromPythonVectorStoreQuery(query)
        j_query_result = self._j_resource.query(j_query)
        return from_java_vector_store_query_result(j_query_result)

    @override
    def size(self, collection_name: str | None = None) -> int:
        return self._j_resource.size(collection_name)

    @override
    def get(
            self,
            ids: str | List[str] | None = None,
            collection_name: str | None = None,
            **kwargs: Any,
    ) -> List[Document]:
        ids = _maybe_cast_to_list(ids)
        j_documents = self._j_resource.get(ids, collection_name, kwargs)
        return [from_java_document(j_document) for j_document in j_documents]

    @override
    def delete(
        self,
        ids: str | List[str] | None = None,
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> List[str]:
        ids = _maybe_cast_to_list(ids)
        return self._j_resource.delete(ids, collection_name, kwargs)

    @override
    def get_or_create_collection(
            self, name: str, metadata: Dict[str, Any] | None = None
    ) -> Collection:
        j_collection = self._j_resource.getOrCreateCollection(name, metadata)
        return from_java_collection(j_collection)

    @override
    def get_collection(self, name: str) -> Collection:
        j_collection = self._j_resource.getCollection(name)
        return from_java_collection(j_collection)

    @override
    def delete_collection(self, name: str) -> Collection:
        j_collection = self._j_resource.deleteCollection(name)
        return from_java_collection(j_collection)

    @override
    def _add_embedding(
        self,
        *,
        documents: List[Document],
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> List[str]:
        """Private functions should never be called for the Resource Wrapper."""

    @override
    def _query_embedding(
        self, embedding: list[float], limit: int = 10, **kwargs: Any
    ) -> list[Document]:
        """Private functions should never be called for the Resource Wrapper."""
