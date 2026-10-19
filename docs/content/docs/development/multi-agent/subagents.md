---
title: "Sub-Agents"
weight: 1
type: docs
aliases:
  - /docs/development/subagents/
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

A sub-agent is an agent that another agent delegates a task to. The parent hands it
input, the sub-agent does the work, and the parent receives the outcome as a
`SubagentResult`. Use one when the delegated work is multi-step or long-running and
should run isolated from the parent.

The work runs either inside the job or outside it. An *internal* sub-agent is a
child `Agent`, including a `ReActAgent`, compiled into the parent's plan. The
child's own actions and events run in the same job. An *external* sub-agent has no
plan of its own. The work happens in your own code or in a remote agent service,
outside the parent's event loop. It takes one of two forms, *deferred* or *async
(pub/sub)*.

Either way, the call is initiated by the model or by you. In *model-driven
delegation* you list the sub-agent on a chat model setup and the model chooses when
to delegate. In *caller-driven delegation* one of your actions submits the task
itself.

Every sub-agent call runs through
[durable execution]({{< ref "docs/development/create-agent/actions#durable-execution" >}}).

## Declare a sub-agent

A sub-agent enters the parent's plan as a resource registered under
`ResourceType.AGENT`. The name you register it under is the name that invocations and
metrics use. What you register depends on the form. An internal sub-agent registers
a child `Agent`. An external sub-agent registers a descriptor that names your setup
class. A YAML file declares either form, as
[Declaring sub-agents]({{< ref "docs/development/create-agent/yaml#declaring-sub-agents" >}})
describes.

### Internal: compile a child agent {#declare-an-internal-sub-agent}

Nothing in a child's own code marks it as a sub-agent. The child is an ordinary
agent with its own resources, actions, and event loop, and it runs standalone
exactly as written. A parent registers it as an `AGENT` resource, and that
registration is the whole of the difference. It gives the child a name in the
parent's plan. It can also give the child an input schema, so a chat model can call
it. And it supplies the caller that submits input and awaits the child's outputs.

Any `Agent` can serve as the child. The two common kinds are a workflow agent and a
`ReActAgent`. Either way, the child's plan is compiled into the parent's when the
parent's plan is built.

#### A workflow agent as the child

Any workflow agent can serve as the child. The one used through this page is built
the way
[Request and Response Example]({{< ref "docs/development/calling-llms/chat#usage-example" >}})
builds an agent that owns a chat model, and this page calls it `ReviewAgent`. It has
a connection resource, a model setup, an action that turns an `InputEvent` into a
`ChatRequestEvent`, and an action that turns the `ChatResponseEvent` into
`OutputEvent`s.

The one part a sub-agent changes is how that first action reads its input. A
caller-driven call passes the object the caller submitted. A model-driven call
passes the arguments map built from the input schema.

{{< tabs "Read the Input in a Child Action" >}}

{{< tab "Python" >}}
```python
# Inside ReviewAgent.


@action(EventType.InputEvent)
@staticmethod
def ask(event: Event, ctx: RunnerContext) -> None:
    task = InputEvent.from_event(event).input
    # A model-driven call passes the arguments map; a caller-driven call
    # passes the submitted object.
    diff = task["code"] if isinstance(task, dict) else str(task)
    ctx.send_event(
        ChatRequestEvent(
            model="ollama_chat_model",
            messages=[ChatMessage.user(f"Review this diff:\n{diff}")],
        )
    )
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Inside ReviewAgent.

@Action(EventType.InputEvent)
public static void ask(Event event, RunnerContext ctx) throws Exception {
    Object task = InputEvent.fromEvent(event).getInput();
    // A model-driven call passes the arguments map; a caller-driven call
    // passes the submitted object.
    String diff =
            task instanceof Map
                    ? String.valueOf(((Map<?, ?>) task).get("code"))
                    : String.valueOf(task);
    ctx.sendEvent(
            new ChatRequestEvent(
                    "ollamaChatModel",
                    List.of(new ChatMessage(MessageRole.USER, "Review this diff:\n" + diff))));
}
```
{{< /tab >}}

{{< /tabs >}}

While a call runs, the child behaves like a scoped agent of its own:

- Its `OutputEvent`s are accumulated, in order, into the call's result instead of
  landing on the parent's context. See
  [the result](#the-result) for the shape.
- It resolves resources against its own plan. Register the resources a child uses,
  such as its chat model connection, on the child itself. In Python a resource
  registered solely on the parent is not visible to the child.
- Its sensory and short-term memory start empty for each call, including calls
  continuing a session, and are shared by the child's actions within that call.
  Long-term memory, when configured, is shared with the caller.
- It may register and await sub-agents of its own, so nesting is unbounded in
  depth.

Registering the child takes one of two forms, and which one you need follows from who
calls it, the model or one of your actions. A model can delegate only to a sub-agent
it can describe and build arguments for, so a child exposed to a model needs the
descriptor form. A child that only your actions call can register as-is, and that
registration wraps it in a descriptor carrying nothing else.

Which parameters the descriptor must carry depends on the same thing.

| Parameter | Model-driven calls | Caller-driven calls |
|---|---|---|
| `agent` | Required | Required |
| `description` | Optional, recommended | Optional |
| `input_type` or `input_schema` | Exactly one required | Optional |

{{< tabs "Register an Internal Sub-agent" >}}

{{< tab "Python" >}}
```python
from pydantic import BaseModel

from flink_agents.api.resource import ResourceType
from flink_agents.api.subagent import InternalSubagentResourceDescriptorBuilder

class ReviewRequest(BaseModel):
    code: str

parent.add_resource(
    "reviewer",
    ResourceType.AGENT,
    InternalSubagentResourceDescriptorBuilder()
        .agent(ReviewAgent())
        .description("Review code for correctness and potential defects")
        .input_type(ReviewRequest)
        .build(),
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.InternalSubagentResourceDescriptorBuilder;

parent.addResource(
    "reviewer",
    ResourceType.AGENT,
    new InternalSubagentResourceDescriptorBuilder()
        .agent(new ReviewAgent())
        .description("Review code for correctness and potential defects")
        .inputType(ReviewRequest.class)
        .build());
```
{{< /tab >}}

{{< /tabs >}}

`ReviewRequest` describes the arguments the child accepts, and the Java form is a bean.
Where no type fits, a JSON schema string declares the same arguments:

{{< tabs "Declare the Input as a Schema" >}}

{{< tab "Python" >}}
```python
parent.add_resource(
    "reviewer",
    ResourceType.AGENT,
    InternalSubagentResourceDescriptorBuilder()
        .agent(ReviewAgent())
        .description("Review code for correctness and potential defects")
        .input_schema(
            '{"type":"object","properties":{"code":{"type":"string"}},'
            '"required":["code"]}'
        )
        .build(),
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
parent.addResource(
    "reviewer",
    ResourceType.AGENT,
    new InternalSubagentResourceDescriptorBuilder()
        .agent(new ReviewAgent())
        .description("Review code for correctness and potential defects")
        .inputSchema(
                "{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"}},"
                        + "\"required\":[\"code\"]}")
        .build());
```
{{< /tab >}}

{{< /tabs >}}

Declare either an input type or an input schema, not both. A declaration that carries
both is rejected when the descriptor is built. In Python the type must be a pydantic
model, and a type that is not is rejected there too.

An input type is a shorthand for a schema. The framework derives the schema from the
type and registers that, so use a type when you already have one and a schema when
you do not. Nothing is ever converted into an instance of the type. The sub-agent
receives the arguments as a map keyed by the schema's field names.

For calls an action makes itself, the child registers as-is:

{{< tabs "Register the Child Agent Directly" >}}

{{< tab "Python" >}}
```python
parent.add_resource("reviewer", ResourceType.AGENT, ReviewAgent())
```
{{< /tab >}}

{{< tab "Java" >}}
```java
parent.addResource("reviewer", ResourceType.AGENT, new ReviewAgent());
```
{{< /tab >}}

{{< /tabs >}}

This form carries no description and no input declaration, so a chat model rejects
the sub-agent at setup time rather than calling it with no arguments.

A YAML file names the child instead of instantiating it. Its entry carries an
`agent` field holding the name of another Agent in the same document, along with the
description and the input schema.

#### A ReActAgent as the child

A ReActAgent runs as a child like any other agent. It keeps its own event loop,
resources and conversation, and it is registered through the same builder. It uses
only its own prompt and output schema, even when the parent is also a ReActAgent.

What differs is the input schema. It has to agree with the prompt you gave the child.
The schema names the arguments, and the prompt turns them into the child's user
message.

Without a prompt, the child accepts one string, so declare a single `input` field. A
string input from an action becomes the user message as-is, and so does the `input`
value of an arguments map from a model. Any other map is rejected, because there is
no prompt to format it.

{{< tabs "Use a ReActAgent Without a Prompt" >}}

{{< tab "Python" >}}
```python
reviewer = ReActAgent(
    chat_model=ResourceDescriptor(
        clazz=ResourceName.ChatModel.OLLAMA_SETUP,
        connection="reviewer_connection",
        model="qwen3:8b",
    ),
)
reviewer.add_resource(
    "reviewer_connection",
    ResourceType.CHAT_MODEL_CONNECTION,
    ResourceDescriptor(clazz=ResourceName.ChatModel.OLLAMA_CONNECTION),
)

parent.add_resource(
    "reviewer",
    ResourceType.AGENT,
    InternalSubagentResourceDescriptorBuilder()
        .agent(reviewer)
        .description("Reviews a code diff and reports problems.")
        .input_schema(
            '{"type":"object","properties":{"input":{"type":"string"}},'
            '"required":["input"]}'
        )
        .build(),
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
ReActAgent reviewer =
        new ReActAgent(
                ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                        .addInitialArgument("connection", "reviewerConnection")
                        .addInitialArgument("model", "qwen3:8b")
                        .build(),
                null,
                null);
reviewer.addResource(
        "reviewerConnection",
        ResourceType.CHAT_MODEL_CONNECTION,
        ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_CONNECTION)
                .addInitialArgument("endpoint", "http://localhost:11434")
                .build());

parent.addResource(
        "reviewer",
        ResourceType.AGENT,
        new InternalSubagentResourceDescriptorBuilder()
                .agent(reviewer)
                .description("Reviews a code diff and reports problems.")
                .inputSchema(
                        "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}},"
                                + "\"required\":[\"input\"]}")
                .build());
```
{{< /tab >}}

{{< /tabs >}}

With a prompt, each schema field fills the placeholder of the same name. See
[Prompt]({{< ref "docs/development/react_agent#prompt" >}}). A string input from an
action fills a placeholder named `input`. Declare a field for every placeholder the
prompt uses. Both examples reuse the connection the previous example registers on the
child:

{{< tabs "Use a ReActAgent With a Prompt" >}}

{{< tab "Python" >}}
```python
chat_model_descriptor = ResourceDescriptor(
    clazz=ResourceName.ChatModel.OLLAMA_SETUP,
    connection="reviewer_connection",
    model="qwen3:8b",
)

reviewer = ReActAgent(
    chat_model=chat_model_descriptor,
    prompt=Prompt.from_text(
        "Review the following code diff and report any problems:\n\n{diff}"
    ),
)

parent.add_resource(
    "reviewer",
    ResourceType.AGENT,
    InternalSubagentResourceDescriptorBuilder()
        .agent(reviewer)
        .description("Reviews a code diff and reports problems.")
        # "diff" matches the {diff} placeholder of the child's prompt.
        .input_schema(
            '{"type":"object","properties":{"diff":{"type":"string"}},'
            '"required":["diff"]}'
        )
        .build(),
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
ResourceDescriptor chatModelDescriptor =
        ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "reviewerConnection")
                .addInitialArgument("model", "qwen3:8b")
                .build();

ReActAgent reviewer =
        new ReActAgent(
                chatModelDescriptor,
                Prompt.fromText(
                        "Review the following code diff and report any problems:\n\n{diff}"),
                null);

parent.addResource(
        "reviewer",
        ResourceType.AGENT,
        new InternalSubagentResourceDescriptorBuilder()
                .agent(reviewer)
                .description("Reviews a code diff and reports problems.")
                // "diff" matches the {diff} placeholder of the child's prompt.
                .inputSchema(
                        "{\"type\":\"object\",\"properties\":{\"diff\":{\"type\":\"string\"}},"
                                + "\"required\":[\"diff\"]}")
                .build());
```
{{< /tab >}}

{{< /tabs >}}

The framework does not check the schema against the prompt. A field the prompt does
not use is ignored, and a placeholder no field fills is left in the prompt as literal
text. So keep the two in step when you change either.

### External: write a setup class {#declare-an-external-sub-agent}

An external sub-agent has no plan of its own, and it is not compiled into the
parent's. The work happens in your own code or in a remote agent service. The parent
only submits to it and waits for the outcome. The framework cannot perform that work
for you, because it does not know your protocol. So you write the part that performs
one invocation, and the framework supplies everything around it. It runs your code
under durable execution, and after a failover it replays the recorded result instead
of calling your code again.

What you write is a setup class. Its only job is to carry out one invocation, so it
declares no actions and no events of its own. It extends a framework base class and
implements the methods that class declares.

Registration names the setup class in a descriptor, and both languages construct the
setup from the descriptor at runtime. Registering a setup instance instead is
rejected.

The invocation itself is carried out in one of two modes, *deferred* and *async
(pub/sub)*. Which one you write follows from your remote interface.

| | Deferred | Async (pub/sub) |
|---|---|---|
| Work starts | When the caller first awaits the result | When the caller submits |
| You implement | `prepare` | `call_submit_request`, `call_query_status`, `call_fetch_result` |
| Choose it when | One request returns the result | The remote hands back a run you query later |

#### Deferred execution

A deferred setup sends nothing when the caller submits. It only registers the
invocation. The request goes out when the caller first awaits the result. So a call
that is cancelled instead never issues its request.

What you write is the interaction itself, in the `prepare` method of your setup class.
`prepare` runs once per invocation and returns the durable call that carries it. The
example is one blocking request, and whatever else your call does is yours to decide,
because the framework does not know your protocol.

The framework assigns `session_id` and `call_id` before it calls `prepare`, and they
are the only values that a replay reproduces. The durable id you build from them is
the key that recovery matches a recorded result against. When the key matches, your
call is not run again. So build the key from those two values alone. Anything else in
it, a timestamp for instance, makes a replay look up a different key and run the work
a second time. Passing the same values to your own remote lets it recognize a
re-issued request as the same one.

The reconciler is optional. Omitting it leaves one crash window uncovered, and
[Durability and failover](#durability-and-failover) describes that window. Supply one
when the work cannot be repeated.

{{< tabs "Supply a Reconciler" >}}

{{< tab "Python" >}}
```python
def prepare(
    self, ctx: RunnerContext, prompt: Any, session_id: str, call_id: str
) -> PreparedTriple:
    key = f"{session_id}#{call_id}"

    def call() -> SubagentResult:
        return SubagentResult.ok(post_review(self.endpoint, prompt, key))

    # Consulted only during recovery, when this call is revisited and no result was
    # recorded for it. It takes no arguments. Return the recovered outcome, because
    # an exception here is a system-level failure that fails the action instead of
    # reaching the caller.
    def reconcile() -> SubagentResult:
        # fetch_review_by_key is your own lookup; not part of Flink Agents.
        found = fetch_review_by_key(self.endpoint, key)
        if found is None:
            return SubagentResult.error("the review service has no record of this call")
        return SubagentResult.ok(found)

    return (key, call, reconcile)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Inside the DurableCallable that prepare returns, alongside getId() and call().

// Consulted only during recovery, when this call is revisited and no result was
// recorded for it. Return the recovered outcome, because an exception here is a
// system-level failure that fails the action instead of reaching the caller.
@Override
public Callable<SubagentResult> reconciler() {
    return () -> {
        // fetchReviewByKey is your own lookup; not part of Flink Agents.
        String found = fetchReviewByKey(endpoint, key);
        if (found == null) {
            return SubagentResult.error("the review service has no record of this call");
        }
        return SubagentResult.ok(found);
    };
}
```
{{< /tab >}}

{{< /tabs >}}

{{< tabs "Deferred Sub-agent Setup" >}}

{{< tab "Python" >}}
```python
from typing import Any

from flink_agents.api.runner_context import RunnerContext
from flink_agents.api.subagent import SubagentResult
from flink_agents.runtime.deferred_subagent import (
    DeferredSubagentSetup,
    PreparedTriple,
)


def post_review(endpoint: str, prompt: Any, key: str) -> str:
    """Your own call to the review service; not part of Flink Agents.

    Keying the work lets the service treat a re-issued request as the same one.
    """
    ...


class RemoteReviewSetup(DeferredSubagentSetup):
    endpoint: str = ""

    def prepare(
        self, ctx: RunnerContext, prompt: Any, session_id: str, call_id: str
    ) -> PreparedTriple:
        key = f"{session_id}#{call_id}"

        def call() -> SubagentResult:
            try:
                return SubagentResult.ok(post_review(self.endpoint, prompt, key))
            except Exception as e:
                # An expected failure is a value; only a system fault should escape.
                return SubagentResult.error(e)

        # The first value is the key recovery matches a recorded result against, so
        # it is built from the two ids alone. The third is the reconciler.
        return (key, call, None)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.runtime.subagent.BaseDeferredSubagentSetup;

/**
 * Your own call to the review service; not part of Flink Agents. Keying the work lets
 * the service treat a re-issued request as the same one.
 */
static String postReview(String endpoint, Object prompt, String key) {
    ...
}

public class RemoteReviewSetup extends BaseDeferredSubagentSetup {

    private final String endpoint;

    public RemoteReviewSetup(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.endpoint = descriptor.getArgument("endpoint");
    }

    @Override
    protected DurableCallable<SubagentResult> prepare(
            RunnerContext ctx, Object prompt, String sessionId, String callId) {
        // Built from the two ids alone, so a replay reproduces it.
        String key = sessionId + "#" + callId;
        return new DurableCallable<SubagentResult>() {
            @Override
            public String getId() {
                // The key recovery matches a recorded result against.
                return key;
            }

            @Override
            public Class<SubagentResult> getResultClass() {
                return SubagentResult.class;
            }

            @Override
            public SubagentResult call() {
                try {
                    return SubagentResult.ok(postReview(endpoint, prompt, key));
                } catch (Exception e) {
                    // An expected failure is a value; only a system fault should escape.
                    return SubagentResult.error(e);
                }
            }
        };
    }
}
```
{{< /tab >}}

{{< /tabs >}}

Register the setup under the name invocations will use. Every descriptor argument
beyond the built-in ones is configuration for the setup to read back. In Python it
reads them as model fields, and in Java through the public
`(ResourceDescriptor, ResourceContext)` constructor. The `endpoint` above is such
configuration, not a built-in transport.

{{< tabs "Register an External Sub-agent" >}}

{{< tab "Python" >}}
```python
parent.add_resource(
    "remote_reviewer",
    ResourceType.AGENT,
    ResourceDescriptor(
        clazz="my_app.agents.RemoteReviewSetup",
        endpoint="https://example.com/review",
        description="Review code using the remote service",
        input_schema=(
            '{"type":"object","properties":{"diff":{"type":"string"}},'
            '"required":["diff"]}'
        ),
    ),
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
parent.addResource(
    "remote_reviewer",
    ResourceType.AGENT,
    ResourceDescriptor.Builder.newBuilder(RemoteReviewSetup.class.getName())
        .addInitialArgument("endpoint", "https://example.com/review")
        .addInitialArgument("description", "Review code using the remote service")
        .addInitialArgument(
                "input_schema",
                "{\"type\":\"object\",\"properties\":{\"diff\":{\"type\":\"string\"}},"
                        + "\"required\":[\"diff\"]}")
        .build());
```
{{< /tab >}}

{{< tab "YAML" >}}
```yaml
agents:
  - name: orchestrator
    subagents:
      - name: remote_reviewer
        clazz: my_app.agents.RemoteReviewSetup
        endpoint: https://example.com/review
        description: Review code using the remote service
        input_schema: '{"type":"object","properties":{"diff":{"type":"string"}},"required":["diff"]}'
```
{{< /tab >}}

{{< /tabs >}}

The YAML form of that registration is the `subagents` list of an agent entry, as in
the YAML tab above. That list can also sit at the shared document level, outside
`agents:`, so that several agents in one file reuse it. A chat model setup declared in
the same file also takes a `subagents` argument, but it is a list of registered names
rather than a list of entries. See
[delegate from a chat model](#delegate-from-a-chat-model).

Two constraints apply to the entry:

- No class alias is defined for `AGENT` resources, so `clazz` is always a
  fully-qualified class path.
- The entry's language must match the loader. A Python YAML file names a Python
  class and may not set `type: java`. A Java YAML file must set `type: java`.

#### Async (pub/sub) execution

An async sub-agent talks to a service that runs the task for you. The service accepts
the work and answers at once, and you query it later for the run's status and
result.

An async setup starts the remote run when the caller submits, through one durable
POST. Waiting for the result then polls the run's status until it reaches a terminal
state, and fetches it.

The base class owns that submit-poll-fetch composition, along with the durable
execution and the crash-window recovery. You supply the three transport calls it is
built from:

- `callSubmitRequest` / `call_submit_request` starts the run.
- `callQueryStatus` / `call_query_status` returns a `RunStatus`, one of
  `notStarted()`, `running()`, `completed()`, or `failed(error)`. It must be a
  read-only probe with no side effects. Report an expected failure as a `FAILED`
  status.
- `callFetchResult` / `call_fetch_result` returns the `SubagentResult`. It must be
  an idempotent read, because a replay may call it again. The status snapshot never
  carries the payload, and a failed run is reported without fetching at all.

{{< tabs "Async Sub-agent Setup" >}}

{{< tab "Python" >}}
```python
from typing import Any

from flink_agents.api.subagent import SubagentResult
from flink_agents.runtime.async_subagent import BaseAsyncSubagentSetup, RunStatus

# client(...) and task_id stand for your own transport client and run-id
# scheme; neither is part of Flink Agents.


class RemoteRunSetup(BaseAsyncSubagentSetup):
    endpoint: str = ""

    def call_submit_request(self, session_id: str, call_id: str, prompt: Any) -> None:
        client(self.endpoint).submit(prompt, task_id=f"{session_id}#{call_id}")

    def call_query_status(self, session_id: str, call_id: str) -> RunStatus:
        probe = client(self.endpoint).status(task_id=f"{session_id}#{call_id}")
        if probe is None:
            return RunStatus.not_started()
        if probe.succeeded:
            return RunStatus.completed()
        if probe.failed:
            return RunStatus.failed(probe.error)
        return RunStatus.running()

    def call_fetch_result(self, session_id: str, call_id: str) -> SubagentResult:
        return SubagentResult.ok(
            client(self.endpoint).fetch(task_id=f"{session_id}#{call_id}")
        )

    # Optional, and a no-op by default, so a cancelled call leaves the remote run
    # going. A replay may propagate the same cancellation again, so your remote has
    # to accept it twice.
    def call_cancel_request(self, session_id: str, call_id: str) -> None:
        client(self.endpoint).cancel(task_id=f"{session_id}#{call_id}")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.runtime.subagent.BaseAsyncSubagentSetup;

// client() and taskId(...) stand for your own transport client and run-id
// scheme; neither is part of Flink Agents.
public class RemoteRunSetup extends BaseAsyncSubagentSetup {

    private final String endpoint;

    public RemoteRunSetup(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.endpoint = descriptor.getArgument("endpoint");
    }

    @Override
    protected void callSubmitRequest(String sessionId, String callId, Object prompt)
            throws Exception {
        client().submit(String.valueOf(prompt), taskId(sessionId, callId));
    }

    @Override
    protected RunStatus callQueryStatus(String sessionId, String callId) {
        // TaskStatus is your own transport type, not a framework type.
        TaskStatus probe = client().status(taskId(sessionId, callId));
        if (probe == null) {
            return RunStatus.notStarted();
        }
        if (probe.succeeded()) {
            return RunStatus.completed();
        }
        if (probe.failed()) {
            return RunStatus.failed(probe.error());
        }
        return RunStatus.running();
    }

    @Override
    protected SubagentResult callFetchResult(String sessionId, String callId) throws Exception {
        return client().fetchResult(taskId(sessionId, callId));
    }

    // Optional, and a no-op by default, so a cancelled call leaves the remote run
    // going. A replay may propagate the same cancellation again, so your remote has
    // to accept it twice.
    @Override
    protected void callCancelRequest(String sessionId, String callId) {
        client().cancel(taskId(sessionId, callId));
    }
}
```
{{< /tab >}}

{{< /tabs >}}

Register the setup like any external sub-agent. The async form adds one descriptor
argument, `status_poll_interval_millis`, and it defaults to 500.

{{< tabs "Register an Async Sub-agent" >}}

{{< tab "Python" >}}
```python
parent.add_resource(
    "remote_runner",
    ResourceType.AGENT,
    ResourceDescriptor(
        clazz="my_app.agents.RemoteRunSetup",
        endpoint="https://example.com/runs",
        description="Run a review task on the remote service",
        input_schema=(
            '{"type":"object","properties":{"diff":{"type":"string"}},'
            '"required":["diff"]}'
        ),
        status_poll_interval_millis=500,
    ),
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
parent.addResource(
    "remote_runner",
    ResourceType.AGENT,
    ResourceDescriptor.Builder.newBuilder(RemoteRunSetup.class.getName())
        .addInitialArgument("endpoint", "https://example.com/runs")
        .addInitialArgument("description", "Run a review task on the remote service")
        .addInitialArgument(
                "input_schema",
                "{\"type\":\"object\",\"properties\":{\"diff\":{\"type\":\"string\"}},"
                        + "\"required\":[\"diff\"]}")
        .addInitialArgument("status_poll_interval_millis", 500L)
        .build());
```
{{< /tab >}}

{{< tab "YAML" >}}
```yaml
agents:
  - name: orchestrator
    subagents:
      - name: remote_runner
        clazz: my_app.agents.RemoteRunSetup
        endpoint: https://example.com/runs
        description: Run a review task on the remote service
        input_schema: '{"type":"object","properties":{"diff":{"type":"string"}},"required":["diff"]}'
        status_poll_interval_millis: 500
```
{{< /tab >}}

{{< /tabs >}}

The base class also recovers the crash window between "POST sent" and "POST durably
recorded". On recovery it probes the run's status first, and it re-posts only when the
probe reports that the run never started. Override `reconcileSubmitRequest` /
`reconcile_submit_request` only if your service needs a different repair.

## Invoke a sub-agent

A sub-agent is invoked in one of two ways, model-driven or caller-driven. The
difference you see is where the result goes.

In model-driven delegation the result returns to the model. The sub-agent is offered
to the model as a tool, so your action sends a `ChatRequestEvent` and the model
answers with a call to the sub-agent. The result goes back to the model as the tool
observation, and the model reasons over it until a final `ChatResponseEvent` reaches
your action.

In caller-driven delegation the result returns to your action. An action that needs
the delegated outcome calls the sub-agent itself, with no model in the loop.

Both ways end in the same call. Your action, or the framework on the model's behalf,
runs `submit(ctx, prompt)` on the registered name. That call returns a
`SubagentFuture`, which is the handle for that one call. Awaiting the handle yields
the call's `SubagentResult`.

### Delegate from a chat model

List the registered name under `subagents` of the parent's chat model:

{{< tabs "Declare Sub-agents on a Chat Model" >}}

{{< tab "Python" >}}
```python
ResourceDescriptor(
    clazz=ResourceName.ChatModel.OLLAMA_SETUP,
    connection="ollama_connection",
    model="qwen3:8b",
    subagents=["reviewer"],
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
    .addInitialArgument("connection", "ollamaConnection")
    .addInitialArgument("model", "qwen3:8b")
    .addInitialArgument("subagents", List.of("reviewer"))
    .build();
```
{{< /tab >}}

{{< /tabs >}}

The model sees `reviewer` as a tool, described by the registration's description and
input schema. A schema is required for model-selected calls. A missing description
falls back to a generic delegation description.

The arguments the model sends become the sub-agent's input, and its outputs return to
the model as the tool observation. The model can then keep reasoning over them. For
an internal sub-agent, the observation is the JSON encoding of the full output list
described in [the result](#the-result). That holds even when the child emits a single
output, so the shape does not depend on how many outputs the child emits.

An external setup class can state the shape of that observation. It declares
`result_type()` in Python or `getResultType()` in Java, and the default reports the
result as it arrived. Declare one to narrow a wider payload, or to convert a payload
that is not already JSON-compatible. An internal sub-agent has no such declaration, so
its observation is always the JSON encoding of its output list. This shapes the
observation only. A caller that awaits the handle gets the result the sub-agent
produced, as [the result](#the-result) describes.

The external sub-agents `remote_reviewer` and `remote_runner` are offered the same
way. Only their registration differs.

### Call from an action

An action can submit input to a registered sub-agent directly and await its
outcome. This path needs no description and no input declaration, so it also works
for a child registered as-is.

{{< tabs "Call a Sub-agent From an Action" >}}

{{< tab "Python" >}}
```python
class DelegatingAgent(Agent):
    @action(EventType.InputEvent)
    @staticmethod
    async def delegate(event: Event, ctx: RunnerContext) -> None:
        reviewer = ctx.get_resource("reviewer", ResourceType.AGENT)
        future = await reviewer.submit(ctx, "review the diff")
        result = await future
        if result.success:
            ctx.send_event(OutputEvent(output=result.result[0]))
        else:
            ctx.send_event(OutputEvent(output=f"failed: {result.error_message}"))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class DelegatingAgent extends Agent {

    @Action(EventType.InputEvent)
    public static void delegate(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup reviewer =
                (SubagentSetup) ctx.getResource("reviewer", ResourceType.AGENT);
        SubagentResult result = reviewer.submit(ctx, "review the diff").await();
        if (result.isSuccess()) {
            String answer = String.valueOf(((List<?>) result.getResult()).get(0));
            ctx.sendEvent(new OutputEvent(answer));
        } else {
            ctx.sendEvent(new OutputEvent("failed: " + result.getErrorMessage()));
        }
    }
}
```
{{< /tab >}}

{{< /tabs >}}

What you pass to `submit` is what the sub-agent receives. An internal child gets it
as its `InputEvent` payload, and a model-driven call passes the arguments map
instead. What comes back is a `SubagentResult`. For the internal sub-agent used here,
its `result` is the list of `OutputEvent` payloads the child emitted. That is why the
example reads `result.result[0]`. An external sub-agent returns whatever its setup
produces.

In Python `submit` is a coroutine, so the call is awaited in two phases and the
action must be `async`. In Java `submit` returns the handle directly and `await()`
yields the result. The same two calls drive `remote_reviewer` and `remote_runner`
unchanged. Only the registration differs.

## Manage sub-agent calls

A `SubagentFuture` is live from `submit` until it is awaited or cancelled. Awaiting
it yields the call's `SubagentResult`. It also carries the call's `session_id` and
`call_id`, so you can read them to continue the session or to match the call against
your own records.

### Fan out to several sub-agents

`combine` groups handles into a `SubagentFutures`, and resolving the batch resolves
every handle in it. `gather` does not accept a `SubagentFuture`, so handles batch this
way instead. The batch prepares every handle before it runs any of them. Preparing an
internal child starts that child, so the children's work can overlap. An external
deferred setup issues its request only when the batch runs it, so those requests do
not overlap. The results come back in the order the handles were added.

{{< tabs "Fan Out to Several Sub-agents" >}}

{{< tab "Python" >}}
```python
reviewer = ctx.get_resource("reviewer", ResourceType.AGENT)

first = await reviewer.submit(ctx, "review the diff")
second = await reviewer.submit(ctx, "review the tests")

outcomes = await first.combine(second)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
SubagentSetup reviewer = (SubagentSetup) ctx.getResource("reviewer", ResourceType.AGENT);

SubagentFuture first = reviewer.submit(ctx, "review the diff");
SubagentFuture second = reviewer.submit(ctx, "review the tests");

List<SubagentResult> outcomes = first.combine(second).awaitAll();
```
{{< /tab >}}

{{< /tabs >}}

### Continue a session

Pass the `session_id` of an earlier handle to continue that conversation. The new
call gets a fresh `call_id` in the same session. Whether a conversation can be
continued across actions is up to the implementation.

{{< tabs "Continue a Session" >}}

{{< tab "Python" >}}
```python
reviewer = ctx.get_resource("reviewer", ResourceType.AGENT)

first = await reviewer.submit(ctx, "review the diff")
first_result = await first

second = await reviewer.submit(ctx, "now the tests", first.session_id)
second_result = await second
```
{{< /tab >}}

{{< tab "Java" >}}
```java
SubagentSetup reviewer = (SubagentSetup) ctx.getResource("reviewer", ResourceType.AGENT);

SubagentFuture first = reviewer.submit(ctx, "review the diff");
SubagentResult firstResult = first.await();

SubagentFuture second = reviewer.submit(ctx, "now the tests", first.getSessionId());
SubagentResult secondResult = second.await();
```
{{< /tab >}}

{{< /tabs >}}

### Cancel and resolve

Resolve every handle you submit. An action that finishes with a handle it neither
awaited nor cancelled fails with `Action <name> finished without resolving the
sub-agent calls it submitted: <ids>`. Cancelling counts as resolving, and awaiting a
cancelled handle throws `CancellationException` in Java and raises `CancelledError` in
Python.

{{< tabs "Cancel a Sub-agent Call" >}}

{{< tab "Python" >}}
```python
from concurrent.futures import CancelledError

reviewer = ctx.get_resource("reviewer", ResourceType.AGENT)

future = await reviewer.submit(ctx, "review the diff")
future.cancel()

try:
    await future
except CancelledError:
    ctx.send_event(OutputEvent(output="cancelled"))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
SubagentSetup reviewer = (SubagentSetup) ctx.getResource("reviewer", ResourceType.AGENT);

SubagentFuture future = reviewer.submit(ctx, "review the diff");
future.cancel();

try {
    future.await();
} catch (CancellationException e) {
    ctx.sendEvent(new OutputEvent("cancelled"));
}
```
{{< /tab >}}

{{< /tabs >}}

`cancel()` on a deferred handle takes effect only before the request is issued.

## Results and failures

### The result

The awaited value of every call is a `SubagentResult`, which carries the outcome in
three fields:

| Field | Java | Carries |
|---|---|---|
| `success` | `isSuccess()` | Whether the call succeeded |
| `result` | `getResult()` | The answer on success. For an internal sub-agent, the list of `OutputEvent` payloads in emission order. For an external one, whatever the setup produces, such as a string or an object |
| `error_message` | `getErrorMessage()` | The reason on failure |

A result a model observes must be JSON-compatible. That means string map keys,
finite numbers, and no cycles. The framework normalizes the result on its way into
the tool observation, and one it cannot normalize fails the delegation.

A failover restores the recorded result, and the two languages restore it
differently. Python persists the payload with cloudpickle, so a replay hands it back
with the type the sub-agent produced. Java persists it as JSON, so `getResult()`
hands back a map after a replay, because recovery re-binds the recorded payload
without your type. `getResult(Class<T>)` converts on the first run and after a
replay alike, so prefer it when you want your own type back.

{{< tabs "Read a SubagentResult" >}}

{{< tab "Python" >}}
```python
if result.success:
    # The payload keeps the type the sub-agent produced, after a replay too.
    ctx.send_event(OutputEvent(output=result.result["verdict"]))
else:
    ctx.send_event(OutputEvent(output=f"failed: {result.error_message}"))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
if (result.isSuccess()) {
    // Review is your own type, the shape this sub-agent produces.
    Review review = result.getResult(Review.class);
    ctx.sendEvent(new OutputEvent(review.verdict));
} else {
    ctx.sendEvent(new OutputEvent("failed: " + result.getErrorMessage()));
}
```
{{< /tab >}}

{{< /tabs >}}

### How failures surface

An expected failure is a value, and an unexpected one is an exception. Return
`SubagentResult.error(...)` for anything the caller or the model should react to. An
exception escaping the durable call is a system-level failure. It fails the action and
triggers recovery.

- A failed result reaches a caller as `success == false` with the reason in
  `error_message`.
- A failed result reaches a model as a tool observation that names the sub-agent and
  the reason, so the model can decide what to do next. An exception while submitting
  or awaiting is reported the same way.
- A failing child action does not fail the job. It surfaces through the caller's
  result. The text in `error_message` is not a contract, so test for the failure
  rather than for the message.
- A result that is not JSON-compatible fails the delegation rather than the job.

## Durability and failover

A sub-agent call always runs through
[durable execution]({{< ref "docs/development/create-agent/actions#durable-execution" >}}).
Its result is persisted and replayed on recovery, so the work is not repeated after
a failover. There is no durability toggle and no sub-agent timeout option.

- Identities are replay-stable. Without explicit ids, the framework assigns
  `session_id` and `call_id` from the executing action and the sub-agent's resource
  name, so a replay of the same call gets the same ids. The ids are opaque, so do not
  parse them. Two sub-agents called from one action never collide, because the
  resource name is part of the namespace.
- A deferred call runs under the durable id you build in `prepare`, so a replay
  re-issues the same logical invocation. A remote keyed on the same values then reuses
  the original work instead of starting a second one. The reconciler is optional.
  Without it, a crash between the call landing and its result being persisted re-runs
  the sub-agent and duplicates its side effects, so supply one whenever the work
  cannot be repeated.
- An async call reuses one identity for both its POST and its poll, so your service
  sees at most one run per call.
- An internal sub-agent's child actions have their own persisted state, so a replay
  reuses the recorded child output instead of running the child again.

## Metrics and Trace Log

Only the delegations a model requests are reported. A sub-agent you drive yourself
from an action emits no sub-agent metrics and no sub-agent trace records. Add
[custom metrics]({{< ref "docs/operations/monitoring#how-to-add-custom-metrics" >}})
if you need to observe that path.

A model-driven delegation is counted under a `subagent` scope keyed by the registered
sub-agent name. It is reported there only and never also as a tool, so the scope's
cardinality is bounded by your plan.

| Metric | Counts |
|---|---|
| `numOfSubagentCallsSucceeded` | Delegations that returned a successful result |
| `numOfSubagentCallsFailed` | Delegations that failed, either while submitting or awaiting, or by returning a failed result |
| `subagentCallLatencyMs` | Time from handing the prompt to the sub-agent until its result is observed |

Full metric paths and types are in
[Execution Metrics]({{< ref "docs/operations/monitoring#execution-metrics" >}}).

The same delegations appear in
[Trace Log]({{< ref "docs/operations/monitoring#trace-log" >}}) as call records with
`entityType: "subagent"`. The default target records events only, so select the
`subagent` scope, or `ALL`, to get them.

## Restrictions

- Recursive registration is rejected when the plan is built, whether the cycle runs
  through a child, through the root agent, or directly back to itself.
- Registering the same child `Agent` instance under two names compiles it once and
  shares the plan.
- `_subagent_` is a reserved prefix. A sub-agent reaches a model as a tool whose name
  carries it, and no tool may be registered under the prefix, including a tool an MCP
  server advertises. A tool and a sub-agent may therefore share a name, because the
  prefix keeps the two namespaces apart. See
  [Tool Use]({{< ref "docs/development/calling-llms/tool_use" >}}).
- A sub-agent declared in one language cannot be driven from the other. Delegation
  resolves the `AGENT` resource in the executing language only.
- Resolving an internal sub-agent call in Java requires JDK 21+ with the Continuation
  API. On an older runtime the call fails fast rather than hanging. Python callers
  and external sub-agents have no JDK requirement.
- When a model emits several sub-agent calls in one turn, and `tool-call.async` is
  enabled with `async.batch.parallelism` above 1, the framework submits every call
  before awaiting any of them. An async sub-agent starts its remote run during that
  submit, so those runs overlap. A deferred or internal call does its work while it
  is awaited, so it does not overlap on this path. Batching handles yourself differs
  for an internal child, as
  [Fan out to several sub-agents](#fan-out-to-several-sub-agents) describes. Results
  still land on their own call ids, in the model's call order. See
  [Configuration]({{< ref "docs/operations/configuration" >}}).
