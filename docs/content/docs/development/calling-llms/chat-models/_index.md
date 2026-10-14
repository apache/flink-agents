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

## Using Cross-Language Providers

Flink Agents supports cross-language chat model integration, allowing you to use chat models implemented in one language (Java or Python) from agents written in the other language. This is particularly useful when a chat model provider is only available in one language (e.g., the DashScope integration for Alibaba Cloud Model Studio is currently Python-only).

{{< hint warning >}}
**Limitations:**

- Cross-language resources are currently supported only when [running in Flink]({{< ref "docs/operations/deployment#run-in-flink" >}}), not in local development mode
- Complex object serialization between languages may have limitations
{{< /hint >}}

### How To Use

To use a resource implemented in another language, declare its implementation class with the corresponding cross-language resource descriptor:

- **Using Java chat models in Python**: Use `JavaResourceDescriptor(clazz=ResourceName.ChatModel.Java.OLLAMA_CONNECTION, ...)` (or the corresponding setup class).
- **Using Python chat models in Java**: Use `PythonResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.Python.OLLAMA_CONNECTION)` (or the corresponding setup class).



### Usage Example

{{< tabs "Cross-Language Chat Model Usage Example" >}}

{{< tab "Using Java Chat Model in Python" >}}
```python
class MyAgent(Agent):

    @chat_model_connection
    @staticmethod
    def java_chat_model_connection() -> ResourceDescriptor:
        # In pure Java, the equivalent ResourceDescriptor would be:
        # ResourceDescriptor.Builder
        #     .newBuilder(ResourceName.ChatModel.OLLAMA_CONNECTION)
        #     .addInitialArgument("endpoint", "http://localhost:11434")
        #     .addInitialArgument("requestTimeout", 120)
        #     .build();
        return JavaResourceDescriptor(
            clazz=ResourceName.ChatModel.Java.OLLAMA_CONNECTION,
            endpoint="http://localhost:11434",
            requestTimeout=120,
        )


    @chat_model_setup
    @staticmethod
    def java_chat_model() -> ResourceDescriptor:
        # In pure Java, the equivalent ResourceDescriptor would be:
        # ResourceDescriptor.Builder
        #     .newBuilder(ResourceName.ChatModel.OLLAMA_SETUP)
        #     .addInitialArgument("connection", "java_chat_model_connection")
        #     .addInitialArgument("model", "qwen3:8b")
        #     .addInitialArgument("prompt", "my_prompt")
        #     .addInitialArgument("tools", List.of("my_tool1", "my_tool2"))
        #     .addInitialArgument("extractReasoning", true)
        #     .build();
        return JavaResourceDescriptor(
            clazz=ResourceName.ChatModel.Java.OLLAMA_SETUP,
            connection="java_chat_model_connection",
            model="qwen3:8b",
            prompt="my_prompt",
            tools=["my_tool1", "my_tool2"],
            extract_reasoning=True,
        )

    @action(EventType.InputEvent)
    @staticmethod
    def process_input(event: Event, ctx: RunnerContext) -> None:
        input_event = InputEvent.from_event(event)
        # Create a chat request with user message
        user_message = ChatMessage.user(f"input: {input_event.input}")
        ctx.send_event(
            ChatRequestEvent(model="java_chat_model", messages=[user_message])
        )

    @action(EventType.ChatResponseEvent)
    @staticmethod
    def process_response(event: Event, ctx: RunnerContext) -> None:
        chat_response = ChatResponseEvent.from_event(event)
        response_content = chat_response.response.text
        # Handle the LLM's response
        # Process the response as needed for your use case
```
{{< /tab >}}

{{< tab "Using Python Chat Model in Java" >}}
```java
public class MyAgent extends Agent {

    @ChatModelConnection
    public static ResourceDescriptor pythonChatModelConnection() {
        // In pure Python, the equivalent ResourceDescriptor would be:
        // ResourceDescriptor(
        //     clazz=ResourceName.ChatModel.OLLAMA_CONNECTION,
        //     request_timeout=120.0
        // )
        return PythonResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.Python.OLLAMA_CONNECTION)
                .addInitialArgument("request_timeout", 120.0)
                .build();
    }

    @ChatModelSetup
    public static ResourceDescriptor pythonChatModel() {
        // In pure Python, the equivalent ResourceDescriptor would be:
        // ResourceDescriptor(
        //     clazz=ResourceName.ChatModel.OLLAMA_SETUP,
        //     connection="pythonChatModelConnection",
        //     model="qwen3:8b",
        //     tools=["tool1", "tool2"],
        //     extract_reasoning=True
        // )
        return PythonResourceDescriptor.Builder.newBuilder(ResourceName.ChatModel.Python.OLLAMA_SETUP)
                .addInitialArgument("connection", "pythonChatModelConnection")
                .addInitialArgument("model", "qwen3:8b")
                .addInitialArgument("tools", List.of("tool1", "tool2"))
                .addInitialArgument("extract_reasoning", true)
                .build();
    }

    @Action(EventType.InputEvent)
    public static void processInput(Event event, RunnerContext ctx) throws Exception {
        InputEvent inputEvent = InputEvent.fromEvent(event);
        ChatMessage userMessage =
                new ChatMessage(MessageRole.USER, String.format("input: {%s}", inputEvent.getInput()));
        ctx.sendEvent(new ChatRequestEvent("pythonChatModel", List.of(userMessage)));
    }

    @Action(EventType.ChatResponseEvent)
    public static void processResponse(Event event, RunnerContext ctx)
            throws Exception {
        ChatResponseEvent chatResponse = ChatResponseEvent.fromEvent(event);
        String response = chatResponse.getResponse().getText();
        // Handle the LLM's response
        // Process the response as needed for your use case
    }
}
```
{{< /tab >}}

{{< /tabs >}}

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
    ) -> ChatResult:
        # Core method: send messages to LLM and return response
        # - messages: Input message sequence
        # - tools: Optional list of tools available to the model
        # - kwargs: Additional parameters from model_kwargs
        # - Returns: ChatResult with the assistant message and available usage information
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
     * @param resourceContext context for resolving resources (e.g., tools) by name and type
     */
    public MyChatModelConnection(
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        // get custom arguments from descriptor
        String endpoint = descriptor.getArgument("endpoint");
        ...
    }


    @Override
    public ChatResult chat(
            List<ChatMessage> messages, List<Tool> tools, Map<String, Object> arguments) {
        // Core method: send messages to LLM and return response
        // - messages: Input message sequence
        // - tools: Optional list of tools available to the model
        // - arguments: Additional parameters from ChatModelSetup
        // - Returns: ChatResult with the assistant message and available usage information
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

[Chat with Models]({{< ref "docs/development/calling-llms/chat" >}}) · [Built-in Chat Model Providers]({{< ref "docs/development/calling-llms/chat-models/providers" >}}) · [Model Routing]({{< ref "docs/development/calling-llms/model_routing" >}})
