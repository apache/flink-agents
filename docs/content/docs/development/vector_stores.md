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

## Vector Store Interface

Flink Agents uses a two-component architecture for vector stores:

1. **Connection**: Manages the connection to the vector database (client configuration, authentication)
2. **Setup**: Configures the specific collection/index and coordinates with embedding models

If you want to use vector stores not offered by the built-in providers, you can extend the base vector store classes and implement your own! The vector store system is built around two main abstract classes:

### BaseVectorStoreConnection

Handles the connection to vector databases and provides raw vector search operations:

```python
from flink_agents.api.vector_stores.vector_store import BaseVectorStoreConnection, Document

class MyVectorStoreConnection(BaseVectorStoreConnection):
    def query(self, embedding: List[float], limit: int = 10, **kwargs) -> List[Document]:
        # Implementation for vector search using pre-computed embeddings
        pass
```

### BaseVectorStoreSetup

Manages vector store configuration and coordinates with embedding models for text-based search:

```python
from flink_agents.api.vector_stores.vector_store import BaseVectorStoreSetup

class MyVectorStoreSetup(BaseVectorStoreSetup):
    connection: str      # Name of the connection to use
    embedding_model: str # Name of the embedding model to use

    @property
    def store_kwargs(self) -> Dict[str, Any]:
        # Return vector store-specific configuration
        return {"collection": "my_collection", ...}
```

### Query Objects

Vector stores use structured query objects for consistent interfaces:

```python
from flink_agents.api.vector_stores.vector_store import VectorStoreQuery, VectorStoreQueryMode

# Create a semantic search query
query = VectorStoreQuery(
    mode=VectorStoreQueryMode.SEMANTIC,
    query_text="What is machine learning?",
    limit=5,
    extra_args={"filters": {"category": "ai"}}
)

# Execute the query
result = vector_store_setup.query(query)
for doc in result.documents:
    print(f"Content: {doc.content}")
    print(f"Metadata: {doc.metadata}")
```

### Resource Decorators

Flink Agents provides decorators to simplify vector store setup within agents:

#### @vector_store_connection

The `@vector_store_connection` decorator marks a method that creates a vector store connection.

#### @vector_store_setup

The `@vector_store_setup` decorator marks a method that creates a vector store setup.

## Built-in Providers

### Chroma

[Chroma](https://www.trychroma.com/home) is an open-source vector database that provides efficient storage and querying of embeddings with support for multiple deployment modes.

#### Prerequisites

1. Install ChromaDB: `pip install chromadb`
2. For server mode, start ChromaDB server: `chroma run --path /db_path`
3. For cloud mode, get API key from [ChromaDB Cloud](https://www.trychroma.com/)

#### ChromaVectorStoreConnection Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `persist_directory` | str | None | Directory for persistent storage. If None, uses in-memory client |
| `host` | str | None | Host for ChromaDB server connection |
| `port` | int | `8000` | Port for ChromaDB server connection |
| `api_key` | str | None | API key for Chroma Cloud connection |
| `client_settings` | Settings | None | ChromaDB client settings for advanced configuration |
| `tenant` | str | `"default_tenant"` | ChromaDB tenant for multi-tenancy support |
| `database` | str | `"default_database"` | ChromaDB database name |

#### ChromaVectorStoreSetup Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | str | Required | Reference to connection method name |
| `embedding_model` | str | Required | Reference to embedding model method name |
| `collection` | str | `"flink_agents_chroma_collection"` | Name of the ChromaDB collection to use |
| `collection_metadata` | dict | `{}` | Metadata for the collection |
| `create_collection_if_not_exists` | bool | `True` | Whether to create the collection if it doesn't exist |

#### Usage Example

```python
from typing import Any, Dict, Tuple, Type
from flink_agents.api.agent import Agent
from flink_agents.api.decorators import vector_store_connection, vector_store_setup, embedding_model_connection, embedding_model_setup
from flink_agents.integrations.vector_stores.chroma.chroma_vector_store import (
    ChromaVectorStoreConnection,
    ChromaVectorStoreSetup
)
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
    @vector_store_connection
    @staticmethod
    def chroma_connection() -> Tuple[Type[ChromaVectorStoreConnection], Dict[str, Any]]:
        return ChromaVectorStoreConnection, {
            "persist_directory": "/path/to/chroma/data",  # For persistent storage
            # Or use other modes:
            # "host": "localhost", "port": 8000  # For server mode
            # "api_key": "your-chroma-cloud-key"  # For cloud mode
        }

    @vector_store_setup
    @staticmethod
    def chroma_store() -> Tuple[Type[ChromaVectorStoreSetup], Dict[str, Any]]:
        return ChromaVectorStoreSetup, {
            "connection": "chroma_connection",
            "embedding_model": "openai_embedding",
            "collection": "my_documents",
            "create_collection_if_not_exists": True
        }

    ...
```

#### Deployment Modes

ChromaDB supports multiple deployment modes:

**In-Memory Mode** (Development/Testing):
```python
@vector_store_connection
@staticmethod
def chroma_connection() -> Tuple[Type[ChromaVectorStoreConnection], Dict[str, Any]]:
    return ChromaVectorStoreConnection, {}  # No configuration needed
```

**Persistent Mode** (Local Production):
```python
@vector_store_connection
@staticmethod
def chroma_connection() -> Tuple[Type[ChromaVectorStoreConnection], Dict[str, Any]]:
    return ChromaVectorStoreConnection, {
        "persist_directory": "/path/to/chroma/data"
    }
```

**Server Mode** (Distributed):
```python
@vector_store_connection
@staticmethod
def chroma_connection() -> Tuple[Type[ChromaVectorStoreConnection], Dict[str, Any]]:
    return ChromaVectorStoreConnection, {
        "host": "your-chroma-server.com",
        "port": 8000
    }
```

**Cloud Mode** (Managed):
```python
@vector_store_connection
@staticmethod
def chroma_connection() -> Tuple[Type[ChromaVectorStoreConnection], Dict[str, Any]]:
    return ChromaVectorStoreConnection, {
        "api_key": "your-chroma-cloud-api-key"
    }
```