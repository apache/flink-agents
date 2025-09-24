---
title: Embedding Models
weight: 4
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

## Embedding Interface

Flink Agents uses a two-component architecture for embedding models:

1. **Connection**: Manages the connection to the embedding service (API keys, URLs, timeouts)
2. **Setup**: Configures the specific embedding model and its parameters

This separation allows multiple embedding setups to share the same connection, improving resource efficiency and configuration management.

If you want to use embedding models not offered by the built-in providers, you can extend the base embedding classes and implement your own! The embedding system is built around two main abstract classes:

### BaseEmbeddingModelConnection

Handles the connection to embedding services and provides the core embedding functionality:

```python
from flink_agents.api.embedding_models.embedding_model import BaseEmbeddingModelConnection

class MyEmbeddingConnection(BaseEmbeddingModelConnection):
    def embed(self, text: str, **kwargs) -> list[float]:
        # Implementation for generating embeddings
        pass
```

### BaseEmbeddingModelSetup

Manages embedding model configuration and acts as a high-level interface:

```python
from flink_agents.api.embedding_models.embedding_model import BaseEmbeddingModelSetup

class MyEmbeddingSetup(BaseEmbeddingModelSetup):
    connection: str  # Name of the connection to use
    model: str       # Model name to use

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        # Return model-specific configuration
        return {"model": self.model, ...}
```

### Resource Decorators

Flink Agents provides decorators to simplify embedding model setup within agents:

#### @embedding_model_connection

The `@embedding_model_connection` decorator marks a method that creates an embedding model connection.

#### @embedding_model_setup

The `@embedding_model_setup` decorator marks a method that creates an embedding model setup.

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
from typing import Any, Dict, Tuple, Type
from flink_agents.api.agent import Agent
from flink_agents.api.decorators import embedding_model_connection, embedding_model_setup
from flink_agents.integrations.embedding_models.local.ollama_embedding_model import (
    OllamaEmbeddingModelConnection,
    OllamaEmbeddingModelSetup
)

class MyAgent(Agent):

    @embedding_model_connection
    @staticmethod
    def ollama_connection() -> Tuple[Type[OllamaEmbeddingModelConnection], Dict[str, Any]]:
        return OllamaEmbeddingModelConnection, {
            "base_url": "http://localhost:11434",
            "request_timeout": 30.0
        }

    @embedding_model_setup
    @staticmethod
    def ollama_embedding() -> Tuple[Type[OllamaEmbeddingModelSetup], Dict[str, Any]]:
        return OllamaEmbeddingModelSetup, {
            "connection": "ollama_connection",
            "model": "nomic-embed-text",
            "truncate": True,
            "keep_alive": "5m"
        }

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
2. Install the OpenAI Python package: `pip install openai`

#### Usage Example

```python
from typing import Any, Dict, Tuple, Type
from flink_agents.api.agent import Agent
from flink_agents.api.decorators import embedding_model_connection, embedding_model_setup
from flink_agents.integrations.embedding_models.openai_embedding_model import (
    OpenAIEmbeddingModelConnection,
    OpenAIEmbeddingModelSetup
)

class MyAgent(Agent):

    @embedding_model_connection
    @staticmethod
    def openai_connection() -> Tuple[Type[OpenAIEmbeddingModelConnection], Dict[str, Any]]:
        return OpenAIEmbeddingModelConnection, {
            "api_key": "your-api-key-here",
            "base_url": "https://api.openai.com/v1",
            "request_timeout": 30.0,
            "max_retries": 3
        }

    @embedding_model_setup
    @staticmethod
    def openai_embedding() -> Tuple[Type[OpenAIEmbeddingModelSetup], Dict[str, Any]]:
        return OpenAIEmbeddingModelSetup, {
            "connection": "openai_connection",
            "model": "text-embedding-3-small",
            "encoding_format": "float",
            "dimensions": 1536
        }
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