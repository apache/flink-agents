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

Flink Agents supports parallel LLM invocations via a broadcast event model. By emitting a single intermediate event from one action and registering multiple independent action handlers that each listen on that event type, the framework's built-in chat action executes the corresponding LLM calls concurrently — no external orchestration is required.

This quickstart introduces an example that demonstrates how to build a parallel LLM workflow with Flink Agents:

The **Parallel Sentiment Analysis** agent processes a restaurant review and judges sentiment along two dimensions (taste / service) in parallel, then aggregates the results into a one-line summary with a final LLM call. The end-to-end wall clock time is roughly "slowest single branch + aggregation call", rather than the sum of all three calls.

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

Below is the example code for the `ParallelChatAgent`. Two system prompts — `PARALLEL_SYSTEM_PROMPT` for the parallel aspect judgments and `AGGREGATE_SYSTEM_PROMPT` for the aggregation call — are packaged into `ChatRequestEvent`s by `_build_aspect_request` / `buildAspectRequest` and `_build_summarize_request` / `buildSummarizeRequest`. The agent defines a chat model and four actions: `request_aspect_judgments` stores the review `id` and `text` in sensory memory and emits a single generic `Event` of type `SENTIMENT_INPUT_EVENT_TYPE`; `handle_taste_input` and `handle_service_input` each listen on that event type and independently dispatch one `ChatRequestEvent`, recording a `{request_id → aspect}` entry using path-based memory access (`aspect_map.<request_id>`); and `handle_response` looks up the dispatched aspect, stores the result under `sentiments.<aspect>`, and triggers aggregation once all aspects are collected.

{{< tabs "Create the Agent" >}}

{{< tab "Python" >}}
```python
ASPECTS: tuple = ("taste", "service")
N_ASPECTS = len(ASPECTS)
SENTIMENT_INPUT_EVENT_TYPE = "SentimentInputEvent"

PARALLEL_SYSTEM_PROMPT = (
    "You are a sentiment analysis assistant. Return JSON: "
    '{"aspect":"<dimension>", "result":"<positive|negative|not_mentioned>"}'
    " — no explanation, no extra fields."
)
AGGREGATE_SYSTEM_PROMPT = (
    "You are a summary assistant. Based on the sentiment judgments for two "
    "dimensions, compose a brief one-line evaluation. Return JSON: "
    '{"summary":"taste:<positive/negative/not_mentioned>, '
    'service:<positive/negative/not_mentioned>"} — return only this JSON.'
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


def _build_summarize_request(text: str, sentiments: Dict[str, str]) -> ChatRequestEvent:
    """Build a ChatRequestEvent for the aggregation phase."""
    body = (
        f"Original: {text}\n"
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

    Event flow:
      1. InputEvent → request_aspect_judgments → emits SentimentInputEvent
      2. SentimentInputEvent triggers handlers in parallel:
           - handle_taste_input   → ChatRequestEvent (taste LLM call)
           - handle_service_input → ChatRequestEvent (service LLM call)
      3. Each ChatResponseEvent → handle_response (accumulates aspect results)
      4. Once all aspects received → aggregation LLM call → OutputEvent
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
        """Process input event and dispatch SentimentInputEvent to aspect handlers."""
        payload = InputEvent.from_event(event).input
        # Primitive types (int, str) cross the Pemja JVM boundary without serialization.
        ctx.sensory_memory.set("id", payload["id"])
        ctx.sensory_memory.set("text", payload["text"])
        ctx.send_event(
            Event(
                type=SENTIMENT_INPUT_EVENT_TYPE,
                attributes={"input_id": payload["id"], "text": payload["text"]},
            )
        )

    @action(SENTIMENT_INPUT_EVENT_TYPE)
    @staticmethod
    def handle_taste_input(event: Event, ctx: RunnerContext) -> None:
        """Handle taste aspect: build and send ChatRequestEvent for taste judgment."""
        req = _build_aspect_request(event.get_attr("text"), "taste")
        ctx.sensory_memory.set(f"aspect_map.{req.id}", "taste")
        ctx.send_event(req)

    @action(SENTIMENT_INPUT_EVENT_TYPE)
    @staticmethod
    def handle_service_input(event: Event, ctx: RunnerContext) -> None:
        """Handle service aspect: build and send ChatRequestEvent for service."""
        req = _build_aspect_request(event.get_attr("text"), "service")
        ctx.sensory_memory.set(f"aspect_map.{req.id}", "service")
        ctx.send_event(req)

    @action(ChatResponseEvent.EVENT_TYPE)
    @staticmethod
    def handle_response(event: Event, ctx: RunnerContext) -> None:
        """Process chat response event and send output event."""
        ...
```

The complete source including `_build_output_event` and other supporting functions can be found in [`parallel_chat_agent.py`](https://github.com/apache/flink-agents/blob/main/python/flink_agents/examples/quickstart/agents/parallel_chat_agent.py).
{{< /tab >}}

{{< tab "Java" >}}
```Java
/**
 * An agent that demonstrates parallel LLM invocations via a broadcast event model.
 *
 * <p>Event flow:
 * <ol>
 *   <li>InputEvent → requestAspectJudgments → emits SentimentInputEvent
 *   <li>SentimentInputEvent triggers handlers in parallel:
 *       <ul>
 *         <li>handleTasteInput → ChatRequestEvent (taste LLM call)
 *         <li>handleServiceInput → ChatRequestEvent (service LLM call)
 *       </ul>
 *   <li>Each ChatResponseEvent → handleResponse (accumulates aspect results)
 *   <li>Once all aspects received → aggregation LLM call → OutputEvent
 * </ol>
 */
public class ParallelChatAgent extends Agent {

    private static final String[] ASPECTS = {"taste", "service"};

    private static final String SENTIMENT_INPUT_EVENT_TYPE = "SentimentInputEvent";

    private static final String PARALLEL_SYSTEM_PROMPT =
            "You are a sentiment analysis assistant. Return JSON: "
                    + "{\"aspect\":\"<dimension>\", \"result\":\"<positive|negative|not_mentioned>\"}"
                    + " — no explanation, no extra fields.";
    private static final String AGGREGATE_SYSTEM_PROMPT =
            "You are a summary assistant. Based on the sentiment judgments for two "
                    + "dimensions, compose a brief one-line evaluation. Return JSON: "
                    + "{\"summary\":\"taste:<positive/negative/not_mentioned>, "
                    + "service:<positive/negative/not_mentioned>\"} — return only this JSON.";

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

    private static ChatRequestEvent buildSummarizeRequest(
            String text, Map<String, String> sentiments) {
        StringJoiner sj = new StringJoiner(" ");
        for (String aspect : ASPECTS) {
            sj.add(aspect + ":" + sentiments.get(aspect));
        }
        String body = "Original: " + text + "\nJudgments: " + sj;
        List<ChatMessage> messages =
                List.of(
                        new ChatMessage(MessageRole.SYSTEM, AGGREGATE_SYSTEM_PROMPT),
                        new ChatMessage(MessageRole.USER, body));
        return new ChatRequestEvent(
                "sentimentModel", messages, CustomTypesAndResources.SummaryResponse.class);
    }

    /** Process input event and dispatch a SentimentInputEvent for each aspect handler. */
    @Action(EventType.InputEvent)
    public static void requestAspectJudgments(Event event, RunnerContext ctx) throws Exception {
        CustomTypesAndResources.SentimentRequest request =
                (CustomTypesAndResources.SentimentRequest) InputEvent.fromEvent(event).getInput();
        ctx.getSensoryMemory().set("id", request.getId());
        ctx.getSensoryMemory().set("text", request.getText());
        ctx.sendEvent(
                new Event(
                        SENTIMENT_INPUT_EVENT_TYPE,
                        Map.of("input_id", request.getId(), "text", request.getText())));
    }

    /** Handle taste aspect: build and send ChatRequestEvent for taste judgment. */
    @Action(SENTIMENT_INPUT_EVENT_TYPE)
    public static void handleTasteInput(Event event, RunnerContext ctx) throws Exception {
        ChatRequestEvent req = buildAspectRequest((String) event.getAttr("text"), "taste");
        ctx.getSensoryMemory().set("aspect_map." + req.getId(), "taste");
        ctx.sendEvent(req);
    }

    /** Handle service aspect: build and send ChatRequestEvent for service judgment. */
    @Action(SENTIMENT_INPUT_EVENT_TYPE)
    public static void handleServiceInput(Event event, RunnerContext ctx) throws Exception {
        ChatRequestEvent req = buildAspectRequest((String) event.getAttr("text"), "service");
        ctx.getSensoryMemory().set("aspect_map." + req.getId(), "service");
        ctx.sendEvent(req);
    }

    @Action(EventType.ChatResponseEvent)
    public static void handleResponse(Event event, RunnerContext ctx) throws Exception {
        ...
    }
}
```

Other private helpers (`buildOutputEvent`) are omitted above; the full source is at [`ParallelChatAgent.java`](https://github.com/apache/flink-agents/blob/main/examples/src/main/java/org/apache/flink/agents/examples/agents/ParallelChatAgent.java).
{{< /tab >}}

{{< /tabs >}}

{{< hint warning >}}
**Constraints when using parallel actions:**
- **Dispatch action must exit immediately after sending events.** The action that emits `SentimentInputEvent` (i.e. `request_aspect_judgments`) should return right after calling `ctx.send_event(...)`. Do not `await`, block, or perform further business logic in the same action — the framework needs control back to schedule the parallel chat actions.
- **Action handlers on the same key execute sequentially.** Multiple action invocations triggered by the same key are processed one at a time, not concurrently. This means `handle_taste_input` and `handle_service_input` are called in sequence. Each handler writes a single path-based entry (`aspect_map.<request_id>`) to sensory memory without reading back, so there is no conflict between the two. The actual LLM calls dispatched by these handlers still execute in parallel.
- **Response handlers on the same key execute sequentially.** Multiple `ChatResponseEvent` triggers for the same key are processed one at a time, not concurrently. This means the path-based writes to `sentiments.<aspect>` in `handle_response` are safe and will not suffer from concurrent overwrites.
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
