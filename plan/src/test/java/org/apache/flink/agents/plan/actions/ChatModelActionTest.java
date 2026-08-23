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
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.withSettings;

/** Tests for {@link ChatModelAction}. */
class ChatModelActionTest {

    private static final String PARSEABLE_CONTENT = "{\"answer\":\"42\"}";

    private static ChatMessage responseWith(Map<String, Object> extraArgs) {
        return new ChatMessage(MessageRole.ASSISTANT, "response", extraArgs);
    }

    private static RunnerContext reportingContext() {
        return mock(RunnerContext.class, withSettings().extraInterfaces(ExecutionReporter.class));
    }

    private static ChatMessage generateStructuredOutput(
            RunnerContext ctx, Map<String, Object> extraArgs) throws Exception {
        return ChatModelAction.generateStructuredOutputWithReport(
                ctx,
                new ChatMessage(MessageRole.ASSISTANT, PARSEABLE_CONTENT, extraArgs),
                Map.class);
    }

    private static Stream<Map<String, Object>> acceptedFinishReasons() {
        return Stream.of(
                Map.of("finish_reason", "stop"),
                Map.of("finish_reason", "tool_calls"),
                Map.of("finish_reason", "some_vendor_reason"),
                Map.of());
    }

    @Test
    void testRecordChatTokenMetricsRecordsWhenAllKeysPresent() {
        BaseChatModelSetup setup = mock(BaseChatModelSetup.class);
        FlinkAgentsMetricGroup requestMetricGroup = mock(FlinkAgentsMetricGroup.class);
        Map<String, Object> extraArgs = new HashMap<>();
        extraArgs.put("model_name", "m");
        extraArgs.put("promptTokens", 100L);
        extraArgs.put("completionTokens", 50L);

        ChatModelAction.recordChatTokenMetrics(setup, responseWith(extraArgs), requestMetricGroup);

        verify(setup).recordTokenMetrics(requestMetricGroup, "m", 100L, 50L);
    }

    @Test
    void testRecordChatTokenMetricsHandlesIntegerTokenValues() {
        BaseChatModelSetup setup = mock(BaseChatModelSetup.class);
        FlinkAgentsMetricGroup requestMetricGroup = mock(FlinkAgentsMetricGroup.class);
        Map<String, Object> extraArgs = new HashMap<>();
        extraArgs.put("model_name", "m");
        extraArgs.put("promptTokens", 100);
        extraArgs.put("completionTokens", 50);

        ChatModelAction.recordChatTokenMetrics(setup, responseWith(extraArgs), requestMetricGroup);

        verify(setup).recordTokenMetrics(requestMetricGroup, "m", 100L, 50L);
    }

    @Test
    void testRecordChatTokenMetricsSkipsWhenMetricGroupMissing() {
        BaseChatModelSetup setup = mock(BaseChatModelSetup.class);
        Map<String, Object> extraArgs = new HashMap<>();
        extraArgs.put("model_name", "m");
        extraArgs.put("promptTokens", 100L);
        extraArgs.put("completionTokens", 50L);

        ChatModelAction.recordChatTokenMetrics(setup, responseWith(extraArgs), null);

        verifyNoInteractions(setup);
    }

    @Test
    void testRecordChatTokenMetricsSkipsWhenTokenValueNonNumeric() {
        BaseChatModelSetup setup = mock(BaseChatModelSetup.class);
        FlinkAgentsMetricGroup requestMetricGroup = mock(FlinkAgentsMetricGroup.class);
        Map<String, Object> extraArgs = new HashMap<>();
        extraArgs.put("model_name", "m");
        extraArgs.put("promptTokens", "100");
        extraArgs.put("completionTokens", 50L);

        ChatModelAction.recordChatTokenMetrics(setup, responseWith(extraArgs), requestMetricGroup);

        verify(setup, never())
                .recordTokenMetrics(
                        any(FlinkAgentsMetricGroup.class), anyString(), anyLong(), anyLong());
    }

    @Test
    void testRecordChatTokenMetricsSkipsWhenKeyMissing() {
        BaseChatModelSetup setup = mock(BaseChatModelSetup.class);
        FlinkAgentsMetricGroup requestMetricGroup = mock(FlinkAgentsMetricGroup.class);
        Map<String, Object> extraArgs = new HashMap<>();
        extraArgs.put("model_name", "m");
        extraArgs.put("completionTokens", 50L);

        ChatModelAction.recordChatTokenMetrics(setup, responseWith(extraArgs), requestMetricGroup);

        verify(setup, never())
                .recordTokenMetrics(
                        any(FlinkAgentsMetricGroup.class), anyString(), anyLong(), anyLong());
    }

    @Test
    void testRecordChatTokenMetricsSkipsZeroTokensOrEmptyModel() {
        BaseChatModelSetup setup = mock(BaseChatModelSetup.class);
        FlinkAgentsMetricGroup requestMetricGroup = mock(FlinkAgentsMetricGroup.class);

        Map<String, Object> zeroPrompt = new HashMap<>();
        zeroPrompt.put("model_name", "m");
        zeroPrompt.put("promptTokens", 0L);
        zeroPrompt.put("completionTokens", 50L);
        ChatModelAction.recordChatTokenMetrics(setup, responseWith(zeroPrompt), requestMetricGroup);

        Map<String, Object> emptyModel = new HashMap<>();
        emptyModel.put("model_name", "");
        emptyModel.put("promptTokens", 100L);
        emptyModel.put("completionTokens", 50L);
        ChatModelAction.recordChatTokenMetrics(setup, responseWith(emptyModel), requestMetricGroup);

        verify(setup, never())
                .recordTokenMetrics(
                        any(FlinkAgentsMetricGroup.class), anyString(), anyLong(), anyLong());
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
    void testStructuredOutputRejectsTruncatedResponse() {
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                generateStructuredOutput(
                                        reportingContext(), Map.of("finish_reason", "length")));

        String message = error.getMessage().toLowerCase(Locale.ROOT);
        assertTrue(message.contains("truncat"), message);
        assertTrue(message.contains("token"), message);
    }

    @Test
    void testStructuredOutputRejectsContentFilteredResponse() {
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                generateStructuredOutput(
                                        reportingContext(),
                                        Map.of("finish_reason", "content_filter")));

        // Both rejection messages interpolate the finish reason, so the literal
        // content_filter appears in either one and cannot tell them apart. These
        // match prose unique to the filtering message.
        String message = error.getMessage().toLowerCase(Locale.ROOT);
        assertTrue(message.contains("withheld"), message);
        assertTrue(message.contains("content filter"), message);
    }

    @ParameterizedTest
    @MethodSource("acceptedFinishReasons")
    void testAcceptedFinishReasonParsesStructuredOutput(Map<String, Object> extraArgs)
            throws Exception {
        ChatMessage parsed = generateStructuredOutput(reportingContext(), extraArgs);

        assertEquals(Map.of("answer", "42"), parsed.getExtraArgs().get(Agent.STRUCTURED_OUTPUT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "content_filter"})
    void testRejectedFinishReasonReportsNoParserExecution(String finishReason) throws Exception {
        RunnerContext ctx = reportingContext();

        assertThrows(
                IllegalStateException.class,
                () -> generateStructuredOutput(ctx, Map.of("finish_reason", finishReason)));

        ExecutionReporter reporter = (ExecutionReporter) ctx;
        verify(reporter, never()).reportExecutionStarted(anyString(), anyString(), any());
        verify(reporter, never()).reportExecutionSucceeded(anyString(), anyString(), any());
        verify(reporter, never())
                .reportExecutionFailed(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void testStructuredOutputPreservesInboundExtraArgs() throws Exception {
        Map<String, Object> extraArgs = new HashMap<>();
        extraArgs.put("finish_reason", "stop");
        extraArgs.put("promptTokens", 100L);

        Map<String, Object> parsedArgs =
                generateStructuredOutput(reportingContext(), extraArgs).getExtraArgs();

        assertEquals("stop", parsedArgs.get("finish_reason"));
        assertEquals(100L, parsedArgs.get("promptTokens"));
        assertTrue(parsedArgs.containsKey(Agent.STRUCTURED_OUTPUT), parsedArgs.toString());
    }
}
