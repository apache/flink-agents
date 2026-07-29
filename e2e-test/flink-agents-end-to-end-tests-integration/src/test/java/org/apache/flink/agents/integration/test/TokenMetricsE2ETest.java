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

package org.apache.flink.agents.integration.test;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.Metric;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.runtime.testutils.InMemoryReporter;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.test.junit5.MiniClusterExtension;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test verifying that agent metrics with key-value metric group dimensions flow through
 * the real Flink metric system. Uses {@link InMemoryReporter} to capture metrics and {@link
 * MockWebServer} as a stand-in for the LLM endpoint.
 *
 * <p>The test validates the <b>complete</b> metric identifier. The TaskManager resource ID is
 * extracted at runtime via {@link #PREFIX_PATTERN}, then each expected metric's full identifier is
 * constructed from {@link #PREFIX_TEMPLATE} and matched exactly. To add coverage for a new metric,
 * add an entry to {@link #EXPECTED_AGENT_COUNTERS}.
 */
class TokenMetricsE2ETest {

    private static final String CANNED_RESPONSE =
            "{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion\",\"created\":0,"
                    + "\"model\":\"gpt-4o-mini\","
                    + "\"choices\":[{\"index\":0,"
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"Hello!\"},"
                    + "\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";

    private static final Pattern PREFIX_PATTERN =
            Pattern.compile(
                    "^\\.taskmanager\\.([a-f0-9-]+)\\.Flink Streaming Job\\.action-execute-operator\\.0\\.");

    private static final String PREFIX_TEMPLATE =
            ".taskmanager.%s.Flink Streaming Job.action-execute-operator.0.";

    /**
     * Expected agent Counter metrics. Each key is the deterministic suffix after the prefix in the
     * full metric identifier. The value is the expected counter value after processing 2 input
     * records.
     *
     * <p>To extend: add one entry per new counter metric.
     */
    private static final Map<String, Long> EXPECTED_AGENT_COUNTERS = buildExpectedCounters();

    private static Map<String, Long> buildExpectedCounters() {
        Map<String, Long> m = new LinkedHashMap<>();
        // Token metrics — validates action.<name>.model.<model>.<counter> hierarchy
        // 2 chat requests x 10 prompt tokens = 20
        m.put("action.chat_model_action.model.gpt-4o-mini.promptTokens", 20L);
        // 2 chat requests x 5 completion tokens = 10
        m.put("action.chat_model_action.model.gpt-4o-mini.completionTokens", 10L);
        return m;
    }

    private static final InMemoryReporter REPORTER = InMemoryReporter.createWithRetainedMetrics();

    @RegisterExtension
    static final MiniClusterExtension MINI_CLUSTER =
            new MiniClusterExtension(
                    new MiniClusterResourceConfiguration.Builder()
                            .setConfiguration(createClusterConfig())
                            .setNumberSlotsPerTaskManager(2)
                            .setNumberTaskManagers(1)
                            .build());

    private static Configuration createClusterConfig() {
        Configuration config = new Configuration();
        REPORTER.addToConfiguration(config);
        return config;
    }

    private MockWebServer mockServer;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        return new MockResponse()
                                .setHeader("Content-Type", "application/json")
                                .setBody(CANNED_RESPONSE);
                    }
                });
        mockServer.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("Agent metrics are registered with correct key-value group hierarchy")
    void testAgentMetricsWithFullIdentifierValidation() throws Exception {
        TokenMetricsE2EAgent.apiBaseUrl = mockServer.url("/v1").toString();

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        DataStream<String> inputStream = env.fromData("What is 1+1?", "Hello world");

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(inputStream, (KeySelector<String, String>) value -> value)
                        .apply(new TokenMetricsE2EAgent())
                        .toDataStream();

        CloseableIterator<Object> results = outputStream.collectAsync();
        agentsEnv.execute();

        List<Object> collected = new ArrayList<>();
        while (results.hasNext()) {
            collected.add(results.next());
        }
        assertThat(collected).hasSize(2);

        Map<MetricGroup, Map<String, Metric>> byGroup = REPORTER.getMetricsByGroup();

        String taskManagerId = extractTaskManagerId(byGroup);
        assertThat(taskManagerId)
                .as(
                        "Should extract TaskManager resource ID from metrics "
                                + "matching prefix pattern: %s",
                        PREFIX_PATTERN.pattern())
                .isNotNull();

        String metricIdPrefix = String.format(PREFIX_TEMPLATE, taskManagerId);

        for (Map.Entry<String, Long> expected : EXPECTED_AGENT_COUNTERS.entrySet()) {
            String expectedSuffix = expected.getKey();
            long expectedValue = expected.getValue();
            String expectedFullId = metricIdPrefix + expectedSuffix;

            List<Counter> counters = findAllCountersByExactId(byGroup, expectedFullId);

            assertThat(counters)
                    .as("Exactly one counter with metric id '%s'", expectedFullId)
                    .hasSize(1);

            assertThat(counters.get(0).getCount())
                    .as("Counter value for '%s'", expectedFullId)
                    .isEqualTo(expectedValue);
        }
    }

    /**
     * Extracts the TaskManager resource ID by matching any operator metric against {@link
     * #PREFIX_PATTERN}. Returns {@code null} if no metric matches the expected prefix structure,
     * which would indicate the framework changed its metric hierarchy.
     */
    private static String extractTaskManagerId(Map<MetricGroup, Map<String, Metric>> byGroup) {
        for (Map.Entry<MetricGroup, Map<String, Metric>> groupEntry : byGroup.entrySet()) {
            MetricGroup group = groupEntry.getKey();
            for (String metricName : groupEntry.getValue().keySet()) {
                String fullId = group.getMetricIdentifier(metricName);
                Matcher matcher = PREFIX_PATTERN.matcher(fullId);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        return null;
    }

    private static List<Counter> findAllCountersByExactId(
            Map<MetricGroup, Map<String, Metric>> byGroup, String expectedId) {
        List<Counter> result = new ArrayList<>();
        for (Map.Entry<MetricGroup, Map<String, Metric>> groupEntry : byGroup.entrySet()) {
            MetricGroup group = groupEntry.getKey();
            for (Map.Entry<String, Metric> metricEntry : groupEntry.getValue().entrySet()) {
                if (!(metricEntry.getValue() instanceof Counter)) {
                    continue;
                }
                String fullId = group.getMetricIdentifier(metricEntry.getKey());
                if (expectedId.equals(fullId)) {
                    result.add((Counter) metricEntry.getValue());
                }
            }
        }
        return result;
    }
}
