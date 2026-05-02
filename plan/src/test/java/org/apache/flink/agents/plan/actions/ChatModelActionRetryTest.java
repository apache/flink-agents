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
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.metrics.Counter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Tests for retry behavior in {@link ChatModelAction}. */
class ChatModelActionRetryTest {

    @Mock private RunnerContext mockCtx;

    @Mock private BaseChatModelSetup mockChatModel;

    @Mock private FlinkAgentsMetricGroup mockActionMetricGroup;

    @Mock private FlinkAgentsMetricGroup mockModelMetricGroup;

    @Mock private Counter mockRetryCountCounter;

    @Mock private Counter mockRetryWaitSecCounter;

    private MemoryObject sensoryMemory;
    private List<Event> sentEvents;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        sentEvents = new ArrayList<>();
        sensoryMemory = createStatefulMemoryObject();

        // Wire up ChatModel
        when(mockChatModel.getConnectionName()).thenReturn("test-connection");

        // Wire up RunnerContext
        when(mockCtx.getResource(anyString(), eq(ResourceType.CHAT_MODEL)))
                .thenReturn(mockChatModel);
        when(mockCtx.getSensoryMemory()).thenReturn(sensoryMemory);
        when(mockCtx.getActionMetricGroup()).thenReturn(mockActionMetricGroup);
        doAnswer(inv -> sentEvents.add(inv.getArgument(0))).when(mockCtx).sendEvent(any());
        when(mockCtx.<ChatMessage>durableExecute(any()))
                .thenAnswer(inv -> inv.<DurableCallable<ChatMessage>>getArgument(0).call());

        // Wire up metric group chain
        when(mockActionMetricGroup.getSubGroup(anyString())).thenReturn(mockModelMetricGroup);
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
    void chatSucceedsWithoutRetry_retryCountIsZero() throws Exception {
        configureRetryStrategy(3, 1);
        when(mockChatModel.chat(any(), any()))
                .thenReturn(new ChatMessage(MessageRole.ASSISTANT, "hello"));

        UUID requestId = UUID.randomUUID();
        ChatModelAction.chat(
                requestId,
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
                null,
                mockCtx);

        assertThat(sentEvents).hasSize(1);
        ChatResponseEvent responseEvent = ChatResponseEvent.fromEvent(sentEvents.get(0));
        assertThat(responseEvent.getRetryCount()).isEqualTo(0);
        assertThat(responseEvent.getTotalRetryWaitSec()).isEqualTo(0);

        // No retry metrics should be recorded
        verify(mockActionMetricGroup, never()).getSubGroup(anyString());
    }

    @Test
    void chatRetriesWithExponentialBackoff() throws Exception {
        // 1 second base interval; fail once then succeed -> wait 1s (1 * 2^0)
        configureRetryStrategy(3, 1);

        AtomicInteger callCount = new AtomicInteger(0);
        when(mockChatModel.chat(any(), any()))
                .thenAnswer(
                        inv -> {
                            int count = callCount.incrementAndGet();
                            if (count <= 1) {
                                throw new RuntimeException("transient error");
                            }
                            return new ChatMessage(MessageRole.ASSISTANT, "success");
                        });

        UUID requestId = UUID.randomUUID();

        long startTime = System.currentTimeMillis();
        ChatModelAction.chat(
                requestId,
                "test-model",
                List.of(new ChatMessage(MessageRole.USER, "hi")),
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

        // Verify metrics recorded under connection name
        verify(mockActionMetricGroup).getSubGroup(mockChatModel.getConnectionName());
        verify(mockRetryCountCounter).inc(1);
        verify(mockRetryWaitSecCounter).inc(1);
    }

    @Test
    void chatExhaustsRetriesAndThrows() {
        configureRetryStrategy(2, 0);

        when(mockChatModel.chat(any(), any())).thenThrow(new RuntimeException("persistent error"));

        UUID requestId = UUID.randomUUID();

        assertThatThrownBy(
                        () ->
                                ChatModelAction.chat(
                                        requestId,
                                        "test-model",
                                        List.of(new ChatMessage(MessageRole.USER, "hi")),
                                        null,
                                        mockCtx))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("persistent error");

        assertThat(sentEvents).isEmpty();
    }

    @Test
    void chatResponseEventDefaultConstructorHasZeroRetryInfo() {
        UUID requestId = UUID.randomUUID();
        ChatMessage msg = new ChatMessage(MessageRole.ASSISTANT, "test");
        ChatResponseEvent event = new ChatResponseEvent(requestId, msg);

        assertThat(event.getRetryCount()).isEqualTo(0);
        assertThat(event.getTotalRetryWaitSec()).isEqualTo(0);
        assertThat(event.getRequestId()).isEqualTo(requestId);
    }

    @Test
    void chatResponseEventFullConstructorCarriesRetryInfo() {
        UUID requestId = UUID.randomUUID();
        ChatMessage msg = new ChatMessage(MessageRole.ASSISTANT, "test");
        ChatResponseEvent event = new ChatResponseEvent(requestId, msg, 5, 31);

        assertThat(event.getRetryCount()).isEqualTo(5);
        assertThat(event.getTotalRetryWaitSec()).isEqualTo(31);
    }

    @Test
    void retryWaitIntervalDefaultValue() {
        assertThat(AgentExecutionOptions.RETRY_WAIT_INTERVAL.getDefaultValue()).isEqualTo(1);
    }

    // --- Helper methods ---

    private void configureRetryStrategy(int maxRetries, int waitIntervalSec) {
        when(mockCtx.getConfig())
                .thenAnswer(
                        inv -> {
                            // Return a mock ReadableConfiguration
                            return new org.apache.flink.agents.api.configuration
                                    .ReadableConfiguration() {
                                @Override
                                @SuppressWarnings("unchecked")
                                public <T> T get(
                                        org.apache.flink.agents.api.configuration.ConfigOption<T>
                                                option) {
                                    if (option == AgentExecutionOptions.ERROR_HANDLING_STRATEGY) {
                                        return (T) Agent.ErrorHandlingStrategy.RETRY;
                                    }
                                    if (option == AgentExecutionOptions.MAX_RETRIES) {
                                        return (T) Integer.valueOf(maxRetries);
                                    }
                                    if (option == AgentExecutionOptions.RETRY_WAIT_INTERVAL) {
                                        return (T) Integer.valueOf(waitIntervalSec);
                                    }
                                    if (option == AgentExecutionOptions.CHAT_ASYNC) {
                                        return (T) Boolean.FALSE;
                                    }
                                    return option.getDefaultValue();
                                }

                                @Override
                                public Integer getInt(String key, Integer defaultValue) {
                                    return defaultValue;
                                }

                                @Override
                                public Long getLong(String key, Long defaultValue) {
                                    return defaultValue;
                                }

                                @Override
                                public Float getFloat(String key, Float defaultValue) {
                                    return defaultValue;
                                }

                                @Override
                                public Double getDouble(String key, Double defaultValue) {
                                    return defaultValue;
                                }

                                @Override
                                public Boolean getBool(String key, Boolean defaultValue) {
                                    return defaultValue;
                                }

                                @Override
                                public String getStr(String key, String defaultValue) {
                                    return defaultValue;
                                }
                            };
                        });
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
