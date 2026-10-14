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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.agents.OutputSchema;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.ReasoningBlock;
import org.apache.flink.agents.api.chat.messages.TokenUsage;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.LLMExecutionMetadataKeys;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.ChatFixtures;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.metrics.Counter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.Invocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Tests for {@link ChatModelAction#chat} driven end to end: retry behavior, execution reporting,
 * the finish-reason gate, and tool-response handling.
 */
class ChatModelActionRetryTest {

    private static final Map<String, Object> LLM_METADATA =
            Map.of(LLMExecutionMetadataKeys.MODEL, "configured-model");

    @Mock private RunnerContext mockCtx;

    @Mock private BaseChatModelSetup mockChatModel;

    @Mock private FlinkAgentsMetricGroup mockActionMetricGroup;

    @Mock private FlinkAgentsMetricGroup mockModelMetricGroup;

    @Mock private Counter mockRetryCountCounter;

    @Mock private Counter mockRetryWaitSecCounter;

    private MemoryObject sensoryMemory;
    private List<Event> sentEvents;
    private List<String> durableCallIds;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        sentEvents = new ArrayList<>();
        durableCallIds = new ArrayList<>();
        sensoryMemory = createStatefulMemoryObject();

        // Wire up ChatModel
        when(mockChatModel.getConnectionName()).thenReturn("test-connection");

        // Wire up RunnerContext
        when(mockCtx.getResource(anyString(), eq(ResourceType.CHAT_MODEL)))
                .thenReturn(mockChatModel);
        when(mockCtx.getSensoryMemory()).thenReturn(sensoryMemory);
        when(mockCtx.getActionMetricGroup()).thenReturn(mockActionMetricGroup);
        doAnswer(inv -> sentEvents.add(inv.getArgument(0))).when(mockCtx).sendEvent(any());
        when(mockCtx.<ChatResult>durableExecute(any()))
                .thenAnswer(inv -> inv.<DurableCallable<ChatResult>>getArgument(0).call());

        // Wire up metric group chain
        when(mockActionMetricGroup.getSubGroup(anyString(), anyString()))
                .thenReturn(mockModelMetricGroup);
        when(mockModelMetricGroup.getCounter("retryCount")).thenReturn(mockRetryCountCounter);
        when(mockModelMetricGroup.getCounter("retryWaitSec")).thenReturn(mockRetryWaitSecCounter);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    void toolDispatchPreservesMessageMetadataWithoutInvocationMetadata() throws Exception {
        configureRetryStrategy(0, 0);
        ToolCallBlock call = new ToolCallBlock("provider-id", "f", Map.of("x", 1));
        ChatResult response =
                new ChatResult(
                        ChatMessage.assistant(
                                        List.of(
                                                new ReasoningBlock(
                                                        "private", Map.of("provider", "local")),
                                                call))
                                .withMetadata(Map.of("message_key", "value")),
                        "provider-model",
                        "response-id",
                        null,
                        "tool_calls",
                        Map.of("provider_only", "opaque"));
        when(mockChatModel.chat(any(), any(), any())).thenReturn(response);
        UUID requestId = UUID.randomUUID();
        ChatMessage user = ChatMessage.user("hi");

        ChatModelAction.chat(requestId, "test-model", List.of(user), Map.of(), null, mockCtx);

        Map<UUID, List<ChatMessage>> context =
                (Map<UUID, List<ChatMessage>>) sensoryMemory.get("_TOOL_CALL_CONTEXT").getValue();
        assertThat(context.get(requestId)).containsExactly(user, response.getMessage());
        ChatMessage assistant = context.get(requestId).get(1);
        assertThat(assistant.getMetadata())
                .containsExactlyEntriesOf(Map.of("message_key", "value"));
        assertThat(assistant.getToolCalls()).containsExactly(call);
        assertThat(sentEvents).hasSize(1);
        assertThat(ToolRequestEvent.fromEvent(sentEvents.get(0)).getToolCalls())
                .containsExactly(call);
    }

    @Test
    void chatSucceedsWithoutRetry_retryCountIsZero() throws Exception {
        configureRetryStrategy(3, 1);
        when(mockChatModel.chat(any(), any(), any())).thenReturn(ChatFixtures.response("hello"));

        UUID requestId = UUID.randomUUID();
        ChatModelAction.chat(
                requestId,
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                null,
                mockCtx);

        assertThat(sentEvents).hasSize(1);
        ChatResponseEvent responseEvent = ChatResponseEvent.fromEvent(sentEvents.get(0));
        assertThat(responseEvent.getRetryCount()).isEqualTo(0);
        assertThat(responseEvent.getTotalRetryWaitSec()).isEqualTo(0);

        // No retry metrics should be recorded
        verify(mockActionMetricGroup, never()).getSubGroup(anyString(), anyString());
    }

    @Test
    void chatRecordsTokenMetricsWithRequestScopedMetricGroup() throws Exception {
        configureRetryStrategy(0, 0);
        FlinkAgentsMetricGroup actionA = mock(FlinkAgentsMetricGroup.class);
        FlinkAgentsMetricGroup actionB = mock(FlinkAgentsMetricGroup.class);
        when(mockCtx.getActionMetricGroup()).thenReturn(actionA, actionB);

        ChatResult response =
                ChatFixtures.response(
                        "hello",
                        Map.of(
                                "model_name", "provider-model",
                                "promptTokens", 100L,
                                "completionTokens", 50L));
        when(mockChatModel.chat(any(), any(), any())).thenReturn(response);

        ChatModelAction.chat(
                UUID.randomUUID(),
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                null,
                mockCtx);

        verify(mockChatModel).recordTokenMetrics(actionA, "provider-model", 100L, 50L);
        verify(mockChatModel, never()).recordTokenMetrics(actionB, "provider-model", 100L, 50L);
    }

    @Test
    void chatReportsLlmExecution() throws Exception {
        RunnerContext reportingCtx = reportingRunnerContext();
        BaseChatModelSetup chatModel = configureReportingChatContext(reportingCtx);
        when(chatModel.chat(any(), any(), any())).thenReturn(ChatFixtures.response("hello"));

        ChatModelAction.chat(
                UUID.randomUUID(),
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                null,
                reportingCtx);

        ExecutionReporter reporter = (ExecutionReporter) reportingCtx;
        verify(reporter)
                .reportExecutionStarted(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.LLM),
                        eq("test-model"),
                        eq(LLM_METADATA));
        verify(reporter)
                .reportExecutionSucceeded(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.LLM),
                        eq("test-model"),
                        eq(LLM_METADATA));
        assertInvocationIdentities(reporter, 1);
    }

    @ParameterizedTest
    @MethodSource("responseUsageCases")
    void chatReportsUsageAndPreservesTokenMetricConditions(
            TokenUsage usage,
            String responseModel,
            Map<String, Object> expectedUsage,
            boolean metricsAvailable,
            boolean recordsMetrics)
            throws Exception {
        RunnerContext reportingCtx = reportingRunnerContext();
        BaseChatModelSetup chatModel = configureReportingChatContext(reportingCtx);
        if (!metricsAvailable) {
            when(reportingCtx.getActionMetricGroup()).thenReturn(null);
        }
        when(chatModel.chat(any(), any(), any()))
                .thenReturn(
                        new ChatResult(
                                ChatMessage.assistant("hello"),
                                responseModel,
                                null,
                                usage,
                                null,
                                Map.of()));

        ChatModelAction.chat(
                UUID.randomUUID(), "test-model", List.of(), Map.of(), null, reportingCtx);

        Map<String, Object> expectedMetadata = new HashMap<>(LLM_METADATA);
        if (!expectedUsage.isEmpty()) {
            expectedMetadata.put("usage", expectedUsage);
        }
        ExecutionReporter reporter = (ExecutionReporter) reportingCtx;
        verify(reporter)
                .reportExecutionStarted(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.LLM),
                        eq("test-model"),
                        eq(LLM_METADATA));
        verify(reporter)
                .reportExecutionSucceeded(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.LLM),
                        eq("test-model"),
                        eq(expectedMetadata));
        if (recordsMetrics) {
            verify(chatModel)
                    .recordTokenMetrics(
                            mockActionMetricGroup,
                            responseModel,
                            usage.getPromptTokens(),
                            usage.getCompletionTokens());
        } else {
            verify(chatModel, never()).recordTokenMetrics(any(), anyString(), anyLong(), anyLong());
        }
        assertInvocationIdentities(reporter, 1);
        assertThat(ChatResponseEvent.fromEvent(sentEvents.get(0)).isFailed()).isFalse();
    }

    private static Stream<Arguments> responseUsageCases() {
        return Stream.of(
                Arguments.of(
                        new TokenUsage(100L, 50L),
                        "metric-model",
                        Map.of("promptTokens", 100L, "completionTokens", 50L),
                        true,
                        true),
                Arguments.of(
                        new TokenUsage(100L, 50L),
                        "metric-model",
                        Map.of("promptTokens", 100L, "completionTokens", 50L),
                        false,
                        false),
                Arguments.of(
                        new TokenUsage(null, 50L),
                        "metric-model",
                        Map.of("completionTokens", 50L),
                        true,
                        false),
                Arguments.of(
                        new TokenUsage(0L, 50L),
                        "metric-model",
                        Map.of("promptTokens", 0L, "completionTokens", 50L),
                        true,
                        true),
                Arguments.of(
                        new TokenUsage(100L, 50L),
                        "",
                        Map.of("promptTokens", 100L, "completionTokens", 50L),
                        true,
                        true),
                Arguments.of(
                        new TokenUsage(5L, 0L),
                        null,
                        Map.of("promptTokens", 5L, "completionTokens", 0L),
                        true,
                        false),
                Arguments.of(
                        new TokenUsage(5L, 0L),
                        "metric-model",
                        Map.of("promptTokens", 5L, "completionTokens", 0L),
                        true,
                        true),
                Arguments.of(
                        new TokenUsage(null, 0L),
                        "metric-model",
                        Map.of("completionTokens", 0L),
                        true,
                        false),
                Arguments.of(
                        new TokenUsage(5L, null),
                        "metric-model",
                        Map.of("promptTokens", 5L),
                        true,
                        false),
                Arguments.of(new TokenUsage(null, null), "metric-model", Map.of(), true, false),
                Arguments.of(null, "metric-model", Map.of(), true, false));
    }

    @Test
    void chatReportsStructuredOutputParserExecution() throws Exception {
        RunnerContext reportingCtx = reportingRunnerContext();
        BaseChatModelSetup chatModel = configureReportingChatContext(reportingCtx);
        when(chatModel.chat(any(), any(), any()))
                .thenReturn(ChatFixtures.response("{\"answer\":\"42\"}"));

        ChatModelAction.chat(
                UUID.randomUUID(),
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                Map.class,
                reportingCtx);

        ExecutionReporter reporter = (ExecutionReporter) reportingCtx;
        verify(reporter)
                .reportExecutionStarted(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.PARSER),
                        eq(Agent.STRUCTURED_OUTPUT),
                        eq(Map.of()));
        verify(reporter)
                .reportExecutionSucceeded(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.PARSER),
                        eq(Agent.STRUCTURED_OUTPUT),
                        eq(Map.of()));
        assertInvocationIdentities(reporter, 2);
    }

    @Test
    void chatRetriesStructuredOutputParseErrorWithoutFailingLlm() throws Exception {
        RunnerContext reportingCtx = reportingRunnerContext();
        BaseChatModelSetup chatModel = configureReportingChatContext(reportingCtx);
        when(reportingCtx.getConfig()).thenReturn(readableConfig(1, 0));
        when(chatModel.chat(any(), any(), any()))
                .thenReturn(
                        ChatFixtures.response("not-json", Map.of("promptTokens", 11L)),
                        ChatFixtures.response(
                                "{\"answer\":\"42\"}", Map.of("completionTokens", 0L)));

        ChatModelAction.chat(
                UUID.randomUUID(),
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                Map.class,
                reportingCtx);

        verify(chatModel, times(2)).chat(any(), any(), any());

        ExecutionReporter reporter = (ExecutionReporter) reportingCtx;
        verify(reporter, times(2))
                .reportExecutionStarted(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.LLM),
                        eq("test-model"),
                        eq(LLM_METADATA));
        verify(reporter)
                .reportExecutionSucceeded(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.LLM),
                        eq("test-model"),
                        eq(
                                Map.of(
                                        "model",
                                        "configured-model",
                                        "usage",
                                        Map.of("promptTokens", 11L))));
        verify(reporter)
                .reportExecutionSucceeded(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.LLM),
                        eq("test-model"),
                        eq(
                                Map.of(
                                        "model",
                                        "configured-model",
                                        "usage",
                                        Map.of("completionTokens", 0L))));
        verify(reporter)
                .reportExecutionFailed(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.PARSER),
                        eq(Agent.STRUCTURED_OUTPUT),
                        eq(Map.of()),
                        any(Exception.class),
                        eq(ExecutionReporter.ProblemCategories.MODEL_OUTPUT_PARSE_ERROR));
        verify(reporter, never())
                .reportExecutionFailed(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.LLM),
                        eq("test-model"),
                        eq(LLM_METADATA),
                        any(Throwable.class),
                        any());
        assertInvocationIdentities(reporter, 4);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void chatReportsEachRetriedModelInvocation(boolean async) throws Exception {
        RunnerContext reportingCtx = reportingRunnerContext();
        BaseChatModelSetup chatModel = configureReportingChatContext(reportingCtx);
        when(reportingCtx.getConfig()).thenReturn(readableConfig(1, 0, async));
        when(chatModel.chat(any(), any(), any()))
                .thenThrow(new RuntimeException("transient error"))
                .thenReturn(ChatFixtures.response("success", Map.of("completionTokens", 0L)));

        ChatModelAction.chat(
                UUID.randomUUID(),
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                null,
                reportingCtx);

        ExecutionReporter reporter = (ExecutionReporter) reportingCtx;
        verify(reporter, times(2))
                .reportExecutionStarted(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.LLM),
                        eq("test-model"),
                        eq(LLM_METADATA));
        verify(reporter)
                .reportExecutionFailed(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.LLM),
                        eq("test-model"),
                        eq(LLM_METADATA),
                        any(ChatModelInvoker.InvocationFailure.class),
                        eq(ExecutionReporter.ProblemCategories.MODEL_CALL_FAILED));
        verify(reporter)
                .reportExecutionSucceeded(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.LLM),
                        eq("test-model"),
                        eq(
                                Map.of(
                                        "model",
                                        "configured-model",
                                        "usage",
                                        Map.of("completionTokens", 0L))));
        assertInvocationIdentities(reporter, 2);
    }

    @Test
    void chatRetriesWithExponentialBackoff() throws Exception {
        // 1 second base interval; fail once then succeed -> wait 1s (1 * 2^0)
        configureRetryStrategy(3, 1);

        AtomicInteger callCount = new AtomicInteger(0);
        when(mockChatModel.chat(any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            int count = callCount.incrementAndGet();
                            if (count <= 1) {
                                throw new RuntimeException("transient error");
                            }
                            return ChatFixtures.response("success");
                        });

        UUID requestId = UUID.randomUUID();

        long startTime = System.currentTimeMillis();
        ChatModelAction.chat(
                requestId,
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                null,
                mockCtx);
        long elapsed = System.currentTimeMillis() - startTime;

        assertThat(sentEvents).hasSize(1);
        ChatResponseEvent responseEvent = ChatResponseEvent.fromEvent(sentEvents.get(0));
        assertThat(responseEvent.getRetryCount()).isEqualTo(1);
        // Exponential backoff: 1000ms (1s * 2^0) total
        // 1 retry with 1s interval = 1s total
        assertThat(responseEvent.getTotalRetryWaitSec()).isEqualTo(1);
        assertThat(elapsed).isGreaterThanOrEqualTo(1000L);

        // Retry health belongs to the ChatModel resource, not the provider connection or model.
        verify(mockActionMetricGroup).getSubGroup("model_resource", "test-model");
        verify(mockRetryCountCounter).inc(1);
        verify(mockRetryWaitSecCounter).inc(1);
    }

    @Test
    void chatExhaustsRetriesAndReturnsFailure() throws Exception {
        configureRetryStrategy(2, 0);

        when(mockChatModel.chat(any(), any(), any()))
                .thenThrow(new RuntimeException("persistent error"));

        UUID requestId = UUID.randomUUID();

        ChatModelAction.chat(
                requestId,
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                null,
                mockCtx);
        assertThat(sentEvents).hasSize(1);
        assertThat(ChatResponseEvent.fromEvent(sentEvents.get(0)).isFailed()).isTrue();
        assertThat(ChatResponseEvent.fromEvent(sentEvents.get(0)).getError())
                .contains("persistent error");

        assertThat(sentEvents).hasSize(1);
        assertThat(ChatResponseEvent.fromEvent(sentEvents.get(0)).isFailed()).isTrue();
        verify(mockActionMetricGroup).getSubGroup("model_resource", "test-model");
        verify(mockRetryCountCounter).inc(2);
        verify(mockRetryWaitSecCounter).inc(0);
    }

    @Test
    void chatResponseEventDefaultConstructorHasZeroRetryInfo() {
        UUID requestId = UUID.randomUUID();
        ChatResult msg = ChatFixtures.response("test");
        ChatResponseEvent event = ChatResponseEvent.success(requestId, msg);

        assertThat(event.getRetryCount()).isEqualTo(0);
        assertThat(event.getTotalRetryWaitSec()).isEqualTo(0);
        assertThat(event.getRequestId()).isEqualTo(requestId);
    }

    @Test
    void chatResponseEventFullConstructorCarriesRetryInfo() {
        UUID requestId = UUID.randomUUID();
        ChatResult msg = ChatFixtures.response("test");
        ChatResponseEvent event = ChatResponseEvent.success(requestId, msg, 5, 31);

        assertThat(event.getRetryCount()).isEqualTo(5);
        assertThat(event.getTotalRetryWaitSec()).isEqualTo(31);
    }

    @Test
    void retryWaitIntervalDefaultValue() {
        assertThat(AgentExecutionOptions.RETRY_WAIT_INTERVAL.getDefaultValue()).isEqualTo(1);
    }

    @Test
    void processToolResponseForwardsSavedArgumentsToChat() throws Exception {
        configureRetryStrategy(0, 0);

        UUID initialRequestId = UUID.randomUUID();
        UUID toolRequestEventId = UUID.randomUUID();
        String toolCallId = "call-1";
        Map<String, Object> savedPromptArgs = Map.of("k", "v");

        // Pre-seed sensory memory with the tool-request-event context that
        // processToolResponse will look up. This simulates a prior chat round
        // that produced a tool call.
        Map<UUID, Object> toolRequestEventContext = new HashMap<>();
        Map<String, Object> contextEntry = new HashMap<>();
        contextEntry.put("initialRequestId", initialRequestId);
        contextEntry.put("model", "test-model");
        contextEntry.put("prompt_args", savedPromptArgs);
        toolRequestEventContext.put(toolRequestEventId, contextEntry);
        sensoryMemory.set("_TOOL_REQUEST_EVENT_CONTEXT", toolRequestEventContext);

        // Pre-seed the tool-call context with the initial messages so
        // updateToolCallContext can extend them with the tool response.
        Map<UUID, Object> toolCallContext = new HashMap<>();
        toolCallContext.put(
                initialRequestId,
                new ArrayList<>(List.of(new ChatMessage(MessageRole.USER, "hi"))));
        sensoryMemory.set("_TOOL_CALL_CONTEXT", toolCallContext);

        when(mockChatModel.chat(any(), any(), any())).thenReturn(ChatFixtures.response("done"));

        ToolResponseEvent toolResponseEvent =
                new ToolResponseEvent(
                        toolRequestEventId,
                        Map.of(toolCallId, ToolResponse.text("42")),
                        Map.of(toolCallId, true),
                        Map.of());

        ChatModelAction.processChatRequestOrToolResponse(toolResponseEvent, mockCtx);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> promptArgsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mockChatModel).chat(any(), promptArgsCaptor.capture(), any());
        assertThat(promptArgsCaptor.getValue()).isEqualTo(savedPromptArgs);
    }

    @Test
    void chatRejectsTruncatedTextResponse() throws Exception {
        RunnerContext reportingCtx = reportingRunnerContext();
        BaseChatModelSetup chatModel = configureReportingChatContext(reportingCtx);
        when(chatModel.chat(any(), any(), any()))
                .thenReturn(
                        ChatFixtures.response("partial answ", Map.of("finish_reason", "length")));

        ChatModelAction.chat(
                UUID.randomUUID(),
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                null,
                reportingCtx);
        assertThat(sentEvents).hasSize(1);
        assertThat(ChatResponseEvent.fromEvent(sentEvents.get(0)).isFailed()).isTrue();
        assertThat(ChatResponseEvent.fromEvent(sentEvents.get(0)).getError()).contains("truncated");
        assertThat(ChatResponseEvent.fromEvent(sentEvents.get(0)).getError()).contains("token");

        assertThat(sentEvents).hasSize(1);
        assertThat(ChatResponseEvent.fromEvent(sentEvents.get(0)).isFailed()).isTrue();
    }

    @Test
    void chatRejectsContentFilteredTextResponse() throws Exception {
        RunnerContext reportingCtx = reportingRunnerContext();
        BaseChatModelSetup chatModel = configureReportingChatContext(reportingCtx);
        when(chatModel.chat(any(), any(), any()))
                .thenReturn(ChatFixtures.response("", Map.of("finish_reason", "content_filter")));

        // Both rejection messages interpolate the finish reason, so the literal
        // content_filter appears in either one and cannot tell them apart. These
        // match prose unique to the filtering message.
        ChatModelAction.chat(
                UUID.randomUUID(),
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                null,
                reportingCtx);
        assertThat(sentEvents).hasSize(1);
        assertThat(ChatResponseEvent.fromEvent(sentEvents.get(0)).isFailed()).isTrue();
        assertThat(ChatResponseEvent.fromEvent(sentEvents.get(0)).getError()).contains("withheld");
        assertThat(ChatResponseEvent.fromEvent(sentEvents.get(0)).getError())
                .contains("content filter");

        assertThat(sentEvents).hasSize(1);
        assertThat(ChatResponseEvent.fromEvent(sentEvents.get(0)).isFailed()).isTrue();
    }

    @Test
    void chatRejectsTruncatedToolCallResponseBeforeDispatchingTools() throws Exception {
        RunnerContext reportingCtx = reportingRunnerContext();
        BaseChatModelSetup chatModel = configureReportingChatContext(reportingCtx);
        when(chatModel.chat(any(), any(), any()))
                .thenReturn(
                        ChatFixtures.response(
                                "",
                                List.of(
                                        Map.of(
                                                "id",
                                                "call-1",
                                                "function",
                                                Map.of("name", "f", "arguments", Map.of()))),
                                Map.of("finish_reason", "length")));

        ChatModelAction.chat(
                UUID.randomUUID(),
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                null,
                reportingCtx);
        assertThat(sentEvents).hasSize(1);
        assertThat(ChatResponseEvent.fromEvent(sentEvents.get(0)).isFailed()).isTrue();
        assertThat(ChatResponseEvent.fromEvent(sentEvents.get(0)).getError()).contains("truncated");

        // A truncated tool call carries arguments the model never finished writing,
        // so no ToolRequestEvent may leave the action.
        assertThat(sentEvents).hasSize(1);
        assertThat(ChatResponseEvent.fromEvent(sentEvents.get(0)).isFailed()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "content_filter"})
    void chatRejectedFinishReasonSkipsStructuredOutput(String finishReason) throws Exception {
        RunnerContext reportingCtx = reportingRunnerContext();
        BaseChatModelSetup chatModel = configureReportingChatContext(reportingCtx);
        when(chatModel.chat(any(), any(), any()))
                .thenReturn(
                        ChatFixtures.response(
                                "{\"answer\":\"42\"}",
                                Map.of(
                                        "finish_reason",
                                        finishReason,
                                        "model_name",
                                        "provider-model",
                                        "promptTokens",
                                        100L,
                                        "completionTokens",
                                        50L)));

        ChatModelAction.chat(
                UUID.randomUUID(),
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                Map.class,
                reportingCtx);
        assertThat(sentEvents).hasSize(1);
        assertThat(ChatResponseEvent.fromEvent(sentEvents.get(0)).isFailed()).isTrue();

        ExecutionReporter reporter = (ExecutionReporter) reportingCtx;
        // The model call itself succeeded and spent its full token budget, so both
        // must be recorded before the response is rejected.
        verify(reporter)
                .reportExecutionSucceeded(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.LLM),
                        eq("test-model"),
                        eq(
                                Map.of(
                                        "model",
                                        "configured-model",
                                        "usage",
                                        Map.of("promptTokens", 100L, "completionTokens", 50L))));
        verify(chatModel).recordTokenMetrics(mockActionMetricGroup, "provider-model", 100L, 50L);
        // The parse is never attempted, so nothing about it is reported. The failed
        // check is unscoped: the rejection must not be reported as a failure of any
        // entity, the model call included.
        verify(reporter, never())
                .reportExecutionStarted(
                        anyString(), eq(ExecutionReporter.EntityTypes.PARSER), anyString(), any());
        verify(reporter, never())
                .reportExecutionSucceeded(
                        anyString(), eq(ExecutionReporter.EntityTypes.PARSER), anyString(), any());
        verify(reporter, never())
                .reportExecutionFailed(anyString(), anyString(), anyString(), any(), any(), any());
        assertThat(sentEvents).hasSize(1);
        assertThat(ChatResponseEvent.fromEvent(sentEvents.get(0)).isFailed()).isTrue();
    }

    @Test
    void chatWithoutRetriesReturnsFailedResponse() throws Exception {
        RunnerContext reportingCtx = reportingRunnerContext();
        BaseChatModelSetup chatModel = configureReportingChatContext(reportingCtx);
        when(reportingCtx.getConfig()).thenReturn(readableConfig());
        when(chatModel.chat(any(), any(), any()))
                .thenReturn(
                        ChatFixtures.response("partial answ", Map.of("finish_reason", "length")));

        // A zero retry budget produces a failed event without forwarding the truncated content.
        ChatModelAction.chat(
                UUID.randomUUID(),
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                null,
                reportingCtx);

        assertThat(sentEvents).hasSize(1);
        assertThat(ChatResponseEvent.fromEvent(sentEvents.get(0)).isFailed()).isTrue();
    }

    private static Stream<Map<String, Object>> acceptedFinishReasons() {
        return Stream.of(
                Map.of("finish_reason", "stop"),
                Map.of("finish_reason", "tool_calls"),
                Map.of("finish_reason", "some_vendor_reason"),
                Map.of());
    }

    @ParameterizedTest
    @MethodSource("acceptedFinishReasons")
    void chatAcceptedFinishReasonReachesTheResponseEvent(Map<String, Object> extraArgs)
            throws Exception {
        RunnerContext reportingCtx = reportingRunnerContext();
        BaseChatModelSetup chatModel = configureReportingChatContext(reportingCtx);
        when(chatModel.chat(any(), any(), any()))
                .thenReturn(ChatFixtures.response("hello", extraArgs));

        ChatModelAction.chat(
                UUID.randomUUID(),
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                null,
                reportingCtx);

        assertThat(sentEvents).hasSize(1);
        assertThat(ChatResponseEvent.fromEvent(sentEvents.get(0)).getResponse().getText())
                .isEqualTo("hello");
        assertThat(ChatResponseEvent.fromEvent(sentEvents.get(0)).getResponse().getFinishReason())
                .isEqualTo(extraArgs.get("finish_reason"));
    }

    /**
     * The conversion instruction that leads the schema-carrying call's message, spelled out rather
     * than read from the invoker so the assertion pins the exact words a provider receives.
     */
    private static final String FINALIZE_DIRECTIVE =
            "Convert the response below into the required structured output format."
                    + " Preserve its meaning and do not add or infer any new information.\n\n"
                    + "Response:\n";

    private static OutputSchema rowTypeSchema() {
        return new OutputSchema(
                new RowTypeInfo(
                        new TypeInformation[] {BasicTypeInfo.STRING_TYPE_INFO},
                        new String[] {"answer"}));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nativeSchemaConvertsTheFinalAnswerInASeparateSchemaCarryingCall() throws Exception {
        RunnerContext reportingCtx = reportingRunnerContext();
        BaseChatModelSetup chatModel = configureReportingChatContext(reportingCtx);
        ChatResult loopAnswer = ChatFixtures.response("the answer is 42");
        when(chatModel.chat(any(), any(), any())).thenReturn(loopAnswer);
        when(chatModel.willApplyNativeStructuredOutput(Map.class)).thenReturn(true);
        when(chatModel.chat(any(), any(), any(), any()))
                .thenReturn(ChatFixtures.response("{\"answer\":\"42\"}"));

        ChatModelAction.chat(
                UUID.randomUUID(),
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                Map.class,
                reportingCtx);

        ArgumentCaptor<List<ChatMessage>> messages = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Tool>> tools = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(chatModel)
                .chat(messages.capture(), tools.capture(), params.capture(), eq(Map.class));
        // Only the directive and the answer's text: no part of the loop's request is resent.
        List<ChatMessage> sent = messages.getValue();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getRole()).isEqualTo(MessageRole.USER);
        assertThat(sent.get(0).getText()).isEqualTo(FINALIZE_DIRECTIVE + "the answer is 42");
        assertThat(tools.getValue()).isEmpty();
        assertThat(params.getValue()).isEmpty();
        verify(chatModel, times(1)).chat(any(), any(), any());
        assertThat(durableCallIds).containsExactly("chat", "chat:final");

        // The loop's prose cannot be parsed, so a structured output proves the conversion's
        // response is the one parsed and returned.
        ChatResponseEvent event = ChatResponseEvent.fromEvent(sentEvents.get(0));
        assertThat(event.isFailed()).isFalse();
        assertThat(event.getResponse().getText()).isEqualTo("{\"answer\":\"42\"}");
        assertThat(event.getStructuredOutput()).isEqualTo(Map.of("answer", "42"));

        ExecutionReporter reporter = (ExecutionReporter) reportingCtx;
        verify(reporter, times(2))
                .reportExecutionStarted(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.LLM),
                        eq("test-model"),
                        eq(LLM_METADATA));
        verify(reporter, times(2))
                .reportExecutionSucceeded(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.LLM),
                        eq("test-model"),
                        eq(LLM_METADATA));
        assertInvocationIdentities((ExecutionReporter) reportingCtx, 3);
    }

    @Test
    void schemaKeptInThePromptIssuesNoFinalizationCall() throws Exception {
        RunnerContext reportingCtx = reportingRunnerContext();
        BaseChatModelSetup chatModel = configureReportingChatContext(reportingCtx);
        when(chatModel.chat(any(), any(), any()))
                .thenReturn(ChatFixtures.response("{\"answer\":\"42\"}"));
        when(chatModel.willApplyNativeStructuredOutput(any())).thenReturn(false);
        OutputSchema schema = rowTypeSchema();

        ChatModelAction.chat(
                UUID.randomUUID(),
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                schema,
                reportingCtx);

        // Consulted and answered no: without this the test cannot tell a suppressed
        // finalization from an invoker that never asks.
        verify(chatModel).willApplyNativeStructuredOutput(schema);
        verify(chatModel, never()).chat(any(), any(), any(), any());
        assertThat(durableCallIds).containsExactly("chat");
        ExecutionReporter reporter = (ExecutionReporter) reportingCtx;
        verify(reporter, times(1))
                .reportExecutionStarted(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.LLM),
                        eq("test-model"),
                        eq(LLM_METADATA));
        verify(reporter, times(1))
                .reportExecutionSucceeded(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.LLM),
                        eq("test-model"),
                        eq(LLM_METADATA));
        assertThat(ChatResponseEvent.fromEvent(sentEvents.get(0)).isFailed()).isFalse();
        assertInvocationIdentities((ExecutionReporter) reportingCtx, 2);
    }

    @Test
    void requestWithoutSchemaDoesNotConsultTheGate() throws Exception {
        RunnerContext reportingCtx = reportingRunnerContext();
        BaseChatModelSetup chatModel = configureReportingChatContext(reportingCtx);
        when(chatModel.chat(any(), any(), any())).thenReturn(ChatFixtures.response("hello"));

        ChatModelAction.chat(
                UUID.randomUUID(),
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                null,
                reportingCtx);

        verify(chatModel, never()).willApplyNativeStructuredOutput(any());
        verify(chatModel, never()).chat(any(), any(), any(), any());
    }

    @Test
    void toolCallResponseIssuesNoFinalizationCall() throws Exception {
        RunnerContext reportingCtx = reportingRunnerContext();
        BaseChatModelSetup chatModel = configureReportingChatContext(reportingCtx);
        when(chatModel.chat(any(), any(), any()))
                .thenReturn(
                        ChatFixtures.response(
                                "",
                                List.of(
                                        Map.of(
                                                "id",
                                                "call-1",
                                                "type",
                                                "function",
                                                "function",
                                                Map.of("name", "lookup", "arguments", Map.of())))));
        when(chatModel.willApplyNativeStructuredOutput(any())).thenReturn(true);

        ChatModelAction.chat(
                UUID.randomUUID(),
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                Map.class,
                reportingCtx);

        verify(chatModel).willApplyNativeStructuredOutput(Map.class);
        verify(chatModel, never()).chat(any(), any(), any(), any());
        assertThat(durableCallIds).containsExactly("chat");
        assertThat(sentEvents).hasSize(1);
        assertThat(sentEvents.get(0).getType()).isEqualTo(ToolRequestEvent.EVENT_TYPE);
    }

    @Test
    void finalizationCallFailureIsAModelCallFailureThatConsumesTheRetryBudget() throws Exception {
        RunnerContext reportingCtx = reportingRunnerContext();
        BaseChatModelSetup chatModel = configureReportingChatContext(reportingCtx);
        when(reportingCtx.getConfig()).thenReturn(readableConfig(1, 0));
        when(chatModel.chat(any(), any(), any()))
                .thenReturn(ChatFixtures.response("the answer is 42"));
        when(chatModel.willApplyNativeStructuredOutput(any())).thenReturn(true);
        when(chatModel.chat(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("conversion call exploded"));

        ChatModelAction.chat(
                UUID.randomUUID(),
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                Map.class,
                reportingCtx);

        // The retry repeats the whole attempt, loop call included.
        verify(chatModel, times(2)).chat(any(), any(), any());
        verify(chatModel, times(2)).chat(any(), any(), any(), any());
        ChatResponseEvent event = ChatResponseEvent.fromEvent(sentEvents.get(0));
        assertThat(event.isFailed()).isTrue();
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getError())
                .isEqualTo("java.lang.RuntimeException: conversion call exploded");

        // Per attempt: the loop call starts and succeeds, the conversion starts and fails.
        ExecutionReporter reporter = (ExecutionReporter) reportingCtx;
        verify(reporter, times(4))
                .reportExecutionStarted(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.LLM),
                        eq("test-model"),
                        eq(LLM_METADATA));
        verify(reporter, times(2))
                .reportExecutionSucceeded(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.LLM),
                        eq("test-model"),
                        eq(LLM_METADATA));
        verify(reporter, times(2))
                .reportExecutionFailed(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.LLM),
                        eq("test-model"),
                        eq(LLM_METADATA),
                        any(ChatModelInvoker.InvocationFailure.class),
                        eq(ExecutionReporter.ProblemCategories.MODEL_CALL_FAILED));
        assertInvocationIdentities((ExecutionReporter) reportingCtx, 4);
    }

    @Test
    void unparseableConversionConsumesTheRetryBudget() throws Exception {
        RunnerContext reportingCtx = reportingRunnerContext();
        BaseChatModelSetup chatModel = configureReportingChatContext(reportingCtx);
        when(reportingCtx.getConfig()).thenReturn(readableConfig(1, 0));
        when(chatModel.chat(any(), any(), any()))
                .thenReturn(ChatFixtures.response("the answer is 42"));
        when(chatModel.willApplyNativeStructuredOutput(any())).thenReturn(true);
        when(chatModel.chat(any(), any(), any(), any()))
                .thenReturn(
                        ChatFixtures.response("not-json"),
                        ChatFixtures.response("{\"answer\":\"42\"}"));

        ChatModelAction.chat(
                UUID.randomUUID(),
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                Map.class,
                reportingCtx);

        verify(chatModel, times(2)).chat(any(), any(), any());
        verify(chatModel, times(2)).chat(any(), any(), any(), any());
        assertThat(durableCallIds).containsExactly("chat", "chat:final", "chat", "chat:final");
        ChatResponseEvent event = ChatResponseEvent.fromEvent(sentEvents.get(0));
        assertThat(event.isFailed()).isFalse();
        assertThat(event.getRetryCount()).isEqualTo(1);
        ExecutionReporter reporter = (ExecutionReporter) reportingCtx;
        verify(reporter)
                .reportExecutionFailed(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.PARSER),
                        eq(Agent.STRUCTURED_OUTPUT),
                        eq(Map.of()),
                        any(Exception.class),
                        eq(ExecutionReporter.ProblemCategories.MODEL_OUTPUT_PARSE_ERROR));
        assertInvocationIdentities((ExecutionReporter) reportingCtx, 6);
    }

    @Test
    void gateIsEvaluatedOncePerInvocationAcrossRetries() throws Exception {
        RunnerContext reportingCtx = reportingRunnerContext();
        BaseChatModelSetup chatModel = configureReportingChatContext(reportingCtx);
        when(reportingCtx.getConfig()).thenReturn(readableConfig(2, 0));
        when(chatModel.chat(any(), any(), any()))
                .thenThrow(new RuntimeException("transient error"))
                .thenThrow(new RuntimeException("transient error"))
                .thenReturn(ChatFixtures.response("{\"answer\":\"42\"}"));
        when(chatModel.willApplyNativeStructuredOutput(any())).thenReturn(false);

        ChatModelAction.chat(
                UUID.randomUUID(),
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                Map.class,
                reportingCtx);

        verify(chatModel, times(3)).chat(any(), any(), any());
        verify(chatModel, times(1)).willApplyNativeStructuredOutput(any());
        assertThat(ChatResponseEvent.fromEvent(sentEvents.get(0)).isFailed()).isFalse();
    }

    @Test
    void gateFailureFailsTheRequestWithoutAModelCall() throws Exception {
        RunnerContext reportingCtx = reportingRunnerContext();
        BaseChatModelSetup chatModel = configureReportingChatContext(reportingCtx);
        when(reportingCtx.getConfig()).thenReturn(readableConfig(2, 0));
        when(chatModel.willApplyNativeStructuredOutput(any()))
                .thenThrow(new IllegalArgumentException("NATIVE cannot apply this schema"));

        // The action completes the request with a failed response instead of throwing.
        ChatModelAction.chat(
                UUID.randomUUID(),
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                Map.class,
                reportingCtx);

        verify(chatModel, never()).chat(any(), any(), any());
        verify(chatModel, never()).chat(any(), any(), any(), any());
        verify(reportingCtx, never()).durableExecute(any());
        assertThat(sentEvents).hasSize(1);
        ChatResponseEvent event = ChatResponseEvent.fromEvent(sentEvents.get(0));
        assertThat(event.isFailed()).isTrue();
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getError())
                .isEqualTo("java.lang.IllegalArgumentException: NATIVE cannot apply this schema");
        ExecutionReporter reporter = (ExecutionReporter) reportingCtx;
        verify(reporter, never())
                .reportExecutionStarted(
                        anyString(), eq(ExecutionReporter.EntityTypes.LLM), anyString(), any());
    }

    @Test
    void finalizationResponseTokensAreRecorded() throws Exception {
        RunnerContext reportingCtx = reportingRunnerContext();
        BaseChatModelSetup chatModel = configureReportingChatContext(reportingCtx);
        when(chatModel.chat(any(), any(), any()))
                .thenReturn(
                        ChatFixtures.response(
                                "the answer is 42",
                                Map.of(
                                        "model_name", "provider-model",
                                        "promptTokens", 10L,
                                        "completionTokens", 5L)));
        when(chatModel.willApplyNativeStructuredOutput(any())).thenReturn(true);
        when(chatModel.chat(any(), any(), any(), any()))
                .thenReturn(
                        ChatFixtures.response(
                                "{\"answer\":\"42\"}",
                                Map.of(
                                        "model_name", "provider-model",
                                        "promptTokens", 100L,
                                        "completionTokens", 50L)));

        ChatModelAction.chat(
                UUID.randomUUID(),
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                Map.class,
                reportingCtx);

        verify(chatModel).recordTokenMetrics(mockActionMetricGroup, "provider-model", 10L, 5L);
        verify(chatModel).recordTokenMetrics(mockActionMetricGroup, "provider-model", 100L, 50L);
        assertInvocationIdentities((ExecutionReporter) reportingCtx, 3);
        ExecutionReporter reporter = (ExecutionReporter) reportingCtx;
        verify(reporter)
                .reportExecutionSucceeded(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.LLM),
                        eq("test-model"),
                        eq(
                                Map.of(
                                        "model",
                                        "configured-model",
                                        "usage",
                                        Map.of("promptTokens", 10L, "completionTokens", 5L))));
        verify(reporter)
                .reportExecutionSucceeded(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.LLM),
                        eq("test-model"),
                        eq(
                                Map.of(
                                        "model",
                                        "configured-model",
                                        "usage",
                                        Map.of("promptTokens", 100L, "completionTokens", 50L))));
    }

    @Test
    void truncatedConversionIsRejectedBeforeParsing() throws Exception {
        RunnerContext reportingCtx = reportingRunnerContext();
        BaseChatModelSetup chatModel = configureReportingChatContext(reportingCtx);
        when(chatModel.chat(any(), any(), any()))
                .thenReturn(ChatFixtures.response("the answer is 42"));
        when(chatModel.willApplyNativeStructuredOutput(any())).thenReturn(true);
        when(chatModel.chat(any(), any(), any(), any()))
                .thenReturn(
                        ChatFixtures.response(
                                "{\"answer\":\"4",
                                Map.of(
                                        "finish_reason",
                                        "length",
                                        "promptTokens",
                                        9L,
                                        "completionTokens",
                                        0L,
                                        "model_name",
                                        "provider-model")));

        ChatModelAction.chat(
                UUID.randomUUID(),
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                Map.of(),
                Map.class,
                reportingCtx);

        ChatResponseEvent event = ChatResponseEvent.fromEvent(sentEvents.get(0));
        assertThat(event.isFailed()).isTrue();
        assertThat(event.getError()).contains("truncated");
        ExecutionReporter reporter = (ExecutionReporter) reportingCtx;
        verify(reporter, never())
                .reportExecutionStarted(
                        anyString(), eq(ExecutionReporter.EntityTypes.PARSER), anyString(), any());
        assertInvocationIdentities((ExecutionReporter) reportingCtx, 2);
        verify(chatModel).recordTokenMetrics(mockActionMetricGroup, "provider-model", 9L, 0L);
        verify(reporter)
                .reportExecutionSucceeded(
                        anyString(),
                        eq(ExecutionReporter.EntityTypes.LLM),
                        eq("test-model"),
                        eq(
                                Map.of(
                                        "model",
                                        "configured-model",
                                        "usage",
                                        Map.of("promptTokens", 9L, "completionTokens", 0L))));
    }

    // --- Helper methods ---

    private static void assertInvocationIdentities(ExecutionReporter reporter, int expectedCalls) {
        Map<String, List<Invocation>> calls = new LinkedHashMap<>();
        for (Invocation report : mockingDetails(reporter).getInvocations()) {
            if (!report.getMethod().getName().startsWith("reportExecution")) {
                continue;
            }
            String executionId = report.getArgument(0);
            assertThat(executionId).isNotBlank();
            calls.computeIfAbsent(executionId, ignored -> new ArrayList<>()).add(report);
        }
        assertThat(calls).hasSize(expectedCalls);
        for (List<Invocation> reports : calls.values()) {
            assertThat(reports).hasSize(2);
            assertThat(reports.get(0).getMethod().getName()).isEqualTo("reportExecutionStarted");
            assertThat(reports.get(1).getMethod().getName())
                    .isIn("reportExecutionSucceeded", "reportExecutionFailed");
            assertThat(reports.get(1).<String>getArgument(1))
                    .isEqualTo(reports.get(0).<String>getArgument(1));
            assertThat(reports.get(1).<String>getArgument(2))
                    .isEqualTo(reports.get(0).<String>getArgument(2));
        }
    }

    private void configureRetryStrategy(int maxRetries, int waitIntervalSec) {
        when(mockCtx.getConfig()).thenReturn(readableConfig(maxRetries, waitIntervalSec));
    }

    private RunnerContext reportingRunnerContext() {
        return mock(RunnerContext.class, withSettings().extraInterfaces(ExecutionReporter.class));
    }

    private BaseChatModelSetup configureReportingChatContext(RunnerContext reportingCtx)
            throws Exception {
        BaseChatModelSetup chatModel = mock(BaseChatModelSetup.class);
        MemoryObject memory = createStatefulMemoryObject();

        when(chatModel.getConnectionName()).thenReturn("test-connection");
        when(chatModel.getModel()).thenReturn("configured-model");
        when(reportingCtx.getResource(anyString(), eq(ResourceType.CHAT_MODEL)))
                .thenReturn(chatModel);
        when(reportingCtx.getSensoryMemory()).thenReturn(memory);
        when(reportingCtx.getActionMetricGroup()).thenReturn(mockActionMetricGroup);
        when(reportingCtx.durableExecute(any()))
                .thenAnswer(
                        invocation -> {
                            DurableCallable<?> callable = invocation.getArgument(0);
                            durableCallIds.add(callable.getId());
                            return callable.call();
                        });
        when(reportingCtx.durableExecuteAsync(any()))
                .thenAnswer(
                        invocation -> {
                            DurableCallable<?> callable = invocation.getArgument(0);
                            durableCallIds.add(callable.getId());
                            return new TestDurableFuture<>(callable.getId(), callable::call);
                        });
        doAnswer(inv -> sentEvents.add(inv.getArgument(0))).when(reportingCtx).sendEvent(any());
        when(reportingCtx.getConfig()).thenReturn(readableConfig());
        return chatModel;
    }

    private AgentConfiguration readableConfig() {
        return readableConfig(0, 0);
    }

    private AgentConfiguration readableConfig(int maxRetries, int retryWaitIntervalSec) {
        return readableConfig(maxRetries, retryWaitIntervalSec, false);
    }

    private AgentConfiguration readableConfig(
            int maxRetries, int retryWaitIntervalSec, boolean async) {
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentExecutionOptions.MAX_RETRIES, maxRetries);
        config.set(AgentExecutionOptions.RETRY_WAIT_INTERVAL, retryWaitIntervalSec);
        config.set(AgentExecutionOptions.CHAT_ASYNC, async);
        return config;
    }

    /**
     * Creates a stateful MemoryObject backed by a HashMap, supporting isExist/get/set operations
     * needed by the retry stats accumulation logic.
     */
    private static MemoryObject createStatefulMemoryObject() {
        Map<String, Object> store = new HashMap<>();

        MemoryObject memoryObject = mock(MemoryObject.class);

        when(memoryObject.isExist(anyString()))
                .thenAnswer(inv -> store.containsKey(inv.<String>getArgument(0)));

        try {
            when(memoryObject.get(anyString()))
                    .thenAnswer(
                            inv -> {
                                String path = inv.getArgument(0);
                                Object value = store.get(path);
                                if (value == null) {
                                    throw new Exception("Path not found: " + path);
                                }
                                MemoryObject valueObj = mock(MemoryObject.class);
                                when(valueObj.getValue()).thenReturn(value);
                                return valueObj;
                            });

            when(memoryObject.set(anyString(), any()))
                    .thenAnswer(
                            inv -> {
                                store.put(inv.getArgument(0), inv.getArgument(1));
                                return null;
                            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return memoryObject;
    }
}
