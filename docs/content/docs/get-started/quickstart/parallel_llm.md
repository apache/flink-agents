---
title: 'Parallel LLM Calls'
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

Flink Agents supports parallel LLM invocations via multi-action fan-out. By emitting multiple `ChatRequestEvent` events from a single action, the framework's built-in chat action executes the corresponding LLM calls concurrently — no external orchestration is required.

This quickstart introduces an example that demonstrates how to build a parallel LLM workflow with Flink Agents:

The **Parallel Sentiment Analysis** agent processes a restaurant review and judges sentiment along three dimensions (taste / service / price) in parallel, then aggregates the results into a one-line summary with a final LLM call. The end-to-end wall clock time is roughly "slowest single branch + aggregation call", rather than the sum of all four calls.

{{< hint info >}}
**JDK version note (Java only):** On JDK 21+, the framework uses the Continuation API to execute concurrent chat actions in parallel. On JDK < 21, the framework silently falls back to sequential execution — the result is identical, but the LLM calls run one after another. Python uses native coroutines and always executes in parallel regardless of the JDK version.
{{< /hint >}}

## Code Walkthrough

### Prepare Agents Execution Environment

Create the agents execution environment, and register the available chat model connection to the environment.

{{< tabs "Prepare Agents Execution Environment" >}}

{{< tab "Python" >}}
```python
# Set up the Flink streaming environment and the Agents execution environment.
env = StreamExecutionEnvironment.get_execution_environment()
env.set_parallelism(1)
agents_env = AgentsExecutionEnvironment.get_execution_environment(env)

# Add Ollama chat model connection to be used by the ParallelChatAgent.
agents_env.add_resource(
    "ollama_server",
    ResourceType.CHAT_MODEL_CONNECTION,
    ResourceDescriptor(
        clazz=ResourceName.ChatModel.OLLAMA_CONNECTION,
        request_timeout=240.0,
    ),
)
```
{{< /tab >}}

{{< tab "Java" >}}
```Java
// Set up the Flink streaming environment and the Agents execution environment.
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
env.setParallelism(1);
AgentsExecutionEnvironment agentsEnv =
        AgentsExecutionEnvironment.getExecutionEnvironment(env);

// Add Ollama chat model connection to be used by the ParallelChatAgent.
agentsEnv.addResource(
        "ollamaChatModelConnection",
        ResourceType.CHAT_MODEL_CONNECTION,
        ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_CONNECTION)
                .addInitialArgument("endpoint", "http://localhost:11434")
                .addInitialArgument("requestTimeout", 240)
                .build());
```
{{< /tab >}}

{{< /tabs >}}

### Create the Agent

Below is the example code for the `ParallelChatAgent`. The agent defines a chat model for sentiment analysis and two actions: `request_aspect_judgments` fans out one `ChatRequestEvent` per dimension, and `handle_response` collects the results, triggers the aggregation call, and emits the final output. For more details, please refer to the [Workflow Agent]({{< ref "docs/development/workflow_agent" >}}) documentation.

{{< tabs "Create the Agent" >}}

{{< tab "Python" >}}
```python
class ParallelChatAgent(Agent):
    """An agent that demonstrates parallel LLM invocations via fan-out of
    multiple ChatRequestEvent events.

    This agent receives a restaurant review and uses an LLM to judge sentiment
    along multiple dimensions in parallel, then aggregates the results into a
    one-line summary with a final LLM call. It handles prompt construction,
    parallel chat dispatch, response accumulation, and output assembly.
    """

    @chat_model_setup
    @staticmethod
    def sentiment_model() -> ResourceDescriptor:
        """ChatModel for sentiment analysis."""
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_SETUP,
            connection="ollama_server",
            model=OLLAMA_MODEL,
            extract_reasoning=True,
        )

    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    def request_aspect_judgments(event: Event, ctx: RunnerContext) -> None:
        """Process input event and send chat requests for each aspect."""
        row = _init_row(event)
        _save_row(ctx, row)
        for aspect in ASPECTS:
            ctx.send_event(_build_aspect_request(row["text"], aspect))

    @action(ChatResponseEvent.EVENT_TYPE)
    @staticmethod
    def handle_response(event: Event, ctx: RunnerContext) -> None:
        """Process chat response event and send output event."""
        parsed = _parse_response(event)
        row = _load_row(ctx)
        if _is_final(parsed):
            ctx.send_event(_build_output_event(row, parsed))
            return
        row["sentiments"][parsed.aspect] = parsed.result
        _save_row(ctx, row)
        if _all_aspects_received(row):
            ctx.send_event(_build_summarize_request(row))
```
{{< /tab >}}

{{< tab "Java" >}}
```Java
/**
 * An agent that demonstrates parallel LLM invocations via fan-out of multiple
 * ChatRequestEvent events.
 */
public class ParallelChatAgent extends Agent {

    @ChatModelSetup
    public static ResourceDescriptor sentimentModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "ollamaChatModelConnection")
                .addInitialArgument("model", OLLAMA_MODEL)
                .addInitialArgument("extract_reasoning", true)
                .build();
    }

    @Action(listenEventTypes = {InputEvent.EVENT_TYPE})
    public static void requestAspectJudgments(Event event, RunnerContext ctx)
            throws Exception {
        Map<String, Object> row = initRow(event);
        saveRow(ctx, row);
        for (String aspect : ASPECTS) {
            ctx.sendEvent(buildAspectRequest((String) row.get("text"), aspect));
        }
    }

    @Action(listenEventTypes = {ChatResponseEvent.EVENT_TYPE})
    public static void handleResponse(Event event, RunnerContext ctx) throws Exception {
        Object parsed = parseResponse(event);
        Map<String, Object> row = loadRow(ctx);

        if (parsed instanceof SummaryResponse) {
            SummaryResponse summary = (SummaryResponse) parsed;
            ctx.sendEvent(buildOutputEvent(row, summary));
            return;
        }

        AspectResponse aspect = (AspectResponse) parsed;
        Map<String, String> sentiments = (Map<String, String>) row.get("sentiments");
        sentiments.put(aspect.aspect, aspect.result);
        saveRow(ctx, row);
        if (allAspectsReceived(row)) {
            ctx.sendEvent(buildSummarizeRequest(row));
        }
    }
}
```
{{< /tab >}}

{{< /tabs >}}

{{< hint warning >}}
**Constraints when using multi-action fan-out:**
- **Fan-out action must exit immediately after sending events.** The action that emits multiple `ChatRequestEvent` events (e.g. `request_aspect_judgments`) should return right after calling `ctx.send_event(...)`. Do not `await`, block, or perform further business logic in the same action — the framework needs control back to schedule the parallel chat actions.
- **Response handlers on the same key execute sequentially.** Multiple `ChatResponseEvent` triggers for the same key are processed one at a time, not concurrently. This means the read-modify-write pattern on sensory memory (e.g. `load_row → update → save_row`) is safe and will not suffer from concurrent overwrites.
  {{< /hint >}}

### Integrate the Agent with Flink

Create the input data, use the `ParallelChatAgent` to analyze the review with parallel LLM calls, and print the results.

{{< tabs "Integrate the Agent with Flink" >}}

{{< tab "Python" >}}
```python
# Create input stream with a single restaurant review.
input_stream = env.from_collection(
    collection=[{"id": 1, "text": INPUT_TEXT}],
)

# Use the ParallelChatAgent to analyze the review with parallel LLM calls.
output_stream = (
    agents_env.from_datastream(
        input=input_stream, key_selector=lambda x: x["id"]
    )
    .apply(ParallelChatAgent())
    .to_datastream()
)

# Print the analysis results to stdout.
output_stream.print()

# Execute the Flink pipeline.
agents_env.execute()
```
{{< /tab >}}

{{< tab "Java" >}}
```Java
// Create input stream with a single restaurant review.
DataStream<SentimentRequest> inputStream =
        env.fromElements(new SentimentRequest(1, ParallelChatAgent.INPUT_TEXT));

// Use the ParallelChatAgent to analyze the review with parallel LLM calls.
DataStream<Object> outputStream =
        agentsEnv
                .fromDataStream(inputStream, new SentimentKeySelector())
                .apply(new ParallelChatAgent())
                .toDataStream();

// Print the analysis results to stdout.
outputStream.print();

// Execute the Flink pipeline.
agentsEnv.execute();
```
{{< /tab >}}

{{< /tabs >}}

## Run the Example

### Prerequisites

* Unix-like environment (we use Linux, Mac OS X, Cygwin, WSL)
* Git
* Java 11+ (Java 21+ recommended for parallel execution on the Java side)
* Python 3.10, 3.11 or 3.12

### Preparation

#### Prepare Flink and Flink Agents

Follow the [installation]({{< ref "docs/get-started/installation" >}}) instructions to setup Flink and the Flink Agents.

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
You can refer to the [local cluster](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/try-flink/local_installation/#starting-and-stopping-a-local-cluster) instructions for more detailed step.

{{< hint info >}}
If you can't navigate to the web UI at [localhost:8081](localhost:8081), you can find the reason in `$FLINK_HOME/log`. If the reason is port conflict, you can change the port in `$FLINK_HOME/conf/config.yaml`.
{{< /hint >}}

#### Prepare Ollama

Download and install Ollama from the official [website](https://ollama.com/download).

{{< hint info >}}
Ollama server **0.9.0** or higher is required.
{{< /hint >}}

Then pull the qwen3:1.7b model, which is required by the parallel LLM example

```bash
ollama pull qwen3:1.7b
```

### Submit Flink Agents Job to Standalone Flink Cluster

#### Submit to Flink Cluster

{{< tabs "Submit to Flink Cluster" >}}

{{< tab "Python" >}}
```bash
export PYTHONPATH=$(python -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')

# Run parallel chat request example
$FLINK_HOME/bin/flink run -py ./flink-agents/python/flink_agents/examples/quickstart/parallel_chat_request_example.py
# or submit the example python file in installed flink-agents wheel
$FLINK_HOME/bin/flink run -py  $PYTHONPATH/flink_agents/examples/quickstart/parallel_chat_request_example.py
```
{{< /tab >}}

{{< tab "Java" >}}
```bash
$FLINK_HOME/bin/flink run -c org.apache.flink.agents.examples.ParallelChatRequestExample ./flink-agents/examples/target/flink-agents-examples-$VERSION.jar
```
{{< /tab >}}

{{< /tabs >}}

Now you should see a Flink job submitted to the Flink Cluster in Flink web UI [localhost:8081](
localhost:8081)

After a few minutes, you can check for the output in the TaskManager output log.
