---
title: Quickstart
bold: true
bookCollapseSection: true
type: docs
weight: 3
aliases:
  - /docs/get-started/quickstart/parallel_llm/
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

Each Quickstart below is a complete, runnable Flink job. All three read the same stream
of product reviews and call the same local Ollama model, so the preparation is shared and
collected in [Before You Begin](#before-you-begin). They differ only in how you declare
the Agent.

- [Build an Agent]({{< ref "docs/get-started/quickstart/create-agent" >}}) declares Actions
  and Events in Python or Java code, and wires a prompt, a chat model, and a tool together.
  Start here if you are new to Flink Agents.
- [Use the Built-in ReAct Agent]({{< ref "docs/get-started/quickstart/react_agent" >}})
  configures the built-in `ReActAgent` with a prompt, tools, and an output schema, and lets
  the model plan the steps.
- [Define an Agent with YAML]({{< ref "docs/get-started/quickstart/yaml_agent" >}}) declares
  the same wiring in one YAML file and loads it into the environment by Agent name.

## Before You Begin

### Install Flink and Flink Agents

Follow [Setup]({{< ref "docs/get-started/setup" >}}). Note which job type you installed
for, because every submit command below is shown for both types. A PyFlink job submits with
`flink run -py`, and a Java Flink job submits with `flink run -c`.

### Get the Example Source

The Quickstarts submit the example programs that ship with the repository, so you need
them locally. Clone the repository:

```bash
git clone https://github.com/apache/flink-agents.git
cd flink-agents
```

For a PyFlink job, the same examples are installed inside the `flink_agents` package, at
`python/flink_agents/examples/quickstart` in that tree. Each Quickstart gives both submit
commands.

A Java Quickstart needs the examples JAR, which the repository build produces. Build from
source with `./tools/build.sh`, then use `examples/target/flink-agents-examples-*.jar`.

### Prepare a Model Server

The examples call a local Ollama server, so install Ollama from the official
[website](https://ollama.com/download) at version **0.9.0** or higher, and pull the model
the Quickstarts use:

```bash
ollama pull qwen3:8b
```

Leave the server running. The examples connect to `http://localhost:11434`, which is the
default endpoint of the Ollama chat model connection.

### Start a Local Flink Cluster

{{< tabs "Start a Local Flink Cluster" >}}

{{< tab "Python" >}}
```bash
# Required: lets the Flink JVM locate the flink-agents package
export PYTHONPATH=$(python -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')

$FLINK_HOME/bin/start-cluster.sh
```
{{< /tab >}}

{{< tab "Java" >}}
```bash
$FLINK_HOME/bin/start-cluster.sh
```
{{< /tab >}}

{{< /tabs >}}

The web UI is at [localhost:8081](http://localhost:8081). If it does not open, check
`$FLINK_HOME/log`. A port conflict is fixed in `$FLINK_HOME/conf/config.yaml`.

On JDK 21 or later, append `--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED` to
[env.java.opts.all](https://nightlies.apache.org/flink/flink-docs-stable/docs/deployment/config/#env-java-opts-all)
in `$FLINK_HOME/conf/config.yaml` before you start the cluster. Both the Python and the Java
Quickstarts need it. See
[JDK and Scheduling Requirements]({{< ref "docs/development/create-agent/actions#jdk-and-scheduling-requirements" >}}).

To stop the cluster again, run `$FLINK_HOME/bin/stop-cluster.sh`.
