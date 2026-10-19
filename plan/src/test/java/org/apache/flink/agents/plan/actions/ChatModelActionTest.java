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
package org.apache.flink.agents.plan.actions;

import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.TokenUsage;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.plan.ChatFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/** Tests for {@link ChatModelAction}. */
class ChatModelActionTest {

    private static final String PARSEABLE_CONTENT = "{\"answer\":\"42\"}";

    @Test
    void structuredOutputPreservesOriginalBlocks() throws Exception {
        ChatMessage original =
                new ChatMessage(
                        MessageRole.ASSISTANT,
                        List.of(
                                new TextBlock("```json\n{\"answer\":42}\n```"),
                                ImageBlock.fromBase64("image/png", "aGk=")),
                        Map.of("provider", "test"));
        Object parsed =
                ChatModelAction.generateStructuredOutput(new ChatResult(original), Map.class);
        assertEquals(Map.of("answer", 42), parsed);
        assertEquals(2, original.getBlocks().size());
        assertEquals("test", original.getMetadata().get("provider"));
        assertFalse(original.getMetadata().containsKey(Agent.STRUCTURED_OUTPUT));
    }

    private static RunnerContext reportingContext() {
        return mock(RunnerContext.class, withSettings().extraInterfaces(ExecutionReporter.class));
    }

    @Test
    void testCleanLlmResponseWithJsonBlock() {
        String input = "```json\n{\"key\": \"value\"}\n```";
        String expected = "{\"key\": \"value\"}";
        assertEquals(expected, ChatModelAction.cleanLlmResponse(input));
    }

    @Test
    void testCleanLlmResponseWithGenericCodeBlock() {
        String input = "```\n{\"key\": \"value\"}\n```";
        String expected = "{\"key\": \"value\"}";
        assertEquals(expected, ChatModelAction.cleanLlmResponse(input));
    }

    @Test
    void testCleanLlmResponseWithWhitespace() {
        String input = "  ```json\n{\"key\": \"value\"}\n```  ";
        String expected = "{\"key\": \"value\"}";
        assertEquals(expected, ChatModelAction.cleanLlmResponse(input));
    }

    @Test
    void testCleanLlmResponseWithoutBlock() {
        String input = "{\"key\": \"value\"}";
        String expected = "{\"key\": \"value\"}";
        assertEquals(expected, ChatModelAction.cleanLlmResponse(input));
    }

    @Test
    void testCleanLlmResponseWithTextAround() {
        // Current implementation uses replaceAll with ^ and $ anchors,
        // so it only matches if the whole (trimmed) string is a code block.
        String input = "Here is the json: ```json {\"key\": \"value\"} ```";
        String expected = "Here is the json: ```json {\"key\": \"value\"} ```";
        assertEquals(expected, ChatModelAction.cleanLlmResponse(input));
    }

    @Test
    void testCleanLlmResponseWithMultipleLinesInBlock() {
        String input = "```json\n{\n  \"key\": \"value\"\n}\n```";
        String expected = "{\n  \"key\": \"value\"\n}";
        assertEquals(expected, ChatModelAction.cleanLlmResponse(input));
    }

    @Test
    void testStructuredOutputParsesToExpectedValue() throws Exception {
        Object parsed =
                ChatModelAction.generateStructuredOutputWithReport(
                        reportingContext(), ChatFixtures.response(PARSEABLE_CONTENT), Map.class);

        assertEquals(Map.of("answer", "42"), parsed);
    }

    @Test
    void testStructuredOutputPreservesResponseUsageAndMetadata() throws Exception {
        TokenUsage usage = new TokenUsage(100L, 0L);
        Map<String, Object> metadata = Map.of("provider", "test");
        ChatResult response =
                new ChatResult(
                        ChatMessage.assistant(PARSEABLE_CONTENT),
                        "provider-model",
                        "response-id",
                        usage,
                        "stop",
                        metadata);

        Object parsed =
                ChatModelAction.generateStructuredOutputWithReport(
                        reportingContext(), response, Map.class);

        assertEquals(Map.of("answer", "42"), parsed);
        assertEquals(usage, response.getUsage());
        assertEquals(metadata, response.getMetadata());
        assertEquals("provider-model", response.getModel());
        assertEquals("stop", response.getFinishReason());
        assertEquals(PARSEABLE_CONTENT, response.getText());
    }
}
