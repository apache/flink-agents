---
title: "Chat with Models"
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

Agents interact with chat models by sending messages and receiving responses. A request identifies a registered chat model and supplies the messages for it to process. The response contains the model's answer or reports a failure.

This chapter explains how chat requests and responses flow through an Agent, then walks through a text-only example and handling successful and failed responses. It then covers multimodal messages and the tool-calling loop.

## Chat Flow {#chat-events}

Chat models communicate through built-in events:

- **ChatRequestEvent**: Sent by actions to request a chat completion from the LLM
- **ChatResponseEvent**: Received by actions containing the LLM's response

An Action in your Agent sends a `ChatRequestEvent`. The built-in `chat_model_action` invokes the named model and emits a `ChatResponseEvent` when the request completes. A response Action in your Agent handles that event; you do not need to implement the model invocation yourself.

A basic text-answering Agent follows this flow:

```text
InputEvent → request Action → ChatRequestEvent
           → chat_model_action → ChatResponseEvent → response Action → OutputEvent
```

A `ChatRequestEvent` can name a model router instead of a chat model. The built-in action selects the model first and emits a `ModelRoutingEvent`; see [Model Routing]({{< ref "docs/development/calling-llms/model_routing" >}}).

## Request and Response Example {#usage-example}

The Agent below declares an Ollama connection and model, sends each input as a user message, and emits the model's text answer as an `OutputEvent`. The connection and model declarations are included to show which resource names the request references.

The example uses Ollama at `http://localhost:11434` with `qwen3:8b`; adjust the endpoint and model to match your configuration.

This example raises an exception when the chat response reports a failure, causing the Action to fail. Applications that need a fallback answer can instead handle the failed event explicitly before accessing its response.

{{< tabs "Usage Example" >}}

{{< tab "Python" >}}
```python
from flink_agents.api.agents.agent import Agent
from flink_agents.api.chat_message import ChatMessage
from flink_agents.api.decorators import action, chat_model_connection, chat_model_setup
from flink_agents.api.events.chat_event import ChatRequestEvent, ChatResponseEvent
from flink_agents.api.events.event import Event, InputEvent, OutputEvent
from flink_agents.api.events.event_type import EventType
from flink_agents.api.resource import ResourceDescriptor, ResourceName
from flink_agents.api.runner_context import RunnerContext


class MyAgent(Agent):

    @chat_model_connection
    @staticmethod
    def ollama_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_CONNECTION,
            base_url="http://localhost:11434",
            request_timeout=30.0
        )

    @chat_model_setup
    @staticmethod
    def ollama_chat_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_SETUP,
            connection="ollama_connection",
            model="qwen3:8b",
            temperature=0.7
        )

    @action(EventType.InputEvent)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        input_event = InputEvent.from_event(event)
        # Create a chat request with user message
        user_message = ChatMessage.user(f"input: {input_event.input}")
        ctx.send_event(
            ChatRequestEvent(model="ollama_chat_model", messages=[user_message])
        )

    @action(EventType.ChatResponseEvent)
    @staticmethod
    def process_response(event: Event, ctx: RunnerContext) -> None:
        chat_response = ChatResponseEvent.from_event(event)
        if chat_response.is_failed:
            raise RuntimeError(chat_response.error)
        ctx.send_event(OutputEvent(output=chat_response.response.text))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
import java.util.List;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.ChatModelConnection;
import org.apache.flink.agents.api.annotation.ChatModelSetup;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;

public class MyAgent extends Agent {
    @ChatModelConnection
    public static ResourceDescriptor ollamaConnection() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_CONNECTION)
                .addInitialArgument("endpoint", "http://localhost:11434")
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor ollamaChatModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "ollamaConnection")
                .addInitialArgument("model", "qwen3:8b")
                .build();
    }

    @Action(EventType.InputEvent)
    public static void processInput(Event event, RunnerContext ctx) throws Exception {
        InputEvent inputEvent = InputEvent.fromEvent(event);
        ChatMessage userMessage =
                new ChatMessage(MessageRole.USER, String.format("input: %s", inputEvent.getInput()));
        ctx.sendEvent(new ChatRequestEvent("ollamaChatModel", List.of(userMessage)));
    }

    @Action(EventType.ChatResponseEvent)
    public static void processResponse(Event event, RunnerContext ctx)
            throws Exception {
        ChatResponseEvent chatResponse = ChatResponseEvent.fromEvent(event);
        if (chatResponse.isFailed()) {
            throw new IllegalStateException(chatResponse.getError());
        }
        ctx.sendEvent(new OutputEvent(chatResponse.getResponse().getText()));
    }
}
```
{{< /tab >}}

{{< /tabs >}}

In the request, `ollama_chat_model` (Python) or `ollamaChatModel` (Java) names the declared model setup. The provider model identifier, `qwen3:8b`, belongs in that setup. The response Action emits an `OutputEvent` so the Agent's answer can continue through the Flink pipeline.

## Handle Success and Failure

`ChatResponseEvent` reports whether a chat request succeeded or failed:

- On success, `event.getResponse()` (Java) or `event.response` (Python) returns a
  `ChatResult`. It contains the assistant message and, when available, the model
  name, response ID, token usage, and finish reason. Use `getText()` / `text` to
  read the answer as text, or `getBlocks()` / `blocks` to include all content.
- On failure, `event.getError()` (Java) or `event.error` (Python) provides an error
  description. Check `event.isFailed()` / `event.is_failed` before reading the
  response: accessing the response of a failed event raises an exception.
  Serialization and trace logging do not invoke these accessors.

If you requested structured output, read the parsed result from
`event.getStructuredOutput()` (Java) or `event.structured_output` (Python). See
[Structured Output]({{< ref "docs/development/calling-llms/structured-output" >}}) for how to declare a schema and which providers enforce it natively.

Applications that need to recover from a failed chat request should handle the
failed event explicitly. The built-in ReAct agent propagates the failure.

### Failures and Recovery

Ordinary provider, resource-resolution, response-validation and structured-output errors produce a failed response after configured retries and routing fallbacks are exhausted. `max-retries` controls the number of additional attempts (default: 0), and `retry-wait-interval` controls exponential backoff. These settings also apply to routing judge calls. A judge call failure fails the request rather than selecting the default model; a normal abstaining verdict still selects the default. Intermediate attempts and Tool rounds do not emit terminal responses, and the final response always refers to the initial request ID.

Cancellation, interruption and fatal errors propagate. Durable persistence/recovery, required Tool context, and event-delivery failures also propagate to Flink rather than becoming Chat failures. A response cannot be guaranteed during cancellation or an unrecoverable runtime failure. Recovery may replay physical events; consumers must correlate by request ID and complete idempotently.

### Custom Response Producers

Create results with `ChatResponseEvent.success(...)` or `ChatResponseEvent.failed(...)`. This is a breaking event and durable-call format change: `status` is mandatory, and old events and old persisted Chat/routing call results are not supported. Update custom producers, consumers, and stored fixtures together.

## Multimodal Messages {#message-content-blocks}

Multimodal messages combine text with images, audio, video, or documents. Use
`ChatMessage.blocks` to arrange these contents in the order you want them presented. Supported block types are `TextBlock`, `ImageBlock`,
`AudioBlock`, `VideoBlock`, and `DocumentBlock`. Assistant messages can also contain
`ReasoningBlock` and `ToolCallBlock`; a tool message contains one `ToolResultBlock`
whose `call_id` matches the corresponding tool call and whose blocks hold the result.

To read the complete content of a message or `ChatResult`, use `getBlocks()` (Java)
or `blocks` (Python). Use `getText()` (Java) or `text` (Python) when you only need
text: these accessors concatenate the message's text blocks and omit media,
reasoning, tool calls, and text inside tool results.

For tools that return text or media, see
[Tool response content and metadata]({{< ref "docs/development/calling-llms/tool_use#tool-response-content-and-metadata" >}}).

**Provider support:** The OpenAI Chat Completions integration, and the Azure OpenAI
and vLLM integrations built on it, send media blocks to the model; see
[Multimodal Input]({{< ref "docs/development/calling-llms/chat-models/providers#multimodal-input" >}}) for what each block becomes. Ollama sends
Base64 images, and Anthropic sends images and PDF or plain-text documents; see the
[Ollama]({{< ref "docs/development/calling-llms/chat-models/providers#ollama" >}}) and
[Anthropic]({{< ref "docs/development/calling-llms/chat-models/providers#anthropic" >}}) provider
sections. The Gemini, Amazon Bedrock, IBM watsonx.ai, DashScope and OpenAI Responses
integrations do not send media yet: a message with a media block raises
`UnsupportedContentBlockException` (Java) or `UnsupportedContentBlockError` (Python).

For example, a message can contain a question followed by an image:

{{< tabs "Multimodal Messages" >}}

{{< tab "Python" >}}
```python
from flink_agents.api.chat_message import ChatMessage, ImageBlock, TextBlock

message = ChatMessage.user([
    TextBlock(text="Describe this image"),
    ImageBlock.from_url("image/png", "https://example.org/image.png"),
])
```
{{< /tab >}}

{{< tab "Java" >}}
```java
ChatMessage message = ChatMessage.user(List.of(
        TextBlock.of("Describe this image"),
        ImageBlock.fromUrl("image/png", "https://example.org/image.png")));
```
{{< /tab >}}

{{< /tabs >}}

Each media block requires a MIME type such as `image/png` and a media source.
Use `from_url` (Python) or `fromUrl` (Java) for an externally managed URL, as above.
For inline data, all four media block types provide two separate entry points:

- `from_bytes(media_type, data)` (Python) or `fromBytes(mediaType, data)` (Java)
  accepts non-empty raw `bytes` or `byte[]` and encodes them as standard Base64
  without line wrapping at construction. Java does not retain the caller's array.
- `from_base64(media_type, data)` or `fromBase64(mediaType, data)` accepts a
  non-empty, already Base64-encoded string. It preserves the string without
  encoding it again or validating its Base64 syntax.

{{< tabs "Inline media" >}}
{{< tab "Python" >}}
```python
from pathlib import Path
from flink_agents.api.chat_message import ImageBlock

image = ImageBlock.from_bytes("image/png", Path("image.png").read_bytes(), name="image.png")
# If a service already returned Base64, pass that string directly.
encoded_image = ImageBlock.from_base64("image/png", base64_from_service)
```
{{< /tab >}}
{{< tab "Java" >}}
```java
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.flink.agents.api.chat.messages.ImageBlock;

ImageBlock image = ImageBlock.fromBytes("image/png", Files.readAllBytes(Path.of("image.png")));
// If a service already returned Base64, pass that string directly.
ImageBlock encodedImage = ImageBlock.fromBase64("image/png", base64FromService);
```
{{< /tab >}}
{{< /tabs >}}

The framework does not verify that the contents match the declared media type.
Choose a concrete block such as `ImageBlock`, rather than constructing the shared
`MediaBlock` base class. Both inline factories produce the same Base64 source
representation for serialization and provider conversion.

Media blocks also accept optional metadata: `name` for a file name, `size_bytes`
for the media size in bytes, and `sha256` for a content checksum.

To read only the text, use `.text` (Python) or `getText()` (Java). These concatenate
the text blocks and exclude media.

## Tool Calling {#built-in-events-and-actions}

A chat model can use tools to perform operations while answering a request. To enable this, define or register the tools and reference their names in the model setup's `tools` list. See [Local Function as Tool]({{< ref "docs/development/calling-llms/tool_use#local-function-as-tool" >}}) for declaration and registration examples, [Tool Parameter Injection]({{< ref "docs/development/calling-llms/tool_use#tool-parameter-injection" >}}) for framework-provided arguments, and [Parallel tool-call batches]({{< ref "docs/development/calling-llms/tool_use#parallel-tool-call-batches" >}}) for execution settings and concurrency limits.

Send a `ChatRequestEvent` as usual. The model decides whether to answer directly or request tool calls:

```text
ChatRequestEvent → chat_model_action → ChatResponseEvent
                      │        ↑
    ToolRequestEvent  │        │  ToolResponseEvent
                      ↓        │
                  tool_call_action
```

When the model requests tools, the framework executes them, adds their results to the conversation, and calls the model again. This cycle continues until the model produces a final answer or the chat request fails. Your Agent handles the final `ChatResponseEvent` using the [success and failure handling](#handle-success-and-failure) described above; intermediate tool calls do not produce a final chat response.

The built-in `chat_model_action` and `tool_call_action` coordinate this cycle. For this flow, you do not need to send or handle `ToolRequestEvent` and `ToolResponseEvent` yourself. For details on tool execution and results, see [Built-in Events and Actions in Tool Use]({{< ref "docs/development/calling-llms/tool_use#built-in-events-and-actions" >}}).

## Next Steps

- Use [Prompts]({{< ref "docs/development/calling-llms/prompts" >}}) to reuse message templates across requests.
- Add [Tool Use]({{< ref "docs/development/calling-llms/tool_use" >}}) or [Skills]({{< ref "docs/development/calling-llms/skills" >}}) when the model needs to perform operations beyond generating an answer.
- Use [Structured Output]({{< ref "docs/development/calling-llms/structured-output" >}}) to get a typed object instead of free text.
- Use [Model Routing]({{< ref "docs/development/calling-llms/model_routing" >}}) to choose among registered models for each request.
- Consult [Built-in Chat Model Providers]({{< ref "docs/development/calling-llms/chat-models/providers" >}}) for provider-specific configuration and media support.
