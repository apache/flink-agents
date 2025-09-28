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

There are three ways to configure Flink Agents:

1. **Explicit Settings**
2. **YAML Configuration File**
3. **Configuration During Build Agent Time**

{{< hint info >}}
The priority of configuration sources, from highest to lowest, is: **Explicit Settings**, followed by **YAML Configuration File**, and finally **Configuration During Build Agent Time**. In case of duplicate keys, the value from the highest-priority source will override those from lower-priority ones.
{{< /hint >}}

### Explicit Settings

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

### YAML Configuration File

Flink Agents reads configuration from a YAML file structured under the **`agent:`** root key. This file is typically used when submitting jobs to a Flink cluster or running locally with a custom configuration.

#### Configuration Structure

The YAML file must follow this format, with all agent-specific settings nested under the `agent:` key:

```yaml
agent:
  # Agent-specific configurations
  OpenAIChatModelSetup.model: "gpt-5"
  OpenAIChatModelConnection.max_retries: 10
```

{{< hint info >}}
- The `agent:` key is required as the root namespace for Flink Agents configurations.
- This differs from standard Flink configurations (which use `flink-conf.yaml` without a root key).
{{< /hint >}}


#### Loading Behavior

**Local Mode**

Use the `AgentsExecutionEnvironment.get_configuration()` API to load a custom YAML file directly:

```python
config = agents_env.get_configuration("path/to/your/config.yaml")
```

**MiniCluster Mode / Cluster Submission**

Flink Agents automatically loads configurations from `$FLINK_CONF_DIR/conf.yaml`, ensuring the file includes the `agent:` section.

- **For MiniCluster**:
  Manual setup is **required** — always export the environment variable before running the job:

  ```bash
  export FLINK_CONF_DIR="path/to/your/config.yaml"
  ```

  This ensures the configuration directory is explicitly defined.

- **For Cluster Submission**:
  The configuration is automatically loaded from `$FLINK_HOME/conf/flink-conf.yaml` (if the `agent:` section exists).

### Configuration During Build Agent Time

How to configure during the build agent time: please refer to the documentation under "How to Build an Agents," specifically the document titled ["Workflow Agent"]({{< ref "docs/development/workflow_agent" >}}).

## Built-in configuration options

Here is the list of all built-in configuration options.

### ChatModel

{{< hint info >}}
ChatModel's built-in configuration options work only with the ChatModel defined in Python.
{{< /hint >}}

#### Ollama

<table class="configuration table table-bordered">
    <thead>
        <tr>
            <th class="text-left" style="width: 20%">Key</th>
            <th class="text-left" style="width: 15%">Default</th>
            <th class="text-left" style="width: 10%">Type</th>
            <th class="text-left" style="width: 55%">Description</th>
        </tr>
    </thead>
    <tbody>
        <tr>
            <td><h5>OllamaChatModelConnection.base_url</h5></td>
            <td style="word-wrap: break-word;">"http://localhost:11434"</td>
            <td>String</td>
            <td>Base url the model is hosted under.</td>
        </tr>
        <tr>
            <td><h5>OllamaChatModelConnection.request_timeout</h5></td>
            <td style="word-wrap: break-word;">30.0</td>
            <td>Double</td>
            <td>The timeout for making http request to Ollama API server.</td>
        </tr>
      	<tr>
            <td><h5>OllamaChatModelSetup.connection</h5></td>
            <td style="word-wrap: break-word;">(none)</td>
            <td>String</td>
            <td>Name of the referenced connection.</td>
        </tr>
        <tr>
            <td><h5>OllamaChatModelSetup.model</h5></td>
            <td style="word-wrap: break-word;">(none)</td>
            <td>String</td>
            <td>Model name to use.</td>
        </tr>
        <tr>
            <td><h5>OllamaChatModelSetup.temperature</h5></td>
            <td style="word-wrap: break-word;">0.75</td>
            <td>Double</td>
            <td>The temperature to use for sampling.</td>
        </tr>
      	<tr>
            <td><h5>OllamaChatModelSetup.num_ctx</h5></td>
            <td style="word-wrap: break-word;">2048</td>
            <td>Integer</td>
            <td>The maximum number of context tokens for the model.</td>
        </tr>
      	<tr>
            <td><h5>OllamaChatModelSetup.keep_alive</h5></td>
            <td style="word-wrap: break-word;">"5m"</td>
            <td>String</td>
            <td>Controls how long the model will stay loaded into memory following the request(default: 5m)</td>
        </tr>
      	<tr>
            <td><h5>OllamaChatModelSetup.extract_reasoning</h5></td>
            <td style="word-wrap: break-word;">true</td>
            <td>Boolean</td>
            <td>If true, extracts content within &lt;think&gt;&lt;/think&gt; tags from the response and stores it in additional_kwargs.</td>
        </tr>
    </tbody>
</table>

#### OpenAI

<table class="configuration table table-bordered">
    <thead>
        <tr>
            <th class="text-left" style="width: 20%">Key</th>
            <th class="text-left" style="width: 15%">Default</th>
            <th class="text-left" style="width: 10%">Type</th>
            <th class="text-left" style="width: 55%">Description</th>
        </tr>
    </thead>
    <tbody>
        <tr>
            <td><h5>OpenAIChatModelConnection.api_key</h5></td>
            <td style="word-wrap: break-word;">(none)</td>
            <td>String</td>
            <td>The OpenAI API key.</td>
        </tr>
        <tr>
            <td><h5>OpenAIChatModelConnection.api_base_url</h5></td>
            <td style="word-wrap: break-word;">(none)</td>
            <td>String</td>
            <td>The base URL for OpenAI API.</td>
        </tr>
        <tr>
            <td><h5>OpenAIChatModelConnection.max_retries</h5></td>
            <td style="word-wrap: break-word;">3</td>
            <td>Integer</td>
            <td>The maximum number of API retries.</td>
        </tr>
        <tr>
            <td><h5>OpenAIChatModelConnection.timeout</h5></td>
            <td style="word-wrap: break-word;">60.0</td>
            <td>Double</td>
            <td>The timeout, in seconds, for API requests.</td>
        </tr>
      	<tr>
            <td><h5>OpenAIChatModelConnection.reuse_client</h5></td>
            <td style="word-wrap: break-word;">true</td>
            <td>Boolean</td>
            <td>Reuse the OpenAI client between requests. When doing anything with large volumes of async API calls, setting this to false can improve stability.</td>
        </tr>
      	<tr>
            <td><h5>OpenAIChatModelSetup.connection</h5></td>
            <td style="word-wrap: break-word;">(none)</td>
            <td>String</td>
            <td>Name of the referenced connection.</td>
        </tr>
      	<tr>
            <td><h5>OpenAIChatModelSetup.model</h5></td>
            <td style="word-wrap: break-word;">"gpt-3.5-turbo"</td>
            <td>String</td>
            <td>The OpenAI model to use.</td>
        </tr>
      	<tr>
            <td><h5>OpenAIChatModelSetup.temperature</h5></td>
            <td style="word-wrap: break-word;">0.1</td>
          	<td>Double</td>
            <td>The temperature to use during generation.</td>
        </tr>
      	<tr>
            <td><h5>OpenAIChatModelSetup.max_tokens</h5></td>
            <td style="word-wrap: break-word;">(none)</td>
          	<td>Integer</td>
            <td>Optional: The maximum number of tokens to generate.</td>
        </tr>
      	<tr>
            <td><h5>OpenAIChatModelSetup.logprobs</h5></td>
            <td style="word-wrap: break-word;">false</td>
          	<td>Boolean</td>
            <td>Whether to return logprobs per token.</td>
        </tr>
      	<tr>
            <td><h5>OpenAIChatModelSetup.top_logprobs</h5></td>
            <td style="word-wrap: break-word;">0</td>
          	<td>Integer</td>
            <td>The number of top token log probs to return.</td>
        </tr>
      	<tr>
            <td><h5>OpenAIChatModelSetup.strict</h5></td>
            <td style="word-wrap: break-word;">false</td>
          	<td>Boolean</td>
            <td>Whether to use strict mode for invoking tools/using schemas.</td>
        </tr>
    </tbody>
</table>



#### Tongyi

<table class="configuration table table-bordered">
    <thead>
        <tr>
            <th class="text-left" style="width: 20%">Key</th>
            <th class="text-left" style="width: 15%">Default</th>
            <th class="text-left" style="width: 10%">Type</th>
            <th class="text-left" style="width: 55%">Description</th>
        </tr>
    </thead>
    <tbody>
        <tr>
          <td><h5>TongyiChatModelConnection.api_key</h5></td>
            <td style="word-wrap: break-word;">From environment variable DASHSCOPE_API_KEY</td>
            <td>String</td>
            <td>Your DashScope API key.</td>
        </tr>
        <tr>
            <td><h5>TongyiChatModelConnection.request_timeout</h5></td>
            <td style="word-wrap: break-word;">60.0</td>
            <td>Double</td>
            <td>The timeout for making http request to Tongyi API server.</td>
        </tr>
      	<tr>
            <td><h5>TongyiChatModelSetup.connection</h5></td>
            <td style="word-wrap: break-word;">(none)</td>
            <td>String</td>
            <td>Name of the referenced connection.</td>
        </tr>
        <tr>
            <td><h5>TongyiChatModelSetup.model</h5></td>
            <td style="word-wrap: break-word;">"qwen-plus"</td>
            <td>String</td>
            <td>Model name to use.</td>
        </tr>
        <tr>
            <td><h5>TongyiChatModelSetup.temperature</h5></td>
            <td style="word-wrap: break-word;">0.7</td>
            <td>Double</td>
            <td>The temperature to use for sampling.</td>
        </tr>
      	<tr>
            <td><h5>TongyiChatModelSetup.extract_reasoning</h5></td>
            <td style="word-wrap: break-word;">false</td>
            <td>Boolean</td>
            <td>If true, extracts reasoning content from the response and stores it.</td>
        </tr>
    </tbody>
</table>

#### Anthropic

<table class="configuration table table-bordered">
    <thead>
        <tr>
            <th class="text-left" style="width: 20%">Key</th>
            <th class="text-left" style="width: 15%">Default</th>
            <th class="text-left" style="width: 10%">Type</th>
            <th class="text-left" style="width: 55%">Description</th>
        </tr>
    </thead>
    <tbody>
        <tr>
            <td><h5>AnthropicChatModelConnection.api_key</h5></td>
            <td style="word-wrap: break-word;">(none)</td>
            <td>String</td>
            <td>The Anthropic API key.</td>
        </tr>
        <tr>
            <td><h5>AnthropicChatModelConnection.max_retries</h5></td>
            <td style="word-wrap: break-word;">3</td>
            <td>Integer</td>
            <td>The number of times to retry the API call upon failure.</td>
        </tr>
        <tr>
            <td><h5>AnthropicChatModelConnection.timeout</h5></td>
            <td style="word-wrap: break-word;">60.0</td>
            <td>Double</td>
            <td>The number of seconds to wait for an API call before it times out.</td>
        </tr>
        <tr>
            <td><h5>AnthropicChatModelSetup.connection</h5></td>
            <td style="word-wrap: break-word;">(none)</td>
            <td>String</td>
            <td>Name of the referenced connection.</td>
        </tr>
      	<tr>
            <td><h5>AnthropicChatModelSetup.model</h5></td>
            <td style="word-wrap: break-word;">"claude-sonnet-4-20250514"</td>
            <td>String</td>
            <td>Specifies the Anthropic model to use.</td>
        </tr>
      	<tr>
            <td><h5>AnthropicChatModelSetup.max_tokens</h5></td>
            <td style="word-wrap: break-word;">1024</td>
            <td>Integer</td>
            <td>Controls how long the model will stay loaded into memory following the request.</td>
        </tr>
      	<tr>
            <td><h5>AnthropicChatModelSetup.temperature</h5></td>
            <td style="word-wrap: break-word;">0.1</td>
            <td>Double</td>
            <td>Amount of randomness injected into the response.</td>
        </tr>
    </tbody>
</table>

### EmbeddingModels

{{< hint info >}}
EmbeddingModels' built-in configuration options work only with the EmbeddingModels defined in Python.
{{< /hint >}}

#### Ollama

<table class="configuration table table-bordered">
    <thead>
        <tr>
            <th class="text-left" style="width: 20%">Key</th>
            <th class="text-left" style="width: 15%">Default</th>
            <th class="text-left" style="width: 10%">Type</th>
            <th class="text-left" style="width: 55%">Description</th>
        </tr>
    </thead>
    <tbody>
        <tr>
            <td><h5>OllamaEmbeddingModelConnection.base_url</h5></td>
            <td style="word-wrap: break-word;">"http://localhost:11434"</td>
            <td>String</td>
            <td>Base url the Ollama server is hosted under.</td>
        </tr>
        <tr>
            <td><h5>OllamaEmbeddingModelConnection.request_timeout</h5></td>
            <td style="word-wrap: break-word;">30.0</td>
            <td>Double</td>
            <td>The timeout for making http request to Ollama API server.</td>
        </tr>
      	<tr>
            <td><h5>OllamaEmbeddingModelSetup.connection</h5></td>
            <td style="word-wrap: break-word;">(none)</td>
            <td>String</td>
            <td>Name of the embedding model to use.</td>
        </tr>
        <tr>
            <td><h5>OllamaEmbeddingModelSetup.model</h5></td>
            <td style="word-wrap: break-word;">(none)</td>
            <td>String</td>
            <td>Model name to use.</td>
        </tr>
        <tr>
            <td><h5>OllamaEmbeddingModelSetup.truncate</h5></td>
            <td style="word-wrap: break-word;">true</td>
            <td>Boolean</td>
            <td>Controls what happens if input text exceeds model's maximum length.</td>
        </tr>
      	<tr>
            <td><h5>OllamaEmbeddingModelSetup.num_ctx</h5></td>
            <td style="word-wrap: break-word;">2048</td>
            <td>Integer</td>
            <td>The maximum number of context tokens for the model.</td>
        </tr>
      	<tr>
            <td><h5>OllamaEmbeddingModelSetup.keep_alive</h5></td>
            <td style="word-wrap: break-word;">"5m"</td>
            <td>String</td>
            <td>Controls how long the model will stay loaded into memory following the request.</td>
        </tr>
    </tbody>
</table>

#### OpenAI

<table class="configuration table table-bordered">
    <thead>
        <tr>
            <th class="text-left" style="width: 20%">Key</th>
            <th class="text-left" style="width: 15%">Default</th>
            <th class="text-left" style="width: 10%">Type</th>
            <th class="text-left" style="width: 55%">Description</th>
        </tr>
    </thead>
    <tbody>
        <tr>
            <td><h5>OpenAIEmbeddingModelConnection.api_key</h5></td>
            <td style="word-wrap: break-word;">(none)</td>
            <td>String</td>
            <td>OpenAI API key for authentication.</td>
        </tr>
        <tr>
            <td><h5>OpenAIEmbeddingModelConnection.base_url</h5></td>
            <td style="word-wrap: break-word;">"https://api.openai.com/v1"</td>
            <td>String</td>
            <td>Base URL for the OpenAI API.</td>
        </tr>
        <tr>
            <td><h5>OpenAIEmbeddingModelConnection.request_timeout</h5></td>
            <td style="word-wrap: break-word;">30.0</td>
            <td>Double</td>
            <td>The timeout for making HTTP requests to OpenAI API.</td>
        </tr>
        <tr>
          <td><h5>OpenAIEmbeddingModelConnection.max_retries</h5></td>
            <td style="word-wrap: break-word;">3</td>
            <td>Integer</td>
            <td>Maximum number of retries for failed requests.</td>
        </tr>
      	<tr>
            <td><h5>OpenAIEmbeddingModelConnection.organization</h5></td>
            <td style="word-wrap: break-word;">(none)</td>
            <td>String</td>
            <td>Optional organization ID for API requests.</td>
        </tr>
      	<tr>
            <td><h5>OpenAIEmbeddingModelConnection.project</h5></td>
            <td style="word-wrap: break-word;">(none)</td>
            <td>String</td>
            <td>Optional project ID for API requests.</td>
        </tr>
      	<tr>
            <td><h5>OpenAIEmbeddingModelSetup.connection</h5></td>
            <td style="word-wrap: break-word;">(none)</td>
            <td>String</td>
            <td>Name of the referenced connection.</td>
        </tr>
      	<tr>
            <td><h5>OpenAIEmbeddingModelSetup.model</h5></td>
            <td style="word-wrap: break-word;">"float"</td>
            <td>String</td>
            <td>The format to return the embeddings in.</td>
        </tr>
      	<tr>
            <td><h5>OpenAIEmbeddingModelSetup.dimensions</h5></td>
            <td style="word-wrap: break-word;">(none)</td>
          	<td>Integer</td>
            <td>Optional: The number of dimensions the resulting output embeddings should have.</td>
        </tr>
      	<tr>
            <td><h5>OpenAIEmbeddingModelSetup.user</h5></td>
            <td style="word-wrap: break-word;">(none)</td>
          	<td>String</td>
            <td>Optional: A unique identifier representing your end-user.</td>
        </tr>
    </tbody>
</table>

### VectorStore

{{< hint info >}}
VectorStore built-in configuration options work only with the VectorStore defined in Python.
{{< /hint >}}

#### Chroma

<table class="configuration table table-bordered">
    <thead>
        <tr>
            <th class="text-left" style="width: 20%">Key</th>
            <th class="text-left" style="width: 15%">Default</th>
            <th class="text-left" style="width: 10%">Type</th>
            <th class="text-left" style="width: 55%">Description</th>
        </tr>
    </thead>
    <tbody>
        <tr>
            <td><h5>ChromaVectorStore.persist_directory</h5></td>
            <td style="word-wrap: break-word;">(none)</td>
            <td>String</td>
            <td>Directory for persistent storage. If None, uses in-memory client.</td>
        </tr>
      	<tr>
            <td><h5>ChromaVectorStore.host</h5></td>
            <td style="word-wrap: break-word;">(none)</td>
            <td>String</td>
            <td>Host for ChromaDB server connection.</td>
        </tr>
      	<tr>
            <td><h5>ChromaVectorStore.port</h5></td>
            <td style="word-wrap: break-word;">8000</td>
            <td>Integer</td>
            <td>Port for ChromaDB server connection.</td>
        </tr>
      	<tr>
            <td><h5>ChromaVectorStore.api_key</h5></td>
            <td style="word-wrap: break-word;">(none)</td>
            <td>String</td>
            <td>API key for Chroma Cloud connection.</td>
        </tr>
      	<tr>
            <td><h5>ChromaVectorStore.tenant</h5></td>
            <td style="word-wrap: break-word;">"default_tenant"</td>
            <td>String</td>
            <td>ChromaDB tenant for multi-tenancy support.</td>
        </tr>
      	<tr>
            <td><h5>ChromaVectorStore.database</h5></td>
            <td style="word-wrap: break-word;">"default_database"</td>
            <td>String</td>
            <td>ChromaDB database name.</td>
        </tr>
        <tr>
            <td><h5>ChromaVectorStore.collection</h5></td>
            <td style="word-wrap: break-word;">"flink_agents_chroma_collection"</td>
            <td>String</td>
            <td>Name of the ChromaDB collection to use.</td>
        </tr>
        <tr>
            <td><h5>ChromaVectorStore.create_collection_if_not_exists</h5></td>
            <td style="word-wrap: break-word;">true</td>
            <td>Boolean</td>
            <td>Whether to create the collection if it doesn't exist.</td>
        </tr>
    </tbody>
</table>

### State Store

#### Kafka-based State Store

Here is the configuration options for Kafka-based State Store.

<table class="configuration table table-bordered">
    <thead>
        <tr>
            <th class="text-left" style="width: 20%">Key</th>
            <th class="text-left" style="width: 15%">Default</th>
            <th class="text-left" style="width: 10%">Type</th>
            <th class="text-left" style="width: 55%">Description</th>
        </tr>
    </thead>
    <tbody>
        <tr>
            <td><h5>actionStateStoreBackend</h5></td>
            <td style="word-wrap: break-word;">(none)</td>
            <td>String</td>
            <td>The config parameter specifies the backend for action state store.</td>
        </tr>
        <tr>
            <td><h5>kafkaBootstrapServers</h5></td>
            <td style="word-wrap: break-word;">"localhost:9092"</td>
            <td>String</td>
            <td>The config parameter specifies the Kafka bootstrap server.</td>
        </tr>
        <tr>
            <td><h5>kafkaActionStateTopic</h5></td>
            <td style="word-wrap: break-word;">(none)</td>
            <td>String</td>
            <td>The config parameter specifies the Kafka topic for action state.</td>
        </tr>
        <tr>
            <td><h5>kafkaActionStateTopicNumPartitions</h5></td>
            <td style="word-wrap: break-word;">64</td>
            <td>Integer</td>
            <td>The config parameter specifies the number of partitions for the Kafka action state topic.</td>
        </tr>
        <tr>
            <td><h5>kafkaActionStateTopicReplicationFactor</h5></td>
            <td style="word-wrap: break-word;">1</td>
            <td>Integer</td>
            <td>The config parameter specifies the replication factor for the Kafka action state topic.</td>
        </tr>
    </tbody>
</table>
