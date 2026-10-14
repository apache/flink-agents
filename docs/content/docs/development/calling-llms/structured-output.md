---
title: "Structured Output"
weight: 8
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

Structured output makes a chat model return an object that matches a schema you define, instead of free text. A [`ChatResponseEvent`]({{< ref "docs/development/calling-llms/chat#handle-success-and-failure" >}}) carries the parsed object in `getStructuredOutput()` (Java) or `structured_output` (Python).

There are two ways to get the model to follow the schema:

- **Native:** the schema is sent in the provider's own structured-output parameter (for example OpenAI `response_format` or Anthropic `output_config`). The provider constrains the model's output to the schema.
- **Prompt:** the model is asked in the prompt text to reply in JSON that matches the schema, and the framework parses the reply. This works with every model, but nothing enforces the shape.

You choose a *strategy*, and each provider connection reports whether it can apply the schema natively. The framework combines the two for every request.

## Declare a Schema

A schema is one of:

| Schema | Python | Java |
|---|---|---|
| Typed class | Pydantic `BaseModel` subclass | POJO `Class` |
| Flink row type | `RowTypeInfo` | `RowTypeInfo` |

Only a typed class can be applied natively. A `RowTypeInfo` always uses the prompt path.

Pass the schema to a [ReAct Agent]({{< ref "docs/development/react_agent" >}}) with `output_schema`, or put it on a `ChatRequestEvent` when you [send chat requests yourself]({{< ref "docs/development/calling-llms/chat" >}}).

On a `ChatRequestEvent`:

- **Python:** wrap the schema in `OutputSchema`, as in the example below.
- **Java:** pass a POJO class directly. Wrap a `RowTypeInfo` with `new OutputSchema(rowTypeInfo)`. Only the ReAct Agent wraps it for you. A bare `RowTypeInfo` on the event fails with `Unsupported output schema`.

{{< tabs "Declare a Schema" >}}

{{< tab "Python" >}}
```python
class Review(BaseModel):
    summary: str
    sentiment: Sentiment  # an Enum, see "Enum Fields" below

# ReAct Agent
agent = ReActAgent(chat_model=chat_model_descriptor, output_schema=Review)

# Custom action
ctx.send_event(
    ChatRequestEvent(
        model="my_chat_model",
        messages=[
            ChatMessage.user(
                "Review the text below. Reply in JSON with the fields"
                f" summary and sentiment.\n\n{text}"
            )
        ],
        output_schema=OutputSchema(output_schema=Review),
    )
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class Review {
    public String summary;
    public Sentiment sentiment; // an enum, see "Enum Fields" below
}

// ReAct Agent
ReActAgent agent = new ReActAgent(chatModelDescriptor, prompt, Review.class);

// Custom action
ctx.sendEvent(
        new ChatRequestEvent(
                "myChatModel",
                List.of(
                        ChatMessage.user(
                                "Review the text below. Reply in JSON with the fields"
                                        + " summary and sentiment.\n\n"
                                        + text)),
                Review.class));
```
{{< /tab >}}

{{< /tabs >}}

## Read the Result

A ReAct Agent emits the parsed object as its output. In a custom action, read it from the successful `ChatResponseEvent`. For a typed class, Java returns an instance of the POJO. Python returns the model instance, or its fields as a `dict` once the event has been serialized, so validate it back into the model. For a `RowTypeInfo`, the result is a `Row`.

{{< tabs "Read the Result" >}}

{{< tab "Python" >}}
```python
@action(ChatResponseEvent.EVENT_TYPE)
@staticmethod
def process_response(event: Event, ctx: RunnerContext) -> None:
    response = cast("ChatResponseEvent", event)
    if response.is_failed:
        raise RuntimeError(response.error)
    review = Review.model_validate(response.structured_output)
    ctx.send_event(OutputEvent(output=review.summary))
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@Action(EventType.ChatResponseEvent)
public static void processResponse(Event event, RunnerContext ctx) throws Exception {
    ChatResponseEvent response = ChatResponseEvent.fromEvent(event);
    if (response.isFailed()) {
        throw new IllegalStateException(response.getError());
    }
    Review review = (Review) response.getStructuredOutput();
    ctx.sendEvent(new OutputEvent(review.summary));
}
```
{{< /tab >}}

{{< /tabs >}}

## Choose a Strategy

Set `structured_output_strategy` on the chat model **setup** (not the connection). The value is case-insensitive.

| Strategy | Behavior |
|---|---|
| `AUTO` (default) | Native when the connection recommends native output for the model (see [Provider Support](#provider-support)). Otherwise the prompt path, silently. This includes a request that cannot carry the schema. |
| `NATIVE` | Native whenever the request can carry the schema, even for a model that is not on the recommended list. If it cannot (for example a `RowTypeInfo` schema, or a blocker listed in [Provider Support](#provider-support)), it raises `IllegalArgumentException` (Java) or `ValueError` (Python) before any model call. |
| `PROMPT` | Never native. |

{{< tabs "Choose a Strategy" >}}

{{< tab "Python" >}}
```python
ResourceDescriptor(
    clazz=ResourceName.ChatModel.OPENAI_COMPLETIONS_SETUP,
    connection="openai_connection",
    model="gpt-4o-mini",
    structured_output_strategy="native",
)
```
{{< /tab >}}

{{< tab "Java" >}}
```java
ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OPENAI_COMPLETIONS_SETUP)
        .addInitialArgument("connection", "openaiConnection")
        .addInitialArgument("model", "gpt-4o-mini")
        .addInitialArgument("structured_output_strategy", "native")
        .build();
```
{{< /tab >}}

{{< /tabs >}}

{{< hint info >}}
`AUTO` falling back is silent. If a request you expect to be native is not, check the model name against [Provider Support](#provider-support), or set `NATIVE` to get an error that names the reason.
{{< /hint >}}

## How Native Output Behaves

The events your actions send and handle are the same on both paths. These points differ:

```text
Prompt path:  ChatRequestEvent → model call (tool loop)
                               → parse JSON answer → ChatResponseEvent

Native path:  ChatRequestEvent → model call (tool loop, no schema)
                               → one conversion call (schema, no tools)
                               → parse answer → ChatResponseEvent
```

- **The schema applies to the final answer only.** Tool-calling turns run without it, so tools work as usual.
- **Native output adds one conversion call after the tool loop.** The model is asked to convert its answer into the schema's format without adding information. This adds latency and cost to each request. The conversion response is the `response` on the `ChatResponseEvent`.
- **The prompt path has no conversion call.** The framework does not add the schema to your messages. The ReAct Agent adds its own instruction. In a custom action, ask for JSON that matches the schema in your own prompt. Do this even under `AUTO`, because the request falls back to the prompt path whenever native output does not apply.
- **The ReAct Agent adjusts its prompt to the path.** On the native path it only asks the model to include the information for every required field, because the conversion call formats it. If it cannot tell whether the call will be native, it keeps the JSON instruction.
- **A reply that cannot be parsed fails the request.** You get a failed `ChatResponseEvent` after the configured retries. See [Failures and Recovery]({{< ref "docs/development/calling-llms/chat#failures-and-recovery" >}}).

## Provider Support

"Recommended" is what `AUTO` needs to go native. Models outside it still go native under `NATIVE`, unless the last column lists a blocker. A blocked request uses the prompt path under `AUTO` and `PROMPT`, and fails under `NATIVE`.

| Provider | Languages | Recommended models (`AUTO` goes native) | Blocked (besides a non-typed-class schema) |
|---|---|---|---|
| OpenAI | Java, Python | `gpt-4o-mini`, `gpt-4o-search-preview`, `gpt-4.1`, `gpt-5` and `o3`, `o4-mini` families (names that start with these), plus `gpt-4o`, `gpt-4o-2024-08-06`, `gpt-4o-2024-11-20`, `o1`, `o1-2024-12-17`. Audio, realtime, TTS and transcribe variants are excluded. | |
| OpenAI Responses | Java | none | Always. Use the prompt path. |
| Azure OpenAI | Java, Python | The model named by `model_of_azure_deployment` is one of `gpt-5.1`, `gpt-5.1-chat`, `gpt-5`, `gpt-5-mini`, `gpt-5-nano`, `o3`, `o3-mini`, `o4-mini`, `o1`, `gpt-4o-mini`, `gpt-4.1`, `gpt-4.1-mini`, `gpt-4.1-nano`. | `api_version` older than `2024-08-01`, or not a date |
| vLLM | Java, Python | Any model | Same as OpenAI |
| Anthropic | Java, Python | `claude-opus-4-6`, `-4-7`, `-4-8`, `-5`; `claude-sonnet-4-6`, `-5`; `claude-fable-5`; `claude-mythos-5`; `claude-mythos-preview`; and the `claude-opus-4-5`, `claude-sonnet-4-5`, `claude-haiku-4-5` aliases and their dated versions | You pass `output_config` in `additional_kwargs` |
| Amazon Bedrock | Java | A fixed list of Converse models (a regional or global inference-profile prefix is ignored). ARNs are never recommended. | |
| Gemini | Java | `gemini-*` text models. Image, TTS, audio, live, transcribe, embedding and omni variants are excluded. | The request binds tools |
| Ollama | Java, Python | Any model | |
| watsonx | Java, Python | Any model | |
| DashScope | Python | `qwen3.7-max`, `qwen3.7-max-preview`, `qwen3.7-max-2026-05-17`, `qwen3.7-max-2026-05-20` | |

Notes:

- **Azure OpenAI** matches `model_of_azure_deployment`, not the deployment name, because a deployment name can be anything. If you do not set it, `AUTO` stays on the prompt path (`NATIVE` still goes native). Set it to get `AUTO` native on Azure, as in the example below.
- **Gemini** blocks native output when the request binds tools. The conversion call is tool-free, so this only affects direct connection calls. A newer model that does not support the schema field fails with `400 INVALID_ARGUMENT`. Constraints such as `pattern` and `minLength` are ignored by the server.
- **Ollama** needs a server at v0.5.0 or later. Ollama Cloud and the MLX runner accept the schema but do not enforce it.
- **Bedrock** rejects recursive schemas, and map values are untyped.
- **A native parameter you set yourself conflicts.** Do not put `response_format` (OpenAI-compatible providers) or `output_config` (Anthropic) in `additional_kwargs` or model parameters when you use an output schema. Azure OpenAI, watsonx and DashScope raise an error when the schema would overwrite your value. Anthropic treats the request as blocked.
- **Cross-language setups never go native.** A chat model from the other language, reached through a [cross-language resource]({{< ref "docs/development/calling-llms/chat-models#using-cross-language-providers" >}}), cannot carry a schema across the bridge. `AUTO` and `PROMPT` use the prompt path, and `NATIVE` raises. To use a provider natively, declare the agent in that provider's language.

For example, this Azure OpenAI setup goes native under `AUTO`, because the deployment's model is `gpt-4o-mini`. Without `model_of_azure_deployment`, the same setup would use the prompt path.

{{< tabs "Azure OpenAI Example" >}}

{{< tab "Python" >}}
```python
@chat_model_setup
@staticmethod
def azure_openai_chat_model() -> ResourceDescriptor:
    return ResourceDescriptor(
        clazz=ResourceName.ChatModel.AZURE_OPENAI_SETUP,
        connection="azure_openai_connection",
        model="my-deployment",  # Your Azure deployment name
        model_of_azure_deployment="gpt-4o-mini",  # The model behind it
    )
```
{{< /tab >}}

{{< tab "Java" >}}
```java
@ChatModelSetup
public static ResourceDescriptor azureOpenAIChatModel() {
    return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.AZURE_OPENAI_SETUP)
            .addInitialArgument("connection", "azureOpenAIConnection")
            .addInitialArgument("model", "my-deployment") // Your Azure deployment name
            .addInitialArgument("model_of_azure_deployment", "gpt-4o-mini") // The model behind it
            .build();
}
```
{{< /tab >}}

{{< /tabs >}}

The connection's `api_version` must be `2024-08-01` or later, for example `2024-10-21`.

## Schema Rules

### Enum Fields

List the values the model should return, in the form the model reads back:

- **Python:** a Pydantic `Enum` lists its values. A string enum gives `"type": "string"` and a numeric enum gives `"type": "integer"`.
- **Java:** the schema lists the values Jackson deserializes. Put `@JsonProperty` on **every** constant, or use a `@JsonValue` method that returns a `String`.

{{< tabs "Enum Fields" >}}

{{< tab "Python" >}}
```python
class Sentiment(str, Enum):
    POSITIVE = "positive"
    NEGATIVE = "negative"
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public enum Sentiment {
    @JsonProperty("positive") POSITIVE,
    @JsonProperty("negative") NEGATIVE
}

// or
public enum Sentiment {
    POSITIVE("positive"),
    NEGATIVE("negative");

    private final String label;

    Sentiment(String label) {
        this.label = label;
    }

    @JsonValue
    public String label() {
        return label;
    }
}
```
{{< /tab >}}

{{< /tabs >}}

Two Java cases do not work as you might expect. They behave the same on every Java provider:

- `@JsonProperty` on only **some** constants lists the Java names for **all** of them (`["POSITIVE","NEGATIVE"]`). The model may reply with a Java name where you annotated a different value, and parsing fails. Annotate every constant or none.
- A `@JsonValue` that returns a number produces `{"type":"string","enum":[1,-1]}`, which contradicts itself. Use a string value instead.

### Java POJOs

- **Descriptions and required fields.** `@JsonPropertyDescription` and `@JsonClassDescription` are sent in the native schema, and every field is required (an `Optional` field is required but nullable on OpenAI, Azure OpenAI and Anthropic). The ReAct Agent's *prompt text* is rendered by a different generator, which has no descriptions, and lists a field as required only when it has `@JsonProperty(required = true)`. The prompt path therefore describes the schema less fully than the native path.
- **`Map` fields.** On OpenAI, Azure OpenAI, Anthropic and vLLM, a `Map<String, V>` field becomes an empty object schema that allows no keys, so the model can only return `{}`. Avoid `Map` fields with these providers, or use a nested class. Gemini, Ollama and watsonx type the map values.
- **Recursive types.** The ReAct Agent rejects a self-referential POJO at construction time. Bedrock also rejects a recursive schema.

### Python Models

The schema comes from Pydantic's `model_json_schema()`, so field descriptions and defaults are included. A model that Pydantic cannot express raises a `TypeError` naming the schema when it is rendered.

## Next Steps

- See [Built-in Chat Model Providers]({{< ref "docs/development/calling-llms/chat-models/providers" >}}) for how to declare each connection and setup.
- Use the [ReAct Agent]({{< ref "docs/development/react_agent" >}}) to get structured output from an agent with tools.
