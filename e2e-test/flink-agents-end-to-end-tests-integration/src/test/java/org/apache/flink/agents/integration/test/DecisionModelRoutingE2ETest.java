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
 * Decision-model routing through a running Flink job: the router, the decision model and both
 * candidate chat models are Java resources registered on {@link DecisionModelRoutingAgent}, so the
 * test covers resource registration and initialization, the routing decision, the candidate
 * invocation and the final output, with no live credentials and no Python bridge.
 */
public class DecisionModelRoutingE2ETest {

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
        DecisionModelRoutingAgent.reset();
    }

    /**
     * One job, four requests: a confident choice of the cheap model, a confident choice of the
     * expensive model, a choice below the configured minimum probability (abstains to the default
     * model) and a provider that keeps failing (the retry budget is exhausted and the request
     * fails, with no candidate fallback).
     */
    @Test
    @Timeout(120)
    public void routesAbstainsAndFailsThroughARunningJob() throws Exception {
        List<String> outputs =
                runJob(
                        agentsEnv -> {
                            agentsEnv.getConfig().set(AgentExecutionOptions.MAX_RETRIES, 1);
                            agentsEnv.getConfig().set(AgentExecutionOptions.RETRY_WAIT_INTERVAL, 0);
                        },
                        "hello there",
                        "Write SQL to list active users",
                        "unsure which model fits",
                        "please crash");

        assertThat(outputs).hasSize(4);
        // Selection: the decision model's choice and probability drive the candidate.
        assertThat(outputs)
                .contains(
                        "final_model=small decision_source=decision_model score=0.95"
                                + " :: small:hello there",
                        "final_model=big decision_source=decision_model score=0.9"
                                + " :: big:Write SQL to list active users");
        // Abstention: 0.55 for "big" is below the 0.6 minimum, so the default model answers, the
        // source records the default rather than a decision, no score is reported, and the
        // provider's rejected choice stays in the metadata.
        assertThat(outputs)
                .contains(
                        "final_model=small decision_source=default score=null"
                                + " decision_choice=big :: small:unsure which model fits");
        // Exhausted retries: the request fails with the documented message naming the decision
        // model and carrying the provider's error; no candidate answers on its behalf.
        assertThat(outputs)
                .anySatisfy(
                        output ->
                                assertThat(output)
                                        .startsWith("FAILED: Decision model 'dm' failed")
                                        .contains("decision provider unavailable")
                                        .doesNotContain("small:please crash")
                                        .doesNotContain("big:please crash"));

        // The provider saw three routed requests once each and the failing one twice (one retry).
        assertThat(DecisionModelRoutingAgent.decisionCalls()).isEqualTo(5);
        assertThat(DecisionModelRoutingAgent.decisionInputs())
                .filteredOn(input -> input.contains("please crash"))
                .hasSize(2);
        // The decision input is the rendered request, framed by role.
        assertThat(DecisionModelRoutingAgent.decisionInputs()).contains("USER: hello there");
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
                        .apply(new DecisionModelRoutingAgent())
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
