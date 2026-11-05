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

### Execution mode

Flink's default execution mode is `STREAMING`. Keep this mode for Flink Agents
jobs, including jobs with bounded inputs; no additional configuration is needed
unless the default has been overridden. To explicitly select it in Flink's
`config.yaml`, set the following outside the `agent` section:

```yaml
execution:
  runtime-mode: STREAMING
```

Flink Agents does not support Flink's batch keyed-state backend, which is enabled
by default in `BATCH` mode through `execution.batch-state-backend.enabled`. This
backend clears state when switching keys, while an Agent may still have unfinished
Actions for a previous key. Flink Agents rejects this backend before processing
Agent inputs.

`AUTOMATIC` selects `BATCH` when all sources are bounded, so it can encounter the
same restriction. This also applies if the execution mode is changed after the
Agent pipeline is built. Ensure the final job configuration uses `STREAMING`.

Setting `execution.batch-state-backend.enabled: false` avoids this particular
backend restriction, but does not imply full support for every BATCH use case.

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

### Set a stable operator UID

The agent operator holds the agent's memory, pending actions and input events. By default,
Flink derives its operator ID from the job topology, so some upstream changes can change the
ID and the saved state no longer maps back to the operator when you restore from a savepoint.
Pass a UID to `apply` to pin the operator ID. The UID identifies this agent operator in the
job, so it must be unique there. An empty or blank UID is rejected. When you omit it, the
operator ID is generated as before.

Supply the UID before obtaining the output stream or table. To use another agent or UID after
the output has been built, create a new builder.

{{< tabs "Set a stable operator UID" >}}

{{< tab "Python" >}}
```python
agents_env.from_datastream(input_stream, key_selector=key_selector) \
    .apply(MyAgent(), uid="my-agent-uid") \
    .to_datastream()
```
{{< /tab >}}

{{< tab "Java" >}}
```java
agentsEnv
        .fromDataStream(inputStream, keySelector)
        .apply(new MyAgent(), "my-agent-uid")
        .toDataStream();
```

An agent loaded from YAML takes the UID the same way: `apply("my-agent-name", "my-agent-uid")`.
{{< /tab >}}

{{< /tabs >}}

Set the UID from the first deployment. Adding it to a job that already has savepoints changes
the operator ID, so state saved under the generated ID is not restored to the operator. Existing
savepoints need a migration, such as remapping the operator identifier with Flink's State
Processor API. `--allowNonRestoredState` skips unmatched state instead of migrating it. For a
savepoint taken with a UID, `KafkaActionStateCleanupTool plan` accepts `--operator-uid` (see
[Consistency]({{< ref "docs/operations/consistency" >}})).


<span id="exactly-once-output-consistency"></span>
<span id="exactly-once-action-consistency"></span>

## Consistency Guarantees

Before submitting the job, choose and configure its consistency mode. See
[Consistency]({{< ref "docs/operations/consistency" >}}) for required setup,
recovery guarantees, retention and cleanup.
