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
This page covers text-based embedding models. Flink Agents does not currently support multimodal embeddings.
{{< /hint >}}

## Overview

Embedding models convert text strings into high-dimensional vectors that capture semantic meaning, enabling powerful semantic search and retrieval capabilities. These vector representations allow agents to understand and work with text similarity, semantic search, and knowledge retrieval patterns.

In Flink Agents, embedding models are essential for:
- **Semantic Search**: Finding relevant documents or information based on meaning rather than exact keyword matches
- **Text Similarity**: Measuring how similar two pieces of text are in meaning
- **Knowledge Retrieval**: Enabling agents to find and retrieve relevant context from large knowledge bases
- **Vector Databases**: Storing and querying embeddings for efficient similarity search

## Getting Started

To use embedding models in your agents, you need to define both a connection and setup using decorators/annotations, then access the embedding model through the runtime context.

### Resource Declaration

Flink Agents provides decorators(in python) and annotations(in java) to simplify embedding model setup within agents:

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
        embedding = embedding_model.embed(user_query)

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
                        ctx.getResource("embeddingModel", ResourceType.EMBEDDING_MODEL);

        // Use the embedding model to generate embeddings
        String input = (String) inputEvent.getInput();
        float[] embedding = embeddingModel.embed(input);

        // Handle the embedding
        // Process the embedding vector as needed for your use case
    }
}
```
{{< /tab >}}

{{< /tabs >}}

## Using Cross-Language Providers

Flink Agents supports cross-language embedding model integration, allowing you to use embedding models implemented in one language (Java or Python) from agents written in the other language. This is particularly useful when an embedding model provider is only available in one language (e.g., the DashScope embedding integration for Alibaba Cloud Model Studio is currently Python-only).

{{< hint warning >}}
**Limitations:**
- Cross-language resources are currently supported only when [running in Flink]({{< ref "docs/operations/deployment#run-in-flink" >}}), not in local development mode
- Complex object serialization between languages may have limitations
{{< /hint >}}
### How To Use

To use a resource implemented in another language, declare its implementation class with the corresponding cross-language resource descriptor:

- **Using Java embedding models in Python**: Use `JavaResourceDescriptor` with the actual Java connection or setup class as `clazz`.
- **Using Python embedding models in Java**: Use `PythonResourceDescriptor.Builder.newBuilder(...)` with the actual Python connection or setup class, including its module.

### Usage Example

{{< tabs "Cross-Language Embedding Model Usage Example" >}}

{{< tab "Using Java Embedding Model in Python" >}}

```python
class MyAgent(Agent):

    @embedding_model_connection
    @staticmethod
    def java_embedding_connection() -> ResourceDescriptor:
        # In pure Java, the equivalent ResourceDescriptor would be:
        # ResourceDescriptor.Builder
        #     .newBuilder(ResourceName.EmbeddingModel.OLLAMA_CONNECTION)
        #     .addInitialArgument("host", "http://localhost:11434")
        #     .build();
        return JavaResourceDescriptor(
            clazz=ResourceName.EmbeddingModel.Java.OLLAMA_CONNECTION,
            host="http://localhost:11434"
        )

    @embedding_model_setup
    @staticmethod
    def java_embedding_model() -> ResourceDescriptor:
        # In pure Java, the equivalent ResourceDescriptor would be:
        # ResourceDescriptor.Builder
        #     .newBuilder(ResourceName.EmbeddingModel.OLLAMA_SETUP)
        #     .addInitialArgument("connection", "java_embedding_connection")
        #     .addInitialArgument("model", "nomic-embed-text")
        #     .build();
        return JavaResourceDescriptor(
            clazz=ResourceName.EmbeddingModel.Java.OLLAMA_SETUP,
            connection="java_embedding_connection",
            model="nomic-embed-text"
        )

    @action(EventType.InputEvent)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        # Use the Java embedding model from Python
        input_event = InputEvent.from_event(event)
        embedding_model = ctx.get_resource("java_embedding_model", ResourceType.EMBEDDING_MODEL)
        embedding = embedding_model.embed(str(input_event.input))
        # Process the embedding vector as needed
```

{{< /tab >}}

{{< tab "Using Python Embedding Model in Java" >}}

```java
public class MyAgent extends Agent {

    @EmbeddingModelConnection
    public static ResourceDescriptor pythonEmbeddingConnection() {
        // In pure Python, the equivalent ResourceDescriptor would be:
        // ResourceDescriptor(
        //     clazz=ResourceName.EmbeddingModel.OLLAMA_CONNECTION,
        //     base_url="http://localhost:11434"
        // )
        return PythonResourceDescriptor.Builder.newBuilder(ResourceName.EmbeddingModel.Python.OLLAMA_CONNECTION)
                .addInitialArgument("base_url", "http://localhost:11434")
                .build();
    }

    @EmbeddingModelSetup
    public static ResourceDescriptor pythonEmbeddingModel() {
        // In pure Python, the equivalent ResourceDescriptor would be:
        // ResourceDescriptor(
        //     clazz=ResourceName.EmbeddingModel.OLLAMA_SETUP,
        //     connection="ollama_connection",
        //     model="nomic-embed-text"
        // )
        return PythonResourceDescriptor.Builder.newBuilder(ResourceName.EmbeddingModel.Python.OLLAMA_SETUP)
                .addInitialArgument("connection", "pythonEmbeddingConnection")
                .addInitialArgument("model", "nomic-embed-text")
                .build();
    }

    @Action(EventType.InputEvent)
    public static void processInput(Event event, RunnerContext ctx) throws Exception {
        InputEvent inputEvent = InputEvent.fromEvent(event);
        // Use the Python embedding model from Java
        BaseEmbeddingModelSetup embeddingModel =
            (BaseEmbeddingModelSetup) ctx.getResource(
                "pythonEmbeddingModel",
                ResourceType.EMBEDDING_MODEL);
        float[] embedding = embeddingModel.embed((String) inputEvent.getInput());
        // Process the embedding vector as needed
    }
}
```

{{< /tab >}}

{{< /tabs >}}

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

    @abstractmethod
    def embed(self, text: str | Sequence[str], **kwargs: Any) -> list[float] | list[list[float]]:
        # Core method: convert text to embedding vector
        # - text: Input text to embed
        # - kwargs: Additional parameters from model_kwargs
        # - Returns: List of float values representing the embedding
        pass
```

{{< /tab >}}

{{< tab "Java" >}}
```java
public class MyEmbeddingConnection extends BaseEmbeddingModelConnection {

    @Override
    public float[] embed(String text, Map<String, Object> parameters) {
        // Core method: convert text to embedding vector
        // - text: Input text to embed
        // - parameters: Additional parameters
        // - Returns: Float array representing the embedding
        float[] embedding = ...;
        return embedding;
    }

    @Override
    public List<float[]> embed(List<String> texts, Map<String, Object> parameters) {
        // Core method: convert texts to embedding vectors
        // - text: Input texts to embed
        // - parameters: Additional parameters
        // - Returns: List of float array representing the embeddings
        List<float[]> embeddings = ...;
        return embeddings;
    }
}
```
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
