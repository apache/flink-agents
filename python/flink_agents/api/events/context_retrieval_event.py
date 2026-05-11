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
from typing import ClassVar, List

try:
    from typing import override
except ImportError:
    from typing_extensions import override
from uuid import UUID

from flink_agents.api.events.event import Event
from flink_agents.api.vector_stores.vector_store import Document


class ContextRetrievalRequestEvent(Event):
    """Event representing a request for context retrieval.

    Attributes:
    ----------
    query : str
        The search query text to find relevant context for
    vector_store : str
        Name of the vector store setup resource to use
    max_results : int
        Maximum number of results to return (default: 3)
    """

    EVENT_TYPE: ClassVar[str] = "_context_retrieval_request_event"

    def __init__(self, query: str, vector_store: str, max_results: int = 3) -> None:
        """Create a ContextRetrievalRequestEvent."""
        super().__init__(
            type=ContextRetrievalRequestEvent.EVENT_TYPE,
            attributes={
                "query": query,
                "vector_store": vector_store,
                "max_results": max_results,
            },
        )

    @classmethod
    @override
    def from_event(cls, event: Event) -> "ContextRetrievalRequestEvent":
        assert "query" in event.attributes
        assert "vector_store" in event.attributes
        return ContextRetrievalRequestEvent(
            query=event.attributes["query"],
            vector_store=event.attributes["vector_store"],
            max_results=event.attributes.get("max_results", 3),
        )

    @property
    def query(self) -> str:
        """Return the search query."""
        return self.get_attr("query")

    @property
    def vector_store(self) -> str:
        """Return the vector store name."""
        return self.get_attr("vector_store")

    @property
    def max_results(self) -> int:
        """Return the maximum number of results."""
        return self.get_attr("max_results")


class ContextRetrievalResponseEvent(Event):
    """Event representing retrieved context results.

    Attributes:
    ----------
    request_id : UUID
        ID of the original request event
    query : str
        The original search query from the request
    documents : List[Document]
        List of retrieved documents from the vector store
    """

    EVENT_TYPE: ClassVar[str] = "_context_retrieval_response_event"

    def __init__(self, request_id: UUID, query: str, documents: List[Document]) -> None:
        """Create a ContextRetrievalResponseEvent."""
        super().__init__(
            type=ContextRetrievalResponseEvent.EVENT_TYPE,
            attributes={
                "request_id": request_id,
                "query": query,
                "documents": documents,
            },
        )

    @classmethod
    @override
    def from_event(cls, event: Event) -> "ContextRetrievalResponseEvent":
        assert "request_id" in event.attributes
        assert "query" in event.attributes
        assert "documents" in event.attributes
        documents_raw = event.attributes["documents"]
        documents = [
            Document.model_validate(d) if isinstance(d, dict) else d
            for d in documents_raw
        ]
        return ContextRetrievalResponseEvent(
            request_id=event.attributes["request_id"],
            query=event.attributes["query"],
            documents=documents,
        )

    @property
    def request_id(self) -> UUID:
        """Return the request event ID."""
        return self.get_attr("request_id")

    @property
    def query(self) -> str:
        """Return the original search query."""
        return self.get_attr("query")

    @property
    def documents(self) -> List[Document]:
        """Return the retrieved documents."""
        return self.get_attr("documents")
