---
title: 'Parallel LLM Calls'
weight: 4
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

Below is the example code for the `ParallelChatAgent`. Two system prompts — `PARALLEL_SYSTEM_PROMPT` for the parallel aspect judgments and `AGGREGATE_SYSTEM_PROMPT` for the aggregation call — are packaged into `ChatRequestEvent`s by `_build_aspect_request` / `buildAspectRequest` and `_build_summarize_request` / `buildSummarizeRequest`. The agent defines a chat model and two actions: `request_aspect_judgments` pre-builds all requests and records a `{request_id → aspect}` map for reliable correlation, and `handle_response` looks up the dispatched aspect by `request_id`, accumulates the results, and emits the final output. For more details, please refer to the [Workflow Agent]({{< ref "docs/development/workflow_agent" >}}) documentation.

{{< tabs "Create the Agent" >}}

{{< tab "Python" >}}
```python
ASPECTS: Tuple[str, ...] = ("taste", "service", "price")
N_ASPECTS = len(ASPECTS)

PARALLEL_SYSTEM_PROMPT = (
    "You are a sentiment analysis assistant. Return JSON: "
    '{"aspect":"<dimension>", "result":"<positive|negative|not_mentioned>"}'
    " — no explanation, no extra fields."
)
AGGREGATE_SYSTEM_PROMPT = (
    "You are a summary assistant. Based on the sentiment judgments for three "
    "dimensions, compose a brief one-line evaluation. Return JSON: "
    '{"summary":"taste:<positive/negative/not_mentioned>, '
    "service:<positive/negative/not_mentioned>, "
    'price:<positive/negative/not_mentioned>"} — return only this JSON.'
)


def _build_aspect_request(text: str, aspect: str) -> ChatRequestEvent:
    """Build a ChatRequestEvent for a single aspect dimension."""
    return ChatRequestEvent(
        model="sentiment_model",
        messages=[
            ChatMessage(role=MessageRole.SYSTEM, content=PARALLEL_SYSTEM_PROMPT),
            ChatMessage(
                role=MessageRole.USER,
                content=f'Judge the "{aspect}" dimension: {text}',
            ),
        ],
        output_schema=OutputSchema(output_schema=AspectResponse),
    )


def _build_summarize_request(row: Dict[str, Any]) -> ChatRequestEvent:
    """Build a ChatRequestEvent for the aggregation phase."""
    sentiments = row["sentiments"]
    body = (
        f"Original: {row['text']}\n"
        + "Judgments: "
        + " ".join(f"{a}:{sentiments[a]}" for a in ASPECTS)
    )
    return ChatRequestEvent(
        model="sentiment_model",
        messages=[
            ChatMessage(role=MessageRole.SYSTEM, content=AGGREGATE_SYSTEM_PROMPT),
            ChatMessage(role=MessageRole.USER, content=body),
        ],
        output_schema=OutputSchema(output_schema=SummaryResponse),
    )


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
        requests = [_build_aspect_request(row["text"], aspect) for aspect in ASPECTS]
        row["aspect_map"] = {
            str(req.id): aspect for req, aspect in zip(requests, ASPECTS, strict=True)
        }
        # Sensory memory requires JSON serialization across the Pemja JVM boundary.
        ctx.sensory_memory.set("res", json.dumps(row, ensure_ascii=False))
        for req in requests:
            ctx.send_event(req)

    @action(ChatResponseEvent.EVENT_TYPE)
    @staticmethod
    def handle_response(event: Event, ctx: RunnerContext) -> None:
        """Process chat response event and send output event."""
        response_event = ChatResponseEvent.from_event(event)
        parsed = response_event.response.extra_args[STRUCTURED_OUTPUT]
        row = json.loads(ctx.sensory_memory.get("res"))
        if isinstance(parsed, SummaryResponse):
            ctx.send_event(_build_output_event(row, parsed))
            return
        aspect = row["aspect_map"][str(response_event.request_id)]
        row["sentiments"][aspect] = parsed.result
        # Sensory memory requires JSON serialization across the Pemja JVM boundary.
        ctx.sensory_memory.set("res", json.dumps(row, ensure_ascii=False))
        if _all_aspects_received(row):
            ctx.send_event(_build_summarize_request(row))
```

The complete source including `_init_row`, `_build_output_event`, `_all_aspects_received`, and other supporting functions can be found in [`parallel_chat_agent.py`](https://github.com/apache/flink-agents/blob/main/python/flink_agents/examples/quickstart/agents/parallel_chat_agent.py).
{{< /tab >}}

{{< tab "Java" >}}
```Java
public class ParallelChatAgent extends Agent {

    private static final String[] ASPECTS = {"taste", "service", "price"};
    private static final int N_ASPECTS = ASPECTS.length;

    private static final String PARALLEL_SYSTEM_PROMPT =
            "You are a sentiment analysis assistant. Return JSON: "
                    + "{\"aspect\":\"<dimension>\", \"result\":\"<positive|negative|not_mentioned>\"}"
                    + " — no explanation, no extra fields.";
    private static final String AGGREGATE_SYSTEM_PROMPT =
            "You are a summary assistant. Based on the sentiment judgments for three "
                    + "dimensions, compose a brief one-line evaluation. Return JSON: "
                    + "{\"summary\":\"taste:<positive/negative/not_mentioned>, "
                    + "service:<positive/negative/not_mentioned>, "
                    + "price:<positive/negative/not_mentioned>\"} — return only this JSON.";

    @ChatModelSetup
    public static ResourceDescriptor sentimentModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "ollamaChatModelConnection")
                .addInitialArgument("model", OLLAMA_MODEL)
                .addInitialArgument("extract_reasoning", true)
                .build();
    }

    private static ChatRequestEvent buildAspectRequest(String text, String aspect) {
        List<ChatMessage> messages =
                List.of(
                        new ChatMessage(MessageRole.SYSTEM, PARALLEL_SYSTEM_PROMPT),
                        new ChatMessage(
                                MessageRole.USER,
                                "Judge the \"" + aspect + "\" dimension: " + text));
        return new ChatRequestEvent(
                "sentimentModel", messages, CustomTypesAndResources.AspectResponse.class);
    }

    @SuppressWarnings("unchecked")
    private static ChatRequestEvent buildSummarizeRequest(Map<String, Object> row) {
        Map<String, String> sentiments = (Map<String, String>) row.get("sentiments");
        StringJoiner sj = new StringJoiner(" ");
        for (String aspect : ASPECTS) {
            sj.add(aspect + ":" + sentiments.get(aspect));
        }
        String body = "Original: " + row.get("text") + "\nJudgments: " + sj;
        List<ChatMessage> messages =
                List.of(
                        new ChatMessage(MessageRole.SYSTEM, AGGREGATE_SYSTEM_PROMPT),
                        new ChatMessage(MessageRole.USER, body));
        return new ChatRequestEvent(
                "sentimentModel", messages, CustomTypesAndResources.SummaryResponse.class);
    }

    @SuppressWarnings("unchecked")
    @Action(listenEventTypes = {InputEvent.EVENT_TYPE})
    public static void requestAspectJudgments(Event event, RunnerContext ctx) throws Exception {
        Map<String, Object> row = initRow(event);
        List<ChatRequestEvent> requests = new ArrayList<>();
        Map<String, String> aspectMap = (Map<String, String>) row.get("aspect_map");
        for (String aspect : ASPECTS) {
            ChatRequestEvent req = buildAspectRequest((String) row.get("text"), aspect);
            aspectMap.put(req.getId().toString(), aspect);
            requests.add(req);
        }
        // Sensory memory stores the Map directly in Java; no JSON serialization required.
        ctx.getSensoryMemory().set("res", row);
        for (ChatRequestEvent req : requests) {
            ctx.sendEvent(req);
        }
    }

    @SuppressWarnings("unchecked")
    @Action(listenEventTypes = {ChatResponseEvent.EVENT_TYPE})
    public static void handleResponse(Event event, RunnerContext ctx) throws Exception {
        ChatResponseEvent chatResponse = ChatResponseEvent.fromEvent(event);
        Object parsed = chatResponse.getResponse().getExtraArgs().get(STRUCTURED_OUTPUT);
        Map<String, Object> row =
                (Map<String, Object>) ctx.getSensoryMemory().get("res").getValue();

        if (parsed instanceof CustomTypesAndResources.SummaryResponse) {
            CustomTypesAndResources.SummaryResponse summary =
                    (CustomTypesAndResources.SummaryResponse) parsed;
            ctx.sendEvent(buildOutputEvent(row, summary));
            return;
        }

        CustomTypesAndResources.AspectResponse aspectResponse =
                (CustomTypesAndResources.AspectResponse) parsed;
        Map<String, String> sentiments = (Map<String, String>) row.get("sentiments");
        Map<String, String> aspectMap = (Map<String, String>) row.get("aspect_map");
        String dispatchedAspect = aspectMap.get(chatResponse.getRequestId().toString());
        sentiments.put(dispatchedAspect, aspectResponse.result);
        // Sensory memory stores the Map directly in Java; no JSON serialization required.
        ctx.getSensoryMemory().set("res", row);
        if (allAspectsReceived(row)) {
            ctx.sendEvent(buildSummarizeRequest(row));
        }
    }
}
```

Other private helpers (`initRow`, `buildOutputEvent`, `allAspectsReceived`) are omitted above; the full source is at [`ParallelChatAgent.java`](https://github.com/apache/flink-agents/blob/main/examples/src/main/java/org/apache/flink/agents/examples/agents/ParallelChatAgent.java).
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
