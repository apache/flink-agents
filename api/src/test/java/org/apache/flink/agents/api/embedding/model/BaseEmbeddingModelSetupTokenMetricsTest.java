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

package org.apache.flink.agents.api.embedding.model;

import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.metrics.UpdatableGauge;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.Histogram;
import org.apache.flink.metrics.Meter;
import org.apache.flink.metrics.SimpleCounter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Test cases for embedding model token metrics. */
class BaseEmbeddingModelSetupTokenMetricsTest {

    private static class TestEmbeddingModelSetup extends BaseEmbeddingModelSetup {

        TestEmbeddingModelSetup(BaseEmbeddingModelConnection connection) {
            super(
                    new ResourceDescriptor(
                            TestEmbeddingModelSetup.class.getName(),
                            Map.of("connection", "mock-connection", "model", "mock-model")),
                    mock(ResourceContext.class));
            this.connection = connection;
        }

        @Override
        public Map<String, Object> getParameters() {
            return new HashMap<>();
        }
    }

    private static class TestEmbeddingModelConnection extends BaseEmbeddingModelConnection {

        TestEmbeddingModelConnection() {
            super(
                    new ResourceDescriptor(
                            TestEmbeddingModelConnection.class.getName(), Collections.emptyMap()),
                    mock(ResourceContext.class));
        }

        @Override
        public float[] embed(String text, Map<String, Object> parameters) {
            return new float[] {0.1f, 0.2f};
        }

        @Override
        public EmbeddingResult<float[]> embedWithUsage(
                String text, Map<String, Object> parameters) {
            return new EmbeddingResult<>(embed(text, parameters), new EmbeddingTokenUsage(7L, 9L));
        }

        @Override
        public List<float[]> embed(List<String> texts, Map<String, Object> parameters) {
            List<float[]> embeddings = new ArrayList<>();
            for (String ignored : texts) {
                embeddings.add(new float[] {0.1f, 0.2f});
            }
            return embeddings;
        }

        @Override
        public EmbeddingResult<List<float[]>> embedWithUsage(
                List<String> texts, Map<String, Object> parameters) {
            return new EmbeddingResult<>(
                    embed(texts, parameters), new EmbeddingTokenUsage(11L, 13L));
        }
    }

    private static class TestEmbeddingModelConnectionWithoutUsage
            extends BaseEmbeddingModelConnection {

        TestEmbeddingModelConnectionWithoutUsage() {
            super(
                    new ResourceDescriptor(
                            TestEmbeddingModelConnectionWithoutUsage.class.getName(),
                            Collections.emptyMap()),
                    mock(ResourceContext.class));
        }

        @Override
        public float[] embed(String text, Map<String, Object> parameters) {
            return new float[] {0.1f, 0.2f};
        }

        @Override
        public List<float[]> embed(List<String> texts, Map<String, Object> parameters) {
            List<float[]> embeddings = new ArrayList<>();
            for (String ignored : texts) {
                embeddings.add(new float[] {0.1f, 0.2f});
            }
            return embeddings;
        }
    }

    private static class ThrowThenReportUsageConnection extends BaseEmbeddingModelConnection {
        private int calls;

        ThrowThenReportUsageConnection() {
            super(
                    new ResourceDescriptor(
                            ThrowThenReportUsageConnection.class.getName(), Collections.emptyMap()),
                    mock(ResourceContext.class));
        }

        @Override
        public float[] embed(String text, Map<String, Object> parameters) {
            return new float[] {0.1f, 0.2f};
        }

        @Override
        public EmbeddingResult<float[]> embedWithUsage(
                String text, Map<String, Object> parameters) {
            calls++;
            if (calls == 1) {
                throw new RuntimeException("provider failure");
            }
            return new EmbeddingResult<>(embed(text, parameters), new EmbeddingTokenUsage(3L, 4L));
        }

        @Override
        public List<float[]> embed(List<String> texts, Map<String, Object> parameters) {
            List<float[]> embeddings = new ArrayList<>();
            for (String ignored : texts) {
                embeddings.add(new float[] {0.1f, 0.2f});
            }
            return embeddings;
        }
    }

    @Test
    void testEmbeddingTokenMetricsAreRecordedWhenUsageIsReported() {
        TestEmbeddingModelSetup setup =
                new TestEmbeddingModelSetup(new TestEmbeddingModelConnection());
        TestMetricGroup metricGroup = new TestMetricGroup();
        setup.setMetricGroup(metricGroup);

        assertArrayEquals(new float[] {0.1f, 0.2f}, setup.embed("hello"));

        TestMetricGroup modelGroup =
                (TestMetricGroup) metricGroup.getSubGroup("model", "mock-model");
        assertEquals(7L, modelGroup.counters.get("promptTokens").getCount());
        assertEquals(9L, modelGroup.counters.get("totalTokens").getCount());
    }

    @Test
    void testEmbeddingTokenMetricsAreNoopWhenUsageIsAbsent() {
        TestEmbeddingModelSetup setup =
                new TestEmbeddingModelSetup(new TestEmbeddingModelConnectionWithoutUsage());
        FlinkAgentsMetricGroup metricGroup = mock(FlinkAgentsMetricGroup.class);
        setup.setMetricGroup(metricGroup);

        setup.embed("hello");

        verifyNoInteractions(metricGroup);
    }

    @Test
    void testEmbeddingTokenMetricsAccumulateAcrossRequests() {
        TestEmbeddingModelSetup setup =
                new TestEmbeddingModelSetup(new TestEmbeddingModelConnection());
        TestMetricGroup metricGroup = new TestMetricGroup();
        setup.setMetricGroup(metricGroup);

        setup.embed("hello");
        setup.embed(List.of("hello", "flink"));

        TestMetricGroup modelGroup =
                (TestMetricGroup) metricGroup.getSubGroup("model", "mock-model");
        assertEquals(18L, modelGroup.counters.get("promptTokens").getCount());
        assertEquals(22L, modelGroup.counters.get("totalTokens").getCount());
    }

    @Test
    void testEmbeddingTokenMetricsDoNotLeakAfterProviderFailure() {
        TestEmbeddingModelSetup setup =
                new TestEmbeddingModelSetup(new ThrowThenReportUsageConnection());
        TestMetricGroup metricGroup = new TestMetricGroup();
        setup.setMetricGroup(metricGroup);

        assertThrows(RuntimeException.class, () -> setup.embed("first"));
        assertArrayEquals(new float[] {0.1f, 0.2f}, setup.embed("second"));

        TestMetricGroup modelGroup =
                (TestMetricGroup) metricGroup.getSubGroup("model", "mock-model");
        assertEquals(3L, modelGroup.counters.get("promptTokens").getCount());
        assertEquals(4L, modelGroup.counters.get("totalTokens").getCount());
    }

    private static class TestMetricGroup implements FlinkAgentsMetricGroup {
        final Map<String, TestMetricGroup> subGroups = new HashMap<>();
        final Map<String, SimpleCounter> counters = new HashMap<>();

        @Override
        public FlinkAgentsMetricGroup getSubGroup(String name) {
            return subGroups.computeIfAbsent(name, ignored -> new TestMetricGroup());
        }

        @Override
        public FlinkAgentsMetricGroup getSubGroup(String key, String value) {
            return subGroups.computeIfAbsent(key + "=" + value, ignored -> new TestMetricGroup());
        }

        @Override
        public UpdatableGauge getGauge(String name) {
            return null;
        }

        @Override
        public Counter getCounter(String name) {
            return counters.computeIfAbsent(name, ignored -> new SimpleCounter());
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
}
