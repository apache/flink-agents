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
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.metrics.Counter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Test cases for BaseChatModelConnection token metrics functionality. */
class BaseChatModelConnectionTokenMetricsTest {

    private TestChatModelConnection connection;
    private FlinkAgentsMetricGroup mockMetricGroup;
    private FlinkAgentsMetricGroup mockModelGroup;
    private Counter mockPromptTokensCounter;
    private Counter mockCompletionTokensCounter;

    /** Test implementation of BaseChatModelConnection for testing purposes. */
    private static class TestChatModelConnection extends BaseChatModelConnection {

        public TestChatModelConnection(
                ResourceDescriptor descriptor,
                BiFunction<String, ResourceType, Resource> getResource) {
            super(descriptor, getResource);
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages, List<Tool> tools, Map<String, Object> arguments) {
            // Simple test implementation
            return new ChatMessage(MessageRole.ASSISTANT, "Test response");
        }

        // Expose protected method for testing
        public void testRecordTokenMetrics(
                String modelName, long promptTokens, long completionTokens) {
            recordTokenMetrics(modelName, promptTokens, completionTokens);
        }
    }

    @BeforeEach
    void setUp() {
        connection =
                new TestChatModelConnection(
                        new ResourceDescriptor(
                                TestChatModelConnection.class.getName(), Collections.emptyMap()),
                        null);

        // Create mock objects
        mockMetricGroup = mock(FlinkAgentsMetricGroup.class);
        mockModelGroup = mock(FlinkAgentsMetricGroup.class);
        mockPromptTokensCounter = mock(Counter.class);
        mockCompletionTokensCounter = mock(Counter.class);

        // Set up mock behavior
        when(mockMetricGroup.getSubGroup("gpt-4")).thenReturn(mockModelGroup);
        when(mockModelGroup.getCounter("promptTokens")).thenReturn(mockPromptTokensCounter);
        when(mockModelGroup.getCounter("completionTokens")).thenReturn(mockCompletionTokensCounter);
    }

    @Test
    @DisplayName("Test token metrics are recorded when metric group is set")
    void testRecordTokenMetricsWithMetricGroup() {
        // Set the metric group
        connection.setMetricGroup(mockMetricGroup);

        // Record token metrics
        connection.testRecordTokenMetrics("gpt-4", 100, 50);

        // Verify the metrics were recorded
        verify(mockMetricGroup).getSubGroup("gpt-4");
        verify(mockModelGroup).getCounter("promptTokens");
        verify(mockModelGroup).getCounter("completionTokens");
        verify(mockPromptTokensCounter).inc(100);
        verify(mockCompletionTokensCounter).inc(50);
    }

    @Test
    @DisplayName("Test token metrics are not recorded when metric group is null")
    void testRecordTokenMetricsWithoutMetricGroup() {
        // Do not set metric group (should be null by default)

        // Record token metrics - should not throw
        assertDoesNotThrow(() -> connection.testRecordTokenMetrics("gpt-4", 100, 50));

        // No metrics should be recorded
        verifyNoInteractions(mockMetricGroup);
    }

    @Test
    @DisplayName("Test token metrics hierarchy: actionMetricGroup -> modelName -> counters")
    void testTokenMetricsHierarchy() {
        // Set the metric group
        connection.setMetricGroup(mockMetricGroup);

        // Record token metrics for different models
        FlinkAgentsMetricGroup mockGpt35Group = mock(FlinkAgentsMetricGroup.class);
        Counter mockGpt35PromptCounter = mock(Counter.class);
        Counter mockGpt35CompletionCounter = mock(Counter.class);

        when(mockMetricGroup.getSubGroup("gpt-3.5-turbo")).thenReturn(mockGpt35Group);
        when(mockGpt35Group.getCounter("promptTokens")).thenReturn(mockGpt35PromptCounter);
        when(mockGpt35Group.getCounter("completionTokens")).thenReturn(mockGpt35CompletionCounter);

        // Record for gpt-4
        connection.testRecordTokenMetrics("gpt-4", 100, 50);

        // Record for gpt-3.5-turbo
        connection.testRecordTokenMetrics("gpt-3.5-turbo", 200, 100);

        // Verify each model has its own counters
        verify(mockMetricGroup).getSubGroup("gpt-4");
        verify(mockMetricGroup).getSubGroup("gpt-3.5-turbo");
        verify(mockPromptTokensCounter).inc(100);
        verify(mockCompletionTokensCounter).inc(50);
        verify(mockGpt35PromptCounter).inc(200);
        verify(mockGpt35CompletionCounter).inc(100);
    }

    @Test
    @DisplayName("Test resource type is CHAT_MODEL_CONNECTION")
    void testResourceType() {
        assertEquals(ResourceType.CHAT_MODEL_CONNECTION, connection.getResourceType());
    }
}
