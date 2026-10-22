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

An Action reacts to Events that match its trigger conditions. It receives the triggering
`Event` and a `RunnerContext`, which it uses to access Resources, work with Memory, and
send new Events. Several Actions can react to the same Event; handling an Event does not
prevent other matching Actions from receiving it.

Define an Action with `@action(...)` in Python or `@Action(...)` in Java. The function or
method sends its output through the context, so its return type is `None` in Python or
`void` in Java. A native Java Action must be `public static`.

This example accepts a review as a string, removes surrounding whitespace, and sends the
cleaned text downstream. Imports and Flink job setup are omitted.

{{< tabs "Action Function" >}}

{{< tab "Python" >}}
```python
class ReviewTextAgent(Agent):
    @action(EventType.InputEvent)
    @staticmethod
    def clean_review(event: Event, ctx: RunnerContext) -> None:
        review = InputEvent.from_event(event).input
        ctx.send_event(OutputEvent(output=review.strip()))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class ReviewTextAgent extends Agent {
    @Action(EventType.InputEvent)
    public static void cleanReview(Event event, RunnerContext ctx) {
        String review = (String) InputEvent.fromEvent(event).getInput();
        ctx.sendEvent(new OutputEvent(review.trim()));
    }
}
```
{{< /tab >}}

{{< /tabs >}}

The framework creates an `InputEvent` for each input record. `OutputEvent` sends a result
downstream; other Events can connect Actions, as shown in [Sending Events](#sending-events).
For calls to external services, see [Async Execution](#async-execution), including how to
write Python Actions with `async def`.

By default, an Action uses its Python function name or Java method name: `clean_review`
in the Python example and `cleanReview` in the Java example. Set `name` in the decorator
or annotation to choose a different name, for example:

| Python | Java |
| --- | --- |
| `@action(EventType.InputEvent, name="process_review")` | `@Action(value = EventType.InputEvent, name = "process_review")` |

Action names must be unique within an Agent.

## Registering Actions Programmatically

You can also create an `Agent` instance and register a function or method with its trigger
conditions. These examples perform the same text cleanup as above without an Agent subclass
or an Action decorator or annotation:

{{< tabs "Programmatic Action" >}}

{{< tab "Python" >}}
```python
def clean_review(event: Event, ctx: RunnerContext) -> None:
    review = InputEvent.from_event(event).input
    ctx.send_event(OutputEvent(output=review.strip()))


agent = Agent()
agent.add_action("clean_review", [EventType.InputEvent], clean_review)
```
{{< /tab >}}

{{< tab "Java" >}}
Define the handler as a static method:

```java
public class ReviewActions {
    public static void cleanReview(Event event, RunnerContext ctx) {
        String review = (String) InputEvent.fromEvent(event).getInput();
        ctx.sendEvent(new OutputEvent(review.trim()));
    }
}
```

Then register it when setting up your job (the enclosing method must handle or declare
`NoSuchMethodException`):

```java
Agent agent = new Agent();
agent.addAction(
        "clean_review",
        new String[] {EventType.InputEvent},
        JavaFunction.fromMethod(
                ReviewActions.class.getMethod("cleanReview", Event.class, RunnerContext.class)),
        null);
```
{{< /tab >}}

{{< /tabs >}}

Both examples register the Action as `clean_review` through the first argument.
In Java, `JavaFunction` is imported from `org.apache.flink.agents.api.function`;
the final `null` means no Action-specific configuration is supplied.

## Trigger Conditions

Trigger conditions determine which Events an Action reacts to.

### Exact Event Type

The simplest way to register an Action is to specify one Event type. The Action runs
whenever an Event with that type arrives:

| Event Type | Python | Java |
| --- | --- | --- |
| Input records | `@action(EventType.InputEvent)` | `@Action(EventType.InputEvent)` |
| Custom `review_ready` Events | `@action("review_ready")` | `@Action("review_ready")` |

Use a built-in `EventType` constant for framework Events, or the type string you chose
for a custom Event. No attribute check is needed when you want to handle every Event
of that type.

### Single Condition

To select Events by their data, use a Boolean expression as the trigger condition.
This Action forwards an Event's attributes downstream when its `urgent` attribute is `true`:

{{< tabs "Single Condition" >}}

{{< tab "Python" >}}
```python
class ReviewRoutingAgent(Agent):
    @action("has(attributes.urgent) && attributes.urgent == true")
    @staticmethod
    def forward_review(event: Event, ctx: RunnerContext) -> None:
        ctx.send_event(OutputEvent(output=event.attributes))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class ReviewRoutingAgent extends Agent {
    @Action("has(attributes.urgent) && attributes.urgent == true")
    public static void forwardReview(Event event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent(event.getAttributes()));
    }
}
```
{{< /tab >}}

{{< /tabs >}}

`has(attributes.urgent)` checks that the optional attribute exists before reading it.
Expressions use the supported subset of
[Common Expression Language (CEL)](https://cel.dev/).

### Multiple Conditions

Conditions on the same Action are alternatives (**OR**). The following Action forwards
an Event's attributes downstream if its type is `review_ready` **or** its `urgent`
attribute is `true`:

{{< tabs "Multiple Trigger Conditions" >}}

{{< tab "Python" >}}
```python
class ReviewRoutingAgent(Agent):
    @action("review_ready", "has(attributes.urgent) && attributes.urgent == true")
    @staticmethod
    def forward_review(event: Event, ctx: RunnerContext) -> None:
        ctx.send_event(OutputEvent(output=event.attributes))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class ReviewRoutingAgent extends Agent {
    @Action({"review_ready", "has(attributes.urgent) && attributes.urgent == true"})
    public static void forwardReview(Event event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent(event.getAttributes()));
    }
}
```
{{< /tab >}}

{{< /tabs >}}

| Event type | `urgent` attribute | Does the Action run? |
| --- | --- | --- |
| `review_ready` | Absent or `false` | Yes: the Event type matches. |
| `review_updated` | `true` | Yes: the expression matches. |
| `review_ready` | `true` | Yes, once: matching both conditions does not cause a second execution. |
| `review_updated` | Absent or `false` | No. |

To require **both** the type and the attribute to match, put them in a single expression:

```text
type == 'review_ready' && has(attributes.urgent) && attributes.urgent == true
```

### Matching Behavior

For each Event, the framework selects every Action with a matching trigger condition.
Each selected Action runs once for that Event, even if several of its conditions match.
Different Actions can all respond to the same Event.

For supported names, attribute access, and evaluation failures, see the
[Trigger Condition Reference](#trigger-condition-reference).

## Sending Events

Use `ctx.send_event(...)` in Python or `ctx.sendEvent(...)` in Java to send an Event.
The Event's type and attributes determine which Actions react to it.

To split the text cleanup example into two steps, have the first Action send a
`review_ready` Event carrying the cleaned text. A second Action listens for that type
and sends the text downstream:

{{< tabs "Connect Actions with Events" >}}

{{< tab "Python" >}}
```python
class ReviewTextAgent(Agent):
    @action(EventType.InputEvent)
    @staticmethod
    def clean_review(event: Event, ctx: RunnerContext) -> None:
        review = InputEvent.from_event(event).input
        ctx.send_event(
            Event(type="review_ready", attributes={"text": review.strip()})
        )

    @action("review_ready")
    @staticmethod
    def emit_review(event: Event, ctx: RunnerContext) -> None:
        ctx.send_event(OutputEvent(output=event.get_attr("text")))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class ReviewTextAgent extends Agent {
    @Action(EventType.InputEvent)
    public static void cleanReview(Event event, RunnerContext ctx) {
        String review = (String) InputEvent.fromEvent(event).getInput();
        ctx.sendEvent(new Event("review_ready", Map.of("text", review.trim())));
    }

    @Action("review_ready")
    public static void emitReview(Event event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent(event.getAttr("text")));
    }
}
```
{{< /tab >}}

{{< /tabs >}}

Flink Agents automatically registers [built-in Actions]({{< ref "docs/development/create-agent/events#built-in-events-and-actions" >}})
to handle common request Events. For example, a `ChatRequestEvent`
asks the [built-in chat Action]({{< ref "docs/development/calling-llms/chat#chat-events" >}})
to call a registered model and send a `ChatResponseEvent`.

You can also define your own Events to pass data between Actions. See
[Creating and Handling Events]({{< ref "docs/development/create-agent/events#unified-event" >}})
for examples, or use [Custom Event Subclasses]({{< ref "docs/development/create-agent/events#custom-event-subclasses" >}})
to add typed access and validation.

{{< hint info >}}
`OutputEvent` is emitted directly downstream and bypasses Action matching. Therefore, an
`OutputEvent` trigger never invokes an Action. Other Events go through Action matching as usual.
{{< /hint >}}

## Cross-language Actions

An Agent can register Actions implemented in another language. A Python Agent can use
a Java method as an Action, and a Java Agent can use a Python function as an Action.
The handler still receives an `Event` and a `RunnerContext` and sends its output through
the context.

The following examples perform the same text cleanup as above. Each example shows the
handler and how to register it from the other language. Flink job setup is omitted.

{{< tabs "Cross-language Actions" >}}

{{< tab "Python Agent" >}}

Define the Java handler in `com/example/ReviewActions.java`:

```java
package com.example;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.context.RunnerContext;

public class ReviewActions {
    public static void cleanReview(Event event, RunnerContext ctx) {
        String review = (String) InputEvent.fromEvent(event).getInput();
        ctx.sendEvent(new OutputEvent(review.trim()));
    }
}
```

Then register that method in the Python Agent. `JavaFunction.for_action` takes the fully
qualified Java class name and method name:

```python
from flink_agents.api.agents.agent import Agent
from flink_agents.api.decorators import action
from flink_agents.api.events.event_type import EventType
from flink_agents.api.function import JavaFunction


class ReviewTextAgent(Agent):
    clean_review = action(EventType.InputEvent)(
        JavaFunction.for_action("com.example.ReviewActions", "cleanReview")
    )
```
{{< /tab >}}

{{< tab "Java Agent" >}}

Define the Python handler in `my_pkg/handlers.py`:

```python
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.runner_context import RunnerContext


def clean_review(event: Event, ctx: RunnerContext) -> None:
    review = InputEvent.from_event(event).input
    ctx.send_event(OutputEvent(output=review.strip()))
```

Then register that function in the Java Agent. `PythonFunction.of` takes the Python
module name and function name. Apply `@Action` to the `static final` field:

```java
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.function.PythonFunction;

public class ReviewTextAgent extends Agent {
    @Action(EventType.InputEvent)
    private static final PythonFunction cleanReview =
            PythonFunction.of("my_pkg.handlers", "clean_review");
}
```
{{< /tab >}}

{{< /tabs >}}

For cross-language Actions, the default Action name comes from the field used to
register it: `clean_review` in Python and `cleanReview` in Java. You can override it
with `name`, as with a native Action.

For dependency setup and supported payload types, see
[Choose APIs and Languages]({{< ref "docs/development/create-agent/choose-apis-and-languages" >}}).

## Async and Durable Execution

When an Action calls an external system, you can control how it waits for the call
and whether the call's outcome is saved for recovery:

- **[Async Execution](#async-execution)** lets the Action yield while waiting for external
  work, allowing other Actions to run. It also supports running multiple calls concurrently.
- **[Durable Execution](#durable-execution)** saves a call's outcome so it can be reused
  during recovery.
- **[Durable Async Execution](#durable-async-execution)** combines both capabilities.

See [Execution Requirements](#execution-requirements) for runtime prerequisites.

### Async Execution

Use async execution for calls that spend time waiting for external systems, such as
looking up a user profile. Async execution controls how calls run and how your Action
waits for them. To also save their results for recovery, see [Durable Async Execution](#durable-async-execution).

#### Single Call

The examples below accept a user ID as a string. `profile_service.get_profile` in Python
and `ProfileService.getProfile` in Java represent your application's synchronous client
function: each takes a user ID and returns a profile as a string. Supply your own client
implementation; framework imports and job setup are omitted.

{{< tabs "Single Async Call" >}}
{{< tab "Python" >}}
```python
class ProfileAgent(Agent):
    @action(EventType.InputEvent)
    @staticmethod
    async def process_input(event: Event, ctx: RunnerContext) -> None:
        user_id = InputEvent.from_event(event).input
        future = ctx.execute_async(profile_service.get_profile, user_id)
        profile = await future
        ctx.send_event(OutputEvent(output=profile))
```
{{< /tab >}}
{{< tab "Java" >}}
```java
public class ProfileAgent extends Agent {
    @Action(EventType.InputEvent)
    public static void processInput(Event event, RunnerContext ctx) throws Exception {
        String userId = (String) InputEvent.fromEvent(event).getInput();
        AsyncFuture<String> future = ctx.executeAsync(() -> ProfileService.getProfile(userId));
        String profile = future.await();
        ctx.sendEvent(new OutputEvent(profile));
    }
}
```
{{< /tab >}}
{{< /tabs >}}

Creating an `AsyncFuture` does not start the call. Awaiting it starts the work and waits
for its result. A future that is never awaited performs no work. Awaiting the same future
again reuses its result or exception within the current Action execution.

In Python, the Action uses `async def`, but the function passed to `execute_async` must
be synchronous. Use the framework's futures and `gather`; standard `asyncio` functions
such as `asyncio.gather` and `asyncio.create_task` are not supported.

#### Concurrent Calls

To look up a profile and preferences concurrently, create both futures and await them
with `ctx.gather(...)`. Here, `get_preferences` / `getPreferences` is another synchronous
application function that takes a user ID and returns a string.

{{< tabs "Concurrent Async Calls" >}}
{{< tab "Python" >}}
```python
class ProfileAgent(Agent):
    @action(EventType.InputEvent)
    @staticmethod
    async def process_input(event: Event, ctx: RunnerContext) -> None:
        user_id = InputEvent.from_event(event).input
        profile = ctx.execute_async(profile_service.get_profile, user_id)
        preferences = ctx.execute_async(profile_service.get_preferences, user_id)
        outcomes = await ctx.gather(profile, preferences)
        for outcome in outcomes:
            if outcome.is_failure():
                raise outcome.error
        ctx.send_event(OutputEvent(output=f"{outcomes[0].value}\n{outcomes[1].value}"))
```
{{< /tab >}}
{{< tab "Java" >}}
```java
public class ProfileAgent extends Agent {
    @Action(EventType.InputEvent)
    public static void processInput(Event event, RunnerContext ctx) throws Exception {
        String userId = (String) InputEvent.fromEvent(event).getInput();
        AsyncFuture<String> profile = ctx.executeAsync(() -> ProfileService.getProfile(userId));
        AsyncFuture<String> preferences =
                ctx.executeAsync(() -> ProfileService.getPreferences(userId));
        List<Outcome<String>> outcomes = ctx.gather(List.of(profile, preferences)).await();
        for (Outcome<String> outcome : outcomes) {
            if (outcome.isFailure()) {
                throw outcome.getError();
            }
        }
        ctx.sendEvent(
                new OutputEvent(outcomes.get(0).getValue() + "\n" + outcomes.get(1).getValue()));
    }
}
```
{{< /tab >}}
{{< /tabs >}}

Awaiting the futures individually, one after another, runs these calls sequentially.
Awaiting the gathered future allows them to run concurrently, subject to
`async.batch.parallelism` and the available worker threads.

#### Results and Failures

- Awaiting a single call returns its result or throws its exception.
- `gather` returns an `Outcome` for each call in input order, regardless of completion
  order. An individual call's failure is represented by a failed `Outcome`. Inspect the
  outcomes before using their values; the example propagates any individual failure.
- A batch may itself fail, for example because of a timeout or cancellation. It does not
  always return a list of outcomes.

Keep futures within the Action execution that created them. Do not pass them to another
Action or await them inside a submitted function. `gather` accepts distinct single-call
futures from the same context; nested gathered futures are not supported.

#### Using Resources and Memory

See [Referencing and Accessing Resources]({{< ref "docs/development/create-agent/resources#referencing-and-accessing-resources" >}})
for Resource lookup. In async Actions, the location of that lookup matters:

Distinguish the **Action body**, including code before and after await, from the
**function submitted to `execute_async` / `executeAsync`**:

| Operation | Action body | Submitted function |
| --- | --- | --- |
| Read or write Sensory Memory / Short-term Memory | Supported | Unsupported, including through a previously obtained `MemoryObject` |
| Look up a Resource with `ctx.get_resource` / `ctx.getResource` | Supported | Unsupported; obtain the Resource in the Action first |
| Send Events or invoke other `RunnerContext` operations | Supported | Unsupported |
| Get or delete a Long-term Memory set | Supported | Unsupported |
| Call `add`, `get`, `search`, or `delete` on a Long-term Memory `MemorySet` obtained in this Action | Supported | Supported |

Read the data you need and obtain Resources in the Action body, pass them to the call,
then update Sensory/Short-term Memory or send Events after awaiting the result. Clients
and Resources shared by concurrent calls must support concurrent use.

For Long-term Memory, obtain a `MemorySet` in each Action that needs one. The set retains
that Action's partition key, so its operations can run in the submitted function. For
example, inside an async Action with Long-term Memory configured:

{{< tabs "Async Long-term Memory Search" >}}
{{< tab "Python" >}}
```python
memory_set = ctx.long_term_memory.get_memory_set("conversations")
items = await ctx.execute_async(memory_set.search, query="user preferences", limit=5)
```
{{< /tab >}}
{{< tab "Java" >}}
```java
MemorySet memorySet = ctx.getLongTermMemory().getMemorySet("conversations");
List<MemorySetItem> items =
        ctx.executeAsync(() -> memorySet.search("user preferences", 5, null, Map.of())).await();
```
{{< /tab >}}
{{< /tabs >}}

Do not cache a `MemorySet` across Actions, or call `get_memory_set` / `getMemorySet` or
`delete_memory_set` / `deleteMemorySet` inside the submitted function. See
[Long-term Memory]({{< ref "docs/development/memory/long_term_memory" >}}) for configuration
and the scope of memory sets.

### Durable Execution

Use durable execution when a call's result should be saved and reused during recovery.
For example, if an Action fails after a profile lookup has completed and its result has
been persisted, recovery can reuse that profile instead of querying the service again.
Code outside the durable call may still run again when the Action is re-executed.

To save and reuse call outcomes during recovery, configure checkpointing and an external
Action State Store as described in
[Consistency]({{< ref "docs/operations/consistency#configure-an-action-state-store" >}}).

#### A Durable Call

The following examples make the profile lookup from [Single Call](#single-call) durable.
The synchronous API waits for the result before continuing the Action.

{{< tabs "Synchronous Durable Call" >}}
{{< tab "Python" >}}
```python
class ProfileAgent(Agent):
    @action(EventType.InputEvent)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        user_id = InputEvent.from_event(event).input
        profile = ctx.durable_execute(
            profile_service.get_profile,
            user_id,
            durable_id=f"profile:{user_id}",
        )
        ctx.send_event(OutputEvent(output=profile))
```
{{< /tab >}}
{{< tab "Java" >}}
```java
class ProfileLookup implements DurableCallable<String> {
    private final String userId;

    ProfileLookup(String userId) {
        this.userId = userId;
    }

    @Override
    public String getId() {
        return "profile:" + userId;
    }

    @Override
    public Class<String> getResultClass() {
        return String.class;
    }

    @Override
    public String call() throws Exception {
        return ProfileService.getProfile(userId);
    }
}

public class ProfileAgent extends Agent {
    @Action(EventType.InputEvent)
    public static void processInput(Event event, RunnerContext ctx) throws Exception {
        String userId = (String) InputEvent.fromEvent(event).getInput();
        String profile = ctx.durableExecute(new ProfileLookup(userId));
        ctx.sendEvent(new OutputEvent(profile));
    }
}
```
{{< /tab >}}
{{< /tabs >}}

Python's `durable_id` and Java's `getId()` identify the logical operation during recovery.
Java's `getResultClass()` provides the type needed to deserialize a saved result.

#### Recovery Behavior

See [Recovery flow]({{< ref "docs/operations/consistency#recovery-flow" >}}) for how recovery
handles saved outcomes and in-flight calls, and
[Known limitations]({{< ref "docs/operations/consistency#known-limitations" >}})
for operations that may repeat.

#### Reconciling an In-flight Call

A reconciler asks the external system what happened to an earlier call whose outcome
was not persisted. It is used only when recovery revisits a matching, recorded in-flight
call. A saved outcome is replayed directly; a new call executes normally.

The reconciler must return a recovered result or raise the recovered failure. That
outcome is persisted. Raising an ordinary exception does not ask the framework to retry
the original operation; it records a failed outcome.

For example, suppose a report service supports idempotent submission by a stable request
ID. The application provides `report_service` / `ReportService` with three synchronous
operations: `is_submitted` / `isSubmitted` checks for an existing request,
`wait_for_result` / `waitForResult` waits for its completion and returns the report URL
(or throws its failure), and `submit_and_wait` / `submitAndWait` submits idempotently and
waits for the same outcome. Inside an Action, register a reconciler as follows:

{{< tabs "Reconcile a Report Request" >}}
{{< tab "Python" >}}
```python
request_id = InputEvent.from_event(event).input


def reconcile_report() -> str:
    if report_service.is_submitted(request_id):
        return report_service.wait_for_result(request_id)
    return report_service.submit_and_wait(request_id)


report_url = ctx.durable_execute(
    report_service.submit_and_wait,
    request_id,
    durable_id=f"report:{request_id}",
    reconciler=reconcile_report,
)
ctx.send_event(OutputEvent(output=report_url))
```
{{< /tab >}}
{{< tab "Java" >}}
```java
String requestId = (String) InputEvent.fromEvent(event).getInput();
DurableCallable<String> call = new DurableCallable<>() {
    @Override
    public String getId() {
        return "report:" + requestId;
    }

    @Override
    public Class<String> getResultClass() {
        return String.class;
    }

    @Override
    public String call() throws Exception {
        return ReportService.submitAndWait(requestId);
    }

    @Override
    public Callable<String> reconciler() {
        return () -> {
            if (ReportService.isSubmitted(requestId)) {
                return ReportService.waitForResult(requestId);
            }
            return ReportService.submitAndWait(requestId);
        };
    }
};
String reportUrl = ctx.durableExecute(call);
ctx.sendEvent(new OutputEvent(reportUrl));
```
{{< /tab >}}
{{< /tabs >}}

The same request ID must be reused after recovery. The service's idempotency guarantee
also covers a retry racing with an earlier submission. If the service cannot establish
a final outcome, the reconciler must implement the appropriate waiting or retry policy.

#### Durable Call Requirements

- Recovery must reach the same logical calls in the same order, with the same inputs.
  Keep explicit durable IDs stable and distinguish operations or inputs that must not
  reuse each other's result. An explicit ID does not make a reordered workflow safe.
- Python derives an identity from the function and arguments when `durable_id` is omitted.
  Supply a stable ID when your application owns one, as in the examples above.
- The Action's control flow must be reproducible; the external function does not have to
  produce the same result every time. Replaying the persisted result is what allows a
  model call or external query to be reused.
- Results must support the runtime's serialization: Java uses JSON and the declared result
  class; Python uses pickle-compatible results. Keep inputs serializable and stable.
- Read and write Sensory/Short-term Memory and send Events in the Action body, outside
  the durable function and reconciler. Obtain Resources and Long-term Memory sets there
  too; operations on an already obtained `MemorySet` may be performed in a durable call.

### Durable Async Execution

Durable async execution combines [async calls](#async-execution) with
[durable recovery](#durable-execution). Use it when you want to await external work and
reuse its persisted outcome after a failure. The context and Memory rules above still apply.

Reuse the profile client and Java `ProfileLookup` from [A Durable Call](#a-durable-call):

{{< tabs "Durable Async Call" >}}
{{< tab "Python" >}}
```python
class ProfileAgent(Agent):
    @action(EventType.InputEvent)
    @staticmethod
    async def process_input(event: Event, ctx: RunnerContext) -> None:
        user_id = InputEvent.from_event(event).input
        future = ctx.durable_execute_async(
            profile_service.get_profile,
            user_id,
            durable_id=f"profile:{user_id}",
        )
        profile = await future
        ctx.send_event(OutputEvent(output=profile))
```
{{< /tab >}}
{{< tab "Java" >}}
```java
public class ProfileAgent extends Agent {
    @Action(EventType.InputEvent)
    public static void processInput(Event event, RunnerContext ctx) throws Exception {
        String userId = (String) InputEvent.fromEvent(event).getInput();
        DurableFuture<String> future = ctx.durableExecuteAsync(new ProfileLookup(userId));
        String profile = future.await();
        ctx.sendEvent(new OutputEvent(profile));
    }
}
```
{{< /tab >}}
{{< /tabs >}}

As with ordinary async calls, creating a future does not start work; await it directly
or include it in an awaited `ctx.gather(...)` batch. A future that is never awaited does
not execute or save a result. A batch may mix ordinary and durable calls; only the durable
calls persist their outcomes for recovery. The optional Python `reconciler` argument and
Java `reconciler()` method work with durable async calls too.

### Execution Requirements

#### JDK and Scheduling Requirements

There are two forms of concurrency: running several calls within one Action using
`gather`, and letting other Actions progress while one Action waits. Concurrent batches
are supported in Java and Python on all supported JDK versions, within the configured
worker and batch limits. Concurrency between Actions has additional requirements:

- **JDK 21+:** add `--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED` to the JVM
  options, including `env.java.opts.all` on a cluster, to enable continuation-based execution.
  Plans with Python Actions need it too, not only plans whose Actions are all Java.
  Queued Java async calls are then prioritized by the order their input records started
  processing, then by the order their Actions were first dispatched, so a record's follow-up
  calls can run before later records' first calls. Running calls are not preempted.
- **Java on JDK 11–20:** the experimental parallel execution engine is enabled by default
  for plans whose Actions are all Java. Setting `parallel-execution.enabled` to `false`,
  or including Python Actions, disables it. A Java single-call await then runs synchronously;
  use `gather` for concurrent calls within an Action. Plans using Python Resources also need
  the cross-language Flink versions below.
- **Python Actions:** use `async def` and the framework's futures and `gather` APIs.

See [Runtime and async execution]({{< ref "docs/operations/configuration#runtime-and-async-execution" >}})
for worker-pool and concurrency limits. These scheduling requirements apply to both ordinary
and durable async calls. Saving durable call outcomes for recovery additionally requires
an Action State Store.

#### Cross-language Async Requirements

Async calls between Java and Python require Flink **1.20.5+**, **2.0.2+**, **2.1.3+**,
**2.2.1+**, or **2.3+**, within the respective release line. This includes built-in chat
and context-retrieval Actions that use a Resource implemented in the other language.

A call may cross into the other language and call back once into its starting language.
For example, a Python Action can call a Java chat model setup that uses a Python connection.
The connection then returns its result through the existing call chain.

| Call chain within one async operation | Usage |
| --- | --- |
| Python Action → Java Resource | Supported. |
| Java Action → Python Resource | Supported. |
| Python Action → Java chat model setup → Python connection | Supported: one callback into the starting language. |
| Java Action → Python chat model setup → Java connection | Supported: one callback into the starting language. |
| Python → Java → Python → Java, or Java → Python → Java → Python | Avoid: further nested calls can deadlock. |

The last row describes a new call made before the earlier calls return, not a normal
return path. For example, a Python connection called by a Java setup should not make
another nested Resource call into Java. Keep the remaining work in that connection's
language, or return to the Action and have it send an Event to trigger a later step. See
[Choose APIs and Languages]({{< ref "docs/development/create-agent/choose-apis-and-languages" >}})
for dependency and payload requirements.

## Appendix

### Trigger Condition Reference

#### Custom Event-Type Names

A custom Event type may be a bare name such as `order.created` or `order-created`. Each
dot-separated segment must start with an ASCII letter or underscore and may then contain
ASCII letters, digits, underscores, or hyphens.

A condition containing only a name, such as `urgent`, matches that Event type. To check
an attribute instead, write a Boolean expression such as `attributes.urgent == true`.

Quote a name that contains other punctuation or would otherwise be parsed as an expression,
for example `'order:created'`, `'true'`, or `'EventType.custom'`. The quotes are part of the
condition string: use `@action("'order:created'")` in Python or `@Action("'order:created'")`
in Java. A quoted name matches the literal Event-type string. Quoted names must be non-empty
and cannot contain whitespace, quotes, backslashes, or control characters.

#### Expression Variables

Java and Python Actions support the same condition expressions. Expressions can access:

| Variable | Value |
| --- | --- |
| `type` | The Event type string |
| `id` | The Event ID as a string |
| `EventType` | The built-in Event-type constants |
| `attributes` | The Event's top-level attribute map |

For example, `type == EventType.InputEvent` checks whether the Event is an `InputEvent`.
You can combine this comparison with attribute checks using `&&`.

#### Accessing Attributes

- Access an attribute as `attributes.score` or by its bare name, `score`.
- Use `attributes["type"]` or `attributes["id"]` for attributes whose names overlap with
  framework variables.
- Use a literal index for a key containing dots, such as `attributes["a.b.c"]`. Check its
  presence with `"a.b.c" in attributes`.

#### Missing Attributes and Null {#missing-attributes-and-value-types}

A missing attribute and a present attribute with a `null` value are different.
`has(attributes.score)` checks whether the attribute exists; it does not exclude `null`.
To test an optional, nullable score, use:

```text
has(attributes.score) && attributes.score != null && attributes.score > 80
```

The guard lets Events without a score fail to match normally, without trying to read a
missing value.

#### Condition Evaluation Failures

Reading a missing attribute without a guard or applying an operation to an incompatible
value can fail while evaluating a condition for an Event. For failure handling options, see
[`action.trigger-condition.evaluate-failure-strategy`]({{< ref "docs/operations/configuration#condition-handling" >}})
in Configuration.

#### Limitations

- `has(...)` is the only supported CEL macro. The comprehension macros `exists`,
  `exists_one`, `all`, `filter`, and `map` are not supported.
- Select a top-level attribute by a fixed name. Dynamic root access such as `attributes[key]`
  and operations on the whole `attributes` map are unsupported. Dynamic access within a
  selected attribute, such as `attributes.details[key]`, is supported.
