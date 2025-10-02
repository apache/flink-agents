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



## Overall

We provide a total of three ways to run the job: Local Run with Test Data, Local Run with Flink MiniCluster, and Run in Flink Cluster. The detailed differences are shown in the table below:

<table class="configuration table table-bordered">
    <thead>
        <tr>
            <th class="text-left" style="width: 30%">Deployment Mode</th>
            <th class="text-left" style="width: 20%">Language Support</th>
            <th class="text-left" style="width: 50%">Typical Use Case</th>
        </tr>
    </thead>
    <tbody>
        <tr>
            <td>Local Run with Test Data</td>
            <td>Only Python</td>
            <td>Validate the internal logic of the Agent.</td>
        </tr>
      	<tr>
            <td>Local Run with Flink MiniCluster</td>
            <td>Python &amp; Java</td>
            <td>Verify upstream/downstream connectivity and schema correctness.</td>
        </tr>
      	<tr>
            <td>Run in Flink Cluster</td>
            <td>Python &amp; Java</td>
            <td>Large-scale data and AI processing in production environments.</td>
        </tr>
    </tbody>
</table>



## Local Run with Test Data

After completing the [installation of flink-agents]({{< ref "docs/get-started/installation" >}}) and [building your agent]({{< ref "docs/development/workflow_agent" >}}), you can test and execute your agent locally using a simple Python script. This allows you to validate logic without requiring a Flink cluster.

### Key Features

- **No Flink Required**: Local execution is ideal for development and testing.
- **Test Data Simulation**: Easily inject mock inputs for validation.
- **IDE Compatibility**: Run directly in your preferred development environment.

### Example for Local Run with Test Data

#### Complete code

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

#### Input Data Format

The input data should be a list of dictionaries (`List[Dict[str, Any]]`) with the following structure:

``````python
[
    {
      	# Optional field: Input key
        "key": "key_1",
        
        # Required field: Input content
        # This becomes the `input` field in InputEvent
        "value": "Calculate the sum of 1 and 2.",
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



## Local Run with Flink MiniCluster

After completing the [installation of flink-agents]({{< ref "docs/get-started/installation" >}}) and [building your agent]({{< ref "docs/development/workflow_agent" >}}), you can test and execute your agent locally using a **Flink MiniCluster**. This allows you to have a lightweight Flink streaming environment without deploying to a full cluster. For more details about how to integrate agents with Flink's `DataStream` or `Table`, please refer to the [Integrate with Flink]({{< ref "docs/development/integrate_with_flink" >}}) documentation.



## Run in Flink Cluster

Flink Agents jobs are deployed and run on the cluster similarly to Pyflink jobs. You can refer to the instructions for [submitting PyFlink jobs](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/deployment/cli/#submitting-pyflink-jobs) for more details.



### Prerequisites

- **Operating System**: Unix-like environment (Linux, macOS, Cygwin, or WSL)  
- **Python**: Version 3.10 or 3.11  
- **Flink**: A running Flink cluster with the Flink Agents dependency installed  



### Prepare Flink Agents

We recommand creating a Python virtual environment to install the Flink Agents Python library.

Follow the [instructions]({{< ref "docs/get-started/installation" >}}) to install the Flink Agents Python and Java libraries.



### Prepare Flink

Download a stable release of Flink 1.20.3, then extract the archive. You can refer to the [local installation](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/try-flink/local_installation/) instructions for more detailed step.

```bash
curl -LO https://archive.apache.org/dist/flink/flink-1.20.3/flink-1.20.3-bin-scala_2.12.tgz
tar -xzf flink-1.20.3-bin-scala_2.12.tgz
```



### Submit to Flink Cluster
```bash
# Run Flink Python Job
# ------------------------------------------------------------------------
# 1. Path Note:
#    Replace "./flink-1.20.3" with the actual Flink installation directory.
#
# 2. Python Entry File:
#    The "--python" parameter specifies the Python script to be executed.
#    Replace "/path/to/flink_agents_job.py" with the full path to your job file.
#
# 3. JobManager Address:
#    Replace "<jobmanagerHost>" with the hostname or IP address of the Flink JobManager.
#    The default REST port is 8081.
#
# 4. Example:
#    ./flink-1.20.3/bin/flink run \
#        --jobmanager localhost:8081 \
#        --python /home/user/flink_jobs/flink_agents_job.py
# ------------------------------------------------------------------------
./flink-1.20.3/bin/flink run \
      --jobmanager <jobmanagerHost>:8081 \
      --python /path/to/flink_agents_job.py
```

Now you should see a Flink job submitted to the Flink Cluster in Flink web UI. After a few minutes, you can check for the output in the TaskManager output log.
