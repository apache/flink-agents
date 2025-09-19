---
title: 'Workflow Agent'
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

{{< hint warning >}}
**TODO**: Briefly describe what is workflow agent, what is can do, and when to use it.
{{< /hint >}}

This quickstart introduces two small, progressive streaming examples that demonstrate how to build LLM-powered workflows with Flink Agents:

- **Review Analysis**: Processes a stream of product reviews and uses a single agent to extract a rating (1–5) and unsatisfied reasons from each review.

- **Product Improvement Suggestions**: Builds on the first example by aggregating per-review analysis in windows to produce product-level summaries (score distribution and common complaints), then applies a second agent to generate concrete improvement suggestions for each product.

Together, these examples show how to build a multi-agent workflow with Flink Agents and run it on a Flink standalone cluster.

{{< hint warning >}}
**TODO**: Add the example code here. And briefly explain the code.
{{< /hint >}}

## Prerequisites

* Unix-like environment (we use Linux, Mac OS X, Cygwin, WSL)
* Git
* Java 11
* Python 3.10 or 3.11

## Preparation

### Prepare Flink

Download a stable release of Flink 1.20.3, then extract the archive:

```bash
curl -LO https://archive.apache.org/dist/flink/flink-1.20.3/flink-1.20.3-bin-scala_2.12.tgz
tar -xzf flink-1.20.3-bin-scala_2.12.tgz

# Copy the flink-python jar from opt to lib
cp ./flink-1.20.3/opt/flink-python-1.20.3.jar ./flink-1.20.3/lib
```
You can refer to the [local installation](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/try-flink/local_installation/) instructions for more detailed step.


### Prepare Flink Agents

We recommand creating a Python virtual environment to install the Flink Agents Python library.

Follow the {{< ref "docs/get-started/installation" >}} instructions to install the Flink Agents Python and Java libraries.

### Prepare Ollama

Download and install Ollama from the official [website](https://ollama.com/download).

Then pull the qwen3:8b model, which is required by the quickstart examples

```bash
ollama pull qwen3:8b
```

### Deploy a Standalone Flink Cluster

You can deploy a standalone Flink cluster in your local environment with the following command.

```bash
export PYTHONPATH=$(python -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')
./flink-1.20.3/bin/start-cluster.sh
```

You should be able to navigate to the web UI at [localhost:8081](localhost:8081) to view the Flink dashboard and see that the cluster is up and running.

## Submit Flink Agents Job to Standalone Flink Cluster

### Clone the Flink Agents repo

```bash
git clone https://github.com/apache/flink-agents.git
```

### Submit to Flink Cluster
```bash
export PYTHONPATH=$(python -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')

# Run review analysis example
./flink-1.20.3/bin/flink run -py ./flink-agents/python/flink_agents/examples/quickstart/product_review_analysis.py

# Run product suggestion example
./flink-1.20.3/bin/flink run -py ./flink-agents/python/flink_agents/examples/quickstart/product_improve_suggestion.py
```

Now you should see a Flink job submitted to the Flink Cluster in Flink web UI [localhost:8081](localhost:8081)

After a few minutes, you can check for the output in the TaskManager output log.
