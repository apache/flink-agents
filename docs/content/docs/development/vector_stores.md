---
title: Vector Stores
weight: 5
type: docs
---
<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Vector Stores

{{< hint info >}}
Vector stores are currently supported in the Python API only. Java API support is planned for future releases.
{{< /hint >}}

{{< hint info >}}
This page covers semantic search using vector stores. Additional query modes (keyword, hybrid) are planned for future releases.
{{< /hint >}}

## Overview

Vector stores enable efficient storage, indexing, and retrieval of high-dimensional embedding vectors alongside their associated documents. They provide the foundation for semantic search capabilities in AI applications by allowing fast similarity searches across large document collections.

In Flink Agents, vector stores are essential for:
- **Document Retrieval**: Finding relevant documents based on semantic similarity
- **Knowledge Base Search**: Querying large collections of information using natural language
- **Retrieval-Augmented Generation (RAG)**: Providing context to language models from vector-indexed knowledge
- **Semantic Similarity**: Comparing and ranking documents by meaning rather than keywords

## Getting Started

To use vector stores in your agents, you need to configure both a vector store and an embedding model, then perform semantic search using structured queries.

### Resource Decorators

Flink Agents provides decorators to simplify vector store setup within agents:

#### @vector_store

The `@vector_store` decorator marks a method that creates a vector store. Vector stores automatically integrate with embedding models for text-based search.

### Query Objects

Vector stores use structured query objects for consistent interfaces:

```python
from flink_agents.api.vector_stores.vector_store import VectorStoreQuery, VectorStoreQueryMode

# Create a semantic search query
query = VectorStoreQuery(
    mode=VectorStoreQueryMode.SEMANTIC,
    query_text="What is Apache Flink Agents?",
    limit=5
)
```

### Query Results

When you execute a query, you receive a `VectorStoreQueryResult` object that contains the search results:

```python
# Execute the query
result = vector_store.query(query)
```

The `VectorStoreQueryResult` contains:
- **documents**: A list of `Document` objects representing the retrieved results
- Each `Document` has:
  - **content**: The actual text content of the document
  - **metadata**: Associated metadata (source, author, timestamp, etc.)
  - **id**: Unique identifier of the document (if available)

### Usage Example

Here's how to define and use vector stores in your agent:

```python
from typing import Any, Dict, Tuple, Type
from flink_agents.api.agent import Agent
from flink_agents.api.decorators import action, vector_store, embedding_model_connection, embedding_model_setup
from flink_agents.api.events import Event, InputEvent
from flink_agents.api.context import RunnerContext
from flink_agents.api.vector_stores.vector_store import VectorStoreQuery, VectorStoreQueryMode
from flink_agents.integrations.vector_stores.chroma.chroma_vector_store import ChromaVectorStore
from flink_agents.integrations.embedding_models.openai_embedding_model import (
    OpenAIEmbeddingModelConnection,
    OpenAIEmbeddingModelSetup
)

class MyAgent(Agent):

    # Embedding model setup (required for vector store)
    @embedding_model_connection
    @staticmethod
    def openai_connection() -> Tuple[Type[OpenAIEmbeddingModelConnection], Dict[str, Any]]:
        return OpenAIEmbeddingModelConnection, {
            "api_key": "your-api-key-here"
        }

    @embedding_model_setup
    @staticmethod
    def openai_embedding() -> Tuple[Type[OpenAIEmbeddingModelSetup], Dict[str, Any]]:
        return OpenAIEmbeddingModelSetup, {
            "connection": "openai_connection",
            "model": "text-embedding-3-small"
        }

    # Vector store setup
    @vector_store
    @staticmethod
    def chroma_store() -> Tuple[Type[ChromaVectorStore], Dict[str, Any]]:
        return ChromaVectorStore, {
            "embedding_model": "openai_embedding",
            "persist_directory": "/path/to/chroma/data",
            "collection": "my_documents"
        }

    @action(InputEvent)
    @staticmethod
    def search_documents(event: Event, ctx: RunnerContext):
        # Get the vector store from the runtime context
        vector_store = ctx.get_resource("chroma_store")

        # Create a semantic search query
        user_query = str(event.input)
        query = VectorStoreQuery(
            mode=VectorStoreQueryMode.SEMANTIC,
            query_text=user_query,
            limit=3
        )

        # Perform the search
        result = vector_store.query(query)

        # Handle the VectorStoreQueryResult
        # Process the retrieved context as needed for your use case
```

## Built-in Providers

### Chroma

[Chroma](https://www.trychroma.com/home) is an open-source vector database that provides efficient storage and querying of embeddings with support for multiple deployment modes.

#### Prerequisites

1. Install ChromaDB: `pip install chromadb`
2. For server mode, start ChromaDB server: `chroma run --path /db_path`
3. For cloud mode, get API key from [ChromaDB Cloud](https://www.trychroma.com/)

#### ChromaVectorStore Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `embedding_model` | str | Required | Reference to embedding model method name |
| `persist_directory` | str | None | Directory for persistent storage. If None, uses in-memory client |
| `host` | str | None | Host for ChromaDB server connection |
| `port` | int | `8000` | Port for ChromaDB server connection |
| `api_key` | str | None | API key for Chroma Cloud connection |
| `client_settings` | Settings | None | ChromaDB client settings for advanced configuration |
| `tenant` | str | `"default_tenant"` | ChromaDB tenant for multi-tenancy support |
| `database` | str | `"default_database"` | ChromaDB database name |
| `collection` | str | `"flink_agents_chroma_collection"` | Name of the ChromaDB collection to use |
| `collection_metadata` | dict | `{}` | Metadata for the collection |
| `create_collection_if_not_exists` | bool | `True` | Whether to create the collection if it doesn't exist |

#### Usage Example

```python
from typing import Any, Dict, Tuple, Type
from flink_agents.api.agent import Agent
from flink_agents.api.decorators import vector_store, embedding_model_connection, embedding_model_setup
from flink_agents.integrations.vector_stores.chroma.chroma_vector_store import ChromaVectorStore
from flink_agents.integrations.embedding_models.openai_embedding_model import (
    OpenAIEmbeddingModelConnection,
    OpenAIEmbeddingModelSetup
)

class MyAgent(Agent):

    # Embedding model setup (required for vector store)
    @embedding_model_connection
    @staticmethod
    def openai_connection() -> Tuple[Type[OpenAIEmbeddingModelConnection], Dict[str, Any]]:
        return OpenAIEmbeddingModelConnection, {
            "api_key": "your-api-key-here"
        }

    @embedding_model_setup
    @staticmethod
    def openai_embedding() -> Tuple[Type[OpenAIEmbeddingModelSetup], Dict[str, Any]]:
        return OpenAIEmbeddingModelSetup, {
            "connection": "openai_connection",
            "model": "text-embedding-3-small"
        }

    # Vector store setup
    @vector_store
    @staticmethod
    def chroma_store() -> Tuple[Type[ChromaVectorStore], Dict[str, Any]]:
        return ChromaVectorStore, {
            "embedding_model": "openai_embedding",
            "persist_directory": "/path/to/chroma/data",  # For persistent storage
            "collection": "my_documents",
            "create_collection_if_not_exists": True
            # Or use other modes:
            # "host": "localhost", "port": 8000  # For server mode
            # "api_key": "your-chroma-cloud-key"  # For cloud mode
        }

    ...
```

#### Deployment Modes

ChromaDB supports multiple deployment modes:

**In-Memory Mode** (Development/Testing):
```python
@vector_store
@staticmethod
def chroma_store() -> Tuple[Type[ChromaVectorStore], Dict[str, Any]]:
    return ChromaVectorStore, {
        "embedding_model": "openai_embedding",
        "collection": "my_documents"
        # No connection configuration needed for in-memory mode
    }
```

**Persistent Mode** (Local Production):
```python
@vector_store
@staticmethod
def chroma_store() -> Tuple[Type[ChromaVectorStore], Dict[str, Any]]:
    return ChromaVectorStore, {
        "embedding_model": "openai_embedding",
        "persist_directory": "/path/to/chroma/data",
        "collection": "my_documents"
    }
```

**Server Mode** (Distributed):
```python
@vector_store
@staticmethod
def chroma_store() -> Tuple[Type[ChromaVectorStore], Dict[str, Any]]:
    return ChromaVectorStore, {
        "embedding_model": "openai_embedding",
        "host": "your-chroma-server.com",
        "port": 8000,
        "collection": "my_documents"
    }
```

**Cloud Mode** (Managed):
```python
@vector_store
@staticmethod
def chroma_store() -> Tuple[Type[ChromaVectorStore], Dict[str, Any]]:
    return ChromaVectorStore, {
        "embedding_model": "openai_embedding",
        "api_key": "your-chroma-cloud-api-key",
        "collection": "my_documents"
    }
```

## Custom Providers

{{< hint warning >}}
The custom provider APIs are experimental and unstable, subject to incompatible changes in future releases.
{{< /hint >}}

If you want to use vector stores not offered by the built-in providers, you can extend the base vector store class and implement your own! The vector store system is built around the `BaseVectorStore` abstract class.

### BaseVectorStore

The base class handles text-to-vector conversion and provides the high-level query interface. You only need to implement the core vector search functionality.

```python
from flink_agents.api.vector_stores.vector_store import BaseVectorStore, Document

class MyVectorStore(BaseVectorStore):
    # Add your custom configuration fields here

    @property
    def store_kwargs(self) -> Dict[str, Any]:
        # Return vector store-specific configuration
        # These parameters are merged with query-specific parameters
        return {"index": "my_index", ...}

    def query_embedding(self, embedding: List[float], limit: int = 10, **kwargs: Any) -> List[Document]:
        # Core method: perform vector search using pre-computed embedding
        # - embedding: Pre-computed embedding vector for semantic search
        # - limit: Maximum number of results to return
        # - kwargs: Vector store-specific parameters
        # - Returns: List of Document objects matching the search criteria
        pass
```