---
title: Embedding Models
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

# Embedding Models

{{< hint info >}}
Embedding models are currently supported in the Python API only. Java API support is planned for future releases.
{{< /hint >}}

{{< hint info >}}
This page covers text-based embedding models. Flink agents does not currently support multimodal embeddings.
{{< /hint >}}

## Overview

Embedding models convert text strings into high-dimensional vectors that capture semantic meaning, enabling powerful semantic search and retrieval capabilities. These vector representations allow agents to understand and work with text similarity, semantic search, and knowledge retrieval patterns.

In Flink Agents, embedding models are essential for:
- **Semantic Search**: Finding relevant documents or information based on meaning rather than exact keyword matches
- **Text Similarity**: Measuring how similar two pieces of text are in meaning
- **Knowledge Retrieval**: Enabling agents to find and retrieve relevant context from large knowledge bases
- **Vector Databases**: Storing and querying embeddings for efficient similarity search

## Getting Started

To use embedding models in your agents, you need to define both a connection and setup using decorators, then access the embedding model through the runtime context.

### Resource Decorators

Flink Agents provides decorators to simplify embedding model setup within agents:

#### @embedding_model_connection

The `@embedding_model_connection` decorator marks a method that creates an embedding model connection.

#### @embedding_model_setup

The `@embedding_model_setup` decorator marks a method that creates an embedding model setup.

### Usage Example

Here's how to define and use embedding models in your agent:

```python
class MyAgent(Agent):
    
    @embedding_model_connection
    @staticmethod
    def openai_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=OpenAIEmbeddingModelConnection,
            api_key="your-api-key-here",
            base_url="https://api.openai.com/v1",
            request_timeout=30.0
        )

    @embedding_model_setup
    @staticmethod
    def openai_embedding() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=OpenAIEmbeddingModelSetup,
            connection="openai_connection",
            model="your-embedding-model-here"
        )

    @action(InputEvent)
    @staticmethod
    def process_text(event: InputEvent, ctx: RunnerContext) -> None:
        # Get the embedding model from the runtime context
        embedding_model = ctx.get_resource("openai_embedding", ResourceType.EMBEDDING_MODEL)

        # Use the embedding model to generate embeddings
        user_query = str(event.input)
        embedding = embedding_model.embed(user_query)

        # Handle the embedding
        # Process the embedding vector as needed for your use case
```

## Built-in Providers

### Ollama

Ollama provides local embedding models that run on your machine, offering privacy and control over your data.

#### Prerequisites

1. Install Ollama from [https://ollama.com/](https://ollama.com/)
2. Start the Ollama server: `ollama serve`
3. Download an embedding model: `ollama pull nomic-embed-text`

#### OllamaEmbeddingModelConnection Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `base_url` | str | `"http://localhost:11434"` | Ollama server URL |
| `request_timeout` | float | `30.0` | HTTP request timeout in seconds |

#### OllamaEmbeddingModelSetup Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | str | Required | Reference to connection method name |
| `model` | str | Required | Name of the embedding model to use |
| `truncate` | bool | `True` | Whether to truncate text exceeding model limits |
| `keep_alive` | str/float | `"5m"` | How long to keep model loaded in memory |
| `additional_kwargs` | dict | `{}` | Additional Ollama API parameters |

#### Usage Example

```python
class MyAgent(Agent):

    @embedding_model_connection
    @staticmethod
    def ollama_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=OllamaEmbeddingModelConnection,
            base_url="http://localhost:11434",
            request_timeout=30.0
        )

    @embedding_model_setup
    @staticmethod
    def ollama_embedding() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=OllamaEmbeddingModelSetup,
            connection="ollama_connection",
            model="nomic-embed-text",
            truncate=True,
            keep_alive="5m"
        )

    ...
```

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

```python
class MyAgent(Agent):

    @embedding_model_connection
    @staticmethod
    def openai_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=OpenAIEmbeddingModelConnection,
            api_key="your-api-key-here",
            base_url="https://api.openai.com/v1",
            request_timeout=30.0,
            max_retries=3
        )

    @embedding_model_setup
    @staticmethod
    def openai_embedding() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=OpenAIEmbeddingModelSetup,
            connection="openai_connection",
            model="your-embedding-model-here",
            encoding_format="float"
        )
```

#### OpenAIEmbeddingModelConnection Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `api_key` | str | Required | OpenAI API key for authentication |
| `base_url` | str | `"https://api.openai.com/v1"` | OpenAI API base URL |
| `request_timeout` | float | `30.0` | HTTP request timeout in seconds |
| `max_retries` | int | `3` | Maximum number of retry attempts |
| `organization` | str | None | Optional organization ID |
| `project` | str | None | Optional project ID |

#### OpenAIEmbeddingModelSetup Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | str | Required | Reference to connection method name |
| `model` | str | Required | OpenAI embedding model name |
| `encoding_format` | str | `"float"` | Return format ("float" or "base64") |
| `dimensions` | int | None | Output dimensions (text-embedding-3 models only) |
| `user` | str | None | End-user identifier for monitoring |
| `additional_kwargs` | dict | `{}` | Additional parameters for the OpenAI embeddings API |

#### Available Models

Visit the [OpenAI Embeddings documentation](https://platform.openai.com/docs/guides/embeddings#embedding-models) for the complete and up-to-date list of available embedding models.

Current popular models include:
- **text-embedding-3-small**
- **text-embedding-3-large**
- **text-embedding-ada-002**

{{< hint warning >}}
Model availability and specifications may change. Always check the official OpenAI documentation for the latest information before implementing in production.
{{< /hint >}}

## Custom Providers

{{< hint warning >}}
The custom provider APIs are experimental and unstable, subject to incompatible changes in future releases.
{{< /hint >}}

If you want to use embedding models not offered by the built-in providers, you can extend the base embedding classes and implement your own! The embedding system is built around two main abstract classes:

### BaseEmbeddingModelConnection

Handles the connection to embedding services and provides the core embedding functionality.

```python
class MyEmbeddingConnection(BaseEmbeddingModelConnection):
    
    def embed(self, text: str, **kwargs) -> list[float]:
        # Core method: convert text to embedding vector
        # - text: Input text to embed
        # - kwargs: Additional parameters from model_kwargs
        # - Returns: List of float values representing the embedding
        pass
```

### BaseEmbeddingModelSetup

The setup class acts as a high-level configuration interface that defines which connection to use and how to configure the embedding model.


```python
class MyEmbeddingSetup(BaseEmbeddingModelSetup):
    # Add your custom configuration fields here
    
    @property
    def model_kwargs(self) -> Dict[str, Any]:
        # Return model-specific configuration passed to embed()
        # This dictionary is passed as **kwargs to the embed() method
        return {"model": self.model, ...}
```