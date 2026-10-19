---
title: "Vector Stores"
weight: 2
type: docs
aliases:
  - /docs/development/vector_stores/
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

For semantic search, register a `VECTOR_STORE` Resource and the embedding model it
uses, then query the store with structured queries. The store's `embedding_model`
setting refers to the embedding model setup's registered Resource name. See
[Registering Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}})
for registration methods and scopes.

### Declare a vector store in Agent

The following example uses the Declaration API with `@vector_store` in Python or
`@VectorStore` in Java. It assumes the referenced embedding model is already registered.

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

    @action(EventType.InputEvent)
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

    @Action(EventType.InputEvent)
    public static void searchDocuments(Event event, RunnerContext ctx) {
        InputEvent inputEvent = InputEvent.fromEvent(event);
        // Option 1: Manual search via the vector store
        BaseVectorStore vectorStore = (BaseVectorStore) ctx.getResource("vectorStore", ResourceType.VECTOR_STORE);
        String queryText = (String) inputEvent.getInput();
        VectorStoreQuery query = new VectorStoreQuery(queryText, 3);
        VectorStoreQueryResult result = vectorStore.query(query);

        // Option 2: Request context retrieval via built-in events
        ctx.sendEvent(new ContextRetrievalRequestEvent(queryText, "vectorStore"));
    }

    @Action(EventType.ContextRetrievalResponseEvent)
    public static void onSearchResponse(Event event, RunnerContext ctx) {
        ContextRetrievalResponseEvent response = ContextRetrievalResponseEvent.fromEvent(event);
        List<Document> documents = response.getDocuments();
        // Process the retrieved documents...
    }
}
```

{{< /tab >}}

{{< /tabs >}}

## Using Cross-Language Providers

For descriptor selection, registration, and runtime requirements, see
[Cross-Language Resources]({{< ref "docs/development/create-agent/resources#cross-language-resources" >}}).
[Built-in Providers]({{< ref "docs/development/vector-search/vector_stores/providers" >}}) lists available implementations and their configuration.

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

[Built-in Vector Store Providers]({{< ref "docs/development/vector-search/vector_stores/providers" >}}).
