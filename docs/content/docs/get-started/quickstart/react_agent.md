---
title: "Use the Built-in ReAct Agent"
weight: 2
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

Flink Agents provides `ReActAgent` as a built-in subclass of `Agent` that implements the ReAct pattern.

ReAct Agent is a general paradigm that combines reasoning and action capabilities to solve complex tasks. You state the goal in a prompt and expose the capabilities as tools, and the model decides how to reach the goal and when to use them.

This example runs a streaming ReAct Agent over product reviews. It extracts a satisfaction
score (1–5) and the unsatisfied reasons from each review, and calls the
`notify_shipping_manager` tool when a review complains about shipping.

## Complete Example

| Language | Example job | Shared types and prompts |
|----------|-------------|----------------------|
| Python | {{< gh_link file="python/flink_agents/examples/quickstart/react_agent_example.py" name="react_agent_example.py" >}} | {{< gh_link file="python/flink_agents/examples/quickstart/agents/custom_types_and_resources.py" name="custom_types_and_resources.py" >}} |
| Java | {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/ReActAgentExample.java" name="ReActAgentExample.java" >}} | {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/agents/CustomTypesAndResources.java" name="CustomTypesAndResources.java" >}} |

`ReActAgent` is a built-in class, so this example declares no Agent subclass of its own and
configures the Agent inside the job. Its tool is `notify_shipping_manager`, the one the first
walkthrough used, registered with `Tool.from_callable` in Python and
`Tool.fromMethod` in Java. Python passes the prompt `review_analysis_react_prompt` from the shared
file, and Java declares a `@Prompt` method that returns the shared
`REVIEW_ANALYSIS_REACT_PROMPT`. The reviews are read from
{{< gh_link file="python/flink_agents/examples/quickstart/resources/product_review.txt" name="product_review.txt" >}}
in Python and
{{< gh_link file="examples/src/main/resources/input_data.txt" name="input_data.txt" >}}
in Java. See [Use the ReAct Agent]({{< ref "docs/development/react_agent" >}}) for the
built-in Agent's full options.

## Code Walkthrough

### Create the Execution Environment

An `AgentsExecutionEnvironment` wraps a Flink `StreamExecutionEnvironment` and is the entry
point that attaches an Agent to a stream. The example also caps how many chat requests run at once.

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
AgentsExecutionEnvironment agentsEnv =
        AgentsExecutionEnvironment.getExecutionEnvironment(env);

// limit async request to avoid overwhelming ollama server
agentsEnv.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2);
```
{{< /tab >}}

{{< /tabs >}}

### Register the Connection and the Tool

Register the Ollama connection and the notification tool as named Resources of types
`CHAT_MODEL_CONNECTION` and `TOOL`. The chat model setup refers to them by name through its
`connection` and `tools` fields. See
[Registering Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}})
for the shared registration and access model.

{{< tabs "Register the Connection and the Tool" >}}

{{< tab "Python" >}}
```python
# Add Ollama chat model connection and notify shipping manager tool to be used
# by the Agent.
agents_env.add_resource(
    "ollama_server",
    ResourceType.CHAT_MODEL_CONNECTION,
    ResourceDescriptor(
        clazz=ResourceName.ChatModel.OLLAMA_CONNECTION, request_timeout=120
    ),
).add_resource(
    "notify_shipping_manager",
    ResourceType.TOOL,
    Tool.from_callable(notify_shipping_manager),
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Add Ollama chat model connection and notify shipping manager tool to be used
// by the Agent.
agentsEnv
        .addResource(
                "ollamaChatModelConnection",
                ResourceType.CHAT_MODEL_CONNECTION,
                CustomTypesAndResources.OLLAMA_SERVER_DESCRIPTOR)
        .addResource(
                "notifyShippingManager",
                ResourceType.TOOL,
                org.apache.flink.agents.api.tools.Tool.fromMethod(
                        ReActAgentExample.class.getMethod(
                                "notifyShippingManager", String.class, String.class)));
```
{{< /tab >}}

{{< /tabs >}}

### Create the ReAct Agent

Configure the built-in `ReActAgent` with the chat model it calls, the prompt that states the
task, and the schema its answer is parsed into. The `chat_model` descriptor names the
connection and the tool registered above, and `model: qwen3:8b` selects the model on the
Ollama server. Java keeps the prompt in a `@Prompt` method of the job class and passes its return
value to the constructor. See
[Use the ReAct Agent]({{< ref "docs/development/react_agent" >}}) for the other options this
Agent accepts.

{{< tabs "Create the ReAct Agent" >}}

{{< tab "Python" >}}
```python
# Create react agent
review_analysis_react_agent = ReActAgent(
    chat_model=ResourceDescriptor(
        clazz=ResourceName.ChatModel.OLLAMA_SETUP,
        connection="ollama_server",
        model="qwen3:8b",
        tools=["notify_shipping_manager"],
    ),
    prompt=review_analysis_react_prompt,
    output_schema=ProductReviewAnalysisRes,
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Create react agent
ReActAgent reviewAnalysisReactAgent = new ReActAgent(
        ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "ollamaChatModelConnection")
                .addInitialArgument("model", "qwen3:8b")
                .addInitialArgument(
                        "tools", Collections.singletonList("notifyShippingManager"))
                .build(),
        reviewAnalysisReactPrompt(),
        CustomTypesAndResources.ProductReviewAnalysisRes.class);
```
{{< /tab >}}

{{< /tabs >}}

### Integrate the ReAct Agent with Flink

Read the product reviews from the text file, then apply the Agent to that stream and print the
results. Java parses each line into a `Row` with `id` and `review` fields before the Agent sees it.

{{< tabs "Integrate the ReAct Agent with Flink" >}}

{{< tab "Python" >}}
```python
# Read product reviews from a text file as a streaming source.
# Each line in the file should be a JSON string representing a ProductReview.
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
    source_name="streaming_agent_example",
).map(
    lambda x: ProductReview.model_validate_json(
        x
    )  # Deserialize JSON to ProductReview.
)

# Use the ReAct agent to analyze each product review and notify the shipping manager
# when needed.
review_analysis_res_stream = (
    agents_env.from_datastream(
        input=product_review_stream, key_selector=lambda x: x.id
    )
    .apply(review_analysis_react_agent)
    .to_datastream()
)

# Print the analysis results to stdout.
review_analysis_res_stream.print()

# Execute the Flink pipeline with the Flink job name.
agents_env.execute("ReAct Agent Example Job")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Read product reviews from input_data.txt file as a streaming source.
// Each element represents a ProductReview.
File inputDataFile = copyResource("input_data.txt");

DataStream<Row> productReviewStream =
        env.fromSource(
                        FileSource.forRecordStreamFormat(
                                        new TextLineInputFormat(),
                                        new Path(inputDataFile.getAbsolutePath()))
                                .monitorContinuously(Duration.ofMinutes(1))
                                .build(),
                        WatermarkStrategy.noWatermarks(),
                        "react-agent-example")
                .map(
                        inputStr -> {
                            Row row = Row.withNames();
                            CustomTypesAndResources.ProductReview productReview =
                                    MAPPER.readValue(
                                            inputStr,
                                            CustomTypesAndResources.ProductReview.class);
                            row.setField("id", productReview.getId());
                            row.setField("review", productReview.getReview());
                            return row;
                        });

// Use the ReAct agent to analyze each product review and
// record shipping question.
DataStream<Object> reviewAnalysisResStream =
        agentsEnv
                .fromDataStream(productReviewStream)
                .apply(reviewAnalysisReactAgent)
                .toDataStream();

// Print the analysis results to stdout.
reviewAnalysisResStream.print();

// Execute the Flink pipeline with the Flink job name.
agentsEnv.execute("ReAct Agent Example Job");
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

{{< tabs "Submit to Flink Cluster" >}}

{{< tab "Python" >}}
```bash
# Or submit the copy inside the installed package, at
# $PYTHONPATH/flink_agents/examples/quickstart/react_agent_example.py
$FLINK_HOME/bin/flink run -py python/flink_agents/examples/quickstart/react_agent_example.py
```
{{< /tab >}}

{{< tab "Java" >}}
```bash
$FLINK_HOME/bin/flink run -c org.apache.flink.agents.examples.ReActAgentExample \
  examples/target/flink-agents-examples-*.jar
```
{{< /tab >}}

{{< /tabs >}}

The job appears in the Flink web UI at [localhost:8081](http://localhost:8081). After a few
minutes, one result per review appears in the TaskManager output log under
`$FLINK_HOME/log`.
