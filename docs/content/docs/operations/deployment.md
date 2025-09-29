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

## Local Run with Test Data

After completing the [installation of flink-agents]({{< ref "docs/get-started/installation" >}}) and [building your agent]({{< ref "docs/development/workflow_agent" >}}), you can test and execute your agent locally using a simple Python script. This allows you to validate logic without requiring a Flink cluster.

### Key Features

- **No Flink Required**: Local execution is ideal for development and testing.
- **Test Data Simulation**: Easily inject mock inputs for validation.
- **IDE Compatibility**: Run directly in your preferred development environment.

### Data Format

#### Input Data Format

The input data should be a list of dictionaries (`List[Dict[str, Any]]`) with the following structure:

``````python
[
    {
        # Optional field: Context key for multi-session management
        # Priority order: "key" > "k" > auto-generated UUID
        "key": "session_001",  # or use shorthand "k": "session_001"
        
        # Required field: Input content (supports text, JSON, or any serializable type)
        # This becomes the `input` field in InputEvent
        "value": "Calculate the sum of 1 and 2.",  # or shorthand "v": "..."
    },
    ...
]
``````

#### Output Data Format

The output data is a list of dictionaries (`List[Dict[str, Any]]`) where each dictionary contains a single key-value pair representing the processed result. The structure is generated from `OutputEvent` objects:

``````python
[
    {key_1: output_1},  # From first OutputEvent
    {key_2: output_2},  # From second OutputEvent
    ...
]
``````

Each dictionary in the output list follows this pattern:

``````
{
    # Key: Matches the input context key (from "key"/"k" field or auto-generated UUID)
    # Value: Result from agent processing (type depends on implementation)
    <context_key>: <processed_output>
}
``````

### Example for Local Run with Test Data

``````python
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

``````

## Local Run with Flink MiniCluster

After completing the [installation of flink-agents]({{< ref "docs/get-started/installation" >}}) and [building your agent]({{< ref "docs/development/workflow_agent" >}}), you can test and execute your agent locally using a **Flink MiniCluster embedded in Python**. This allows you to simulate a real Flink streaming environment without deploying to a full cluster. For more details about how to integrate agents with Flink's `DataStream` or `Table`, please refer to the [Integrate with Flink]({{< ref "docs/development/integrate_with_flink" >}}) documentation.

{{< hint info >}}

If you encounter the exception "No module named 'flink_agents'" when running with Flink MiniCluster, you can set the PYTHONPATH by adding os.environ["PYTHONPATH"] = sysconfig.get_paths()["purelib"] at the beginning of your code.

{{< /hint >}}

## Run in Flink Cluster

Flink Agents jobs are deployed and run on the cluster similarly to Pyflink jobs. You can refer to the instructions for [submit pyflink jobs](https://https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/deployment/cli/#submitting-pyflink-jobs)  for more detailed steps. The following explains how to submit Flink Agents jobs to the cluster, using a Standalone cluster as an example.

### Prepare Flink Agents

We recommand creating a Python virtual environment to install the Flink Agents Python library.

Follow the [instructions]({{< ref "docs/get-started/installation" >}}) to install the Flink Agents Python and Java libraries.

### Deploy a Standalone Flink Cluster

Download a stable release of Flink 1.20.3, then extract the archive. You can refer to the [local installation](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/try-flink/local_installation/) instructions for more detailed step.

```bash
curl -LO https://archive.apache.org/dist/flink/flink-1.20.3/flink-1.20.3-bin-scala_2.12.tgz
tar -xzf flink-1.20.3-bin-scala_2.12.tgz
```

Deploy a standalone Flink cluster in your local environment with the following command.

```bash
./flink-1.20.3/bin/start-cluster.sh
```

You should be able to navigate to the web UI at [localhost:8081](localhost:8081) to view the Flink dashboard and see that the cluster is up and running.

### Submit to Flink Cluster
```bash
# Set Python environment variable to locate Python libraries , ensuring Flink 
# can find Python dependencies
export PYTHONPATH=$(python -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')

# Run Flink Python Job
# 1. Path note: Replace ./flink-1.20.3 with your actual Flink installation directory
# 2. -py parameter specifies the Python script entry file
# 3. Ensure /path/to/flink_agents_job.py is replaced with your actual file path
./flink-1.20.3/bin/flink run -py /path/to/flink_agents_job.py
```

Now you should see a Flink job submitted to the Flink Cluster in Flink web UI [localhost:8081](localhost:8081)

After a few minutes, you can check for the output in the TaskManager output log.
