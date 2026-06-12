/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.agents.api.chat.model;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases for BaseChatModel class, Tests chat model functionality, prompt processing, and
 * response generation.
 */
class BaseChatModelTest {

    private TestChatModel chatModel;
    private Prompt simplePrompt;
    private Prompt conversationPrompt;

    /** Test implementation of BaseChatModel for testing purposes. */
    private static class TestChatModel extends BaseChatModelSetup {
        private String responsePrefix = "Test Response: ";

        public TestChatModel(ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of();
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages,
                Map<String, Object> promptArgs,
                Map<String, Object> modelParams) {
            // Simple test implementation that echoes the last user message

            String lastUserContent = "";
            for (ChatMessage message : messages) {
                if (message.getRole() == MessageRole.USER) {
                    lastUserContent = message.getContent();
                }
            }

            if (lastUserContent.isEmpty()) {
                lastUserContent = "No user message found";
            }

            return new ChatMessage(MessageRole.ASSISTANT, responsePrefix + lastUserContent);
        }

        public void setResponsePrefix(String prefix) {
            this.responsePrefix = prefix;
        }
    }

    @BeforeEach
    void setUp() {
        chatModel =
                new TestChatModel(
                        new ResourceDescriptor(
                                TestChatModel.class.getName(), Collections.emptyMap()),
                        null);

        // Create simple prompt
        simplePrompt = Prompt.fromText("You are a helpful assistant. User says: {user_input}");

        // Create conversation prompt
        List<ChatMessage> conversationTemplate =
                Arrays.asList(
                        new ChatMessage(MessageRole.SYSTEM, "You are a helpful AI assistant."),
                        new ChatMessage(MessageRole.USER, "{user_message}"));
        conversationPrompt = Prompt.fromMessages(conversationTemplate);
    }

    @Test
    @DisplayName("Test ChatModel resource type")
    void testChatModelResourceType() {
        assertEquals(ResourceType.CHAT_MODEL, chatModel.getResourceType());
    }

    @Test
    @DisplayName("Test basic chat functionality")
    void testBasicChat() {
        Map<String, String> variables = new HashMap<>();
        variables.put("user_input", "Hello, how are you?");

        // Format the prompt with variables
        Prompt formattedPrompt =
                Prompt.fromMessages(simplePrompt.formatMessages(MessageRole.SYSTEM, variables));

        ChatMessage response =
                chatModel.chat(formattedPrompt.formatMessages(MessageRole.USER, new HashMap<>()));

        assertNotNull(response);
        assertEquals(MessageRole.ASSISTANT, response.getRole());
        assertTrue(response.getContent().contains("Test Response:"));
    }

    @Test
    @DisplayName("Test chat with conversation prompt")
    void testChatWithConversationPrompt() {
        Map<String, String> variables = new HashMap<>();
        variables.put("user_message", "What's the weather like?");

        Prompt formattedPrompt =
                Prompt.fromMessages(
                        conversationPrompt.formatMessages(MessageRole.SYSTEM, variables));

        ChatMessage response =
                chatModel.chat(formattedPrompt.formatMessages(MessageRole.USER, new HashMap<>()));

        assertNotNull(response);
        assertEquals(MessageRole.ASSISTANT, response.getRole());
        assertTrue(response.getContent().contains("What's the weather like?"));
    }

    @Test
    @DisplayName("Test chat with empty prompt")
    void testChatWithEmptyPrompt() {
        Prompt emptyPrompt = Prompt.fromText("");

        ChatMessage response =
                chatModel.chat(emptyPrompt.formatMessages(MessageRole.USER, new HashMap<>()));

        assertNotNull(response);
        assertEquals(MessageRole.ASSISTANT, response.getRole());
        assertTrue(response.getContent().contains("No user message found"));
    }

    @Test
    @DisplayName("Test chat with multiple user messages")
    void testChatWithMultipleUserMessages() {
        List<ChatMessage> multipleMessages =
                Arrays.asList(
                        new ChatMessage(MessageRole.SYSTEM, "You are a helpful assistant."),
                        new ChatMessage(MessageRole.USER, "First message"),
                        new ChatMessage(MessageRole.ASSISTANT, "I understand"),
                        new ChatMessage(
                                MessageRole.USER, "Second message - this should be the response"));

        Prompt multiPrompt = Prompt.fromMessages(multipleMessages);

        ChatMessage response =
                chatModel.chat(multiPrompt.formatMessages(MessageRole.USER, new HashMap<>()));

        assertNotNull(response);
        assertTrue(response.getContent().contains("Second message - this should be the response"));
    }

    @Test
    @DisplayName("Test chat model configuration")
    void testChatModelConfiguration() {
        chatModel.setResponsePrefix("Custom Response: ");

        Map<String, String> variables = new HashMap<>();
        variables.put("user_input", "Test message");

        Prompt formattedPrompt =
                Prompt.fromMessages(simplePrompt.formatMessages(MessageRole.SYSTEM, variables));

        ChatMessage response =
                chatModel.chat(formattedPrompt.formatMessages(MessageRole.USER, new HashMap<>()));

        assertTrue(response.getContent().startsWith("Custom Response:"));
    }

    @Test
    @DisplayName("Test chat with system-only prompt")
    void testChatWithSystemOnlyPrompt() {
        Prompt systemOnlyPrompt =
                Prompt.fromMessages(
                        Arrays.asList(
                                new ChatMessage(MessageRole.SYSTEM, "System instruction only")));

        ChatMessage response =
                chatModel.chat(systemOnlyPrompt.formatMessages(MessageRole.USER, new HashMap<>()));

        assertNotNull(response);
        assertEquals(MessageRole.ASSISTANT, response.getRole());
        assertTrue(response.getContent().contains("No user message found"));
    }

    @Test
    @DisplayName("Test chat response format")
    void testChatResponseFormat() {
        Map<String, String> variables = new HashMap<>();
        variables.put("user_input", "Format test");

        Prompt formattedPrompt =
                Prompt.fromMessages(simplePrompt.formatMessages(MessageRole.SYSTEM, variables));

        ChatMessage response =
                chatModel.chat(formattedPrompt.formatMessages(MessageRole.USER, new HashMap<>()));

        // Verify response structure
        assertNotNull(response.getRole());
        assertNotNull(response.getContent());
        assertNotNull(response.getToolCalls());
        assertNotNull(response.getExtraArgs());
        assertTrue(response.getContent().length() > 0);
    }

    /** Connection that captures the messages passed to it for assertions. */
    private static class RecordingConnection extends BaseChatModelConnection {
        List<ChatMessage> capturedMessages;

        RecordingConnection() {
            super(
                    new ResourceDescriptor(
                            RecordingConnection.class.getName(), Collections.emptyMap()),
                    null);
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages, List<Tool> tools, Map<String, Object> modelParams) {
            this.capturedMessages = new ArrayList<>(messages);
            return new ChatMessage(MessageRole.ASSISTANT, "ok");
        }
    }

    /** Subclass that exposes setters so we can inject the connection and prompt directly. */
    private static class RecordingChatModelSetup extends BaseChatModelSetup {
        RecordingChatModelSetup(BaseChatModelConnection connection, Prompt prompt) {
            super(
                    new ResourceDescriptor(
                            RecordingChatModelSetup.class.getName(), Collections.emptyMap()),
                    null);
            this.connection = connection;
            this.prompt = prompt;
        }

        @Override
        public Map<String, Object> getParameters() {
            return new HashMap<>();
        }
    }

    @Test
    @DisplayName("chat() fills prompt template from promptArgs parameter")
    void testChatFillsTemplateFromPromptArgsParameter() {
        RecordingConnection connection = new RecordingConnection();
        Prompt prompt = Prompt.fromText("Task: {key}");
        RecordingChatModelSetup setup = new RecordingChatModelSetup(connection, prompt);

        setup.chat(Collections.emptyList(), Map.of("key", "value"), Map.of());

        assertNotNull(connection.capturedMessages);
        assertEquals(1, connection.capturedMessages.size());
        assertEquals("Task: value", connection.capturedMessages.get(0).getContent());
    }

    @Test
    @DisplayName("chat() does not read template vars from ChatMessage.extraArgs")
    void testChatDoesNotReadTemplateVarsFromExtraArgs() {
        RecordingConnection connection = new RecordingConnection();
        Prompt prompt = Prompt.fromText("Task: {key}");
        RecordingChatModelSetup setup = new RecordingChatModelSetup(connection, prompt);

        ChatMessage userMessage =
                new ChatMessage(MessageRole.USER, "hello", Map.of("key", "value"));
        setup.chat(List.of(userMessage), Map.of(), Map.of());

        assertNotNull(connection.capturedMessages);
        assertEquals(2, connection.capturedMessages.size());
        assertEquals("Task: {key}", connection.capturedMessages.get(0).getContent());
        assertEquals("hello", connection.capturedMessages.get(1).getContent());
    }

    @Test
    @DisplayName("chat() re-fills prompt template on subsequent invocations when args supplied")
    void testChatRefillsTemplateOnSubsequentInvocations() {
        RecordingConnection connection = new RecordingConnection();
        Prompt prompt = Prompt.fromText("Task: {key}");
        RecordingChatModelSetup setup = new RecordingChatModelSetup(connection, prompt);

        setup.chat(Collections.emptyList(), Map.of("key", "v1"), Map.of());
        assertNotNull(connection.capturedMessages);
        assertEquals(1, connection.capturedMessages.size());
        assertEquals("Task: v1", connection.capturedMessages.get(0).getContent());

        ChatMessage toolResponse = new ChatMessage(MessageRole.TOOL, "tool result");
        setup.chat(List.of(toolResponse), Map.of("key", "v1"), Map.of());
        assertEquals(2, connection.capturedMessages.size());
        assertEquals("Task: v1", connection.capturedMessages.get(0).getContent());
        assertEquals("tool result", connection.capturedMessages.get(1).getContent());
    }

    @Test
    @DisplayName("popStructuredOutputSchema removes the reserved key and returns its value")
    void testPopStructuredOutputSchemaRemovesAndReturns() {
        Object schema = new Object();
        Map<String, Object> modelParams = new HashMap<>();
        modelParams.put(BaseChatModelConnection.STRUCTURED_OUTPUT_SCHEMA_KEY, schema);
        modelParams.put("temperature", 0.5);

        Object popped = BaseChatModelConnection.popStructuredOutputSchema(modelParams);

        assertSame(schema, popped);
        assertFalse(modelParams.containsKey(BaseChatModelConnection.STRUCTURED_OUTPUT_SCHEMA_KEY));
        assertTrue(modelParams.containsKey("temperature"));
    }

    @Test
    @DisplayName(
            "popStructuredOutputSchema returns null when the reserved key is absent or map is null")
    void testPopStructuredOutputSchemaNoKey() {
        Map<String, Object> modelParams = new HashMap<>();
        modelParams.put("temperature", 0.5);

        assertNull(BaseChatModelConnection.popStructuredOutputSchema(modelParams));
        assertEquals(1, modelParams.size());
        assertNull(BaseChatModelConnection.popStructuredOutputSchema(null));
    }

    @Test
    @DisplayName("Test chat with long input")
    void testChatWithLongInput() {
        StringBuilder longInput = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longInput.append("This is a long message part ").append(i).append(". ");
        }

        Map<String, String> variables = new HashMap<>();
        variables.put("user_input", longInput.toString());

        Prompt formattedPrompt =
                Prompt.fromMessages(simplePrompt.formatMessages(MessageRole.SYSTEM, variables));

        ChatMessage response =
                chatModel.chat(formattedPrompt.formatMessages(MessageRole.USER, new HashMap<>()));

        assertNotNull(response);
        assertTrue(response.getContent().length() > 0);
    }
}
