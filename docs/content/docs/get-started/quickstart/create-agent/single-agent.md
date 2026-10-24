---
title: "Build Your First Agent"
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

Build an Agent that analyzes a stream of product reviews. The job reads one review per line from a
text file, asks a local Ollama model for a satisfaction score and the reasons behind it, and prints
one result per review.

## Complete Example

| Language | Example job | Agent definition | Shared types and resources |
|----------|-------------|------------------|----------------------------|
| Python | {{< gh_link file="python/flink_agents/examples/quickstart/workflow_single_agent_example.py" name="workflow_single_agent_example.py" >}} | {{< gh_link file="python/flink_agents/examples/quickstart/agents/review_analysis_agent.py" name="review_analysis_agent.py" >}} | {{< gh_link file="python/flink_agents/examples/quickstart/agents/custom_types_and_resources.py" name="custom_types_and_resources.py" >}} |
| Java | {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/WorkflowSingleAgentExample.java" name="WorkflowSingleAgentExample.java" >}} | {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/agents/ReviewAnalysisAgent.java" name="ReviewAnalysisAgent.java" >}} | {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/agents/CustomTypesAndResources.java" name="CustomTypesAndResources.java" >}} |

The reviews come from
{{< gh_link file="python/flink_agents/examples/quickstart/resources/product_review.txt" name="product_review.txt" >}}
in Python and
{{< gh_link file="examples/src/main/resources/input_data.txt" name="input_data.txt" >}}
in Java.

## Code Walkthrough

### Create the Execution Environment

An `AgentsExecutionEnvironment` wraps a Flink `StreamExecutionEnvironment` and is the
entry point that attaches an Agent to a stream. The job also pins parallelism to one and
caps how many chat requests run at once.

{{< tabs "Create the Execution Environment" >}}

{{< tab "Python" >}}
```python
# Set up the Flink streaming environment and the Agents execution environment.
env = StreamExecutionEnvironment.get_execution_environment()
env.set_parallelism(1)
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

### Register the Chat Model Connection

Actions and Resources find a Resource by name, so register the Ollama connection on the
environment before you apply an Agent. `ollama_server_descriptor` and
`OLLAMA_SERVER_DESCRIPTOR` are the descriptors in the shared types file above, and each
declares `ResourceName.ChatModel.OLLAMA_CONNECTION`.

{{< tabs "Register the Chat Model Connection" >}}

{{< tab "Python" >}}
```python
# Add Ollama chat model connection to be used by the ReviewAnalysisAgent.
agents_env.add_resource(
    "ollama_server",
    ResourceType.CHAT_MODEL_CONNECTION,
    ollama_server_descriptor,
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Add Ollama chat model connection to be used by the ReviewAnalysisAgent.
agentsEnv.addResource(
        "ollamaChatModelConnection",
        ResourceType.CHAT_MODEL_CONNECTION,
        CustomTypesAndResources.OLLAMA_SERVER_DESCRIPTOR);
```
{{< /tab >}}

{{< /tabs >}}

Registering on the environment makes the Resource visible to every Agent applied to it.
See
[Registering Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}})
for the shared registration and access model.

### Create the Agent

`ReviewAnalysisAgent` declares one prompt, one tool, one chat model setup, and two Actions. The
setup references the registered connection by name, and the input Action sends a `ChatRequestEvent`
that names the setup. The [built-in chat Action]({{< ref "docs/development/calling-llms/chat#chat-events" >}})
gets and calls that Resource, then sends a response Event for the output Action to handle.

{{< tabs "Create the Agent" >}}

{{< tab "Python" >}}
```python
class ReviewAnalysisAgent(Agent):
    """An agent that uses a large language model (LLM) to analyze product reviews
    and generate a satisfaction score and potential reasons for dissatisfaction.

    This agent receives a product review and produces a satisfaction score and a list
    of reasons for dissatisfaction. It handles prompt construction, LLM interaction,
    and output parsing.
    """

    @prompt
    @staticmethod
    def review_analysis_prompt() -> Prompt:
        """Prompt for review analysis."""
        return review_analysis_prompt

    @tool
    @staticmethod
    def notify_shipping_manager(id: str, review: str) -> None:
        """Notify the shipping manager when product received a negative review due to
        shipping damage.

        Parameters
        ----------
        id : str
            The id of the product that received a negative review due to shipping damage
        review: str
            The negative review content
        """
        # reuse the declared function, but for parsing the tool metadata, we write doc
        # string here again.
        notify_shipping_manager(id=id, review=review)

    @chat_model_setup
    @staticmethod
    def review_analysis_model() -> ResourceDescriptor:
        """ChatModel which focus on review analysis."""
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_SETUP,
            connection="ollama_server",
            model="qwen3:8b",
            prompt="review_analysis_prompt",
            tools=["notify_shipping_manager"],
            extract_reasoning=True,
        )

    @action(EventType.InputEvent)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        """Process input event and send chat request for review analysis."""
        input = ProductReview.model_validate(cast("InputEvent", event).input)
        ctx.short_term_memory.set("id", input.id)

        content = f"""
            "id": {input.id},
            "review": {input.review}
        """
        msg = ChatMessage.user("")
        ctx.send_event(
            ChatRequestEvent(
                model="review_analysis_model",
                messages=[msg],
                prompt_args={"input": content},
            )
        )

    @action(EventType.ChatResponseEvent)
    @staticmethod
    def process_chat_response(event: Event, ctx: RunnerContext) -> None:
        """Process chat response event and send output event."""
        chat_response = cast("ChatResponseEvent", event)
        # Fail fast on a malformed LLM response: a parse error here propagates
        # and fails the agent, so a dropped input is surfaced instead of
        # silently lost. In production, choose the handling that fits your
        # pipeline: raise to fail the input (as below), emit an OutputEvent
        # carrying an error sentinel, or send a custom error event so
        # downstream operators can detect the failure.
        json_content = json.loads(chat_response.response.text)
        ctx.send_event(
            OutputEvent(
                output=ProductReviewAnalysisRes(
                    id=ctx.short_term_memory.get("id"),
                    score=json_content["score"],
                    reasons=json_content["reasons"],
                )
            )
        )
```
{{< /tab >}}

{{< tab "Java" >}}
```java
/**
 * An agent that uses a large language model (LLM) to analyze product reviews and generate a
 * satisfaction score and potential reasons for dissatisfaction.
 *
 * <p>This agent receives a product review and produces a satisfaction score and a list of reasons
 * for dissatisfaction. It handles prompt construction, LLM interaction, and output parsing.
 */
public class ReviewAnalysisAgent extends Agent {

private static final ObjectMapper MAPPER = new ObjectMapper();

@Prompt
public static org.apache.flink.agents.api.prompt.Prompt reviewAnalysisPrompt() {
    return REVIEW_ANALYSIS_PROMPT;
}

@ChatModelSetup
public static ResourceDescriptor reviewAnalysisModel() {
    return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
            .addInitialArgument("connection", "ollamaChatModelConnection")
            .addInitialArgument("model", "qwen3:8b")
            .addInitialArgument("prompt", "reviewAnalysisPrompt")
            .addInitialArgument("tools", Collections.singletonList("notifyShippingManager"))
            .addInitialArgument("extract_reasoning", true)
            .build();
}

/**
 * Tool for notifying the shipping manager when product received a negative review due to
 * shipping damage.
 *
 * @param id The id of the product that received a negative review due to shipping damage
 * @param review The negative review content
 */
@Tool(
        description =
                "Notify the shipping manager when product received a negative review due to shipping damage.")
public static void notifyShippingManager(
        @ToolParam(name = "id") String id, @ToolParam(name = "review") String review) {
    CustomTypesAndResources.notifyShippingManager(id, review);
}

/** Process input event and send chat request for review analysis. */
@Action(EventType.InputEvent)
public static void processInput(Event event, RunnerContext ctx) throws Exception {
    InputEvent inputEvent = (InputEvent) event;
    CustomTypesAndResources.ProductReview inputObj =
            (CustomTypesAndResources.ProductReview) inputEvent.getInput();

    ctx.getShortTermMemory().set("id", inputObj.getId());

    String content = MAPPER.writeValueAsString(inputObj);
    ChatMessage msg = new ChatMessage(MessageRole.USER, "");

    ctx.sendEvent(
            new ChatRequestEvent(
                    "reviewAnalysisModel", List.of(msg), Map.of("input", content), null));
}

@Action(EventType.ChatResponseEvent)
public static void processChatResponse(Event event, RunnerContext ctx) throws Exception {
    ChatResponseEvent chatResponse = (ChatResponseEvent) event;
    // Fail fast on a malformed LLM response: a parse error here propagates and fails the
    // agent, so a dropped input is surfaced instead of silently lost. In production, choose
    // the handling that fits your pipeline: raise to fail the input (as below), emit an
    // OutputEvent carrying an error sentinel, or send a custom error event so downstream
    // operators can detect the failure.
    JsonNode jsonNode = MAPPER.readTree(chatResponse.getResponse().getText());
    JsonNode scoreNode = jsonNode.findValue("score");
    JsonNode reasonsNode = jsonNode.findValue("reasons");
    if (scoreNode == null || reasonsNode == null) {
        throw new IllegalStateException(
                "Invalid response from LLM: missing 'score' or 'reasons' field.");
    }
    List<String> result = new ArrayList<>();
    if (reasonsNode.isArray()) {
        for (JsonNode node : reasonsNode) {
            result.add(node.asText());
        }
    }

    ctx.sendEvent(
            new OutputEvent(
                    new CustomTypesAndResources.ProductReviewAnalysisRes(
                            ctx.getShortTermMemory().get("id").getValue().toString(),
                            scoreNode.asInt(),
                            result)));
}
}
```
{{< /tab >}}

{{< /tabs >}}

### Integrate the Agent with Flink

Read the product reviews from the text file as a streaming source, apply the Agent to that
stream, and print the results.

{{< tabs "Integrate the Agent with Flink" >}}

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

# Use the ReviewAnalysisAgent to analyze each product review.
review_analysis_res_stream = (
    agents_env.from_datastream(
        input=product_review_stream, key_selector=lambda x: x.id
    )
    .apply(ReviewAnalysisAgent())
    .to_datastream()
)

# Print the analysis results to stdout.
review_analysis_res_stream.print()

# Execute the Flink pipeline with the Flink job name.
agents_env.execute("Workflow Agent Example Job")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Configure this mapper once on the enclosing example class:
// private static final ObjectMapper MAPPER = new ObjectMapper()
//         .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
// ProductReview is CustomTypesAndResources.ProductReview.
// Read product reviews from input_data.txt file as a streaming source.
// Each element represents a ProductReview.
File inputDataFile = copyResource("input_data.txt");
DataStream<ProductReview> productReviewStream =
        env.fromSource(
                        FileSource.forRecordStreamFormat(
                                        new TextLineInputFormat(),
                                        new Path(inputDataFile.getAbsolutePath()))
                                .build(),
                        WatermarkStrategy.noWatermarks(),
                        "streaming-agent-example")
                .map(json -> MAPPER.readValue(json, ProductReview.class))
                .returns(ProductReview.class);

// Use the ReviewAnalysisAgent to analyze each product review.
DataStream<Object> reviewAnalysisResStream =
        agentsEnv
                .fromDataStream(productReviewStream, ProductReview::getId)
                .apply(new ReviewAnalysisAgent())
                .toDataStream();

// Print the analysis results to stdout.
reviewAnalysisResStream.print();

// Execute the Flink pipeline with the Flink job name.
agentsEnv.execute("Workflow Agent Example Job");
```
{{< /tab >}}

{{< /tabs >}}

## Run the Example

Install Flink and Flink Agents, get the example source, prepare Ollama, and start a local
cluster as described in
[Before You Begin]({{< ref "docs/get-started/quickstart#before-you-begin" >}}). From the
repository root, submit the job:

{{< tabs "Submit to Flink Cluster" >}}

{{< tab "Python" >}}
```bash
# Submitted from the repository, or from the installed package by replacing the path with
# $PYTHONPATH/flink_agents/examples/quickstart/workflow_single_agent_example.py
$FLINK_HOME/bin/flink run -py python/flink_agents/examples/quickstart/workflow_single_agent_example.py
```
{{< /tab >}}

{{< tab "Java" >}}
```bash
$FLINK_HOME/bin/flink run -c org.apache.flink.agents.examples.WorkflowSingleAgentExample \
  examples/target/flink-agents-examples-*.jar
```
{{< /tab >}}

{{< /tabs >}}

The job appears in the Flink web UI at [localhost:8081](http://localhost:8081). After a few
minutes, one result per review appears in the TaskManager output log under
`$FLINK_HOME/log`.

Continue with [Connect Multiple Agents]({{< ref "docs/get-started/quickstart/create-agent/multiple-agents" >}}), or explore [Skills]({{< ref "docs/development/calling-llms/skills" >}}).
