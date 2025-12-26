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

package org.apache.flink.agents.api.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases for Prompt class, Tests prompt creation, variable substitution, message formatting,
 * and different template types.
 */
class PromptTest {

    private Prompt textPrompt;
    private Prompt messagesPrompt;
    private Map<String, String> variables;

    @BeforeEach
    void setUp() {
        // Create text-based prompt template
        String textTemplate =
                "You are a product review analyzer, please generate a score and the dislike reasons "
                        + "(if any) for the review. The product {product_id} is {description}, and user review is '{review}'.";
        textPrompt = new Prompt(textTemplate);

        // Create message-based prompt template
        List<ChatMessage> messageTemplate =
                Arrays.asList(
                        new ChatMessage(
                                MessageRole.SYSTEM,
                                "You are a product review analyzer, please generate a score and the dislike reasons "
                                        + "(if any) for the review."),
                        new ChatMessage(
                                MessageRole.USER,
                                "The product {product_id} is {description}, and user review is '{review}'."));
        messagesPrompt = new Prompt(messageTemplate);

        // Set up test variables
        variables = new HashMap<>();
        variables.put("product_id", "12345");
        variables.put(
                "description", "wireless noise-canceling headphones with 20-hour battery life");
        variables.put("review", "The headphones broke after one week of use. Very poor quality");
    }

    @Test
    @DisplayName("Test text prompt to string formatting")
    void testTextPromptToString() {
        String expected =
                "You are a product review analyzer, please generate a score and the dislike reasons "
                        + "(if any) for the review. The product 12345 is wireless noise-canceling headphones with 20-hour "
                        + "battery life, and user review is 'The headphones broke after one week of use. Very poor quality'.";

        String actual = textPrompt.formatString(variables);
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("Test text prompt to messages formatting")
    void testTextPromptToMessages() {
        List<ChatMessage> messages = textPrompt.formatMessages(MessageRole.SYSTEM, variables);

        assertEquals(1, messages.size());
        assertEquals(MessageRole.SYSTEM, messages.get(0).getRole());
        assertTrue(messages.get(0).getContent().contains("12345"));
        assertTrue(messages.get(0).getContent().contains("wireless noise-canceling headphones"));
        assertTrue(messages.get(0).getContent().contains("The headphones broke after one week"));
    }

    @Test
    @DisplayName("Test messages prompt to string formatting")
    void testMessagesPromptToString() {
        String expected =
                "system: You are a product review analyzer, please generate a score and the dislike reasons "
                        + "(if any) for the review.\n"
                        + "user: The product 12345 is wireless noise-canceling headphones with 20-hour battery life, "
                        + "and user review is 'The headphones broke after one week of use. Very poor quality'.";

        String actual = messagesPrompt.formatString(variables);
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("Test messages prompt to messages formatting")
    void testMessagesPromptToMessages() {
        List<ChatMessage> messages = messagesPrompt.formatMessages(MessageRole.SYSTEM, variables);

        assertEquals(2, messages.size());

        // Check system message
        assertEquals(MessageRole.SYSTEM, messages.get(0).getRole());
        assertEquals(
                "You are a product review analyzer, please generate a score and the dislike reasons "
                        + "(if any) for the review.",
                messages.get(0).getContent());

        // Check user message with variable substitution
        assertEquals(MessageRole.USER, messages.get(1).getRole());
        assertTrue(messages.get(1).getContent().contains("12345"));
        assertTrue(messages.get(1).getContent().contains("wireless noise-canceling headphones"));
    }

    @Test
    @DisplayName("Test prompt with missing variables")
    void testPromptWithMissingVariables() {
        Map<String, String> incompleteVars = new HashMap<>();
        incompleteVars.put("product_id", "12345");
        // Missing 'description' and 'review'

        String result = textPrompt.formatString(incompleteVars);
        // Should still contain placeholder variables
        assertTrue(result.contains("{description}"));
        assertTrue(result.contains("{review}"));
        assertFalse(result.contains("{product_id}")); // This should be replaced
    }

    @Test
    @DisplayName("Test prompt with extra variables")
    void testPromptWithExtraVariables() {
        Map<String, String> extraVars = new HashMap<>(variables);
        extraVars.put("extra_var", "not used");
        extraVars.put("another_extra", "also not used");

        // Should work fine, extra variables are ignored
        String result = textPrompt.formatString(extraVars);
        assertFalse(result.contains("{extra_var}"));
        assertFalse(result.contains("{another_extra}"));
        assertTrue(result.contains("12345")); // Normal variables still work
    }

    @Test
    @DisplayName("Test prompt resource type")
    void testPromptResourceType() {
        assertEquals(ResourceType.PROMPT, textPrompt.getResourceType());
        assertEquals(ResourceType.PROMPT, messagesPrompt.getResourceType());
    }

    @Test
    @DisplayName("Test empty prompt")
    void testEmptyPrompt() {
        Prompt emptyPrompt = new Prompt("");
        String result = emptyPrompt.formatString(new HashMap<>());
        assertEquals("", result);

        List<ChatMessage> messages = emptyPrompt.formatMessages(MessageRole.USER, new HashMap<>());
        assertEquals(1, messages.size());
        assertEquals("", messages.get(0).getContent());
    }

    @Test
    @DisplayName("Test prompt with special characters")
    void testPromptWithSpecialCharacters() {
        String specialTemplate = "Handle special chars: {text} with symbols like @#$%^&*()";
        Prompt specialPrompt = new Prompt(specialTemplate);

        Map<String, String> specialVars = new HashMap<>();
        specialVars.put("text", "Hello & Welcome!");

        String result = specialPrompt.formatString(specialVars);
        assertTrue(result.contains("Hello & Welcome!"));
        assertTrue(result.contains("@#$%^&*()"));
    }

    @Test
    @DisplayName("Test prompt with nested braces")
    void testPromptWithNestedBraces() {
        String nestedTemplate = "JSON example: {{\"key\": \"{value}\"}}";
        Prompt nestedPrompt = new Prompt(nestedTemplate);

        Map<String, String> nestedVars = new HashMap<>();
        nestedVars.put("value", "test");

        String result = nestedPrompt.formatString(nestedVars);
        assertTrue(result.contains("{\"key\": \"test\"}"));
    }

    @Test
    @DisplayName("Test complex conversation prompt")
    void testComplexConversationPrompt() {
        List<ChatMessage> conversationTemplate =
                Arrays.asList(
                        new ChatMessage(
                                MessageRole.SYSTEM,
                                "You are {assistant_type} specialized in {domain}."),
                        new ChatMessage(MessageRole.USER, "Hello, I need help with {task}."),
                        new ChatMessage(
                                MessageRole.ASSISTANT,
                                "I'd be happy to help with {task}. Let me know what specifically you need."),
                        new ChatMessage(MessageRole.USER, "{user_request}"));

        Prompt conversationPrompt = new Prompt(conversationTemplate);

        Map<String, String> conversationVars = new HashMap<>();
        conversationVars.put("assistant_type", "an AI assistant");
        conversationVars.put("domain", "software development");
        conversationVars.put("task", "debugging");
        conversationVars.put("user_request", "My code is throwing a NullPointerException");

        List<ChatMessage> messages =
                conversationPrompt.formatMessages(MessageRole.SYSTEM, conversationVars);

        assertEquals(4, messages.size());
        assertTrue(messages.get(0).getContent().contains("an AI assistant"));
        assertTrue(messages.get(0).getContent().contains("software development"));
        assertTrue(messages.get(3).getContent().contains("NullPointerException"));
    }

    @Test
    @DisplayName("Test string prompt serialize and deserialize")
    void testStringPromptSerializeAndDeserialize() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(textPrompt);
        Prompt deserialized = mapper.readValue(json, Prompt.class);
        Map<String, String> empty = new HashMap<>();
        Assertions.assertEquals(textPrompt.formatString(empty), deserialized.formatString(empty));
    }

    @Test
    @DisplayName("Test message prompt serialize and deserialize")
    void testMessagePromptSerializeAndDeserialize() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(messagesPrompt);
        Prompt deserialized = mapper.readValue(json, Prompt.class);
        Map<String, String> empty = new HashMap<>();
        Assertions.assertEquals(
                messagesPrompt.formatString(empty), deserialized.formatString(empty));
    }
}
