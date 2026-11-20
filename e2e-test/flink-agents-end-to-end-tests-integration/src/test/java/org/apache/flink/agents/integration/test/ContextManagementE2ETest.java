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
import org.apache.flink.agents.api.logger.LoggerType;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context management through a running Flink job: the chat model, its tool and the policy are
 * declared on {@link ContextManagementAgent} and in the job configuration, so the test covers the
 * configuration path, the tool-call loop of the built-in actions, the summarizer call, the events
 * an action can listen to and their Trace Log records, with no live credentials.
 */
public class ContextManagementE2ETest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MANAGED = "sum 6 accounts";
    private static final String MASKED = "masked sum 4 accounts";
    private static final String UNMANAGED = "unmanaged sum 3 accounts";
    private static final String CALLED = "called sum 4 accounts";

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
        ContextManagementAgent.reset();
    }

    /**
     * One job, four requests. The job's policy cuts tool results to 1000 characters and, above 480
     * estimated tokens, summarizes every round but the last. The first request looks up six
     * accounts under that policy. The second carries its own policy, which only masks the results
     * of all rounds but the last. The third carries a disabled policy. The fourth is made with
     * {@code ctx.chat} and the masking policy.
     */
    @Test
    @Timeout(120)
    public void appliesTheJobsPolicyAndTheRequestsOwnPolicyThroughARunningJob() throws Exception {
        List<String> outputs =
                runJob(
                        agentsEnv -> {
                            agentsEnv
                                    .getConfig()
                                    .set(
                                            AgentExecutionOptions
                                                    .CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS,
                                            1000);
                            agentsEnv
                                    .getConfig()
                                    .set(
                                            AgentExecutionOptions
                                                    .CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS,
                                            600);
                            agentsEnv
                                    .getConfig()
                                    .set(
                                            AgentExecutionOptions
                                                    .CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS,
                                            1);
                            agentsEnv
                                    .getConfig()
                                    .set(AgentConfigOptions.TRACE_LOG_OUTPUT_TYPE, LoggerType.FILE);
                            agentsEnv
                                    .getConfig()
                                    .set(
                                            AgentConfigOptions.TRACE_LOG_OUTPUT_BASE_DIR,
                                            traceDir.toString());
                        },
                        MANAGED,
                        MASKED,
                        UNMANAGED,
                        CALLED);

        assertThat(outputs)
                .containsExactlyInAnyOrder(
                        // Every amount: each round was summarized before it left the model input.
                        MANAGED + " -> sum=210 accounts=6",
                        // Masking alone hides the results of older rounds from the model, so the
                        // scripted model only finds the amount of the last round.
                        MASKED + " -> sum=40 accounts=1",
                        UNMANAGED + " -> sum=60 accounts=3",
                        // The policy given to ctx.chat is applied to the call's tool rounds.
                        CALLED + " -> sum=40 accounts=1");

        int resultChars = ContextManagementAgent.lookupResult(1).length();
        // Without a policy every model call is sent every earlier result in full.
        assertThat(ContextManagementAgent.toolResultChars(UNMANAGED))
                .containsExactly(0, resultChars, 2 * resultChars, 3 * resultChars);
        assertThat(ContextManagementAgent.steps(UNMANAGED)).isEmpty();

        // Under the job's policy the model is sent one result at a time, cut to 1000 characters:
        // the rounds before it are in the notes.
        assertThat(ContextManagementAgent.toolResultChars(MANAGED))
                .containsExactly(0, 1000, 1000, 1000, 1000, 1000, 1000);
        List<String> managedSteps = ContextManagementAgent.steps(MANAGED);
        assertThat(managedSteps)
                .containsExactly(
                        "truncate",
                        "summarize",
                        "truncate",
                        "summarize",
                        "truncate",
                        "summarize",
                        "truncate",
                        "summarize",
                        "truncate",
                        "summarize",
                        "truncate");
        List<Map<String, Object>> summaries = ContextManagementAgent.summaryDetails();
        assertThat(summaries).hasSize(5);
        assertThat(summaries)
                .allSatisfy(
                        details ->
                                assertThat(details)
                                        .containsEntry("summarizer", "chat")
                                        .containsEntry("rounds", 1)
                                        .containsEntry("max_input_tokens", 600)
                                        .containsEntry("estimate_approximate", true)
                                        .doesNotContainKey("error"));
        assertThat(summaries.get(4)).containsEntry("summarized_rounds", 5);
        // The summarizer was sent the stored result of one round at a time, cut to the limit.
        List<String> summaryRequests = ContextManagementAgent.summaryRequests();
        assertThat(summaryRequests).hasSize(5);
        assertThat(summaryRequests)
                .allSatisfy(
                        request ->
                                assertThat(request)
                                        .contains("}) returned: account=")
                                        .contains("characters truncated")
                                        .doesNotContain("Tool result omitted"));
        // Each result is shown with the call it answers.
        assertThat(summaryRequests.get(0))
                .contains("Round 1:\nlookup({\"account\":1}) returned: account=1 amount=10 ");
        // It is asked for the line of the round it is shown; the lines of earlier rounds are
        // kept as they were written and are not sent again.
        assertThat(summaryRequests.get(4))
                .contains("Round 5:\n")
                .contains("Write one line for each round above")
                .doesNotContain("Round 4:")
                .doesNotContain("account=1 amount=10");
        assertThat(summaries.get(4))
                .containsEntry(
                        "summary_chars",
                        ("Round 1: account=1 amount=10\n"
                                        + "Round 2: account=2 amount=20\n"
                                        + "Round 3: account=3 amount=30\n"
                                        + "Round 4: account=4 amount=40\n"
                                        + "Round 5: account=5 amount=50")
                                .length());

        // The request's own policy replaces the job's: nothing is cut or summarized, and the
        // results of all rounds but the last are placeholders.
        List<Integer> masked = ContextManagementAgent.toolResultChars(MASKED);
        assertThat(masked).hasSize(5);
        assertThat(masked.subList(0, 2)).containsExactly(0, resultChars);
        assertThat(masked.get(4)).isBetween(resultChars + 3 * 60, resultChars + 3 * 120);
        List<String> maskedSteps = ContextManagementAgent.steps(MASKED);
        assertThat(maskedSteps).containsExactly("mask", "mask", "mask");
        // The call made with ctx.chat was sent the same messages. Its steps are not delivered to
        // the agent's actions.
        assertThat(ContextManagementAgent.toolResultChars(CALLED)).isEqualTo(masked);
        assertThat(ContextManagementAgent.steps(CALLED)).isEmpty();

        List<String> allSteps = new ArrayList<>(managedSteps);
        allSteps.addAll(maskedSteps);
        assertTraceLog(allSteps);
    }

    /**
     * Every step the agent's action received is also a Trace Log record of the event. The steps of
     * the {@code ctx.chat} call stay inside the call, like its other events: the Trace Log records
     * them in the call's envelope.
     */
    private void assertTraceLog(List<String> steps) throws IOException {
        List<JsonNode> records = traceRecords();
        List<String> recorded =
                records.stream()
                        .filter(
                                record ->
                                        "_context_management_event"
                                                .equals(record.path("entityName").asText()))
                        .map(record -> record.path("attributes").path("step").asText())
                        .collect(Collectors.toList());
        assertThat(recorded).containsExactlyInAnyOrderElementsOf(steps);

        List<String> insideTheCall =
                records.stream()
                        .map(record -> record.path("attributes"))
                        .filter(
                                attributes ->
                                        "_context_management_event"
                                                .equals(attributes.path("delegate_type").asText()))
                        .map(
                                attributes ->
                                        attributes
                                                .path("delegate_attributes")
                                                .path("step")
                                                .asText())
                        .collect(Collectors.toList());
        assertThat(insideTheCall).containsExactly("mask", "mask", "mask");
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
                        .apply(new ContextManagementAgent())
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
