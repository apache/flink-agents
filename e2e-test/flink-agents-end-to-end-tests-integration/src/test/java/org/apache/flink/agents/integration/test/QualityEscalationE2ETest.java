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

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quality-triggered escalation through a running Flink job: tiers, the verifier decision model, the
 * custom signal, the structural checks, the tool round and the fallback interplay are all exercised
 * against {@link QualityEscalationAgent}'s Java resources, with no live credentials.
 */
public class QualityEscalationE2ETest {

    /** Routes by request text so every input is its own key. */
    public static class TextKeySelector implements KeySelector<String, String> {
        @Override
        public String getKey(String value) {
            return value;
        }
    }

    @BeforeEach
    @AfterEach
    void reset() {
        QualityEscalationAgent.reset();
    }

    /**
     * One job, five requests: an answer the verifier accepts on the first tier, one it rejects
     * (escalated to the second tier), an empty answer (structural escalation without a verifier
     * call), a tool round whose follow-up answer the custom signal rejects (escalated with the tool
     * result in hand) and an escalation whose target is down (fallback skips the rejected tier, and
     * the request fails).
     */
    @Test
    @Timeout(120)
    public void escalatesThroughARunningJob() throws Exception {
        List<String> outputs =
                runJob(
                        agentsEnv -> {
                            agentsEnv.getConfig().set(AgentExecutionOptions.MAX_RETRIES, 1);
                            agentsEnv.getConfig().set(AgentExecutionOptions.RETRY_WAIT_INTERVAL, 0);
                        },
                        "hello there",
                        "Write SQL to list active users",
                        "say nothing",
                        "lookup the answer",
                        "escalate then big down");

        assertThat(outputs).hasSize(5);
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

        assertThat(QualityEscalationAgent.toolCalls()).isEqualTo(1);
        // hello, SQL, big-down, plus the two rounds of the lookup request; not the empty answer.
        assertThat(QualityEscalationAgent.verifierCalls()).isEqualTo(5);
        assertThat(QualityEscalationAgent.verifierInputs())
                .anySatisfy(
                        input ->
                                assertThat(input)
                                        .startsWith("REQUEST:")
                                        .contains("USER: Write SQL to list active users")
                                        .contains("ANSWER:\nsmall:Write SQL to list active users"));
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
