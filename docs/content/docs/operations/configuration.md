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
env = StreamExecutionEnvironment.get_execution_environment()
agents_env = AgentsExecutionEnvironment.get_execution_environment(env)

# Get configuration object from the environment
config = agents_env.get_config()

# Set custom configuration using a direct key (string-based key)
# This is suitable for user-defined or non-standardized settings.
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

// Set custom configuration using key (direct string key)
config.setInt("kafkaActionStateTopicNumPartitions", 128);  // Kafka topic partitions count

// Set the list of event listeners
config.set(AgentConfigOptions.EVENT_LISTENERS, List.of(MyCustomListener.class.getName()));

// Set framework configuration using ConfigOption (predefined option class)
config.set(AgentExecutionOptions.MAX_RETRIES, 3);
```

{{< /tab >}}
{{< /tabs >}}

### Setting via the Flink YAML configuration file

The former `ErrorHandlingStrategy` API and `error-handling-strategy` option have been removed. To enable retries, set `max-retries` to a positive number (for example, 3 to retain the former `RETRY` mode's default budget). Its default is now 0, preserving the previous default behavior of making no retries. Terminal Chat failures are reported through failed `ChatResponseEvent` events instead of a `FAIL` or `IGNORE` policy.

Flink Agents allows reading configurations from the Flink YAML configuration file.

#### Format

As part of the Flink configuration file, the flink agents configuration must follow this format, with all agent-specific settings nested under the `agent` key:

```yaml
agent:
  # Agent-specific configurations
  max-retries: 3
  chat:
    async: true
```

#### Loading Behavior

By default, the configuration is automatically loaded from `$FLINK_HOME/conf/config.yaml`.

**Special Condition**

In the following case, Flink Agents may not locate the corresponding configuration file, necessitating manual configuration. If the file is not set, no configuration file will be loaded, potentially resulting in unexpected behavior or failures.

- **For MiniCluster**:
  Manual setup is **required** — always export the environment variable before running the job:

  ```bash
  export FLINK_CONF_DIR="path/to/your/config.yaml"
  ```

  This ensures that Flink can locate and load the configuration file correctly.

## Built-in configuration options

### Core Options
Here is the list of all built-in core configuration options.

| Key                       | Default                    | Type                  | Description                                                                                                                                                                                                                                                     |
|---------------------------|----------------------------|-----------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `event-listeners`         | none                       | `List<String>`        | The list of event listener class names. Each class must implement the EventListener interface and provide a public no-argument constructor. {{< hint warning >}} Note: Currently, custom event listeners are only supported in Java. {{< /hint >}} |
| `action.trigger-condition.evaluate-failure-strategy` | `WARN_AND_SKIP` | ConditionEvaluationFailureStrategy | Handles event-time failures while preparing variables for or evaluating a compiled condition, including a dynamic non-Boolean result. <br/><ul><li>`WARN_AND_SKIP` (default): log a warning, treat that condition as false, and continue with later OR conditions.</li><li>`FAIL`: throw `IllegalStateException` and fail the Flink task; recovery follows the job's restart configuration.</li></ul> Plan-validation failures and runtime compilation or static type-check failures occur during initialization and are not handled by this option. |
| `max-retries`             | 0                          | int                   | Number of additional attempts per model call, including routing judge calls. Defaults to 0 (no retries).                                                                                                                                                                                                     |
| `retry-wait-interval`     | 1                          | int                   | Base wait interval in seconds between retries. Uses exponential backoff: the actual wait time for the Nth retry is `retry-wait-interval * 2^(N-1)` seconds. For example, with default 1s, waits are 1s, 2s, 4s, etc. Retry count and total wait time are reported in `ChatResponseEvent` and recorded as metrics (`retryCount`, `retryWaitSec`) under the configured ChatModel resource name. |
| `chat.async`              | true                       | boolean               | Whether chat asynchronously for built-in chat action.                                                                                                                                                                                                           |
| `tool-call.async`         | true                       | boolean               | Whether the built-in tool-call action runs each tool via durable async execution.                                                                                                                                                                               |
| `async.batch.parallelism` | os cpu count | int | Maximum concurrent calls in one `ctx.gather(...)` batch, including ordinary calls, durable calls, and built-in async tool calls. `1` runs calls sequentially. Actual concurrency is also limited by `num-async-threads`; lower this per-batch limit to leave capacity for other calls on the same subtask. Java and Python support batch concurrency on all supported JDK versions. |
| `async.batch.timeout.ms` | -1 (disabled) | long (milliseconds) | Overall timeout for one async batch, including ordinary calls, durable calls, and built-in async tool calls. Non-positive values disable it. Completed calls keep their outcomes; unfinished calls receive timeout failures. For durable calls, started calls are recorded as failures while unstarted calls remain pending for recovery. Running callbacks may continue after timeout, occupy workers, and produce external effects, so also configure timeouts in blocking clients. |
| `rag.async`               | true                       | boolean               | Whether retrieve context asynchronously for built-in context retrieval action.                                                                                                                                                                                  |
| `num-async-threads`       | os cpu count * 2           | int                   | Size of the async worker pool shared by all keys on an operator subtask. On JDK 21+ and in Python, chat, RAG, and batches use this pool. On older JDKs, Java uses a separate batch pool of this size in addition to its Action workers. Default `async.batch.parallelism` is `os cpu count`, so one full batch can occupy up to half of this pool; multiple keys running large batches can saturate it. |
| `parallel-execution.enabled` | true                    | boolean               | Whether plans with only **Java Actions** run Actions concurrently on the parallel execution engine when the continuation-based engine is unavailable (**JDK < 21**). Plans declaring Python Resources additionally require a Flink version supporting [cross-language async calls]({{< ref "docs/development/create-agent/actions#cross-language-async-requirements" >}}). Same-key input records commit in input order, with exactly-once checkpoint semantics. Set to `false` to use the serial engine. Ignored when the JDK 21 coroutine engine is available, and for plans containing **Python** actions, which never use the parallel engine. {{< hint warning >}}**Experimental.** If you observe unexpected behavior on JDK < 21, set this option to `false` and report an issue.{{< /hint >}} |
| `max-in-flight-input-records` | 100                   | int                   | Maximum number of input records that may be in flight concurrently. Only enforced by the **JDK < 21** parallel execution engine for plans with only **Java Actions**; the JDK 21 coroutine engine and plans containing **Python** actions ignore it. Every admitted record consumes one unit of budget; at the cap, admission of further records blocks until an in-flight record retires. |
| `job-identifier`          | none                       | String                | The unique identifier of job, remaining consistent after restoring from a savepoint. If not set, uses flink job id.                                                                                                                                             |
| `short-term-memory.state-ttl.ms` | 0                    | long                  | Time-to-live for short-term memory state in milliseconds. Set to a value greater than 0 to enable TTL; 0 disables it.                                                                                                                                           |
| `short-term-memory.state-ttl.update-type` | `ON_READ_AND_WRITE` | ShortTermMemoryTtlUpdate | Update policy for short-term memory TTL. Only applies when `short-term-memory.state-ttl.ms` is greater than 0. Valid values: `ON_CREATE_AND_WRITE`, `ON_READ_AND_WRITE`. An enabled run-begin memory snapshot also refreshes TTL for entries it reads under `ON_READ_AND_WRITE`. |
| `short-term-memory.state-ttl.visibility` | `NEVER_RETURN_EXPIRED` | ShortTermMemoryTtlVisibility | Visibility policy for expired short-term memory state. Only applies when `short-term-memory.state-ttl.ms` is greater than 0. Valid values: `NEVER_RETURN_EXPIRED`, `RETURN_EXPIRED_IF_NOT_CLEANED_UP`.                                                        |

### Trace Log Options

Trace Log records Events, Action executions, and calls made within Actions. See [Monitoring]({{< ref "docs/operations/monitoring#trace-log" >}}) for output formats and the Trace Tree reader.

| Key | Default | Type | Description |
|-----|---------|------|-------------|
| `trace-log.output-type` | `SLF4J` | LoggerType | Trace log output: `SLF4J` writes JSON through a dedicated SLF4J logger, visible in Flink's Web UI Logs tab; `FILE` writes per-subtask files. A non-empty `trace-log.base-dir` forces `FILE`, even when `SLF4J` is selected. |
| `trace-log.base-dir` | (none) | String | Directory for file output; a non-empty value forces `FILE`. When file output is selected without a directory, uses `java.io.tmpdir/flink-agents`. |
| `trace-log.pretty-print` | false | boolean | Format JSON over multiple lines. When false, each record occupies one line (JSONL). |
| `trace-log.targets` | `[{scope: EVENT_ONLY, level: STANDARD}]` | List of objects | Match records and control the recording level. Each target uses `scope` and optional `level`; see [Trace log targets](#trace-log-targets). |
| `trace-log.standard.max-string-length` | 2000 | int | Maximum attribute string length at `STANDARD` level. No effect at `VERBOSE`. |
| `trace-log.standard.max-array-elements` | 20 | int | Maximum attribute array length at `STANDARD` level. No effect at `VERBOSE`. |
| `trace-log.standard.max-depth` | 5 | int | Maximum attribute nesting depth at `STANDARD` level. Deeper objects are summarized. No effect at `VERBOSE`. |

#### Trace log targets

Every target combines a `scope` with an optional `level`. Omitting `targets` records only Events at `STANDARD`. An explicit list replaces that default; an empty list records nothing.

For example, this configuration records all entities at `STANDARD`, with full attributes for the `search` Tool:

```yaml
agent:
  trace-log:
    targets:
      - scope: ALL
        level: STANDARD
      - scope: {tool: search}
        level: VERBOSE
    standard:
      max-string-length: 2000
      max-array-elements: 20
      max-depth: 5
    output-type: FILE
    base-dir: /tmp/flink-agent-logs
    pretty-print: false
```

Java and Python can configure targets with `TraceLogTarget` through the same `TRACE_LOG_TARGETS` option:

{{< tabs >}}
{{< tab "python" >}}

```python
from flink_agents.api.core_options import AgentConfigOptions
from flink_agents.api.trace_log import TraceLogLevel, TraceLogTarget

agents_env.get_config().set(
    AgentConfigOptions.TRACE_LOG_TARGETS,
    [
        TraceLogTarget.all(TraceLogLevel.STANDARD),
        TraceLogTarget.tool("search", TraceLogLevel.VERBOSE),
    ],
)
```

{{< /tab >}}
{{< tab "java" >}}

```java
import java.util.List;
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.logger.TraceLogLevel;
import org.apache.flink.agents.api.logger.TraceLogTarget;

agentsEnv.getConfig().set(
        AgentConfigOptions.TRACE_LOG_TARGETS,
        List.of(
                TraceLogTarget.all(TraceLogLevel.STANDARD),
                TraceLogTarget.tool("search", TraceLogLevel.VERBOSE)));
```

{{< /tab >}}
{{< /tabs >}}

`TraceLogTarget` provides `all()` and whole-type factories `events()`, `actions()`, `tools()`, `llms()`, `parsers()`, and `subagents()`. The corresponding singular factories accept a name, such as `event("OrderCreated")` or `tool("search")`. All factories accept an optional `TraceLogLevel`. Omitting it preserves inheritance: `tool("search")` inherits the level of a matching Tool type target or `ALL`, as described below. Targets are immutable.

In YAML, `scope` accepts `EVENT_ONLY` (match Events), `ALL` (match all entities), or a selector object. Use one of the keys `event`, `action`, `tool`, `llm`, `parser`, or `subagent` with an entity name, for example `{event: OrderCreated}`. The value `"*"` selects that whole type: `{event: "*"}` is equivalent to `EVENT_ONLY`.

The generic form `{entityType: custom, entityName: name}` also supports custom entity types reported through `ExecutionReporter`; omit `entityName` to select the whole type. Use `TraceLogTarget.entity(...)` for the same selection in Java or Python. For example, `TraceLogTarget.entity("retriever", "catalog")` corresponds to `scope: {entityType: retriever, entityName: catalog}`. Each selector object must contain exactly one category key, or the generic `entityType` and optional `entityName` fields. Multiple category keys and mixing a category key with the generic fields are rejected.

Direct model or tool calls inside an Action are not automatically recorded as separate calls, even with `scope: ALL`.

`entityType` identifies the kind of object to observe. For `entityName`, use the logical name from your Agent's code or configuration. Trace Log records and target selectors use the same name. Common examples are:

| `entityType` | What to use as `entityName` | Example |
|--------------|----------------------------|---------|
| `event` | The Event type used when emitting or subscribing to Events. | `OrderCreated` |
| `action` | The name used to declare the Action in the Agent. | `create_order` |
| `llm` | The registered ChatModel Resource name used by the call. | `primary_model` |

For example, `scope: {llm: primary_model}` selects calls to the Resource named `primary_model`. Its configured model or deployment is recorded separately in `entityMetadata.model`.

Entity names match exactly. A name ending in `.*` matches its namespace and descendants: `{event: "com.foo.*"}` matches `com.foo` and `com.foo.Event`, but not `com.foobar.Event`. Other wildcard forms are unsupported. A category selector with `"*"`, or a generic selector without `entityName`, matches the whole type.

`level` accepts `OFF`, `STANDARD`, and `VERBOSE`. `OFF` writes no record. `STANDARD` truncates or summarizes attributes using the `standard` limits; `VERBOSE` retains full attributes. Both recording settings preserve all identity and relationship fields, entity metadata, timestamps, statuses, and problem categories. Media payload sanitization applies at both recording settings. See [Recording Level]({{< ref "docs/operations/monitoring#recording-level" >}}) for the truncation format and media handling. In YAML, write `level: "OFF"` with quotes so Python does not interpret it as a boolean.

For each entity, matching scopes are considered from most to least specific:

| Order | Matching scope |
|-------|----------------|
| 1 | Exact entity name within its type |
| 2 | Namespace prefixes within its type, from longest to shortest |
| 3 | Entity type (`EVENT_ONLY` is the `event` type scope) |
| 4 | `ALL` |

The first explicitly specified `level` in this chain applies. An omitted `level` inherits from the next matching scope and does not override it. If at least one scope matches but no matching scope specifies `level`, the entity uses `STANDARD`. If no scope matches, no record is written. Target order does not affect the result.

An explicit `OFF` stops inheritance and suppresses recording. A more specific scope can enable recording by explicitly setting `STANDARD` or `VERBOSE`; omitting `level` continues to inherit `OFF`.

For example, a Tool type target at `VERBOSE` supplies the level to matching namespace and exact-name targets that omit it. A matching namespace target at `OFF` suppresses an exact-name target that omits `level`, while an exact-name target with explicit `STANDARD` or `VERBOSE` enables that entity. `EVENT_ONLY` applies only to Events; its level does not affect a Tool target.

Duplicate targets for the same scope merge an omitted level with an explicit level and accept repeated identical explicit levels. Different explicit levels for the same scope are rejected when logging starts, regardless of target order. This includes equivalent targets expressed as `EVENT_ONLY`, `{event: "*"}`, or `{entityType: event}`. For example, `EVENT_ONLY` without `level` and `{event: "*"}` with `VERBOSE` merge into one Event type target at `VERBOSE`. Typed targets, mapping objects, and YAML follow the same validation, matching, and inheritance rules.

Selecting a Tool does not automatically select its parent Action; recorded relationships retain their IDs even when related records are omitted.

An `ALL` preset with `level: "OFF"` disables its default range, while a more specific target with explicit `STANDARD` or `VERBOSE` can enable recording. A preset is unnecessary when selecting only one entity type. To record only Tools:

```yaml
agent:
  trace-log:
    targets:
      - scope: {tool: "*"}
        level: VERBOSE
```

To record everything except one Tool, use a local `OFF` target:

```yaml
agent:
  trace-log:
    targets:
      - scope: ALL
        level: STANDARD
      - scope: {tool: search}
        level: "OFF"
```

The old `event-log.*`, `eventLoggerType`, `baseLogDir`, and `prettyPrint` configuration keys are unsupported and rejected at startup. Migrate selection and verbosity to `targets`, truncation limits to `trace-log.standard.*`, and output settings to `trace-log.output-type`, `trace-log.base-dir`, and `trace-log.pretty-print`.

### Memory Event Options

The eight `memory.generate-event*` options have no raw `ConfigOption` default. When a sub-key and the master switch are both unset, the runtime uses the effective default shown below. See [Memory Observability]({{< ref "docs/development/memory/memory_events" >}}) for resolution order, event payloads, and subscription examples.

| Key | Raw default | Effective default | Type | Description |
|-----|-------------|-------------------|------|-------------|
| `memory.generate-event` | unset | per-operation defaults | boolean | Master fallback for unset operation-specific switches. |
| `memory.generate-event.short-term-write` | unset | on | boolean | Emit short-term memory write events. |
| `memory.generate-event.short-term-read` | unset | off | boolean | Emit short-term memory read events. |
| `memory.generate-event.sensory-write` | unset | on | boolean | Emit sensory memory write events. |
| `memory.generate-event.sensory-read` | unset | off | boolean | Emit sensory memory read events. |
| `memory.generate-event.long-term-update` | unset | on | boolean | Emit long-term memory add/delete events. |
| `memory.generate-event.long-term-get` | unset | on | boolean | Emit long-term memory get events. |
| `memory.generate-event.long-term-search` | unset | on | boolean | Emit long-term memory search events. |
| `agent-run.begin-event` | false | off | boolean | Opt in to the agent-run begin event. Independent of the memory-event master switch. |

### Action State Store

#### Common

| Key                          | Default          | Type    | Description                                                                              |
|------------------------------|------------------|---------|------------------------------------------------------------------------------------------|
| `actionStateStoreBackend`    | (none)           | String  | The backend for action state store. Supported values: `"kafka"`, `"fluss"`.              |

#### Kafka-based Action State Store

Here are the configuration options for Kafka-based Action State Store.

| Key                                 | Default                  | Type    | Description                                                                 |
|-------------------------------------|--------------------------|---------|-----------------------------------------------------------------------------|
| `kafkaBootstrapServers`             | "localhost:9092"         | String  | The config parameter specifies the Kafka bootstrap server.                  |
| `kafkaActionStateTopic`             | (none)                   | String  | The Kafka topic for action state. Dedicate it to one logical Flink Agents operator, shared by that operator's subtasks. |
| `kafkaActionStateTopicNumPartitions`| 64                       | Integer | The config parameter specifies the number of partitions for the Kafka action state topic. |
| `kafkaActionStateTopicReplicationFactor` | 1                     | Integer | The config parameter specifies the replication factor for the Kafka action state topic. |
| `kafkaActionStateTombstoneEnabled`  | false                    | Boolean | Whether pruning sends tombstone records so log compaction can reclaim pruned keys on a compacted action-state topic. Off by default: pruning does not invalidate older restore points, but the topic continues to grow. When enabled, the checkpoint whose completion triggers pruning remains usable, but restoring an earlier checkpoint or savepoint may replay later tombstones and re-execute already completed actions. Enable only if the job never restores from earlier checkpoints or savepoints, or if re-executing actions is acceptable. |
| `kafkaActionStateCleanupControlTopic` | (none)                 | String  | Separate, single-partition topic containing committed checkpoint-aligned cleanup boundaries. It must use `cleanup.policy=compact` without delete retention, differ from the action-state topic, and be dedicated to the same job recovery history. Setting it enables boundary enforcement during recovery. It cannot be combined with `kafkaActionStateTombstoneEnabled`. |

##### Checkpoint-aligned Kafka cleanup

Checkpoint-aligned cleanup is an explicit administrative operation. `KafkaActionStateCleanupTool` provides the same command-line workflow for Java and Python jobs. Run it with the Flink Agents runtime JAR and the matching Flink State Processor API JAR on the classpath:

```text
plan --checkpoint PATH (--operator-uid UID | --operator-uid-hash HASH) --output FILE
apply --plan FILE --bootstrap-servers SERVERS --control-topic TOPIC [--replication-factor N]
```

The `plan` command reads all recovery markers from the selected checkpoint or savepoint through Flink's State Processor API, creates a deterministic plan using the earliest required offset per partition, and refuses to overwrite an existing output file. Legacy map markers can still be restored by a job, but cannot authorize deletion because they do not identify the physical Kafka topic. The `apply` command verifies the content-derived plan ID before contacting Kafka. Plan files must contain exactly one JSON document; trailing content is rejected. The optional `--replication-factor` controls replication when creating the control topic, defaults to `1`, and must be between `1` and `32767`.

Review and retain the plan's deterministic JSON before applying it. The coordinator verifies that the selected offsets are still available, writes `COMMITTED` before calling Kafka `deleteRecords`, verifies every resulting beginning offset, and then writes `APPLIED`. Reapplying the same plan retries an interrupted committed operation without changing its boundary. Set `kafkaActionStateCleanupControlTopic` on the job only after the first plan is committed; recovery requires the configured topic to exist and contain a committed boundary, and fails closed if the topic is missing or empty.

Physical prefix cleanup requires the action-state data topic to use `cleanup.policy=compact,delete`, `retention.ms=-1`, and `retention.bytes=-1`. The store uses those settings when it creates a new topic. Before applying cleanup to an existing topic, alter it to those values. The `delete` policy enables Kafka's `deleteRecords` API, while both retention limits remain disabled so Kafka cannot independently retire records that a supported checkpoint still needs. Apply validates these effective settings before writing `COMMITTED` and rechecks them immediately before and after deletion.

For the first cleanup on an existing job, stop the job, create and apply the plan from the recovery point that will become the oldest supported one, and restart from that same point with `kafkaActionStateCleanupControlTopic` configured. This avoids a failover window in which the old running job does not yet know the committed boundary. Once the job is running with boundary enforcement enabled, later forward-only plans may be applied while it runs. Run only one `apply` operation at a time; concurrent incomparable plans fail closed and require operator intervention. Do not recreate the action-state topic or change its partitions while an apply operation is running: the coordinator checks the topic ID and partition set before and after deletion, but Kafka addresses `deleteRecords` by topic name and cannot atomically fence topic lifecycle changes.

The action-state topic and control topic must be dedicated to one job's recovery history. Once any boundary has been committed, every future run and restore of that history must retain the same control-topic configuration; omitting or changing it removes logical boundary enforcement. The boundary can move only forward. A checkpoint whose marker is below the committed boundary is rejected even if Kafka has not finished physical deletion.

Per-key tombstones and checkpoint-aligned cleanup are mutually exclusive. Tombstones are not tied to the selected recovery boundary and could invalidate a checkpoint that checkpoint-aligned cleanup promises to retain.

To migrate a job that previously emitted tombstones, first stop it cleanly so its Kafka producer closes and all accepted tombstone sends finish. Restart from the newest recovery point that is valid under the tombstone mode's existing recovery trade-off, with `kafkaActionStateTombstoneEnabled=false` and no cleanup control topic configured. After that attempt completes a new checkpoint or savepoint, stop it again, create and apply the first cleanup plan from that new recovery point, and restart from the same point with `kafkaActionStateCleanupControlTopic` configured. The new marker is after every old tombstone; selecting an older recovery point cannot provide the same guarantee because replay may still encounter those tombstones.

#### Fluss-based Action State Store

Here are the configuration options for Fluss-based Action State Store.

| Key                          | Default          | Type    | Description                                                                              |
|------------------------------|------------------|---------|------------------------------------------------------------------------------------------|
| `flussBootstrapServers`      | "localhost:9123" | String  | The Fluss bootstrap servers address.                                                     |
| `flussActionStateDatabase`   | "flink_agents"   | String  | The Fluss database name for storing action state.                                        |
| `flussActionStateTable`      | (none)           | String  | The Fluss table for action state. Dedicate it to one logical Flink Agents operator, shared by that operator's subtasks. |
| `flussActionStateTableBuckets` | 64             | Integer | The number of buckets for the Fluss action state table.                                  |
| `flussSecurityProtocol`      | "PLAINTEXT"      | String  | The authentication protocol for Fluss client. Valid values: `PLAINTEXT` (default, no authentication), `SASL` (SASL/PLAIN authentication). |
| `flussSaslMechanism`         | "PLAIN"          | String  | The SASL mechanism for Fluss authentication.                                             |
| `flussSaslJaasConfig`        | (none)           | String  | The JAAS configuration string for Fluss SASL authentication.                             |
| `flussSaslUsername`          | (none)           | String  | The username for Fluss SASL authentication.                                              |
| `flussSaslPassword`          | (none)           | String  | The password for Fluss SASL authentication.                                              |
