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
 * See the License for the specific language governing permissions of
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Test cases for embedding token usage metrics recorded by {@link BaseEmbeddingModelSetup}. Mirrors
 * {@code BaseChatModelSetupTokenMetricsTest}: embedding providers already populate {@link
 * EmbeddingTokenUsage} on the returned {@link EmbeddingResult}, but nothing records it until this
 * setup reads it back at the {@code embedWithUsage} chokepoint.
 */
class BaseEmbeddingModelSetupTokenMetricsTest {

    /** Value-based metric group that mirrors the one in the chat token-metrics test. */
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

    private static final float[] VEC = new float[] {0.1f, 0.2f};

    /**
     * Builds a setup bound to a connection that reports the given usage on single-text embed, with
     * the given model name in its descriptor (may be {@code null} to exercise the guard).
     */
    private static BaseEmbeddingModelSetup setupWithSingleUsageAndModel(
            EmbeddingTokenUsage usage, String model) {
        BaseEmbeddingModelSetup setup =
                new BaseEmbeddingModelSetup(
                        new ResourceDescriptor("test", descriptorArgs(model)),
                        mock(ResourceContext.class)) {
                    @Override
                    public Map<String, Object> getParameters() {
                        return new HashMap<>();
                    }
                };
        setup.connection =
                new BaseEmbeddingModelConnection(
                        new ResourceDescriptor("conn", Collections.emptyMap()),
                        mock(ResourceContext.class)) {
                    @Override
                    public float[] embed(String text, Map<String, Object> parameters) {
                        return VEC;
                    }

                    @Override
                    public List<float[]> embed(List<String> texts, Map<String, Object> parameters) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public EmbeddingResult<float[]> embedWithUsage(
                            String text, Map<String, Object> parameters) {
                        return new EmbeddingResult<>(VEC, usage);
                    }
                };
        return setup;
    }

    /** Builds a setup bound to a connection that reports the given usage on single-text embed. */
    private static BaseEmbeddingModelSetup setupWithSingleUsage(EmbeddingTokenUsage usage) {
        return setupWithSingleUsageAndModel(usage, "bedrock-text");
    }

    /** Descriptor args with an optional model (omitted when null/blank so it stays unset). */
    private static Map<String, String> descriptorArgs(String model) {
        Map<String, String> args = new HashMap<>();
        args.put("connection", "conn");
        if (model != null && !model.isBlank()) {
            args.put("model", model);
        }
        return args;
    }

    /** Builds a setup whose connection reports the given usage on batch embed. */
    private static BaseEmbeddingModelSetup setupWithBatchUsage(EmbeddingTokenUsage usage) {
        BaseEmbeddingModelSetup setup =
                new BaseEmbeddingModelSetup(
                        new ResourceDescriptor(
                                "test", Map.of("connection", "conn", "model", "bedrock-text")),
                        mock(ResourceContext.class)) {
                    @Override
                    public Map<String, Object> getParameters() {
                        return new HashMap<>();
                    }
                };
        setup.connection =
                new BaseEmbeddingModelConnection(
                        new ResourceDescriptor("conn", Collections.emptyMap()),
                        mock(ResourceContext.class)) {
                    @Override
                    public float[] embed(String text, Map<String, Object> parameters) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public List<float[]> embed(List<String> texts, Map<String, Object> parameters) {
                        return Collections.singletonList(VEC);
                    }

                    @Override
                    public EmbeddingResult<List<float[]>> embedWithUsage(
                            List<String> texts, Map<String, Object> parameters) {
                        return new EmbeddingResult<>(Collections.singletonList(VEC), usage);
                    }
                };
        return setup;
    }

    private static TestMetricGroup modelGroup(TestMetricGroup root, String model) {
        return (TestMetricGroup) root.getSubGroup("model", model);
    }

    @Test
    @DisplayName("recordTokenMetrics records prompt and total tokens under the model group")
    void testRecordTokenMetricsUnderModelGroup() {
        BaseEmbeddingModelSetup setup = setupWithSingleUsage(null);
        TestMetricGroup root = new TestMetricGroup();
        setup.setMetricGroup(root);

        setup.recordTokenMetrics("bedrock-text", 100, 210);

        TestMetricGroup model = modelGroup(root, "bedrock-text");
        assertEquals(100, model.counters.get("promptTokens").getCount());
        assertEquals(210, model.counters.get("totalTokens").getCount());
    }

    @Test
    @DisplayName("embedWithUsage single records provider usage onto the model group")
    void testEmbedWithUsageSingleRecordsUsage() {
        BaseEmbeddingModelSetup setup = setupWithSingleUsage(new EmbeddingTokenUsage(100, 210));
        TestMetricGroup root = new TestMetricGroup();
        setup.setMetricGroup(root);

        EmbeddingResult<float[]> result = setup.embedWithUsage("hello");

        // result is still returned with its usage intact
        assertEquals(100, result.getTokenUsage().getPromptTokens());
        assertEquals(210, result.getTokenUsage().getTotalTokens());
        // ...and the same usage was recorded as metrics
        TestMetricGroup model = modelGroup(root, "bedrock-text");
        assertEquals(100, model.counters.get("promptTokens").getCount());
        assertEquals(210, model.counters.get("totalTokens").getCount());
    }

    @Test
    @DisplayName("embedWithUsage batch records provider usage onto the model group")
    void testEmbedWithUsageBatchRecordsUsage() {
        BaseEmbeddingModelSetup setup = setupWithBatchUsage(new EmbeddingTokenUsage(100, 210));
        TestMetricGroup root = new TestMetricGroup();
        setup.setMetricGroup(root);

        EmbeddingResult<List<float[]>> result =
                setup.embedWithUsage(Collections.singletonList("hello"));

        assertEquals(100, result.getTokenUsage().getPromptTokens());
        assertEquals(210, result.getTokenUsage().getTotalTokens());
        TestMetricGroup model = modelGroup(root, "bedrock-text");
        assertEquals(100, model.counters.get("promptTokens").getCount());
        assertEquals(210, model.counters.get("totalTokens").getCount());
    }

    @Test
    @DisplayName("embedWithUsage records nothing when the provider reports no usage")
    void testEmbedWithUsageNullUsageRecordsNothing() {
        BaseEmbeddingModelSetup setup = setupWithSingleUsage(null);
        TestMetricGroup root = new TestMetricGroup();
        setup.setMetricGroup(root);

        setup.embedWithUsage("hello");

        // model group exists only if a counter was requested; absent means nothing was recorded
        assertFalse(root.subGroups.containsKey("model=bedrock-text"));
    }

    @Test
    @DisplayName("recordTokenMetrics is a no-op when no metric group is bound")
    void testRecordTokenMetricsWithoutMetricGroup() {
        BaseEmbeddingModelSetup setup = setupWithSingleUsage(null);
        // no setMetricGroup call -> getMetricGroup() returns null

        // must not throw
        setup.recordTokenMetrics("bedrock-text", 100, 210);
    }

    @Test
    @DisplayName("embedWithUsage records nothing when no metric group is bound")
    void testEmbedWithUsageWithoutMetricGroupRecordsNothingButReturnsUsage() {
        BaseEmbeddingModelSetup setup = setupWithSingleUsage(new EmbeddingTokenUsage(100, 210));
        // no setMetricGroup call

        EmbeddingResult<float[]> result = setup.embedWithUsage("hello");

        // usage still flows back to the caller; only metrics are skipped
        assertEquals(100, result.getTokenUsage().getPromptTokens());
    }

    @Test
    @DisplayName("counters accumulate across multiple embedding calls")
    void testCountersAccumulate() {
        BaseEmbeddingModelSetup setup = setupWithSingleUsage(new EmbeddingTokenUsage(100, 210));
        TestMetricGroup root = new TestMetricGroup();
        setup.setMetricGroup(root);

        setup.embedWithUsage("a");
        setup.embedWithUsage("b");

        TestMetricGroup model = modelGroup(root, "bedrock-text");
        assertEquals(200, model.counters.get("promptTokens").getCount());
        assertEquals(420, model.counters.get("totalTokens").getCount());
    }

    @Test
    @DisplayName("embedWithUsage records nothing when the setup has no model name")
    void testEmbedWithUsageNullModelRecordsNothing() {
        // descriptor omits "model" -> getArgument("model") returns null
        BaseEmbeddingModelSetup setup =
                setupWithSingleUsageAndModel(new EmbeddingTokenUsage(100, 210), null);
        TestMetricGroup root = new TestMetricGroup();
        setup.setMetricGroup(root);

        EmbeddingResult<float[]> result = setup.embedWithUsage("hello");

        // usage still flows back ...
        assertEquals(100, result.getTokenUsage().getPromptTokens());
        // ... but no model group is created without a model name to key on
        assertFalse(root.subGroups.containsKey("model=null"));
    }

    @Test
    @DisplayName("recordTokenMetrics rejects a null or blank model name")
    void testRecordTokenMetricsRejectsBlankModelName() {
        BaseEmbeddingModelSetup setup = setupWithSingleUsage(null);
        setup.setMetricGroup(new TestMetricGroup());

        assertThrows(
                IllegalArgumentException.class, () -> setup.recordTokenMetrics(null, 100, 210));
        assertThrows(IllegalArgumentException.class, () -> setup.recordTokenMetrics("", 100, 210));
        assertThrows(
                IllegalArgumentException.class, () -> setup.recordTokenMetrics("   ", 100, 210));
    }
}
