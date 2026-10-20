---
title: Configuration
weight: 3
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

Set options through `AgentsExecutionEnvironment` or the `agent` section of a
Flink YAML configuration file. Values set on the environment override values loaded
from YAML.

Environment settings apply to Agents added to that environment. YAML settings apply
to jobs that load the same file.

### Setting via the AgentsExecutionEnvironment

Users can explicitly modify the configuration when defining the `AgentsExecutionEnvironment`:

{{< tabs>}}
{{< tab "python" >}}

```python
# Get Flink Agents execution environment
env = StreamExecutionEnvironment.get_execution_environment()
agents_env = AgentsExecutionEnvironment.get_execution_environment(env)

# Get configuration object from the environment
config = agents_env.get_config()

# Set an option using its string key
config.set_int("kafkaActionStateTopicNumPartitions", 128)

# Set framework-level configuration using a predefined ConfigOption class
# This ensures type safety and better integration with the framework.
config.set(AgentExecutionOptions.MAX_RETRIES, 3)
```

{{< /tab >}}

{{< tab "java" >}}

```java
// Get Flink Agents execution environment
AgentsExecutionEnvironment agentsEnv = AgentsExecutionEnvironment.getExecutionEnvironment(env);

// Get configuration object
Configuration config = agentsEnv.getConfig();

// Set an option using its string key
config.setInt("kafkaActionStateTopicNumPartitions", 128);  // Kafka topic partitions count

// Set the list of event listeners
config.set(AgentConfigOptions.EVENT_LISTENERS, List.of(MyCustomListener.class.getName()));

// Set framework configuration using ConfigOption (predefined option class)
config.set(AgentExecutionOptions.MAX_RETRIES, 3);
```

{{< /tab >}}
{{< /tabs >}}

### Setting via the Flink YAML configuration file

Flink Agents allows reading configurations from the Flink YAML configuration file.

#### Format

Nest Flink Agents settings under the `agent` key:

```yaml
agent:
  # Agent-specific configurations
  max-retries: 3
  chat:
    async: true
```

#### Loading Behavior

When creating an `AgentsExecutionEnvironment` for a Flink job, Flink Agents loads
the `agent` section from the configuration directory specified by `FLINK_CONF_DIR`.
Set this environment variable before creating the environment:

```bash
export FLINK_CONF_DIR="/path/to/flink/conf"
```

`FLINK_CONF_DIR` points to a directory. Java reads `config.yaml` from that directory.
Python reads `flink-conf.yaml` if it exists, otherwise `config.yaml`.

If `FLINK_CONF_DIR` is unset, no YAML configuration file is loaded. Configuration
through `AgentsExecutionEnvironment` still works and overrides values loaded from
the file.

## Built-in configuration options

<span id="core-options"></span>

### Observability

See [Monitoring]({{< ref "docs/operations/monitoring" >}}) for Trace Log output and
record selection, and [Memory Observability]({{< ref "docs/development/memory/memory_events" >}})
for the agent-run begin event and custom Event listeners.

<span id="trace-log-options"></span>

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `agent-run.begin-event` | false | boolean | Emit an `AgentRunBeginEvent` with a short-term memory value snapshot before running the Actions triggered by each input. Independent of `memory.generate-event`. |
| `event-listeners` | none | `List<String>` | Fully qualified Java `EventListener` class names. Each class must provide a public no-argument constructor. Python listener implementations are not supported. |
| `trace-log.base-dir` | (none) | String | Directory for Trace Log files. A nonblank value selects `FILE`, overriding `trace-log.output-type`. When unset, file output uses `${java.io.tmpdir}/flink-agents`. |
| `trace-log.output-type` | `SLF4J` | LoggerType | Trace Log destination: `SLF4J` uses the Trace Log logger; `FILE` writes one file per operator subtask. See [Output]({{< ref "docs/operations/monitoring#output" >}}) for logging configuration and file locations. |
| `trace-log.pretty-print` | false | boolean | Write multiline JSON when `true`, or compact single-line JSON when `false`. Applies to both output types. |
| `trace-log.standard.max-array-elements` | 20 | int | Maximum elements retained in each attribute array at `STANDARD` level. `0` disables this limit. No effect at `VERBOSE`. |
| `trace-log.standard.max-depth` | 5 | int | Nesting depth at which attribute objects are summarized at `STANDARD` level. `0` disables this limit. No effect at `VERBOSE`. |
| `trace-log.standard.max-string-length` | 2000 | int | Maximum characters retained in each attribute string at `STANDARD` level. `0` disables this limit. No effect at `VERBOSE`. |
| `trace-log.targets` | `[{scope: EVENT_ONLY, level: STANDARD}]` | List of objects | Select Trace Log entities and their recording levels. Each target requires `scope` and may specify `level`; see [Trace log targets](#trace-log-targets). |

#### Trace log targets

`trace-log.targets` is a list of target objects. Omitting the option records Events
at `STANDARD`. An explicit list replaces this default; an empty list records nothing.

Each target has the following fields:

- `scope` (required): `EVENT_ONLY`, `ALL`, a category selector with exactly one of
  `event`, `action`, `tool`, `llm`, `parser`, or `subagent`, or a generic selector with
  `entityType` and optional `entityName`. Do not mix category and generic fields.
  A category value of `"*"`, or a generic selector without `entityName`, selects the
  whole entity type. `EVENT_ONLY` is equivalent to `{event: "*"}`.
- `level` (optional): `OFF`, `STANDARD`, or `VERBOSE`. An omitted level inherits
  from matching scopes; it does not automatically mean `STANDARD`. In YAML, quote
  `level: "OFF"` so Python does not interpret it as a boolean.

Duplicate scopes may combine an omitted level with an explicit level or repeat the
same explicit level. Conflicting explicit levels for the same scope are rejected
when logging starts. This also applies to equivalent Event type selectors.

For YAML and API examples, entity naming, and level inheritance, see
[Recording Targets]({{< ref "docs/operations/monitoring#recording-targets" >}}).

The old `event-log.*`, `eventLoggerType`, `baseLogDir`, and `prettyPrint` keys are
unsupported and rejected at startup. Use `trace-log.targets`, `trace-log.standard.*`,
and the output options above.

### Condition handling

See [Condition Evaluation Failures]({{< ref "docs/development/create-agent/actions#condition-evaluation-failures" >}})
for trigger condition examples.

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `action.trigger-condition.evaluate-failure-strategy` | `WARN_AND_SKIP` | ConditionEvaluationFailureStrategy | Behavior when evaluating a trigger condition for an Event fails. `WARN_AND_SKIP` logs a warning and treats the condition as false; `FAIL` fails the Flink task. Initialization failures are not affected. See [Condition Evaluation Failures]({{< ref "docs/development/create-agent/actions#condition-evaluation-failures" >}}). |

### Model calls, tools, and retries

See [Chat]({{< ref "docs/development/calling-llms/chat#handle-success-and-failure" >}})
and [Decision Models]({{< ref "docs/development/decision_models#event-driven-calls" >}})
for model-call outcomes and retries, and [Context Retrieval]({{< ref "docs/development/vector-search/vector_stores#built-in-events-and-actions" >}})
for vector-store queries. See [Tool Use]({{< ref "docs/development/calling-llms/tool_use#parallel-tool-call-batches" >}})
for tool-call batches and timeout behavior, and [Execution Metrics]({{< ref "docs/operations/monitoring#execution-metrics" >}})
for retry metrics.

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `chat.async` | true | boolean | Enable durable async execution for built-in Chat model calls, including routing judge calls. `false` selects synchronous execution. |
| `decision.async` | true | boolean | Enable durable async execution for built-in Decision Model calls. `false` selects synchronous execution. |
| `max-retries` | 0 | int | Maximum retries after the initial attempt of a built-in Chat or Decision Model call, including routing judge calls. Non-positive values disable retries. |
| `rag.async` | true | boolean | Enable durable async execution for vector-store queries made by the built-in context retrieval Action. `false` selects synchronous execution. |
| `retry-wait-interval` | 1 | int | Base wait in seconds between retries controlled by `max-retries`. The wait before retry N is `retry-wait-interval * 2^(N-1)`. Non-positive values retry without waiting. |
| `tool-call.async` | true | boolean | Enable durable async execution for ordinary tools handled by the built-in Tool Call Action. With `false`, these calls run synchronously, one at a time. See [Tool Use]({{< ref "docs/development/calling-llms/tool_use#parallel-tool-call-batches" >}}) for batch behavior. |

These async switches select how built-in calls execute. Synchronous calls still
use durable execution; saving outcomes for recovery requires an
[Action State Store]({{< ref "docs/operations/consistency#configure-an-action-state-store" >}}).
Built-in Chat and context retrieval calls, and Java Decision Model calls through
Python, fall back to synchronous execution when the runtime cannot support their
cross-language async call. See [Execution Requirements]({{< ref "docs/development/create-agent/actions#execution-requirements" >}})
for cross-language async requirements.

The `ErrorHandlingStrategy` API and `error-handling-strategy` option have been
removed. Configure retries with `max-retries`; see Chat for terminal failure handling.

### Runtime and async execution

See [Execution Requirements]({{< ref "docs/development/create-agent/actions#execution-requirements" >}})
for JDK, scheduling, and cross-language requirements. Batch limits also apply to
the built-in [parallel tool-call batches]({{< ref "docs/development/calling-llms/tool_use#parallel-tool-call-batches" >}}).

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `async.batch.parallelism` | os cpu count | int | Maximum concurrent calls in one `ctx.gather(...)` batch, including ordinary, durable, and built-in tool calls. Values of `1` or less run calls sequentially. Worker-pool capacity can further limit concurrency. |
| `async.batch.timeout.ms` | -1 (disabled) | long (milliseconds) | Overall timeout for one `ctx.gather(...)` batch, in milliseconds. Non-positive values disable it. A timeout does not guarantee that running calls stop and cannot undo their external effects. See [Tool Use]({{< ref "docs/development/calling-llms/tool_use#timeouts-and-external-effects" >}}) for built-in tool-batch behavior. |
| `job-identifier` | none | String | Stable job identifier used to scope Mem0 long-term memory. Keep an explicit value unchanged across restores. If unset, the initial Flink JobID is stored in operator state and reused when restoring a checkpoint or savepoint. |
| `max-in-flight-input-records` | 100 | int | Maximum input records in flight per Agent operator subtask on the experimental parallel engine. Must be positive. The limit is ignored by continuation-based execution and plans containing Python Actions. See [Execution Requirements]({{< ref "docs/development/create-agent/actions#execution-requirements" >}}). |
| `num-async-threads` | os cpu count * 2 | int | Worker threads per async execution pool on each Agent operator subtask. Must be positive. `async.batch.parallelism` separately limits concurrency within each batch; see [Execution Requirements]({{< ref "docs/development/create-agent/actions#execution-requirements" >}}) for scheduling behavior. |
| `parallel-execution.enabled` | true | boolean | **Experimental.** Enable the parallel engine for eligible plans with only Java Actions when continuation-based execution is unavailable. `false` selects serial execution. Ignored by the continuation engine and plans containing Python Actions. See [Execution Requirements]({{< ref "docs/development/create-agent/actions#execution-requirements" >}}) for JDK and Python Resource requirements. |

### Memory

#### Memory events {#memory-event-options}

An explicitly configured operation-specific switch overrides `memory.generate-event`.
If neither is set, the effective default below applies. See
[Memory Observability]({{< ref "docs/development/memory/memory_events" >}}) for event
payloads and subscription examples. The independent `agent-run.begin-event` switch
is listed under [Observability](#observability).

| Key | Raw default | Effective default | Type | Description |
| --- | --- | --- | --- | --- |
| `memory.generate-event` | unset | per-operation defaults | boolean | Fallback value for operation-specific memory-event switches that are unset. |
| `memory.generate-event.long-term-get` | unset | on | boolean | Emit Events describing long-term memory reads. |
| `memory.generate-event.long-term-search` | unset | on | boolean | Emit Events describing long-term memory searches. |
| `memory.generate-event.long-term-update` | unset | on | boolean | Emit Events describing long-term memory additions, updates, deletions, and memory-set deletion. |
| `memory.generate-event.sensory-read` | unset | off | boolean | Emit Events describing sensory memory reads. |
| `memory.generate-event.sensory-write` | unset | on | boolean | Emit Events describing sensory memory writes. |
| `memory.generate-event.short-term-read` | unset | off | boolean | Emit Events describing short-term memory reads. |
| `memory.generate-event.short-term-write` | unset | on | boolean | Emit Events describing short-term memory writes. |

#### Short-term memory TTL

See [Short-Term Memory TTL]({{< ref "docs/development/memory/sensory_and_short_term_memory#short-term-memory-2" >}})
for TTL behavior and setup examples.

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `short-term-memory.state-ttl.ms` | 0 | long | Short-term memory TTL in milliseconds. A positive value enables expiration; non-positive values disable it. |
| `short-term-memory.state-ttl.update-type` | `ON_READ_AND_WRITE` | ShortTermMemoryTtlUpdate | When TTL is enabled, refresh expiration on creation and writes (`ON_CREATE_AND_WRITE`), or also on reads (`ON_READ_AND_WRITE`). |
| `short-term-memory.state-ttl.visibility` | `NEVER_RETURN_EXPIRED` | ShortTermMemoryTtlVisibility | When TTL is enabled, never return expired state (`NEVER_RETURN_EXPIRED`), or allow it until cleanup (`RETURN_EXPIRED_IF_NOT_CLEANED_UP`). |

### Action consistency

<span id="action-state-store"></span>

For consistency modes, setup examples, and recovery behavior, see
[Consistency]({{< ref "docs/operations/consistency" >}}).

#### Common

| Key                          | Default          | Type    | Description                                                                              |
|------------------------------|------------------|---------|------------------------------------------------------------------------------------------|
| `actionStateStoreBackend` | (none) | String | Action State Store backend: `"kafka"` or `"fluss"` (case-insensitive). When unset, no external Action State Store is enabled. |

#### Kafka-based Action State Store

Dedicate one topic to each logical Agent operator, shared by that operator's
subtasks. Separate operators in the same job need separate topics.

The store creates the configured topic if it does not exist, and reuses an existing
topic without changing its settings. The partition-count and replication-factor
options apply only when creating a topic.

The Kafka Action State Store and its cleanup tool currently expose no configuration
for passing Kafka client SASL/SSL settings.

| Key                                 | Default                  | Type    | Description                                                                 |
|-------------------------------------|--------------------------|---------|-----------------------------------------------------------------------------|
| `kafkaActionStateCleanupControlTopic` | (none) | String | Separate topic containing committed checkpoint-aligned cleanup boundaries. When configured, recovery rejects points requiring data below the boundary. Mutually exclusive with `kafkaActionStateTombstoneEnabled=true`. See [Checkpoint-aligned Kafka cleanup]({{< ref "docs/operations/consistency#checkpoint-aligned-kafka-cleanup" >}}) for prerequisites and procedure. |
| `kafkaActionStateTombstoneEnabled` | false | Boolean | Write deletion markers for execution records covered by completed checkpoints. Enabling this may cause completed Actions to run again when restoring older checkpoints or savepoints. Mutually exclusive with `kafkaActionStateCleanupControlTopic`. See [Kafka retention and cleanup]({{< ref "docs/operations/consistency#kafka-retention-and-cleanup" >}}) for the recovery trade-off. |
| `kafkaActionStateTopic` | (none) | String | Topic storing Action state. Required when `actionStateStoreBackend` is `kafka`. |
| `kafkaActionStateTopicNumPartitions` | 64 | Integer | Number of partitions when creating the topic. Does not change an existing topic. |
| `kafkaActionStateTopicReplicationFactor` | 1 | Integer | Replication factor when creating the topic. Does not change an existing topic. |
| `kafkaBootstrapServers` | "localhost:9092" | String | Bootstrap servers for the Kafka Action State Store. |

##### Changes to an existing Kafka store

When reusing checkpoints or savepoints with this store:

- Keep the original topic and partition count. Increasing the partition count
  prevents recovery from existing snapshots and causes checkpoints of the running
  job to fail. Do not delete and recreate the topic or switch to a replacement,
  even if it has the same name and partition count.
- Retention changes and manual deletion must preserve the records needed by every
  recovery point you intend to retain. See
  [Kafka retention and cleanup]({{< ref "docs/operations/consistency" >}}#kafka-retention-and-cleanup).

#### Fluss-based Action State Store

Dedicate one table to each logical Agent operator, shared by that operator's
subtasks. Separate operators in the same job need separate tables.

The store creates the configured database and append-only log table if they do not
exist, and reuses an existing table. The bucket-count option applies only when
creating a table. SASL options apply when `flussSecurityProtocol` is `SASL`; they
are ignored for `PLAINTEXT`.

| Key                          | Default          | Type    | Description                                                                              |
|------------------------------|------------------|---------|------------------------------------------------------------------------------------------|
| `flussActionStateDatabase` | "flink_agents" | String | Database containing the Action State Store table. |
| `flussActionStateTable` | (none) | String | Append-only log table storing Action state. Required when `actionStateStoreBackend` is `fluss`. |
| `flussActionStateTableBuckets` | 64 | Integer | Number of buckets when creating the table. Does not change an existing table. |
| `flussBootstrapServers` | "localhost:9123" | String | Bootstrap servers for the Fluss Action State Store. |
| `flussSaslJaasConfig` | (none) | String | JAAS configuration for Fluss SASL authentication. Takes precedence over `flussSaslUsername` and `flussSaslPassword`. |
| `flussSaslMechanism` | "PLAIN" | String | SASL mechanism used when `flussSecurityProtocol` is `SASL`. Use `PLAIN` for SASL/PLAIN authentication. |
| `flussSaslPassword` | (none) | String | Password for SASL/PLAIN authentication. Set together with `flussSaslUsername` when `flussSaslJaasConfig` is unset. |
| `flussSaslUsername` | (none) | String | Username for SASL/PLAIN authentication. Set together with `flussSaslPassword` when `flussSaslJaasConfig` is unset. |
| `flussSecurityProtocol` | "PLAINTEXT" | String | Fluss client authentication protocol: `PLAINTEXT` (no authentication) or `SASL` (SASL/PLAIN authentication), case-insensitive. |

##### Changes to an existing Fluss store

When reusing checkpoints or savepoints with this store:

- Keep the original cluster, database, table, and bucket layout. Do not change the
  bucket count, recreate the table, or switch to a replacement table. A replacement
  with the same name and bucket count may not be detected during recovery.
- Preserve the append-only log table type and the schema used by Flink Agents,
  including column types and order.
- Log retention changes and manual truncation must preserve the records needed
  by every recovery point you intend to retain. See
  [Fluss retention]({{< ref "docs/operations/consistency" >}}#fluss-retention).
