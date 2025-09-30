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

import chromadb

from flink_agents.integrations.embedding_models.local.ollama_embedding_model import (
    OllamaEmbeddingModelConnection,
)

OLLAMA_EMBEDDING_MODEL = os.environ.get("OLLAMA_EMBEDDING_MODEL", "nomic-embed-text")

"""Utility for populating ChromaDB with sample knowledge documents for RAG examples."""


def populate_knowledge_base() -> None:
    """Populate ChromaDB with sample knowledge documents using Ollama embeddings."""
    print("Populating ChromaDB with sample knowledge documents...")

    # Create connections directly
    embedding_connection = OllamaEmbeddingModelConnection()
    chroma_client = chromadb.EphemeralClient()

    # Get collection (create if doesn't exist)
    collection_name = "example_knowledge_base"
    collection = chroma_client.get_or_create_collection(
        name=collection_name,
        metadata=None,
    )

    # Sample documents to embed and store
    documents = [
        "Apache Flink is a stream processing framework for distributed, high-performing, always-available, and accurate data streaming applications.",
        "Flink provides exactly-once state consistency guarantees and low-latency processing with high throughput.",
        "Apache Flink Agents is an innovative Agentic AI framework built on Apache Flink that enables distributed, stateful execution of AI agents.",
        "Vector stores are databases optimized for storing and searching high-dimensional vectors, commonly used in context retrieval applications.",
        "Context retrieval combines information retrieval with language generation to provide more accurate and contextual responses by finding relevant information.",
    ]

    metadatas = [
        {"topic": "flink", "source": "documentation"},
        {"topic": "flink", "source": "documentation"},
        {"topic": "flink_agents", "source": "documentation"},
        {"topic": "vector_stores", "source": "ai_concepts"},
        {"topic": "context_retrieval", "source": "ai_concepts"},
    ]

    # Generate real embeddings using Ollama
    embeddings = []
    for _i, doc in enumerate(documents):
        embedding = embedding_connection.embed(doc, model=OLLAMA_EMBEDDING_MODEL)
        embeddings.append(embedding)

    # Prepare data for ChromaDB
    test_data = {
        "documents": documents,
        "embeddings": embeddings,
        "metadatas": metadatas,
        "ids": [f"doc{i + 1}" for i in range(len(documents))]
    }

    # Add documents to ChromaDB
    collection.add(**test_data)

    print(f"Knowledge base setup complete! Added {len(documents)} documents to ChromaDB.")
