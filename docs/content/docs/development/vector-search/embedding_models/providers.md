---
title: "Built-in Embedding Model Providers"
weight: 1
type: docs
aliases:
  - /docs/development/vector-search/embedding_models_providers/
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

### Amazon Bedrock

Amazon Bedrock provides embedding capabilities through the Amazon Titan Text Embeddings V2 model via the [InvokeModel API](https://docs.aws.amazon.com/bedrock/latest/userguide/titan-embedding-models.html). The integration supports configurable output dimensions (256, 512, or 1024) and parallelizes batch embedding via a configurable thread pool, since the Titan V2 model processes one text per API call. Authentication is handled via SigV4 using the AWS default credentials chain.

{{< hint info >}}
Amazon Bedrock embedding models are only supported in Java currently. To use Amazon Bedrock embeddings from Python agents, see [Using Cross-Language Providers]({{< ref "docs/development/vector-search/embedding_models#using-cross-language-providers" >}}).
{{< /hint >}}

#### Prerequisites

1. An AWS account with [Amazon Bedrock model access](https://docs.aws.amazon.com/bedrock/latest/userguide/model-access.html) enabled for Amazon Titan Text Embeddings V2
2. IAM credentials configured via any method supported by the [AWS Default Credentials Provider](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html)

#### BedrockEmbeddingModelConnection Parameters

{{< tabs "BedrockEmbeddingModelConnection Parameters" >}}

{{< tab "Java" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `region` | String | `"us-east-1"` | AWS region for the Bedrock service |
| `model` | String | `"amazon.titan-embed-text-v2:0"` | Default embedding model ID |
| `embed_concurrency` | int | `4` | Thread pool size for parallel batch embedding |
| `max_retries` | int | `5` | Maximum number of API retry attempts (retries on throttling, 429, 503) |

{{< /tab >}}

{{< /tabs >}}

#### BedrockEmbeddingModelSetup Parameters

{{< tabs "BedrockEmbeddingModelSetup Parameters" >}}

{{< tab "Java" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | String | Required | Reference to connection method name |
| `model` | String | None | Override the default embedding model from the connection |
| `dimensions` | int | None | Output embedding dimensions: 256, 512, or 1024 |

{{< /tab >}}

{{< /tabs >}}

#### Usage Example

{{< tabs "Amazon Bedrock Embedding Usage Example" >}}

{{< tab "Java" >}}
```java
public class MyAgent extends Agent {

    @EmbeddingModelConnection
    public static ResourceDescriptor bedrockEmbeddingConnection() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.EmbeddingModel.BEDROCK_CONNECTION)
                .addInitialArgument("region", "us-east-1")
                .addInitialArgument("embed_concurrency", 8)
                .build();
    }

    @EmbeddingModelSetup
    public static ResourceDescriptor bedrockEmbedding() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.EmbeddingModel.BEDROCK_SETUP)
                .addInitialArgument("connection", "bedrockEmbeddingConnection")
                .addInitialArgument("model", "amazon.titan-embed-text-v2:0")
                .addInitialArgument("dimensions", 1024)
                .build();
    }

    ...
}
```
{{< /tab >}}

{{< /tabs >}}

#### Available Models

The Bedrock embedding integration currently supports:
- **Amazon Titan Text Embeddings V2** (`amazon.titan-embed-text-v2:0`): supports 256, 512, or 1024 dimensions

{{< hint info >}}
The integration always requests **normalized** embeddings (unit vectors), which makes cosine similarity equivalent to dot product. If you need raw, un-normalized vectors, use a custom provider.
{{< /hint >}}

Visit the [Amazon Bedrock Embedding Models documentation](https://docs.aws.amazon.com/bedrock/latest/userguide/titan-embedding-models.html) for the latest information.

{{< hint warning >}}
Model availability varies by AWS region and requires explicit model access enablement in the Bedrock console. Always check the [Amazon Bedrock documentation](https://docs.aws.amazon.com/bedrock/latest/userguide/models-regions.html) for regional availability before implementing in production.
{{< /hint >}}

### Ollama

Ollama provides local embedding models that run on your machine, offering privacy and control over your data.

#### Prerequisites

1. Install Ollama from [https://ollama.com/](https://ollama.com/)
2. Start the Ollama server: `ollama serve`
3. Download an embedding model: `ollama pull nomic-embed-text`

#### OllamaEmbeddingModelConnection Parameters

{{< tabs "OllamaEmbeddingModelConnection Parameters" >}}

{{< tab "Python" >}}

| Parameter         | Type  | Default                    | Description                     |
|-------------------|-------|----------------------------|---------------------------------|
| `base_url`        | str   | `"http://localhost:11434"` | Ollama server URL               |
| `request_timeout` | float | `30.0`                     | HTTP request timeout in seconds |

{{< /tab >}}

{{< tab "Java" >}}

| Parameter | Type   | Default                    | Description                         |
|-----------|--------|----------------------------|-------------------------------------|
| `host`    | String | `"http://localhost:11434"` | Ollama server URL                   |
| `model`   | String | `nomic-embed-text`         | Name of the default embedding model |

{{< /tab >}}

{{< /tabs >}}

#### OllamaEmbeddingModelSetup Parameters

{{< tabs "OllamaEmbeddingModelSetup Parameters" >}}

{{< tab "Python" >}}

| Parameter           | Type      | Default  | Description                                     |
|---------------------|-----------|----------|-------------------------------------------------|
| `connection`        | str       | Required | Reference to connection method name             |
| `model`             | str       | Required | Name of the embedding model to use              |
| `truncate`          | bool      | `True`   | Whether to truncate text exceeding model limits |
| `keep_alive`        | str/float | `"5m"`   | How long to keep model loaded in memory         |
| `additional_kwargs` | dict      | `{}`     | Additional Ollama API parameters                |

{{< /tab >}}

{{< tab "Java" >}}

| Parameter           | Type   | Default  | Description                                     |
|---------------------|--------|----------|-------------------------------------------------|
| `connection`        | String | Required | Reference to connection method name             |
| `model`             | String | Required | Name of the embedding model to use              |

{{< /tab >}}

{{< /tabs >}}

#### Usage Example
{{< tabs "Ollama Usage Example" >}}

{{< tab "Python" >}}

```python
class MyAgent(Agent):

    @embedding_model_connection
    @staticmethod
    def ollama_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.EmbeddingModel.OLLAMA_CONNECTION,
            base_url="http://localhost:11434",
            request_timeout=30.0
        )

    @embedding_model_setup
    @staticmethod
    def ollama_embedding() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.EmbeddingModel.OLLAMA_SETUP,
            connection="ollama_connection",
            model="nomic-embed-text",
            truncate=True,
            keep_alive="5m"
        )

    ...
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class MyAgent extends Agent {
    
    @EmbeddingModelConnection
    public static ResourceDescriptor ollamaConnection() {
        return ResourceDescriptor.Builder.newBuilder(
                        ResourceName.EmbeddingModel.OLLAMA_CONNECTION)
                .addInitialArgument("host", "http://localhost:11434")
                .build();
    }

    @EmbeddingModelSetup
    public static ResourceDescriptor ollamaEmbedding() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.EmbeddingModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "ollamaConnection")
                .addInitialArgument("model", "nomic-embed-text")
                .build();
    }
    
    ...
}
```
{{< /tab >}}

{{< /tabs >}}

#### Available Models

Visit the [Ollama Embedding Models Library](https://ollama.com/search?c=embedding) for the complete and up-to-date list of available embedding models.

Some popular options include:
- **nomic-embed-text**
- **all-minilm**
- **mxbai-embed-large**

{{< hint warning >}}
Model availability and specifications may change. Always check the official Ollama documentation for the latest information before implementing in production.
{{< /hint >}}

### OpenAI

OpenAI provides cloud-based embedding models with state-of-the-art performance.

#### Prerequisites

1. Get an API key from [OpenAI Platform](https://platform.openai.com/)

#### Usage Example

{{< tabs "OpenAI Embedding Usage Example" >}}

{{< tab "Python" >}}
```python
class MyAgent(Agent):

    @embedding_model_connection
    @staticmethod
    def openai_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.EmbeddingModel.OPENAI_CONNECTION,
            api_key="your-api-key-here",
            base_url="https://api.openai.com/v1",
            request_timeout=30.0,
            max_retries=3
        )

    @embedding_model_setup
    @staticmethod
    def openai_embedding() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.EmbeddingModel.OPENAI_SETUP,
            connection="openai_connection",
            model="your-embedding-model-here",
            encoding_format="float"
        )
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class MyAgent extends Agent {

    @EmbeddingModelConnection
    public static ResourceDescriptor openaiConnection() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.EmbeddingModel.OPENAI_CONNECTION)
                .addInitialArgument("api_key", System.getenv("OPENAI_API_KEY"))
                .addInitialArgument("request_timeout", 30)
                .addInitialArgument("max_retries", 3)
                .build();
    }

    @EmbeddingModelSetup
    public static ResourceDescriptor openaiEmbedding() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.EmbeddingModel.OPENAI_SETUP)
                .addInitialArgument("connection", "openaiConnection")
                .addInitialArgument("model", "text-embedding-3-small")
                .addInitialArgument("dimensions", 512)
                .build();
    }

    ...
}
```
{{< /tab >}}

{{< /tabs >}}

#### OpenAIEmbeddingModelConnection Parameters

{{< tabs "OpenAIEmbeddingModelConnection Parameters" >}}

{{< tab "Python" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `api_key` | str | Required | OpenAI API key for authentication; a blank key is passed through to the SDK for unauthenticated OpenAI-compatible servers (the Java connection rejects a blank key) |
| `base_url` | str | `"https://api.openai.com/v1"` | OpenAI API base URL |
| `request_timeout` | float | `30.0` | HTTP request timeout in seconds; `0` disables the timeout |
| `max_retries` | int | `3` | Maximum number of retry attempts |
| `organization` | str | None | Optional organization ID; when unset or blank the Python SDK may read `OPENAI_ORG_ID` from the environment (the Java connection sends no organization) |
| `project` | str | None | Optional project ID; when unset or blank the Python SDK may read `OPENAI_PROJECT_ID` from the environment (the Java connection sends no project) |

{{< /tab >}}

{{< tab "Java" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `api_key` | String | Required | OpenAI API key for authentication |
| `base_url` | String | `"https://api.openai.com/v1"` | OpenAI API base URL |
| `request_timeout` | Number | `30` | HTTP request timeout in seconds; `0` disables the timeout |
| `max_retries` | Number | `3` | Maximum number of retry attempts, a non-negative integer |
| `organization` | String | None | Optional organization ID |
| `project` | String | None | Optional project ID |

{{< /tab >}}

{{< /tabs >}}

#### OpenAIEmbeddingModelSetup Parameters

{{< tabs "OpenAIEmbeddingModelSetup Parameters" >}}

{{< tab "Python" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | str | Required | Reference to connection method name |
| `model` | str | Required | OpenAI embedding model name |
| `encoding_format` | str | `"float"` | Wire format, `"float"` or `"base64"`; base64 responses are decoded to floats |
| `dimensions` | int | None | Output dimensions (text-embedding-3 models only) |
| `user` | str | None | End-user identifier for monitoring |
| `additional_kwargs` | dict | `{}` | Extra request body properties sent with every embeddings request; keys may not be `model`, `input`, `encoding_format`, `dimensions` or `user`. A per-call `additional_kwargs` replaces this map |

`additional_kwargs` entries are sent to the API as request body properties; an entry that repeats a typed field fails validation (set the typed argument instead), and `dimensions` must be an integer.

{{< /tab >}}

{{< tab "Java" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | String | Required | Reference to connection method name |
| `model` | String | Required | OpenAI embedding model name |
| `encoding_format` | String | `"float"` | Wire format, `"float"` or `"base64"`; base64 responses are decoded to floats |
| `dimensions` | Number | None | Output dimensions, a positive integer (text-embedding-3 models only) |
| `user` | String | None | End-user identifier for monitoring |
| `additional_kwargs` | Map | `{}` | Extra request body properties sent with every embeddings request; keys may not be `model`, `input`, `encoding_format`, `dimensions` or `user`. A per-call `additional_kwargs` replaces this map |


`additional_kwargs` entries that repeat a typed field fail validation instead of overriding it (set the typed argument instead), and `dimensions` must be a positive integer.
{{< /tab >}}

{{< /tabs >}}

A batch of texts is embedded in a single request, so it must stay within OpenAI's per-request limits (2048 inputs at the time of writing); split larger batches before calling. Token usage reported by the API is available through `embedWithUsage` in Java and `embed_with_usage` in Python.

#### Available Models

Visit the [OpenAI Embeddings documentation](https://platform.openai.com/docs/guides/embeddings#embedding-models) for the complete and up-to-date list of available embedding models.

Current popular models include:
- **text-embedding-3-small**
- **text-embedding-3-large**
- **text-embedding-ada-002**

{{< hint warning >}}
Model availability and specifications may change. Always check the official OpenAI documentation for the latest information before implementing in production.
{{< /hint >}}

### Alibaba Cloud Model Studio (DashScope)

This integration connects to Alibaba Cloud Model Studio (Bailian) through the DashScope SDK to access embedding models.

{{< hint info >}}
The DashScope integration is currently supported in the Python API only. To use it from Java agents, see [Using Cross-Language Providers]({{< ref "docs/development/vector-search/embedding_models#using-cross-language-providers" >}}).
{{< /hint >}}

#### Prerequisites

1. Get an API key from [Alibaba Cloud Model Studio](https://dashscope.console.aliyun.com/)

#### Usage Example

```python
class MyAgent(Agent):

    @embedding_model_connection
    @staticmethod
    def dashscope_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.EmbeddingModel.DASHSCOPE_CONNECTION,
            api_key="your-api-key-here",  # Or set DASHSCOPE_API_KEY env var
            request_timeout=30.0
        )

    @embedding_model_setup
    @staticmethod
    def dashscope_embedding() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.EmbeddingModel.DASHSCOPE_SETUP,
            connection="dashscope_connection",
            model="text-embedding-v4",
            text_type="query"
        )
```

#### DashScopeEmbeddingModelConnection Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `api_key` | str | `$DASHSCOPE_API_KEY` | DashScope API key for authentication |
| `request_timeout` | float | `30.0` | HTTP request timeout in seconds |

#### DashScopeEmbeddingModelSetup Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | str | Required | Reference to connection method name |
| `model` | str | `"text-embedding-v4"` | Embedding model name |
| `text_type` | str | None | Input type: `"query"` or `"document"` |
| `dimension` | int | None | Output vector dimensions (model-dependent) |
| `additional_kwargs` | dict | `{}` | Additional DashScope API parameters |

#### Available Models

Visit the [Alibaba Cloud Model Studio embedding models documentation](https://help.aliyun.com/zh/dashscope/developer-reference/text-embedding-api-details) for the complete and up-to-date list of available embedding models.

Some popular options include:
- **text-embedding-v4** (default, recommended)
- **text-embedding-v3**
- **text-embedding-v2**
- **text-embedding-v1**

{{< hint warning >}}
Model availability and specifications may change. Always check the official Alibaba Cloud Model Studio documentation for the latest information before implementing in production.
{{< /hint >}}
