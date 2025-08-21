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

package org.apache.flink.agents.api.chat.messages;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases for ChatMessage class, Tests message creation, role handling, content management, and
 * serialization.
 */
class ChatMessageTest {

    private ChatMessage userMessage;
    private ChatMessage systemMessage;
    private ChatMessage assistantMessage;

    @BeforeEach
    void setUp() {
        userMessage = new ChatMessage(MessageRole.USER, "Hello, how are you?");
        systemMessage = new ChatMessage(MessageRole.SYSTEM, "You are a helpful assistant.");
        assistantMessage = new ChatMessage(MessageRole.ASSISTANT, "I'm doing well, thank you!");
    }

    @Test
    @DisplayName("Test ChatMessage creation with role and content")
    void testChatMessageCreation() {
        assertEquals(MessageRole.USER, userMessage.getRole());
        assertEquals("Hello, how are you?", userMessage.getContent());

        assertEquals(MessageRole.SYSTEM, systemMessage.getRole());
        assertEquals("You are a helpful assistant.", systemMessage.getContent());

        assertEquals(MessageRole.ASSISTANT, assistantMessage.getRole());
        assertEquals("I'm doing well, thank you!", assistantMessage.getContent());
    }

    @Test
    @DisplayName("Test default constructor creates USER message")
    void testDefaultConstructor() {
        ChatMessage defaultMessage = new ChatMessage();
        assertEquals(MessageRole.USER, defaultMessage.getRole());
        assertEquals("", defaultMessage.getContent());
        assertNotNull(defaultMessage.getToolCalls());
        assertNotNull(defaultMessage.getExtraArgs());
    }

    @Test
    @DisplayName("Test ChatMessage with tool calls")
    void testChatMessageWithToolCalls() {
        ChatMessage messageWithTools =
                new ChatMessage(MessageRole.ASSISTANT, "Let me help you with that.");

        // Add a tool call
        Map<String, Object> toolCall = new HashMap<>();
        toolCall.put("id", "call_123");
        toolCall.put(
                "function",
                new HashMap<String, Object>() {
                    {
                        put("name", "get_weather");
                        put("arguments", "{\"location\": \"NYC\"}");
                    }
                });
        messageWithTools.getToolCalls().add(toolCall);

        assertEquals(1, messageWithTools.getToolCalls().size());
        assertEquals("call_123", messageWithTools.getToolCalls().get(0).get("id"));
    }

    @Test
    @DisplayName("Test ChatMessage with extra arguments")
    void testChatMessageWithExtraArgs() {
        ChatMessage messageWithExtras = new ChatMessage(MessageRole.USER, "Test message");

        messageWithExtras.getExtraArgs().put("temperature", 0.7);
        messageWithExtras.getExtraArgs().put("max_tokens", 100);

        assertEquals(0.7, messageWithExtras.getExtraArgs().get("temperature"));
        assertEquals(100, messageWithExtras.getExtraArgs().get("max_tokens"));
    }

    @Test
    @DisplayName("Test ChatMessage equality")
    void testChatMessageEquality() {
        ChatMessage message1 = new ChatMessage(MessageRole.USER, "Hello");
        ChatMessage message2 = new ChatMessage(MessageRole.USER, "Hello");
        ChatMessage message3 = new ChatMessage(MessageRole.SYSTEM, "Hello");

        assertEquals(message1, message2);
        assertNotEquals(message1, message3);
    }

    @Test
    @DisplayName("Test ChatMessage toString representation")
    void testChatMessageToString() {
        String userString = userMessage.toString();
        assertTrue(userString.contains("user"));
        assertTrue(userString.contains("Hello, how are you?"));
    }

    @Test
    @DisplayName("Test all MessageRole values")
    void testMessageRoles() {
        assertEquals("user", MessageRole.USER.getValue());
        assertEquals("system", MessageRole.SYSTEM.getValue());
        assertEquals("assistant", MessageRole.ASSISTANT.getValue());
        assertEquals("tool", MessageRole.TOOL.getValue());
    }

    @Test
    @DisplayName("Test ChatMessage content modification")
    void testContentModification() {
        ChatMessage message = new ChatMessage(MessageRole.USER, "Original content");
        assertEquals("Original content", message.getContent());

        message.setContent("Modified content");
        assertEquals("Modified content", message.getContent());
    }

    @Test
    @DisplayName("Test ChatMessage role modification")
    void testRoleModification() {
        ChatMessage message = new ChatMessage(MessageRole.USER, "Test content");
        assertEquals(MessageRole.USER, message.getRole());

        message.setRole(MessageRole.ASSISTANT);
        assertEquals(MessageRole.ASSISTANT, message.getRole());
    }
}
