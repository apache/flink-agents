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
input received since that checkpoint. For an Agent, this can mean calling a model,
invoking a tool, or updating an external system again.

The consistency mode you choose determines what state must remain available for
recovery and which work can be reused.

## Choose a consistency mode

With exactly-once checkpointing and the required source and sink guarantees,
each input affects the job's state and output once, even if recovery processes it
again. Flink Agents calls this **exactly-once output consistency**.

Adding an external Action State Store lets recovery also reuse saved Action
completions and durable call outcomes. Flink Agents calls this **exactly-once
action consistency**.

| Configuration | Behavior after a failure | Requirements and trade-offs |
| --- | --- | --- |
| No checkpointing | The job restarts from the source's configured starting positions without restoring Agent state or progress from a checkpoint. | Suitable when the application can accept lost progress or repeated work. |
| Flink checkpointing | Flink restores checkpointed state and replays subsequent input. Actions and external calls may repeat, and models or queries may return different results. | Enable exactly-once checkpointing with durable checkpoint storage and the required source and sink guarantees. External calls must tolerate repetition. |
| Flink checkpointing + Action State Store | Recovery replays saved completions without rerunning Action bodies. Unfinished Actions may run again, reusing saved durable call outcomes. | Add a Kafka or Fluss Action State Store. Saving Action completions and durable call outcomes requires synchronous backend writes. Retain both checkpoints and the backend records needed for recovery. Use idempotency or reconciliation to protect external effects. |

## Required setup

Enable exactly-once checkpointing for the Flink or PyFlink job, and use source
and sink connectors with the required
[fault tolerance guarantees](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/connectors/datastream/guarantees/).
Follow Flink's [checkpointing guide](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/dev/datastream/fault-tolerance/checkpointing/)
for configuration and checkpoint storage, and
[checkpoint recovery guide](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/ops/state/checkpoints/#resuming-from-a-retained-checkpoint)
for restore procedures. Use the Flink documentation for the version you deploy.

### Configure an Action State Store

Configure the Kafka or Fluss backend before building the Agent pipeline.

Create `agents_env` (Python) or `agentsEnv` (Java) as described in
[Integrate with Flink]({{< ref "docs/development/integrate_with_flink" >}}).
The YAML examples show the `agent:` section of the job's Flink configuration.

Each logical Agent operator needs its **own Kafka topic or Fluss table**, shared
only by that operator's subtasks.

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

The store creates the topic if needed, with 64 partitions and a replication
factor of 1 by default. It reuses existing topics without changing their settings.
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
it uses the `flink_agents` database and creates new tables with 64 buckets. It does
not set log retention when creating the table; configure retention to preserve
the data needed by your recovery points. If the cluster requires authentication,
also configure the Fluss authentication options.

See [Configuration]({{< ref "docs/operations/configuration" >}}) for option defaults,
authentication settings, and configuration loading and precedence.

## Recovery guarantees and user responsibilities

### Recovery flow

When restoring a checkpoint or savepoint with its corresponding Action State
Store, Flink Agents handles each Action as follows:

1. **Check for a saved Action completion.** If one is available, replay its saved
   Sensory/Short-Term Memory updates and output Events. The Events keep their
   original Event IDs. Skip the Action body.
2. **Rerun an unfinished Action.** Ordinary calls may repeat. At each durable call,
   check for a matching record:

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

Before restoring state after changes to keys, Action names, result types,
application code, or versions, review [Upgrades and migration](#upgrades-and-migration).

### Limits on external side effects

An external operation can finish before its outcome is saved. If the job fails
in that interval, recovery may execute the operation again. The Action State
Store cannot commit the external operation and its saved outcome as a single
transaction.

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

A completed checkpoint includes the Agent's progress for the work it covers.
Those execution records are no longer needed to restore that checkpoint, but
earlier recovery points may still need them. Choose a cleanup method based on
the recovery history you need to retain:

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

For checkpoint-aligned cleanup, use `KafkaActionStateCleanupTool` for both Java
and Python jobs. Use the full Flink Agents distribution JAR for your Flink version
(`flink-agents-dist-flink-<FLINK_VERSION>-<FA_VERSION>.jar`, without the `-thin`
classifier), the matching Flink State Processor API JAR, and Flink's `lib/` on the
classpath. Run the tool where the recovery point and Kafka are accessible.

If the job has used per-key tombstones, first follow
[Switching from per-key tombstones](#switching-from-per-key-tombstones).

For the first cleanup of an existing job:

1. Select the oldest completed checkpoint or savepoint you intend to retain, and
   stop the job while keeping that recovery point available. Identify the Agent
   operator by its UID or UID hash in that snapshot. The selected point must
   contain versioned Kafka recovery markers.
   See the [configuration reference]({{< ref "docs/operations/configuration#checkpoint-aligned-kafka-cleanup" >}})
   for recovery marker requirements.
2. Generate a cleanup plan. Replace the JAR and recovery-point placeholders below;
   use `--operator-uid` instead of `--operator-uid-hash` when specifying a UID:

   ```bash
   java -cp "$FLINK_HOME/lib/*:<FLINK_AGENTS_DIST_JAR>:<FLINK_STATE_PROCESSOR_API_JAR>" \
     org.apache.flink.agents.runtime.actionstate.KafkaActionStateCleanupTool \
     plan --checkpoint "<CHECKPOINT_OR_SAVEPOINT_PATH>" \
     --operator-uid-hash "<AGENT_OPERATOR_UID_HASH>" \
     --output cleanup-plan.json
   ```

3. Check that the plan's `sourceRecoveryPoint` and `topic` match the selected
   snapshot and this Agent operator's Action State Store. Keep the plan file, and
   ensure the data topic uses the retention settings listed above.
4. Apply the plan using a separate control topic:

   ```bash
   java -cp "$FLINK_HOME/lib/*:<FLINK_AGENTS_DIST_JAR>:<FLINK_STATE_PROCESSOR_API_JAR>" \
     org.apache.flink.agents.runtime.actionstate.KafkaActionStateCleanupTool \
     apply --plan cleanup-plan.json --bootstrap-servers "kafka:9092" \
     --control-topic "orders-agent-action-state-cleanup"
   ```

   Dedicate the control topic to this Agent operator's data topic. The tool can
   create it; see the [configuration reference]({{< ref "docs/operations/configuration#checkpoint-aligned-kafka-cleanup" >}})
   for existing-topic requirements and optional parameters.
5. After `apply` succeeds and prints `Cleanup plan <id> is APPLIED`, set
   `kafkaActionStateCleanupControlTopic` to the control topic and
   `kafkaActionStateTombstoneEnabled=false`, then restart from the
   **same selected recovery point**. Startup or recovery fails if the control
   topic is missing or has no committed boundary.

If this first cleanup fails or is interrupted, keep the job stopped and retry
`apply` with the same plan. The boundary may already have been committed, making
earlier recovery points unavailable.

With boundary enforcement enabled, you can apply later cleanup plans while the job
runs, provided they only advance the boundary. Apply one plan at a time, and keep
the data topic and its partition count unchanged during cleanup.

{{< hint warning >}}
Keep the same control-topic setting and retain its records whenever you run or
restore the job using this recovery history. Omitting or changing the setting
removes the protection against restoring an older point. With the control topic
configured, the job also rejects a fresh start without a recovery marker.
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

Fluss log retention controls the physical cleanup of Action state. The per-key
tombstones and checkpoint-aligned cleanup described above apply only to the Kafka
Action State Store.

## Upgrades and migration

### Check recovery conditions before a change

Before restoring state after a change, check Flink's state compatibility
requirements and confirm that the application can still use its saved Action
results. A readable checkpoint does not by itself guarantee that saved results
remain valid for the new code.

| Planned change | What to check |
| --- | --- |
| Flink Agents or Flink version | Follow the target release's migration guidance and Flink's upgrade requirements. A job starting successfully does not confirm that recovery across those versions is supported. |
| Key type or serializer | Keep the original key type and serializer configuration when reusing Action state. Custom key serializers must produce deterministic bytes. Changes to the serialized key bytes are not always detected. |
| Action name | Renaming an Action prevents recovery from finding its saved completion. Keeping the same name allows recovery to reuse saved output without running the new body. Check that this output is still valid for the new code. |
| User code, Events, Memory, or durable results | Check that saved outputs and values can still be read and used, and that unfinished Actions reach the same logical calls. Runtime format checks do not verify compatibility with the application's logic. |
| Action State Store backend | When reusing checkpoints or savepoints, preserve the original [Kafka topic]({{< ref "docs/operations/configuration" >}}#changes-to-an-existing-kafka-store) or [Fluss table]({{< ref "docs/operations/configuration" >}}#changes-to-an-existing-fluss-store), its partition or bucket layout, and [the records needed for recovery](#action-state-store-retention-and-cleanup). |

Use Flink's
[application and version upgrade guidance](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/ops/upgrading/)
for the hosting job. The checks above identify recovery requirements and known
risks. They do not establish which Flink Agents and Flink version combinations are
supported.

### Starting without existing state

If the target release requires a fresh start without restoring a checkpoint or
savepoint, configure the source's starting position before launching the new job.
If it will reprocess input handled by the previous version, use idempotency or
deduplication to prevent repeated external effects.

### Migration notes

#### Flink Agents 0.4

Flink Agents 0.4 changes the Action State Store record key format and does not
support records written by earlier versions. When upgrading from an earlier
version, use a new Kafka topic or Fluss table and
[start without restoring an earlier checkpoint or savepoint](#starting-without-existing-state).
