/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.flink.agents.api.chat.model;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
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
    private static class TestChatModel extends BaseChatModel {
        private String responsePrefix = "Test Response: ";

        @Override
        public ChatMessage chat(Prompt request) {
            // Simple test implementation that echoes the last user message
            List<ChatMessage> messages =
                    request.formatMessages(MessageRole.SYSTEM, new HashMap<>());

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

        @Override
        public String getName() {
            return this.getClass().getSimpleName();
        }
    }

    @BeforeEach
    void setUp() {
        chatModel = new TestChatModel();

        // Create simple prompt
        simplePrompt = new Prompt("simple", "You are a helpful assistant. User says: {user_input}");

        // Create conversation prompt
        List<ChatMessage> conversationTemplate =
                Arrays.asList(
                        new ChatMessage(MessageRole.SYSTEM, "You are a helpful AI assistant."),
                        new ChatMessage(MessageRole.USER, "{user_message}"));
        conversationPrompt = new Prompt("conversation", conversationTemplate);
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
                new Prompt("formatted", simplePrompt.formatMessages(MessageRole.SYSTEM, variables));

        ChatMessage response = chatModel.chat(formattedPrompt);

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
                new Prompt(
                        "formatted",
                        conversationPrompt.formatMessages(MessageRole.SYSTEM, variables));

        ChatMessage response = chatModel.chat(formattedPrompt);

        assertNotNull(response);
        assertEquals(MessageRole.ASSISTANT, response.getRole());
        assertTrue(response.getContent().contains("What's the weather like?"));
    }

    @Test
    @DisplayName("Test chat with empty prompt")
    void testChatWithEmptyPrompt() {
        Prompt emptyPrompt = new Prompt("empty", "");

        ChatMessage response = chatModel.chat(emptyPrompt);

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

        Prompt multiPrompt = new Prompt("multi", multipleMessages);

        ChatMessage response = chatModel.chat(multiPrompt);

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
                new Prompt("formatted", simplePrompt.formatMessages(MessageRole.SYSTEM, variables));

        ChatMessage response = chatModel.chat(formattedPrompt);

        assertTrue(response.getContent().startsWith("Custom Response:"));
    }

    @Test
    @DisplayName("Test chat with system-only prompt")
    void testChatWithSystemOnlyPrompt() {
        Prompt systemOnlyPrompt =
                new Prompt(
                        "system",
                        Arrays.asList(
                                new ChatMessage(MessageRole.SYSTEM, "System instruction only")));

        ChatMessage response = chatModel.chat(systemOnlyPrompt);

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
                new Prompt("formatted", simplePrompt.formatMessages(MessageRole.SYSTEM, variables));

        ChatMessage response = chatModel.chat(formattedPrompt);

        // Verify response structure
        assertNotNull(response.getRole());
        assertNotNull(response.getContent());
        assertNotNull(response.getToolCalls());
        assertNotNull(response.getExtraArgs());
        assertTrue(response.getContent().length() > 0);
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
                new Prompt("formatted", simplePrompt.formatMessages(MessageRole.SYSTEM, variables));

        ChatMessage response = chatModel.chat(formattedPrompt);

        assertNotNull(response);
        assertTrue(response.getContent().length() > 0);
    }
}
