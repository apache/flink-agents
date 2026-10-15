---
title: "Actions"
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

## Action

An action is a piece of code that can be executed. It declares one or more trigger conditions and is
triggered when an event matches one of them.

Use `@action(*trigger_conditions, name=None)` to decorate a Python function or `@Action({...})` to
annotate a Java method. The function or method accepts the triggering `Event` and a `RunnerContext`.
It sends events through the context rather than returning a result, so declare its return type as
`None` in Python or `void` in Java. A native Java action must be `public static`. Python actions can
also be defined as `async def` when using async execution (see [Async Execution](#async-execution)).

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

### Registering Actions Programmatically

As an alternative to annotations, register an action through the `Agent` API:

{{< tabs "Programmatic Action" >}}

{{< tab "Python" >}}
```python
agent.add_action(
    "process_event",
    [EventType.InputEvent],
    process_event,
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class ProgrammaticAgent extends Agent {
    public ProgrammaticAgent() throws NoSuchMethodException {
        addAction(
                new String[] {EventType.InputEvent},
                ProgrammaticAgent.class.getMethod(
                        "processEvent", Event.class, RunnerContext.class));
    }

    public static void processEvent(Event event, RunnerContext ctx) {
        // Handle the input event.
    }
}
```
{{< /tab >}}

{{< /tabs >}}

### Trigger Conditions

A trigger condition is either:

- An **exact event type**, such as `EventType.InputEvent` or a custom event's `EVENT_TYPE` constant.
- A **condition expression** that evaluates event data to `true` or `false`. Expressions are written
  in [Common Expression Language (CEL)](https://cel.dev/); Flink Agents supports the subset described
  in [Trigger Condition Reference](#trigger-condition-reference).

Conditions on the same action are alternatives (OR). For example, separate conditions for
`EventType.InputEvent` and `attributes.urgent == true` allow any input event to trigger the action,
even when `urgent` is false. To require both, combine them in one expression:

```text
type == EventType.InputEvent && attributes.urgent == true
```

When passed directly to `@action` or `@Action`, `EventType.InputEvent` declares an exact event-type
condition. Within an expression, it is a value and must be compared with `type`, as shown above. A
bare identifier or dotted path is treated as an exact event type, so write attribute checks
explicitly, for example `ready == true` or `attributes.ready == true`.

#### Matching Behavior

When an event arrives:

1. The runtime selects every action with an exact event-type condition that matches the event's type.
2. For each remaining action, it evaluates expressions in declaration order and stops at the first
   `true` result.
3. Every selected action is executed once, even if more than one of its conditions matches.

### Sending Events

An action can send an event to trigger another action or emit output downstream.

**Trigger another action** — send a built-in or custom event that matches another action's trigger
conditions:

{{< tabs "Trigger Another Action" >}}

{{< tab "Python" >}}
```python
@action(EventType.InputEvent)
@staticmethod
def process_input(event: Event, ctx: RunnerContext) -> None:
    ctx.send_event(ChatRequestEvent(model="my_model", messages=messages))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Action(EventType.InputEvent)
public static void processInput(Event event, RunnerContext ctx) throws Exception {
    ctx.sendEvent(new ChatRequestEvent("my_model", messages));
}
```
{{< /tab >}}

{{< /tabs >}}

**Emit downstream output** — send an `OutputEvent`:

{{< tabs "Emit Output" >}}

{{< tab "Python" >}}
```python
@action(EventType.ChatResponseEvent)
@staticmethod
def emit_output(event: Event, ctx: RunnerContext) -> None:
    ctx.send_event(OutputEvent(output=result))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Action(EventType.ChatResponseEvent)
public static void emitOutput(Event event, RunnerContext ctx) {
    ctx.sendEvent(new OutputEvent(result));
}
```
{{< /tab >}}

{{< /tabs >}}

{{< hint info >}}
`OutputEvent` is emitted directly downstream and bypasses action matching. Therefore, an
`OutputEvent` trigger never invokes an action. Other events go through action matching as usual.
{{< /hint >}}

### Trigger Condition Reference

#### Custom Event-Type Names

A custom event type may be a bare name such as `order.created` or `order-created`. Each dot-separated
segment must start with an ASCII letter or underscore and may then contain ASCII letters, digits,
underscores, or hyphens. Quote a name that contains other punctuation or would otherwise be parsed as
an expression, for example `'order:created'`, `'true'`, or `'EventType.custom'`. In Java and Python,
the quotes are part of the condition string. For example, use `@Action("'order:created'")` in Java or
`@action("'order:created'")` in Python. A quoted name matches the literal event-type string; it does
not reference a built-in `EventType` constant. Quoted names must be non-empty and cannot contain
whitespace, quotes, backslashes, or control characters.

#### Expression Variables

Java and Python actions use the same Java runtime to evaluate condition expressions. Expressions can
access these framework variables:

| Variable     | Value                                |
|--------------|--------------------------------------|
| `type`       | The event type string                |
| `id`         | The event ID as a string             |
| `EventType`  | The built-in event-type constants    |
| `attributes` | The event's top-level attribute map  |

#### Accessing Attributes

- Top-level attributes are also available as bare variables, so `score > 80` and
  `attributes.score > 80` refer to the same field.
- Nested values are not flattened. For `{input: {status: "ok"}}`, use `input.status` or
  `attributes.input.status`; bare `status` does not refer to the nested value. Other event payloads
  keep their top-level envelope, for example `response.message.blocks`.
- Framework variables take precedence over attributes with the same names. Use `attributes["type"]`
  or `attributes["id"]` to access a colliding attribute.
- For a top-level key containing dots, use a literal index such as `attributes["a.b.c"]`. Test its
  presence with `"a.b.c" in attributes`.

#### Missing Attributes and Value Types

A present attribute whose value is `null` remains `null`, while a missing attribute remains absent.
Use `has(attributes.field)` before reading an optional attribute. Reading a missing attribute without
a guard causes an event-time evaluation failure. With the default `WARN_AND_SKIP` strategy, the
runtime logs a warning, treats that condition as false, and continues with later OR conditions;
`FAIL` fails the Flink task. See the
[condition evaluation failure strategy]({{< ref "docs/operations/configuration#core-options" >}}).

Strings remain strings even when they contain JSON. To match nested data, send a structured map or
list instead of a JSON-encoded string. Decimal values and integers outside the signed 64-bit range are
evaluated as doubles and may lose precision.

#### Limitations

- `has(...)` is the only supported CEL macro. The comprehension macros `exists`, `exists_one`,
  `all`, `filter`, and `map` are not supported.
- Dynamic root access such as `attributes[key]` and operations over the whole `attributes` map are
  rejected when the runtime compiles the condition. Operator initialization fails, so the job cannot
  start. This happens before event-time evaluation and is not controlled by the condition evaluation
  failure strategy. Dynamic access inside a selected top-level attribute remains supported.

### Cross-language Actions

To declare a cross-language Action, apply `action(...)` to a `Function` descriptor
in Python, or annotate a `static final` `Function` field in Java. The descriptor
identifies the function to execute.

{{< tabs "Cross-language Actions" >}}

{{< tab "Python" >}}
```python
from flink_agents.api.function import JavaFunction

class MyAgent(Agent):
    # Action signatures are fixed (Event, RunnerContext), so for_action
    # fills the Java parameter types for you — only the class and method.
    handle_input = action(InputEvent.EVENT_TYPE)(
        JavaFunction.for_action("com.example.MyHandlers", "handleInput"))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.function.PythonFunction;

public class MyAgent extends Agent {
    @Action(EventType.InputEvent)
    private static final PythonFunction handleInput =
            PythonFunction.of("my_pkg.handlers", "handle_input");
}
```
{{< /tab >}}

{{< /tabs >}}

**Override the Action name (optional)**

By default, the examples above use the member name as the Action name:
`handle_input` in Python and `handleInput` in Java. Set `name` to give the Action
a different name. These declarations name the Action `process_input`:

{{< tabs "Override Cross-language Action Name" >}}

{{< tab "Python" >}}
```python
class MyAgent(Agent):
    handle_input = action(InputEvent.EVENT_TYPE, name="process_input")(
        JavaFunction.for_action("com.example.MyHandlers", "handleInput")
    )
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class MyAgent extends Agent {
    @Action(value = EventType.InputEvent, name = "process_input")
    private static final PythonFunction handleInput =
            PythonFunction.of("my_pkg.handlers", "handle_input");
}
```
{{< /tab >}}

{{< /tabs >}}

For dependency setup and supported payload types, see
[Choose APIs and Languages]({{< ref "docs/development/create-agent/choose-apis-and-languages" >}}).

### Async Execution

Use `execute_async` (Python) or `executeAsync` (Java) to run a function
asynchronously, for example when querying an external service.

These methods return an `AsyncFuture`. Work starts when you await the future,
not when you create it. Await one call directly, or use `ctx.gather(...)` to combine
multiple calls. Awaiting each call separately executes them sequentially.

{{< tabs "Ordinary Async Execution" >}}
{{< tab "Python" >}}
Inside an `async def` action, pass synchronous functions to `execute_async`:

```python
profile = ctx.execute_async(query_profile, user_id)
inventory = ctx.execute_async(query_inventory, item_id)
outcomes = await ctx.gather(profile, inventory)
for outcome in outcomes:
    if outcome.is_failure():
        raise outcome.error
profile_result = outcomes[0].value
```

For a single call, use `result = await ctx.execute_async(query_profile, user_id)`.
Use `ctx.gather` for composition; standard asyncio functions are not supported.
{{< /tab >}}
{{< tab "Java" >}}
```java
AsyncFuture<String> profile = ctx.executeAsync(() -> queryProfile(userId));
AsyncFuture<String> inventory = ctx.executeAsync(() -> queryInventory(itemId));
List<Outcome<String>> outcomes = ctx.gather(List.of(profile, inventory)).await();
for (Outcome<String> outcome : outcomes) {
    if (outcome.isFailure()) {
        throw outcome.getError();
    }
}
String profileResult = outcomes.get(0).getValue();
```

For a single call, use `String result = ctx.executeAsync(() -> queryProfile(userId)).await()`.
{{< /tab >}}
{{< /tabs >}}

`gather` returns an `Outcome` for each call in input order, including individual
failures. Batch concurrency is controlled by `async.batch.parallelism`.
Java and Python support concurrent batches on all supported JDK versions.

Keep futures within the action that created them. Repeated awaits reuse the same
future's result or exception; a future that is never awaited performs no work.
Obtain resources before creating calls, and update memory or send events after
awaiting results. Callbacks must not access `RunnerContext`, and any clients they
share must support concurrent use.

#### JDK and scheduling requirements

For concurrency between Actions:

- **Java on JDK 21+:** enable continuation-based execution by adding
  `--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED` to the JVM options, including
  `env.java.opts.all` when running on a cluster.
- **Java on JDK 11–20:** the experimental parallel execution engine is enabled by
  default for plans whose Actions are all Java. If the plan declares Python Resources,
  it also requires a Flink version supporting cross-language async calls (below).
  Setting `parallel-execution.enabled` to `false`, or including Python Actions,
  disables this engine; a Java single-call `executeAsync(...).await()` then runs
  synchronously. Use `gather` to run a batch concurrently.
- **Python Actions:** use `async def` and the framework's `AsyncFuture`/`gather` APIs.
  Use `gather` to await multiple calls concurrently; awaiting each call in turn runs
  them sequentially.

See [Core Options]({{< ref "docs/operations/configuration#core-options" >}})
for worker-pool and concurrency limits. These scheduling rules also apply to durable
async calls; persistence adds the [durable execution requirements](#durable-execution).

#### Cross-language async requirements

Async calls across Java/Python require Flink **1.20.5+**, **2.0.2+**, **2.1.3+**,
**2.2.1+**, or **2.3+**, within the respective release line. Use these versions for
custom cross-language async calls and for asynchronous built-in chat and
context-retrieval Actions that use an opposite-language Resource.

{{< hint warning >}}
Async actions in either language may call cross-language resources. Within one async execution,
keep the cross-language call chain to one reverse callback. Only the following call shapes are
supported:

- Python action → Java resource → Python callback
- Java action → Python resource → Java callback

For example, a Python action may use a Java `ChatModelSetup` backed by a Python
`ChatModelConnection`. Multiple or deeper cross-language callbacks, such as
Python→Java→Python→Java or Java→Python→Java→Python, are not recommended and may deadlock
when an outer call occupies an async or interpreter worker while waiting for another worker.
{{< /hint >}}

### Durable Execution

Use durable execution when an operation needs result persistence and recovery replay. When a matching persisted result is available during recovery, the framework reuses it without invoking the function again. When recovery re-enters an action that has not been recorded as completed, code outside `durable_execute` / `durable_execute_async` will still be re-executed.

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
- In Python, a recorded failure is replayed as the original exception when it can be pickled and rebuilt. Otherwise it is replayed as a `RuntimeError` with the message `<class>: <message>`, so code that catches a specific exception type may take a different path during replay. A failure that cannot be pickled is recorded this way too, so the call is not executed again on recovery. Failures recorded by a version that stored only the pickled exception have no class name or message to fall back on, and a version without this format cannot read failures recorded with it.

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

### Durable Async Execution

Durable async execution adds result persistence and recovery replay to
[async execution](#async-execution). Ordinary async calls may execute again if an
action is re-executed after a failure. Use `durable_execute_async` (Python) or
`durableExecuteAsync` (Java) when you need to reuse a saved result instead.

Await the returned `DurableFuture` directly or combine calls with `ctx.gather(...)`.
A batch can mix ordinary and durable calls; only durable calls save their results
for recovery. The [durable execution constraints](#durable-execution) also apply.

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
{{< /tab >}}

{{< tab "Java" >}}
Use `ctx.durableExecuteAsync(DurableCallable)` and await the returned future.
The optional `reconciler()` hook can be used for recovery.
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

    DurableFuture<String> future = ctx.durableExecuteAsync(call);
    String result = future.await();
    ctx.sendEvent(new OutputEvent(result));
}
```

{{< /tab >}}
{{< /tabs >}}
