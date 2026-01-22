---
title: Chat Models
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

# Chat Models

## Overview

Chat models enable agents to communicate with Large Language Models (LLMs) for natural language understanding, reasoning, and generation. In Flink Agents, chat models act as the "brain" of your agents, processing input messages and generating intelligent responses based on context, prompts, and available tools.

## Getting Started

To use chat models in your agents, you need to define both a connection and setup using decorators/annotations, then interact with the model through events.

### Resource Declaration

Flink Agents provides decorators in python and annotations in java to simplify chat model setup within agents:

#### @chat_model_connection/@ChatModelConnection

The `@chat_model_connection` decorator or `@ChatModelConnection` annotation marks a method that creates a chat model connection. This is typically defined once and shared across multiple chat model setups.

#### @chat_model_setup/@ChatModelSetup

The `@chat_model_setup` decorator or `@ChatModelSetup` annotation marks a method that creates a chat model setup. This references a connection and adds chat-specific configuration like prompts and tools.

### Chat Events

Chat models communicate through built-in events:

- **ChatRequestEvent**: Sent by actions to request a chat completion from the LLM
- **ChatResponseEvent**: Received by actions containing the LLM's response

### Usage Example

Here's how to define and use chat models in a workflow agent:

{{< tabs "Usage Example" >}}

{{< tab "Python" >}}
```python
class MyAgent(Agent):

    @chat_model_connection
    @staticmethod
    def ollama_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=Constant.OLLAMA_CHAT_MODEL_CONNECTION,
            base_url="http://localhost:11434",
            request_timeout=30.0
        )

    @chat_model_setup
    @staticmethod
    def ollama_chat_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=Constant.OLLAMA_CHAT_MODEL_SETUP,
            connection="ollama_connection",
            model="qwen3:8b",
            temperature=0.7
        )

    @action(InputEvent)
    @staticmethod
    def process_input(event: InputEvent, ctx: RunnerContext) -> None:
        # Create a chat request with user message
        user_message = ChatMessage(
            role=MessageRole.USER,
            content=f"input: {event.input}"
        )
        ctx.send_event(
            ChatRequestEvent(model="ollama_chat_model", messages=[user_message])
        )

    @action(ChatResponseEvent)
    @staticmethod
    def process_response(event: ChatResponseEvent, ctx: RunnerContext) -> None:
        response_content = event.response.content
        # Handle the LLM's response
        # Process the response as needed for your use case
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class MyAgent extends Agent {
    @ChatModelConnection
    public static ResourceDescriptor ollamaConnection() {
        return ResourceDescriptor.Builder.newBuilder(Constant.OLLAMA_CHAT_MODEL_CONNECTION)
                .addInitialArgument("endpoint", "http://localhost:11434")
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor ollamaChatModel() {
        return ResourceDescriptor.Builder.newBuilder(Constant.OLLAMA_CHAT_MODEL_SETUP)
                .addInitialArgument("connection", "ollamaConnection")
                .addInitialArgument("model", "qwen3:8b")
                .build();
    }

    @Action(listenEvents = {InputEvent.class})
    public static void processInput(InputEvent event, RunnerContext ctx) throws Exception {
        ChatMessage userMessage =
                new ChatMessage(MessageRole.USER, String.format("input: {%s}", event.getInput()));
        ctx.sendEvent(new ChatRequestEvent("ollamaChatModel", List.of(userMessage)));
    }

    @Action(listenEvents = {ChatResponseEvent.class})
    public static void processResponse(ChatResponseEvent event, RunnerContext ctx)
            throws Exception {
        String response = event.getResponse().getContent();
        // Handle the LLM's response
        // Process the response as needed for your use case
    }
}
```
{{< /tab >}}

{{< /tabs >}}


## Built-in Providers

### Azure AI

Azure AI provides cloud-based chat models through Azure AI Inference API, supporting various models including GPT-4, GPT-4o, and other Azure-hosted models.

{{< hint warning >}}
Azure AI is only supported in Java currently.
{{< /hint >}}

#### Prerequisites

1. Create an Azure AI resource in the [Azure Portal](https://portal.azure.com/)
2. Obtain your endpoint URL and API key from the Azure AI resource

#### AzureAIChatModelConnection Parameters

{{< tabs "AzureAIChatModelConnection Parameters" >}}

{{< tab "Java" >}}

| Parameter | Type   | Default  | Description                            |
|-----------|--------|----------|----------------------------------------|
| `endpoint` | String | Required | Azure AI service endpoint URL          |
| `apiKey`   | String | Required | Azure AI API key for authentication   |

{{< /tab >}}

{{< /tabs >}}

#### AzureAIChatModelSetup Parameters

{{< tabs "AzureAIChatModelSetup Parameters" >}}

{{< tab "Java" >}}

| Parameter    | Type             | Default  | Description                                      |
|--------------|------------------|----------|--------------------------------------------------|
| `connection` | String           | Required | Reference to connection method name              |
| `model`      | String           | Required | Name of the chat model to use (e.g., "gpt-4o")   |
| `prompt`     | Prompt \| String | None     | Prompt template or reference to prompt resource  |
| `tools`      | List[String]     | None     | List of tool names available to the model        |

{{< /tab >}}

{{< /tabs >}}

#### Usage Example

{{< tabs "Azure AI Usage Example" >}}

{{< tab "Java" >}}
```java
public class MyAgent extends Agent {
    @ChatModelConnection
    public static ResourceDescriptor azureAIConnection() {
        return ResourceDescriptor.Builder.newBuilder(Constant.AZURE_CHAT_MODEL_CONNECTION)
                .addInitialArgument("endpoint", "https://your-resource.inference.ai.azure.com")
                .addInitialArgument("apiKey", "your-api-key-here")
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor azureAIChatModel() {
        return ResourceDescriptor.Builder.newBuilder(Constant.AZURE_CHAT_MODEL_SETUP)
                .addInitialArgument("connection", "azureAIConnection")
                .addInitialArgument("model", "gpt-4o")
                .build();
    }
    
    ...
}
```
{{< /tab >}}

{{< /tabs >}}

#### Available Models

Azure AI supports various models through the Azure AI Inference API. Visit the [Azure AI Model Catalog](https://ai.azure.com/explore/models) for the complete and up-to-date list of available models.

Some popular options include:
- **GPT-4o** (gpt-4o)
- **GPT-4** (gpt-4)
- **GPT-4 Turbo** (gpt-4-turbo)
- **GPT-3.5 Turbo** (gpt-3.5-turbo)

{{< hint warning >}}
Model availability and specifications may change. Always check the official Azure AI documentation for the latest information before implementing in production.
{{< /hint >}}

### Anthropic

Anthropic provides cloud-based chat models featuring the Claude family, known for their strong reasoning, coding, and safety capabilities.

{{< hint warning >}}
Anthropic is only supported in python currently.
{{< /hint >}}

#### Prerequisites

1. Get an API key from [Anthropic Console](https://console.anthropic.com/)

#### AnthropicChatModelConnection Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `api_key` | str | Required | Anthropic API key for authentication |
| `max_retries` | int | `3` | Maximum number of API retry attempts |
| `timeout` | float | `60.0` | API request timeout in seconds |

#### AnthropicChatModelSetup Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | str | Required | Reference to connection method name |
| `model` | str | `"claude-sonnet-4-20250514"` | Name of the chat model to use |
| `prompt` | Prompt \| str | None | Prompt template or reference to prompt resource |
| `tools` | List[str] | None | List of tool names available to the model |
| `max_tokens` | int | `1024` | Maximum number of tokens to generate |
| `temperature` | float | `0.1` | Sampling temperature (0.0 to 1.0) |
| `additional_kwargs` | dict | `{}` | Additional Anthropic API parameters |

#### Usage Example

```python
class MyAgent(Agent):

    @chat_model_connection
    @staticmethod
    def anthropic_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=Constant.ANTHROPIC_CHAT_MODEL_CONNECTION,
            api_key="your-api-key-here",  # Or set ANTHROPIC_API_KEY env var
            max_retries=3,
            timeout=60.0
        )

    @chat_model_setup
    @staticmethod
    def anthropic_chat_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=Constant.ANTHROPIC_CHAT_MODEL_SETUP,
            connection="anthropic_connection",
            model="claude-sonnet-4-20250514",
            max_tokens=2048,
            temperature=0.7
        )

    ...
```

#### Available Models

Visit the [Anthropic Models documentation](https://docs.anthropic.com/en/docs/about-claude/models) for the complete and up-to-date list of available chat models.

Some popular options include:
- **Claude Sonnet 4.5** (claude-sonnet-4-5-20250929)
- **Claude Sonnet 4** (claude-sonnet-4-20250514)
- **Claude Sonnet 3.7** (claude-3-7-sonnet-20250219)
- **Claude Opus 4.1** (claude-opus-4-1-20250805)

{{< hint warning >}}
Model availability and specifications may change. Always check the official Anthropic documentation for the latest information before implementing in production.
{{< /hint >}}

### Ollama

Ollama provides local chat models that run on your machine, offering privacy, control, and no API costs.

#### Prerequisites

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
| `extract_reasoning` | bool                                     | `True`   | Extract reasoning content from response         |
| `additional_kwargs` | dict                                     | `{}`     | Additional Ollama API parameters                |
| `think`             | bool \| Literal["low", "medium", "high"] | True     | Whether enable model think                      |
{{< /tab >}}

{{< tab "Java" >}}

| Parameter | Type             | Default | Description |
|-----------|------------------|---------|-------------|
| `connection` | String           | Required | Reference to connection method name |
| `model` | String           | Required | Name of the chat model to use |
| `prompt` | Prompt \| String | None | Prompt template or reference to prompt resource |
| `tools` | List[String]     | None | List of tool names available to the model |
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
            clazz=Constant.OLLAMA_CHAT_MODEL_CONNECTION,
            base_url="http://localhost:11434",
            request_timeout=120.0
        )

    @chat_model_setup
    @staticmethod
    def my_chat_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=Constant.OLLAMA_CHAT_MODEL_CONNECTION,
            connection="ollama_connection",
            model="qwen3:8b",
            temperature=0.7,
            num_ctx=4096,
            keep_alive="10m",
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
        return ResourceDescriptor.Builder.newBuilder(Constant.OLLAMA_CHAT_MODEL_CONNECTION)
                .addInitialArgument("endpoint", "http://localhost:11434")
                .addInitialArgument("requestTimeout", 120)
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor ollamaChatModel() {
        return ResourceDescriptor.Builder.newBuilder(Constant.OLLAMA_CHAT_MODEL_SETUP)
                .addInitialArgument("connection", "ollamaConnection")
                .addInitialArgument("model", "qwen3:8b")
                .build();
    }
    
    ...
}
```
{{< /tab >}}

{{< /tabs >}}


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

1. Get an API key from [OpenAI Platform](https://platform.openai.com/)
2. Set the API key as an environment variable: `export OPENAI_API_KEY=your-api-key`

#### OpenAIChatModelConnection Parameters

{{< tabs "OpenAIChatModelConnection Parameters" >}}

{{< tab "Python" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `api_key` | str | `$OPENAI_API_KEY` | OpenAI API key for authentication |
| `api_base_url` | str | `"https://api.openai.com/v1"` | Base URL for OpenAI API |
| `max_retries` | int | `3` | Maximum number of API retry attempts |
| `timeout` | float | `60.0` | API request timeout in seconds |
| `default_headers` | dict | None | Default headers for API requests |
| `reuse_client` | bool | `True` | Whether to reuse the OpenAI client between requests |

{{< /tab >}}

{{< tab "Java" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `api_key` | String | Required | OpenAI API key for authentication |
| `api_base_url` | String | `"https://api.openai.com/v1"` | Base URL for OpenAI API |
| `max_retries` | int | `2` | Maximum number of API retry attempts |
| `timeout` | int | None | Timeout in seconds for API requests |
| `default_headers` | Map<String, String> | None | Default headers for API requests |
| `model` | String | None | Default model to use if not specified in setup |

{{< /tab >}}

{{< /tabs >}}

#### OpenAIChatModelSetup Parameters

{{< tabs "OpenAIChatModelSetup Parameters" >}}

{{< tab "Python" >}}

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | str | Required | Reference to connection method name |
| `model` | str | `"gpt-3.5-turbo"` | Name of the chat model to use |
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
| `model` | String | `"gpt-3.5-turbo"` | Name of the chat model to use |
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

#### Usage Example

{{< tabs "OpenAI Usage Example" >}}

{{< tab "Python" >}}
```python
class MyAgent(Agent):

    @chat_model_connection
    @staticmethod
    def openai_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=Constant.OPENAI_CHAT_MODEL_CONNECTION,
            api_key="your-api-key-here",  # Or set OPENAI_API_KEY env var
            api_base_url="https://api.openai.com/v1",
            max_retries=3,
            timeout=60.0
        )

    @chat_model_setup
    @staticmethod
    def openai_chat_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=Constant.OPENAI_CHAT_MODEL_SETUP,
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
        return ResourceDescriptor.Builder.newBuilder(OpenAIChatModelConnection.class.getName())
                .addInitialArgument("api_key", System.getenv("OPENAI_API_KEY"))
                .addInitialArgument("api_base_url", "https://api.openai.com/v1")
                .addInitialArgument("timeout", 60)
                .addInitialArgument("max_retries", 3)
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor openaiChatModel() {
        return ResourceDescriptor.Builder.newBuilder(OpenAIChatModelSetup.class.getName())
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

#### Available Models

Visit the [OpenAI Models documentation](https://platform.openai.com/docs/models) for the complete and up-to-date list of available chat models.

Some popular options include:
- **GPT-5** series (GPT-5, GPT-5 mini, GPT-5 nano)
- **GPT-4.1**
- **gpt-oss** series (gpt-oss-120b, gpt-oss-10b)

{{< hint warning >}}
Model availability and specifications may change. Always check the official OpenAI documentation for the latest information before implementing in production.
{{< /hint >}}

### Tongyi (DashScope)

Tongyi provides cloud-based chat models from Alibaba Cloud, offering powerful Chinese and English language capabilities.

{{< hint warning >}}
Tongyi is only supported in python currently.
{{< /hint >}}

#### Prerequisites

1. Get an API key from [Alibaba Cloud DashScope](https://dashscope.aliyun.com/)

#### TongyiChatModelConnection Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `api_key` | str | `$DASHSCOPE_API_KEY` | DashScope API key for authentication |
| `request_timeout` | float | `60.0` | HTTP request timeout in seconds |

#### TongyiChatModelSetup Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | str | Required | Reference to connection method name |
| `model` | str | `"qwen-plus"` | Name of the chat model to use |
| `prompt` | Prompt \| str | None | Prompt template or reference to prompt resource |
| `tools` | List[str] | None | List of tool names available to the model |
| `temperature` | float | `0.7` | Sampling temperature (0.0 to 2.0) |
| `extract_reasoning` | bool | `False` | Extract reasoning content from response |
| `additional_kwargs` | dict | `{}` | Additional DashScope API parameters |

#### Usage Example

```python
class MyAgent(Agent):

    @chat_model_connection
    @staticmethod
    def tongyi_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=Constant.TONGYI_CHAT_MODEL_CONNECTION,
            api_key="your-api-key-here",  # Or set DASHSCOPE_API_KEY env var
            request_timeout=60.0
        )

    @chat_model_setup
    @staticmethod
    def tongyi_chat_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=Constant.TONGYI_CHAT_MODEL_SETUP,
            connection="tongyi_connection",
            model="qwen-plus",
            temperature=0.7,
            extract_reasoning=True
        )

    ...
```

#### Available Models

Visit the [DashScope Models documentation](https://help.aliyun.com/zh/dashscope/developer-reference/model-introduction) for the complete and up-to-date list of available chat models.

Some popular options include:
- **qwen-plus**
- **qwen-max**
- **qwen-turbo**
- **qwen-long**

{{< hint warning >}}
Model availability and specifications may change. Always check the official DashScope documentation for the latest information before implementing in production.
{{< /hint >}}

## Custom Providers

{{< hint warning >}}
The custom provider APIs are experimental and unstable, subject to incompatible changes in future releases.
{{< /hint >}}

If you want to use chat models not offered by the built-in providers, you can extend the base chat classes and implement your own! The chat model system is built around two main abstract classes:

### BaseChatModelConnection

Handles the connection to chat model services and provides the core chat functionality.

{{< tabs "Custom BaseChatModelConnection" >}}

{{< tab "Python" >}}
```python
class MyChatModelConnection(BaseChatModelConnection):

    def chat(
        self,
        messages: Sequence[ChatMessage],
        tools: List[Tool] | None = None,
        **kwargs: Any,
    ) -> ChatMessage:
        # Core method: send messages to LLM and return response
        # - messages: Input message sequence
        # - tools: Optional list of tools available to the model
        # - kwargs: Additional parameters from model_kwargs
        # - Returns: ChatMessage with the model's response
        pass
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class MyChatModelConnection extends BaseChatModelConnection {

    /**
     * Creates a new chat model connection.
     *
     * @param descriptor a resource descriptor contains the initial parameters
     * @param getResource a function to resolve resources (e.g., tools) by name and type
     */
    public MyChatModelConnection(
            ResourceDescriptor descriptor, BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
        // get custom arguments from descriptor
        String endpoint = descriptor.getArgument("endpoint");
        ...
    }
    

    @Override
    public ChatMessage chat(
            List<ChatMessage> messages, List<Tool> tools, Map<String, Object> arguments) {
        // Core method: send messages to LLM and return response
        // - messages: Input message sequence
        // - tools: Optional list of tools available to the model
        // - arguments: Additional parameters from ChatModelSetup
        // - Returns: ChatMessage with the model's response
    }
}
```
{{< /tab >}}

{{< /tabs >}}


### BaseChatModelSetup

The setup class acts as a high-level configuration interface that defines which connection to use and how to configure the chat model.

{{< tabs "Prepare Agents Execution Environment" >}}

{{< tab "Python" >}}
```python
class MyChatModelSetup(BaseChatModelSetup):
    # Add your custom configuration fields here

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        # Return model-specific configuration passed to chat()
        # This dictionary is passed as **kwargs to the chat() method
        return {"model": self.model, "temperature": 0.7, ...}
```
{{< /tab >}}

{{< tab "Java" >}}
```java
public class MyChatModelSetup extends BaseChatModelSetup {
    // Add your custom configuration fields here
    
    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("model", model);
        ...
        // Return model-specific configuration passed to chat()
        // This dictionary is passed as arguments to the chat() method
        return params;
    }
}
```
{{< /tab >}}

{{< /tabs >}}

