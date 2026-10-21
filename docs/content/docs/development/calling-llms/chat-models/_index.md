---
title: "Chat Models"
weight: 1
type: docs
aliases:
  - /docs/development/calling-llms/declare-resources/
  - /docs/development/chat_models/
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

Chat models let an Agent send messages to a Large Language Model (LLM) and use its
responses. In Flink Agents, you configure a model through two named Resources:

| Resource | Purpose | Typical settings |
|---|---|---|
| **Connection** (`CHAT_MODEL_CONNECTION`) | Connect to a provider service. | Endpoint, credentials, request timeout, retries |
| **Setup** (`CHAT_MODEL`) | Choose how to use that connection for a particular task. | Connection name, model name, prompt, tools, skills, generation parameters |

A setup's registered name identifies it inside your application; its `model`
setting selects the model or deployment at the provider. For example, a setup named
`review_model` can select `qwen3:8b` on an Ollama server. A `ChatRequestEvent` refers
to `review_model`, not to the provider's model name.

Choose an implementation and its settings from
[Built-in Chat Model Providers]({{< ref "docs/development/calling-llms/chat-models/providers" >}}).
Provider implementations have different options and capabilities. Install the
selected implementation's dependencies in the job environment; declaring a
Resource does not install a provider or download a model.

## Configuring Chat Models

Register a connection and a setup using the methods described in
[Resources]({{< ref "docs/development/create-agent/resources#registering-resources" >}}).
For a Python or Java example, see the
[Declaration API example]({{< ref "docs/development/create-agent/resources#declaration-api" >}}).

<span id="using-cross-language-providers"></span>

To use a provider implemented in the other language, see
[Cross-Language Resources]({{< ref "docs/development/create-agent/resources#cross-language-resources" >}})
for descriptor selection and runtime requirements.

### Connection

A connection configures access to the provider service, including its endpoint,
authentication, and supported timeout and retry options. Field names and defaults
vary by implementation; use the selected implementation's options listed in
[Built-in Chat Model Providers]({{< ref "docs/development/calling-llms/chat-models/providers" >}}).

Multiple setups can share a connection. Use separate connections when they need
different endpoints, credentials, or request settings.

### Setup

A setup selects a model and the capabilities available to it. The table below
lists the common setup settings. Each provider implementation also supports its
own configuration options, such as `temperature` and token limits; see
[Built-in Chat Model Providers]({{< ref "docs/development/calling-llms/chat-models/providers" >}})
for provider-specific settings and defaults.

| Setting | Required | Meaning |
|---|---|---|
| `connection` | Required | Registered name of a compatible `CHAT_MODEL_CONNECTION` Resource. |
| `model` | Required unless the implementation provides a default | Provider model or deployment identifier, such as `qwen3:8b`. Set it explicitly when selecting a model. An empty or whitespace-only value is rejected when the setup is created. |
| `prompt` | Optional | `PROMPT` Resource name or `Prompt` object. A string is a Resource reference, not literal prompt text. Omitted by default. See [Prompts]({{< ref "docs/development/calling-llms/prompts" >}}). |
| `tools` | Optional | Registered `TOOL` Resource names the model may call. Defaults to an empty list; registering a tool alone does not expose it to the model. See [Tool Use]({{< ref "docs/development/calling-llms/tool_use" >}}). |
| `skills` | Optional | Skill names from the `name` field in each loaded `SKILL.md`, not the registered names of the skill sources. Omitted by default. See [Skills]({{< ref "docs/development/calling-llms/skills" >}}). |
| `subagents` | Optional | Registered `AGENT` Resource names the model may delegate to. Defaults to an empty list. See [Sub-agents]({{< ref "docs/development/multi-agent/subagents#delegate-from-a-chat-model" >}}). |
| `allowed_commands` | Optional | Shell command names permitted by the built-in `bash` tool. Defaults to an empty list. See [Skills]({{< ref "docs/development/calling-llms/skills#using-skill-files-and-commands" >}}) for command permissions. |
| `allowed_script_dirs` | Optional | Additional directories whose scripts the built-in `bash` tool may execute. Defaults to an empty list; the chat flow also includes the selected skills' directories. |
| `structured_output_strategy` | Optional | How to apply a request's output schema: `AUTO` (default), `NATIVE`, or `PROMPT`. See [Structured Output]({{< ref "docs/development/calling-llms/structured-output#choose-a-strategy" >}}). |

Enabling skills also adds their descriptions and the built-in `load_skill` and
`bash` tools. Configure command permissions as described in the Skills chapter.
The selected model must support tool calling to use tools, skills, or sub-agents.

## Custom Providers

{{< hint warning >}}
The custom provider APIs are experimental and unstable, subject to incompatible changes in future releases.
{{< /hint >}}

To integrate another model provider, implement a connection and a setup. The
setup defines the model configuration; the connection translates between Flink
Agents and the provider API:

```text
Setup: model and provider-specific settings
  → Connection.chat(messages, tools, parameters)
  → provider request → provider response → ChatResult
```

### BaseChatModelConnection

The connection manages access to the provider service, such as its SDK client,
endpoint, credentials, and request options. Its `chat` implementation handles
one provider request:

1. Convert `ChatMessage` roles and content blocks into the provider's message
   format, including supported media, previous tool calls, and tool results.
   Reject unsupported content instead of silently dropping it.
2. Convert the supplied tools' names, descriptions, and input schemas into the
   provider's tool definitions.
3. Build and send the provider request using those messages, tool definitions,
   and the model parameters supplied by the setup.
4. Convert the response into a `ChatResult`, preserving the assistant's content
   and tool calls, plus model, token usage, and other metadata when available.

Return tool calls as part of the response; the framework's
[tool-calling loop]({{< ref "docs/development/calling-llms/chat#built-in-events-and-actions" >}})
executes them and invokes the model again with the results.

The following skeletons mark where to implement the provider-specific conversion
and SDK call:

{{< tabs "Custom BaseChatModelConnection" >}}
{{< tab "Python" >}}
```python
class MyChatModelConnection(BaseChatModelConnection):
    endpoint: str

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool] | None = None,
        output_schema: OutputSchema | None = None,
        **kwargs: Any,
    ) -> ChatResult:
        # This example does not implement native structured output.
        self._reject_unsupported_output_schema(output_schema)
        # 1. Convert messages and tool definitions to the provider's format.
        # 2. Build the request with kwargs (model, temperature, etc.).
        # 3. Send it using the provider client configured for self.endpoint.
        # 4. Convert the response content, tool calls, and metadata to ChatResult.
        raise NotImplementedError("Implement the provider request and conversion")
```
{{< /tab >}}
{{< tab "Java" >}}
```java
public class MyChatModelConnection extends BaseChatModelConnection {
    private final String endpoint;

    public MyChatModelConnection(
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.endpoint = descriptor.getArgument("endpoint");
        // Initialize the provider client with endpoint and authentication settings.
    }

    @Override
    public ChatResult chat(
            List<ChatMessage> messages, List<Tool> tools, Map<String, Object> arguments) {
        // 1. Convert messages and tool definitions to the provider's format.
        // 2. Build the request with arguments (model, temperature, etc.).
        // 3. Send it using the provider client.
        // 4. Convert the response content, tool calls, and metadata to ChatResult.
        throw new UnsupportedOperationException("Implement the provider request and conversion");
    }
}
```
{{< /tab >}}
{{< /tabs >}}

For native structured output, the connection must also translate the output
schema into the provider's request format and report support through
`supports_native_structured_output` (Python) or `supportsNativeStructuredOutput`
(Java). In Java, override the `chat` overload that accepts `outputSchema`;
in Python, handle the named `output_schema` argument separately from generation
parameters. The skeletons above leave native output unsupported. See
[Structured Output]({{< ref "docs/development/calling-llms/structured-output" >}})
for the available strategies.

### BaseChatModelSetup

The setup defines provider-specific model settings, their defaults, and any
validation required by the provider. For example, it can expose `temperature`,
token limits, or reasoning options alongside the inherited common settings.
Return these parameters from `model_kwargs` (Python) or `getParameters()` (Java);
the base setup passes them to the connection's `chat` method as `**kwargs` or an
argument map.

The base setup already handles common Resource references and prompt preparation.
Keep provider request construction and response conversion in the connection.
The example below adds a configurable `temperature` and passes it with `model`:

{{< tabs "Custom BaseChatModelSetup" >}}
{{< tab "Python" >}}
```python
class MyChatModelSetup(BaseChatModelSetup):
    temperature: float = 0.7

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        return {"model": self.model, "temperature": self.temperature}
```
{{< /tab >}}
{{< tab "Java" >}}
```java
public class MyChatModelSetup extends BaseChatModelSetup {
    private final double temperature;

    public MyChatModelSetup(
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        Number configuredTemperature = descriptor.getArgument("temperature", 0.7);
        this.temperature = configuredTemperature.doubleValue();
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("model", model);
        params.put("temperature", temperature);
        return params;
    }
}
```
{{< /tab >}}
{{< /tabs >}}

<span id="calling-chat-from-an-action"></span>

## Next Steps

- Follow [Chat with Models]({{< ref "docs/development/calling-llms/chat" >}}) to send messages to a model and handle its responses.
- Consult [Built-in Chat Model Providers]({{< ref "docs/development/calling-llms/chat-models/providers" >}}) for provider-specific configuration, dependencies, and capabilities.
- Use [Model Routing]({{< ref "docs/development/calling-llms/model_routing" >}}) to choose among registered models for each request.
