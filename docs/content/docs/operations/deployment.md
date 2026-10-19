---
title: Deployment
weight: 1
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

## Overview

Flink Agents runs as part of Java Flink or PyFlink jobs:

- **Input and Output**: DataStream or Table
- **Suitable Use Case**: Production

## Run in Flink

### Prerequisites

- **Operating System**: Unix-like environment (Linux, macOS, Cygwin, or WSL)  
- **Python**: Version 3.10, 3.11 or 3.12 when the job uses PyFlink or Python components/features
- **Flink**: A running Flink cluster with version above 1.20.3 (including 1.20.3) and the Flink Agents dependency installed

{{< hint info >}}
**Note**: Python 3.12 requires Flink above 2.1 (including 2.1).
{{< /hint >}}

### Prepare Flink Agents

Follow [Setup]({{< ref "docs/get-started/setup" >}}) to install Flink Agents for
the Java Flink or PyFlink job. When the Agent uses Actions or Resources implemented
in the other language, also install their dependencies.

### Submit to Flink Cluster

Submit the Java Flink or PyFlink job to the cluster. If the Agent is defined in YAML,
[load it in that job]({{< ref "docs/development/create-agent/yaml#loading-and-running" >}})
before submission.

{{< tabs "Submit to Flink Cluster" >}}

{{< tab "Python" >}}
Submit a PyFlink job with Flink Agents in the same way as any other PyFlink job. For
all available options, see the [Flink CLI documentation](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/deployment/cli/#submitting-pyflink-jobs).

```bash
<FLINK_HOME>/bin/flink run \
      --jobmanager <FLINK_CLUSTER_ADDR> \
      --python <PATH_TO_YOUR_FLINK_AGENTS_JOB>
```
{{< /tab >}}

{{< tab "Java" >}}
Submit a Java Flink job with Flink Agents in the same way as any other Java Flink job.
For all available options, see the [Flink CLI documentation](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/deployment/cli/#submitting-a-job).

```bash
<FLINK_HOME>/bin/flink run \
      -c <MAIN_CLASS> \
      <PATH_TO_YOUR_FLINK_AGENTS_JOB_JAR>
```

{{< /tab >}}

{{< /tabs >}}


Now you should see a Flink job submitted to the Flink Cluster in Flink web UI (typically accessible at http://&lt;jobmanagerHost&gt;:8081).


## Consistency Guarantees

Flink Agents leverage Flink’s checkpointing to provide **exactly-once output consistency**. Exactly-once output consistency does not mean each event is processed only once; it means that every event will affect the state and output result exactly once. This is sufficient for typical data-processing use cases where the processing of data does not cause any external side effects.

However, actions in Flink Agents can trigger external side effects (for example, sending an email or invoking an external API). These side effects should not be executed more than once. To address this, Flink Agents support **exactly-once action consistency** by leveraging an external action state store.

### Exactly-Once Output Consistency

When a job runs on a Flink cluster, Flink periodically takes checkpoints. See [Flink Checkpointing](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/ops/state/checkpoints/) for details.  
After recovery from a checkpoint, Flink Agents reprocess events that arrived after that checkpoint. As a result, any actions triggered by those events may be executed again.

### Exactly-Once Action Consistency

To enable exactly-once action consistency, configure an external action state store.
Flink Agents record state for each Action in this store. After recovering from a
checkpoint, the framework reuses Actions whose completion was persisted, including
their stored Sensory/Short-Term Memory updates and output Events. Unfinished Actions
may execute again.

When a completed Action is reused during recovery, its stored output Events keep their original Event IDs. The runtime records their relationships to the current trigger Event in Trace Log `entityMetadata`.

The same store also saves individual call outcomes for
[Durable Execution]({{< ref "docs/development/create-agent/actions#durable-execution" >}}).
When an unfinished Action runs again, durable calls can reuse persisted outcomes.
An external operation can finish before its outcome is saved, so recovery may repeat
that operation. Use idempotent operations or reconciliation for external side effects;
see [Recovery Behavior]({{< ref "docs/development/create-agent/actions#recovery-behavior" >}}).

{{< hint info >}}
**Note**: Currently, Kafka and Fluss are supported as the external action state store.
{{< /hint >}}

{{< hint warning >}}
The action-state key format changed in Flink Agents 0.4 and action-state records written by earlier versions are unsupported. When upgrading, use a fresh Kafka topic or Fluss table and start without an older checkpoint or savepoint. Recovery validates the current fields, but cannot reliably distinguish every older record from the current format.

Action-state keys contain a digest of the serialized key. Changing key types or serializer configuration when recovering existing state is unsupported, even if Flink accepts the change for keyed state: the same key can serialize to different bytes, causing recovery to miss completed actions and repeat their side effects. These changes are not detected by the action-state store. Preserve the original key types and serializer configuration for recovery. Custom key serializers must produce deterministic bytes.

Action-state keys identify an action by its name. Keeping an action's name across a code change lets recovery reuse its completed results; renaming an action makes its recorded results unreachable, so it is executed again on the next recovery.

Dedicate each Kafka topic or Fluss table to one logical Flink Agents operator, shared by that operator's subtasks. Action-state keys do not contain a job or operator namespace, so sharing a backend between logical operators can allow otherwise identical records to collide.
{{< /hint >}}

The business-key component stored in the backend is a SHA-256 digest rather than the serialized key itself. This keeps record keys bounded and avoids embedding raw key bytes, but it is not encryption; protect the action-state backend with appropriate access controls.

See [Action State Store Configuration]({{< ref "docs/operations/configuration#action-state-store" >}}) for configuration options.

{{< hint warning >}}
**Note**: Enabling Kafka action-state tombstones can invalidate checkpoints or savepoints older than the prune and cause completed actions to execute again. See [Action State Store Configuration]({{< ref "docs/operations/configuration#action-state-store" >}}) for the recovery trade-off.
{{< /hint >}}

{{< hint warning >}}
**Note**: A committed checkpoint-aligned Kafka cleanup boundary permanently makes older recovery points unsupported. Every subsequent run and restore must use the same cleanup control topic so the runtime can reject those recovery points before replay. See [Checkpoint-aligned Kafka cleanup]({{< ref "docs/operations/configuration#checkpoint-aligned-kafka-cleanup" >}}).
{{< /hint >}}

{{< hint info >}}
**Note**: Exactly-once action consistency is guaranteed only if, after recovering from the same checkpoint, inputs for each key arrive in the same order as before recovery. If this ordering requirement is not met, the system falls back to exactly-once output consistency.
{{< /hint >}}
