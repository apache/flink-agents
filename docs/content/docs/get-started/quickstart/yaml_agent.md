---
title: "Define an Agent with YAML"
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

The YAML API lets you declare an Agent's Actions and named Resources in a file and load it
into an `AgentsExecutionEnvironment`. It uses the same
[core concepts]({{< ref "docs/development/create-agent/overview#core-concepts" >}})
as the Java and Python APIs. Your Action and tool implementations still live in Python or Java;
the YAML file declares their configuration and connections.

This quickstart runs the same Review Analysis job as
[Build Your First Agent]({{< ref "docs/get-started/quickstart/create-agent/single-agent" >}}),
which scores each product review and reports the reasons behind the score. It declares the Agent in a
YAML file instead of in code. Load the file with `load_yaml` / `loadYaml`, then apply the Agent by its
YAML `name`. Its Actions and tool are the same `ReviewAnalysisAgent` static methods, and the chat
model connection is declared in the file rather than registered on the environment.

For the full reference of the YAML format, see the [YAML API]({{< ref "docs/development/create-agent/yaml" >}}) documentation.

## Complete Example

| Language | Example job | Agent declaration |
|----------|-------------|-------------------|
| Python | {{< gh_link file="python/flink_agents/examples/quickstart/yaml_workflow_agent_example.py" name="yaml_workflow_agent_example.py" >}} | {{< gh_link file="python/flink_agents/examples/quickstart/yaml_review_analysis_agent.yaml" name="yaml_review_analysis_agent.yaml" >}} |
| Java | {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/YamlWorkflowAgentExample.java" name="YamlWorkflowAgentExample.java" >}} | {{< gh_link file="examples/src/main/resources/yaml/yaml_review_analysis_agent.yaml" name="yaml_review_analysis_agent.yaml" >}} |

The Actions and tool are the `ReviewAnalysisAgent` static methods used by
[Build Your First Agent]({{< ref "docs/get-started/quickstart/create-agent/single-agent" >}}),
and the reviews come from the same input file.

## Code Walkthrough

### Declare the Agent in YAML

One file declares the Agent's chat model connection and setup, its prompt, its tool, and its two
Actions. Each `function:` string names a static method of the `ReviewAnalysisAgent` class that
Build Your First Agent defines, in the form `<module-or-class>:<qualname>`.

{{< tabs "Declare Agent YAML" >}}

{{< tab "Python" >}}
```yaml
agents:
  - name: review_analysis_agent
    description: |
      YAML-declared review analysis agent. Reuses the static methods of
      ReviewAnalysisAgent from the single-Agent quickstart as actions
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

              Ensure your response can be parsed by Python JSON, using this
              format as an example:
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

    # Action ``process_input`` sends ``ChatRequestEvent(model="review_analysis_model", ...)``,
    # so the chat-model setup MUST be named ``review_analysis_model``.
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
      ReviewAnalysisAgent from the single-Agent quickstart as actions
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

              Ensure your response can be parsed by Java JSON, using this
              format as an example:
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

Notes on the file above:

- A Resource's `name` is the name Actions and other Resources use to find it, and the section it
  sits in gives its type, so `chat_model_setups` declares chat model Resources. See
  [Registering and Referencing Resources]({{< ref "docs/development/create-agent/overview#registering-and-referencing-resources" >}}).
- The chat model setup's `connection`, `prompt`, and `tools` fields name Resources the same file
  declares. Its `model: qwen3:8b` field selects the model on the Ollama server.
- `clazz: ollama` is a provider alias for the Ollama chat model classes, and
  `trigger_conditions: [input]` / `[chat_response]` are event aliases for the framework's built-in
  events. See [Provider aliases]({{< ref "docs/development/create-agent/yaml#provider-aliases" >}})
  for the alias tables and the exact-match rule.

### Create the Execution Environment

An `AgentsExecutionEnvironment` wraps a Flink `StreamExecutionEnvironment`, as in the single-Agent
walkthrough, and the example caps how many chat requests run at once. No connection registration
appears here, because the YAML file declares the connection and `load_yaml` registers it.

{{< tabs "Create the Execution Environment" >}}

{{< tab "Python" >}}
```python
# Set up the Flink streaming environment and the Agents execution environment.
env = StreamExecutionEnvironment.get_execution_environment()
agents_env = AgentsExecutionEnvironment.get_execution_environment(env)

# limit async request to avoid overwhelming ollama server
agents_env.get_config().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2)
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
```
{{< /tab >}}

{{< /tabs >}}

### Load the YAML and Apply the Agent

Load the file, then apply the Agent by the `name` it declares. Two calls carry the difference from
Build Your First Agent:

1. `agents_env.load_yaml(...)` / `agentsEnv.loadYaml(...)` registers the declared Agent and its
   Resources on the environment.
2. `.apply("review_analysis_agent")` looks the Agent up by name rather than taking an `Agent`
   instance.

The rest of the job matches the walkthrough.

{{< tabs "Load the YAML and Apply the Agent" >}}

{{< tab "Python" >}}
```python
# Load the YAML — the declared agent and its chat-model connection,
# chat-model setup, prompt, tool, and actions are all registered on
# the environment in this single call.
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
).map(
    lambda x: ProductReview.model_validate_json(
        x
    )  # Deserialize JSON to ProductReview.
)

# Apply the YAML-declared agent BY NAME — ``apply`` accepts either an
# Agent instance or the name of an agent registered on the environment.
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
// Load the YAML — the declared agent and its chat-model connection,
// chat-model setup, prompt, tool, and actions are all registered on
// the environment in this single call.
File yamlFile = copyResource("yaml/yaml_review_analysis_agent.yaml");
agentsEnv.loadYaml(yamlFile.toPath());

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

// Apply the YAML-declared agent BY NAME — `apply` accepts either an
// Agent instance or the name of an agent registered on the environment.
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

{{< hint info >}}
See [Integrate with Flink]({{< ref "docs/development/integrate_with_flink" >}}) for details on integrating agents with the Flink DataStream and Table API.
{{< /hint >}}

## Run the Example

Install Flink and Flink Agents, get the example source, prepare Ollama, and start a local
cluster as described in
[Before You Begin]({{< ref "docs/get-started/quickstart#before-you-begin" >}}). From the
repository root, submit the job:

{{< tabs "Submit YAML Example" >}}

{{< tab "Python" >}}
```bash
# Or submit the copy inside the installed package, at
# $PYTHONPATH/flink_agents/examples/quickstart/yaml_workflow_agent_example.py
$FLINK_HOME/bin/flink run -py python/flink_agents/examples/quickstart/yaml_workflow_agent_example.py
```
{{< /tab >}}

{{< tab "Java" >}}
```bash
$FLINK_HOME/bin/flink run -c org.apache.flink.agents.examples.YamlWorkflowAgentExample \
  examples/target/flink-agents-examples-*.jar
```
{{< /tab >}}

{{< /tabs >}}

The job appears in the Flink web UI at [localhost:8081](http://localhost:8081). After a few
minutes, one JSON record per input review appears in the TaskManager output log under
`$FLINK_HOME/log`.
