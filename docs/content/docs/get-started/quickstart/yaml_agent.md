---
title: 'YAML Agent'
weight: 3
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

The YAML API lets you declare a Flink Agents application — the agent's actions, tools, prompts, chat models, and other resources — as a single declarative file, and load it into an `AgentsExecutionEnvironment` with one call. Your action and tool implementations still live in Python or Java; the YAML file only describes how those pieces are wired together.

This quickstart runs the same **Review Analysis** workflow as the [Workflow Agent Quickstart]({{< ref "docs/get-started/quickstart/workflow_agent" >}}) — extracting a satisfaction score and dissatisfaction reasons from a stream of product reviews — but declares the agent in YAML and loads it with `load_yaml` / `loadYaml`. The action functions and the `notify_shipping_manager` tool are reused from the workflow-agent example as static methods, so this quickstart shows the **smallest possible delta** between a code-defined agent and a YAML-declared agent.

For the full reference of the YAML format, see the [YAML API]({{< ref "docs/development/yaml" >}}) documentation.

## Code Walkthrough

### Declare the Agent in YAML

The whole agent — chat model, prompt, tool, and the two actions — is declared in `yaml_review_analysis_agent.yaml`. The `function:` strings point at static methods on the existing `ReviewAnalysisAgent` class from the workflow-agent quickstart.

{{< tabs "Declare Agent YAML" >}}

{{< tab "Python" >}}
```yaml
agents:
  - name: review_analysis_agent
    description: |
      YAML-declared review analysis agent. Reuses the static methods of
      ReviewAnalysisAgent from the workflow_agent quickstart as actions
      and tool, but wires everything together through this YAML file
      instead of class-level decorators.

    actions:
      - name: process_input
        function: flink_agents.examples.quickstart.agents.review_analysis_agent:ReviewAnalysisAgent.process_input
        trigger_conditions: [input]
      - name: process_chat_response
        function: flink_agents.examples.quickstart.agents.review_analysis_agent:ReviewAnalysisAgent.process_chat_response
        trigger_conditions: [chat_response]

    chat_model_connections:
      - name: ollama_server
        clazz: ollama
        request_timeout: 120

    prompts:
      - name: review_analysis_prompt
        messages:
          - role: system
            content: |
              Analyze the user review and product information to determine a
              satisfaction score (1-5) and potential reasons for dissatisfaction.

              Example input format:
              {
                  "id": "12345",
                  "review": "The headphones broke after one week. Very poor quality."
              }

              Ensure your response can be parsed by Python JSON, using this format
              as an example:
              {
               "id": "12345",
               "score": 1,
               "reasons": ["poor quality"]
              }

              If the review mentions shipping dissatisfaction, first call
              notify_shipping_manager, then return the JSON above with no
              mention of the tool call.
          - role: user
            content: |
              "input":
              {input}

    chat_model_setups:
      - name: review_analysis_model
        clazz: ollama
        connection: ollama_server
        model: qwen3:8b
        prompt: review_analysis_prompt
        tools: [notify_shipping_manager]
        extract_reasoning: true

    tools:
      - name: notify_shipping_manager
        function: flink_agents.examples.quickstart.agents.review_analysis_agent:ReviewAnalysisAgent.notify_shipping_manager
```
{{< /tab >}}

{{< tab "Java" >}}
```yaml
agents:
  - name: review_analysis_agent
    description: |
      YAML-declared review analysis agent. Reuses the static methods of
      ReviewAnalysisAgent from the workflow_agent quickstart as actions
      and tool, but wires everything together through this YAML file
      instead of class-level annotations.

    actions:
      - name: processInput
        type: java
        function: org.apache.flink.agents.examples.agents.ReviewAnalysisAgent:processInput
        trigger_conditions: [input]
      - name: processChatResponse
        type: java
        function: org.apache.flink.agents.examples.agents.ReviewAnalysisAgent:processChatResponse
        trigger_conditions: [chat_response]

    chat_model_connections:
      - name: ollama_server
        clazz: ollama
        type: java
        endpoint: http://localhost:11434
        requestTimeout: 120

    prompts:
      - name: review_analysis_prompt
        messages:
          - role: system
            content: |
              Analyze the user review and product information to determine a
              satisfaction score (1-5) and potential reasons for dissatisfaction.

              Example input format:
              {
                  "id": "12345",
                  "review": "The headphones broke after one week. Very poor quality."
              }

              Ensure your response can be parsed by Java JSON, using this format
              as an example:
              {
               "id": "12345",
               "score": 1,
               "reasons": ["poor quality"]
              }

              If the review mentions shipping dissatisfaction, first call
              notifyShippingManager, then return the JSON above with no
              mention of the tool call.
          - role: user
            content: |
              "input":
              {input}

    # Action ``processInput`` sends ``ChatRequestEvent("reviewAnalysisModel", ...)``,
    # so the chat-model setup MUST be named ``reviewAnalysisModel``.
    chat_model_setups:
      - name: reviewAnalysisModel
        clazz: ollama
        type: java
        connection: ollama_server
        model: qwen3:8b
        prompt: review_analysis_prompt
        tools: [notifyShippingManager]
        extract_reasoning: true

    tools:
      - name: notifyShippingManager
        type: java
        function: org.apache.flink.agents.examples.agents.ReviewAnalysisAgent:notifyShippingManager
        parameter_types: [java.lang.String, java.lang.String]
```
{{< /tab >}}

{{< /tabs >}}

A few things to notice in the YAML above:

- `clazz: ollama` is an alias resolved by the loader to the full Ollama chat-model class — see the alias table in the [YAML API]({{< ref "docs/development/yaml#class-aliases" >}}) doc.
- `trigger_conditions: [input]` / `[chat_response]` use **event aliases** for the framework's built-in events.
- `function:` strings use the `<module-or-class>:<qualname>` format. The right side is the class-qualified method name, so the YAML reuses the same `process_input` / `processInput` static methods the original `ReviewAnalysisAgent` already defines.
- The prompt is declared inline as a `messages:` list and referenced from the chat-model setup by name.

### Load and Run

In the entry-point script, build the Flink stream as usual, then load the YAML and apply the agent **by name**.

{{< tabs "Load and Run" >}}

{{< tab "Python" >}}
```python
# Set up the Flink streaming environment and the Agents execution environment.
env = StreamExecutionEnvironment.get_execution_environment()
agents_env = AgentsExecutionEnvironment.get_execution_environment(env)

# limit async request to avoid overwhelming ollama server
agents_env.get_config().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2)

# Load the YAML — agents and shared resources are registered on the env.
agents_env.load_yaml(current_dir / "yaml_review_analysis_agent.yaml")

# Read product reviews from a text file as a streaming source.
product_review_stream = env.from_source(
    # Target the single file, not the resources/ dir: Flink's enumerator
    # recurses, so files like skills/SKILL.md would be parsed as reviews.
    source=FileSource.for_record_stream_format(
        StreamFormat.text_line_format(),
        f"file:///{current_dir}/resources/product_review.txt",
    )
    .monitor_continuously(Duration.of_minutes(1))
    .build(),
    watermark_strategy=WatermarkStrategy.no_watermarks(),
    source_name="yaml_review_analysis_example",
).map(lambda x: ProductReview.model_validate_json(x))

# Apply the YAML-declared agent BY NAME.
review_analysis_res_stream = (
    agents_env.from_datastream(
        input=product_review_stream, key_selector=lambda x: x.id
    )
    .apply("review_analysis_agent")
    .to_datastream()
)

review_analysis_res_stream.print()
# Execute the Flink pipeline with the Flink job name.
agents_env.execute("YAML Agent Example Job")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Set up the Flink streaming environment and the Agents execution environment.
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
env.setParallelism(1);
AgentsExecutionEnvironment agentsEnv =
        AgentsExecutionEnvironment.getExecutionEnvironment(env);

// limit async request to avoid overwhelming ollama server
agentsEnv.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2);

// Load the YAML — agents and shared resources are registered on the env.
Path yamlPath = copyResource("yaml/yaml_review_analysis_agent.yaml").toPath();
agentsEnv.loadYaml(yamlPath);

// Read product reviews from input_data.txt file as a streaming source.
File inputDataFile = copyResource("input_data.txt");
DataStream<String> productReviewStream =
        env.fromSource(
                FileSource.forRecordStreamFormat(
                                new TextLineInputFormat(),
                                new Path(inputDataFile.getAbsolutePath()))
                        .build(),
                WatermarkStrategy.noWatermarks(),
                "yaml-review-analysis-example");

// Apply the YAML-declared agent BY NAME.
DataStream<Object> reviewAnalysisResStream =
        agentsEnv
                .fromDataStream(productReviewStream)
                .apply("review_analysis_agent")
                .toDataStream();

reviewAnalysisResStream.print();
// Execute the Flink pipeline with the Flink job name.
agentsEnv.execute("YAML Agent Example Job");
```
{{< /tab >}}

{{< /tabs >}}

The key difference from the code-defined workflow agent quickstart is the pair of calls:

1. `agents_env.load_yaml(...)` / `agentsEnv.loadYaml(...)` — parses the YAML and registers the declared agent(s) and shared resources on the environment.
2. `.apply("review_analysis_agent")` — looks the agent up **by name** instead of passing an `Agent` instance.

Everything else — the Flink source, the key selector, the sink — is identical.

{{< hint info >}}
See [Integrate with Flink]({{< ref "docs/development/integrate_with_flink" >}}) for details on integrating agents with the Flink DataStream and Table API.
{{< /hint >}}

## Run the Example

### Prerequisites

* Unix-like environment (we use Linux, Mac OS X, Cygwin, WSL)
* Git
* Java 11+
* Python 3.10, 3.11 or 3.12

### Preparation

#### Prepare Flink and Flink Agents

Follow the [installation]({{< ref "docs/get-started/installation" >}}) instructions to set up Flink and Flink Agents.

#### Clone the Flink Agents Repository (if not done already)

```bash
git clone https://github.com/apache/flink-agents.git
cd flink-agents
```

{{< hint info >}}
For python examples, you can skip this step and submit the python file in installed flink-agents wheel.
{{< /hint >}}

#### Deploy a Standalone Flink Cluster

You can deploy a standalone Flink cluster in your local environment with the following command.

{{< tabs "Deploy a Standalone Flink Cluster" >}}

{{< tab "Python" >}}
```bash
export PYTHONPATH=$(python -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')
$FLINK_HOME/bin/start-cluster.sh
```
{{< /tab >}}

{{< tab "Java" >}}
1. Build Flink Agents from source to generate example jar. See [installation]({{< ref "docs/get-started/installation" >}}) for more details.
2. Start the Flink cluster
    ```bash
    $FLINK_HOME/bin/start-cluster.sh
    ```

{{< hint info >}}
To run example on JDK 21+, append jvm option `--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED` to [env.java.opts.all](https://nightlies.apache.org/flink/flink-docs-stable/docs/deployment/config/#env-java-opts-all) in `$FLINK_HOME/conf/config.yaml` before start the flink cluster.
{{< /hint >}}
{{< /tab >}}

{{< /tabs >}}

#### Prepare Ollama

Download and install Ollama from the official [website](https://ollama.com/download).

{{< hint info >}}
Ollama server **0.9.0** or higher is required.
{{< /hint >}}

Then pull the `qwen3:8b` model:

```bash
ollama pull qwen3:8b
```

### Submit Flink Agents Job to Standalone Flink Cluster

{{< tabs "Submit YAML Example" >}}

{{< tab "Python" >}}
```bash
export PYTHONPATH=$(python -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')

# Run the YAML-declared review analysis example
$FLINK_HOME/bin/flink run -py ./flink-agents/python/flink_agents/examples/quickstart/yaml_workflow_agent_example.py
# or submit the example python file in installed flink-agents wheel
$FLINK_HOME/bin/flink run -py $PYTHONPATH/flink_agents/examples/quickstart/yaml_workflow_agent_example.py
```
{{< /tab >}}

{{< tab "Java" >}}
```bash
$FLINK_HOME/bin/flink run -c org.apache.flink.agents.examples.YamlWorkflowAgentExample ./flink-agents/examples/target/flink-agents-examples-$VERSION.jar
```
{{< /tab >}}

{{< /tabs >}}

You should see a Flink job submitted to the Flink Cluster in the Flink web UI at [localhost:8081](localhost:8081). After a few minutes, the analysis results — one JSON record per input review — appear in the TaskManager output log.
