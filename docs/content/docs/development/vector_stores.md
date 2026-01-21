---
title: Vector Stores
weight: 6
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

{{< tabs "Resource Decorators" >}}

{{< tab "Python" >}}

#### @vector_store

The `@vector_store` decorator marks a method that creates a vector store. Vector stores automatically integrate with embedding models for text-based search.

{{< /tab >}}

{{< tab "Java" >}}

#### @VectorStore

The `@VectorStore` annotation marks a method that creates a vector store.

{{< /tab >}}

{{< /tabs >}}

### Query Objects

Vector stores use structured query objects for consistent interfaces:

{{< tabs "Query Objects" >}}

{{< tab "Python" >}}

```python
# Create a semantic search query
query = VectorStoreQuery(
    query_text="What is Apache Flink Agents?",
    limit=3
)
```

{{< /tab >}}

{{< tab "Java" >}}

```java
// Create a semantic search query
VectorStoreQuery query = new VectorStoreQuery(
        "What is Apache Flink Agents?", // query text
        3 // limit
);
```

{{< /tab >}}

{{< /tabs >}}

### Query Results

When you execute a query, you receive a `VectorStoreQueryResult` object that contains the search results:

The `VectorStoreQueryResult` contains:
- **documents**: A list of `Document` objects representing the retrieved results
- Each `Document` has:
  - **content**: The actual text content of the document
  - **metadata**: Associated metadata (source, category, timestamp, etc.)
  - **id**: Unique identifier of the document (if available)

{{< tabs "Query Results" >}}

{{< tab "Python" >}}

```python
# Execute the query
result = vector_store.query(query)
```

{{< /tab >}}

{{< tab "Java" >}}

```java
// Execute the query
VectorStoreQueryResult result = vectorStore.query(query);
```

{{< /tab >}}

{{< /tabs >}}

### Usage Example

Here's how to define and use vector stores in your agent:

{{< tabs "Usage Example" >}}

{{< tab "Python" >}}

```python
class MyAgent(Agent):

    # Embedding model setup (required for vector store)
    @embedding_model_connection
    @staticmethod
    def openai_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=Constant.OPENAI_EMBEDDING_MODEL_CONNECTION,
            api_key="your-api-key-here"
        )

    @embedding_model_setup
    @staticmethod
    def openai_embedding() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=Constant.OPENAI_EMBEDDING_MODEL_SETUP,
            connection="openai_connection",
            model="your-embedding-model-here"
        )

    # In-memory Chroma setup
    @vector_store
    @staticmethod
    def chroma_store() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=Constant.CHROMA_VECTOR_STORE,
            embedding_model="openai_embedding",
            collection="my_chroma_store"
        )

    @action(InputEvent)
    @staticmethod
    def search_documents(event: InputEvent, ctx: RunnerContext) -> None:
        # Get the vector store from the runtime context
        vector_store = ctx.get_resource("chroma_store", ResourceType.VECTOR_STORE)

        # Create a semantic search query
        user_query = str(event.input)
        query = VectorStoreQuery(
            query_text=user_query,
            limit=3
        )

        # Perform the search
        result = vector_store.query(query)

        # Handle the VectorStoreQueryResult
        # Process the retrieved context as needed for your use case
```

{{< /tab >}}

{{< tab "Java" >}}

```java
public class MyAgent extends Agent {

    @EmbeddingModelConnection
    public static ResourceDescriptor embeddingConnection() {
        return ResourceDescriptor.Builder.newBuilder(Constant.OLLAMA_EMBEDDING_MODEL_CONNECTION)
                .addInitialArgument("host", "http://localhost:11434")
                .build();
    }

    @EmbeddingModelSetup
    public static ResourceDescriptor embeddingModel() {
        return ResourceDescriptor.Builder.newBuilder(Constant.OLLAMA_EMBEDDING_MODEL_SETUP)
                .addInitialArgument("connection", "embeddingConnection")
                .addInitialArgument("model", "nomic-embed-text")
                .build();
    }

    @VectorStore
    public static ResourceDescriptor vectorStore() {
        return ResourceDescriptor.Builder.newBuilder(Constant.ELASTICSEARCH_VECTOR_STORE)
                .addInitialArgument("embedding_model", "embeddingModel")
                .addInitialArgument("host", "http://localhost:9200")
                .addInitialArgument("index", "my_documents")
                .addInitialArgument("vector_field", "content_vector")
                .addInitialArgument("dims", 1536)
                .build();
    }

    @Action(listenEvents = InputEvent.class)
    public static void searchDocuments(InputEvent event, RunnerContext ctx) {
        // Option 1: Manual search via the vector store
        VectorStore vectorStore = (VectorStore) ctx.getResource("vectorStore", ResourceType.VECTOR_STORE);
        String queryText = (String) event.getInput();
        VectorStoreQuery query = new VectorStoreQuery(queryText, 3);
        VectorStoreQueryResult result = vectorStore.query(query);

        // Option 2: Request context retrieval via built-in events
        ctx.sendEvent(new ContextRetrievalRequestEvent(queryText, "vectorStore"));
    }

    @Action(listenEvents = ContextRetrievalResponseEvent.class)
    public static void onSearchResponse(ContextRetrievalResponseEvent event, RunnerContext ctx) {
        List<Document> documents = event.getDocuments();
        // Process the retrieved documents...
    }
}
```

{{< /tab >}}

{{< /tabs >}}

## Built-in Providers

### Chroma

[Chroma](https://www.trychroma.com/home) is an open-source vector database that provides efficient storage and querying of embeddings with support for multiple deployment modes.

{{< hint info >}}
Chroma is currently supported in the Python API only. To use Chroma from Java agents, see [Using Cross-Language Providers](#using-cross-language-providers).
{{< /hint >}}

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

{{< tabs "Chroma Usage Example" >}}

{{< tab "Python" >}}

```python
class MyAgent(Agent):

    # Embedding model setup (required for vector store)
    @embedding_model_connection
    @staticmethod
    def openai_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=Constant.OPENAI_EMBEDDING_MODEL_CONNECTION,
            api_key="your-api-key-here"
        )

    @embedding_model_setup
    @staticmethod
    def openai_embedding() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=Constant.OPENAI_EMBEDDING_MODEL_SETUP,
            connection="openai_connection",
          model="your-embedding-model-here"
        )

    # Vector store setup
    @vector_store
    @staticmethod
    def chroma_store() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=Constant.CHROMA_VECTOR_STORE,
            embedding_model="openai_embedding",
            persist_directory="/path/to/chroma/data",  # For persistent storage
            collection="my_documents",
            create_collection_if_not_exists=True
            # Or use other modes:
            # "host": "localhost", "port": 8000  # For server mode
            # "api_key": "your-chroma-cloud-key"  # For cloud mode
        )

    ...
```

{{< /tab >}}

{{< /tabs >}}

#### Deployment Modes

ChromaDB supports multiple deployment modes:

**In-Memory Mode**
```python
@vector_store
@staticmethod
def chroma_store() -> ResourceDescriptor:
    return ResourceDescriptor(
        clazz=Constant.CHROMA_VECTOR_STORE,
        embedding_model="your_embedding_model",
        collection="my_documents"
        # No connection configuration needed for in-memory mode
    )
```

**Persistent Mode**
```python
@vector_store
@staticmethod
def chroma_store() -> ResourceDescriptor:
    return ResourceDescriptor(
        clazz=Constant.CHROMA_VECTOR_STORE,
        embedding_model="your_embedding_model",
        persist_directory="/path/to/chroma/data",
        collection="my_documents"
    )
```

**Server Mode**
```python
@vector_store
@staticmethod
def chroma_store() -> ResourceDescriptor:
    return ResourceDescriptor(
        clazz=Constant.CHROMA_VECTOR_STORE,
        embedding_model="your_embedding_model",
        host="your-chroma-server.com",
        port=8000,
        collection="my_documents"
    )
```

**Cloud Mode**
```python
@vector_store
@staticmethod
def chroma_store() -> ResourceDescriptor:
    return ResourceDescriptor(
        clazz=Constant.CHROMA_VECTOR_STORE,
        embedding_model="your_embedding_model",
        api_key="your-chroma-cloud-api-key",
        collection="my_documents"
    )
```

### Elasticsearch

[Elasticsearch](https://www.elastic.co/elasticsearch/) is a distributed, RESTful search and analytics engine that supports vector search through dense vector fields and K-Nearest Neighbors (KNN).

{{< hint info >}}
Elasticsearch is currently supported in the Java API only. To use Elasticsearch from Python agents, see [Using Cross-Language Providers](#using-cross-language-providers).
{{< /hint >}}

#### Prerequisites

1. An Elasticsearch cluster (version 8.0 or later for KNN support).
2. An index with a `dense_vector` field.

#### ElasticsearchVectorStore Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `embedding_model` | str | Required | Reference to embedding model resource name |
| `index` | str | Required | Target Elasticsearch index name |
| `vector_field` | str | Required | Name of the dense vector field used for KNN |
| `dims` | int | `768` | Vector dimensionality |
| `k` | int | None | Number of nearest neighbors to return; can be overridden per query |
| `num_candidates` | int | None | Candidate set size for ANN search; can be overridden per query |
| `filter_query` | str | None | Raw JSON Elasticsearch filter query (DSL) applied as a post-filter |
| `host` | str | `"http://localhost:9200"` | Elasticsearch endpoint |
| `hosts` | str | None | Comma-separated list of Elasticsearch endpoints |
| `username` | str | None | Username for basic authentication |
| `password` | str | None | Password for basic authentication |
| `api_key_base64` | str | None | Base64-encoded API key for authentication |
| `api_key_id` | str | None | API key ID for authentication |
| `api_key_secret` | str | None | API key secret for authentication |

#### Usage Example

{{< tabs "Elasticsearch Usage Example" >}}

{{< tab "Java" >}}

Here's how to define an Elasticsearch vector store in your Java agent:

```java
@VectorStore
public static ResourceDescriptor vectorStore() {
    return ResourceDescriptor.Builder.newBuilder(Constant.ELASTICSEARCH_VECTOR_STORE)
            .addInitialArgument("embedding_model", "embeddingModel")
            .addInitialArgument("host", "http://localhost:9200")
            .addInitialArgument("index", "my_documents")
            .addInitialArgument("vector_field", "content_vector")
            .addInitialArgument("dims", 1536)
            // Optional authentication
            // .addInitialArgument("username", "elastic")
            // .addInitialArgument("password", "secret")
            .build();
}
```

{{< /tab >}}

{{< /tabs >}}

## Using Cross-Language Providers

Flink Agents supports cross-language vector store integration, allowing you to use vector stores implemented in one language (Java or Python) from agents written in the other language. This is particularly useful when a vector store provider is only available in one language (e.g., Elasticsearch is currently Java-only, Chroma is currently Python-only).

{{< hint warning >}}
**Limitations:**
- Cross-language resources are currently supported only when [running in Flink]({{< ref "docs/operations/deployment#run-in-flink" >}}), not in local development mode
- Complex object serialization between languages may have limitations
{{< /hint >}}

### How To Use

To leverage vector store supports provided in a different language, you need to declare the resource within a built-in cross-language wrapper, and specify the target provider as an argument:

- **Using Java vector stores in Python**: Use `Constant.JAVA_COLLECTION_MANAGEABLE_VECTOR_STORE`, specifying the Java provider class via the `java_clazz` parameter
- **Using Python vector stores in Java**: Use `Constant.PYTHON_COLLECTION_MANAGEABLE_VECTOR_STORE`, specifying the Python provider via `module` and `clazz` parameters

### Usage Example

{{< tabs "Cross-Language Vector Store Usage Example" >}}

{{< tab "Using Java Vector Store in Python" >}}

```python
class MyAgent(Agent):

    # Define embedding model (can be Java or Python implementation)
    @embedding_model_connection
    @staticmethod
    def my_embedding_connection() -> ResourceDescriptor:
        # Configure embedding model connection as needed
        pass

    @embedding_model_setup
    @staticmethod
    def my_embedding_model() -> ResourceDescriptor:
        # Configure embedding model setup as needed
        pass

    # Use Java vector store with embedding model
    @vector_store
    @staticmethod
    def java_vector_store() -> ResourceDescriptor:
        # In pure Java, the equivalent ResourceDescriptor would be:
        # ResourceDescriptor.Builder
        #     .newBuilder(Constant.ElasticsearchVectorStore)
        #     .addInitialArgument("embedding_model", "my_embedding_model")
        #     .addInitialArgument("host", "http://localhost:9200")
        #     .addInitialArgument("index", "my_documents")
        #     .addInitialArgument("dims", 768)
        #     .build();
        return ResourceDescriptor(
            clazz=Constant.JAVA_COLLECTION_MANAGEABLE_VECTOR_STORE,
            java_clazz="org.apache.flink.agents.integrations.vectorstores.elasticsearch.ElasticsearchVectorStore",
            embedding_model="my_embedding_model",
            host="http://localhost:9200",
            index="my_documents",
            dims=768
        )

    @action(InputEvent)
    @staticmethod
    def process_input(event: InputEvent, ctx: RunnerContext) -> None:
        # Use Java vector store from Python
        vector_store = ctx.get_resource("java_vector_store", ResourceType.VECTOR_STORE)
        
        # Perform semantic search
        query = VectorStoreQuery(query_text=str(event.input), limit=3)
        result = vector_store.query(query)
        
        # Process the retrieved documents
```

{{< /tab >}}

{{< tab "Using Python Vector Store in Java" >}}

```java
public class MyAgent extends Agent {

    // Define embedding model (can be Java or Python implementation)
    @EmbeddingModelConnection
    public static ResourceDescriptor myEmbeddingConnection() {
        // Configure embedding model connection as needed
        return null;
    }

    @EmbeddingModelSetup
    public static ResourceDescriptor myEmbeddingModel() {
        // Configure embedding model setup as needed
        return null;
    }
    
    @VectorStore
    public static ResourceDescriptor pythonVectorStore() {
        // In pure Python, the equivalent ResourceDescriptor would be:
        // ResourceDescriptor(
        //     clazz=Constant.CHROMA_VECTOR_STORE,
        //     embedding_model="my_embedding_model",
        // )
        return ResourceDescriptor.Builder.newBuilder(PYTHON_COLLECTION_MANAGEABLE_VECTOR_STORE)
                .addInitialArgument(
                        "module", 
                        "flink_agents.integrations.vector_stores.chroma.chroma_vector_store")
                .addInitialArgument("clazz", "ChromaVectorStore")
                .addInitialArgument("embedding_model", "myEmbeddingModel")
                .build();
    }

    @Action(listenEvents = {InputEvent.class})
    public static void processInput(InputEvent event, RunnerContext ctx) throws Exception {
        // Use Python vector store from Java
        VectorStore vectorStore = 
            (VectorStore) ctx.getResource("pythonVectorStore", ResourceType.VECTOR_STORE);
        
        // Perform semantic search
        VectorStoreQuery query = new VectorStoreQuery((String) event.getInput(), 3);
        VectorStoreQueryResult result = vectorStore.query(query);
        
        // Process the retrieved documents
    }
}
```

{{< /tab >}}

{{< /tabs >}}

## Custom Providers

{{< hint warning >}}
The custom provider APIs are experimental and unstable, subject to incompatible changes in future releases.
{{< /hint >}}

If you want to use vector stores not offered by the built-in providers, you can extend the base vector store class and implement your own! The vector store system is built around the `BaseVectorStore` abstract class.

### BaseVectorStore

The base class handles text-to-vector conversion and provides the high-level query interface. You only need to implement the core vector search functionality.

{{< tabs "Custom Vector Store" >}}

{{< tab "Python" >}}

```python
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

{{< /tab >}}

{{< tab "Java" >}}

```java
public class MyVectorStore extends BaseVectorStore {

    public MyVectorStore(
            ResourceDescriptor descriptor,
            BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
    }

    @Override
    public Map<String, Object> getStoreKwargs() {
        // Return vector store-specific configuration
        // These parameters are merged with query-specific parameters
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("index", "my_index");
        return kwargs;
    }

    @Override
    public List<Document> queryEmbedding(float[] embedding, int limit, Map<String, Object> args) {
        // Core method: perform vector search using pre-computed embedding
        // - embedding: Pre-computed embedding vector for semantic search
        // - limit: Maximum number of results to return
        // - args: Vector store-specific parameters
        // - Returns: List of Document objects matching the search criteria
        return null;
    }
}
```

{{< /tab >}}

{{< /tabs >}}