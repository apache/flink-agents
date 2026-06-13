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
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.chat.model.python.PythonChatModelSetup;
import org.apache.flink.agents.api.configuration.ReadableConfiguration;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests for {@link ChatModelAction}. */
class ChatModelActionTest {

    private static ChatMessage responseWith(Map<String, Object> extraArgs) {
        return new ChatMessage(MessageRole.ASSISTANT, "response", extraArgs);
    }

    @Test
    void testRecordChatTokenMetricsRecordsWhenAllKeysPresent() {
        BaseChatModelSetup setup = mock(BaseChatModelSetup.class);
        Map<String, Object> extraArgs = new HashMap<>();
        extraArgs.put("model_name", "m");
        extraArgs.put("promptTokens", 100L);
        extraArgs.put("completionTokens", 50L);

        ChatModelAction.recordChatTokenMetrics(setup, responseWith(extraArgs));

        verify(setup).recordTokenMetrics("m", 100L, 50L);
    }

    @Test
    void testRecordChatTokenMetricsHandlesIntegerTokenValues() {
        BaseChatModelSetup setup = mock(BaseChatModelSetup.class);
        Map<String, Object> extraArgs = new HashMap<>();
        extraArgs.put("model_name", "m");
        extraArgs.put("promptTokens", 100);
        extraArgs.put("completionTokens", 50);

        ChatModelAction.recordChatTokenMetrics(setup, responseWith(extraArgs));

        verify(setup).recordTokenMetrics("m", 100L, 50L);
    }

    @Test
    void testRecordChatTokenMetricsSkipsWhenTokenValueNonNumeric() {
        BaseChatModelSetup setup = mock(BaseChatModelSetup.class);
        Map<String, Object> extraArgs = new HashMap<>();
        extraArgs.put("model_name", "m");
        extraArgs.put("promptTokens", "100");
        extraArgs.put("completionTokens", 50L);

        ChatModelAction.recordChatTokenMetrics(setup, responseWith(extraArgs));

        verify(setup, never()).recordTokenMetrics(anyString(), anyLong(), anyLong());
    }

    @Test
    void testRecordChatTokenMetricsSkipsWhenKeyMissing() {
        BaseChatModelSetup setup = mock(BaseChatModelSetup.class);
        Map<String, Object> extraArgs = new HashMap<>();
        extraArgs.put("model_name", "m");
        extraArgs.put("completionTokens", 50L);

        ChatModelAction.recordChatTokenMetrics(setup, responseWith(extraArgs));

        verify(setup, never()).recordTokenMetrics(anyString(), anyLong(), anyLong());
    }

    @Test
    void testRecordChatTokenMetricsSkipsZeroTokensOrEmptyModel() {
        BaseChatModelSetup setup = mock(BaseChatModelSetup.class);

        Map<String, Object> zeroPrompt = new HashMap<>();
        zeroPrompt.put("model_name", "m");
        zeroPrompt.put("promptTokens", 0L);
        zeroPrompt.put("completionTokens", 50L);
        ChatModelAction.recordChatTokenMetrics(setup, responseWith(zeroPrompt));

        Map<String, Object> emptyModel = new HashMap<>();
        emptyModel.put("model_name", "");
        emptyModel.put("promptTokens", 100L);
        emptyModel.put("completionTokens", 50L);
        ChatModelAction.recordChatTokenMetrics(setup, responseWith(emptyModel));

        verify(setup, never()).recordTokenMetrics(anyString(), anyLong(), anyLong());
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

    /** A representative POJO output schema. */
    public static class Person {
        public String name;
    }

    /**
     * Invokes {@link ChatModelAction#chat} once with an output schema and captures the modelParams
     * the resolved setup's {@code chat} receives. The setup throws so the IGNORE strategy returns
     * before any downstream serialization runs.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> captureModelParams(BaseChatModelSetup chatModel)
            throws Exception {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        when(chatModel.chat(any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            captured.set(inv.getArgument(2));
                            throw new RuntimeException("stop after capture");
                        });

        ReadableConfiguration config = mock(ReadableConfiguration.class);
        when(config.get(AgentExecutionOptions.ERROR_HANDLING_STRATEGY))
                .thenReturn(Agent.ErrorHandlingStrategy.IGNORE);
        when(config.get(AgentExecutionOptions.CHAT_ASYNC)).thenReturn(false);

        RunnerContext ctx = mock(RunnerContext.class);
        when(ctx.getConfig()).thenReturn(config);
        when(ctx.getResource("model", ResourceType.CHAT_MODEL)).thenReturn(chatModel);
        when(ctx.durableExecute(any()))
                .thenAnswer(inv -> ((DurableCallable<ChatMessage>) inv.getArgument(0)).call());

        ChatModelAction.chat(
                UUID.randomUUID(),
                "model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                Person.class,
                ctx);
        return captured.get();
    }

    @Test
    void testOutputSchemaThreadedForSameLanguageSetup() throws Exception {
        Map<String, Object> modelParams = captureModelParams(mock(BaseChatModelSetup.class));

        assertNotNull(modelParams);
        assertTrue(
                modelParams.containsKey(BaseChatModelConnection.STRUCTURED_OUTPUT_SCHEMA_KEY),
                "A Java setup must receive the reserved output-schema key");
    }

    @Test
    void testOutputSchemaNotThreadedForPythonSetup() throws Exception {
        Map<String, Object> modelParams = captureModelParams(mock(PythonChatModelSetup.class));

        assertNotNull(modelParams);
        assertFalse(
                modelParams.containsKey(BaseChatModelConnection.STRUCTURED_OUTPUT_SCHEMA_KEY),
                "A Python-backed setup must not receive a Java schema across the bridge");
    }
}
