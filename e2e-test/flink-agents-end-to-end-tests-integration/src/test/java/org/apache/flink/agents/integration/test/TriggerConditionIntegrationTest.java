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
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.configuration.AgentConfigOptions.ConditionEvaluationFailureStrategy;
import org.apache.flink.agents.integration.test.TriggerConditionIntegrationAgent.ConditionInput;
import org.apache.flink.agents.integration.test.TriggerConditionIntegrationAgent.DoubleQuotedDottedMapKeyAgent;
import org.apache.flink.agents.integration.test.TriggerConditionIntegrationAgent.NestedMapAndAgent;
import org.apache.flink.agents.integration.test.TriggerConditionIntegrationAgent.PojoMapParityAgent;
import org.apache.flink.agents.integration.test.TriggerConditionIntegrationAgent.ScalarListPayloadAgent;
import org.apache.flink.agents.integration.test.TriggerConditionIntegrationAgent.SingleQuotedDottedMapKeyAgent;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end test for trigger conditions in a full Flink pipeline. */
public class TriggerConditionIntegrationTest {

    @Test
    public void testPojoConditionsAndOrRouting() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        DataStream<ConditionInput> inputStream =
                env.fromElements(
                        new ConditionInput("ok-high", "ok", 9),
                        new ConditionInput("error-high", "error", 9),
                        new ConditionInput("ok-low", "ok", 2));

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agentsEnv
                .getConfig()
                .set(
                        AgentConfigOptions.CONDITION_EVALUATION_FAILURE_STRATEGY,
                        ConditionEvaluationFailureStrategy.FAIL);

        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(
                                inputStream,
                                (KeySelector<ConditionInput, String>) input -> input.id)
                        .apply(new TriggerConditionIntegrationAgent())
                        .toDataStream();

        CloseableIterator<Object> results = outputStream.collectAsync();
        agentsEnv.execute();

        List<String> outputs = new ArrayList<>();
        while (results.hasNext()) {
            outputs.add((String) results.next());
        }
        results.close();

        assertThat(outputs.stream().filter(output -> output.startsWith("nested:")))
                .containsExactlyInAnyOrder("nested:ok-high", "nested:ok-low");
        assertThat(outputs.stream().filter(output -> output.startsWith("envelope:")))
                .containsExactlyInAnyOrder("envelope:ok-high", "envelope:ok-low");
        assertThat(outputs.stream().filter(output -> output.startsWith("or:")))
                .as("an action runs once even when both OR branches match")
                .containsExactlyInAnyOrder("or:ok-high", "or:error-high", "or:ok-low");
        assertThat(outputs.stream().filter(output -> output.startsWith("custom:")))
                .containsExactlyInAnyOrder("custom:ok-high", "custom:ok-low");
        assertThat(outputs.stream().filter(output -> output.startsWith("guarded:")))
                .containsExactlyInAnyOrder("guarded:ok-high", "guarded:ok-low");
        assertThat(outputs).doesNotContain("unexpected-output-routing").hasSize(11);
    }

    @Test
    public void pojoAndMapHaveSameResult() throws Exception {
        List<Object> inputs =
                List.of(
                        new ConditionInput("pojo", "ok", 9),
                        Map.of("id", "map", "status", "ok", "value", 9));

        assertThat(runPayloads(inputs, new PojoMapParityAgent()))
                .containsExactlyInAnyOrder("parity:pojo", "parity:map");
    }

    @Test
    public void doubleQuotedDottedKeyWorks() throws Exception {
        List<Object> inputs =
                List.of(
                        Map.of(
                                "id",
                                "dotted-match",
                                "com.www.info",
                                "ready",
                                "status",
                                Map.of("forward", true)),
                        Map.of(
                                "id",
                                "dotted-miss",
                                "com.www.info",
                                "blocked",
                                "status",
                                Map.of("forward", true)));

        assertThat(runPayloads(inputs, new DoubleQuotedDottedMapKeyAgent()))
                .containsExactly("double-quoted:dotted-match");
    }

    @Test
    public void singleQuotedDottedKeyWorks() throws Exception {
        List<Object> inputs =
                List.of(
                        Map.of(
                                "id",
                                "dotted-match",
                                "com.www.info",
                                "ready",
                                "status",
                                Map.of("forward", true)),
                        Map.of(
                                "id",
                                "dotted-miss",
                                "com.www.info",
                                "blocked",
                                "status",
                                Map.of("forward", true)));

        assertThat(runPayloads(inputs, new SingleQuotedDottedMapKeyAgent()))
                .containsExactly("single-quoted:dotted-match");
    }

    @Test
    public void nestedMapAndWorks() throws Exception {
        List<Object> inputs =
                List.of(
                        Map.of("id", "both", "status", Map.of("forward", true, "code", "ready")),
                        Map.of(
                                "id",
                                "forward-only",
                                "status",
                                Map.of("forward", true, "code", "blocked")),
                        Map.of(
                                "id",
                                "code-only",
                                "status",
                                Map.of("forward", false, "code", "ready")));

        assertThat(runPayloads(inputs, new NestedMapAndAgent())).containsExactly("nested-map:both");
    }

    @Test
    public void scalarAndListPayloadsWork() throws Exception {
        assertThat(runPayloads(List.of("ready", List.of("ready")), new ScalarListPayloadAgent()))
                .containsExactlyInAnyOrder("scalar", "list");
    }

    @Test
    public void yamlNestedAndHyphenatedRouting() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        DataStream<ConditionInput> inputStream =
                env.fromElements(
                        new ConditionInput("ok", "ok", 1), new ConditionInput("error", "error", 1));

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agentsEnv
                .getConfig()
                .set(
                        AgentConfigOptions.CONDITION_EVALUATION_FAILURE_STRATEGY,
                        ConditionEvaluationFailureStrategy.FAIL);
        agentsEnv.loadYaml(yamlFixture("trigger_condition_agent.yaml"));

        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(
                                inputStream,
                                (KeySelector<ConditionInput, String>) input -> input.id)
                        .apply("yaml_trigger_condition_agent")
                        .toDataStream();

        CloseableIterator<Object> results = outputStream.collectAsync();
        agentsEnv.execute();

        List<String> outputs = new ArrayList<>();
        while (results.hasNext()) {
            outputs.add((String) results.next());
        }
        results.close();

        assertThat(outputs).containsExactly("yaml-hyphen:ok");
    }

    private static Path yamlFixture(String name) {
        URL resource =
                TriggerConditionIntegrationTest.class.getClassLoader().getResource("yaml/" + name);
        Objects.requireNonNull(resource, "fixture not found on classpath: yaml/" + name);
        return Paths.get(resource.getPath());
    }

    private static List<String> runPayloads(List<Object> inputs, Agent agent) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        DataStream<Object> inputStream =
                env.fromCollection(inputs, TypeInformation.of(Object.class));
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agentsEnv
                .getConfig()
                .set(
                        AgentConfigOptions.CONDITION_EVALUATION_FAILURE_STRATEGY,
                        ConditionEvaluationFailureStrategy.FAIL);

        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(
                                inputStream,
                                (KeySelector<Object, String>) ignored -> "payload-shapes")
                        .apply(agent)
                        .toDataStream();

        CloseableIterator<Object> results = outputStream.collectAsync();
        agentsEnv.execute();

        List<String> outputs = new ArrayList<>();
        while (results.hasNext()) {
            outputs.add((String) results.next());
        }
        results.close();
        return outputs;
    }
}
