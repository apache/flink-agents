---
title: Configuration
weight: 2
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

## How to configure Flink Agents

There are three ways to configure Flink Agents, listed in order of **priority from high to low**:

1. **Setting via the ResourceDescriptor**
2. **Setting via the AgentsExecutionEnvironment**
3. **Setting via a Flink YAML configuration file**

The ResourceDescriptor applies to specific Resource instances, the AgentsExecutionEnvironment applies to Agents from the AgentsExecutionEnvironment, and the Flink YAML configuration file applies to all Flink Agents Jobs using the same configuration file.

{{< hint info >}}
In case of duplicate keys, the value from the highest priority will override those from lower priorities.
{{< /hint >}}

### Setting via the ResourceDescriptor

For resources like `ChatModel`, `EmbeddingModel`, and `VectorStore`, Flink Agents allows configuration during agent definition **via `ResourceDescriptor`**. This enables declarative setup of model parameters directly in the agent class.

#### Example: Defining a Math-Focused Chat Model

{{< tabs "Example: Defining a Math-Focused Chat Model" >}}

{{< tab "Python" >}}
```python
class MyAgent(Agent):
    """Example agent demonstrating the new ChatModel architecture."""

    @chat_model_connection
    @staticmethod
    def ollama_connection() -> ResourceDescriptor:
        """Defines the connection to the Ollama model service."""
        return ResourceDescriptor(
            clazz=OllamaChatModelConnection,  # Connection class
            request_timeout=10.0,
        )

    @chat_model_setup
    @staticmethod
    def math_chat_model() -> ResourceDescriptor:
        """Configures a math-focused chat model using the Ollama connection."""
        return ResourceDescriptor(
            clazz=OllamaChatModelSetup,       # Model setup class
            connection="ollama_connection",   # Reference to the connection method
            model=OLLAMA_MODEL,        				# Specific model name
            tools=["add"],                    # Add tools
            extract_reasoning=True            # Enable reasoning extraction
        )
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class MyAgent extends Agent {
    @ChatModelConnection
    public static ResourceDescriptor ollamaConnection() {
        return ResourceDescriptor.Builder.newBuilder(OllamaChatModelConnection.class.getName())
                .addInitialArgument("endpoint", "http://localhost:11434")
                .addInitialArgument("requestTimeout", 120)
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor mathChatModel() {
        return ResourceDescriptor.Builder.newBuilder(OllamaChatModelSetup.class.getName())
                .addInitialArgument("connection", "ollamaConnection")
                .addInitialArgument("model", OLLAMA_MODEL)
                .addInitialArgument("tools", List.of("add"))
                .addInitialArgument("extractReasoning", true)
                .build();
    }
}
```
{{< /tab >}}

{{< /tabs >}}

### Setting via the AgentsExecutionEnvironment

Users can explicitly modify the configuration when defining the `AgentsExecutionEnvironment`:

{{< tabs>}}
{{< tab "python" >}}

```python
# Get Flink Agents execution environment
agents_env = AgentsExecutionEnvironment.get_execution_environment()

# Get configuration object from the environment
config = agents_env.get_configuration()

# Set custom configuration using a direct key (string-based key)
# This is suitable for user-defined or non-standardized settings.
config.set_str("OpenAIChatModelSetup.model", "gpt-5")

# Set framework-level configuration using a predefined ConfigOption class
# This ensures type safety and better integration with the framework.
config.set(FlinkAgentsCoreOptions.KAFKA_BOOTSTRAP_SERVERS, "kafka-broker.example.com:9092")
```

{{< /tab >}}

{{< tab "java" >}}

```java
// Get Flink Agents execution environment
AgentsExecutionEnvironment agentsEnv = AgentsExecutionEnvironment.getExecutionEnvironment(env);

// Get configuration object
Configuration config = agentsEnv.getConfig();

// Set custom configuration using key (direct string key)
config.setInt("kafkaActionStateTopicNumPartitions", 128);  // Kafka topic partitions count

// Set framework configuration using ConfigOption (predefined option class)
config.set(AgentConfigOptions.KAFKA_BOOTSTRAP_SERVERS, "kafka-broker.example.com:9092");  // Kafka cluster address
```

{{< /tab >}}
{{< /tabs >}}

### Setting via the Flink YAML configuration file

Flink Agents allows reading configurations from the Flink YAML configuration file.

#### Format

As part of the Flink configuration file, the flink agents configuration must follow this format, with all agent-specific settings nested under the `agent` key:

```yaml
agent:
  # Agent-specific configurations
  OpenAIChatModelSetup.model: "gpt-5"
  OpenAIChatModelConnection:
    max_retries: 10
    timeout: 120.0
```

#### Loading Behavior

By default, the configuration is automatically loaded from `$FLINK_HOME/conf/config.yaml`.

**Special Condition**

In the following two cases, Flink Agents may not locate the corresponding configuration file, necessitating manual configuration. If the files are not set, no configuration files will be loaded, potentially resulting in unexpected behavior or failures.

- **For MiniCluster**:
  Manual setup is **required** — always export the environment variable before running the job:

  ```bash
  export FLINK_CONF_DIR="path/to/your/config.yaml"
  ```

  This ensures that Flink can locate and load the configuration file correctly.

- **Local mode**: When [run without flink]({{< ref "docs/operations/deployment">}}), use the `AgentsExecutionEnvironment.get_configuration()` API to load the YAML file directly:

  ```python
  config = agents_env.get_configuration("path/to/your/config.yaml")
  ```
  

## Built-in configuration options

Here is the list of all built-in configuration options.
### Execution
Here are the configuration options for Agent execution.

| Key                                 | Default                    | Type                  | Description                                                                                                                                                                                                                                                     |
|-------------------------------------|----------------------------|-----------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `baseLogDir`                        | (none)                     | String                | Base directory for file-based event logs. If not set, uses `java.io.tmpdir/flink-agents`.                                                                                                                                                                        |
| `error-handling-strategy`           | ErrorHandlingStrategy.FAIL | ErrorHandlingStrategy | Strategy for handling errors during model requests, include timeout and unexpected output schema. <br/>The option value could be:<br/> <ul><li>`ErrorHandlingStrategy.FAIL`</li> <li>`ErrorHandlingStrategy.RETRY`</li> <li>`ErrorHandlingStrategy.IGNORE`</li> |
| `max-retries`             | 3                          | int                   | Number of retries when using `ErrorHandlingStrategy.RETRY`.                                                                                                                                                                                                     |

### ChatModel

{{< hint info >}}
ChatModel's built-in configuration options work only with the ChatModel defined in Python.
{{< /hint >}}

#### Ollama

| Key                                   | Default              | Type     | Description                                                                 |
|---------------------------------------|----------------------|----------|-----------------------------------------------------------------------------|
| OllamaChatModelConnection.base_url    | "http://localhost:11434" | String   | Base url the model is hosted under.                                         |
| OllamaChatModelConnection.request_timeout | 30.0              | Double   | The timeout for making http request to Ollama API server.                   |
| OllamaChatModelSetup.connection       | (none)               | String   | Name of the referenced connection.                                          |
| OllamaChatModelSetup.model            | (none)               | String   | Model name to use.                                                          |
| OllamaChatModelSetup.temperature      | 0.75                | Double   | The temperature to use for sampling.                                        |
| OllamaChatModelSetup.num_ctx          | 2048                | Integer  | The maximum number of context tokens for the model.                         |
| OllamaChatModelSetup.keep_alive       | "5m"                | String   | Controls how long the model will stay loaded into memory following the request (default: 5m) |
| OllamaChatModelSetup.extract_reasoning| true                | Boolean  | If true, extracts content within `

#### OpenAI

| Key                                   | Default              | Type     | Description                                                                 |
|---------------------------------------|----------------------|----------|-----------------------------------------------------------------------------|
| OpenAIChatModelConnection.api_key     | (none)               | String   | The OpenAI API key.                                                         |
| OpenAIChatModelConnection.api_base_url| (none)               | String   | The base URL for OpenAI API.                                                |
| OpenAIChatModelConnection.max_retries | 3                    | Integer  | The maximum number of API retries.                                          |
| OpenAIChatModelConnection.timeout     | 60.0                 | Double   | The timeout, in seconds, for API requests.                                  |
| OpenAIChatModelConnection.reuse_client| true                 | Boolean  | Reuse the OpenAI client between requests. When doing anything with large volumes of async API calls, setting this to false can improve stability. |
| OpenAIChatModelSetup.connection       | (none)               | String   | Name of the referenced connection.                                          |
| OpenAIChatModelSetup.model            | "gpt-3.5-turbo"      | String   | The OpenAI model to use.                                                    |
| OpenAIChatModelSetup.temperature      | 0.1                  | Double   | The temperature to use during generation.                                   |
| OpenAIChatModelSetup.max_tokens       | (none)               | Integer  | Optional: The maximum number of tokens to generate.                         |
| OpenAIChatModelSetup.logprobs         | false                | Boolean  | Whether to return logprobs per token.                                       |
| OpenAIChatModelSetup.top_logprobs     | 0                    | Integer  | The number of top token log probs to return.                                |
| OpenAIChatModelSetup.strict           | false                | Boolean  | Whether to use strict mode for invoking tools/using schemas.                |

#### Tongyi

| Key                                  | Default                                      | Type    | Description                                                                 |
|--------------------------------------|----------------------------------------------|---------|-----------------------------------------------------------------------------|
| TongyiChatModelConnection.api_key    | From environment variable `DASHSCOPE_API_KEY` | String  | Your DashScope API key.                                                     |
| TongyiChatModelConnection.request_timeout | 60.0                                        | Double  | The timeout for making HTTP request to Tongyi API server.                   |
| TongyiChatModelSetup.connection      | (none)                                       | String  | Name of the referenced connection.                                          |
| TongyiChatModelSetup.model           | "qwen-plus"                                  | String  | Model name to use.                                                          |
| TongyiChatModelSetup.temperature     | 0.7                                          | Double  | The temperature to use for sampling.                                        |
| TongyiChatModelSetup.extract_reasoning| false                                        | Boolean | If true, extracts reasoning content from the response and stores it.        |

#### Anthropic

| Key                                   | Default                            | Type     | Description                                                                 |
|---------------------------------------|------------------------------------|----------|-----------------------------------------------------------------------------|
| AnthropicChatModelConnection.api_key  | (none)                             | String   | The Anthropic API key.                                                      |
| AnthropicChatModelConnection.max_retries | 3                                 | Integer  | The number of times to retry the API call upon failure.                     |
| AnthropicChatModelConnection.timeout  | 60.0                               | Double   | The number of seconds to wait for an API call before it times out.          |
| AnthropicChatModelSetup.connection    | (none)                             | String   | Name of the referenced connection.                                          |
| AnthropicChatModelSetup.model         | "claude-sonnet-4-20250514"        | String   | Specifies the Anthropic model to use.                                       |
| AnthropicChatModelSetup.max_tokens    | 1024                               | Integer  | Controls how long the model will stay loaded into memory following the request. |
| AnthropicChatModelSetup.temperature   | 0.1                                | Double   | Amount of randomness injected into the response.                            |

### EmbeddingModels

{{< hint info >}}
EmbeddingModels' built-in configuration options work only with the EmbeddingModels defined in Python.
{{< /hint >}}

#### Ollama

| Key                                   | Default                              | Type     | Description                                                                 |
|---------------------------------------|--------------------------------------|----------|-----------------------------------------------------------------------------|
| OllamaEmbeddingModelConnection.base_url | "http://localhost:11434"           | String   | Base URL the Ollama server is hosted under.                                 |
| OllamaEmbeddingModelConnection.request_timeout | 30.0                          | Double   | The timeout for making HTTP request to Ollama API server.                   |
| OllamaEmbeddingModelSetup.connection    | (none)                             | String   | Name of the embedding model to use.                                         |
| OllamaEmbeddingModelSetup.model         | (none)                             | String   | Model name to use.                                                          |
| OllamaEmbeddingModelSetup.truncate      | true                               | Boolean  | Controls what happens if input text exceeds model's maximum length.         |
| OllamaEmbeddingModelSetup.num_ctx       | 2048                               | Integer  | The maximum number of context tokens for the model.                         |
| OllamaEmbeddingModelSetup.keep_alive    | "5m"                               | String   | Controls how long the model will stay loaded into memory following the request. |


#### OpenAI

| Key                                   | Default                                | Type     | Description                                                                 |
|---------------------------------------|----------------------------------------|----------|-----------------------------------------------------------------------------|
| OpenAIEmbeddingModelConnection.api_key | (none)                                 | String   | OpenAI API key for authentication.                                          |
| OpenAIEmbeddingModelConnection.base_url | "https://api.openai.com/v1"            | String   | Base URL for the OpenAI API.                                                |
| OpenAIEmbeddingModelConnection.request_timeout | 30.0                             | Double   | The timeout for making HTTP requests to OpenAI API.                         |
| OpenAIEmbeddingModelConnection.max_retries | 3                                    | Integer  | Maximum number of retries for failed requests.                              |
| OpenAIEmbeddingModelConnection.organization | (none)                            | String   | Optional organization ID for API requests.                                  |
| OpenAIEmbeddingModelConnection.project    | (none)                             | String   | Optional project ID for API requests.                                       |
| OpenAIEmbeddingModelSetup.connection      | (none)                           | String   | Name of the referenced connection.                                          |
| OpenAIEmbeddingModelSetup.model           | "float"                          | String   | The format to return the embeddings in.                                     |
| OpenAIEmbeddingModelSetup.dimensions      | (none)                           | Integer  | Optional: The number of dimensions the resulting output embeddings should have. |
| OpenAIEmbeddingModelSetup.user            | (none)                           | String   | Optional: A unique identifier representing your end-user.                   |

### VectorStore

{{< hint info >}}
VectorStore built-in configuration options work only with the VectorStore defined in Python.
{{< /hint >}}

#### Chroma

| Key                                      | Default                                | Type     | Description                                                                 |
|------------------------------------------|----------------------------------------|----------|-----------------------------------------------------------------------------|
| ChromaVectorStore.persist_directory      | (none)                                 | String   | Directory for persistent storage. If None, uses in-memory client.           |
| ChromaVectorStore.host                   | (none)                                 | String   | Host for ChromaDB server connection.                                        |
| ChromaVectorStore.port                   | 8000                                   | Integer  | Port for ChromaDB server connection.                                        |
| ChromaVectorStore.api_key                | (none)                                 | String   | API key for Chroma Cloud connection.                                        |
| ChromaVectorStore.tenant                 | "default_tenant"                       | String   | ChromaDB tenant for multi-tenancy support.                                  |
| ChromaVectorStore.database               | "default_database"                     | String   | ChromaDB database name.                                                     |
| ChromaVectorStore.collection             | "flink_agents_chroma_collection"       | String   | Name of the ChromaDB collection to use.                                     |
| ChromaVectorStore.create_collection_if_not_exists | true                             | Boolean  | Whether to create the collection if it doesn't exist.                       |

### Action State Store

#### Kafka-based Action State Store

Here are the configuration options for Kafka-based Action State Store.

| Key                                 | Default                  | Type    | Description                                                                 |
|-------------------------------------|--------------------------|---------|-----------------------------------------------------------------------------|
| `actionStateStoreBackend`           | (none)                   | String  | The config parameter specifies the backend for action state store.          |
| `kafkaBootstrapServers`             | "localhost:9092"         | String  | The config parameter specifies the Kafka bootstrap server.                  |
| `kafkaActionStateTopic`             | (none)                   | String  | The config parameter specifies the Kafka topic for action state.            |
| `kafkaActionStateTopicNumPartitions`| 64                       | Integer | The config parameter specifies the number of partitions for the Kafka action state topic. |
| `kafkaActionStateTopicReplicationFactor` | 1                     | Integer | The config parameter specifies the replication factor for the Kafka action state topic. |
