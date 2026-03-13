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

There are two ways to configure Flink Agents, listed in order of **priority from high to low**:

1. **Setting via the AgentsExecutionEnvironment**
2. **Setting via a Flink YAML configuration file**

The AgentsExecutionEnvironment applies to Agents from the AgentsExecutionEnvironment, and the Flink YAML configuration file applies to all Flink Agents Jobs using the same configuration file.

{{< hint info >}}
In case of duplicate keys, the value from the highest priority will override those from lower priorities.
{{< /hint >}}

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
config.set_int("kafkaActionStateTopicNumPartitions", 128)

# Set framework-level configuration using a predefined ConfigOption class
# This ensures type safety and better integration with the framework.
config.set(AgentExecutionOptions.ERROR_HANDLING_STRATEGY, ErrorHandlingStrategy.RETRY)
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
config.set(AgentExecutionOptions.ERROR_HANDLING_STRATEGY, ErrorHandlingStrategy.RETRY);
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
  error-handling-strategy: retry
  chat:
    async: true
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

### Core Options
Here is the list of all built-in core configuration options.
| Key                       | Default                    | Type                  | Description                                                                                                                                                                                                                                                     |
|---------------------------|----------------------------|-----------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `baseLogDir`              | (none)                     | String                | Base directory for file-based event logs. If not set, uses `java.io.tmpdir/flink-agents`.                                                                                                                                                                       |
| `eventLogLevel`           | "STANDARD"                 | String                | Default event log level for all event types. Valid values: `OFF` (do not log), `STANDARD` (log with truncated fields), `VERBOSE` (log full content).                                                                                                            |
| `eventLogLevels`          | (none)                     | String                | Per-event-type log level overrides. Format: `EventTypeName=LEVEL,EventTypeName=LEVEL`. Example: `ChatRequestEvent=VERBOSE,ContextRetrievalRequestEvent=OFF`.                                                                                                   |
| `eventLogMaxFieldLength`  | 1024                       | Integer               | Maximum character length for string fields at `STANDARD` level. Fields exceeding this limit are truncated. `VERBOSE` level ignores this setting. Set to 0 to disable truncation.                                                                                |
| `error-handling-strategy` | ErrorHandlingStrategy.FAIL | ErrorHandlingStrategy | Strategy for handling errors during model requests, include timeout and unexpected output schema. <br/>The option value could be:<br/> <ul><li>`ErrorHandlingStrategy.FAIL`</li> <li>`ErrorHandlingStrategy.RETRY`</li> <li>`ErrorHandlingStrategy.IGNORE`</li> |
| `max-retries`             | 3                          | int                   | Number of retries when using `ErrorHandlingStrategy.RETRY`.                                                                                                                                                                                                     |
| `chat.async`              | true                       | boolean               | Whether chat asynchronously for built-in chat action.                                                                                                                                                                                                           |
| `tool-call.async`         | true                       | boolean               | Whether process tool call for built-in tool call action.                                                                                                                                                                                                        |
| `rag.async`               | true                       | boolean               | Whether retrieve context asynchronously for built-in context retrieval action.                                                                                                                                                                                  |
| `num-async-threads`       | os cpu count * 2           | int                   | The thread pool size for async executor.                                                                                                                                                                                                                        |
| `job-identifier`          | none                       | String                | The unique identifier of job, remaining consistent after restoring from a savepoint. If not set, uses flink job id.                                                                                                                                             |


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
