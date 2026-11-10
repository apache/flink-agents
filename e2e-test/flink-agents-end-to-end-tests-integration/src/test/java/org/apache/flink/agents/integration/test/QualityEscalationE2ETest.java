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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.event.ModelRoutingEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.logger.LoggerType;
import org.apache.flink.agents.api.logger.TraceLogLevel;
import org.apache.flink.agents.api.logger.TraceLogTarget;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.flink.agents.integration.test.QualityEscalationAgent.SMALL_DISCARDED_TOKENS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quality-triggered escalation through a running Flink job: tiers, the verifier decision model, the
 * custom signal, the structural checks, the tool round and the fallback interplay are all exercised
 * against {@link QualityEscalationAgent}'s Java resources, with no live credentials. The job writes
 * its trace log to a file with every entity recorded, so the test also reads the {@code
 * _model_routing_event} records with {@code decision_source=escalation}, checks that a rejected
 * tool round left no tool execution behind, and reads the escalation counters from the cluster's
 * metric reporter.
 */
public class QualityEscalationE2ETest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACTION_COUNTER_SUFFIX = ".action.chat_model_action.";

    private static final InMemoryReporter REPORTER = InMemoryReporter.createWithRetainedMetrics();

    @RegisterExtension
    static final MiniClusterExtension MINI_CLUSTER =
            new MiniClusterExtension(
                    new MiniClusterResourceConfiguration.Builder()
                            .setConfiguration(clusterConfig())
                            .setNumberSlotsPerTaskManager(2)
                            .setNumberTaskManagers(1)
                            .build());

    private static Configuration clusterConfig() {
        Configuration config = new Configuration();
        REPORTER.addToConfiguration(config);
        return config;
    }

    /** Routes by request text so every input is its own key. */
    public static class TextKeySelector implements KeySelector<String, String> {
        @Override
        public String getKey(String value) {
            return value;
        }
    }

    @TempDir Path traceDir;

    @BeforeEach
    @AfterEach
    void reset() {
        QualityEscalationAgent.reset();
    }

    /**
     * One job, six requests: an answer the verifier accepts on the first tier, one it rejects
     * (escalated to the second tier), an empty answer (structural escalation without a verifier
     * call), a tool round whose follow-up answer the custom signal rejects (escalated with the tool
     * result in hand), an escalation whose target is down (fallback skips the rejected tier, and
     * the request fails) and a call to a tool the first tier does not have (structural escalation
     * before anything is dispatched).
     */
    @Test
    @Timeout(120)
    public void escalatesThroughARunningJob() throws Exception {
        List<String> outputs =
                runJob(
                        agentsEnv -> {
                            agentsEnv.getConfig().set(AgentExecutionOptions.MAX_RETRIES, 1);
                            agentsEnv
                                    .getConfig()
                                    .set(AgentExecutionOptions.RETRY_WAIT_INTERVAL, Duration.ZERO);
                            agentsEnv
                                    .getConfig()
                                    .set(AgentConfigOptions.TRACE_LOG_OUTPUT_TYPE, LoggerType.FILE);
                            agentsEnv
                                    .getConfig()
                                    .set(
                                            AgentConfigOptions.TRACE_LOG_OUTPUT_BASE_DIR,
                                            traceDir.toString());
                            // The default targets record events only; tool executions are
                            // entities of their own and are needed for the gating check.
                            agentsEnv
                                    .getConfig()
                                    .set(
                                            AgentConfigOptions.TRACE_LOG_TARGETS,
                                            List.of(TraceLogTarget.all(TraceLogLevel.STANDARD)));
                        },
                        "hello there",
                        "Write SQL to list active users",
                        "say nothing",
                        "lookup the answer",
                        "escalate then big down",
                        "call unknown tool");

        assertThat(outputs).hasSize(6);
        // Accepted on the first tier: no strategy, so the source is the default start tier.
        assertThat(outputs)
                .contains("final_model=small decision_source=default hops=[] :: small:hello there");
        // The verifier scores the SQL answer at 0.3, below 0.7: big answers the same request.
        assertThat(outputs)
                .contains(
                        "final_model=big decision_source=escalation"
                                + " hops=[small>big:decision_model:verifier]"
                                + " :: big:Write SQL to list active users");
        // An empty answer is escalated structurally; the verifier is never asked about it.
        assertThat(outputs)
                .contains(
                        "final_model=big decision_source=escalation"
                                + " hops=[small>big:structural] :: big:say nothing");
        // The tool round passes the gate and the tool runs; the follow-up answer "weak" fails the
        // custom signal, and big continues the conversation with the tool result it was handed.
        assertThat(outputs)
                .contains(
                        "final_model=big decision_source=escalation hops=[small>big:custom:"
                                + QualityEscalationAgent.WeakAnswerSignal.class.getName()
                                + "] :: big:lookup the answer+tool=42");
        // The escalation target fails and fallback has no candidate left (small was rejected), so
        // the request fails with big's error and small never answers on its behalf.
        assertThat(outputs)
                .anySatisfy(
                        output ->
                                assertThat(output)
                                        .startsWith("FAILED:")
                                        .contains("big is down")
                                        .doesNotContain("small:escalate"));
        // A call to a tool small does not have is rejected structurally; big answers the request
        // and the unknown tool is never executed.
        assertThat(outputs)
                .contains(
                        "final_model=big decision_source=escalation"
                                + " hops=[small>big:structural] :: big:call unknown tool");

        // Only the lookup request's valid tool round ran a tool.
        assertThat(QualityEscalationAgent.toolCalls()).isEqualTo(1);
        // hello, SQL, big-down, plus the lookup request's written answer after its tool round. Not
        // the tool-only round itself, and not the two structural escalations.
        assertThat(QualityEscalationAgent.verifierCalls()).isEqualTo(4);
        assertThat(QualityEscalationAgent.verifierInputs())
                .anySatisfy(
                        input ->
                                assertThat(input)
                                        .startsWith("REQUEST:")
                                        .contains("USER: Write SQL to list active users")
                                        .contains("ANSWER:\nsmall:Write SQL to list active users"));
        assertThat(QualityEscalationAgent.verifierInputs())
                .noneSatisfy(input -> assertThat(input).contains("Tool call lookup("));

        assertResponseMetadata();
        assertTraceLog();
        assertCounters();
    }

    /**
     * The {@code escalated_tokens} of each successful response: one discarded small answer each.
     */
    @SuppressWarnings("unchecked")
    private static void assertResponseMetadata() {
        List<Map<String, Object>> blocks =
                QualityEscalationAgent.routingBlocks().stream()
                        .map(b -> (Map<String, Object>) b)
                        .collect(Collectors.toList());
        // Five successful responses; the big-down request failed and carries no block.
        assertThat(blocks).hasSize(5);
        assertThat(blocks.stream().map(b -> b.get("final_model")))
                .containsExactlyInAnyOrder("small", "big", "big", "big", "big");
        for (Map<String, Object> block : blocks) {
            List<Map<String, Object>> hops = (List<Map<String, Object>>) block.get("escalations");
            if ("small".equals(block.get("final_model"))) {
                assertThat(hops).isEmpty();
                assertThat(block).containsEntry("escalation_attempted", false);
                assertThat(((Number) block.get("escalated_tokens")).longValue()).isZero();
            } else {
                assertThat(hops).hasSize(1);
                assertThat(block).containsEntry("escalation_attempted", true);
                assertThat(((Number) block.get("escalated_tokens")).longValue())
                        .isEqualTo(SMALL_DISCARDED_TOKENS);
                assertThat(((Number) hops.get(0).get("discarded_tokens")).longValue())
                        .isEqualTo(SMALL_DISCARDED_TOKENS);
            }
        }
    }

    /**
     * The trace log's routing events with {@code decision_source=escalation}, one per escalated
     * request, each naming the signal, the tier it left and the tokens it discarded; and the tool
     * gating: the rejected unknown-tool round produced neither a tool request nor a tool execution.
     */
    private void assertTraceLog() throws IOException {
        List<JsonNode> records = traceRecords();
        assertThat(records).isNotEmpty();

        Map<String, JsonNode> escalations =
                records.stream()
                        .filter(r -> "event".equals(r.path("entityType").asText()))
                        .filter(
                                r ->
                                        ModelRoutingEvent.EVENT_TYPE.equals(
                                                r.path("entityName").asText()))
                        .filter(
                                r ->
                                        ModelRoutingEvent.SOURCE_ESCALATION.equals(
                                                r.at("/attributes/decision_source").asText()))
                        .collect(
                                Collectors.toMap(
                                        r -> r.path("businessKey").asText(), r -> r, (a, b) -> a));
        assertThat(escalations.keySet())
                .containsExactlyInAnyOrder(
                        "Write SQL to list active users",
                        "say nothing",
                        "lookup the answer",
                        "escalate then big down",
                        "call unknown tool");
        assertEscalationEvent(
                escalations.get("Write SQL to list active users"), "decision_model:verifier");
        assertThat(
                        escalations
                                .get("Write SQL to list active users")
                                .at("/attributes/score")
                                .asDouble())
                .isEqualTo(0.3);
        assertEscalationEvent(escalations.get("say nothing"), "structural");
        assertEscalationEvent(
                escalations.get("lookup the answer"),
                "custom:" + QualityEscalationAgent.WeakAnswerSignal.class.getName());
        assertEscalationEvent(escalations.get("escalate then big down"), "decision_model:verifier");
        assertEscalationEvent(escalations.get("call unknown tool"), "structural");
        assertThat(escalations.get("call unknown tool").at("/attributes/reason").asText())
                .contains("'search'")
                .contains("does not have");

        // Tool gating: one tool request and one tool execution in the whole job, both for the
        // lookup request; the unknown-tool request left no trace of a tool.
        List<String> toolRequestKeys =
                records.stream()
                        .filter(r -> "event".equals(r.path("entityType").asText()))
                        .filter(
                                r ->
                                        ToolRequestEvent.EVENT_TYPE.equals(
                                                r.path("entityName").asText()))
                        .map(r -> r.path("businessKey").asText())
                        .collect(Collectors.toList());
        assertThat(toolRequestKeys).containsExactly("lookup the answer");
        List<JsonNode> toolExecutions =
                records.stream()
                        .filter(r -> "tool".equals(r.path("entityType").asText()))
                        .collect(Collectors.toList());
        assertThat(toolExecutions).isNotEmpty();
        assertThat(toolExecutions)
                .allSatisfy(
                        r -> {
                            assertThat(r.path("entityName").asText()).isEqualTo("lookup");
                            assertThat(r.path("businessKey").asText())
                                    .isEqualTo("lookup the answer");
                        });
        assertThat(
                        toolExecutions.stream()
                                .map(r -> r.path("executionId").asText())
                                .distinct()
                                .count())
                .isEqualTo(1);
    }

    private static void assertEscalationEvent(JsonNode event, String signal) {
        assertThat(event.at("/attributes/selected_model").asText()).isEqualTo("big");
        assertThat(event.at("/attributes/metadata/signal").asText()).isEqualTo(signal);
        assertThat(event.at("/attributes/metadata/escalated_from").asText()).isEqualTo("small");
        assertThat(event.at("/attributes/metadata/discarded_tokens").asLong())
                .isEqualTo(SMALL_DISCARDED_TOKENS);
    }

    /** The two escalation counters of the chat action, read from the cluster's metric reporter. */
    private static void assertCounters() {
        assertThat(counter("routingEscalationCount").getCount()).isEqualTo(5L);
        assertThat(counter("routingEscalatedTokens").getCount())
                .isEqualTo(5L * SMALL_DISCARDED_TOKENS);
    }

    private static Counter counter(String name) {
        String suffix = ACTION_COUNTER_SUFFIX + name;
        List<Counter> matches = new ArrayList<>();
        for (Map.Entry<MetricGroup, Map<String, Metric>> group :
                REPORTER.getMetricsByGroup().entrySet()) {
            for (Map.Entry<String, Metric> metric : group.getValue().entrySet()) {
                if (metric.getValue() instanceof Counter
                        && group.getKey().getMetricIdentifier(metric.getKey()).endsWith(suffix)) {
                    matches.add((Counter) metric.getValue());
                }
            }
        }
        assertThat(matches).as("one counter ending in %s", suffix).hasSize(1);
        return matches.get(0);
    }

    private List<JsonNode> traceRecords() throws IOException {
        List<JsonNode> records = new ArrayList<>();
        try (Stream<Path> files = Files.list(traceDir)) {
            for (Path file :
                    files.filter(f -> f.getFileName().toString().startsWith("traces-"))
                            .collect(Collectors.toList())) {
                for (String line : Files.readAllLines(file)) {
                    if (!line.isBlank()) {
                        records.add(JSON.readTree(line));
                    }
                }
            }
        }
        return records;
    }

    /** Runs a single-parallelism agent job and returns the collected outputs. */
    private static List<String> runJob(
            java.util.function.Consumer<AgentsExecutionEnvironment> configure, String... inputs)
            throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        DataStream<String> inputStream = env.fromData(inputs);

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        configure.accept(agentsEnv);

        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(inputStream, new TextKeySelector())
                        .apply(new QualityEscalationAgent())
                        .toDataStream();

        List<String> outputs = new ArrayList<>();
        try (CloseableIterator<Object> results = outputStream.collectAsync()) {
            agentsEnv.execute();
            while (results.hasNext()) {
                outputs.add(results.next().toString());
            }
        }
        return outputs;
    }
}
