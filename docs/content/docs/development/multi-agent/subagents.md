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

There are two kinds of sub-agents:

- **Internal**: a child `Agent`, such as a workflow agent or a `ReActAgent`. It runs
  in the same Flink job as the parent, with its own actions and events.
- **External**: custom code or a remote agent service that performs the delegated
  task. You provide a setup class that connects the parent to that implementation.

Both kinds support two ways to delegate a task:

- **Model-driven**: bind the sub-agents to a chat model. The model decides whether
  to delegate, chooses a sub-agent, and
  supplies its input. The framework invokes the sub-agent and returns the outcome
  to the model as a tool result, so the model can use it in its next response.
- **Caller-driven**: call the sub-agent explicitly from one of the parent's actions.
  Your code chooses the sub-agent, submits the input, and awaits the result. The
  result returns to your action, which decides what to do next. Use this when your
  workflow determines when to delegate.

Every sub-agent call runs through
[durable execution]({{< ref "docs/development/create-agent/actions#durable-execution" >}}).

## Declare a sub-agent

Register a sub-agent on the parent under `ResourceType.AGENT` and give it a name.
Use that name when calling the sub-agent from an action or binding it to a chat model.
The following sections show how to register internal and external sub-agents.

Both kinds can also be declared in YAML. See
[Declaring sub-agents]({{< ref "docs/development/create-agent/yaml#declaring-sub-agents" >}})
for YAML examples and [Resources]({{< ref "docs/development/create-agent/resources" >}})
for resource registration and access.

### Internal: use an existing agent {#declare-an-internal-sub-agent}

An internal sub-agent is an ordinary `Agent`, such as a workflow agent or a
`ReActAgent`, with its own resources and actions. Register it on the parent as an
`AGENT` resource so the parent can submit tasks to it and receive its results.

#### A workflow agent as the child

The example below uses a workflow agent named `ReviewAgent` to review a code diff
and return feedback. For how to build an agent that calls a chat model, see
[Using Chat Events]({{< ref "docs/development/calling-llms/chat#usage-example" >}}).

Its input-handling action supports both delegation styles. When called from an
action, it receives the submitted input directly. When called by a model, it
receives an arguments map whose fields are defined by the input schema.

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
            model="review_model",
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
                    "reviewModel",
                    List.of(new ChatMessage(MessageRole.USER, "Review this diff:\n" + diff))));
}
```
{{< /tab >}}

{{< /tabs >}}

When you call an internal sub-agent:

- The parent receives the child's outputs as the call's result, in emission order.
  See [the result](#the-result) for the result structure.
- The child first looks for resources registered on itself, then falls back to
  resources registered on the root agent. You can share a chat model connection
  by registering it on the root, or override it on a child using the same name
  and resource type. This also applies to nested sub-agents.
- The child's sensory and short-term memory start empty for each call, even when
  reusing a session ID. Its actions share this memory during the call. Long-term
  memory, when configured, is shared with the caller.
- The child can also register and call sub-agents of its own.

Choose the registration form based on how the child will be called:

- **Model-driven**: use the builder shown below to register the child with an input
  type or schema. Add a description to help the model decide when to use it.
- **Caller-driven only**: you can register the `Agent` directly. A description and
  input declaration are optional.

When using the builder, the parameters are:

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

`input_type` defines the arguments a model supplies when calling the sub-agent,
using a Pydantic model in Python or a Java bean. You can also use `input_schema`
to describe the same arguments directly with JSON Schema:

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

Choose either `input_type` or `input_schema`. With either form, a model-driven
call passes the arguments to the child as a Python dictionary or a Java `Map`.
For example, declaring `ReviewRequest` above means the child receives
`{"code": "..."}`, not a `ReviewRequest` instance.

If you only call the sub-agent from actions, you can also register the agent directly:

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

In YAML, use the `agent` field to reference a child agent by name. The child can
be defined in the same file or loaded earlier into the same execution environment.
You can also provide a description and an input schema. See
[Internal sub-agents in YAML]({{< ref "docs/development/create-agent/yaml#internal-sub-agents" >}})
for examples and loading order.

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

When the child has a prompt, define an input schema whose fields match the prompt's
placeholders. In the example below, the prompt contains `{diff}`, so the schema
declares a `diff` field. Pass `{"diff": "..."}` when calling this child.

If an action passes a plain string instead of an argument map, the string fills a
placeholder named `{input}`. Use `{input}` in the child's prompt for this form of
input. See [Prompt]({{< ref "docs/development/react_agent#prompt" >}}) for details.

Connection configuration is unchanged from the previous example and is omitted below.

{{< tabs "Use a ReActAgent With a Prompt" >}}

{{< tab "Python" >}}
```python
reviewer = ReActAgent(
    chat_model=ResourceDescriptor(
        clazz=ResourceName.ChatModel.OLLAMA_SETUP,
        connection="reviewer_connection",
        model="qwen3:8b",
    ),
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
ReActAgent reviewer =
        new ReActAgent(
                ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                        .addInitialArgument("connection", "reviewerConnection")
                        .addInitialArgument("model", "qwen3:8b")
                        .build(),
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

An external sub-agent delegates work to your own code or a remote agent service.
To use an external sub-agent, extend `DeferredSubagentSetup`
(`BaseDeferredSubagentSetup` in Java) or `BaseAsyncSubagentSetup`, implement the
methods for calling your code or service, and register it on the parent agent.

Choose between *deferred* and *async (pub/sub)* based on the interface you are calling:

| | Deferred | Async (pub/sub) |
|---|---|---|
| Work starts | When the caller first awaits the result | When the caller submits |
| You implement | `prepare` | `call_submit_request`, `call_query_status`, `call_fetch_result` |
| Choose it when | One request returns the result | The remote hands back a run you query later |

#### Deferred execution

A deferred sub-agent starts its request when the caller first awaits the result.
Submitting the call alone does not send a request. Cancelling it before the first
await therefore prevents the request from being sent.

Implement `prepare` to define the function that sends the request and returns a
`SubagentResult`. In Python, return `(key, call, reconcile)`; in Java, return a
`DurableCallable<SubagentResult>`. The example below performs one request and leaves
out the optional reconciler.

Build the call's key from the supplied `session_id` and `call_id`, as shown below,
so it stays the same during recovery. You can also pass this key to your service
so it can recognize repeated requests for the same work.

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

Register the external sub-agent using a `ResourceDescriptor` that names its setup
class. Both Java and Python require this form; passing a setup instance directly
is not supported. Give the resource the name the parent will use to call it.

Every descriptor argument beyond the built-in ones is configuration for the setup
to read back. In Python it reads them as model fields, and in Java through the public
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

<a id="optional-reconciler"></a>

**Optional reconciler**

If repeating a request could duplicate its effects, add a reconciler that checks
your service for the existing outcome. During recovery, the framework uses this
lookup when no result was recorded for the call. See
[Durability and failover](#durability-and-failover) for how this handles a failure
after the service has completed the work but before the result is persisted.

The following code adds a reconciler to the setup above:

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

#### Async (pub/sub) execution

Use an async sub-agent when a service accepts a task and completes it later.
The call has three steps:

1. **Submit the task.** When the parent submits the call, send a request to start
   the task on the remote service.
2. **Query its status.** When the caller awaits the result, the framework repeatedly
   checks whether the task has completed or failed.
3. **Fetch the result.** Once the task completes, retrieve its result. If the task
   fails, the caller receives an error result without a separate fetch.

Extend `BaseAsyncSubagentSetup` and implement these methods for your service:

| Step | Python | Java |
|---|---|---|
| Submit | `call_submit_request` | `callSubmitRequest` |
| Query status | `call_query_status` | `callQueryStatus` |
| Fetch result | `call_fetch_result` | `callFetchResult` |

Each method receives `session_id` and `call_id` (`sessionId` and `callId` in Java)
to identify the same task. The example below combines them into a task ID used by
the service. The status method returns a `RunStatus`, and the fetch method returns
a `SubagentResult`. Report an expected task failure as a `FAILED` status.

{{< tabs "Async Sub-agent Setup" >}}

{{< tab "Python" >}}
```python
from typing import Any

from flink_agents.api.subagent import SubagentResult
from flink_agents.runtime.async_subagent import BaseAsyncSubagentSetup, RunStatus

# client(...) is your service client; task_id is how your service identifies tasks.
# Neither is part of Flink Agents.


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
```
{{< /tab >}}

{{< tab "Java" >}}
```java
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.runtime.subagent.BaseAsyncSubagentSetup;

// client() is your service client; taskId(...) builds the service's task ID.
// Neither is part of Flink Agents.
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
        // TaskStatus is the status type returned by your service client.
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

<a id="recovery-and-cancellation"></a>

**Recovery and cancellation**

If a failure occurs after the submission request was sent but before its completion
was recorded, recovery first queries the task's status. The framework resubmits the
request only if the service reports that the task has not started. Override
`reconcile_submit_request` (`reconcileSubmitRequest` in Java) if your service needs
a different recovery procedure. See [Durability and failover](#durability-and-failover)
for more details.

Status queries must be read-only. Fetching the result must also be safe to repeat,
since recovery may fetch it again.

To cancel the remote task when the caller cancels the call, implement
`call_cancel_request` (`callCancelRequest` in Java) in your setup class. Without this
optional method, cancelling the call leaves the remote task running. The service
must accept repeated cancellation requests, since recovery may send them again.

{{< tabs "Cancel an Async Sub-agent Task" >}}

{{< tab "Python" >}}
```python
# Add this method to RemoteRunSetup.
def call_cancel_request(self, session_id: str, call_id: str) -> None:
    client(self.endpoint).cancel(task_id=f"{session_id}#{call_id}")
```
{{< /tab >}}

{{< tab "Java" >}}
```java
// Add this method to RemoteRunSetup.
@Override
protected void callCancelRequest(String sessionId, String callId) {
    client().cancel(taskId(sessionId, callId));
}
```
{{< /tab >}}

{{< /tabs >}}

## Invoke a sub-agent

- **Model-driven**: the model chooses and calls a sub-agent, then uses the result
  to continue processing the task.
- **Caller-driven**: an action explicitly calls a sub-agent, awaits its result,
  and decides how to use it.

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

The model's arguments become the sub-agent's input. Its result returns to the model
as a tool observation, which the model can use to continue processing the task.
See [The result](#the-result) for the result format.

The external sub-agents `remote_reviewer` and `remote_runner` are offered the same
way. Only their registration differs.

### Call from an action

An action can call a registered sub-agent directly, without declaring a description
or input schema. Pass the input to `submit(ctx, prompt)` to obtain a `SubagentFuture`,
then await that future to get a `SubagentResult`.

In Python, `submit` is a coroutine: use `await reviewer.submit(...)` to obtain the
future, then `await future` for the result. The action must therefore be `async`.
In Java, `submit` returns the future directly, and `await()` returns its result.

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

An internal child receives the submitted input as its `InputEvent` payload. The
example reads the first output from the internal `reviewer`; see
[The result](#the-result) for how to read internal and external sub-agent results.
Use the same `submit` and await methods to call `remote_reviewer` or `remote_runner`.

## Manage sub-agent calls

A `SubagentFuture` is live from `submit` until it is awaited or cancelled. Awaiting
it yields the call's `SubagentResult`. It also carries the call's `session_id` and
`call_id`, so you can read them to continue the session or to match the call against
your own records.

### Fan out to several sub-agents

Use `combine` to wait for several sub-agent calls together. Results are returned in
the order the calls were added, regardless of completion order. Whether their work
can overlap depends on the sub-agent type:

| Sub-agent type | Execution behavior |
|---|---|
| Internal | Child agents can run concurrently. |
| External deferred | Requests run one at a time; `combine` does not make them concurrent. |
| External async | Tasks start when submitted and can run concurrently if the remote service supports it. |

In Python, use `combine` to await `SubagentFuture` objects together.
`ctx.gather` does not accept these objects, and `asyncio.gather` is not supported
in agent actions.

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

An external sub-agent can continue a conversation if its remote service supports
sessions. Its setup must map `session_id` to a remote session and use that session
for subsequent requests. The conversation context is stored by the remote service.

The following example assumes `session_reviewer` is an external sub-agent registered
with such a setup. Pass the first call's `session_id` to continue the conversation;
the second call gets a new `call_id` while using the same remote session.

{{< tabs "Continue a Session" >}}

{{< tab "Python" >}}
```python
reviewer = ctx.get_resource("session_reviewer", ResourceType.AGENT)

first = await reviewer.submit(ctx, "Review this diff: ...")
first_result = await first

second = await reviewer.submit(
    ctx, "Which issue should I fix first?", first.session_id
)
second_result = await second
```
{{< /tab >}}

{{< tab "Java" >}}
```java
SubagentSetup reviewer =
        (SubagentSetup) ctx.getResource("session_reviewer", ResourceType.AGENT);

SubagentFuture first = reviewer.submit(ctx, "Review this diff: ...");
SubagentResult firstResult = first.await();

SubagentFuture second =
        reviewer.submit(ctx, "Which issue should I fix first?", first.getSessionId());
SubagentResult secondResult = second.await();
```
{{< /tab >}}

{{< /tabs >}}

Internal sub-agents do not retain conversation context between calls, even when
reusing a session ID.

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
| `result` | `getResult()` | The answer on success |
| `error_message` | `getErrorMessage()` | The reason on failure |

**Internal sub-agent results**

An internal sub-agent returns a list of its `OutputEvent` payloads in emission
order, even when it emits only one output. The following example reads the first
output from the `reviewer` shown earlier:

{{< tabs "Read an Internal Sub-agent Result" >}}

{{< tab "Python" >}}
```python
if result.success:
    ctx.send_event(OutputEvent(output=result.result[0]))
else:
    ctx.send_event(OutputEvent(output=f"failed: {result.error_message}"))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
import java.util.List;

if (result.isSuccess()) {
    List<?> outputs = (List<?>) result.getResult();
    ctx.sendEvent(new OutputEvent(outputs.get(0)));
} else {
    ctx.sendEvent(new OutputEvent("failed: " + result.getErrorMessage()));
}
```
{{< /tab >}}

{{< /tabs >}}

**External sub-agent results**

An external sub-agent returns the value its setup supplies, such as a string or an
object. This example assumes the setup returns a Python dictionary with a `verdict`
key, or a Java `Review` bean with a public `verdict` field:

{{< tabs "Read an External Sub-agent Result" >}}

{{< tab "Python" >}}
```python
if result.success:
    ctx.send_event(OutputEvent(output=result.result["verdict"]))
else:
    ctx.send_event(OutputEvent(output=f"failed: {result.error_message}"))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
if (result.isSuccess()) {
    Review review = result.getResult(Review.class);
    ctx.sendEvent(new OutputEvent(review.verdict));
} else {
    ctx.sendEvent(new OutputEvent("failed: " + result.getErrorMessage()));
}
```
{{< /tab >}}

{{< /tabs >}}

In Java, use `getResult(Class<T>)` when reading a result as your own type. It also
works after recovery; see [Result types after recovery](#result-types-after-recovery).

**Results returned to a model**

For a model-driven call, an internal sub-agent's tool observation is the JSON
encoding of its full output list, even when the child emits a single output.

An external setup can declare `result_type()` in Python or `getResultType()` in Java
to shape the tool observation, for example to narrow a wider payload or convert it
to a JSON-compatible form. By default, it uses the result as returned by the setup.
This declaration affects only what the model observes; an action awaiting the call
receives the result the setup produced.

A result a model observes must be JSON-compatible. That means string map keys,
finite numbers, and no cycles. The framework normalizes the result on its way into
the tool observation, and one it cannot normalize fails the delegation.

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
- Ordinary exceptions from a child action are returned as a failed result.
  Cancellation and fatal errors still propagate. The text in `error_message` is
  not a contract, so test for the failure rather than for the message.
- For model-driven calls, a result that cannot be converted to JSON is reported
  as a failed delegation.

## Durability and failover

Sub-agent calls use
[durable execution]({{< ref "docs/development/create-agent/actions#durable-execution" >}}).
With checkpointing and an
[Action State Store]({{< ref "docs/operations/consistency#configure-an-action-state-store" >}}),
recovery reuses persisted results. Work whose result has not been persisted may
run again, so external services need to handle retried requests. See
[Consistency]({{< ref "docs/operations/consistency#recovery-guarantees-and-user-responsibilities" >}})
for replay conditions and limits on repeated effects.

The framework generates stable `session_id` and `call_id` values for recovery.
If you supply your own IDs, keep them unchanged when the same call is retried.

- **Internal**: recovery reuses the child's persisted action state and outputs.
  Work not yet recorded may run again.
- **External deferred**: build the key returned by `prepare` from `session_id` and
  `call_id`. Adding a timestamp or another changing value prevents recovery from
  finding the original result. If the service completed the work but the result
  was not persisted, a reconciler can look up the outcome. Without one, recovery
  repeats the request. Use a reconciler or service-side deduplication when repeating
  the work would cause duplicate effects. See [Optional reconciler](#optional-reconciler).
- **External async**: if submission completed but was not recorded, recovery queries
  the task's status and resubmits only when it reports `NOT_STARTED`. The service
  must reliably identify the existing task using the same IDs. If a submitted task
  can be missing from status queries, the service must also deduplicate repeated
  submissions to avoid starting it twice. Status queries, result fetches, and
  cancellation requests must be safe to repeat. See
  [Recovery and cancellation](#recovery-and-cancellation).

### Result types after recovery

Python persists the result payload of an external sub-agent call with cloudpickle
and restores its Python types. An internal call's result is recorded by the Java
runtime as JSON, so a Python caller reads JSON values from it: map keys are
strings, and nested containers and nulls decode as Python JSON values. Custom
Python objects are not preserved, and the values are the same on the first
execution and after recovery.
Java persists them as JSON, so a custom object returned by `getResult()` can be
restored as a map after recovery. Use `getResult(Class<T>)` to obtain the expected
Java type on both the first execution and after recovery.

## Metrics and Trace Log

The framework reports sub-agent metrics for model-driven calls, grouped by the
registered sub-agent name under the `subagent` scope. These calls are not also
counted as Tool calls.

Calls made directly from actions do not automatically produce sub-agent metrics or
sub-agent Trace Log records. Add
[custom metrics]({{< ref "docs/operations/monitoring#how-to-add-custom-metrics" >}})
if you need to monitor those calls.

| Metric | Counts |
|---|---|
| `numOfSubagentCallsSucceeded` | Delegations that returned a successful result |
| `numOfSubagentCallsFailed` | Delegations that failed, either while submitting or awaiting, or by returning a failed result |
| `subagentCallLatencyMs` | Time from handing the prompt to the sub-agent until its result is observed |

Full metric paths and types are in
[Execution Metrics]({{< ref "docs/operations/monitoring#execution-metrics" >}}).

To include model-driven calls in
[Trace Log]({{< ref "docs/operations/monitoring#trace-log" >}}), configure
[`trace-log.targets`]({{< ref "docs/operations/configuration#trace-log-targets" >}})
with `scope: {subagent: "*"}` or `scope: ALL`. These records have
`entityType: "subagent"`. The default configuration records only Events;
Trace Log selection does not affect metrics collection.

## Restrictions

- Internal sub-agent registrations must not form a cycle. An agent cannot register
  itself as a child, directly or through other agents.
- Tool names must not start with the reserved `_subagent_` prefix. This also applies
  to Tools provided by MCP servers. A Tool and a sub-agent can otherwise use the
  same registered name. See
  [Tool Use]({{< ref "docs/development/calling-llms/tool_use" >}}).
- Create an internal child with the same Java or Python Agent API as its parent.
  An external setup and the action invoking it must use the same language;
  cross-language calls to external setups are not supported.
- Calling an internal sub-agent from Java requires a runtime that releases the
  operator while the child graph runs: JDK 21+ with the Continuation API, or the
  experimental parallel execution engine on JDK 11–20. With neither, resolving the
  call fails before the child starts. See
  [JDK and Scheduling Requirements]({{< ref "docs/development/create-agent/actions#jdk-and-scheduling-requirements" >}}).
  This additional requirement does not apply to Python callers or external
  sub-agents.
- On the parallel execution engine, every Action of an internal child must be a
  Java Action, or the job fails when the operator opens. A caller that waits there
  keeps its worker thread, so concurrent or nested internal calls can exhaust the
  worker pool. See
  [Runtime and async execution]({{< ref "docs/operations/configuration#runtime-and-async-execution" >}}).
- There is no per-call sub-agent timeout setting.
- For multiple model-driven calls, enabling `tool-call.async` with
  `async.batch.parallelism` above 1 allows external async tasks to overlap if the
  remote service supports concurrent execution. Internal and external deferred
  calls still run one at a time on this path. Results are returned in the model's
  call order. For concurrent internal calls from an action, see
  [Fan out to several sub-agents](#fan-out-to-several-sub-agents). See
  [Configuration]({{< ref "docs/operations/configuration" >}}) for these settings.
