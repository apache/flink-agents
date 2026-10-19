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

Choose a Java or Python provider implementation and install its dependencies in the
job's environment, following [the setup instructions]({{< ref "docs/development/create-agent/choose-apis-and-languages#integrate-the-agent-into-a-flink-job" >}}).
When provider results are carried in Event payloads, those payloads must use the supported
[Java and Python data types]({{< ref "docs/development/create-agent/choose-apis-and-languages#data-passed-between-java-and-python" >}}).
For async calls, also check the [cross-language execution requirements]({{< ref "docs/development/create-agent/actions#cross-language-async-requirements" >}}).

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

### Calling chat from an action

The example above sends a `ChatRequestEvent` and handles the response in a separate action. When the same action needs to continue with the result, use `ctx.chat` to await it directly. It wraps the same built-in Chat/Tool event flow and supports the same model routing, retries, tools, and structured output.

Keep the resource declarations above and replace the input and response actions with the following action:

{{< tabs "Chat calls" >}}
{{< tab "Python" >}}
```python
@action(EventType.InputEvent)
@staticmethod
async def process_input(event: Event, ctx: RunnerContext) -> None:
    input_event = InputEvent.from_event(event)
    response = await ctx.chat(
        "ollama_chat_model",
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
            "ollamaChatModel",
            List.of(ChatMessage.user(String.valueOf(inputEvent.getInput()))))
            .await();
    ctx.sendEvent(new OutputEvent(response.getText()));
}
```

For prompt arguments or structured output, pass a `ChatRequestEvent` to `ctx.chat(request)`.
{{< /tab >}}
{{< /tabs >}}

The returned `DurableFuture<ChatResult>` (Python: `DurableFuture[ChatResult]`) starts the request only when awaited. Await it directly, without wrapping it in `durable_execute_async` / `durableExecuteAsync`. Use the future only within the action execution that created it; do not store it for a later event or pass it to another action execution. Repeated awaits within that execution reuse the same result. Passing chat futures to `gather` is not supported yet.

The result is a full `ChatResult`, including the assistant message and its content blocks, plus available model, token usage and invocation metadata. Use `response.message` in Python or `response.getMessage()` in Java to access the message; `response.text` / `response.getText()` provides its text. A failed chat response raises `ChatResponseError` in Python or `ChatResponseEvent.ChatResponseException` in Java, which the calling action can catch. Existing model and tool timeout settings still apply; there is no additional whole-call timeout.

`ctx.chat` uses the calling action's resources and memory. Its response is returned to the caller and does not trigger actions subscribed to `ChatResponseEvent`. Use the event-style API when you want a separate action to handle the response.

With durable execution enabled, recorded results are reused after recovery. Replay matches calls by the calling action's execution identity and the order in which chat calls are first awaited; it does not compare the model, messages, or prompt arguments. Changing a request at the same call position therefore still reuses its recorded response when the caller identity is unchanged. Requests whose results were not recorded may execute again, so external provider or tool effects are not guaranteed to occur exactly once.

Java chat calls require JDK 21 and `--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED` in `env.java.opts.all`. Python chat calls do not require JDK 21. Java calls are currently affected by the known deadlock limitation in [#1213](https://github.com/apache/flink-agents/issues/1213).

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
