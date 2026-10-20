---
title: Consistency
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

When a Flink job recovers from a checkpoint, it restores its state and replays the
input received since that checkpoint. An Agent may therefore call a model, invoke
a tool, or update an external system again. The consistency mode you choose
determines what state must remain available for recovery and which work can be
reused.

## Choose a consistency mode

With exactly-once checkpointing and the required source and sink guarantees,
each input affects the job's state and output once, even if recovery processes it
again. Flink Agents calls this **exactly-once output consistency**.

Checkpoint recovery may still rerun Actions and external calls, so you can add an
external Action State Store to reuse saved Action completions and durable call
outcomes. Flink Agents calls this **exactly-once action consistency**.

| Configuration | Behavior after a failure | Requirements and trade-offs |
| --- | --- | --- |
| No checkpointing | The job restarts from the source's configured starting positions without restoring Agent state or progress from a checkpoint. | Suitable when the application can accept lost progress or repeated work. |
| Flink checkpointing | Flink restores checkpointed state and replays subsequent input. Actions and external calls may repeat, and models or queries may return different results. | Enable exactly-once checkpointing with durable checkpoint storage and the required source and sink guarantees. External calls must tolerate repetition. |
| Flink checkpointing + Action State Store | Recovery replays saved completions without rerunning Action bodies. Unfinished Actions may run again, reusing saved durable call outcomes. | Add a Kafka or Fluss Action State Store. Saving Action completions and durable call outcomes requires synchronous backend writes. Retain both checkpoints and the backend records needed for recovery. |

## Required setup

Enable exactly-once checkpointing for the Flink or PyFlink job, and use source
and sink connectors with the required
[fault tolerance guarantees](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/connectors/datastream/guarantees/).
Follow Flink's [checkpointing guide](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/dev/datastream/fault-tolerance/checkpointing/)
for configuration and checkpoint storage, and
[checkpoint recovery guide](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/ops/state/checkpoints/#resuming-from-a-retained-checkpoint)
for restore procedures. Use the Flink documentation for the version you deploy.

### Configure an Action State Store

Before building the Agent pipeline, configure Kafka or Fluss as the Action State
Store backend. Each logical Agent operator needs its **own Kafka topic or Fluss
table**, shared only by that operator's subtasks.

The Python and Java examples below use `agents_env` and `agentsEnv`, respectively,
created as described in [Integrate with Flink]({{< ref "docs/development/integrate_with_flink" >}}).
The YAML examples show the same settings in the `agent:` section of the job's
Flink configuration.

#### Kafka

Set the backend to `kafka` and provide the broker address and a dedicated topic:

{{< tabs "Kafka Action State Store" >}}
{{< tab "Python" >}}
```python
from flink_agents.api.core_options import AgentConfigOptions

config = agents_env.get_config()
config.set(AgentConfigOptions.ACTION_STATE_STORE_BACKEND, "kafka")
config.set(AgentConfigOptions.KAFKA_BOOTSTRAP_SERVERS, "kafka:9092")
config.set(AgentConfigOptions.KAFKA_ACTION_STATE_TOPIC, "orders-agent-action-state")
```
{{< /tab >}}
{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.configuration.Configuration;

Configuration config = agentsEnv.getConfig();
config.set(AgentConfigOptions.ACTION_STATE_STORE_BACKEND, "kafka");
config.set(AgentConfigOptions.KAFKA_BOOTSTRAP_SERVERS, "kafka:9092");
config.set(AgentConfigOptions.KAFKA_ACTION_STATE_TOPIC, "orders-agent-action-state");
```
{{< /tab >}}
{{< tab "YAML" >}}
```yaml
agent:
  actionStateStoreBackend: kafka
  kafkaBootstrapServers: "kafka:9092"
  kafkaActionStateTopic: orders-agent-action-state
```
{{< /tab >}}
{{< /tabs >}}

If the topic does not exist, the store creates it with 64 partitions and a
replication factor of 1 by default. Otherwise, it reuses the existing topic without
changing its settings.
For retention requirements and cleanup options, see
[Kafka retention and cleanup](#kafka-retention-and-cleanup).

#### Fluss

Set the backend to `fluss` and provide the server address and a dedicated table:

{{< tabs "Fluss Action State Store" >}}
{{< tab "Python" >}}
```python
from flink_agents.api.core_options import AgentConfigOptions

config = agents_env.get_config()
config.set(AgentConfigOptions.ACTION_STATE_STORE_BACKEND, "fluss")
config.set(AgentConfigOptions.FLUSS_BOOTSTRAP_SERVERS, "fluss:9123")
config.set(AgentConfigOptions.FLUSS_ACTION_STATE_DATABASE, "flink_agents")
config.set(AgentConfigOptions.FLUSS_ACTION_STATE_TABLE, "orders_agent_action_state")
```
{{< /tab >}}
{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.configuration.Configuration;

Configuration config = agentsEnv.getConfig();
config.set(AgentConfigOptions.ACTION_STATE_STORE_BACKEND, "fluss");
config.set(AgentConfigOptions.FLUSS_BOOTSTRAP_SERVERS, "fluss:9123");
config.set(AgentConfigOptions.FLUSS_ACTION_STATE_DATABASE, "flink_agents");
config.set(AgentConfigOptions.FLUSS_ACTION_STATE_TABLE, "orders_agent_action_state");
```
{{< /tab >}}
{{< tab "YAML" >}}
```yaml
agent:
  actionStateStoreBackend: fluss
  flussBootstrapServers: "fluss:9123"
  flussActionStateDatabase: flink_agents
  flussActionStateTable: orders_agent_action_state
```
{{< /tab >}}
{{< /tabs >}}

The store creates the database and append-only log table if needed. By default,
it uses the `flink_agents` database and creates new tables with 64 buckets.

The store does not set log retention when creating a table, so configure retention
to preserve the records required by your recovery points. If the cluster requires
authentication, configure the Fluss authentication options as well. See
[Configuration]({{< ref "docs/operations/configuration" >}}) for these settings,
option defaults, and configuration loading and precedence.

## Recovery guarantees and user responsibilities

### Recovery flow

When restoring a checkpoint or savepoint with its corresponding Action State
Store, Flink Agents handles each Action as follows:

1. **Check for a saved Action completion.** If one is available, Flink Agents
   replays its saved Sensory/Short-Term Memory updates and output Events without
   running the Action body again. The replayed Events keep their original Event
   IDs.
2. **Rerun an unfinished Action.** If no completion is found, Flink Agents runs the
   Action body again. Ordinary calls may therefore repeat, but the framework
   checks each durable call for a matching record before executing it:

   - **Saved success or failure:** replay the saved outcome without calling the
     function again.
   - **Recorded in-flight call:** run its reconciler if one is configured.
     Otherwise, execute the function again.
   - **No matching record:** execute the function normally.

### Conditions for reusing saved records

To match saved records to the correct work, replay must preserve the following:

- **Preserve input order for each key.** Recovery must receive each key's inputs
  in the same order as the original execution.
- **For custom Actions, keep the durable call sequence stable.** An unfinished
  Action must reach the same durable calls in the same order, with the same inputs.
  See [Durable Execution]({{< ref "docs/development/create-agent/actions#durable-execution" >}})
  for call identity, serialization, and Action-context requirements.
- **Keep keys and Action names stable.** Reuse the original key types and
  serializer configuration, and ensure custom key serializers produce
  deterministic bytes. Changing key serialization or renaming an Action can
  prevent recovery from finding its saved completion.
- **Keep saved values usable.** The application must still be able to read and use
  stored Events, Memory updates, and durable call outcomes, since recovery may
  replay them without running the code that produced them.

### Limits on external side effects

The Action State Store cannot commit an external operation and its saved outcome
as a single transaction. If the operation finishes but the job fails before its
outcome is saved, recovery may execute it again.

For operations that change external state, use the service's idempotency support
or, for a durable call, provide a reconciler that can recover the original outcome.
See [Reconciling an in-flight call]({{< ref "docs/development/create-agent/actions#reconciling-an-in-flight-call" >}})
for the API and requirements.

## Action State Store retention and cleanup

For every checkpoint or savepoint you may need to restore, retain both the
snapshot and the Action State Store records it requires. Before changing retention
or cleanup settings, identify the oldest recovery point you need to keep.

{{< hint warning >}}
Hashing business keys does not encrypt Action state. Saved Events, Memory updates,
and durable call outcomes may contain sensitive data. Protect access to these
records.
{{< /hint >}}

### Kafka retention and cleanup

Topics created by the store use `cleanup.policy=compact,delete`,
`retention.ms=-1`, and `retention.bytes=-1`. The `-1` values disable the time and
size limits for the `delete` policy. Compaction can still reclaim older values
for the same key.

For an existing topic, check that its retention settings preserve the records
needed by every recovery point you intend to keep.

A completed checkpoint includes the Agent's progress for the work it covers,
which means those execution records are no longer needed to restore it. Because
earlier recovery points may still need the same records, choose a cleanup method
based on the recovery history you need to retain:

| Method | Cleanup behavior | When to use it | Recovery impact |
| --- | --- | --- | --- |
| No tombstones (default) | With `kafkaActionStateTombstoneEnabled=false`, completed work is removed from the in-memory cache, while its Kafka records remain. | Kafka storage growth is acceptable and you need to retain older recovery points. | Cache cleanup alone does not invalidate older recovery points. Kafka retention or manual deletion can still remove required data. |
| Per-key tombstones | The store automatically writes deletion markers for execution records covered by each completed checkpoint. Kafka can reclaim those records through background compaction. | The job only needs the latest completed checkpoint, or repeating affected Actions is acceptable. | The triggering checkpoint remains usable. Restoring an earlier checkpoint or savepoint may repeat completed Actions. |
| Checkpoint-aligned cleanup | An administrator selects the oldest recovery point to retain. The tool records a cleanup boundary in a separate control topic, then deletes older Kafka data no longer needed by the selected recovery point. | You need to retain a selected checkpoint or savepoint while reclaiming space from older recovery data. | The selected point remains usable. With the control topic configured, recovery points requiring data below the boundary are rejected. |

Per-key tombstones and checkpoint-aligned cleanup cannot be enabled together.
A committed cleanup boundary takes effect even if physical deletion has not
finished.

#### Cleanup steps

For per-key tombstones, set `kafkaActionStateTombstoneEnabled=true` before starting
the job.

##### Checkpoint-aligned Kafka cleanup

Use `KafkaActionStateCleanupTool` for both Java and Python jobs. Before generating
a plan, check the prerequisites below. If the job has used per-key tombstones,
first follow [Switching from per-key tombstones](#switching-from-per-key-tombstones).

{{< details title="Prerequisites and topic settings" >}}

- The data topic must use `cleanup.policy=compact,delete`, `retention.ms=-1`, and
  `retention.bytes=-1`. These are also the settings used when the store creates a
  new data topic.
- The control topic must differ from the data topic, have exactly one partition,
  and use `cleanup.policy=compact` without delete retention. Dedicate it to the
  same logical Agent operator and physical data topic; do not share it across
  operators.
- Before enabling `kafkaActionStateCleanupControlTopic` on the job, the control
  topic must exist and contain a committed cleanup boundary. Recovery fails if
  the topic or its committed history is missing.
- The `plan` command accepts only versioned Kafka recovery markers. Legacy map
  markers can still be used for job recovery, but cannot authorize cleanup.

{{< /details >}}

For the first cleanup of an existing job:

1. Select the oldest completed checkpoint or savepoint to retain, and stop the
   job while keeping that snapshot available.
2. Generate a cleanup plan for the Agent operator in that snapshot, and check
   that it matches the selected recovery point and Action State Store.
3. Apply the plan with a separate cleanup control topic dedicated to that Action
   State Store.
4. After `apply` succeeds, set `kafkaActionStateCleanupControlTopic` to that topic
   and `kafkaActionStateTombstoneEnabled=false`, then restart from the
   **same selected recovery point**.

{{< details title="Command examples" >}}

Run the tool where the recovery point and Kafka are accessible. The classpath
must include the full Flink Agents distribution JAR for your Flink version
(`flink-agents-dist-flink-<FLINK_VERSION>-<FA_VERSION>.jar`, without the `-thin`
classifier), the matching Flink State Processor API JAR, and Flink's `lib/`.

Generate a plan with the Agent operator's UID hash in the selected snapshot.
Replace the placeholders below, or use `--operator-uid` instead of
`--operator-uid-hash` when specifying a UID:

```bash
java -cp "$FLINK_HOME/lib/*:<FLINK_AGENTS_DIST_JAR>:<FLINK_STATE_PROCESSOR_API_JAR>" \
  org.apache.flink.agents.runtime.actionstate.KafkaActionStateCleanupTool \
  plan --checkpoint "<CHECKPOINT_OR_SAVEPOINT_PATH>" \
  --operator-uid-hash "<AGENT_OPERATOR_UID_HASH>" \
  --output cleanup-plan.json
```

After checking the plan, apply it with a separate control topic. The tool creates
the topic if it does not exist.

```bash
java -cp "$FLINK_HOME/lib/*:<FLINK_AGENTS_DIST_JAR>:<FLINK_STATE_PROCESSOR_API_JAR>" \
  org.apache.flink.agents.runtime.actionstate.KafkaActionStateCleanupTool \
  apply --plan cleanup-plan.json --bootstrap-servers "kafka:9092" \
  --control-topic "orders-agent-action-state-cleanup"
```

To choose the replication factor when creating the control topic, add
`--replication-factor N` to `apply`. The default is `1`; valid values are `1`
through `32767`.

A successful apply prints `Cleanup plan <id> is APPLIED`.

{{< /details >}}

If the first cleanup fails or is interrupted, keep the job stopped and retry
`apply` with the same plan: the boundary may already have been committed, making
earlier recovery points unavailable.

Once boundary enforcement is enabled, later plans may be applied while the job
runs. Apply one plan at a time, only advance the boundary, and keep the data topic
and its partition count unchanged during cleanup.

{{< hint warning >}}
Keep the same cleanup control topic configured and retain its committed records
for every subsequent run and restore of this recovery history. Omitting or
changing the topic removes boundary enforcement; with it configured, the job also
rejects a fresh start without a recovery marker.
{{< /hint >}}

#### Switching from per-key tombstones

To switch a job from per-key tombstones to checkpoint-aligned cleanup:

1. Stop the job cleanly and wait for pending tombstone writes to finish.
2. Restore the newest recovery point that is still valid under the tombstone
   policy. Set `kafkaActionStateTombstoneEnabled=false` and leave the cleanup
   control-topic option unset.
3. Complete and retain a new checkpoint or savepoint, then stop the job again.
4. Use that new recovery point for the first cleanup plan and apply operation.
5. Restart from the same point with the cleanup control topic configured.

Use this new recovery point because an older one may still require records
affected by past tombstones.

### Fluss retention

Flink Agents currently provides no equivalent of Kafka's per-key tombstones or
checkpoint-aligned cleanup for the Fluss Action State Store. Instead, use Fluss's
own log retention settings to manage record retention and cleanup.
