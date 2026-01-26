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

### Use Case
In Flink Agents, vector stores are essential for:
- **Document Retrieval**: Finding relevant documents based on semantic similarity
- **Knowledge Base Search**: Querying large collections of information using natural language
- **Retrieval-Augmented Generation (RAG)**: Providing context to language models from vector-indexed knowledge
- **Semantic Similarity**: Comparing and ranking documents by meaning rather than keywords

### Concepts
* **Document**: Document is the abstraction that represents a piece of text and associated metadata.
* **Collection**: Collection is the abstraction that represents a set of documents. It corresponds to different concept for different vector store specification, like index in Elasticsearch and collection in Chroma.

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

#### Query Results

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

### Manage collections

User can dynamic create, get or delete collections in agent execution:
* `get_or_create_collection`: Get a collection by name, create if not exists. User can provide additional metadatas.
* `get_collection`: Get a collection by name. The collection must be created by flink-agents before.
* `delete_collection`: Delete a collection by name.

{{< hint info >}}
Collection level operations is only supported for vector store that implements `CollectionManageableVectorStore`. Currently, Chroma and Elasticsearch.
{{< /hint >}}

{{< tabs "Collection level operations" >}}

{{< tab "Python" >}}

```python
# get the vector store from runner context
vector_store: CollectionManageableVectorStore = ctx.get_resource("vector_store", ResourceType.VECTOR_STORE)

# create a collection
collection: Collection = vector_store.get_or_create_collection("my_collection" , metadata={"key1": "value1", "key2": "value2"})
# get the collection
collection: Collection = vector_store.get_collection("my_collection")
# get the collection metadata
metadata = collection.metadata

# delete the collection
vector_store.delete_collection("my_collection)
```

{{< /tab >}}

{{< tab "Java" >}}

```java
// get the vector store from runner context
CollectionManageableVectorStore vectorStore =
        (CollectionManageableVectorStore)
                ctx.getResource("vector_store", ResourceType.VECTOR_STORE);

// create a collection
Collection collection = vectorStore.getOrCreateCollection(
        "my_collection", Map.of("key1", "value1", "key2", "value2"));
// get the collection
collection = vectorStore.getCollection("my_collection");
// get the collection metadata
Map<String, Object> metadata = collection.getMetadata();

// delete the collection
vectorStore.deleteCollection("my_collection");
```

{{< /tab >}}

{{< /tabs >}}


### Manage documents
User can dynamic add, get or delete documents in agent execution:
* `add`: Add documents to a collection. If document ID is not specified, will generate random ID for each document.
* `get`: Get documents from a collection by IDs. If no IDs are provided, get all documents.
* `delete`: Delete documents from a collection by IDs. If no IDs are provided, delete all documents.

{{< hint info >}}
If collection name is not specified, the document level operations will apply to the default collection configured by vector store initialization parameters.
{{< /hint >}}

{{< tabs "Document level operations" >}}

{{< tab "Python" >}}

```python
# get the vector store from runner context
store: CollectionManageableVectorStore = ctx.get_resource("vector_store", ResourceType.VECTOR_STORE)

# create or get a collection
collection: Collection = vector_store.get_or_create_collection("my_collection" , metadata={"key1": "value1", "key2": "value2"})

# add documents to the collection
documents = [Document(id="doc1", content="the first doc", metadata={"key": "value1"}), 
             Document(id="doc2", content="the second doc", metadata={"key": "value2"})]
vector_store.add(documents=documents, collection_name="my_collection")

# get documents by IDs
doc: List[Document] = vector_store.get(ids="doc2", collectioin_name="my_collection")
# get all documents
doc: List[Document] = vector_store.get(collectioin_name="my_collection")

# delete documents by IDs
vector_store.delete(ids=["doc1", "doc2"], collection_name="my_collection")
# delete all documents
vector_store.delete(collection_name="my_collection")
```

{{< /tab >}}

{{< tab "Java" >}}

```java
// get the vector store from runner context
BaseVectorStore vectorStore =
                (BaseVectorStore)
                        ctx.getResource("vectorStore", ResourceType.VECTOR_STORE);
// create or get a collection
Collection collection = ((CollectionManageableVectorStore) vectorStore)
        .getOrCreateCollection("my_collection", Map.of("key1", "value1", "key2", "value2"));

// add documents to the collection
List<Document> documents = List.of(
                new Document(
                        "the first doc.",
                        Map.of("key", "value1"),
                        "doc1"),
                new Document(
                        "the second doc",
                        Map.of("key", "value2"),
                        "doc2"));
vectorStore.add(documents, "my_collection", Collections.emptyMap());

// get documents by IDs
List<Document> docs = vectorStore.get(List.of("doc1"), "my_collection", Collections.emptyMap());
// get all documents
docs = vectorStore.get(null, "my_collection", Collections.emptyMap());

// delete documents by IDs
vectorStore.delete(List.of("doc1", "doc2"), "my_collection", Collections.emptyMap());
// delete all documents
vectorStore.delete(null, "my_collection", Collections.emptyMap());
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
For index not create by flink-agents, the index must have a `dense_tensor` field, and user must specify the filed name by `vector_field`.

And, the index can't be accessed by collection level operations due to Elasticsearch does not support store index metadata natively.
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

## Using Cross-Language Providers

Flink Agents supports cross-language vector store integration, allowing you to use vector stores implemented in one language (Java or Python) from agents written in the other language. This is particularly useful when a vector store provider is only available in one language (e.g., Elasticsearch is currently Java-only, Chroma is currently Python-only).

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
            clazz=ResourceName.VectorStore.Java_WRAPPER_COLLECTION_MANAGEABLE_VECTOR_STORE,
            java_clazz=ResourceName.VectorStore.Java.ELASTICSEARCH_VECTOR_STORE,
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
        //     clazz=ResourceName.VectorStore.CHROMA_VECTOR_STORE,
        //     embedding_model="my_embedding_model",
        // )
        return ResourceDescriptor.Builder.newBuilder(ResourceName.VectorStore.PYTHON_WRAPPER_COLLECTION_MANAGEABLE_VECTOR_STORE)
                .addInitialArgument("pythonClazz", ResourceName.VectorStore.Python.CHROMA_VECTOR_STORE)
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

If you want to use vector stores not offered by the built-in providers, you can extend the base vector store class and implement your own! The vector store system is built around the `BaseVectorStore` abstract class and `CollectionManageableVectorStore` interface.

### BaseVectorStore

The base class handles text-to-vector conversion and provides the high-level add and query interface. You only need to implement the core search functionality and other basic document level operations. 

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
    def size(self, collection_name: str | None = None) -> int:
        """Get the size of the collection in vector store.

        Args:
            collection_name: The target collection. If not provided, use defualt collection.
        """
        size = ...
        return size

    @override
    def get(
        self,
        ids: str | List[str] | None = None,
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> List[Document]:
        """Retrieve documents from the vector store by its ID.

        Args:
            ids: Unique identifier of the documents to retrieve. If not provided, get all documents.
            collection_name: The collection name of the documents to retrieve.
                             If not provided, use defualt collection.
            **kwargs: Vector store specific parameters (offset, limit, filter etc.)

        Returns:
            Document object if found, None otherwise
        """
        documents: List[Document] = ...
        return documents

    @override
    def delete(
        self,
        ids: str | List[str] | None = None,
        collection_name: str | None = None,
        **kwargs: Any,
    ) -> None:
        """Delete documents in the vector store by its IDs.

        Args:
            ids: Unique identifier of the documents to delete. If not provided, delete all documents.
            collection_name: The collection name of the documents belong to. 
                             If not provided, use defualt collection.
            **kwargs: Vector store specific parameters (filter etc.)
        """
        # delete the documents 
        pass

    @override
    def query_embedding(self, embedding: List[float], limit: int = 10, **kwargs: Any) -> List[Document]:
        """Perform vector search using pre-computed embedding.

        Args:
            embedding: Pre-computed embedding vector for semantic search
            limit: Maximum number of results to return (default: 10)
            collection_name: The collection to apply the query.
                             If not provided, use default collection.
            **kwargs: Vector store-specific parameters (filters, distance metrics, etc.)

        Returns:
            List of documents matching the search criteria
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
            documents: Documents with embeddings to add to the vector store
            collection_name: The collection name of the documents to add.
                             If not provided, use default collection.
            **kwargs: Vector store-specific parameters (collection, namespace, etc.)

        Returns:
            List of document IDs that were added to the vector store
        """
        # add the documents
        ids: List[str] = ...
        return ids
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
    
    /**
     * Get the size of the collection in vector store.
     *
     * @param collection The name of the collection to count. If is null, count the default
     *     collection.
     * @return The documents count in the collection.
     */
    @Override
    public long size(@Nullable String collection) throws Exception {
        size = ...;
        return size;
    }

    /**
     * Retrieve documents from the vector store.
     *
     * @param ids The ids of the documents. If is null, get all the documents or first n documents
     *     according to implementation specific limit.
     * @param collection The name of the collection to be retrieved. If is null, retrieve the
     *     default collection.
     * @param extraArgs Additional arguments.
     * @return List of documents retrieved.
     */
    @Override
    public List<Document> get(
            @Nullable List<String> ids, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException {
        List<Document> documents = ...;
        return documents;
    }

    /**
     * Delete documents in the vector store.
     *
     * @param ids The ids of the documents. If is null, delete all the documents.
     * @param collection The name of the collection the documents belong to. If is null, use the
     *     default collection.
     * @param extraArgs Additional arguments.
     */
    @Override
    public void delete(
            @Nullable List<String> ids, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException {
        // delete the documents
    }

    /**
     * Performs vector search using a pre-computed embedding.
     *
     * @param embedding The embedding vector to search with
     * @param limit Maximum number of results to return
     * @param collection The collection to query to. If is null, query the default collection.
     * @param args Additional arguments for the vector search
     * @return List of documents matching the query embedding
     */
    @Override
    protected List<Document> queryEmbedding(
            float[] embedding, int limit, @Nullable String collection, Map<String, Object> args) {
        List<Document> documents = ...;
        return documents;
    }

    /**
     * Add documents with pre-computed embedding to vector store.
     *
     * @param documents The documents to be added.
     * @param collection The name of the collection to add to. If is null, add to the default
     *     collection.
     * @param extraArgs Additional arguments.
     * @return IDs of the added documents.
     */
    @Override
    protected List<String> addEmbedding(
            List<Document> documents, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException {
        // add the documents
        List<String> ids = ...;
        return ids;
    }
}
```

{{< /tab >}}

{{< /tabs >}}

### CollectionManageableVectorStore

For vector store which support collection level operations, user can implement follow methods additionally.

{{< tabs "Custom Vector Store support Collection" >}}

{{< tab "Python" >}}

```python
class MyVectorStore(CollectionManageableVectorStore):
    # Add your custom configuration fields here

    # implementation for `BaseVectoStore` method.
    
    @override
    def get_or_create_collection(
        self, name: str, metadata: Dict[str, Any] | None = None
    ) -> Collection:
        """Get a collection, or create it if it doesn't exist.

        Args:
            name: Name of the collection
            metadata: Metadata of the collection
        Returns:
            The retrieved or created collection
        """
        collection: Collection = ...
        return collection

    @override
    def get_collection(self, name: str) -> Collection:
        """Get a collection, raise an exception if it doesn't exist.

        Args:
            name: Name of the collection
        Returns:
            The retrieved collection
        """
        collection: Collection = ...
        return collection

    @override
    def delete_collection(self, name: str) -> Collection:
        """Delete a collection.

        Args:
            name: Name of the collection
        Returns:
            The deleted collection
        """
        collection: Collection = ...
        return collection
```

{{< /tab >}}

{{< tab "Java" >}}

```java
public class MyVectorStore extends BaseVectorStore
        implements CollectionManageableVectorStore{
    // Add your custom configuration fields here

    // implementation for `BaseVectoStore` method.
    
    /**
     * Get a collection, or create it if it doesn't exist.
     *
     * @param name The name of the collection to get or create.
     * @param metadata The metadata of the collection.
     * @return The retrieved or created collection.
     */
    @override
    public Collection getOrCreateCollection(String name, Map<String, Object> metadata) throws Exception {
        Collection collection = ...;
        return collection;
    }

    /**
     * Get a collection by name.
     *
     * @param name The name of the collection to get.
     * @return The retrieved collection.
     */
    @override
    public Collection getCollection(String name) throws Exception {
        Collection collection = ...;
        return collection;
    }

    /**
     * Delete a collection by name.
     *
     * @param name The name of the collection to delete.
     * @return The deleted collection.
     */
    @override
    public Collection deleteCollection(String name) throws Exception {
        Collection collection = ...;
        return collection;
    }
}
```

{{< /tab >}}

{{< /tabs >}}