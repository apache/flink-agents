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

import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.metrics.UpdatableGauge;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.Histogram;
import org.apache.flink.metrics.Meter;
import org.apache.flink.metrics.SimpleCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Test cases for BaseChatModelSetup token metrics functionality. */
class BaseChatModelSetupTokenMetricsTest {

    private TestChatModelSetup setup;
    private FlinkAgentsMetricGroup mockMetricGroup;
    private FlinkAgentsMetricGroup mockModelGroup;
    private Counter mockPromptTokensCounter;
    private Counter mockCompletionTokensCounter;

    /** Test implementation of BaseChatModelSetup for testing purposes. */
    private static class TestChatModelSetup extends BaseChatModelSetup {

        public TestChatModelSetup(ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public Map<String, Object> getParameters() {
            return Collections.emptyMap();
        }
    }

    @BeforeEach
    void setUp() {
        setup =
                new TestChatModelSetup(
                        new ResourceDescriptor(
                                TestChatModelSetup.class.getName(), Collections.emptyMap()),
                        null);

        mockMetricGroup = mock(FlinkAgentsMetricGroup.class);
        mockModelGroup = mock(FlinkAgentsMetricGroup.class);
        mockPromptTokensCounter = mock(Counter.class);
        mockCompletionTokensCounter = mock(Counter.class);

        when(mockMetricGroup.getSubGroup("model", "gpt-4")).thenReturn(mockModelGroup);
        when(mockModelGroup.getCounter("promptTokens")).thenReturn(mockPromptTokensCounter);
        when(mockModelGroup.getCounter("completionTokens")).thenReturn(mockCompletionTokensCounter);
    }

    @Test
    @DisplayName("Test token metrics are recorded when metric group is set")
    void testRecordTokenMetricsWithMetricGroup() {
        setup.recordTokenMetrics(mockMetricGroup, "gpt-4", 100, 50);

        verify(mockMetricGroup).getSubGroup("model", "gpt-4");
        verify(mockModelGroup).getCounter("promptTokens");
        verify(mockModelGroup).getCounter("completionTokens");
        verify(mockPromptTokensCounter).inc(100);
        verify(mockCompletionTokensCounter).inc(50);
    }

    @Test
    @DisplayName("Test token metrics use the request-scoped metric group")
    void testRecordTokenMetricsWithRequestScopedMetricGroup() {
        TestMetricGroup actionA = new TestMetricGroup();
        TestMetricGroup actionB = new TestMetricGroup();

        setup.setMetricGroup(actionB);
        setup.recordTokenMetrics(actionA, "gpt-4", 100, 50);

        TestMetricGroup actionAModelGroup = (TestMetricGroup) actionA.getSubGroup("model", "gpt-4");
        assertEquals(100, actionAModelGroup.counters.get("promptTokens").getCount());
        assertEquals(50, actionAModelGroup.counters.get("completionTokens").getCount());

        TestMetricGroup actionBModelGroup = (TestMetricGroup) actionB.getSubGroup("model", "gpt-4");
        assertEquals(0, actionBModelGroup.getCounter("promptTokens").getCount());
        assertEquals(0, actionBModelGroup.getCounter("completionTokens").getCount());
    }

    @Test
    @DisplayName("Test token metrics hierarchy: metricGroup -> modelName -> counters")
    void testTokenMetricsHierarchy() {
        FlinkAgentsMetricGroup mockGpt35Group = mock(FlinkAgentsMetricGroup.class);
        Counter mockGpt35PromptCounter = mock(Counter.class);
        Counter mockGpt35CompletionCounter = mock(Counter.class);

        when(mockMetricGroup.getSubGroup("model", "gpt-3.5-turbo")).thenReturn(mockGpt35Group);
        when(mockGpt35Group.getCounter("promptTokens")).thenReturn(mockGpt35PromptCounter);
        when(mockGpt35Group.getCounter("completionTokens")).thenReturn(mockGpt35CompletionCounter);

        setup.recordTokenMetrics(mockMetricGroup, "gpt-4", 100, 50);
        setup.recordTokenMetrics(mockMetricGroup, "gpt-3.5-turbo", 200, 100);

        verify(mockMetricGroup).getSubGroup("model", "gpt-4");
        verify(mockMetricGroup).getSubGroup("model", "gpt-3.5-turbo");
        verify(mockPromptTokensCounter).inc(100);
        verify(mockCompletionTokensCounter).inc(50);
        verify(mockGpt35PromptCounter).inc(200);
        verify(mockGpt35CompletionCounter).inc(100);
    }

    @Test
    @DisplayName("Test resource type is CHAT_MODEL")
    void testResourceType() {
        assertEquals(ResourceType.CHAT_MODEL, setup.getResourceType());
    }

    // ========================================================================
    // Value-based tests using TestMetricGroup (non-Mockito)
    // ========================================================================

    private static class TestMetricGroup implements FlinkAgentsMetricGroup {
        final Map<String, TestMetricGroup> subGroups = new HashMap<>();
        final Map<String, SimpleCounter> counters = new HashMap<>();

        @Override
        public FlinkAgentsMetricGroup getSubGroup(String name) {
            return subGroups.computeIfAbsent(name, k -> new TestMetricGroup());
        }

        @Override
        public FlinkAgentsMetricGroup getSubGroup(String key, String value) {
            return subGroups.computeIfAbsent(key + "=" + value, k -> new TestMetricGroup());
        }

        @Override
        public Counter getCounter(String name) {
            return counters.computeIfAbsent(name, k -> new SimpleCounter());
        }

        @Override
        public UpdatableGauge getGauge(String name) {
            return null;
        }

        @Override
        public Meter getMeter(String name) {
            return null;
        }

        @Override
        public Meter getMeter(String name, Counter counter) {
            return null;
        }

        @Override
        public Histogram getHistogram(String name) {
            return null;
        }

        @Override
        public Histogram getHistogram(String name, int windowSize) {
            return null;
        }
    }

    @Test
    @DisplayName("Value-based: token counters are accessible under model key-value group")
    void testTokenMetricsUnderModelKeyValueGroup() {
        TestMetricGroup root = new TestMetricGroup();

        setup.recordTokenMetrics(root, "gpt-4", 100, 50);

        TestMetricGroup modelGroup = (TestMetricGroup) root.getSubGroup("model", "gpt-4");
        assertEquals(100, modelGroup.counters.get("promptTokens").getCount());
        assertEquals(50, modelGroup.counters.get("completionTokens").getCount());
    }

    @Test
    @DisplayName("Value-based: different models have independent counters")
    void testDifferentModelsHaveIndependentCounters() {
        TestMetricGroup root = new TestMetricGroup();

        setup.recordTokenMetrics(root, "gpt-4", 100, 50);
        setup.recordTokenMetrics(root, "gpt-3.5-turbo", 200, 80);

        TestMetricGroup gpt4 = (TestMetricGroup) root.getSubGroup("model", "gpt-4");
        TestMetricGroup gpt35 = (TestMetricGroup) root.getSubGroup("model", "gpt-3.5-turbo");

        assertEquals(100, gpt4.counters.get("promptTokens").getCount());
        assertEquals(50, gpt4.counters.get("completionTokens").getCount());
        assertEquals(200, gpt35.counters.get("promptTokens").getCount());
        assertEquals(80, gpt35.counters.get("completionTokens").getCount());
    }

    @Test
    @DisplayName("Value-based: counters accumulate across multiple calls")
    void testCountersAccumulate() {
        TestMetricGroup root = new TestMetricGroup();

        setup.recordTokenMetrics(root, "gpt-4", 100, 50);
        setup.recordTokenMetrics(root, "gpt-4", 150, 75);

        TestMetricGroup modelGroup = (TestMetricGroup) root.getSubGroup("model", "gpt-4");
        assertEquals(250, modelGroup.counters.get("promptTokens").getCount());
        assertEquals(125, modelGroup.counters.get("completionTokens").getCount());
    }
}
