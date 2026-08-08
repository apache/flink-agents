---
title: Workflow Agent
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

In Flink-Agents, a workflow agent is a class that inherits from the `Agent` base class. Its logic is
a set of actions. In Python, use `@action(...)` on a function; in Java, use `@Action(...)` on a
method. Each action declares one or more trigger conditions, which may be exact event types or
condition expressions. Actions consume events, perform reasoning or tool calls, and emit new events
that may trigger other actions. This event-driven workflow forms a directed graph that may contain
cycles. Each node is an action, and each edge represents an event flow between actions.

A workflow agent is well-suited for scenarios where the solution requires explicit orchestration, branching, or multi-step reasoning, such as data enrichment, multi-tool pipelines, or complex business logic.

{{< hint info >}}
For guidance on choosing Java or Python, see [Should I choose Java or Python?]({{< ref "docs/faq/faq#q3-should-i-choose-java-or-python" >}}).
{{< /hint >}}

## Workflow Agent Example

{{< tabs "Workflow Agent Example" >}}

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
        input_event = InputEvent.from_event(event)
        input: ProductReview = input_event.input
        ctx.short_term_memory.set("id", input.id)

        content = f"""
            "id": {input.id},
            "review": {input.review}
        """
        msg = ChatMessage(role=MessageRole.USER)
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
        chat_response = ChatResponseEvent.from_event(event)
        try:
            json_content = json.loads(chat_response.response.content)
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
                f"Error processing chat response {chat_response.response.content}"
            )

            # To fail the agent, you can raise an exception here.
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
        InputEvent inputEvent = InputEvent.fromEvent(event);
        String input = (String) inputEvent.getInput();
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        CustomTypesAndResources.ProductReview inputObj =
                MAPPER.readValue(input, CustomTypesAndResources.ProductReview.class);

        ctx.getShortTermMemory().set("id", inputObj.getId());

        String content =
                String.format(
                        "{\n" + "\"id\": %s,\n" + "\"review\": \"%s\"\n" + "}",
                        inputObj.getId(), inputObj.getReview());
        ChatMessage msg = new ChatMessage(MessageRole.USER, "");

        ctx.sendEvent(
                new ChatRequestEvent(
                        "reviewAnalysisModel", List.of(msg), Map.of("input", content), null));
    }

    @Action(EventType.ChatResponseEvent)
    public static void processChatResponse(Event event, RunnerContext ctx)
            throws Exception {
        ChatResponseEvent chatResponse = ChatResponseEvent.fromEvent(event);
        JsonNode jsonNode = MAPPER.readTree(chatResponse.getResponse().getContent());
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

## Action

Each action has one or more trigger conditions and runs when any of them matches an event. A trigger
condition is either an exact event type or a condition expression that evaluates to `true` or
`false`. Condition expressions use [Common Expression Language (CEL)](https://cel.dev/), a safe
language for checking event data. Multiple trigger conditions use OR semantics. To require both an
event type and an attribute predicate, put both checks in the same expression.

Every matching action runs (fan-out), but each action runs at most once per event. For each action, an
exact event-type match takes precedence and skips its condition expressions. For an action without an
exact match, condition expressions are evaluated in declaration order until one matches.

Use `@action(*trigger_conditions, target=None)` in Python or `@Action({...})` in Java. The action
function must accept `(Event, RunnerContext)` and should not return a value (`None` in Python or
`void` in Java). Use `RunnerContext` to send events instead. A native Java action must be
`public static`. Python actions can also be defined as `async def` when using async execution (see
[Async Execution](#async-execution)).

{{< tabs "Action Function" >}}

{{< tab "Python" >}}
```python
class ReviewAnalysisAgent(Agent):
    @action(EventType.InputEvent)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        # the action logic
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class ReviewAnalysisAgent extends Agent {
    /** Process input event and send chat request for review analysis. */
    @Action(EventType.InputEvent)
    public static void processInput(Event event, RunnerContext ctx) throws Exception {
        InputEvent inputEvent = InputEvent.fromEvent(event);
        // the action logic
    }
}
```
{{< /tab >}}

{{< /tabs >}}

### Trigger Condition Syntax

Use `EventType` constants for built-in exact event types. Inside a condition expression, compare
`type` with the constant, for example `type == EventType.InputEvent`. `EventType.InputEvent` by
itself is a value, not a condition.

A bare identifier or dotted path is treated as an exact event type, so check an attribute explicitly,
for example `ready == true` or `attributes.ready == true`. An unquoted value that starts with
`EventType.` is treated as a condition expression. To use such a value as an exact event type, quote
the whole name, for example `'EventType.custom'`.

Custom event types may be bare names such as `order.created` or `order-created`. Each dot-separated
segment must start with an ASCII letter or underscore and may then contain ASCII letters, digits,
underscores, or hyphens. Quote names that contain other punctuation or would otherwise be treated as
condition expressions, for example `'order:created'` or `'true'`. A quoted event type must be
non-empty and cannot contain whitespace, quotes, backslashes, or control characters.

### Trigger Condition Examples

Multiple values use OR semantics. Entries may use `EventType` constants, event-class
`EVENT_TYPE` constants, custom event strings, or Boolean expressions:

```java
@Action({
        EventType.InputEvent,
        ChatResponseEvent.EVENT_TYPE,
        "MyCustomEvent",
        "attributes.urgent == true"})
public static void handleAnyTrigger(Event event, RunnerContext ctx) {}
```

In the example above, an input event matches even when `urgent` is false because separate entries
are OR branches. Put type and attribute checks in one expression when both are required:

```java
@Action("type == EventType.InputEvent && input.score > 5 && input.ip.name == 'Chinese'")
public static void handleQualifiedInput(Event event, RunnerContext ctx) {}
```

Dots and hyphens are valid in a bare exact event type. Quote the event type when its name contains
other punctuation or would otherwise be interpreted as a condition expression:

```java
@Action("com.example.order.created")
public static void handleDottedOrderCreated(Event event, RunnerContext ctx) {}

@Action("order-created")
public static void handleOrderCreated(Event event, RunnerContext ctx) {}

@Action("'order:created'")
public static void handleColonOrderCreated(Event event, RunnerContext ctx) {}
```

Actions can also be registered programmatically:

{{< tabs "Programmatic Action" >}}

{{< tab "Python" >}}
```python
agent.add_action(
    "process_event",
    [
        EventType.InputEvent,
        "type == EventType.ChatResponseEvent && response.content != ''",
    ],
    process_event,
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class ProgrammaticAgent extends Agent {
    public ProgrammaticAgent() throws NoSuchMethodException {
        addAction(
                new String[] {
                    EventType.InputEvent,
                    "type == EventType.ChatResponseEvent && response.content != ''"
                },
                ProgrammaticAgent.class.getMethod(
                        "processEvent", Event.class, RunnerContext.class));
    }

    public static void processEvent(Event event, RunnerContext ctx) {
        // Handle either matching event.
    }
}
```
{{< /tab >}}

{{< /tabs >}}

### Condition Data

Condition expressions are written in CEL and evaluated by the Java runtime for both Java and Python
actions. The runtime provides these framework variables:

- `type`: the event type string.
- `id`: the event ID as a string.
- `EventType`: the built-in event-type constants.
- `attributes`: the event's attribute map.

Referenced top-level attributes are also available as bare variables, so `score > 80` and
`attributes.score > 80` refer to the same field. All four framework variables take precedence over
attributes with the same names. Use the `attributes` namespace, such as `attributes["type"]` or
`attributes["id"]`, to access colliding attribute keys.

Nested values are not flattened. For an input event whose attributes are
`{input: {status: "ok"}}`, use `input.status` or `attributes.input.status`; bare `status` is not
available. Other event payloads keep their top-level envelope, for example `response.content`.

Use a literal index for top-level keys containing dots, such as `attributes["a.b.c"]`, and test
static membership with `"a.b.c" in attributes`. Dynamic access at the root, such as
`attributes[key]`, and expressions over the whole `attributes` map are not supported. Dynamic access
inside an already selected top-level attribute still uses standard CEL map/list access within a
condition expression.

A present attribute whose value is `null` remains `null`; a missing attribute remains absent, so use
`has(attributes.field)` to test presence before reading an optional field. Strings remain strings
even when they look like JSON. To match nested data, send a structured map/list value instead of a
JSON-encoded string. Decimal values and integers outside the signed 64-bit range are evaluated as
doubles and may lose precision.

The only CEL macro supported in condition expressions is `has(...)`. The standard comprehension
macros `exists`, `exists_one`, `all`, `filter`, and `map` are not supported.

Java validates trigger-condition classification and CEL syntax when it builds the agent plan. Python
first checks the list shape, then sends the serialized plan to the same Java validation during
`apply()`. The Java runtime performs the final type check and evaluates conditions, so Java, Python,
and YAML plans share one contract for condition expressions rather than separate language-specific
evaluators.

An action can also send events to trigger other actions or emit output downstream.

**Trigger another action** — send a built-in or custom event that matches another action's trigger
conditions:

{{< tabs "Trigger Another Action" >}}

{{< tab "Python" >}}
```python
@action(EventType.InputEvent)
@staticmethod
def process_input(event: Event, ctx: RunnerContext) -> None:
    # send a ChatRequestEvent to trigger the built-in chat-model action
    ctx.send_event(ChatRequestEvent(model="my_model", messages=messages))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Action(EventType.InputEvent)
public static void processInput(Event event, RunnerContext ctx) throws Exception {
    InputEvent inputEvent = InputEvent.fromEvent(event);
    // send ChatRequestEvent
    ctx.sendEvent(new ChatRequestEvent("my_model", messages));
}
```
{{< /tab >}}

{{< /tabs >}}

**Emit downstream output** — send an `OutputEvent` to produce an output of the agent:

{{< tabs "Emit Output" >}}

{{< tab "Python" >}}
```python
@action(EventType.ChatResponseEvent)
@staticmethod
def emit_output(event: Event, ctx: RunnerContext) -> None:
    # output data to downstream
    ctx.send_event(OutputEvent(output=result))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Action(EventType.ChatResponseEvent)
public static void emitOutput(Event event, RunnerContext ctx) {
    // output data to downstream
    ctx.sendEvent(new OutputEvent(result));
}
```
{{< /tab >}}

{{< /tabs >}}

{{< hint info >}}
After the current action finishes, an `OutputEvent` is emitted directly to the agent's downstream,
bypassing action routing. Other events (such as `ChatRequestEvent`) go through action matching and
trigger every action whose trigger conditions match. Although the API accepts `OutputEvent` as a
trigger condition, runtime dispatch bypasses action matching for this event, so an action using that
trigger condition does not run. Sending a `ChatRequestEvent` and an `OutputEvent` from the same
action is valid API usage, but it produces both a direct downstream output and, once the chat
response is handled, a later model-based output. For the normal chat request/response workflow, emit
the `OutputEvent` from the action that handles the `ChatResponseEvent`, as shown above.
{{< /hint >}}

### Durable Execution

Use durable execution when you wrap a time-consuming or side-effecting operation. The framework persists the result and replays it on recovery when the same call is encountered, so the function will not be called again and side effects are avoided. When recovery re-enters an action that has not been recorded as completed, code outside `durable_execute` / `durable_execute_async` will still be re-executed.

**Constraints:**
- The function must be deterministic and called in the same order on recovery.
- Access to Memory and `send_event` is prohibited inside the function/callable.
- Arguments and results must be serializable.

{{< hint info >}}
Durable execution requires an external action state store. See
[Exactly-Once Action Consistency]({{< ref "docs/operations/deployment#exactly-once-action-consistency" >}})
on how to setup and configure the external action state store.
{{< /hint >}}

**Best-effort replay:**
- Results may not be reused if call order or arguments change (non-deterministic actions), which clears subsequent cached results and re-executes.
- If a failure happens after a function starts but before it completes and its result is persisted, the call will be re-executed. See the "With a reconciler" section below.
- In Python async actions, if `ctx.durable_execute_async(...)` is not awaited, the result is not recorded and cannot be replayed.

**With a reconciler:**

Use a reconciler for durable calls when the original call may already have completed but its result or failure has not yet been persisted, so the framework cannot determine during recovery whether the call needs to be executed again. A reconciler provides custom logic that can return the result or raise the failure for the durable call instead of re-executing the original call.

- A durable call may optionally provide a reconciler that is used only during recovery, when the same durable call is revisited and no execution result has been persisted for it yet.
- If the reconciler logic returns a result, the runtime persists and replays that recovered result.
- If the reconciler logic raises an exception, the runtime persists and replays that recovered failure.

{{< tabs "Durable Execution" >}}
{{< tab "Python" >}}
Python actions can call `ctx.durable_execute(...)` to run a synchronous durable code block.
```python
@action(EventType.InputEvent)
@staticmethod
def process_input(event: Event, ctx: RunnerContext) -> None:
    input_event = InputEvent.from_event(event)
    def slow_external_call(data: str) -> str:
        time.sleep(2)
        return f"Processed: {data}"

    # Synchronous durable execution
    result = ctx.durable_execute(slow_external_call, input_event.input)
    ctx.send_event(OutputEvent(output=result))
```

You can also pass an optional `reconciler` callable to recover an execution outcome during recovery.
```python
@action(EventType.InputEvent)
@staticmethod
def process_input(event: Event, ctx: RunnerContext) -> None:
    input_event = InputEvent.from_event(event)

    def submit_payment(order_id: str) -> str:
        return payment_client.submit(order_id)

    def payment_reconciler() -> str:
        status = payment_client.get_status(input_event.input)
        if status == "SUCCEEDED":
            return payment_client.lookup_completed_payment(input_event.input)
        raise payment_client.get_failure(input_event.input)

    result = ctx.durable_execute(
        submit_payment,
        input_event.input,
        reconciler=payment_reconciler,
    )
    ctx.send_event(OutputEvent(output=result))
```
{{< /tab >}}

{{< tab "Java" >}}
Java actions use `DurableCallable<T>` with `ctx.durableExecute(...)`, where `getId()` must be stable and `getResultClass()` supports recovery deserialization.
```java
@Action(EventType.InputEvent)
public static void processInput(Event event, RunnerContext ctx) throws Exception {
    InputEvent inputEvent = InputEvent.fromEvent(event);
    DurableCallable<String> call = new DurableCallable<>() {
        @Override
        public String getId() {
            return "slow_external_call";
        }

        @Override
        public Class<String> getResultClass() {
            return String.class;
        }

        @Override
        public String call() throws Exception {
            Thread.sleep(2000);
            return "Processed: " + inputEvent.getInput();
        }
    };

    String result = ctx.durableExecute(call);
    ctx.sendEvent(new OutputEvent(result));
}
```

Java actions can also override `reconciler()` to recover an execution outcome during recovery.
```java
@Action(EventType.InputEvent)
public static void processInput(Event event, RunnerContext ctx) throws Exception {
    InputEvent inputEvent = InputEvent.fromEvent(event);
    DurableCallable<String> call = new DurableCallable<>() {
        @Override
        public String getId() {
            return "submit_payment";
        }

        @Override
        public Class<String> getResultClass() {
            return String.class;
        }

        @Override
        public String call() {
            return paymentClient.submit(inputEvent.getInput());
        }

        @Override
        public Callable<String> reconciler() {
            return () -> {
                PaymentStatus status =
                    paymentClient.getStatus(inputEvent.getInput());
                if (status == PaymentStatus.SUCCEEDED) {
                    return paymentClient.lookupCompletedPayment(
                        inputEvent.getInput());
                }
                throw paymentClient.getFailure(inputEvent.getInput());
            };
        }
    };

    String result = ctx.durableExecute(call);
    ctx.sendEvent(new OutputEvent(result));
}
```
{{< /tab >}}
{{< /tabs >}}

### Async Execution

Async execution uses the same durable semantics but yields while waiting for a thread-pool task. This is useful for high-latency I/O.

{{< tabs "Async Execution" >}}
{{< tab "Python" >}}
Define an `async def` action and `await ctx.durable_execute_async(...)`. The same optional `reconciler=...` argument is available for recovery.
```python
@action(EventType.InputEvent)
@staticmethod
async def process_with_async(event: Event, ctx: RunnerContext) -> None:
    input_event = InputEvent.from_event(event)
    def slow_external_call(data: str) -> str:
        time.sleep(2)
        return f"Processed: {data}"

    result = await ctx.durable_execute_async(slow_external_call, input_event.input)
    ctx.send_event(OutputEvent(output=result))
```
{{< hint info >}}
Python async actions only support `await ctx.durable_execute_async(...)`. Standard asyncio
functions like `asyncio.gather`, `asyncio.wait`, `asyncio.create_task`, and
`asyncio.sleep` are **NOT** supported because there is no asyncio event loop.
{{< /hint >}}
{{< /tab >}}

{{< tab "Java" >}}
Use `ctx.durableExecuteAsync(DurableCallable)`; on **JDK 21+** it yields using Continuation,
and on **JDK < 21** it falls back to synchronous execution. The same optional `reconciler()` hook can be used for recovery.
```java
@Action(EventType.InputEvent)
public static void processInput(Event event, RunnerContext ctx) throws Exception {
    InputEvent inputEvent = InputEvent.fromEvent(event);
    DurableCallable<String> call = new DurableCallable<>() {
        @Override
        public String getId() {
            return "slow_external_call";
        }

        @Override
        public Class<String> getResultClass() {
            return String.class;
        }

        @Override
        public String call() throws Exception {
            Thread.sleep(2000);
            return "Processed: " + inputEvent.getInput();
        }
    };

    String result = ctx.durableExecuteAsync(call);
    ctx.sendEvent(new OutputEvent(result));
}
```

{{< hint info >}}
To use async execution on JDK 21+, user should append jvm option `--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED` to [env.java.opts.all](https://nightlies.apache.org/flink/flink-docs-stable/docs/deployment/config/#env-java-opts-all) before start the flink cluster.
{{< /hint >}}
{{< /tab >}}
{{< /tabs >}}

### Cross-language Actions

An action declared in one language can dispatch its body to the other language by setting a `target` on the decorator/annotation. The decorated function or annotated method then acts as a stub — it should raise so direct calls outside the framework fail loud.

{{< tabs "Cross-language Actions" >}}

{{< tab "Python" >}}
```python
from flink_agents.api.function import JavaFunction

class MyAgent(Agent):
    @action(
        InputEvent.EVENT_TYPE,
        # Action signatures are fixed (Event, RunnerContext), so for_action
        # fills the Java parameter types for you — only the class and method.
        target=JavaFunction.for_action("com.example.MyHandlers", "handleInput"),
    )
    @staticmethod
    def handle_input(event: Event, ctx: RunnerContext) -> None:
        raise NotImplementedError("cross-language stub")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class MyAgent extends Agent {
    @Action(
            value = EventType.InputEvent,
            target = @PythonFunction(
                    module = "my_pkg.handlers",
                    qualname = "handle_input"))
    public static void handleInput(Event event, RunnerContext ctx) {
        throw new UnsupportedOperationException("cross-language stub");
    }
}
```
{{< /tab >}}

{{< /tabs >}}

{{< hint warning >}}
**Limitations:**

- Cross-language actions are currently supported only when [running in Flink]({{< ref "docs/operations/deployment#run-in-flink" >}}), not in local development mode
- Complex object serialization between languages may have limitations
{{< /hint >}}

## Event

Events are JSON-serializable messages passed between actions. Every event has a `type` string and an
`attributes` map that carries its payload. Action routing can match the exact event type or evaluate
a condition expression against the event data. One event may trigger multiple actions.

### Special Events

* `InputEvent`: Generated by the framework when an input record arrives. The record is available in
  the event's `input` attribute. Actions whose trigger conditions match an `InputEvent` are the
  agent's entry points.
* `OutputEvent`: When an action sends this event, the framework emits its `output` attribute
  downstream as an agent output. It bypasses action matching.

### Unified Event

For simple cases, users can pass data between actions directly using `Event` with a custom `type` and `attributes`, without needing to define a subclass. For more structured events, see [Custom Event Subclasses](#custom-event-subclasses) below.

{{< tabs "Unified Event" >}}

{{< tab "Python" >}}
```python
# Send a unified event from one action
@action(EventType.InputEvent)
@staticmethod
def create_my_event(event: Event, ctx: RunnerContext) -> None:
    ctx.send_event(
        Event(type="my_event", attributes={"field1": "test", "field2": 42})
    )

# Consume it in another action
@action("my_event")
@staticmethod
def handle_my_event(event: Event, ctx: RunnerContext) -> None:
    field1: str = event.get_attr("field1")
    field2: int = event.get_attr("field2")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Send a unified event from one action
@Action(EventType.InputEvent)
public static void createMyEvent(Event event, RunnerContext ctx) {
    ctx.sendEvent(new Event("my_event", Map.of("field1", "test", "field2", 42)));
}

// Consume it in another action
@Action("my_event")
public static void handleMyEvent(Event event, RunnerContext ctx) {
    String field1 = (String) event.getAttr("field1");
    int field2 = (int) event.getAttr("field2");
}
```
{{< /tab >}}

{{< /tabs >}}

{{< hint info >}}
`upstreamEventId` and `upstreamActionName` (`upstream_event_id` and
`upstream_action_name` in Python) are framework-managed lineage metadata. User code should keep
user data in `attributes`. Values accepted during deserialization or reconstruction are
overwritten when an Action emits the Event.
{{< /hint >}}

### JSON Serialization

Events are serialized as JSON when passed between Python actions or across the Java-Python boundary. This means attribute values of non-trivial types (such as Pydantic models) lose their type information and arrive as plain `dict` objects. Users must manually reconstruct the typed object:

```python
input_event = InputEvent.from_event(event)
input_data = ItemData.model_validate(input_event.input)
```

### Custom Event Subclasses

Users can also define custom event subclasses for reusable, structured events. Data should be stored in the `attributes` map, and the subclass must implement a `from_event` / `fromEvent` factory method that validates required attributes and reconstructs typed objects from the deserialized data.

{{< tabs "Custom Event" >}}

{{< tab "Python" >}}
```python
class MyEvent(Event):
    EVENT_TYPE: ClassVar[str] = "my_event"

    def __init__(self, value: str) -> None:
        super().__init__(type=MyEvent.EVENT_TYPE, attributes={"value": value})

    @classmethod
    @override
    def from_event(cls, event: Event) -> "MyEvent":
        assert "value" in event.attributes
        result = cls(value=event.attributes["value"])
        return result.reconstruct_from(event)

    @property
    def value(self) -> str:
        return self.get_attr("value")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class MyEvent extends Event {
    public static final String EVENT_TYPE = "my_event";

    public MyEvent(String value) {
        super(EVENT_TYPE);
        setAttr("value", value);
    }

    @JsonCreator
    public MyEvent(
            @JsonProperty("id") UUID id,
            @JsonProperty("attributes") Map<String, Object> attributes) {
        super(id, EVENT_TYPE, attributes);
    }

    public static MyEvent fromEvent(Event event) {
        return reconstructFrom(event, MyEvent::new);
    }

    public String getValue() {
        return (String) getAttr("value");
    }
}
```
{{< /tab >}}

{{< /tabs >}}

{{< hint warning >}}
Python Event IDs are immutable. Custom `from_event` implementations written against earlier
versions may assign `result.id = event.id`; that assignment now raises a Pydantic
`ValidationError`. Replace `result.id = event.id` followed by `return result` with
`return result.reconstruct_from(event)`.

Typed reconstruction represents the same Event occurrence, so it must preserve the base Event's
identity and framework-managed metadata:

- **Python**: return `reconstruct_from(event)` after constructing the typed object.
  It returns a new typed object with the Event's UUIDv4 `id`, `upstream_event_id`, and
  `upstream_action_name`; the returned Event's `id` remains immutable.
- **Java**: implement the typed constructor that accepts `(UUID id, Map<String, Object>
  attributes)`, then have `fromEvent` return
  `reconstructFrom(event, MyEvent::new)`. The framework supplies the original ID and
  attributes, validates that the ID is preserved, and carries over `sourceTimestamp`,
  `upstreamEventId`, and `upstreamActionName`.
{{< /hint >}}

{{< hint info >}}
All attribute values must be JSON-serializable. In Python, this means `BaseModel`-serializable or primitive types. In Java, values must be Jackson-serializable.
{{< /hint >}}

{{< hint warning >}}
When defining a custom `Event` subclass in Java, annotate its `(UUID id, Map<String, Object> attributes)` constructor with `@JsonCreator`, and annotate both parameters with `@JsonProperty`. Durable execution stores events in `ActionState` and uses Jackson to restore concrete event subclasses during recovery. Without these annotations, recovery deserialization fails because the base `Event` constructor's `@JsonCreator` is not inherited by subclasses.

If the subclass stores typed objects in `attributes`, convert them back to their typed forms inside this constructor too, as the built-in events do. For example, `ChatResponseEvent` restores its typed attributes like this:

```java
@JsonCreator
public ChatResponseEvent(
        @JsonProperty("id") UUID id,
        @JsonProperty("attributes") Map<String, Object> attributes) {
    super(id, EVENT_TYPE, normalizeAttributes(attributes));
}

/** Converts nested attributes back to their typed forms. */
private static Map<String, Object> normalizeAttributes(Map<String, Object> attributes) {
    Object rawId = attributes.get("request_id");
    if (rawId instanceof String) {
        attributes.put("request_id", UUID.fromString((String) rawId));
    }
    Object rawResponse = attributes.get("response");
    if (rawResponse instanceof Map) {
        attributes.put("response", MAPPER.convertValue(rawResponse, ChatMessage.class));
    }
    return attributes;
}
```
{{< /hint >}}

## Built-in Events and Actions

There are several built-in `Event` and `Action` in Flink-Agents:
* See [Chat Models]({{< ref "docs/development/chat_models#built-in-events-and-actions" >}}) for how to chat with a LLM leveraging built-in action and events.
* See [Tool Use]({{< ref "docs/development/tool_use#built-in-events-and-actions" >}}) for how to programmatically use a tool leveraging built-in action and events.
* See [Vector Stores]({{< ref "docs/development/vector_stores#built-in-events-and-actions" >}}) for how to retrieve context from vector stores leveraging built-in action and events.
