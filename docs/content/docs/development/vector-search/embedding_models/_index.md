---
title: "Embedding Models"
weight: 1
type: docs
aliases:
  - /docs/development/embedding_models/
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
Built-in embedding models accept text.
[DashScope multimodal embeddings]({{< ref "docs/development/vector-search/embedding_models/providers#multimodal-embeddings" >}}) also accept images and video files.
{{< /hint >}}

## Overview

Embedding models convert text and supported media into vectors that represent
their meaning. Agents can compare these vectors to find similar content or
retrieve relevant information.

In Flink Agents, embedding models are essential for:
- **Semantic Search**: Finding relevant documents or information based on meaning rather than exact keyword matches
- **Text Similarity**: Measuring how similar two pieces of text are in meaning
- **Knowledge Retrieval**: Enabling agents to find and retrieve relevant context from large knowledge bases
- **Vector Databases**: Storing and querying embeddings for efficient similarity search

## Getting Started

Register an embedding model connection (`EMBEDDING_MODEL_CONNECTION`) and setup
(`EMBEDDING_MODEL`), then access the setup through the runtime context. The setup's
`connection` field references the connection's registered Resource name. See
[Registering Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}})
for the Declaration API, programmatic API, YAML, and registration scopes.

### Resource Declaration

The following examples use the Declaration API with Python decorators and Java
annotations. Provider-specific connection and setup options are described in
[Built-in Embedding Model Providers]({{< ref "docs/development/vector-search/embedding_models/providers" >}}).

#### Declare an embedding model connection

The **`@embedding_model_connection`** decorator/ **`@EmbeddingModelConnection`** annotation marks a method that creates an embedding model connection.
This is typically defined once and shared across multiple embedding model setups.

{{< tabs "Declare an embedding model connection" >}}

{{< tab "Python" >}}
```python
@embedding_model_connection
@staticmethod
def embedding_model_connection() -> ResourceDescriptor:
    ...
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@EmbeddingModelConnection
public static ResourceDescriptor embeddingModelConnection() {
    ...
}
```
{{< /tab >}}

{{< /tabs >}}

#### Declare an embedding model setup

The **`@embedding_model_setup`** decorator/ **`@EmbeddingModelSetup`** annotation marks a method that creates an embedding model setup.
This references an embedding model connection and adds embed-specific configuration like model and dimensions.

{{< tabs "Declare an embedding model setup" >}}

{{< tab "Python" >}}
```python
@embedding_model_setup
@staticmethod
def embedding_model_setup() -> ResourceDescriptor:
    ...
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@EmbeddingModelSetup
public static ResourceDescriptor embeddingModelSetup() {
    ...
}
```
{{< /tab >}}

{{< /tabs >}}

### Usage Example

Here's how to define and use embedding models in your agent:

{{< tabs "Usage example" >}}

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
            request_timeout=30.0
        )

    @embedding_model_setup
    @staticmethod
    def openai_embedding() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.EmbeddingModel.OPENAI_SETUP,
            connection="openai_connection",
            model="your-embedding-model-here"
        )

    @action(EventType.InputEvent)
    @staticmethod
    def process_text(event: Event, ctx: RunnerContext) -> None:
        # Get the embedding model from the runtime context
        embedding_model = ctx.get_resource("openai_embedding", ResourceType.EMBEDDING_MODEL)

        # Use the embedding model to generate embeddings
        input_event = InputEvent.from_event(event)
        user_query = str(input_event.input)
        embedding = embedding_model.embed(user_query).embeddings

        # Handle the embedding
        # Process the embedding vector as needed for your use case
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

    @Action(EventType.InputEvent)
    public static void processText(Event event, RunnerContext ctx)
            throws Exception {
        InputEvent inputEvent = InputEvent.fromEvent(event);
        // Get the embedding model from the runtime context
        BaseEmbeddingModelSetup embeddingModel =
                (BaseEmbeddingModelSetup)
                        ctx.getResource("ollamaEmbedding", ResourceType.EMBEDDING_MODEL);

        // Use the embedding model to generate embeddings
        String input = (String) inputEvent.getInput();
        float[] embedding = embeddingModel.embed(input).getEmbeddings();

        // Handle the embedding
        // Process the embedding vector as needed for your use case
    }
}
```
{{< /tab >}}

{{< /tabs >}}

## Inputs and Results

Supported inputs depend on the provider and model. See
[Chat Messages]({{< ref "docs/development/calling-llms/chat#message-content-blocks" >}})
for block types and media sources.

Every call returns an `EmbeddingResult` containing the vectors and optional token
usage. Read `.embeddings` in Python or `.getEmbeddings()` in Java to access the
vectors.

| Operation | Python | Java |
|---|---|---|
| Embed a string | `embed("text")`: result contains one vector | `embed("text")`: result contains one `float[]` |
| Embed a batch of strings | `embed(["first", "second"])`: result contains a list of vectors | `embed(List.of(new TextBlock("first"), new TextBlock("second")))`: result contains a `List<float[]>` |
| Embed one block | `embed(block)`: result contains one vector | `embed(block)`: result contains one `float[]` |
| Embed a batch containing media | `embed([text, image, video])`: result contains a list of vectors | `embed(List.of(textBlock, image, video))`: result contains a `List<float[]>` |

A batch returns one vector per input, in input order.

Read `.token_usage` in Python or `.getTokenUsage()` in Java for usage reported by
the provider. It is `None` / `null` when unavailable; batch usage applies to the
whole batch.

For example, `result = embedding_model.embed("hello")` provides both
`result.embeddings` and `result.token_usage`. A one-item batch still contains a
list of vectors; it does not become a single vector.

## Using Cross-Language Providers

For descriptor selection, registration, and runtime requirements, see
[Cross-Language Resources]({{< ref "docs/development/create-agent/resources#cross-language-resources" >}}).
[Built-in Providers]({{< ref "docs/development/vector-search/embedding_models/providers" >}}) lists available implementations and their configuration.

## Custom Providers

{{< hint warning >}}
The custom provider APIs are experimental and unstable, subject to incompatible changes in future releases.
{{< /hint >}}

If you want to use embedding models not offered by the built-in providers, you can extend the base embedding classes and implement your own! The embedding system is built around two main abstract classes:

### BaseEmbeddingModelConnection

Handles the connection to embedding services and provides the core embedding functionality.
{{< tabs "Custom Embedding Connection" >}}

{{< tab "Python" >}}

```python
class MyEmbeddingConnection(BaseEmbeddingModelConnection):
    def embed(
        self, content: EmbeddingContent, **kwargs: Any
    ) -> EmbeddingResult[list[float] | list[list[float]]]:
        # Validate the input types supported by your provider.
        # Return EmbeddingResult with one vector for a single input, or one per
        # batch item in order. Include token_usage when the provider reports it.
        ...
```

Import `EmbeddingContent` and `EmbeddingResult` from
`flink_agents.api.embedding_models.embedding_model`.
An input can be a string, a data content block, or a sequence containing either
or both.

{{< /tab >}}

{{< tab "Java" >}}
```java
public class MyEmbeddingConnection extends BaseEmbeddingModelConnection {

    @Override
    public EmbeddingResult<List<float[]>> embed(
            List<? extends DataContentBlock> blocks, Map<String, Object> parameters) {
        // Text-only providers validate the entire batch before calling the service.
        List<String> texts = EmbeddingModelUtils.requireTextInputs(blocks, "MyProvider");
        List<float[]> embeddings = ...;
        return new EmbeddingResult<>(embeddings, null);
    }
}
```

Implement the batch `embed` method and return one vector per input, in input
order, with optional token usage. Single-input calls are supported by the base class.

For a text-only provider, `EmbeddingModelUtils.requireTextInputs` extracts text and
rejects media before any service call. A multimodal provider converts its supported
block types directly and rejects unsupported ones.
{{< /tab >}}

{{< /tabs >}}

### BaseEmbeddingModelSetup

The setup class acts as a high-level configuration interface that defines which connection to use and how to configure the embedding model.

{{< tabs "Custom Embedding Setup" >}}

{{< tab "Python" >}}
```python
class MyEmbeddingSetup(BaseEmbeddingModelSetup):
    # Add your custom configuration fields here

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        # Return model-specific configuration passed to embed()
        # This dictionary is passed as **kwargs to the embed() method
        return {"model": self.model, ...}
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class MyEmbeddingSetup extends BaseEmbeddingModelSetup {

    @Override
    public Map<String, Object> getParameters() {
        // Return model-specific configuration passed to embed()
        // This dictionary is passed as parameters to the embed() method
        Map<String, Object> parameters = new HashMap<>();

        if (model != null) {
            parameters.put("model", model);
        }
        ...

        return parameters;
    }

}
```
{{< /tab >}}

{{< /tabs >}}

[Built-in Embedding Model Providers]({{< ref "docs/development/vector-search/embedding_models/providers" >}}).
