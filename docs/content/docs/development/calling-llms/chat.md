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

There are two ways to call a chat model:

- **[Using Chat Events](#chat-events):** Send a request and handle the response in a separate Action.
- **[Using `ctx.chat`](#using-ctx-chat):** Await the model's answer and continue in the same Action.

Both automatically handle tool calls: when the model requests a configured tool,
the framework executes it and sends the result back to the model. This repeats
until the model returns a final answer or the request fails; your Action does not
need to execute the tools itself. See [Tool Calling](#built-in-events-and-actions)
for details.

Both also support retries and structured output. Select a registered
[chat model setup]({{< ref "docs/development/calling-llms/chat-models" >}}) or
[model router]({{< ref "docs/development/calling-llms/model_routing" >}}) by name.

## Chat Messages

A `ChatMessage` is one message in a conversation. Its `role` identifies who is
speaking, and its `blocks` hold the message content in order. Optional `metadata`
carries additional message attributes.

| Role | Purpose | Construction |
|---|---|---|
| `system` | Instructions or context for the model; accepts text only. | `ChatMessage.system(...)` |
| `user` | User input, containing text or supported media. | `ChatMessage.user(...)` |
| `assistant` | A model response, which may include text, reasoning, or tool calls. | Returned by the model, or `ChatMessage.assistant(...)` for conversation history. |
| `tool` | The result of one tool call. | Created by the tool-calling flow, or `ChatMessage.tool(...)` with a `ToolResultBlock`. |

For text messages, pass a string to the factory method. It creates a message
containing a `TextBlock`:

{{< tabs "Text Messages" >}}
{{< tab "Python" >}}
```python
from flink_agents.api.chat_message import ChatMessage

messages = [
    ChatMessage.system("Answer concisely."),
    ChatMessage.user("What is stream processing?"),
]
```
{{< /tab >}}
{{< tab "Java" >}}
```java
import java.util.List;
import org.apache.flink.agents.api.chat.messages.ChatMessage;

List<ChatMessage> messages = List.of(
        ChatMessage.system("Answer concisely."),
        ChatMessage.user("What is stream processing?"));
```
{{< /tab >}}
{{< /tabs >}}

Pass messages in conversation order. To include previous answers, add them as
assistant messages before the next user message. For reusable instructions or
message templates, see [Prompts]({{< ref "docs/development/calling-llms/prompts" >}}).

Use `message.text` (Python) or `message.getText()` (Java) to concatenate its
text blocks in order. This excludes media, reasoning, tool calls, and text nested
inside tool results. Use `message.blocks` / `message.getBlocks()` for the complete
content. See [Multimodal Messages](#message-content-blocks) for combining text and media.

## Using Chat Events {#chat-events}

The event-based API uses two built-in events:

- **ChatRequestEvent**: Sent by actions to request a chat completion from the LLM
- **ChatResponseEvent**: Received by actions containing the LLM's response

An Action in your Agent sends a `ChatRequestEvent`. The built-in `chat_model_action` invokes the named model and emits a `ChatResponseEvent` when the request completes. A response Action in your Agent handles that event; you do not need to implement the model invocation yourself.

A basic text-answering Agent follows this flow:

```text
InputEvent → request Action → ChatRequestEvent
           → chat_model_action → ChatResponseEvent → response Action → OutputEvent
```

A `ChatRequestEvent` can name a model router instead of a chat model. The built-in action selects the model first and emits a `ModelRoutingEvent`; see [Model Routing]({{< ref "docs/development/calling-llms/model_routing" >}}).

### Example {#usage-example}

Start with the Agent and model declarations in the
[Resources example]({{< ref "docs/development/create-agent/resources#declaration-api" >}}),
which registers `review_model` in Python or `reviewModel` in Java. Add these two
Actions to the Agent class to send each input to the model and emit its answer.
Imports are omitted.

{{< tabs "Usage Example" >}}
{{< tab "Python" >}}
```python
@action(EventType.InputEvent)
@staticmethod
def process_input(event: Event, ctx: RunnerContext) -> None:
    input_event = InputEvent.from_event(event)
    ctx.send_event(ChatRequestEvent(
        model="review_model",
        messages=[ChatMessage.user(str(input_event.input))],
    ))


@action(EventType.ChatResponseEvent)
@staticmethod
def process_response(event: Event, ctx: RunnerContext) -> None:
    response = ChatResponseEvent.from_event(event)
    if response.is_failed:
        raise RuntimeError(response.error)
    ctx.send_event(OutputEvent(output=response.response.text))
```
{{< /tab >}}
{{< tab "Java" >}}
```java
@Action(EventType.InputEvent)
public static void processInput(Event event, RunnerContext ctx) throws Exception {
    InputEvent input = InputEvent.fromEvent(event);
    ctx.sendEvent(new ChatRequestEvent(
            "reviewModel", List.of(ChatMessage.user(String.valueOf(input.getInput())))));
}

@Action(EventType.ChatResponseEvent)
public static void processResponse(Event event, RunnerContext ctx) throws Exception {
    ChatResponseEvent response = ChatResponseEvent.fromEvent(event);
    if (response.isFailed()) {
        throw new IllegalStateException(response.getError());
    }
    ctx.sendEvent(new OutputEvent(response.getResponse().getText()));
}
```
{{< /tab >}}
{{< /tabs >}}

The response Action fails if the request fails. For fallback handling, see
[Handle Success and Failure](#handle-success-and-failure).

<span id="calling-chat-from-an-action"></span>

## Using `ctx.chat` {#using-ctx-chat}

Use `ctx.chat` when the same Action needs to continue with the model's answer.
It returns a `DurableFuture<ChatResult>` (`DurableFuture[ChatResult]` in Python),
which you await to receive the result. The response is returned to the caller and
does not trigger Actions subscribed to `ChatResponseEvent`; the call uses the
calling Action's resources and memory.

Using the same registered model as the [Chat Events example](#usage-example),
replace its two Actions with the following:

{{< tabs "Chat calls" >}}
{{< tab "Python" >}}
```python
@action(EventType.InputEvent)
@staticmethod
async def process_input(event: Event, ctx: RunnerContext) -> None:
    input_event = InputEvent.from_event(event)
    response = await ctx.chat(
        "review_model",
        [ChatMessage.user(str(input_event.input))],
    )
    ctx.send_event(OutputEvent(output=response.text))
```

Use the optional `prompt_args` and `output_schema` arguments for prompt parameters and structured output.
{{< /tab >}}
{{< tab "Java" >}}
```java
@Action(EventType.InputEvent)
public static void processInput(Event event, RunnerContext ctx) throws Exception {
    InputEvent inputEvent = InputEvent.fromEvent(event);
    ChatResult response = ctx.chat(
            "reviewModel",
            List.of(ChatMessage.user(String.valueOf(inputEvent.getInput()))))
            .await();
    ctx.sendEvent(new OutputEvent(response.getText()));
}
```

For prompt arguments or structured output, pass a `ChatRequestEvent` to `ctx.chat(request)`.
{{< /tab >}}
{{< /tabs >}}

### Await and Recovery

Await `ctx.chat` directly within the Action execution that created it; do not wrap
it in `durable_execute_async` / `durableExecuteAsync`. Chat futures do not currently
support `gather`. For general await behavior and durable execution, see
[Async Execution]({{< ref "docs/development/create-agent/actions#async-execution" >}})
and [Durable Execution]({{< ref "docs/development/create-agent/actions#durable-execution" >}}).

With durable execution enabled, chat calls reuse recorded responses by Action
execution and await order, not by model or message contents. Existing model and
tool timeouts apply; `ctx.chat` adds no whole-call timeout.

<span id="java-runtime-requirements"></span>

{{< hint warning >}}
Java `ctx.chat` calls require JDK 21.
Earlier JDK versions are not supported: awaiting the call throws before sending
the request. Use [Chat Events](#chat-events) on those versions.
Python `ctx.chat` calls do not require JDK 21.
A job that runs on JDK 21 also needs
`--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED` in `env.java.opts.all`,
whether its Actions are Java or Python.
{{< /hint >}}

<span id="custom-response-producers"></span>

## Handle Success and Failure

A successful request returns a `ChatResult`, either through the response event
or directly from `ctx.chat`. It contains the assistant message and, when
available, the model name, response ID, token usage, and finish reason. Use
`getMessage()` / `message` to access the message. The result also exposes
`getText()` / `text` and `getBlocks()` / `blocks` with the same semantics described
in [Chat Messages](#chat-messages).

| Calling style | Success | Failure |
|---|---|---|
| Events | Read `event.getResponse()` (Java) or `event.response` (Python). | Check `event.isFailed()` / `event.is_failed` and read `event.getError()` / `event.error`. Accessing the response of a failed event raises an exception. |
| `ctx.chat` | Await the future to receive the `ChatResult`. | A failed chat response raises `ChatResponseEvent.ChatResponseException` (Java) or `ChatResponseError` (Python), which the calling Action can catch. |

When using events with structured output, read the parsed result from
`event.getStructuredOutput()` (Java) or `event.structured_output` (Python). See
[Structured Output]({{< ref "docs/development/calling-llms/structured-output" >}})
for schemas and provider support.

Applications that need a fallback should handle the failed event or catch the
exception, depending on the calling style. The built-in ReAct agent propagates
the failure.

<span id="failures-and-recovery"></span>

Model-call failures are reported after configured retries and routing fallbacks
are exhausted. See [Core Options]({{< ref "docs/operations/configuration#core-options" >}})
for retry settings and [Model Routing]({{< ref "docs/development/calling-llms/model_routing" >}})
for fallbacks.

For how calls behave after a failure and recovery, see
[Recovery Behavior]({{< ref "docs/development/create-agent/actions#recovery-behavior" >}})
in Actions.

## Multimodal Messages {#message-content-blocks}

A message can combine text with images, audio, video, or documents. Pass an
ordered list of blocks to `ChatMessage.user(...)`:

| Block | Content |
|---|---|
| `TextBlock` | Plain text. |
| `ImageBlock` | An image with a media type and source. |
| `AudioBlock` | Audio with a media type and source. |
| `VideoBlock` | Video with a media type and source. |
| `DocumentBlock` | A document with a media type and source. |

Assistant messages can also contain `ReasoningBlock` and `ToolCallBlock`. A tool
message contains exactly one `ToolResultBlock`, whose `call_id` matches the tool
call and whose blocks hold the result. See
[Tool response content and metadata]({{< ref "docs/development/calling-llms/tool_use#tool-response-content-and-metadata" >}})
for tool results.

{{< hint warning >}}
Representable content does not imply support by the selected provider integration
or model. Check [Provider Capabilities]({{< ref "docs/development/calling-llms/chat-models/providers#provider-capabilities" >}})
before using media. In particular, OpenAI Chat Completions and OpenAI Responses
have different support in Flink Agents: the Responses integration currently
rejects media blocks. Media that the integration does not support raises
`UnsupportedContentBlockException` (Java) or `UnsupportedContentBlockError` (Python)
before the request is sent. Even when the integration accepts the media, the
model service may reject it if the selected model or deployment does not support it.
{{< /hint >}}

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

### Media Types and Sources

Each media block requires a `media_type` such as `image/png` and a media source.
There is no framework-wide format whitelist: the selected provider integration
and model determine which media types and sources are supported. The framework
does not verify that the contents match the declared media type.

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

Choose a concrete block such as `ImageBlock`, rather than constructing the shared
`MediaBlock` base class. Media blocks also accept optional metadata: `name` for a
file name, `size_bytes` for the media size in bytes, and `sha256` for a content checksum.

### Serialization

Java and Python use the same serialized block and source structure. Each block
has a `type`, such as `image`; a media block's `source.type` is either `url` or
`base64`:

- A URL source stores the address in `source.url`, not a copy of the media.
  The address must remain accessible when the provider uses it, including after recovery.
- An inline source stores Base64 text in `source.data`. Both `from_bytes` /
  `fromBytes` and `from_base64` / `fromBase64` produce this same representation;
  raw bytes are encoded before serialization.

For example, an image block with a URL source has this structure (optional
metadata omitted):

```json
{
  "type": "image",
  "media_type": "image/png",
  "source": {
    "type": "url",
    "url": "https://example.org/image.png"
  }
}
```

## Tool Calling {#built-in-events-and-actions}

A chat model can use tools to perform operations while answering a request. To enable this, define or register the tools and reference their names in the model setup's `tools` list. See [Local Function as Tool]({{< ref "docs/development/calling-llms/tool_use#local-function-as-tool" >}}) for declaration and registration examples, [Tool Parameter Injection]({{< ref "docs/development/calling-llms/tool_use#tool-parameter-injection" >}}) for framework-provided arguments, and [Parallel tool-call batches]({{< ref "docs/development/calling-llms/tool_use#parallel-tool-call-batches" >}}) for execution settings and concurrency limits.

Send a `ChatRequestEvent` or await `ctx.chat` as usual. Both use the following tool loop, in which the model decides whether to answer directly or request tool calls:

```text
ChatRequestEvent → chat_model_action → ChatResponseEvent
                      │        ↑
    ToolRequestEvent  │        │  ToolResponseEvent
                      ↓        │
                  tool_call_action
```

When the model requests tools, the framework executes them, adds their results to the conversation, and calls the model again. This cycle continues until the model produces a final answer or the chat request fails. The final result reaches your response Action or the awaiting `ctx.chat` caller as described in [Handle Success and Failure](#handle-success-and-failure); intermediate tool calls do not produce a final chat response.

The built-in `chat_model_action` and `tool_call_action` coordinate this cycle. For this flow, you do not need to send or handle `ToolRequestEvent` and `ToolResponseEvent` yourself. For details on tool execution and results, see [Built-in Events and Actions in Tool Use]({{< ref "docs/development/calling-llms/tool_use#built-in-events-and-actions" >}}).

## Next Steps

- Use [Prompts]({{< ref "docs/development/calling-llms/prompts" >}}) to reuse message templates across requests.
- Add [Tool Use]({{< ref "docs/development/calling-llms/tool_use" >}}) or [Skills]({{< ref "docs/development/calling-llms/skills" >}}) when the model needs to perform operations beyond generating an answer.
- Use [Structured Output]({{< ref "docs/development/calling-llms/structured-output" >}}) to get a typed object instead of free text.
- Use [Model Routing]({{< ref "docs/development/calling-llms/model_routing" >}}) to choose among registered models for each request.
- Consult [Built-in Chat Model Providers]({{< ref "docs/development/calling-llms/chat-models/providers" >}}) for provider-specific configuration and media support.
