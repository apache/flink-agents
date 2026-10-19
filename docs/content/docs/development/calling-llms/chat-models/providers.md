---
title: "Built-in Chat Model Providers"
weight: 1
type: docs
aliases:
  - /docs/development/calling-llms/declare-resources/providers/
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

## Built-in Providers

For which providers and models enforce an `output_schema` natively, see [Structured Output]({{< ref "docs/development/calling-llms/structured-output#provider-support" >}}).

### Amazon Bedrock

Amazon Bedrock provides access to a wide range of foundation models from leading AI providers through a unified API. The Flink Agents Bedrock integration uses the [Converse API](https://docs.aws.amazon.com/bedrock/latest/userguide/conversation-inference.html), which provides a consistent interface across all supported models with native tool calling support. Authentication is handled via SigV4 using the AWS default credentials chain. No API keys are required.

{{< hint info >}}
Amazon Bedrock is only supported in Java currently. To use Amazon Bedrock from Python agents, see [Using Cross-Language Providers]({{< ref "docs/development/calling-llms/chat-models#using-cross-language-providers" >}}).
{{< /hint >}}

#### Prerequisites

1. An AWS account with [Amazon Bedrock model access](https://docs.aws.amazon.com/bedrock/latest/userguide/model-access.html) enabled for the models you plan to use
2. IAM credentials configured via any method supported by the [AWS Default Credentials Provider](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html) (environment variables, `~/.aws/credentials`, IAM role, etc.)

#### BedrockChatModelConnection Parameters

{{< tabs "BedrockChatModelConnection Parameters" >}}

{{< tab "Java" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `region` | String | `"us-east-1"` | AWS region for the Bedrock service |
| `model` | String | None | Default model ID (can be overridden per setup) |
| `max_retries` | int | `5` | Maximum number of API retry attempts (retries on throttling, 429, 503) |

{{< /tab >}}

{{< /tabs >}}

#### BedrockChatModelSetup Parameters

{{< tabs "BedrockChatModelSetup Parameters" >}}

{{< tab "Java" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | String | Required | Reference to connection method name |
| `model` | String | Required | Bedrock model ID (e.g. `"us.anthropic.claude-sonnet-4-20250514-v1:0"`) |
| `prompt` | Prompt \| String | None | Prompt template or reference to prompt resource |
| `tools` | List<String> | None | List of tool names available to the model |
| `temperature` | double | `0.1` | Sampling temperature (0.0 to 1.0) |
| `max_tokens` | int | None | Maximum number of tokens to generate |

{{< /tab >}}

{{< /tabs >}}

#### Usage Example

{{< tabs "Amazon Bedrock Usage Example" >}}

{{< tab "Java" >}}
```java
public class MyAgent extends Agent {
    @ChatModelConnection
    public static ResourceDescriptor bedrockConnection() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.BEDROCK_CONNECTION)
                .addInitialArgument("region", "us-east-1")
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor bedrockChatModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.BEDROCK_SETUP)
                .addInitialArgument("connection", "bedrockConnection")
                .addInitialArgument("model", "us.anthropic.claude-sonnet-4-20250514-v1:0")
                .addInitialArgument("temperature", 0.1d)
                .addInitialArgument("max_tokens", 4096)
                .build();
    }

    ...
}
```
{{< /tab >}}

{{< /tabs >}}

#### Available Models

Amazon Bedrock supports models from multiple providers through a single API. Visit the [Amazon Bedrock Model IDs documentation](https://docs.aws.amazon.com/bedrock/latest/userguide/models-supported.html) for the complete and up-to-date list of available models.

Some popular options include:
- **Claude** (Anthropic): `us.anthropic.claude-sonnet-4-6`, `us.anthropic.claude-opus-4-7`, `us.anthropic.claude-opus-4-6-v1`
- **Llama** (Meta): `us.meta.llama4-scout-17b-16e-instruct-v1:0`
- **Mistral**: `mistral.mistral-large-2402-v1:0`
- **Amazon Nova**: `us.amazon.nova-pro-v1:0`, `us.amazon.nova-lite-v1:0`

{{< hint warning >}}
Model availability varies by AWS region and requires explicit model access enablement in the Bedrock console. Always check the [Amazon Bedrock documentation](https://docs.aws.amazon.com/bedrock/latest/userguide/models-regions.html) for regional availability before implementing in production.
{{< /hint >}}

{{< hint warning >}}
**Current limitations:** The integration uses text content blocks only. Extended thinking / reasoning content blocks (e.g. Claude extended thinking), citation blocks, and image / document content blocks are not yet supported; a message with a media block raises `UnsupportedContentBlockException`.
{{< /hint >}}

### Anthropic

Anthropic provides cloud-based chat models featuring the Claude family, known for their strong reasoning, coding, and safety capabilities.

#### Prerequisites

1. Create an account at [Anthropic Console](https://console.anthropic.com/)
2. Navigate to [API Keys](https://console.anthropic.com/settings/keys) and create a new secret key

#### AnthropicChatModelConnection Parameters

{{< tabs "AnthropicChatModelConnection Parameters" >}}

{{< tab "Python" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `api_key` | str | Required | Anthropic API key for authentication |
| `max_retries` | int | `3` | Maximum number of API retry attempts |
| `timeout` | float | `60.0` | API request timeout in seconds |

{{< /tab >}}

{{< tab "Java" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `api_key` | String | Required | Anthropic API key for authentication |
| `timeout` | int | None | Timeout in seconds for API requests |
| `max_retries` | int | `2` | Maximum number of API retry attempts |

{{< /tab >}}

{{< /tabs >}}

#### AnthropicChatModelSetup Parameters

{{< tabs "AnthropicChatModelSetup Parameters" >}}

{{< tab "Python" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | str | Required | Reference to connection method name |
| `model` | str | `"claude-sonnet-4-6"` | Name of the chat model to use |
| `prompt` | Prompt \| str | None | Prompt template or reference to prompt resource |
| `tools` | List[str] | None | List of tool names available to the model |
| `max_tokens` | int | `1024` | Maximum number of tokens to generate |
| `temperature` | float | `0.1` | Sampling temperature (0.0 to 1.0) (not sent on Claude 4.7 and later, which reject a non-default sampling parameter) |
| `json_prefill` | bool | `False` | Prefill assistant response with "{" to enforce JSON output (applies only on models that accept assistant-message prefilling; disabled when tools are used, or when the request carries an `output_config`) |

{{< /tab >}}

{{< tab "Java" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | String | Required | Reference to connection method name |
| `model` | String | `"claude-sonnet-4-6"` | Name of the chat model to use |
| `prompt` | Prompt \| String | None | Prompt template or reference to prompt resource |
| `tools` | List<String> | None | List of tool names available to the model |
| `max_tokens` | long | `1024` | Maximum number of tokens to generate |
| `temperature` | double | `0.1` | Sampling temperature (0.0 to 1.0) (not sent on Claude 4.7 and later, which reject a non-default sampling parameter) |
| `json_prefill` | boolean | `false` | Prefill assistant response with "{" to enforce JSON output (applies only on models that accept assistant-message prefilling; disabled when tools are used, or when the request carries an `output_config`) |
| `strict_tools` | boolean | `false` | Enable strict mode for tool calling schemas |
| `additional_kwargs` | Map<String, Object> | `{}` | Additional Anthropic API parameters (`temperature`, `top_k`, `top_p`, `stop_sequences`); none of the three sampling parameters is sent on Claude 4.7 and later. A `temperature` supplied here takes precedence over the top-level one, and an `output_config` supplied here takes precedence over one derived from an output schema |

{{< /tab >}}

{{< /tabs >}}

#### Usage Example

{{< tabs "Anthropic Usage Example" >}}

{{< tab "Python" >}}
```python
class MyAgent(Agent):

    @chat_model_connection
    @staticmethod
    def anthropic_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.ANTHROPIC_CONNECTION,
            api_key="<your-api-key>",
            max_retries=3,
            timeout=60.0
        )

    @chat_model_setup
    @staticmethod
    def anthropic_chat_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.ANTHROPIC_SETUP,
            connection="anthropic_connection",
            model="claude-sonnet-4-6",
            max_tokens=2048,
            temperature=0.7
        )

    ...
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class MyAgent extends Agent {
    @ChatModelConnection
    public static ResourceDescriptor anthropicConnection() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.ANTHROPIC_CONNECTION)
                .addInitialArgument("api_key", "<your-api-key>")
                .addInitialArgument("timeout", 120)
                .addInitialArgument("max_retries", 3)
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor anthropicChatModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.ANTHROPIC_SETUP)
                .addInitialArgument("connection", "anthropicConnection")
                .addInitialArgument("model", "claude-sonnet-4-6")
                .addInitialArgument("temperature", 0.7d)
                .addInitialArgument("max_tokens", 2048)
                .build();
    }

    ...
}
```
{{< /tab >}}

{{< /tabs >}}

**Multimodal input.** The media blocks of a user message are sent as Anthropic content blocks, in block order, next to its text blocks:

| Block | Sent as |
|---|---|
| `TextBlock` | a `text` block |
| `ImageBlock` | an `image` block, by URL or Base64 data, for `image/jpeg`, `image/png`, `image/gif` or `image/webp` |
| `DocumentBlock` | a `document` block titled by the block's `name`: `application/pdf` by URL or Base64 data, or `text/plain` Base64 data sent as decoded UTF-8 text |
| `AudioBlock`, `VideoBlock` | not supported |

Other image or document types, plain text given by URL, audio, video, and media in assistant messages or tool results raise `UnsupportedContentBlockException` (Java) or `UnsupportedContentBlockError` (Python) before the request is sent.

#### Available Models

Visit the [Anthropic Models documentation](https://docs.anthropic.com/en/docs/about-claude/models) for the complete and up-to-date list of available chat models.

Some popular options include:
- **Claude Opus 4.6** (claude-opus-4-6)
- **Claude Sonnet 4.6** (claude-sonnet-4-6)
- **Claude Haiku 4.5** (claude-haiku-4-5)

{{< hint warning >}}
Model availability and specifications may change. Always check the official Anthropic documentation for the latest information before implementing in production.
{{< /hint >}}

### Azure OpenAI

Azure OpenAI provides access to OpenAI models (GPT-4, GPT-4o, etc.) through Azure's cloud infrastructure, using the same OpenAI SDK with Azure-specific authentication and endpoints. This offers enterprise security, compliance, and regional availability while using familiar OpenAI APIs.

{{< hint warning >}}
**Migrating from Azure AI:** `AzureAIChatModelConnection` and `AzureAIChatModelSetup` have been removed. Replace them with `OpenAICompletionsConnection` and `OpenAICompletionsSetup` (see [OpenAI](#openai)), pointed at a Microsoft Foundry OpenAI v1 endpoint. There are two declarations to change, not one.

On the connection, `AzureAIChatModelConnection` → `OpenAICompletionsConnection`:

- `endpoint` becomes `api_base_url`, **and the value changes shape, not just the key**: `https://<resource>.services.ai.azure.com/models` becomes `https://<resource>.openai.azure.com/openai/v1/`. `https://<resource>.services.ai.azure.com/openai/v1/` is also accepted. Carrying the old value over unchanged will not work.
- `apiKey` becomes `api_key`.

On the setup, `AzureAIChatModelSetup` → `OpenAICompletionsSetup`:

- Set `model` explicitly to your Foundry deployment name. Leaving it out is **not** reported as a configuration error: `OpenAICompletionsSetup` substitutes its own OpenAI default (`gpt-4o-mini`), and on the OpenAI v1 route that value is read as a deployment name. If no deployment on the resource carries that name, the request fails later at the provider; if one does, it is used silently in place of the deployment you meant.

In YAML, `clazz: azure` no longer resolves to anything. Use `clazz: openai_completions` with `type: java`, in both `chat_model_connections` and `chat_model_setups`.

The target is `OpenAICompletionsConnection` rather than `AzureOpenAIChatModelConnection` because the `/openai/v1/` route uses implicit versioning and takes no `api-version`, which `AzureOpenAIChatModelConnection` requires.
{{< /hint >}}

#### Prerequisites

1. Create an Azure OpenAI resource in the [Azure Portal](https://portal.azure.com/)
2. Deploy a model in [Azure OpenAI Studio](https://oai.azure.com/)
3. Obtain your endpoint URL, API key, API version, and deployment name from the Azure portal

#### AzureOpenAIChatModelConnection Parameters

{{< tabs "AzureOpenAIChatModelConnection Parameters" >}}

{{< tab "Python" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `api_key` | str | Required | Azure OpenAI API key for authentication |
| `api_version` | str | Required | Azure OpenAI REST API version (e.g., "2024-10-21"). See [API versions](https://learn.microsoft.com/en-us/azure/ai-services/openai/reference#rest-api-versioning) |
| `azure_endpoint` | str | Required | Azure OpenAI endpoint URL (e.g., `https://{resource-name}.openai.azure.com`) |
| `timeout` | float | `60.0` | API request timeout in seconds; `0` disables timeouts |
| `max_retries` | int | `3` | Maximum number of API retry attempts |

{{< /tab >}}

{{< tab "Java" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `api_key` | String | Required | Azure OpenAI API key for authentication |
| `api_version` | String | Required | Azure OpenAI REST API version (e.g., "2024-10-21"). See [API versions](https://learn.microsoft.com/en-us/azure/ai-services/openai/reference#rest-api-versioning) |
| `azure_endpoint` | String | Required | Azure OpenAI endpoint URL (e.g., `https://{resource-name}.openai.azure.com`) — either a direct Azure resource or a proxy/gateway URL that fronts an Azure OpenAI service |
| `timeout` | float | `60` | Timeout in seconds for API requests; `0` disables timeouts; must be 0–2,147,483.647 |
| `max_retries` | int | `3` | Maximum number of API retry attempts; must be non-negative |
| `azure_url_path_mode` | String | `"AUTO"` | Controls how the SDK constructs Azure OpenAI request URLs. One of `"AUTO"`, `"LEGACY"`, or `"UNIFIED"`. Custom gateways that proxy Azure OpenAI typically need `"LEGACY"` to force the `/openai/deployments/{model}` path |

{{< /tab >}}

{{< /tabs >}}

#### AzureOpenAIChatModelSetup Parameters

{{< tabs "AzureOpenAIChatModelSetup Parameters" >}}

{{< tab "Python" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | str | Required | Reference to connection method name |
| `model` | str | Required | Name of OpenAI model deployment on Azure |
| `model_of_azure_deployment` | str | None | The underlying model name (e.g., 'gpt-4', 'gpt-35-turbo'). Used for token metrics tracking, and to decide whether an `output_schema` is applied natively (see [Structured Output]({{< ref "docs/development/calling-llms/structured-output#provider-support" >}})) |
| `prompt` | Prompt \| str | None | Prompt template or reference to prompt resource |
| `tools` | List[str] | None | List of tool names available to the model |
| `temperature` | float | None | Sampling temperature (0.0 to 2.0). Not supported by reasoning models |
| `max_tokens` | int | None | Maximum number of tokens to generate |
| `logprobs` | bool | `False` | Whether to return log probabilities of output tokens |
| `additional_kwargs` | dict | `{}` | Additional Azure OpenAI API parameters |

{{< /tab >}}

{{< tab "Java" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | String | Required | Reference to connection method name |
| `model` | String | Required | Azure deployment name (not the underlying OpenAI model name) |
| `model_of_azure_deployment` | String | None | The underlying model name (e.g., 'gpt-4', 'gpt-4o'). Used for token metrics tracking, and to decide whether an `output_schema` is applied natively (see [Structured Output]({{< ref "docs/development/calling-llms/structured-output#provider-support" >}})) |
| `prompt` | Prompt \| String | None | Prompt template or reference to prompt resource |
| `tools` | List<String> | None | List of tool names available to the model |
| `temperature` | double | None | Sampling temperature (0.0 to 2.0). Not supported by reasoning models |
| `max_tokens` | int | None | Maximum number of tokens to generate (must be greater than 0) |
| `logprobs` | boolean | `false` | Whether to return log probabilities of output tokens |
| `additional_kwargs` | Map<String, Object> | `{}` | Additional Azure OpenAI API parameters (forwarded to the OpenAI request body) |

{{< /tab >}}

{{< /tabs >}}

#### Usage Example

{{< tabs "Azure OpenAI Usage Example" >}}

{{< tab "Python" >}}
```python
class MyAgent(Agent):

    @chat_model_connection
    @staticmethod
    def azure_openai_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.AZURE_OPENAI_CONNECTION,
            api_key="<your-api-key>",
            api_version="2024-10-21",
            azure_endpoint="https://your-resource.openai.azure.com"
        )

    @chat_model_setup
    @staticmethod
    def azure_openai_chat_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.AZURE_OPENAI_SETUP,
            connection="azure_openai_connection",
            model="my-gpt4-deployment",  # Your Azure deployment name
            model_of_azure_deployment="gpt-4",  # Underlying model for metrics
            max_tokens=1000
        )

    ...
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class MyAgent extends Agent {
    @ChatModelConnection
    public static ResourceDescriptor azureOpenAIConnection() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.AZURE_OPENAI_CONNECTION)
                .addInitialArgument("api_key", "<your-api-key>")
                .addInitialArgument("api_version", "2024-10-21")
                .addInitialArgument("azure_endpoint", "https://your-resource.openai.azure.com")
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor azureOpenAIChatModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.AZURE_OPENAI_SETUP)
                .addInitialArgument("connection", "azureOpenAIConnection")
                .addInitialArgument("model", "my-gpt4-deployment")          // Your Azure deployment name
                .addInitialArgument("model_of_azure_deployment", "gpt-4")   // Underlying model for metrics
                .addInitialArgument("temperature", 0.3d)
                .addInitialArgument("max_tokens", 1000)
                .build();
    }

    ...
}
```
{{< /tab >}}

{{< /tabs >}}

Azure OpenAI sends media blocks the same way as the OpenAI Chat Completions API; see [Multimodal Input](#multimodal-input).

#### Available Models

Azure OpenAI supports OpenAI models deployed through your Azure subscription. Visit the [Azure OpenAI Models documentation](https://learn.microsoft.com/en-us/azure/ai-services/openai/concepts/models) for the complete and up-to-date list of available models.

Some popular options include:
- **GPT-4o** (gpt-4o)
- **GPT-4** (gpt-4)
- **GPT-4 Turbo** (gpt-4-turbo)
- **GPT-3.5 Turbo** (gpt-35-turbo)

{{< hint warning >}}
Model availability depends on your Azure region and subscription. Always check the official Azure OpenAI documentation for regional availability before implementing in production.
{{< /hint >}}

### Gemini

Google Gemini provides cloud-based chat models through the Gemini Developer API and Vertex AI. The Flink Agents Gemini integration uses the official Google Gen AI SDK and supports text conversations, system instructions, and tool calling.

{{< hint warning >}}
Vertex AI support is experimental. The connection path has been smoke-tested during construction but has not yet been verified end to end.
{{< /hint >}}

{{< hint info >}}
Gemini is only supported in Java currently. To use Gemini from Python agents, see [Using Cross-Language Providers]({{< ref "docs/development/calling-llms/chat-models#using-cross-language-providers" >}}).
{{< /hint >}}

#### Prerequisites

1. For the Gemini Developer API, create an API key in [Google AI Studio](https://aistudio.google.com/app/apikey)
2. For Vertex AI, enable Vertex AI in your Google Cloud project and configure Google Cloud credentials

#### GeminiChatModelConnection Parameters

{{< tabs "GeminiChatModelConnection Parameters" >}}

{{< tab "Java" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `api_key` | String | Required unless `base_url` is set or `vertex_ai` is `true` | Gemini Developer API key |
| `base_url` | String | None | Custom endpoint, such as a proxy that injects credentials |
| `model` | String | None | Default model name, used when no model is supplied per setup |
| `timeout` | int | None | API request timeout in seconds |
| `vertex_ai` | boolean | `false` | Use the experimental Vertex AI backend; not yet verified end to end |
| `project` | String | None | Vertex AI project id |
| `location` | String | None | Vertex AI location |

{{< /tab >}}

{{< /tabs >}}

#### GeminiChatModelSetup Parameters

{{< tabs "GeminiChatModelSetup Parameters" >}}

{{< tab "Java" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | String | Required | Reference to connection method name |
| `model` | String | `"gemini-3.1-pro-preview"` | Name of the chat model to use |
| `prompt` | Prompt \| String | None | Prompt template or reference to prompt resource |
| `tools` | List<String> | None | List of tool names available to the model |
| `temperature` | double | `0.1` | Sampling temperature (0.0 to 2.0) |
| `max_output_tokens` | long | `1024` | Maximum number of tokens to generate |
| `additional_kwargs` | Map<String, Object> | `{}` | Additional Gemini parameters (`top_k`, `top_p`, `stop_sequences`) |

{{< /tab >}}

{{< /tabs >}}

#### Usage Example

{{< tabs "Gemini Usage Example" >}}

{{< tab "Java" >}}
```java
public class MyAgent extends Agent {
    @ChatModelConnection
    public static ResourceDescriptor geminiConnection() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.GEMINI_CONNECTION)
                .addInitialArgument("api_key", System.getenv("GEMINI_API_KEY"))
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor geminiChatModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.GEMINI_SETUP)
                .addInitialArgument("connection", "geminiConnection")
                .addInitialArgument("model", "gemini-3.1-pro-preview")
                .addInitialArgument("temperature", 0.1d)
                .addInitialArgument("max_output_tokens", 1024)
                .build();
    }

    ...
}
```
{{< /tab >}}

{{< /tabs >}}

#### Available Models

Visit the [Gemini models documentation](https://ai.google.dev/gemini-api/docs/models) for the complete and up-to-date list of available models.

{{< hint warning >}}
Model availability and names may differ between the Gemini Developer API and Vertex AI. Always check the official Gemini documentation before implementing in production.
{{< /hint >}}

### Ollama

Ollama provides local chat models that run on your machine, offering privacy, control, and no API costs.

#### Prerequisites

{{< hint info >}}
Ollama server **0.9.0** or higher is required.
{{< /hint >}}

1. Install Ollama from [https://ollama.com/](https://ollama.com/)
2. Start the Ollama server: `ollama serve`
3. Download a chat model: `ollama pull qwen3:8b`

#### OllamaChatModelConnection Parameters

{{< tabs "OllamaChatModelConnection Parameters" >}}

{{< tab "Python" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `base_url` | str | `"http://localhost:11434"` | Ollama server URL |
| `request_timeout` | float | `30.0` | HTTP request timeout in seconds |

{{< /tab >}}

{{< tab "Java" >}}

| Parameter        | Type   | Default                    | Description |
|------------------|--------|----------------------------|-------------|
| `endpoint`       | String | `"http://localhost:11434"` | Ollama server URL |
| `requestTimeout` | long   | `10`                       | HTTP request timeout in seconds |

{{< /tab >}}

{{< /tabs >}}

#### OllamaChatModelSetup Parameters

{{< tabs "OllamaChatModelSetup Parameters" >}}

{{< tab "Python" >}}

| Parameter           | Type                                     | Default  | Description                                     |
|---------------------|------------------------------------------|----------|-------------------------------------------------|
| `connection`        | str                                      | Required | Reference to connection method name             |
| `model`             | str                                      | Required | Name of the chat model to use                   |
| `prompt`            | Prompt \| str                            | None     | Prompt template or reference to prompt resource |
| `tools`             | List[str]                                | None     | List of tool names available to the model       |
| `temperature`       | float                                    | `0.75`   | Sampling temperature (0.0 to 1.0)               |
| `num_ctx`           | int                                      | `2048`   | Maximum number of context tokens                |
| `keep_alive`        | str \| float                             | `"5m"`   | How long to keep model loaded in memory         |
| `think`             | bool \| Literal["low", "medium", "high"] | True     | Whether enable model think                      |
| `extract_reasoning` | bool                                     | True     | Extract reasoning content from response         |
| `additional_kwargs` | dict                                     | `{}`     | Additional Ollama API parameters                |
{{< /tab >}}

{{< tab "Java" >}}

| Parameter           | Type                                        | Default  | Description                                     |
|---------------------|---------------------------------------------|----------|-------------------------------------------------|
| `connection`        | String                                      | Required | Reference to connection method name             |
| `model`             | String                                      | Required | Name of the chat model to use                   |
| `prompt`            | Prompt \| String                            | None     | Prompt template or reference to prompt resource |
| `tools`             | List[String]                                | None     | List of tool names available to the model       |
| `think`             | Boolean \| Literal["low", "medium", "high"] | true     | Whether enable model think                      |
| `extract_reasoning` | Boolean                                     | true     | Extract reasoning content from response         |

{{< /tab >}}

{{< /tabs >}}

#### Usage Example

{{< tabs "Ollama Usage Example" >}}

{{< tab "Python" >}}
```python
class MyAgent(Agent):

    @chat_model_connection
    @staticmethod
    def ollama_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_CONNECTION,
            base_url="http://localhost:11434",
            request_timeout=120.0
        )

    @chat_model_setup
    @staticmethod
    def my_chat_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OLLAMA_SETUP,
            connection="ollama_connection",
            model="qwen3:8b",
            temperature=0.7,
            num_ctx=4096,
            keep_alive="10m",
            think=True,
            extract_reasoning=True
        )

    ...
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class MyAgent extends Agent {
    @ChatModelConnection
    public static ResourceDescriptor ollamaConnection() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_CONNECTION)
                .addInitialArgument("endpoint", "http://localhost:11434")
                .addInitialArgument("requestTimeout", 120)
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor ollamaChatModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
                .addInitialArgument("connection", "ollamaConnection")
                .addInitialArgument("model", "qwen3:8b")
                .addInitialArgument("extract_reasoning", true)
                .build();
    }

    ...
}
```
{{< /tab >}}

{{< /tabs >}}


**Multimodal input.** The `ImageBlock`s of a user message are sent as the message's `images`, in block order, next to its text; Ollama's API does not interleave images with text. The images must be Base64 data, and the model must support vision. Images given by URL, other media, and media in system, assistant or tool messages raise `UnsupportedContentBlockException` (Java) or `UnsupportedContentBlockError` (Python) before the request is sent.

#### Available Models

Visit the [Ollama Models Library](https://ollama.com/library) for the complete and up-to-date list of available chat models.

Some popular options include:
- **qwen3** series (qwen3:8b, qwen3:14b, qwen3:32b)
- **llama3** series (llama3:8b, llama3:70b)
- **deepseek** series (deepseek-r1, deepseek-v3.1)
- **gpt-oss**

{{< hint warning >}}
Model availability and specifications may change. Always check the official Ollama documentation for the latest information before implementing in production.
{{< /hint >}}

### OpenAI

OpenAI provides cloud-based chat models with state-of-the-art performance for a wide range of natural language tasks.

#### Prerequisites

1. Create an account at [OpenAI Platform](https://platform.openai.com/)
2. Navigate to [API Keys](https://platform.openai.com/api-keys) and create a new secret key

#### Completions API

##### OpenAICompletionsConnection Parameters

{{< tabs "OpenAICompletionsConnection Parameters" >}}

{{< tab "Python" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `api_key` | str | Required | OpenAI API key for authentication |
| `api_base_url` | str | `"https://api.openai.com/v1"` | Base URL for OpenAI API |
| `max_retries` | int | `3` | Maximum number of API retry attempts |
| `timeout` | float | `60.0` | API request timeout in seconds; `0` disables timeouts |
| `default_headers` | dict | None | Default headers for API requests |
| `reuse_client` | bool | `True` | Whether to reuse the OpenAI client between requests |

{{< /tab >}}

{{< tab "Java" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `api_key` | String | Required | OpenAI API key for authentication |
| `api_base_url` | String | `"https://api.openai.com/v1"` | Base URL for OpenAI API |
| `max_retries` | int | `3` | Maximum number of API retry attempts; must be non-negative |
| `timeout` | float | `60` | Timeout in seconds for API requests; `0` disables timeouts; must be 0–2,147,483.647 |
| `default_headers` | Map<String, String> | None | Default headers for API requests |
| `model` | String | None | Default model to use if not specified in setup |

{{< /tab >}}

{{< /tabs >}}

##### OpenAICompletionsSetup Parameters

{{< tabs "OpenAICompletionsSetup Parameters" >}}

{{< tab "Python" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | str | Required | Reference to connection method name |
| `model` | str | `"gpt-4o-mini"` | Name of the chat model to use |
| `prompt` | Prompt \| str | None | Prompt template or reference to prompt resource |
| `tools` | List[str] | None | List of tool names available to the model |
| `temperature` | float | `0.1` | Sampling temperature (0.0 to 2.0) |
| `max_tokens` | int | None | Maximum number of tokens to generate |
| `logprobs` | bool | None | Whether to return log probabilities per token |
| `top_logprobs` | int | `0` | Number of top token log probabilities to return (0-20) |
| `strict` | bool | `False` | Enable strict mode for tool calling and schemas |
| `reasoning_effort` | str | None | Reasoning effort level for reasoning models ("low", "medium", "high") |
| `additional_kwargs` | dict | `{}` | Additional OpenAI API parameters |

{{< /tab >}}

{{< tab "Java" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | String | Required | Reference to connection method name |
| `model` | String | `"gpt-4o-mini"` | Name of the chat model to use |
| `prompt` | Prompt \| String | None | Prompt template or reference to prompt resource |
| `tools` | List<String> | None | List of tool names available to the model |
| `temperature` | double | `0.1` | Sampling temperature (0.0 to 2.0) |
| `max_tokens` | int | None | Maximum number of tokens to generate |
| `logprobs` | boolean | None | Whether to return log probabilities per token |
| `top_logprobs` | int | `0` | Number of top token log probabilities to return (0-20) |
| `strict` | boolean | `false` | Enable strict mode for tool calling and schemas |
| `reasoning_effort` | String | None | Reasoning effort level for reasoning models ("low", "medium", "high") |
| `additional_kwargs` | Map<String, Object> | `{}` | Additional OpenAI API parameters |

{{< /tab >}}

{{< /tabs >}}

##### Usage Example

{{< tabs "OpenAI Chat Completions Usage Example" >}}

{{< tab "Python" >}}
```python
class MyAgent(Agent):

    @chat_model_connection
    @staticmethod
    def openai_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OPENAI_COMPLETIONS_CONNECTION,
            api_key="<your-api-key>",
            api_base_url="https://api.openai.com/v1",
            max_retries=3,
            timeout=60.0
        )

    @chat_model_setup
    @staticmethod
    def openai_chat_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.OPENAI_COMPLETIONS_SETUP,
            connection="openai_connection",
            model="gpt-4",
            temperature=0.7,
            max_tokens=1000
        )

    ...
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class MyAgent extends Agent {
    @ChatModelConnection
    public static ResourceDescriptor openaiConnection() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OPENAI_COMPLETIONS_CONNECTION)
                .addInitialArgument("api_key", "<your-api-key>")
                .addInitialArgument("api_base_url", "https://api.openai.com/v1")
                .addInitialArgument("timeout", 60)
                .addInitialArgument("max_retries", 3)
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor openaiChatModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OPENAI_COMPLETIONS_SETUP)
                .addInitialArgument("connection", "openaiConnection")
                .addInitialArgument("model", "gpt-4")
                .addInitialArgument("temperature", 0.7d)
                .addInitialArgument("max_tokens", 1000)
                .build();
    }

    ...
}
```
{{< /tab >}}

{{< /tabs >}}

#### Multimodal Input

The Chat Completions connections (OpenAI, Azure OpenAI and vLLM) send the media blocks of a user message as content parts, in block order:

| Block | Sent as |
|---|---|
| `TextBlock` | a `text` part |
| `ImageBlock` | an `image_url` part: the URL, or a `data:` URI for Base64 data |
| `AudioBlock` | an `input_audio` part, for Base64 data in `audio/wav` or `audio/mpeg` (WAV or MP3) |
| `DocumentBlock` | a `file` part: the Base64 data as a `data:` URI, with the block's `name` as the file name (`document` when unset). OpenAI accepts PDF documents only |
| `VideoBlock` | not supported |

A user message with only text blocks is still sent as a plain string. Media in system, assistant or tool messages, audio or documents given by URL, other audio types, and video raise `UnsupportedContentBlockException` (Java) or `UnsupportedContentBlockError` (Python) before the request is sent. Whether a model accepts a given part depends on the model and the server.

The other built-in providers (Gemini, Amazon Bedrock, IBM watsonx.ai, DashScope and the OpenAI Responses API) do not send media yet: a message with a media block raises the same error instead of being sent as its text.

#### Responses API

{{< hint info >}}
Responses API is only supported in Java currently. To use OpenAI Responses API from Python agents, see [Using Cross-Language Providers]({{< ref "docs/development/calling-llms/chat-models#using-cross-language-providers" >}}).
{{< /hint >}}

##### OpenAIResponsesModelConnection Parameters

{{< tabs "OpenAIResponsesModelConnection Parameters" >}}

{{< tab "Java" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `api_key` | String | Required | OpenAI API key for authentication |
| `api_base_url` | String | None | Base URL for OpenAI API (useful for proxies) |
| `max_retries` | int | `3` | Maximum number of API retry attempts; must be non-negative |
| `timeout` | float | `60` | Timeout in seconds for API requests; `0` disables timeouts; must be 0–2,147,483.647 |
| `default_headers` | Map<String, String> | None | Default headers for API requests |
| `model` | String | None | Default model to use if not specified in setup |

{{< /tab >}}

{{< /tabs >}}

##### OpenAIResponsesModelSetup Parameters

{{< tabs "OpenAIResponsesModelSetup Parameters" >}}

{{< tab "Java" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | String | Required | Reference to connection method name |
| `model` | String | `"gpt-4o"` | Name of the chat model to use |
| `prompt` | Prompt \| String | None | Prompt template or reference to prompt resource |
| `tools` | List<String> | None | List of tool names available to the model |
| `temperature` | double | `0.1` | Sampling temperature (0.0 to 2.0) |
| `max_tokens` | int | None | Maximum number of tokens to generate |
| `strict` | boolean | `false` | Enable strict mode for tool calling schemas |
| `reasoning_effort` | String | None | Reasoning effort level for reasoning models ("low", "medium", "high") |
| `store` | boolean | `false` | Whether to store the response for later retrieval |
| `instructions` | String | None | System-level instructions for the model |
| `additional_kwargs` | Map<String, Object> | `{}` | Additional Responses API parameters |

{{< /tab >}}

{{< /tabs >}}

##### Usage Example

{{< tabs "OpenAI Responses API Usage Example" >}}

{{< tab "Java" >}}
```java
public class MyAgent extends Agent {
    @ChatModelConnection
    public static ResourceDescriptor openaiResponsesConnection() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OPENAI_RESPONSES_CONNECTION)
                .addInitialArgument("api_key", "<your-api-key>")
                .addInitialArgument("timeout", 120)
                .addInitialArgument("max_retries", 3)
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor openaiResponsesChatModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.OPENAI_RESPONSES_SETUP)
                .addInitialArgument("connection", "openaiResponsesConnection")
                .addInitialArgument("model", "gpt-4o")
                .addInitialArgument("temperature", 0.3d)
                .addInitialArgument("max_tokens", 2048)
                .addInitialArgument("store", true)
                .build();
    }

    ...
}
```
{{< /tab >}}

{{< /tabs >}}

#### Available Models

Visit the [OpenAI Models documentation](https://platform.openai.com/docs/models) for the complete and up-to-date list of available chat models.

Some popular options include:
- **GPT-5** series (GPT-5, GPT-5 mini, GPT-5 nano)
- **GPT-4.1**
- **gpt-oss** series (gpt-oss-120b, gpt-oss-10b)

{{< hint warning >}}
Model availability and specifications may change. Always check the official OpenAI documentation for the latest information before implementing in production.
{{< /hint >}}

### Alibaba Cloud Model Studio (DashScope)

This integration connects to Alibaba Cloud Model Studio (Bailian) through the DashScope SDK to access chat models.

{{< hint info >}}
The DashScope integration is currently supported in the Python API only. To use it from Java agents, see [Using Cross-Language Providers]({{< ref "docs/development/calling-llms/chat-models#using-cross-language-providers" >}}).
{{< /hint >}}

#### Prerequisites

1. Get an API key from [Alibaba Cloud Model Studio](https://dashscope.aliyun.com/)

#### DashScopeChatModelConnection Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `api_key` | str | `$DASHSCOPE_API_KEY` | DashScope API key for authentication |
| `request_timeout` | float | `60.0` | HTTP request timeout in seconds |

#### DashScopeChatModelSetup Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | str | Required | Reference to connection method name |
| `model` | str | `"qwen-plus"` | Name of the chat model to use (an `output_schema` is enforced natively only on `qwen3.7-max`, `qwen3.7-max-preview`, `qwen3.7-max-2026-05-17` and `qwen3.7-max-2026-05-20`, the schema-capable models DashScope serves on the text-generation interface this connection calls; every other model, the `qwen-plus` default included, falls back to prompting) |
| `prompt` | Prompt \| str | None | Prompt template or reference to prompt resource |
| `tools` | List[str] | None | List of tool names available to the model |
| `temperature` | float | `0.7` | Sampling temperature (0.0 to 2.0) |
| `extract_reasoning` | bool | `False` | Extract reasoning content from response |
| `additional_kwargs` | dict | `{}` | Additional DashScope API parameters. A `response_format` supplied here is rejected only when it would be overwritten, that is when an `output_schema` carrying a Pydantic model is set on a model that applies it natively; otherwise it is passed through unchanged |

#### Usage Example

```python
class MyAgent(Agent):

    @chat_model_connection
    @staticmethod
    def dashscope_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.DASHSCOPE_CONNECTION,
            api_key="your-api-key-here",  # Or set DASHSCOPE_API_KEY env var
            request_timeout=60.0
        )

    @chat_model_setup
    @staticmethod
    def dashscope_chat_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.DASHSCOPE_SETUP,
            connection="dashscope_connection",
            model="qwen-plus",
            temperature=0.7,
            extract_reasoning=True
        )

    ...
```

#### Available Models

Visit the [Alibaba Cloud Model Studio models documentation](https://help.aliyun.com/zh/dashscope/developer-reference/model-introduction) for the complete and up-to-date list of available chat models.

Some popular options include:
- **qwen-plus**
- **qwen-max**
- **qwen-turbo**
- **qwen-long**

{{< hint warning >}}
Model availability and specifications may change. Always check the official Alibaba Cloud Model Studio documentation for the latest information before implementing in production.
{{< /hint >}}

### vLLM

[vLLM](https://docs.vllm.ai) serves open-weight models behind an OpenAI-compatible API and is a popular choice for self-hosted production deployments. Flink Agents provides a dedicated connection that reuses the OpenAI integration with vLLM-friendly defaults, in both Java and Python.

#### Prerequisites

1. Install vLLM and start a server. For agent use, enable automatic tool calling — Flink Agents sends tools without a named `tool_choice`, so the server needs `--enable-auto-tool-choice` plus a model-specific `--tool-call-parser` (for Qwen2.5, vLLM recommends `hermes`):

   ```bash
   vllm serve Qwen/Qwen2.5-7B-Instruct --enable-auto-tool-choice --tool-call-parser hermes
   ```

   Without these flags the server can chat but tool calls are not parsed into the OpenAI `tool_calls` field, so the model cannot drive an agent's tools. The parser is model-specific; see the [vLLM tool calling docs](https://docs.vllm.ai/en/stable/features/tool_calling/).
2. By default the server listens on `http://localhost:8000` and requires no API key. If the server is started with `--api-key`, pass the same key in the connection.

#### VLLMChatModelConnection Parameters

{{< tabs "VLLMChatModelConnection Parameters" >}}

{{< tab "Python" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `api_base_url` | str | `"http://localhost:8000/v1"` | vLLM server URL |
| `api_key` | str | `"EMPTY"` | Only needed when the server is started with `--api-key`; the placeholder default works for unauthenticated servers. Unlike the OpenAI connection, the `OPENAI_API_KEY` / `OPENAI_API_BASE_URL` environment variables are not consulted |
| `timeout` | float | `60.0` | HTTP request timeout in seconds |
| `max_retries` | int | `3` | Maximum number of API retries |

{{< /tab >}}

{{< tab "Java" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `api_base_url` | String | `"http://localhost:8000/v1"` | vLLM server URL |
| `api_key` | String | `"EMPTY"` | Only needed when the server is started with `--api-key`; the placeholder default works for unauthenticated servers |
| `timeout` | int | SDK default | Seconds before an API call times out |
| `max_retries` | int | SDK default | Retry attempts on failure |

{{< /tab >}}

{{< /tabs >}}

#### VLLMChatModelSetup Parameters

Same as the [OpenAI Completions setup](#openaicompletionssetup-parameters), with one difference: `model` is **required** and has no default — it must match the model name served by the vLLM server (see `vllm serve <model>`, or query `GET /v1/models`).

#### Usage Example

{{< tabs "vLLM Usage Example" >}}

{{< tab "Python" >}}

```python
class MyAgent(Agent):

    @chat_model_connection
    @staticmethod
    def vllm_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.VLLM_CONNECTION,
            api_base_url="http://localhost:8000/v1",
        )

    @chat_model_setup
    @staticmethod
    def vllm_chat_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.VLLM_SETUP,
            connection="vllm_connection",
            model="Qwen/Qwen2.5-7B-Instruct",
            temperature=0.3,
        )

    ...
```

{{< /tab >}}

{{< tab "Java" >}}

```java
public class MyAgent extends Agent {

    @ChatModelConnection
    public static ResourceDescriptor vllmConnection() {
        return ResourceDescriptor.Builder.newBuilder(
                        ResourceName.ChatModel.VLLM_CONNECTION)
                .addInitialArgument("api_base_url", "http://localhost:8000/v1")
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor vllmChatModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.VLLM_SETUP)
                .addInitialArgument("connection", "vllmConnection")
                .addInitialArgument("model", "Qwen/Qwen2.5-7B-Instruct")
                .addInitialArgument("temperature", 0.3d)
                .build();
    }

    // ...
}
```

{{< /tab >}}

{{< /tabs >}}

vLLM receives media blocks the same way as the OpenAI Chat Completions API; see [Multimodal Input](#multimodal-input). The model must be multimodal; see the [vLLM multimodal inputs docs](https://docs.vllm.ai/en/stable/features/multimodal_inputs/).

#### Available Models

A vLLM server serves the model(s) it was started with. Query `GET /v1/models` on the server to list them; the `model` value in the setup must match one of the returned names.

### Watsonx (IBM watsonx.ai)

IBM watsonx.ai provides cloud-based chat models, including the IBM Granite series, with enterprise-grade governance and deployment options on IBM Cloud.

#### Prerequisites

1. Create an account on [IBM Cloud](https://cloud.ibm.com/) and provision a [watsonx.ai](https://www.ibm.com/products/watsonx-ai) instance
2. Create a watsonx.ai project or deployment space
2. On Developer access, select your Project ID and create API Key
4. Note the generated API Key, Project ID and watsonx.ai URL for configuring your connection

#### WatsonxChatModelConnection Parameters

{{< tabs "WatsonxChatModelConnection Parameters" >}}

{{< tab "Python" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `url` | str | `$WATSONX_URL` | watsonx.ai service endpoint of your region |
| `api_key` | str | `$WATSONX_API_KEY` | IBM Cloud API key; configure exactly one of `api_key` and `token` |
| `token` | str | `$WATSONX_TOKEN` | Caller-provided bearer token; it is not refreshed automatically and must remain valid for the job lifetime; configure exactly one of `api_key` and `token` |
| `project_id` | str | `$WATSONX_PROJECT_ID` | watsonx.ai project id (or use `space_id`) |
| `space_id` | str | `$WATSONX_SPACE_ID` | Deployment space id, as an alternative to `project_id` |
| `request_timeout` | float | `120.0` | HTTP request timeout in seconds |
| `max_retries` | int | `3` | Maximum retries for transport failures and HTTP 408, 429, 500, 502, 503, and 504 responses |

{{< /tab >}}

{{< tab "Java" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `url` | String | `$WATSONX_URL` | watsonx.ai service endpoint of your region |
| `api_key` | String | `$WATSONX_API_KEY` | IBM Cloud API key; configure exactly one of `api_key` and `token` |
| `token` | String | `$WATSONX_TOKEN` | Caller-provided bearer token; it is not refreshed automatically and must remain valid for the job lifetime; configure exactly one of `api_key` and `token` |
| `project_id` | String | `$WATSONX_PROJECT_ID` | watsonx.ai project id (or use `space_id`) |
| `space_id` | String | `$WATSONX_SPACE_ID` | Deployment space id, as an alternative to `project_id` |
| `api_version` | String | `"2025-04-23"` | watsonx.ai REST API version date |
| `iam_url` | String | `"https://iam.cloud.ibm.com"` | IAM endpoint used to exchange the API key for a token |
| `request_timeout` | double | `120.0` | HTTP request timeout in seconds |
| `max_retries` | int | `3` | Maximum retries for transport failures and HTTP 408, 429, 500, 502, 503, and 504 responses |

{{< /tab >}}

{{< /tabs >}}

#### WatsonxChatModelSetup Parameters

{{< tabs "WatsonxChatModelSetup Parameters" >}}

{{< tab "Python" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | str | Required | Reference to connection method name |
| `model` | str | `"ibm/granite-4-h-small"` | watsonx.ai model id to use |
| `prompt` | Prompt \| str | None | Prompt template or reference to prompt resource |
| `tools` | List[str] | None | List of tool names available to the model |
| `temperature` | float | `0.1` | Sampling temperature (0.0 to 2.0) |
| `max_tokens` | int | None | Maximum number of tokens to generate |
| `extract_reasoning` | bool | `False` | Extract reasoning content (e.g. `<think>` blocks) from response |
| `additional_kwargs` | dict | `{}` | Additional watsonx.ai chat parameters (e.g. `top_p`, `time_limit`, `seed`) |

{{< /tab >}}

{{< tab "Java" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | String | Required | Reference to connection method name |
| `model` | String | `"ibm/granite-4-h-small"` | watsonx.ai model id to use |
| `prompt` | Prompt \| String | None | Prompt template or reference to prompt resource |
| `tools` | List[String] | None | List of tool names available to the model |
| `temperature` | Double | `0.1` | Sampling temperature (0.0 to 2.0) |
| `max_tokens` | Integer | None | Maximum number of tokens to generate |
| `extract_reasoning` | Boolean | `false` | Extract reasoning content (e.g. `<think>` blocks) from response |
| `additional_kwargs` | Map | `{}` | Additional watsonx.ai chat parameters (e.g. `top_p`, `time_limit`, `seed`) |

{{< /tab >}}

{{< /tabs >}}

#### Usage Example

{{< tabs "Watsonx Usage Example" >}}

{{< tab "Python" >}}
```python
class MyAgent(Agent):

    @chat_model_connection
    @staticmethod
    def watsonx_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.WATSONX_CONNECTION,
            url="https://us-south.ml.cloud.ibm.com",  # set WATSONX_URL env var
            api_key="your-api-key-here",  # set WATSONX_API_KEY env var
            project_id="your-project-id",  # set WATSONX_PROJECT_ID env var
        )

    @chat_model_setup
    @staticmethod
    def watsonx_chat_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=ResourceName.ChatModel.WATSONX_SETUP,
            connection="watsonx_connection",
            model="ibm/granite-4-h-small",
            temperature=0.1,
            max_tokens=1024
        )

    ...
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class MyAgent extends Agent {
    @ChatModelConnection
    public static ResourceDescriptor watsonxConnection() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.WATSONX_CONNECTION)
                .addInitialArgument("url", "https://us-south.ml.cloud.ibm.com")
                .addInitialArgument("api_key", "your-api-key-here")
                .addInitialArgument("project_id", "your-project-id")
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor watsonxChatModel() {
        return ResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.WATSONX_SETUP)
                .addInitialArgument("connection", "watsonxConnection")
                .addInitialArgument("model", "ibm/granite-4-h-small")
                .addInitialArgument("temperature", 0.1)
                .build();
    }

    ...
}
```
{{< /tab >}}

{{< /tabs >}}

#### Available Models

Visit the [watsonx.ai foundation models documentation](https://www.ibm.com/products/watsonx-ai/foundation-models) for the complete and up-to-date list of available chat models.

Some popular options include:
- **ibm/granite** series (ibm/granite-4-h-small, ibm/granite-3-3-8b-instruct)
- **meta-llama** series (meta-llama/llama-3-3-70b-instruct)
- **mistralai** series (mistralai/mistral-large)

{{< hint warning >}}
Model availability and specifications may change. Always check the official IBM watsonx.ai documentation for the latest information before implementing in production.
{{< /hint >}}
