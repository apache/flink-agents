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

A sub-agent handles a task delegated by a parent agent. An internal sub-agent runs
an agent workflow in the same job. An external sub-agent calls an agent service
through a `SubagentSetup` implementation.

## Declare an internal sub-agent

Use `InternalSubagentResourceDescriptorBuilder` to attach a description and input
schema to a child agent. The builder returns a normal resource descriptor for `addResource`
or `add_resource`.

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

`ReviewAgent` is your child workflow; the Java `ReviewRequest` is a data class
with fields describing its input. Alternatively, pass a JSON schema string to
`inputSchema(...)` in Java or `input_schema(...)` in Python. Choose either an
input type or an explicit schema. The schema describes the submitted arguments,
which the child receives as its input event payload; it does not convert that
payload into an instance of the input type.

When a chat model is configured with `subagents=["reviewer"]`, the description
helps it choose when to delegate, and the schema tells it which arguments to
send. A schema is required for model-selected calls. A missing description uses
a generic delegation description.

For calls made explicitly by an action, the existing shorthand remains available:

```java
parent.addResource("reviewer", ResourceType.AGENT, new ReviewAgent());
```

```python
parent.add_resource("reviewer", ResourceType.AGENT, ReviewAgent())
```

This shorthand does not declare an input schema. Use the builder when exposing
the child to a model. Recursive registration, such as a child registering its
parent, is rejected when the plan is built.

Register the resources a child uses, such as its chat model connection, on the
child itself. In Python, a child resolves resources against its own plan only,
so a resource registered solely on the parent is not visible to the child.

## Declare an external sub-agent

Implement `SubagentSetup` (or the appropriate runtime base class) and register a
resource descriptor naming that implementation. Do not register a setup instance.

```java
parent.addResource(
    "remote_reviewer",
    ResourceType.AGENT,
    ResourceDescriptor.Builder.newBuilder(RemoteReviewSetup.class.getName())
        .addInitialArgument("endpoint", "https://example.com/review")
        .addInitialArgument("description", "Review code using the remote service")
        .addInitialArgument("input_schema", inputSchema)
        .build());
```

The Java setup exposes a public `(ResourceDescriptor, ResourceContext)`
constructor and implements the sub-agent invocation contract. `endpoint` above
is configuration read by the example implementation, not a built-in transport.

```python
parent.add_resource(
    "remote_reviewer",
    ResourceType.AGENT,
    ResourceDescriptor(
        clazz="my_app.agents.RemoteReviewSetup",
        endpoint="https://example.com/review",
        description="Review code using the remote service",
        input_schema=input_schema,
    ),
)
```

The Python setup declares these configuration fields and implements the
invocation contract. Both languages construct the setup at runtime from the
descriptor. Existing code that passes a setup instance to `addResource` or
`add_resource` must instead pass a descriptor with the same configuration.

## Delegate from a chat model

List the child's registered name under `subagents` of the parent's chat model:

```java
ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
    .addInitialArgument("connection", "ollamaConnection")
    .addInitialArgument("model", "qwen3:8b")
    .addInitialArgument("subagents", List.of("reviewer"))
    .build();
```

```python
ResourceDescriptor(
    clazz=ResourceName.ChatModel.OLLAMA_SETUP,
    connection="ollama_connection",
    model="qwen3:8b",
    subagents=["reviewer"],
)
```

The model sees the child as a tool named `_subagent_reviewer`, described by the
registration's description and input schema. The arguments the model sends
become the child's input, and the child's outputs return to the model as the
tool observation, so it can continue reasoning over them. For an internal
sub-agent, the observation is the JSON encoding of the full output list
described in [Call from an action](#call-from-an-action), even when the child
emits a single output, so its shape does not depend on how many outputs the
child emits.

## Call from an action

An action can submit input to a child directly and await its outcome. This path
does not need an input schema.

```java
public class DelegatingAgent extends Agent {

    @Action(EventType.InputEvent)
    public static void delegate(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup child =
                (SubagentSetup) ctx.getResource("reviewer", ResourceType.AGENT);
        SubagentResult result = child.submit(ctx, "review the diff").await();
        if (result.isSuccess()) {
            String answer = String.valueOf(((List<?>) result.getResult()).get(0));
            ctx.sendEvent(new OutputEvent(answer));
        } else {
            ctx.sendEvent(new OutputEvent("failed: " + result.getErrorMessage()));
        }
    }
}
```

```python
class DelegatingAgent(Agent):
    @action(InputEvent.EVENT_TYPE)
    @staticmethod
    async def delegate(event: Event, ctx: RunnerContext) -> None:
        child = ctx.get_resource("reviewer", ResourceType.AGENT)
        future = await child.submit(ctx, "review the diff")
        result = await future
        if result.success:
            ctx.send_event(OutputEvent(output=result.result[0]))
        else:
            ctx.send_event(OutputEvent(output=f"failed: {result.error_message}"))
```

The awaited value is a `SubagentResult`. On success, its `result` field
(`getResult()` in Java) carries the child's result. For an internal sub-agent,
such as `reviewer` here, that is the list of outputs the child emitted, which is
why the example takes the first element. An external sub-agent returns whatever
its `SubagentSetup` implementation produces, such as a string or an object. On
failure, `success` is `False` (`isSuccess()` is `false` in Java), and
`error_message` (`getErrorMessage()`) carries the reason.

## Use a ReActAgent as the child

A ReActAgent runs as a child like any other agent, keeping its own event loop,
resources and conversation. It uses only its own prompt and output schema, even
when the parent is also a ReActAgent.

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

Called from an action, the child takes a string input as its user message, or
uses it to fill the `{input}` placeholder of its prompt. Delegated to by a
model, the child receives the arguments as a map keyed by the input schema's
field names and turns that map into its user message, so the schema and the
child's prompt form one contract:

- **With a prompt**, the schema's field names must match the prompt's
  placeholders: each argument fills the placeholder of the same name, as
  described in [Prompt]({{< ref "docs/development/react_agent#prompt" >}}).
- **Without a prompt**, declare a single `input` field, as in the example above.
  A map holding only `input` passes its value through as the user message; any
  other map is rejected because there is no prompt to format it.

A child with a prompt declares the fields its placeholders use:

```java
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

```python
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

The framework does not check the schema against the prompt. A field the prompt
does not use is ignored, and a placeholder no field fills is left in the prompt
as literal text, so keep the two in step when changing either.
