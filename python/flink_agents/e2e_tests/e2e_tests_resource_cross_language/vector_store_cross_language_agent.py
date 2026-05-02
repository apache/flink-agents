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
import os
import time

from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import (
    action,
    embedding_model_connection,
    embedding_model_setup,
    vector_store,
)
from flink_agents.api.events.context_retrieval_event import (
    ContextRetrievalRequestEvent,
    ContextRetrievalResponseEvent,
)
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.resource import (
    ResourceDescriptor,
    ResourceName,
    ResourceType,
)
from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.vector_stores.vector_store import (
    CollectionManageableVectorStore,
    Document,
    VectorStoreQuery,
    VectorStoreQueryMode,
)

TEST_COLLECTION = "test_collection"
MAX_RETRIES_TIMES = 10


class VectorStoreCrossLanguageAgent(Agent):
    """Example agent demonstrating cross-language embedding model testing."""

    @embedding_model_connection
    @staticmethod
    def embedding_model_connection() -> ResourceDescriptor:
        """EmbeddingModelConnection responsible for ollama model service connection."""
        if os.environ.get("EMBEDDING_TYPE") == "JAVA":
            return ResourceDescriptor(
                clazz=ResourceName.EmbeddingModel.JAVA_WRAPPER_CONNECTION,
                java_clazz=ResourceName.EmbeddingModel.Java.OLLAMA_CONNECTION,
                host="http://localhost:11434",
            )
        return ResourceDescriptor(
            clazz=ResourceName.EmbeddingModel.OLLAMA_CONNECTION,
            host="http://localhost:11434",
        )

    @embedding_model_setup
    @staticmethod
    def embedding_model() -> ResourceDescriptor:
        """EmbeddingModel which focus on math, and reuse ChatModelConnection."""
        if os.environ.get("EMBEDDING_TYPE") == "JAVA":
            return ResourceDescriptor(
                clazz=ResourceName.EmbeddingModel.JAVA_WRAPPER_SETUP,
                java_clazz=ResourceName.EmbeddingModel.Java.OLLAMA_SETUP,
                connection="embedding_model_connection",
                model=os.environ.get(
                    "OLLAMA_EMBEDDING_MODEL", "nomic-embed-text:latest"
                ),
            )
        return ResourceDescriptor(
            clazz=ResourceName.EmbeddingModel.OLLAMA_SETUP,
            connection="embedding_model_connection",
            model=os.environ.get("OLLAMA_EMBEDDING_MODEL", "nomic-embed-text:latest"),
        )

    @vector_store
    @staticmethod
    def vector_store() -> ResourceDescriptor:
        """Vector store setup for knowledge base."""
        return ResourceDescriptor(
            clazz=ResourceName.VectorStore.JAVA_WRAPPER_COLLECTION_MANAGEABLE_VECTOR_STORE,
            java_clazz=ResourceName.VectorStore.Java.ELASTICSEARCH_VECTOR_STORE,
            embedding_model="embedding_model",
            host=os.environ.get("ES_HOST"),
            index="my_documents",
            dims=768,
        )

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        """User defined action for processing input.

        In this action, we will test Vector store Collection Management and
        Document Management.
        """
        input_text = InputEvent.from_event(event).input

        stm = ctx.short_term_memory

        is_initialized = stm.get("is_initialized") or False

        if not is_initialized:
            print("[TEST] Initializing vector store...")

            vector_store = ctx.get_resource("vector_store", ResourceType.VECTOR_STORE)
            if isinstance(vector_store, CollectionManageableVectorStore):
                vector_store.create_collection_if_not_exists(
                    TEST_COLLECTION, metadata={"key1": "value1", "key2": "value2"}
                )
                vector_store.delete_collection(name=TEST_COLLECTION)

                print("[TEST] Vector store Collection Management PASSED")

                documents = [
                    Document(
                        id="doc1",
                        content="The sum of 1 and 2 equals 3.",
                        metadata={"category": "calculate", "source": "test"},
                    ),
                    Document(
                        id="doc2",
                        content="Why did the cat sit on the computer? Because it wanted to keep an eye on the mouse.",
                        metadata={"category": "ai-agent", "source": "test"},
                    ),
                    Document(
                        id="doc3",
                        content="This is a test document used to verify the delete functionality.",
                        metadata={"category": "utility", "source": "test"},
                    ),
                ]
                vector_store.add(documents=documents)

                assert len(vector_store.get()) == 3

                # Test delete
                vector_store.delete(ids="doc3")

                # Wait for vector store to delete doc3
                retry_time = 0
                while len(vector_store.get()) > 2 and retry_time < MAX_RETRIES_TIMES:
                    retry_time += 1
                    time.sleep(2)
                    print(f"[TEST] Retrying to delete doc3, retry_time={retry_time}")

                assert len(vector_store.get()) == 2

                # Test get
                doc = vector_store.get(ids="doc2")
                assert doc is not None
                assert doc[0].id == "doc2"
                assert (
                    doc[0].content
                    == "Why did the cat sit on the computer? Because it wanted to keep an eye on the mouse."
                )

                print("[TEST] Vector store Document Management PASSED")

                # Verify VectorStoreQuery.filters survives the Python->Java bridge.
                # Elasticsearch translates the unified-DSL filter to a bool/must term
                # post-filter, so the result must contain only the doc tagged
                # ``category=calculate`` (doc1).
                filtered_query = VectorStoreQuery(
                    mode=VectorStoreQueryMode.SEMANTIC,
                    query_text="sum",
                    limit=10,
                    filters={"category": "calculate"},
                )

                # ES is eventually consistent; allow a few retries.
                retry_time = 0
                filtered_docs = vector_store.query(filtered_query).documents
                while (
                    len(filtered_docs) != 1 and retry_time < MAX_RETRIES_TIMES
                ):
                    retry_time += 1
                    time.sleep(2)
                    filtered_docs = vector_store.query(filtered_query).documents

                assert len(filtered_docs) == 1, (
                    f"Filter {{category=calculate}} should match 1 doc, got "
                    f"{len(filtered_docs)}"
                )
                assert filtered_docs[0].id == "doc1"

                print("[TEST] Vector store filter query PASSED")

            stm.set("is_initialized", True)

        ctx.send_event(
            ContextRetrievalRequestEvent(query=input_text, vector_store="vector_store")
        )

    @action(ContextRetrievalResponseEvent.EVENT_TYPE)
    @staticmethod
    def contextRetrievalResponseEvent(
        event: Event, ctx: RunnerContext
    ) -> None:
        """User defined action for processing context retrieval response.

        In this action, we will test Vector store Context Retrieval.
        """
        documents = ContextRetrievalResponseEvent.from_event(event).documents

        assert documents is not None
        assert len(documents) > 0

        for document in documents:
            assert document is not None
            assert document.id is not None
            assert document.content is not None

        test_result = f"[PASS] retrieved_count={len(documents)}, first_doc_id={documents[0].id}, first_doc_preview={documents[0].content[:50]}"
        print(
            f"[TEST] Vector store Context Retrieval PASSED, first_doc_id={documents[0].id}, first_doc_preview={documents[0].content[:50]}"
        )

        ctx.send_event(OutputEvent(output=test_result))
