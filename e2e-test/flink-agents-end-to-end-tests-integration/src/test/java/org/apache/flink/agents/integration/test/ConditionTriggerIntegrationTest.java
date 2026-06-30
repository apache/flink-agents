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
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.configuration.AgentConfigOptions.ConditionEvaluationFailureStrategy;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end test for {@code @Action} selectors in a full Flink pipeline. */
public class ConditionTriggerIntegrationTest {

    @Test
    public void testConditionGatedActionsFireSelectively() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        // Two records above the threshold (9, 7), two below (2, 4).
        DataStream<Integer> inputStream = env.fromElements(2, 9, 4, 7);

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agentsEnv
                .getConfig()
                .set(
                        AgentConfigOptions.CONDITION_EVALUATION_FAILURE_STRATEGY,
                        ConditionEvaluationFailureStrategy.FAIL);

        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(inputStream, (KeySelector<Integer, Integer>) value -> value)
                        .apply(new ConditionTriggerIntegrationAgent())
                        .toDataStream();

        CloseableIterator<Object> results = outputStream.collectAsync();
        agentsEnv.execute();

        List<String> outputs = new ArrayList<>();
        while (results.hasNext()) {
            outputs.add((String) results.next());
        }
        results.close();

        assertThat(outputs.stream().filter(o -> o.startsWith("all:")))
                .as("unconditioned action must fire for every record")
                .containsExactlyInAnyOrder("all:2", "all:9", "all:4", "all:7");
        assertThat(outputs.stream().filter(o -> o.startsWith("high:")))
                .as("condition-gated action must fire only for records > 5")
                .containsExactlyInAnyOrder("high:9", "high:7");
        assertThat(outputs.stream().filter(o -> o.startsWith("guard:")))
                .as("has()-guarded action must fire only for records > 5")
                .containsExactlyInAnyOrder("guard:9", "guard:7");
        assertThat(outputs.stream().filter(o -> o.startsWith("quoted:")))
                .as("dotted/hyphenated type must route via the type index")
                .containsExactlyInAnyOrder("quoted:2", "quoted:9", "quoted:4", "quoted:7");
        assertThat(outputs.stream().filter(o -> o.startsWith("multi:")))
                .as("multi-type conditional action must run once per matching event")
                .containsExactlyInAnyOrder("multi:9", "multi:7");
        assertThat(outputs)
                .doesNotContain("unexpected-non-target-evaluation", "unexpected-output-routing")
                .hasSize(14);
    }
}
