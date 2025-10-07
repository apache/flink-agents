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

We provide two options to run the job:

- **Run without Flink**
    - **Language Support**: Only Python
    - **Input and Output**: Python List
    - **Suitable Use Case**: Local Testing and Debugging

- **Run in Flink**
    - **Language Support**: Python & Java
    - **Input and Output**: DataStream or Table
    - **Suitable Use Case**: Production

These deployment modes differ in supported languages and data formats, allowing you to choose the one that best fits your use case.

## Run without Flink

After completing the [installation of flink-agents]({{< ref "docs/get-started/installation" >}}) and building your [ReAct Agent]({{< ref "docs/development/react_agent" >}}) or [workflow agent]({{< ref "docs/development/workflow_agent" >}}), you can test and execute your agent locally using a simple Python script. This allows you to validate logic without requiring a Flink cluster.

### Example for Local Run with Test Data

```python
from flink_agents.api.execution_environment import AgentsExecutionEnvironment
from my_module.agents import MyAgent  # Replace with your actual agent path

if __name__ == "__main__":
    # 1. Initialize environment
    env = AgentsExecutionEnvironment.get_execution_environment()
    
    # 2. Prepare test data
    input_data = [
        {"key": "0001", "value": "Calculate the sum of 1 and 2."},
        {"key": "0002", "value": "Tell me a joke about cats."}
    ]
    
    # 3. Create agent instance
    agent = MyAgent()
    
    # 4. Build pipeline
    output_data = env.from_list(input_data) \
                     .apply(agent) \
                     .to_list()
    
    # 5. Execute and show results
    env.execute()
    
    print("\nExecution Results:")
    for record in output_data:
        for key, value in record.items():
            print(f"{key}: {value}")

```

#### Input Data Format

The input data should be a list of dictionaries `List[Dict[str, Any]]` with the following structure:

```python
[
    {
        # Optional field: Input key. 
        # The key is randomly generated if not provided.
        "key": "key_1",
        
        # Required field: Input content
        # This becomes the `input` field in InputEvent
        "value": "Calculate the sum of 1 and 2.",
    },
    ...
]
```

#### Output Data Format

The output data is a list of dictionaries `List[Dict[str, Any]]` where each dictionary contains a single key-value pair representing the processed result. The structure is generated from `OutputEvent` objects:

```python
[
    {key_1: output_1},  # From first OutputEvent
    {key_2: output_2},  # From second OutputEvent
    ...
]
```

## Run in Flink

### Prerequisites

- **Operating System**: Unix-like environment (Linux, macOS, Cygwin, or WSL)  
- **Python**: Version 3.10 or 3.11  
- **Flink**: A running Flink cluster with version 1.20.3 and the Flink Agents dependency installed

### Prepare Flink Agents

We recommand creating a Python virtual environment to install the Flink Agents Python library.

Follow the [instructions]({{< ref "docs/get-started/installation" >}}) to install the Flink Agents Python and Java libraries.

### Submit to Flink Cluster

Submitting Flink Agent jobs to the Flink Cluster is the same as submitting PyFlink jobs. For more details on all available options, please refer to the [Flink CLI documentation](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/deployment/cli/#submitting-pyflink-jobs).

```bash
<FLINK_HOME>/bin/flink run \
      --jobmanager <FLINK_CLUSTER_ADDR> \
      --python <PATH_TO_YOUR_FLINK_AGENTS_JOB>
```

Now you should see a Flink job submitted to the Flink Cluster in Flink web UI (typically accessible at http://&lt;jobmanagerHost&gt;:8081).
