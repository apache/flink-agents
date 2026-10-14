---
title: "Built-in Vector Store Providers"
weight: 1
type: docs
aliases:
  - /docs/development/vector-search/vector_stores_providers/
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

## Built-in Providers

### Amazon OpenSearch

[Amazon OpenSearch](https://docs.aws.amazon.com/opensearch-service/latest/developerguide/) is a managed vector search service available in two flavors: OpenSearch Service (provisioned domains) and OpenSearch Serverless (AOSS). The Flink Agents integration supports both via a single `service_type` parameter, with IAM (SigV4) or basic authentication.

{{< hint info >}}
Amazon OpenSearch is only supported in Java currently. To use Amazon OpenSearch from Python agents, see [Using Cross-Language Providers]({{< ref "docs/development/vector-search/vector_stores#using-cross-language-providers" >}}).
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
Amazon S3 Vectors is only supported in Java currently. To use Amazon S3 Vectors from Python agents, see [Using Cross-Language Providers]({{< ref "docs/development/vector-search/vector_stores#using-cross-language-providers" >}}).
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
Chroma is currently supported in the Python API only. To use Chroma from Java agents, see [Using Cross-Language Providers]({{< ref "docs/development/vector-search/vector_stores#using-cross-language-providers" >}}).
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
Elasticsearch is currently supported in the Java API only. To use Elasticsearch from Python agents, see [Using Cross-Language Providers]({{< ref "docs/development/vector-search/vector_stores#using-cross-language-providers" >}}).
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
| `filter_query`    | str  | None                      | Raw JSON Elasticsearch filter query (DSL) restricting which documents a KNN query can match |
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
Mem0 is currently supported in the Python API only. To use it from Java agents, see [Using Cross-Language Providers]({{< ref "docs/development/vector-search/vector_stores#using-cross-language-providers" >}}).
{{< /hint >}}

{{< hint info >}}
`Mem0VectorStore` implements `CollectionManageableVectorStore`, enabling [Long-Term Memory]({{< ref "docs/development/memory/long_term_memory" >}}) support. Filters use the unified equality-only [Filter DSL]({{< ref "docs/development/vector-search/vector_stores#filter-dsl" >}}) and are forwarded to the underlying Mem0 backend unchanged.
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
Milvus is currently supported in the Java API only. To use Milvus from Python agents, see [Using Cross-Language Providers]({{< ref "docs/development/vector-search/vector_stores#using-cross-language-providers" >}}).
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
