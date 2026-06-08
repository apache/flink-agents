---
title: Vector Stores
weight: 7
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

### Use Case
In Flink Agents, vector stores are essential for:
- **Document Retrieval**: Finding relevant documents based on semantic similarity
- **Knowledge Base Search**: Querying large collections of information using natural language
- **Retrieval-Augmented Generation (RAG)**: Providing context to language models from vector-indexed knowledge
- **Semantic Similarity**: Comparing and ranking documents by meaning rather than keywords

### Concepts
* **Document**: Document is the abstraction that represents a piece of text and associated metadata. A document may also carry a pre-computed `embedding` vector and a `score` populated by query results.
* **Filter DSL**: A unified, equality-only metadata filter dialect shared by `query`, `get`, and `delete`. The DSL covers only the subset every supported backend can honour (equality matching), so callers don't need to know each store's native operators. See the [Filter DSL](#filter-dsl) section below for details.

## How to use

To use vector stores in your agents, you need to configure both a vector store and an embedding model, then perform semantic search using structured queries.

### Declare a vector store in Agent

Flink Agents provides decorators/annotations to simplify vector store setup within agents:

{{< tabs "Resource Decorators" >}}

{{< tab "Python" >}}
```python
@vector_store
@staticmethod
def my_vector_store() -> ResourceDescriptor:
    return ResourceDescriptor(
        clazz=ResourceName.VectorStore.CHROMA_VECTOR_STORE,
        embedding_model="embedding_model",
        collection="my_chroma_store"
    )
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@VectorStore
public static ResourceDescriptor vectorStore() {
    return ResourceDescriptor.Builder.newBuilder(ResourceName.VectorStore.ELASTICSEARCH_VECTOR_STORE)
            .addInitialArgument("embedding_model", "embeddingModel")
            .addInitialArgument("host", "http://localhost:9200")
            .addInitialArgument("index", "my_documents")
            .addInitialArgument("vector_field", "content_vector")
            .addInitialArgument("dims", 1536)
            .build();
}
```
{{< /tab >}}

{{< /tabs >}}

### How to query the vector store

#### Query Objects

Vector stores use structured query objects for consistent interfaces:

{{< tabs "Query Objects" >}}

{{< tab "Python" >}}

```python
# Create a semantic search query
query = VectorStoreQuery(
    query_text="What is Apache Flink Agents?",
    limit=3,
    collection_name="my_collection",        # optional: defaults to the store's collection
    filters={"category": "docs"},           # optional: unified equality filter
    extra_args={"where_document": {...}},   # optional: backend-specific parameters
)
```

{{< /tab >}}

{{< tab "Java" >}}

```java
// Simple semantic-search query (defaults to default collection, no filter)
VectorStoreQuery query = new VectorStoreQuery(
        "What is Apache Flink Agents?", // query text
        3                                // limit
);

// Query with filters and explicit collection
VectorStoreQuery filteredQuery = new VectorStoreQuery(
        VectorStoreQueryMode.SEMANTIC,
        "What is Apache Flink Agents?",
        3,
        "my_collection",
        Map.of("category", "docs"),      // unified equality filter
        Map.of()                          // extraArgs (backend-specific)
);
```

{{< /tab >}}

{{< /tabs >}}

#### Query Results

When you execute a query, you receive a `VectorStoreQueryResult` object that contains the search results:

The `VectorStoreQueryResult` contains:
- **documents**: A list of `Document` objects representing the retrieved results
- Each `Document` has:
  - **content**: The actual text content of the document
  - **metadata**: Associated metadata (source, category, timestamp, etc.)
  - **id**: Unique identifier of the document (if available)
  - **embedding**: The pre-computed embedding vector (if available)
  - **score**: Similarity / distance score against the query (only populated by query results; `null` for non-query operations such as `get`). Semantics — distance vs. similarity, metric — are implementation-specific; consult each store's documentation.

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

### Manage collections

For vector stores that implement `CollectionManageableVectorStore`, you can create or delete collections during agent execution:
* `create_collection_if_not_exists` / `createCollectionIfNotExists`: Create the collection if it doesn't already exist; no-op otherwise. Backend-specific options (e.g. Chroma's `metadata`, Pinecone's `dimension` / `metric`) can be passed via `**kwargs` / `kwargs`. Unknown keys are ignored.
* `delete_collection` / `deleteCollection`: Delete a collection by name.

{{< hint info >}}
Collection-level operations are only supported for vector stores that implement `CollectionManageableVectorStore`. Among the built-in providers, Chroma (Python), Mem0 (Python), Elasticsearch (Java), OpenSearch (Java), and Milvus (Java) implement this interface.
{{< /hint >}}

{{< tabs "Collection level operations" >}}

{{< tab "Python" >}}

```python
# get the vector store from runner context
vector_store: CollectionManageableVectorStore = ctx.get_resource("vector_store", ResourceType.VECTOR_STORE)

# create a collection (no-op if it already exists)
vector_store.create_collection_if_not_exists(
    "my_collection",
    metadata={"key1": "value1", "key2": "value2"},  # backend-specific, ignored if unsupported
)

# delete the collection
vector_store.delete_collection("my_collection")
```

{{< /tab >}}

{{< tab "Java" >}}

```java
// get the vector store from runner context
CollectionManageableVectorStore vectorStore =
        (CollectionManageableVectorStore)
                ctx.getResource("vector_store", ResourceType.VECTOR_STORE);

// create a collection (no-op if it already exists)
vectorStore.createCollectionIfNotExists(
        "my_collection",
        Map.of("key1", "value1", "key2", "value2")); // backend-specific, ignored if unsupported

// delete the collection
vectorStore.deleteCollection("my_collection");
```

{{< /tab >}}

{{< /tabs >}}


### Manage documents
You can add, update, get, or delete documents during agent execution:
* `add`: Add documents to a collection. If a document has no `id`, the implementation generates one. Documents whose `embedding` field is `None` are auto-embedded by the configured embedding model.
* `update`: Update existing documents in place. Identity is read from `Document.id` — every document must have its `id` set; unlike `add`, `update` does not generate ids.
* `get`: Retrieve documents from a collection. When `ids` is provided, only those documents are returned. Otherwise up to `limit` documents matching `filters` are returned (default `limit=100`; pass `None` / `null` for unbounded).
* `delete`: Delete documents from a collection by `ids` or `filters`. When neither is provided, all documents in the collection are deleted.

{{< hint info >}}
If `collection_name` / `collection` is not specified, document-level operations apply to the default collection configured at vector-store initialization.
{{< /hint >}}

{{< tabs "Document level operations" >}}

{{< tab "Python" >}}

```python
# get the vector store from runner context
vector_store: CollectionManageableVectorStore = ctx.get_resource("vector_store", ResourceType.VECTOR_STORE)

# ensure the collection exists (no-op if it already does)
vector_store.create_collection_if_not_exists("my_collection")

# add documents to the collection (embeddings are auto-computed from `content`)
documents = [Document(id="doc1", content="the first doc", metadata={"key": "value1"}),
             Document(id="doc2", content="the second doc", metadata={"key": "value2"})]
vector_store.add(documents=documents, collection_name="my_collection")

# update documents in place — every document must already have its `id` set
vector_store.update(
    documents=[Document(id="doc1", content="rewritten first doc", metadata={"key": "value1"})],
    collection_name="my_collection",
)

# get documents by IDs
docs: List[Document] = vector_store.get(ids="doc2", collection_name="my_collection")
# get documents matching a metadata filter (limit defaults to 100; pass None for unbounded)
docs = vector_store.get(filters={"key": "value1"}, collection_name="my_collection")
# get all documents (bounded by `limit`, defaults to 100)
docs = vector_store.get(collection_name="my_collection")

# delete documents by IDs
vector_store.delete(ids=["doc1", "doc2"], collection_name="my_collection")
# delete documents matching a metadata filter
vector_store.delete(filters={"key": "value1"}, collection_name="my_collection")
# delete all documents
vector_store.delete(collection_name="my_collection")
```

{{< /tab >}}

{{< tab "Java" >}}

```java
// get the vector store from runner context
BaseVectorStore vectorStore =
        (BaseVectorStore) ctx.getResource("vectorStore", ResourceType.VECTOR_STORE);

// ensure the collection exists (no-op if it already does)
((CollectionManageableVectorStore) vectorStore)
        .createCollectionIfNotExists("my_collection", Map.of());

// add documents to the collection (embeddings are auto-computed from `content`)
List<Document> documents = List.of(
        new Document("the first doc.",  Map.of("key", "value1"), "doc1"),
        new Document("the second doc", Map.of("key", "value2"), "doc2"));
vectorStore.add(documents, "my_collection", Map.of());

// update documents in place — every document must already have its `id` set
vectorStore.update(
        List.of(new Document("rewritten first doc", Map.of("key", "value1"), "doc1")),
        "my_collection",
        Map.of());

// get documents by IDs (convenience overloads avoid passing nulls)
List<Document> docs = vectorStore.getByIds(List.of("doc1"), "my_collection");
// get documents matching a metadata filter
docs = vectorStore.getByFilters(Map.of("key", "value1"));
// full signature — pass `limit=null` for unbounded
docs = vectorStore.get(null, "my_collection", Map.of("key", "value1"), 100, Map.of());

// delete documents by IDs
vectorStore.deleteByIds(List.of("doc1", "doc2"), "my_collection");
// delete documents matching a metadata filter
vectorStore.deleteByFilters(Map.of("key", "value1"));
// delete all documents in a collection
vectorStore.delete(null, "my_collection", null, Map.of());
```

{{< /tab >}}

{{< /tabs >}}

#### Filter DSL

`query`, `get`, and `delete` all accept the same unified `filters` map. The dialect intentionally covers only the subset every backend supports — equality matching — so callers don't have to know each store's native operators.

```text
# Equality — "field equals value":
{"field": value}

# Multiple top-level keys are implicitly AND-ed:
{"user_id": "u1", "run_id": "r1"}
```

`None` / `null` means "no filter". Richer operators (ranges, set membership, OR, NOT, etc.) are out of scope here. Callers needing backend-specific operators should pass them through `extra_args` (Python `VectorStoreQuery.extra_args` or `**kwargs`) or `extraArgs` (Java) — for example, ChromaDB's native `where` dict. Implementations that receive an unsupported operator via `filters` raise `NotImplementedError` (Python) or `UnsupportedOperationException` (Java).

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
            clazz=ResourceName.EmbeddingModel.OPENAI_CONNECTION,
            api_key="your-api-key-here"
        )

    @embedding_model_setup
    @staticmethod
    def openai_embedding() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.EmbeddingModel.OPENAI_SETUP,
            connection="openai_connection",
            model="your-embedding-model-here"
        )

    # In-memory Chroma setup
    @vector_store
    @staticmethod
    def chroma_store() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.VectorStore.CHROMA_VECTOR_STORE,
            embedding_model="openai_embedding",
            collection="my_chroma_store"
        )

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def search_documents(event: Event, ctx: RunnerContext) -> None:
        # Get the vector store from the runtime context
        vector_store = ctx.get_resource("chroma_store", ResourceType.VECTOR_STORE)

        # Create a semantic search query
        input_event = InputEvent.from_event(event)
        user_query = str(input_event.input)
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
        return ResourceDescriptor.Builder.newBuilder(ResourceName.EmbeddingModel.OLLAMA_CONNECTION)
                .addInitialArgument("host", "http://localhost:11434")
                .build();
    }

    @EmbeddingModelSetup
    public static ResourceDescriptor embeddingModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.EmbeddingModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "embeddingConnection")
                .addInitialArgument("model", "nomic-embed-text")
                .build();
    }

    @VectorStore
    public static ResourceDescriptor vectorStore() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.VectorStore.ELASTICSEARCH_VECTOR_STORE)
                .addInitialArgument("embedding_model", "embeddingModel")
                .addInitialArgument("host", "http://localhost:9200")
                .addInitialArgument("index", "my_documents")
                .addInitialArgument("vector_field", "content_vector")
                .addInitialArgument("dims", 1536)
                .build();
    }

    @Action(listenEventTypes = {InputEvent.EVENT_TYPE})
    public static void searchDocuments(Event event, RunnerContext ctx) {
        InputEvent inputEvent = InputEvent.fromEvent(event);
        // Option 1: Manual search via the vector store
        VectorStore vectorStore = (VectorStore) ctx.getResource("vectorStore", ResourceType.VECTOR_STORE);
        String queryText = (String) inputEvent.getInput();
        VectorStoreQuery query = new VectorStoreQuery(queryText, 3);
        VectorStoreQueryResult result = vectorStore.query(query);

        // Option 2: Request context retrieval via built-in events
        ctx.sendEvent(new ContextRetrievalRequestEvent(queryText, "vectorStore"));
    }

    @Action(listenEventTypes = {ContextRetrievalResponseEvent.EVENT_TYPE})
    public static void onSearchResponse(Event event, RunnerContext ctx) {
        ContextRetrievalResponseEvent response = ContextRetrievalResponseEvent.fromEvent(event);
        List<Document> documents = response.getDocuments();
        // Process the retrieved documents...
    }
}
```

{{< /tab >}}

{{< /tabs >}}

## Built-in Providers

### Amazon OpenSearch

[Amazon OpenSearch](https://docs.aws.amazon.com/opensearch-service/latest/developerguide/) is a managed vector search service available in two flavors: OpenSearch Service (provisioned domains) and OpenSearch Serverless (AOSS). The Flink Agents integration supports both via a single `service_type` parameter, with IAM (SigV4) or basic authentication.

{{< hint info >}}
Amazon OpenSearch is only supported in Java currently. To use Amazon OpenSearch from Python agents, see [Using Cross-Language Providers](#using-cross-language-providers).
{{< /hint >}}

{{< hint info >}}
Amazon OpenSearch implements `CollectionManageableVectorStore`, enabling [Long-Term Memory]({{< ref "docs/development/memory/long_term_memory" >}}) support. Collections map to OpenSearch indices. OpenSearch indices do not natively support attaching arbitrary metadata, so any `metadata` passed to `createCollectionIfNotExists` is ignored. Callers needing per-document attributes should put them on the documents themselves.
{{< /hint >}}

#### Prerequisites

1. Either an [OpenSearch Service](https://docs.aws.amazon.com/opensearch-service/latest/developerguide/) provisioned domain with KNN enabled (version 2.x+), or an [OpenSearch Serverless](https://docs.aws.amazon.com/opensearch-service/latest/developerguide/serverless.html) collection of type `VECTORSEARCH`
2. For IAM auth: IAM credentials configured via the [AWS Default Credentials Provider](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html) with appropriate access policies (or a Serverless data-access policy)
3. For basic auth (Service domains only): username and password for the OpenSearch domain

#### OpenSearchVectorStore Parameters

{{< tabs "OpenSearchVectorStore Parameters" >}}

{{< tab "Java" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `embedding_model` | String | Required | Reference to embedding model resource name |
| `endpoint` | String | Required | OpenSearch endpoint URL (e.g. `https://my-domain.us-east-1.es.amazonaws.com` for a domain, or the `*.aoss.amazonaws.com` endpoint for Serverless) |
| `index` | String | Required | Default index name for document operations |
| `service_type` | String | `"serverless"` | OpenSearch flavor: `"serverless"` (AOSS) or `"domain"` (OpenSearch Service) |
| `auth` | String | `"iam"` | Authentication method: `"iam"` (SigV4) or `"basic"`. Basic auth is supported on Service domains only |
| `username` | String | None | Username for basic authentication (required if `auth=basic`) |
| `password` | String | None | Password for basic authentication (required if `auth=basic`) |
| `vector_field` | String | `"embedding"` | Name of the KNN vector field in the index |
| `content_field` | String | `"content"` | Name of the text content field in the index |
| `region` | String | `"us-east-1"` | AWS region |
| `dims` | int | `1024` | Vector dimensionality used when this integration creates an index |
| `max_bulk_mb` | int | `5` | Maximum bulk payload size in MB |

{{< /tab >}}

{{< /tabs >}}

#### Usage Example

{{< tabs "Amazon OpenSearch Usage Example" >}}

{{< tab "Java" >}}

For an OpenSearch Serverless (AOSS) collection with IAM auth (the default):

```java
public class MyAgent extends Agent {

    @EmbeddingModelConnection
    public static ResourceDescriptor bedrockEmbeddingConnection() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.EmbeddingModel.BEDROCK_CONNECTION)
                .addInitialArgument("region", "us-east-1")
                .build();
    }

    @EmbeddingModelSetup
    public static ResourceDescriptor bedrockEmbedding() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.EmbeddingModel.BEDROCK_SETUP)
                .addInitialArgument("connection", "bedrockEmbeddingConnection")
                .addInitialArgument("dimensions", 1024)
                .build();
    }

    @VectorStore
    public static ResourceDescriptor opensearchStore() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.VectorStore.OPENSEARCH_VECTOR_STORE)
                .addInitialArgument("embedding_model", "bedrockEmbedding")
                .addInitialArgument("endpoint", "https://abc123.us-east-1.aoss.amazonaws.com")
                .addInitialArgument("index", "my-vectors")
                // service_type defaults to "serverless"; auth defaults to "iam"
                .addInitialArgument("dims", 1024)
                .build();
    }

    ...
}
```

For an OpenSearch Service provisioned domain with IAM auth:

```java
@VectorStore
public static ResourceDescriptor opensearchDomainStore() {
    return ResourceDescriptor.Builder.newBuilder(ResourceName.VectorStore.OPENSEARCH_VECTOR_STORE)
            .addInitialArgument("embedding_model", "bedrockEmbedding")
            .addInitialArgument("endpoint", "https://my-domain.us-east-1.es.amazonaws.com")
            .addInitialArgument("index", "my-vectors")
            .addInitialArgument("service_type", "domain")
            .addInitialArgument("auth", "iam")
            .addInitialArgument("dims", 1024)
            .build();
}
```

For an OpenSearch Service domain with basic auth:

```java
@VectorStore
public static ResourceDescriptor opensearchDomainBasicAuth() {
    return ResourceDescriptor.Builder.newBuilder(ResourceName.VectorStore.OPENSEARCH_VECTOR_STORE)
            .addInitialArgument("embedding_model", "bedrockEmbedding")
            .addInitialArgument("endpoint", "https://my-domain.us-east-1.es.amazonaws.com")
            .addInitialArgument("index", "my-vectors")
            .addInitialArgument("service_type", "domain")
            .addInitialArgument("auth", "basic")
            .addInitialArgument("username", "admin")
            .addInitialArgument("password", "your-password")
            .addInitialArgument("dims", 1024)
            .build();
}
```

{{< /tab >}}

{{< /tabs >}}

### Amazon S3 Vectors

[Amazon S3 Vectors](https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors.html) is a purpose-built vector storage service from Amazon S3 that provides native support for storing and querying vector embeddings with sub-second query performance. It uses the S3 Vectors SDK for PutVectors, QueryVectors, GetVectors, and DeleteVectors operations.

{{< hint info >}}
Amazon S3 Vectors is only supported in Java currently. To use Amazon S3 Vectors from Python agents, see [Using Cross-Language Providers](#using-cross-language-providers).
{{< /hint >}}

{{< hint warning >}}
Amazon S3 Vectors does **not** implement `CollectionManageableVectorStore`, so it does not support [Long-Term Memory]({{< ref "docs/development/memory/long_term_memory" >}}) features. It also does not support `size()` or get-all operations: explicit document IDs are required for `get()` and `delete()`.
{{< /hint >}}

#### Prerequisites

1. An [S3 Vectors vector bucket](https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-buckets.html) and vector index created in your AWS account
2. IAM credentials configured via the [AWS Default Credentials Provider](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html) with appropriate S3 Vectors permissions

#### S3VectorsVectorStore Parameters

{{< tabs "S3VectorsVectorStore Parameters" >}}

{{< tab "Java" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `embedding_model` | String | Required | Reference to embedding model resource name |
| `vector_bucket` | String | Required | S3 Vectors bucket name |
| `vector_index` | String | Required | S3 Vectors index name within the bucket |
| `region` | String | `"us-east-1"` | AWS region |

{{< /tab >}}

{{< /tabs >}}

#### Usage Example

{{< tabs "Amazon S3 Vectors Usage Example" >}}

{{< tab "Java" >}}

```java
public class MyAgent extends Agent {

    @EmbeddingModelConnection
    public static ResourceDescriptor bedrockEmbeddingConnection() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.EmbeddingModel.BEDROCK_CONNECTION)
                .addInitialArgument("region", "us-east-1")
                .build();
    }

    @EmbeddingModelSetup
    public static ResourceDescriptor bedrockEmbedding() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.EmbeddingModel.BEDROCK_SETUP)
                .addInitialArgument("connection", "bedrockEmbeddingConnection")
                .addInitialArgument("dimensions", 1024)
                .build();
    }

    @VectorStore
    public static ResourceDescriptor s3VectorsStore() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.VectorStore.S3_VECTORS_VECTOR_STORE)
                .addInitialArgument("embedding_model", "bedrockEmbedding")
                .addInitialArgument("vector_bucket", "my-vector-bucket")
                .addInitialArgument("vector_index", "my-index")
                .addInitialArgument("region", "us-east-1")
                .build();
    }

    ...
}
```

{{< /tab >}}

{{< /tabs >}}

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
| `collection_metadata` | dict | `{}` | Metadata for the collection (applied only when the read / write paths auto-create it) |
| `auto_create_collection` | bool | `True` | Whether read / write paths auto-create the collection when it's missing |

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
            clazz=ResourceName.EmbeddingModel.OPENAI_CONNECTION,
            api_key="your-api-key-here"
        )

    @embedding_model_setup
    @staticmethod
    def openai_embedding() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.EmbeddingModel.OPENAI_SETUP,
            connection="openai_connection",
          model="your-embedding-model-here"
        )

    # Vector store setup
    @vector_store
    @staticmethod
    def chroma_store() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.VectorStore.CHROMA_VECTOR_STORE,
            embedding_model="openai_embedding",
            persist_directory="/path/to/chroma/data",  # For persistent storage
            collection="my_documents",
            auto_create_collection=True
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
        clazz=ResourceName.VectorStore.CHROMA_VECTOR_STORE,
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
        clazz=ResourceName.VectorStore.CHROMA_VECTOR_STORE,
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
        clazz=ResourceName.VectorStore.CHROMA_VECTOR_STORE,
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
        clazz=ResourceName.VectorStore.CHROMA_VECTOR_STORE,
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

#### ElasticsearchVectorStore Parameters

| Parameter         | Type | Default                   | Description                                                        |
|-------------------|------|---------------------------|--------------------------------------------------------------------|
| `embedding_model` | str  | Required                  | Reference to embedding model resource name                         |
| `index`           | str  | None                      | Default target Elasticsearch index name                            |
| `vector_field`    | str  | `"_vector"`               | Name of the dense vector field used for KNN                        |
| `dims`            | int  | `768`                     | Vector dimensionality                                              |
| `k`               | int  | None                      | Number of nearest neighbors to return; can be overridden per query |
| `num_candidates`  | int  | None                      | Candidate set size for ANN search; can be overridden per query     |
| `filter_query`    | str  | None                      | Raw JSON Elasticsearch filter query (DSL) applied as a post-filter |
| `host`            | str  | `"http://localhost:9200"` | Elasticsearch endpoint                                             |
| `hosts`           | str  | None                      | Comma-separated list of Elasticsearch endpoints                    |
| `username`        | str  | None                      | Username for basic authentication                                  |
| `password`        | str  | None                      | Password for basic authentication                                  |
| `api_key_base64`  | str  | None                      | Base64-encoded API key for authentication                          |
| `api_key_id`      | str  | None                      | API key ID for authentication                                      |
| `api_key_secret`  | str  | None                      | API key secret for authentication                                  |

{{< hint warning >}}
For an index not created by flink-agents, the index must already contain a `dense_vector` field, and the user must specify its name via `vector_field`.
{{< /hint >}}
#### Usage Example

{{< tabs "Elasticsearch Usage Example" >}}

{{< tab "Java" >}}

Here's how to define an Elasticsearch vector store in your Java agent:

```java
@VectorStore
public static ResourceDescriptor vectorStore() {
    return ResourceDescriptor.Builder.newBuilder(ResourceName.VectorStore.ELASTICSEARCH_VECTOR_STORE)
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

### Mem0

[Mem0](https://docs.mem0.ai/) ships its own ecosystem of vector-store backends (pgvector, Milvus, Qdrant, Redis, Weaviate, ...). `Mem0VectorStore` is a gateway that exposes any of them through Flink Agents' resource system, so you can reach Mem0-supported backends without a dedicated integration for each.

{{< hint info >}}
Mem0 is currently supported in the Python API only. To use it from Java agents, see [Using Cross-Language Providers](#using-cross-language-providers).
{{< /hint >}}

{{< hint info >}}
`Mem0VectorStore` implements `CollectionManageableVectorStore`, enabling [Long-Term Memory]({{< ref "docs/development/memory/long_term_memory" >}}) support. Filters use the unified equality-only [Filter DSL](#filter-dsl) and are forwarded to the underlying Mem0 backend unchanged.
{{< /hint >}}

#### Prerequisites

1. Install Mem0: `pip install mem0ai`
2. Any extra dependency required by the chosen backend (e.g. `pip install qdrant-client` for Qdrant, `pip install pymilvus` for Milvus). See the [Mem0 vector store docs](https://docs.mem0.ai/components/vectordbs/overview) for per-provider requirements.

#### Mem0VectorStore Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `embedding_model` | str | Required | Reference to embedding model resource name |
| `provider` | str | Required | Mem0 vector store provider name (e.g. `"chroma"`, `"qdrant"`, `"pgvector"`, `"milvus"`) |
| `provider_config` | dict | `{}` | Provider-specific config dict passed to Mem0's `VectorStoreFactory` (e.g. host, port, credentials). `collection_name` is injected automatically and need not be set |
| `collection` | str | `"flink_agents_mem0_vs"` | Default collection used when a caller does not specify one |

#### Usage Example

{{< tabs "Mem0 Usage Example" >}}

{{< tab "Python" >}}

```python
class MyAgent(Agent):

    # Embedding model setup (required for vector store)
    @embedding_model_connection
    @staticmethod
    def openai_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.EmbeddingModel.OPENAI_CONNECTION,
            api_key="your-api-key-here"
        )

    @embedding_model_setup
    @staticmethod
    def openai_embedding() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.EmbeddingModel.OPENAI_SETUP,
            connection="openai_connection",
            model="your-embedding-model-here"
        )

    # Mem0 vector store backed by Qdrant
    @vector_store
    @staticmethod
    def mem0_store() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.VectorStore.MEM0_VECTOR_STORE,
            embedding_model="openai_embedding",
            provider="qdrant",
            provider_config={"host": "localhost", "port": 6333, "embedding_model_dims": 1536},
            collection="my_documents"
        )

    ...
```

{{< /tab >}}

{{< /tabs >}}

### Milvus

[Milvus](https://milvus.io/) is an open-source vector database designed for high-dimensional vector search at scale.

{{< hint info >}}
Milvus is currently supported in the Java API only. To use Milvus from Python agents, see [Using Cross-Language Providers](#using-cross-language-providers).
{{< /hint >}}

#### Prerequisites

1. A Milvus server.

#### MilvusVectorStore Parameters

| Parameter                   | Type | Default                              | Description                                                                 |
|-----------------------------|------|--------------------------------------|-----------------------------------------------------------------------------|
| `embedding_model`           | str  | Required                             | Reference to embedding model resource name                                  |
| `collection`                | str  | `"flink_agents_milvus_collection"`   | Default target Milvus collection name                                       |
| `collection_name`           | str  | None                                 | Alias for `collection`                                                       |
| `index`                     | str  | None                                 | Alias for `collection`, mainly for cross-provider compatibility              |
| `id_field`                  | str  | `"id"`                               | Name of the primary key field                                                |
| `content_field`             | str  | `"content"`                          | Name of the field storing document content                                   |
| `metadata_field`            | str  | `"metadata"`                         | Name of the JSON field storing document metadata                             |
| `vector_field`              | str  | `"embedding"`                        | Name of the FloatVector field used for vector search                         |
| `dims`                      | int  | `768`                                | Vector dimensionality                                                        |
| `id_max_length`             | int  | `65535`                              | Maximum length for the VarChar primary key field                             |
| `content_max_length`        | int  | `65535`                              | Maximum length for the VarChar content field                                 |
| `metric_type`               | str  | `"COSINE"`                           | Milvus metric type used by vector search                                     |
| `index_type`                | str  | `"AUTOINDEX"`                        | Milvus vector index type                                                     |
| `index_params`              | map  | `{}`                                 | Extra vector index parameters passed to Milvus                               |
| `metadata_index_keys`       | list | `user_id`, `agent_id`, `run_id`, `actor_id`, `category` | Additional metadata JSON keys indexed with path indexes |
| `metadata_index_cast_types` | map  | Default keys use `"VARCHAR"`         | Per-metadata-key JSON path index cast type overrides                         |
| `num_shards`                | int  | `1`                                  | Number of Milvus shards for newly created collections                        |
| `consistency_level`         | str  | `"BOUNDED"`                          | Milvus consistency level for collection creation, query, and search          |
| `max_get_limit`             | int  | `10000`                              | Maximum number of documents returned by `get` when no limit is specified     |
| `load_timeout_ms`           | long | `120000`                             | Timeout for loading collections                                              |
| `uri`                       | str  | `"http://localhost:19530"`           | Milvus endpoint                                                              |
| `host`                      | str  | `"localhost"`                        | Milvus host used when `uri` is not set                                       |
| `port`                      | int  | `19530`                              | Milvus port used when `uri` is not set                                       |
| `db_name`                   | str  | None                                 | Milvus database name                                                         |
| `token`                     | str  | None                                 | Token for Milvus authentication                                              |
| `username`                  | str  | None                                 | Username for basic authentication                                            |
| `password`                  | str  | None                                 | Password for basic authentication                                            |
| `enable_precheck`           | bool | `false`                              | Whether to enable Milvus client precheck                                     |

{{< hint info >}}
When creating a collection, MilvusVectorStore creates a primary-key field, content field, JSON metadata field, vector field, vector index, and JSON metadata indexes. The default metadata JSON path indexes cover common filter keys such as `user_id`, `agent_id`, `run_id`, `actor_id`, and `category`; add `metadata_index_keys` for application-specific filter keys.

The default shard count is `1`. As a rough capacity-planning rule, use about one shard per 100 million vectors, and increase it for heavier write throughput.
{{< /hint >}}

#### Usage Example

{{< tabs "Milvus Usage Example" >}}

{{< tab "Java" >}}

```java
@VectorStore
public static ResourceDescriptor vectorStore() {
    return ResourceDescriptor.Builder.newBuilder(ResourceName.VectorStore.MILVUS_VECTOR_STORE)
            .addInitialArgument("embedding_model", "embeddingModel")
            .addInitialArgument("uri", "http://localhost:19530")
            .addInitialArgument("collection", "my_documents")
            .addInitialArgument("dims", 1536)
            .addInitialArgument("metric_type", "COSINE")
            .addInitialArgument("index_type", "AUTOINDEX")
            // Optional metadata JSON path indexes
            // .addInitialArgument("metadata_index_keys", List.of("user_id", "agent_id", "run_id"))
            .build();
}
```

{{< /tab >}}

{{< /tabs >}}

## Using Cross-Language Providers

Flink Agents supports cross-language vector store integration, allowing you to use vector stores implemented in one language (Java or Python) from agents written in the other language. This is particularly useful when a vector store provider is only available in one language (e.g., Elasticsearch and Milvus are currently Java-only, Chroma is currently Python-only).

{{< hint warning >}}
**Limitations:**
- Cross-language resources are currently supported only when [running in Flink]({{< ref "docs/operations/deployment#run-in-flink" >}}), not in local development mode
- Complex object serialization between languages may have limitations
{{< /hint >}}

### How To Use

To leverage vector store supports provided in a different language, you need to declare the resource within a built-in cross-language wrapper, and specify the target provider as an argument:

- **Using Java vector stores in Python**: Use `ResourceName.VectorStore.JAVA_WRAPPER_COLLECTION_MANAGEABLE_VECTOR_STORE`, specifying the Java provider class via the `java_clazz` parameter
- **Using Python vector stores in Java**: Use `ResourceName.VectorStore.PYTHON_WRAPPER_COLLECTION_MANAGEABLE_VECTOR_STORE`, specifying the Python provider via the `pythonClazz` parameter

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
        #     .newBuilder(ResourceName.VectorStore.ELASTICSEARCH_VECTOR_STORE)
        #     .addInitialArgument("embedding_model", "my_embedding_model")
        #     .addInitialArgument("host", "http://localhost:9200")
        #     .addInitialArgument("index", "my_documents")
        #     .addInitialArgument("dims", 768)
        #     .build();
        return ResourceDescriptor(
            clazz=ResourceName.VectorStore.JAVA_WRAPPER_COLLECTION_MANAGEABLE_VECTOR_STORE,
            java_clazz=ResourceName.VectorStore.Java.ELASTICSEARCH_VECTOR_STORE,
            embedding_model="my_embedding_model",
            host="http://localhost:9200",
            index="my_documents",
            dims=768
        )

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        # Use Java vector store from Python
        input_event = InputEvent.from_event(event)
        vector_store = ctx.get_resource("java_vector_store", ResourceType.VECTOR_STORE)
        
        # Perform semantic search
        query = VectorStoreQuery(query_text=str(input_event.input), limit=3)
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
        //     clazz=ResourceName.VectorStore.CHROMA_VECTOR_STORE,
        //     embedding_model="my_embedding_model",
        // )
        return ResourceDescriptor.Builder.newBuilder(ResourceName.VectorStore.PYTHON_WRAPPER_COLLECTION_MANAGEABLE_VECTOR_STORE)
                .addInitialArgument("pythonClazz", ResourceName.VectorStore.Python.CHROMA_VECTOR_STORE)
                .addInitialArgument("embedding_model", "myEmbeddingModel")
                .build();
    }

    @Action(listenEventTypes = {InputEvent.EVENT_TYPE})
    public static void processInput(Event event, RunnerContext ctx) throws Exception {
        InputEvent inputEvent = InputEvent.fromEvent(event);
        // Use Python vector store from Java
        VectorStore vectorStore = 
            (VectorStore) ctx.getResource("pythonVectorStore", ResourceType.VECTOR_STORE);
        
        // Perform semantic search
        VectorStoreQuery query = new VectorStoreQuery((String) inputEvent.getInput(), 3);
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

If you want to use vector stores not offered by the built-in providers, you can extend the base vector store class and implement your own! The vector store system is built around the `BaseVectorStore` abstract class and `CollectionManageableVectorStore` interface.

### BaseVectorStore

The base class handles text-to-vector conversion and provides the high-level `add`, `update`, and `query` interfaces. You only need to implement the public document-level reads (`get` / `delete`) and the protected pre-computed-embedding hooks (`_query_embedding` / `_add_embedding` / `_update_embedding` in Python; `queryEmbedding` / `addEmbedding` / `updateEmbedding` in Java).

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

    @override
    def get(
        self,
        ids: str | List[str] | None = None,
        collection_name: str | None = None,
        filters: Dict[str, Any] | None = None,
        limit: int | None = 100,
        **kwargs: Any,
    ) -> List[Document]:
        """Retrieve documents from the vector store.

        When ``ids`` is provided, the ``ids`` list itself bounds the result size
        and ``limit`` is effectively ignored. Without ``ids``, up to ``limit``
        documents matching ``filters`` (or all, when no filter is set) are
        returned. ``limit=None`` means unbounded.

        Args:
            ids: Unique identifier(s) of the documents to retrieve.
            collection_name: Target collection. If not provided, use the default collection.
            filters: Metadata filter in the unified DSL (equality only); ``None`` = no filter.
            limit: Maximum number of documents to return. Defaults to 100; pass ``None`` for unbounded.
            **kwargs: Vector store-specific parameters (offset, etc.).
        """
        documents: List[Document] = ...
        return documents

    @override
    def delete(
        self,
        ids: str | List[str] | None = None,
        collection_name: str | None = None,
        filters: Dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> None:
        """Delete documents in the vector store.

        Args:
            ids: Unique identifier(s) of the documents to delete. If neither ``ids``
                 nor ``filters`` is provided, all documents in the collection are deleted.
            collection_name: Target collection. If not provided, use the default collection.
            filters: Metadata filter in the unified DSL (equality only); ``None`` = no filter.
            **kwargs: Vector store-specific parameters.
        """
        # delete the documents
        pass

    @override
    def _query_embedding(
        self,
        embedding: List[float],
        limit: int = 10,
        collection_name: str | None = None,
        filters: Dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> List[Document]:
        """Perform vector search using a pre-computed embedding.

        Args:
            embedding: Pre-computed embedding vector for semantic search.
            limit: Maximum number of results to return (default: 10).
            collection_name: Target collection. If not provided, use the default collection.
            filters: Metadata filter in the unified DSL (equality only); ``None`` = no filter.
            **kwargs: Vector store-specific parameters (distance metrics, etc.).
        """
        documents: List[Document] = ...
        return documents

    @override
    def _add_embedding(
        self,
        *,
        documents: List[Document],
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> List[str]:
        """Add documents with pre-computed embeddings to the vector store.

        Args:
            documents: Documents (with ``embedding`` populated) to add.
            collection_name: Target collection. If not provided, use the default collection.
            **kwargs: Vector store-specific parameters.

        Returns:
            List of document IDs that were added.
        """
        # add the documents
        ids: List[str] = ...
        return ids

    @override
    def _update_embedding(
        self,
        *,
        documents: List[Document],
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> None:
        """Update documents with pre-computed embeddings. Identity is read from ``Document.id``.

        Args:
            documents: Documents carrying ``id`` plus the new content / metadata / embedding.
            collection_name: Target collection. If not provided, use the default collection.
            **kwargs: Vector store-specific parameters.
        """
        # update the documents
        pass
```

{{< /tab >}}

{{< tab "Java" >}}

```java
public class MyVectorStore extends BaseVectorStore {

    public MyVectorStore(
            ResourceDescriptor descriptor,
            ResourceContext resourceContext) {
        super(descriptor, resourceContext);
    }

    @Override
    public Map<String, Object> getStoreKwargs() {
        // Return vector store-specific configuration
        // These parameters are merged with query-specific parameters
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("index", "my_index");
        return kwargs;
    }

    /**
     * Retrieve documents from the vector store.
     *
     * <p>When {@code ids} is provided, the {@code ids} list itself bounds the result size
     * and {@code limit} is effectively ignored. Without {@code ids}, up to {@code limit}
     * documents matching {@code filters} (or all, when no filter is set) are returned.
     *
     * @param ids        The ids of the documents. If null, retrieve documents matching {@code filters}.
     * @param collection Target collection. If null, retrieve from the default collection.
     * @param filters    Metadata filter in the unified DSL (equality only); {@code null} = no filter.
     * @param limit      Maximum number of documents to return. Defaults to 100; pass {@code null} for unbounded.
     * @param extraArgs  Additional arguments.
     */
    @Override
    public List<Document> get(
            @Nullable List<String> ids,
            @Nullable String collection,
            @Nullable Map<String, Object> filters,
            @Nullable Integer limit,
            Map<String, Object> extraArgs)
            throws IOException {
        List<Document> documents = ...;
        return documents;
    }

    /**
     * Delete documents in the vector store.
     *
     * @param ids        The ids of the documents. If null, delete documents matching {@code filters}.
     * @param collection Target collection. If null, use the default collection.
     * @param filters    Metadata filter in the unified DSL (equality only); {@code null} = no filter.
     * @param extraArgs  Additional arguments.
     */
    @Override
    public void delete(
            @Nullable List<String> ids,
            @Nullable String collection,
            @Nullable Map<String, Object> filters,
            Map<String, Object> extraArgs)
            throws IOException {
        // delete the documents
    }

    /**
     * Performs vector search using a pre-computed embedding.
     *
     * @param embedding  The embedding vector to search with.
     * @param limit      Maximum number of results to return.
     * @param collection Target collection. If null, query the default collection.
     * @param filters    Metadata filter in the unified DSL (equality only); {@code null} = no filter.
     * @param args       Additional arguments for the vector search.
     */
    @Override
    public List<Document> queryEmbedding(
            float[] embedding,
            int limit,
            @Nullable String collection,
            @Nullable Map<String, Object> filters,
            Map<String, Object> args) {
        List<Document> documents = ...;
        return documents;
    }

    /**
     * Add documents with pre-computed embeddings to the vector store.
     *
     * @param documents  Documents (with embeddings populated) to add.
     * @param collection Target collection. If null, add to the default collection.
     * @param extraArgs  Additional arguments.
     * @return IDs of the added documents.
     */
    @Override
    public List<String> addEmbedding(
            List<Document> documents, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException {
        // add the documents
        List<String> ids = ...;
        return ids;
    }

    /**
     * Update documents with pre-computed embeddings. Identity is read from {@link Document#getId()}.
     *
     * @param documents  Documents carrying id plus the new content / metadata / embedding.
     * @param collection Target collection. If null, use the default collection.
     * @param extraArgs  Additional arguments.
     */
    @Override
    public void updateEmbedding(
            List<Document> documents, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException {
        // update the documents
    }
}
```

{{< /tab >}}

{{< /tabs >}}

### CollectionManageableVectorStore

For vector stores that support collection-level management, additionally implement the following methods:

{{< tabs "Custom Vector Store support Collection" >}}

{{< tab "Python" >}}

```python
class MyVectorStore(CollectionManageableVectorStore):
    # Add your custom configuration fields here

    # implementation for `BaseVectorStore` methods (see above).

    @override
    def create_collection_if_not_exists(self, name: str, **kwargs: Any) -> None:
        """Create the collection if it doesn't already exist; no-op otherwise.

        Args:
            name: Name of the collection.
            **kwargs: Backend-specific options applied only when the collection
                is created (e.g. Chroma's ``metadata`` dict, Pinecone's
                ``dimension`` / ``metric``). Document which keys are recognized;
                unknown keys should be ignored.
        """
        # create the collection if missing
        pass

    @override
    def delete_collection(self, name: str) -> None:
        """Delete a collection.

        Args:
            name: Name of the collection.
        """
        # delete the collection
        pass
```

{{< /tab >}}

{{< tab "Java" >}}

```java
public class MyVectorStore extends BaseVectorStore
        implements CollectionManageableVectorStore {
    // Add your custom configuration fields here

    // implementation for `BaseVectorStore` methods (see above).

    /**
     * Create the collection if it doesn't already exist; no-op otherwise.
     *
     * @param name   The name of the collection.
     * @param kwargs Backend-specific options applied only when the collection is created.
     *               Document which keys are recognized; unknown keys should be ignored.
     */
    @Override
    public void createCollectionIfNotExists(String name, Map<String, Object> kwargs) throws Exception {
        // create the collection if missing
    }

    /**
     * Delete a collection by name.
     *
     * @param name The name of the collection to delete.
     */
    @Override
    public void deleteCollection(String name) throws Exception {
        // delete the collection
    }
}
```

{{< /tab >}}

{{< /tabs >}}

## Built-in Events and Actions

The built-in `context_retrieval_action` listens to `ContextRetrievalRequestEvent`. To retrieve relevant documents, send a `ContextRetrievalRequestEvent`. The action queries the configured vector store through durable execution and sends a `ContextRetrievalResponseEvent`.