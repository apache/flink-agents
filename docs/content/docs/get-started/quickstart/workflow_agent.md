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

A workflow style agent in Flink-Agents is an agent whose reasoning and behavior are organized as a directed workflow of modular steps, called actions, connected by events. This design is inspired by the need to orchestrate complex, multi-stage tasks in a transparent, extensible, and data-centric way, leveraging Apache Flink's streaming architecture.

This quickstart introduces two small, progressive streaming examples that demonstrate how to build LLM-powered workflows with Flink Agents:

- **Review Analysis**: Processes a stream of product reviews and uses a single agent to extract a rating (1–5) and unsatisfied reasons from each review.

- **Product Improvement Suggestions**: Builds on the first example by aggregating per-review analysis in windows to produce product-level summaries (score distribution and common complaints), then applies a second agent to generate concrete improvement suggestions for each product.

Together, these examples show how to build a multi-agent workflow with Flink Agents and run it on a Flink standalone cluster.

## Code Walkthrough

### Prepare Agents Execution Environment

Create the agents execution environment, and register the available chat model connections, which can be used by the agents, to the environment.

{{< tabs "Prepare Agents Execution Environment" >}}

{{< tab "Python" >}}
```python
# Set up the Flink streaming environment and the Agents execution environment.
env = StreamExecutionEnvironment.get_execution_environment()
agents_env = AgentsExecutionEnvironment.get_execution_environment(env)

# Add Ollama chat model connection to be used by the ReviewAnalysisAgent
# and ProductSuggestionAgent.
agents_env.add_resource(
    "ollama_server",
    ollama_server_descriptor,
)
```
{{< /tab >}}

{{< tab "Java" >}}
```Java
// Set up the Flink streaming environment and the Agents execution environment.
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
AgentsExecutionEnvironment agentsEnv =
        AgentsExecutionEnvironment.getExecutionEnvironment(env);

// Add Ollama chat model connection to be used by the ReviewAnalysisAgent.
agentsEnv.addResource(
        "ollamaChatModelConnection",
        ResourceType.CHAT_MODEL_CONNECTION,
        CustomTypesAndResources.OLLAMA_SERVER_DESCRIPTOR);
```
{{< /tab >}}

{{< /tabs >}}

### Create the Agents

Below is the example code for the `ReviewAnalysisAgent`, which is used to analyze the product reviews and generate a satisfaction score and potential reasons for dissatisfaction. It demonstrates how to define the prompt, tool, chat model, and action for the agent. Also, it shows how to process the chat response and send the output event. For more details, please refer to the [Workflow Agent]({{< ref "docs/development/workflow_agent" >}}) documentation.

{{< tabs "Create the Agents" >}}

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
            clazz=OllamaChatModelSetup,
            connection="ollama_server",
            model="qwen3:8b",
            prompt="review_analysis_prompt",
            tools=["notify_shipping_manager"],
            extract_reasoning=True,
        )

    @action(InputEvent)
    @staticmethod
    def process_input(event: InputEvent, ctx: RunnerContext) -> None:
        """Process input event and send chat request for review analysis."""
        input: ProductReview = event.input
        ctx.short_term_memory.set("id", input.id)

        content = f"""
            "id": {input.id},
            "review": {input.review}
        """
        msg = ChatMessage(role=MessageRole.USER, extra_args={"input": content})
        ctx.send_event(ChatRequestEvent(model="review_analysis_model", messages=[msg]))

    @action(ChatResponseEvent)
    @staticmethod
    def process_chat_response(event: ChatResponseEvent, ctx: RunnerContext) -> None:
        """Process chat response event and send output event."""
        try:
            json_content = json.loads(event.response.content)
            ctx.send_event(
                OutputEvent(
                    output=ProductReviewAnalysisRes(
                        id=ctx.short_term_memory.get("id"),
                        score=json_content["score"],
                        reasons=json_content["reasons"],
                    )
                )
            )
        except Exception:
            logging.exception(
                f"Error processing chat response {event.response.content}"
            )

            # To fail the agent, you can raise an exception here.
```
{{< /tab >}}

{{< tab "Java" >}}
```Java
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
        return ResourceDescriptor.Builder.newBuilder(OllamaChatModelSetup.class.getName())
                .addInitialArgument("connection", "ollamaChatModelConnection")
                .addInitialArgument("model", "qwen3:8b")
                .addInitialArgument("prompt", "reviewAnalysisPrompt")
                .addInitialArgument("tools", Collections.singletonList("notifyShippingManager"))
                .addInitialArgument("extract_reasoning", "true")
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
    @Action(listenEvents = {InputEvent.class})
    public static void processInput(InputEvent event, RunnerContext ctx) throws Exception {
        String input = (String) event.getInput();
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        CustomTypesAndResources.ProductReview inputObj =
                MAPPER.readValue(input, CustomTypesAndResources.ProductReview.class);

        ctx.getShortTermMemory().set("id", inputObj.getId());

        String content =
                String.format(
                        "{\n" + "\"id\": %s,\n" + "\"review\": \"%s\"\n" + "}",
                        inputObj.getId(), inputObj.getReview());
        ChatMessage msg = new ChatMessage(MessageRole.USER, "", Map.of("input", content));

        ctx.sendEvent(new ChatRequestEvent("reviewAnalysisModel", List.of(msg)));
    }

    @Action(listenEvents = ChatResponseEvent.class)
    public static void processChatResponse(ChatResponseEvent event, RunnerContext ctx)
            throws Exception {
        JsonNode jsonNode = MAPPER.readTree(event.getResponse().getContent());
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

The code for the `ProductSuggestionAgent`, which is used to generate product improvement suggestions based on the aggregated analysis results, is similar to the `ReviewAnalysisAgent`.

### Integrate the Agents with Flink

Create the input DataStream by reading the product reviews from a text file as a streaming source, and use the `ReviewAnalysisAgent` to analyze the product reviews and generate the result DataStream. Finally print the result DataStream.

{{< tabs "Integrate the Agents with Flink" >}}

{{< tab "Python" >}}
```python
# Read product reviews from a text file as a streaming source.
# Each line in the file should be a JSON string representing a ProductReview.
product_review_stream = env.from_source(
    source=FileSource.for_record_stream_format(
        StreamFormat.text_line_format(), f"file:///{current_dir}/resources"
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

# Execute the Flink pipeline.
agents_env.execute()
```
{{< /tab >}}

{{< tab "Java" >}}
```Java
// Read product reviews from input_data.txt file as a streaming source.
// Each element represents a ProductReview.
DataStream<String> productReviewStream =
       env.fromSource(
               FileSource.forRecordStreamFormat(
                               new TextLineInputFormat(),
                               new Path(inputDataFile.getAbsolutePath()))
                       .build(),
               WatermarkStrategy.noWatermarks(),
               "streaming-agent-example");

// Use the ReviewAnalysisAgent to analyze each product review.
DataStream<Object> reviewAnalysisResStream =
       agentsEnv
               .fromDataStream(productReviewStream)
               .apply(new ReviewAnalysisAgent())
               .toDataStream();

// Print the analysis results to stdout.
reviewAnalysisResStream.print();

// Execute the Flink pipeline.
agentsEnv.execute();
```
{{< /tab >}}

{{< /tabs >}}

## Run the Example

### Prerequisites

* Unix-like environment (we use Linux, Mac OS X, Cygwin, WSL)
* Git
* Java 11
* Python 3.10 or 3.11

### Preparation

#### Prepare Flink and Flink Agents

Follow the [installation]({{< ref "docs/get-started/installation" >}}) instructions to setup Flink and the Flink Agents.

#### Clone the Flink Agents Repository (if not done already)

```bash
git clone https://github.com/apache/flink-agents.git
cd flink-agents
```

#### Deploy a Standalone Flink Cluster

You can deploy a standalone Flink cluster in your local environment with the following command.

{{< tabs "Deploy a Standalone Flink Cluster" >}}

{{< tab "Python" >}}
```bash
export PYTHONPATH=$(python -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')
./flink-1.20.3/bin/start-cluster.sh
```
{{< /tab >}}

{{< tab "Java" >}}
1. Build Flink Agents from source to generate example jar. See [installation]({{< ref "docs/get-started/installation" >}}) for more details.
2. Copy the Flink Agents example jar to Flink lib directory
    ```bash
    cp flink-agents/examples/target/flink-agents-examples-$VERSION.jar ./flink-1.20.3/lib/
    ```
3. Start the Flink cluster
    ```bash
    ./flink-1.20.3/bin/start-cluster.sh
    ```
{{< /tab >}}

{{< /tabs >}}
You can refer to the [local cluster](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/try-flink/local_installation/#starting-and-stopping-a-local-cluster) instructions for more detailed step.


{{< hint info >}}
If you can't navigate to the web UI at [localhost:8081](localhost:8081), you can find the reason in `./flink-1.20.3/log`. If the reason is port conflict, you can change the port in `./flink-1.20.3/conf/config.yaml`.
{{< /hint >}}

#### Prepare Ollama

Download and install Ollama from the official [website](https://ollama.com/download).

Then run the qwen3:8b model, which is required by the quickstart examples

```bash
ollama run qwen3:8b
```

### Submit Flink Agents Job to Standalone Flink Cluster

#### Submit to Flink Cluster

{{< tabs "Submit to Flink Cluster" >}}

{{< tab "Python" >}}
```bash
export PYTHONPATH=$(python -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')

# Run review analysis example
./flink-1.20.3/bin/flink run -py ./flink-agents/python/flink_agents/examples/quickstart/workflow_single_agent_example.py

# Run product suggestion example
./flink-1.20.3/bin/flink run -py ./flink-agents/python/flink_agents/examples/quickstart/workflow_multiple_agent_example.py
```
{{< /tab >}}

{{< tab "Java" >}}
```bash
# Run review analysis example
./flink-1.20.3/bin/flink run -c org.apache.flink.agents.examples.WorkflowSingleAgentExample ./flink-agents/examples/target/flink-agents-examples-$VERSION.jar

# Run product suggestion example
./flink-1.20.3/bin/flink run -c org.apache.flink.agents.examples.WorkflowMultipleAgentExample ./flink-agents/examples/target/flink-agents-examples-$VERSION.jar
```
{{< /tab >}}

{{< /tabs >}}

Now you should see a Flink job submitted to the Flink Cluster in Flink web UI [localhost:8081](
localhost:8081)

After a few minutes, you can check for the output in the TaskManager output log.
