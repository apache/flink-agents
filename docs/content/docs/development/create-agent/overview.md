---
title: "Overview"
weight: 1
type: docs
aliases:
  - /docs/development/workflow_agent/
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

## What Is an Agent?

An AI agent uses a language model to reason about a task, decide what to do next, and
take actions toward a goal. It can use tools to interact with external systems and
memory to retain context, adapting its next steps based on the results.

Flink Agents provides an event-driven model for building and orchestrating these agents
on Apache Flink. **Actions** define the work to perform, **Events** carry data and connect
steps, and **Resources** provide reusable capabilities such as models, tools, and prompts.
You can explicitly define the workflow, let a model choose the next steps within it,
or combine both approaches.

## Core Concepts

| Concept | Role in an Agent | Example |
| --- | --- | --- |
| **Event** | Carries data between Actions. Its type and attributes determine which Actions react to it. | A `ChatRequestEvent` carries messages and the name of a model to call. |
| **Action** | Reacts to matching Events, performs work, and can send new Events. One Event can trigger multiple Actions. | An Action reads a review and sends a request for a summary. |
| **Resource** | A reusable, named runtime component that Actions or other Resources can use. | A chat model configured for summarizing reviews. |

Common Resources include:

- [Chat Models]({{< ref "docs/development/calling-llms/chat-models" >}}) for model calls.
- [Tools]({{< ref "docs/development/calling-llms/tool_use" >}}) for callable operations.
- [Prompts]({{< ref "docs/development/calling-llms/prompts" >}}) for reusable message templates.
- [MCP Servers]({{< ref "docs/development/calling-llms/mcp" >}}) for accessing tools and prompts provided by external MCP servers.
- [Vector Stores]({{< ref "docs/development/vector-search/vector_stores" >}}) for storing and retrieving embeddings and related data.
- [Skills]({{< ref "docs/development/calling-llms/skills" >}}) for reusable instructions and supporting files.

[Memory]({{< ref "docs/development/memory/overview" >}}) lets Actions retain and retrieve
information through the execution context. It is a separate API, not a Resource type.

## How an Agent Runs

Consider an Agent that summarizes product reviews. You define two Actions: one to prepare
the request and one to emit the result. The framework supplies the chat Action between them.

{{< mermaid >}}
flowchart TD
    input[Input review] -->|InputEvent| request[Your Action: request summary]
    request -->|ChatRequestEvent| chat[Built-in chat Action]
    chat -. uses .-> model[Resource: review model]
    chat -->|ChatResponseEvent| output[Your Action: emit summary]
    output -->|OutputEvent| downstream[Downstream output]
{{< /mermaid >}}

1. The framework wraps each input record in an `InputEvent`.
2. Your input Action reacts to that Event and sends a `ChatRequestEvent`, referencing
   the registered model by name.
3. The built-in chat Action calls the model and sends a `ChatResponseEvent` with the result.
4. Your response Action sends an `OutputEvent`. The framework emits its payload downstream;
   `OutputEvent` bypasses Action matching.

Actions are connected by the Events they send and react to, rather than by direct calls to
one another. The example is a linear flow, but an Event can trigger several matching Actions,
and an Action can send Events that lead to different branches or another iteration of a loop.

## Define Your Agent

To define this workflow:

1. Extend `Agent` and declare the Resources it needs.
2. Register Actions with `@action(...)` in Python or `@Action(...)` in Java, specifying
   the Events they react to.
3. Use the `RunnerContext` passed to each Action to send Events and access runtime services.

### Registering and Referencing Resources

Register a Resource with a name and a resource type. For example, `@chat_model_setup`
in Python or `@ChatModelSetup` in Java registers a chat model under the declaration's method
name. You can also use `add_resource` / `addResource` to supply the name and type explicitly.
Register a Resource on the agents execution environment to make it available to multiple Agents.

Inside an Action, use the execution context to get a Resource instance by its name and type,
then use the instance's methods. For the model declared in the example below:

- **Python:** `model = ctx.get_resource("review_model", ResourceType.CHAT_MODEL)`
- **Java:** `Resource model = ctx.getResource("reviewModel", ResourceType.CHAT_MODEL)`

Built-in Actions handle some Resource operations for you. For example, when you send a
`ChatRequestEvent`, the [built-in chat Action]({{< ref "docs/development/calling-llms/chat#chat-events" >}})
gets the named chat model Resource from the
context, calls it, and sends a `ChatResponseEvent`. The example below uses this built-in
Action, so your own Actions do not need to get and call the model directly. Sending an Event
works here because an Action handles that Event and performs the Resource operation.

Here, `review_model` / `reviewModel` names the Resource within your application;
`qwen3:8b` selects the model on the Ollama server.

### Review Summary Example

The examples below accept a review as a **string** and output the model's summary as a string.
They assume an Ollama connection named `ollama_server` is registered in the agents execution
environment and the `qwen3:8b` model is available on that server. See the
[Quickstart]({{< ref "docs/get-started/quickstart/create-agent/single-agent#register-the-chat-model-connection" >}})
for environment registration (use `ollama_server` as the connection name in either language),
and [Chat Model Providers]({{< ref "docs/development/calling-llms/chat-models/providers" >}})
for provider configuration. Imports and job setup are omitted here to focus on the Agent definition.

{{< tabs "Define Your Agent" >}}

{{< tab "Python" >}}
```python
class ReviewSummaryAgent(Agent):
    @chat_model_setup
    @staticmethod
    def review_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_SETUP,
            connection="ollama_server",
            model="qwen3:8b",
        )

    @action(EventType.InputEvent)
    @staticmethod
    def request_summary(event: Event, ctx: RunnerContext) -> None:
        review = InputEvent.from_event(event).input
        ctx.send_event(
            ChatRequestEvent(
                model="review_model",
                messages=[ChatMessage.user(f"Summarize this review: {review}")],
            )
        )

    @action(EventType.ChatResponseEvent)
    @staticmethod
    def emit_summary(event: Event, ctx: RunnerContext) -> None:
        response = ChatResponseEvent.from_event(event).response
        ctx.send_event(OutputEvent(output=response.text))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class ReviewSummaryAgent extends Agent {
    @ChatModelSetup
    public static ResourceDescriptor reviewModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "ollama_server")
                .addInitialArgument("model", "qwen3:8b")
                .build();
    }

    @Action(EventType.InputEvent)
    public static void requestSummary(Event event, RunnerContext ctx) {
        String review = (String) InputEvent.fromEvent(event).getInput();
        ctx.sendEvent(
                new ChatRequestEvent(
                        "reviewModel",
                        List.of(ChatMessage.user("Summarize this review: " + review))));
    }

    @Action(EventType.ChatResponseEvent)
    public static void emitSummary(Event event, RunnerContext ctx) {
        String summary = ChatResponseEvent.fromEvent(event).getResponse().getText();
        ctx.sendEvent(new OutputEvent(summary));
    }
}
```
{{< /tab >}}

{{< /tabs >}}

## Next Steps

- Learn how to register Actions, define trigger conditions, and use async or durable execution
  in [Actions]({{< ref "docs/development/create-agent/actions" >}}).
- Learn how to define and pass data in [Events]({{< ref "docs/development/create-agent/events" >}}).
- [Choose APIs and Languages]({{< ref "docs/development/create-agent/choose-apis-and-languages" >}})
  for your application, or define an Agent with the
  [YAML API]({{< ref "docs/development/create-agent/yaml" >}}).
- Follow the [Quickstart]({{< ref "docs/get-started/quickstart/create-agent/single-agent" >}})
  to connect an Agent to a Flink job and build a complete review-analysis application.
