---
title: "Connect Multiple Agents"
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

This example continues
[Build Your First Agent]({{< ref "docs/get-started/quickstart/create-agent/single-agent" >}}).
It connects two Agents in one Flink job. The review Agent analyzes each review on its own, and
the suggestion Agent turns a window of those analyses into product improvement suggestions.

Two things change from Build Your First Agent. The input arrives through the Flink Table API, and
the analyses are aggregated in a window before the suggestion Agent sees them. Connecting a stream
to an Agent takes one of two forms. That page uses `from_datastream` / `fromDataStream`, and this
example uses `from_table` / `fromTable`. Start from the
[execution environment]({{< ref "docs/get-started/quickstart/create-agent/single-agent#create-the-execution-environment" >}})
and the
[registered connection]({{< ref "docs/get-started/quickstart/create-agent/single-agent#register-the-chat-model-connection" >}})
of that page. Both Agents read Resources from that one environment by name.

## Complete Example

| Language | Example job | Agent definitions | Shared types and resources |
|----------|-------------|-------------------|----------------------------|
| Python | {{< gh_link file="python/flink_agents/examples/quickstart/workflow_multiple_agent_example.py" name="workflow_multiple_agent_example.py" >}} | {{< gh_link file="python/flink_agents/examples/quickstart/agents/table_review_analysis_agent.py" name="table_review_analysis_agent.py" >}}, {{< gh_link file="python/flink_agents/examples/quickstart/agents/product_suggestion_agent.py" name="product_suggestion_agent.py" >}} | {{< gh_link file="python/flink_agents/examples/quickstart/agents/custom_types_and_resources.py" name="custom_types_and_resources.py" >}} |
| Java | {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/WorkflowMultipleAgentExample.java" name="WorkflowMultipleAgentExample.java" >}} | {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/agents/TableReviewAnalysisAgent.java" name="TableReviewAnalysisAgent.java" >}}, {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/agents/ProductSuggestionAgent.java" name="ProductSuggestionAgent.java" >}} | {{< gh_link file="examples/src/main/java/org/apache/flink/agents/examples/agents/CustomTypesAndResources.java" name="CustomTypesAndResources.java" >}} |

The example defines two new Agent classes and reuses the prompt objects, the tool, and the chat
model connection declared in Build Your First Agent.

## Code Walkthrough

### Create the Environments

The Table API needs a `StreamTableEnvironment`. Pass it to the agents environment alongside the
Flink environment, and `from_table` / `fromTable` becomes available on it.

{{< tabs "Create the Environments" >}}

{{< tab "Python" >}}
```python
# Set up the Flink streaming environment and the Agents execution environment.
config = Configuration()
config.set_string("pipeline.auto-watermark-interval", "1ms")
config.set_string("python.fn-execution.bundle.size", "1")
env = StreamExecutionEnvironment.get_execution_environment(config)
env.set_parallelism(1)

# Create StreamTableEnvironment for Table API support.
t_env = StreamTableEnvironment.create(stream_execution_environment=env)

# Create AgentsExecutionEnvironment with both env and t_env to enable
# Table API integration.
agents_env = AgentsExecutionEnvironment.get_execution_environment(
    env=env, t_env=t_env
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Set up the Flink streaming environment.
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
env.setParallelism(1);

// Create StreamTableEnvironment for Table API support.
StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env);

// Create AgentsExecutionEnvironment with both env and tableEnv to enable
// Table API integration.
AgentsExecutionEnvironment agentsEnv =
        AgentsExecutionEnvironment.getExecutionEnvironment(env, tableEnv);
```
{{< /tab >}}

{{< /tabs >}}

The example also caps how many chat requests run at once and registers the Ollama connection, both
exactly as Build Your First Agent does. The two `Configuration` entries above set Flink options
rather than Agent options.

### Declare the Source Table

Register the review file as a temporary table, then read it:

{{< tabs "Declare the Source Table" >}}

{{< tab "Python" >}}
```python
# Define the source table with watermark based on the 'ts' column.
t_env.create_temporary_table(
    "product_reviews",
    TableDescriptor.for_connector("filesystem")
    .schema(
        Schema.new_builder()
        .column("id", DataTypes.STRING())
        .column("review", DataTypes.STRING())
        .column("ts", DataTypes.BIGINT())
        .column_by_expression("rowtime", "TO_TIMESTAMP_LTZ(`ts` * 1000, 3)")
        .watermark("rowtime", "rowtime - INTERVAL '0' SECOND")
        .build()
    )
    .option("format", "json")
    .option("path", f"file:///{current_dir}/resources/product_review.txt")
    .build(),
)

input_table = t_env.from_path("product_reviews").select(
    col("id"), col("review"), col("ts")
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Read product reviews from input_data.txt file using Flink Table API.
// Each line in the file is a JSON string representing a product review.
File inputDataFile = copyResource("input_data.txt");
tableEnv.createTemporaryTable(
        "product_reviews",
        TableDescriptor.forConnector("filesystem")
                .schema(
                        Schema.newBuilder()
                                .column("id", DataTypes.STRING())
                                .column("review", DataTypes.STRING())
                                .build())
                .option("format", "json")
                .option("path", inputDataFile.getAbsolutePath())
                .build());

Table inputTable = tableEnv.from("product_reviews");
```
{{< /tab >}}

{{< /tabs >}}

The Python table derives a row-time attribute from a `ts` column and declares a watermark on it.
The Java table declares only the `id` and `review` columns.

### Analyze Each Row with the Review Agent

A Table row reaches the Action as a `pyflink.common.Row` in Python and an
`org.apache.flink.types.Row` in Java. In Python, access fields with `row["id"]` or call
`row.as_dict()` when dictionary methods such as `.get()` are needed. Build Your First Agent
gave its Agent a `ProductReview` object in Python and a JSON string in Java, so this example
defines a second Agent class, `TableReviewAnalysisAgent`, for the row input. It redeclares the
prompt, tool, and chat model setup that `ReviewAnalysisAgent` declares, and `ReviewAnalysisAgent`
itself is unchanged. The key selector that reads `id` out of a row travels with this Agent, and
`from_table` takes it as an argument.

{{< tabs "Create the Table Agent" >}}

{{< tab "Python" >}}
```python
class TableKeySelector(KeySelector):
    """KeySelector for extracting the product id from a Table Row."""

    def get_key(self, value: Any) -> str:
        if isinstance(value, dict):
            return str(value["id"])
        # Fallback for other types (e.g., Row)
        return str(value["id"]) if hasattr(value, "__getitem__") else str(value.id)


class TableReviewAnalysisAgent(Agent):
    # prompt / tool / chat_model_setup redeclare the ones ReviewAnalysisAgent declares.

    @action(EventType.InputEvent)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        """Process input event from Table data (Row format).

        When using from_table(), the input is a Row with fields matching
        the table column names. Use row.as_dict() if a dictionary is needed.
        """
        input_row = cast("InputEvent", event).input
        product_id = str(input_row["id"])
        review_text = str(input_row["review"])
        # Rest of the body is the one Build Your First Agent shows. It keeps the id in
        # short-term memory and sends ChatRequestEvent.
        ...
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class TableReviewAnalysisAgent extends Agent {

    /** Key selector for extracting keys from Row objects used with Table API input. */
    public static class RowKeySelector implements KeySelector<Object, String> {
        @Override
        public String getKey(Object value) {
            if (value instanceof Row) {
                Row row = (Row) value;
                return (String) row.getField("id");
            }
            return "";
        }
    }

    // prompt / tool / chat model setup redeclare the ones ReviewAnalysisAgent declares.

    /**
     * Process input event from Table data (Row format).
     *
     * <p>When using {@code fromTable()}, the input is a {@link Row} with fields matching the table
     * column names.
     */
    @Action(EventType.InputEvent)
    public static void processInput(Event event, RunnerContext ctx) throws Exception {
        InputEvent inputEvent = (InputEvent) event;
        Row row = (Row) inputEvent.getInput();
        String productId = (String) row.getField("id");
        String reviewText = (String) row.getField("review");
        // Rest of the body is the one Build Your First Agent shows. It keeps the id in
        // short-term memory and sends ChatRequestEvent.
        ...
    }
}
```
{{< /tab >}}

{{< /tabs >}}

Feed the table to that Agent, naming the output type so the result stream is typed:

{{< tabs "Integrate via Table API" >}}

{{< tab "Python" >}}
```python
review_analysis_res_stream = (
    agents_env.from_table(input=input_table, key_selector=TableKeySelector())
    .apply(TableReviewAnalysisAgent())
    .to_datastream(ProductReviewAnalysisRes)
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
DataStream<ProductReviewAnalysisRes> reviewAnalysisResStream =
        agentsEnv
                .fromTable(inputTable, new TableReviewAnalysisAgent.RowKeySelector())
                .apply(new TableReviewAnalysisAgent())
                .toDataStream(ProductReviewAnalysisRes.class);
```
{{< /tab >}}

{{< /tabs >}}

See [Integrate with Flink]({{< ref "docs/development/integrate_with_flink#fromto-flink-table-api" >}}) for the full Table API integration reference.

### Aggregate the Analyses in a Window

One review is too little to suggest an improvement, so the job groups the per-review analyses by
product id in a one-minute tumbling window and reduces each group to a `ProductReviewSummary`. That
type is declared in the shared types file and carries the product id, the score distribution, and
the collected unsatisfied reasons. Python returns the summary object, and Java emits it as JSON
text, because the next stage reads it back with Jackson. Scores outside 1–5 are excluded from
the distribution; if none are valid, all five percentages are `0.0%`. The reasons are still retained.

{{< tabs "Aggregate in a Window" >}}

{{< tab "Python" >}}
```python
class AggregateScoreDistributionAndDislikeReasons(ProcessWindowFunction):
    """Aggregate score distribution and dislike reasons."""

    def process(
        self,
        key: str,
        context: "ProcessWindowFunction.Context",
        elements: Iterable[ProductReviewAnalysisRes],
    ) -> Iterable[ProductReviewSummary]:
        """Aggregate score distribution and dislike reasons."""
        rating_counts = [0 for _ in range(5)]
        reason_list = []
        for element in elements:
            rating = element.score
            if 1 <= rating <= 5:
                rating_counts[rating - 1] += 1
            reason_list = reason_list + element.reasons
        total = sum(rating_counts)
        percentages = [
            round((x / total) * 100, 1) if total else 0.0 for x in rating_counts
        ]
        formatted_percentages = [f"{p}%" for p in percentages]
        return [
            ProductReviewSummary(
                id=key,
                score_hist=formatted_percentages,
                unsatisfied_reasons=reason_list,
            )
        ]


aggregated_analysis_res_stream = (
    review_analysis_res_stream.key_by(lambda x: x.id)
    .window(TumblingProcessingTimeWindows.of(Time.minutes(1)))
    .process(AggregateScoreDistributionAndDislikeReasons())
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
static class AggregateScoreDistributionAndDislikeReasons
        extends ProcessWindowFunction<ProductReviewAnalysisRes, String, String, TimeWindow> {

    @Override
    public void process(
            String key,
            Context context,
            Iterable<ProductReviewAnalysisRes> elements,
            Collector<String> out)
            throws JsonProcessingException {

        // Initialize rating counts for scores 1-5
        int[] ratingCounts = new int[5];
        List<String> reasonList = new ArrayList<>();

        // Process each element in the window
        for (CustomTypesAndResources.ProductReviewAnalysisRes element : elements) {
            int rating = element.getScore();
            if (rating >= 1 && rating <= 5) {
                ratingCounts[rating - 1]++;
            }
            reasonList.addAll(element.getReasons());
        }

        // Calculate total and percentages
        int total = 0;
        for (int count : ratingCounts) {
            total += count;
        }

        // Calculate percentages and format them
        List<String> formattedPercentages = new ArrayList<>();
        if (total > 0) {
            for (int count : ratingCounts) {
                double percentage = Math.round((count * 100.0 / total) * 10.0) / 10.0;
                formattedPercentages.add(String.format("%.1f%%", percentage));
            }
        } else {
            // If no ratings, set all to 0%
            for (int i = 0; i < 5; i++) {
                formattedPercentages.add("0.0%");
            }
        }

        // Create and emit the aggregated result
        ProductReviewSummary summary =
                new ProductReviewSummary(key, formattedPercentages, reasonList);
        out.collect(MAPPER.writeValueAsString(summary));
    }
}

DataStream<String> aggregatedAnalysisResStream =
        reviewAnalysisResStream
                .keyBy(ProductReviewAnalysisRes::getId)
                .window(TumblingProcessingTimeWindows.of(Duration.ofMinutes(1)))
                .process(new AggregateScoreDistributionAndDislikeReasons());
```
{{< /tab >}}

{{< /tabs >}}

### Generate Suggestions with the Suggestion Agent

`ProductSuggestionAgent` reads that summary and emits a `ProductSuggestion`, which carries the
product id, the score distribution, and the suggestions it asks the model for. It is written the
same way as `TableReviewAnalysisAgent`, with its own prompt and its own chat model setup, and its setup
declares no `tools`.

{{< tabs "Add the Suggestion Agent" >}}

{{< tab "Python" >}}
```python
class ProductSuggestionAgent(Agent):
    """An agent that uses a large language model (LLM) to generate actionable product
    improvement suggestions from aggregated product review data.
    """

    @prompt
    @staticmethod
    def generate_suggestion_prompt() -> Prompt:
        """Generate product suggestions based on the rating distribution and user
        dissatisfaction reasons.
        """
        return product_suggestion_prompt

    @chat_model_setup
    @staticmethod
    def generate_suggestion_model() -> ResourceDescriptor:
        """ChatModel which focus on generating product suggestions."""
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_SETUP,
            connection="ollama_server",
            model="qwen3:8b",
            prompt="generate_suggestion_prompt",
            extract_reasoning=True,
        )

    @action(EventType.InputEvent)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        """Process input event."""
        input = ProductReviewSummary.model_validate(cast("InputEvent", event).input)
        # Rest of the body keeps the id and score histogram in short-term memory and
        # sends ChatRequestEvent for generate_suggestion_model.
        ...
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class ProductSuggestionAgent extends Agent {

    @ChatModelSetup
    public static ResourceDescriptor generateSuggestionModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "ollamaChatModelConnection")
                .addInitialArgument("model", "qwen3:8b")
                .addInitialArgument("extract_reasoning", true)
                .addInitialArgument("prompt", "productSuggestionPrompt")
                .build();
    }

    @Prompt
    public static org.apache.flink.agents.api.prompt.Prompt productSuggestionPrompt() {
        return PRODUCT_SUGGESTION_PROMPT;
    }

    /** Process input event. */
    @Action(EventType.InputEvent)
    public static void processInput(Event event, RunnerContext ctx) throws Exception {
        // The window stage emitted JSON text, so this Agent reads it back first.
        InputEvent inputEvent = (InputEvent) event;
        String input = (String) inputEvent.getInput();
        ProductReviewSummary summary = MAPPER.readValue(input, ProductReviewSummary.class);
        // Rest of the body keeps the id and score histogram in short-term memory and
        // sends ChatRequestEvent for generateSuggestionModel.
        ...
    }
}
```
{{< /tab >}}

{{< /tabs >}}

Both Agents live in the same job, and `ProductSuggestionAgent` consumes the output of
`TableReviewAnalysisAgent`:

{{< tabs "Chain the Two Agents" >}}

{{< tab "Python" >}}
```python
product_suggestion_res_stream = (
    agents_env.from_datastream(
        input=aggregated_analysis_res_stream,
        key_selector=lambda x: x.id,
    )
    .apply(ProductSuggestionAgent())
    .to_datastream()
)

product_suggestion_res_stream.print()

# Execute the Flink pipeline with the Flink job name.
agents_env.execute("Workflow Multiple Agent Example Job")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
DataStream<Object> productSuggestionResStream =
        agentsEnv
                .fromDataStream(aggregatedAnalysisResStream)
                .apply(new ProductSuggestionAgent())
                .toDataStream();

productSuggestionResStream.print();

// Execute the Flink pipeline with the Flink job name.
agentsEnv.execute("Workflow Multiple Agent Example Job");
```
{{< /tab >}}

{{< /tabs >}}

`ProductSuggestionAgent` needs no extra connection registration. Its chat model setup names
`ollama_server` / `ollamaChatModelConnection`, and both Agents resolve that name against the
environment they were applied to. See
[Registering Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}}).

## Run the Example

Install Flink and Flink Agents, get the example source, prepare Ollama, and start a local
cluster as described in
[Before You Begin]({{< ref "docs/get-started/quickstart#before-you-begin" >}}). From the
repository root, submit the job:

{{< tabs "Submit to Flink Cluster" >}}

{{< tab "Python" >}}
```bash
# Or submit the copy inside the installed package, at
# $PYTHONPATH/flink_agents/examples/quickstart/workflow_multiple_agent_example.py
$FLINK_HOME/bin/flink run -py python/flink_agents/examples/quickstart/workflow_multiple_agent_example.py
```
{{< /tab >}}

{{< tab "Java" >}}
```bash
$FLINK_HOME/bin/flink run -c org.apache.flink.agents.examples.WorkflowMultipleAgentExample \
  examples/target/flink-agents-examples-*.jar
```
{{< /tab >}}

{{< /tabs >}}

The job appears in the Flink web UI at [localhost:8081](http://localhost:8081). Suggestions
appear in the TaskManager output log under `$FLINK_HOME/log` once a window closes, which takes
up to a minute after the first review is analyzed.
