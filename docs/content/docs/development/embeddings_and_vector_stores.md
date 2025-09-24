---
title: Embeddings and Vector Store
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


## Embedding Models

Embedding models convert human language into high-dimensional vectors that capture semantic meaning, enabling powerful semantic search and retrieval
capabilities. These vector representations allow agents to understand and work with text similarity, semantic search, and knowledge retrieval patterns.

Flink Agents separates embedding models into two components:

- **EmbeddingModelConnection**: Manages the low-level communication with embedding services (API keys, endpoints, timeouts)
- **EmbeddingModelSetup**: Configures model-specific parameters and provides the embedding interface to agents

This design allows you to reuse connections across multiple model configurations while maintaining clean separation of concerns.

```python
  # Connection handles service communication
  connection = OpenAIEmbeddingModelConnection(api_key="sk-...")

  # Setup configures model behavior
  setup = OpenAIEmbeddingModelSetup(
      connection="openai_conn",
      model="text-embedding-3-small"
  )
```

###  Supported Providers

#### OpenAI Embeddings
OpenAI offers multiple embedding models(e.g. text-embedding-3-small), check [here](https://platform.openai.com/docs/guides/embeddings#embedding-models) to see the full list of OpenAI embedding models.
##### Connection Setup
```python
  # Configure connection to OpenAI API
  openai_conn = OpenAIEmbeddingModelConnection(
      name="openai_conn",
      api_key="sk-your-api-key-here",
      base_url="https://api.openai.com/v1",  # Default
      request_timeout=30.0,                  # Default
      max_retries=3,                         # Default
      organization="org-123",                # Optional
      project="proj-456"                     # Optional
  )

```

#### Ollama Embeddings
TBD

### Agent Integration
#### Using Decorators
The recommended approach is to use decorators to define embedding models within your agent:
```python
  class MyAgent(Agent):
      @embedding_model_connection
      @staticmethod
      def openai_conn():
          return OpenAIEmbeddingModelConnection, {
              "name": "openai_conn",
              "api_key": "sk-your-key"
          }

      @embedding_model_setup
      @staticmethod
      def embeddings():
          return OpenAIEmbeddingModelSetup, {
              "name": "embeddings",
              "connection": "openai_conn",
              "model": "text-embedding-3-small"
          }

      ...
```

{{< hint warning >}}
**TODO**: List of all built-in Embedding Model and configuration.
{{< /hint >}}

## Vector Store

{{< hint warning >}}
**TODO**: What is Vector Store. How to define and use VectorStoreConnection and VectorStoreSetup.
{{< /hint >}}

{{< hint warning >}}
**TODO**: How to use a Vector Store. List of all built-in Vector Store and configuration.
{{< /hint >}}