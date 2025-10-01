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

To use chat models in your agents, you need to define both a connection and setup using decorators, then interact with the model through events.

### Resource Decorators

Flink Agents provides decorators to simplify chat model setup within agents:

#### @chat_model_connection

The `@chat_model_connection` decorator marks a method that creates a chat model connection. This is typically defined once and shared across multiple chat model setups.

#### @chat_model_setup

The `@chat_model_setup` decorator marks a method that creates a chat model setup. This references a connection and adds chat-specific configuration like prompts and tools.

### Chat Events

Chat models communicate through built-in events:

- **ChatRequestEvent**: Sent by actions to request a chat completion from the LLM
- **ChatResponseEvent**: Received by actions containing the LLM's response

### Usage Example

Here's how to define and use chat models in a workflow agent:

```python
class MyAgent(Agent):

    @chat_model_connection
    @staticmethod
    def ollama_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=OllamaChatModelConnection,
            base_url="http://localhost:11434",
            request_timeout=30.0
        )

    @chat_model_setup
    @staticmethod
    def ollama_chat_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=OllamaChatModelSetup,
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

## Built-in Providers

### Anthropic

Anthropic provides cloud-based chat models featuring the Claude family, known for their strong reasoning, coding, and safety capabilities.

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
            clazz=AnthropicChatModelConnection,
            api_key="your-api-key-here",  # Or set ANTHROPIC_API_KEY env var
            max_retries=3,
            timeout=60.0
        )

    @chat_model_setup
    @staticmethod
    def anthropic_chat_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=AnthropicChatModelSetup,
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

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `base_url` | str | `"http://localhost:11434"` | Ollama server URL |
| `request_timeout` | float | `30.0` | HTTP request timeout in seconds |

#### OllamaChatModelSetup Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `connection` | str | Required | Reference to connection method name |
| `model` | str | Required | Name of the chat model to use |
| `prompt` | Prompt \| str | None | Prompt template or reference to prompt resource |
| `tools` | List[str] | None | List of tool names available to the model |
| `temperature` | float | `0.75` | Sampling temperature (0.0 to 1.0) |
| `num_ctx` | int | `2048` | Maximum number of context tokens |
| `keep_alive` | str \| float | `"5m"` | How long to keep model loaded in memory |
| `extract_reasoning` | bool | `True` | Extract reasoning content from response |
| `additional_kwargs` | dict | `{}` | Additional Ollama API parameters |

#### Usage Example

```python
class MyAgent(Agent):

    @chat_model_connection
    @staticmethod
    def ollama_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=OllamaChatModelConnection,
            base_url="http://localhost:11434",
            request_timeout=120.0
        )

    @chat_model_setup
    @staticmethod
    def my_chat_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=OllamaChatModelSetup,
            connection="ollama_connection",
            model="qwen3:8b",
            temperature=0.7,
            num_ctx=4096,
            keep_alive="10m",
            extract_reasoning=True
        )

    ...
```

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

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `api_key` | str | `$OPENAI_API_KEY` | OpenAI API key for authentication |
| `api_base_url` | str | `"https://api.openai.com/v1"` | Base URL for OpenAI API |
| `max_retries` | int | `3` | Maximum number of API retry attempts |
| `timeout` | float | `60.0` | API request timeout in seconds |
| `default_headers` | dict | None | Default headers for API requests |
| `reuse_client` | bool | `True` | Whether to reuse the OpenAI client between requests |

#### OpenAIChatModelSetup Parameters

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

#### Usage Example

```python
class MyAgent(Agent):

    @chat_model_connection
    @staticmethod
    def openai_connection() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=OpenAIChatModelConnection,
            api_key="your-api-key-here",  # Or set OPENAI_API_KEY env var
            api_base_url="https://api.openai.com/v1",
            max_retries=3,
            timeout=60.0
        )

    @chat_model_setup
    @staticmethod
    def openai_chat_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=OpenAIChatModelSetup,
            connection="openai_connection",
            model="gpt-4",
            temperature=0.7,
            max_tokens=1000
        )

    ...
```

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
            clazz=TongyiChatModelConnection,
            api_key="your-api-key-here",  # Or set DASHSCOPE_API_KEY env var
            request_timeout=60.0
        )

    @chat_model_setup
    @staticmethod
    def tongyi_chat_model() -> ResourceDescriptor:
        return ResourceDescriptor(
            clazz=TongyiChatModelSetup,
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

### BaseChatModelSetup

The setup class acts as a high-level configuration interface that defines which connection to use and how to configure the chat model.

```python
class MyChatModelSetup(BaseChatModelSetup):
    # Add your custom configuration fields here

    @property
    def model_kwargs(self) -> Dict[str, Any]:
        # Return model-specific configuration passed to chat()
        # This dictionary is passed as **kwargs to the chat() method
        return {"model": self.model, "temperature": 0.7, ...}
```
