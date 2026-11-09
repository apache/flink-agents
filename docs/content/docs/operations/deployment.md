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

Flink's default execution mode is `STREAMING`, which supports both bounded and
unbounded Agent inputs. To explicitly select it in Flink's `config.yaml`, set the
following outside the `agent` section:

```yaml
execution:
  runtime-mode: STREAMING
```

For bounded inputs, Agent workflows also support `BATCH` execution, including
`AUTOMATIC` mode when Flink selects batch execution. With Flink's default batch
keyed-state backend, each Agent operator subtask finishes the current input's
entire workflow, including asynchronous Actions, before accepting the next input.
Different subtasks can still run concurrently, and short-term memory is preserved
across inputs of the same key. Flink discards that key's state when it advances to
the next key.

For workloads that spend time waiting for LLMs or remote tools, allowing workflows
for different keys to overlap within a subtask can improve throughput. To retain
that concurrency in `BATCH` mode, use a regular state backend:

```yaml
execution:
  runtime-mode: BATCH
  batch-state-backend:
    enabled: false
```

Keyed state remains available, but it may retain state for more keys and require
more storage. If no other operator requires sorted input, also set
`execution.sorted-inputs.enabled: false` to avoid input sorting overhead. Both
options default to `true` and apply to the whole job; changing them can affect other
keyed operators and timer behavior. They do not enable streaming checkpoints in
`BATCH` mode.

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


<span id="exactly-once-output-consistency"></span>
<span id="exactly-once-action-consistency"></span>

## Consistency Guarantees

Before submitting the job, choose and configure its consistency mode. See
[Consistency]({{< ref "docs/operations/consistency" >}}) for required setup,
recovery guarantees, retention and cleanup.
