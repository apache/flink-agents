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

package org.apache.flink.agents.resource.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.decision.BaseDecisionModelConnection;
import org.apache.flink.agents.api.decision.BaseDecisionModelSetup;
import org.apache.flink.agents.api.decision.DecisionRequest;
import org.apache.flink.agents.api.decision.DecisionRequest.Question;
import org.apache.flink.agents.api.decision.DecisionResult;
import org.apache.flink.agents.api.decision.DecisionResult.ChoiceAnswer;
import org.apache.flink.agents.api.decision.DecisionResult.PredicateAnswer;
import org.apache.flink.agents.api.decision.DecisionResult.RefusalAnswer;
import org.apache.flink.agents.api.decision.DecisionResult.ScoreAnswer;
import org.apache.flink.agents.api.resource.PythonResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.PythonMCPResourceDiscovery;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.python.utils.PythonInterpreterManager;
import org.apache.flink.agents.runtime.python.utils.PythonResourceAdapterImpl;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Test;
import pemja.core.PythonInterpreter;
import pemja.core.PythonInterpreterConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises actual Pemja calls and a Flink job without requiring a remote model service. */
public class DecisionModelCrossLanguageTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FIXTURE =
            "flink_agents.runtime.tests.decision_model_bridge_fixture";

    public static class DecisionAgent extends Agent {
        public DecisionAgent() {
            addResource(
                    "connection",
                    ResourceType.DECISION_MODEL_CONNECTION,
                    new PythonResourceDescriptor(FIXTURE, "BridgeDecisionConnection", Map.of()));
            for (String name : List.of("first", "second")) {
                addResource(
                        name,
                        ResourceType.DECISION_MODEL,
                        new PythonResourceDescriptor(
                                "flink_agents.api.decision_models.decision_model",
                                "BaseDecisionModelSetup",
                                Map.of("connection", "connection", "model", name)));
            }
        }

        @Action(EventType.InputEvent)
        public static void decide(Event event, RunnerContext ctx) throws Exception {
            BaseDecisionModelSetup setup =
                    (BaseDecisionModelSetup) ctx.getResource("first", ResourceType.DECISION_MODEL);
            ctx.sendEvent(
                    new OutputEvent(
                            MAPPER.writeValueAsString(
                                    setup.decide(
                                            request(InputEvent.fromEvent(event).getInput())))));
        }
    }

    @Test
    void preservesContractsAndSharedConnectionLifecycleThroughPemja() throws Exception {
        Path root = projectRoot();
        PythonInterpreterConfig config =
                PythonInterpreterConfig.newBuilder()
                        .setExcType(PythonInterpreterConfig.ExecType.MULTI_THREAD)
                        .setPythonExec(System.getenv().getOrDefault("PYTHON_EXECUTABLE", "python3"))
                        .addPythonPaths(root.resolve("python").toString())
                        .build();
        PythonInterpreter interpreter = new PythonInterpreter(config);
        try (PythonInterpreterManager manager =
                new PythonInterpreterManager(interpreter, () -> new PythonInterpreter(config))) {
            interpreter.exec("from " + FIXTURE + " import reset, snapshot");
            interpreter.invoke("reset");
            AgentPlan plan = new AgentPlan(new DecisionAgent());
            plan = MAPPER.readValue(MAPPER.writeValueAsString(plan), AgentPlan.class);
            ResourceCache cache = new ResourceCache(plan.getResourceProviders());
            PythonResourceAdapterImpl adapter =
                    new PythonResourceAdapterImpl(cache.getResourceContext(), manager, null);
            adapter.open();
            PythonMCPResourceDiscovery.discoverPythonMCPResources(
                    plan.getResourceProviders(), adapter, cache);
            try {
                BaseDecisionModelSetup first =
                        (BaseDecisionModelSetup)
                                cache.getResource("first", ResourceType.DECISION_MODEL);
                BaseDecisionModelSetup second =
                        (BaseDecisionModelSetup)
                                cache.getResource("second", ResourceType.DECISION_MODEL);
                BaseDecisionModelConnection connection =
                        (BaseDecisionModelConnection)
                                cache.getResource(
                                        "connection", ResourceType.DECISION_MODEL_CONNECTION);
                DecisionRequest structured =
                        request(Map.of("message", "请退款", "flags", List.of(true, "true")));
                assertResult(first.decide(structured), structured, "first");
                // Closing one setup must not close the shared connection.
                first.close();
                assertThatThrownBy(() -> first.decide(structured))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("closed");
                DecisionRequest text = request("{\"paid\": true}");
                assertResult(second.decide(text), text, "second");
                DecisionRequest array = request(List.of("evidence", Map.of("paid", true)));
                assertResult(connection.decide(array, "direct"), array, "direct");
                assertThatThrownBy(() -> second.decide(request("fail")))
                        .hasMessageContaining("decision provider failed");
                DecisionRequest invalid =
                        new DecisionRequest(
                                "input", Map.of("bad", Question.score(List.of(42), null)));
                assertThatThrownBy(() -> second.decide(invalid)).hasMessageContaining("validation");
                JsonNode live = MAPPER.readTree((String) interpreter.invoke("snapshot"));
                assertThat(live.get("opens").asInt()).isEqualTo(1);
                assertThat(live.get("closes").asInt()).isZero();
                assertThat(live.get("calls").asInt()).isEqualTo(4);
            } finally {
                try {
                    cache.close();
                } finally {
                    adapter.close();
                }
            }
            JsonNode closed = MAPPER.readTree((String) interpreter.invoke("snapshot"));
            assertThat(closed.get("closes").asInt()).isEqualTo(1);
        }
    }

    @Test
    void javaActionCallsPythonDecisionModelInFlink() throws Exception {
        Configuration config = new Configuration();
        config.setString("restart-strategy.type", "disable");
        config.setString(
                "python.executable", System.getenv().getOrDefault("PYTHON_EXECUTABLE", "python3"));
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(config);
        env.setParallelism(1);
        AgentsExecutionEnvironment agents = AgentsExecutionEnvironment.getExecutionEnvironment(env);
        List<String> outputs = new ArrayList<>();
        try (CloseableIterator<Object> results =
                agents.fromDataStream(env.fromData("请退款", "{\"paid\": true}"), value -> "decisions")
                        .apply(new DecisionAgent())
                        .toDataStream()
                        .collectAsync()) {
            agents.execute();
            results.forEachRemaining(value -> outputs.add((String) value));
        }
        assertThat(outputs).hasSize(2);
        List<Object> inputs = new ArrayList<>();
        for (String output : outputs) {
            DecisionResult result = MAPPER.readValue(output, DecisionResult.class);
            DecisionRequest original =
                    MAPPER.convertValue(result.getMetadata().get("request"), DecisionRequest.class);
            inputs.add(original.getInput());
            assertResult(result, original, "first");
        }
        assertThat(inputs).containsExactlyInAnyOrder("请退款", "{\"paid\": true}");
    }

    private static DecisionRequest request(Object input) {
        return new DecisionRequest(
                input,
                Map.of(
                        "eligible",
                                Question.predicate(
                                        Map.of("condition", "Has a receipt"),
                                        Map.of("true", "有收据")),
                        "route",
                                Question.choice(
                                        List.of(Map.of("value", true), Map.of("value", "true")),
                                        "Choose"),
                        "impact",
                                Question.score(
                                        List.of(Map.of("description", "轻微"), List.of("严重")), null),
                        "blocked", Question.predicate("Can this be answered?")));
    }

    private static void assertResult(DecisionResult result, DecisionRequest request, String model)
            throws Exception {
        assertThat(result.getAnswers()).containsOnlyKeys("eligible", "route", "impact", "blocked");
        assertThat(((PredicateAnswer) result.getAnswers().get("eligible")).getProbabilityTrue())
                .isEqualTo(0.9);
        ChoiceAnswer choice = (ChoiceAnswer) result.getAnswers().get("route");
        assertThat(choice.getChoice()).isEqualTo(true);
        assertThat(choice.getProbabilities())
                .extracting(DecisionResult.ChoiceProbability::getValue)
                .containsExactly(true, "true");
        assertThat(choice.getProbabilities())
                .extracting(DecisionResult.ChoiceProbability::getProbability)
                .containsExactly(0.75, 0.25);
        assertThat(choice.getProviderConfidence()).isEqualTo(0.5);
        ScoreAnswer score = (ScoreAnswer) result.getAnswers().get("impact");
        assertThat(score.getScore()).isEqualTo(0.75);
        assertThat(score.getProviderConfidence()).isNull();
        assertThat(score.getProbabilities())
                .extracting(DecisionResult.ScoreProbability::getValue)
                .containsExactly(0, 1);
        assertThat(score.getProbabilities())
                .extracting(DecisionResult.ScoreProbability::getLabel)
                .containsExactly(Map.of("description", "轻微"), List.of("严重"));
        assertThat(((RefusalAnswer) result.getAnswers().get("blocked")).getReason()).isNull();
        assertThat(result.getModel()).isEqualTo("reported-" + model);
        assertThat(result.getUsage().getInputTokens()).isEqualTo(7L);
        assertThat(result.getUsage().getOutputTokens()).isNull();
        assertThat(result.getUsage().getTotalTokens()).isNull();
        // Compare as JSON because Python's model_dump includes explicit null optional fields.
        DecisionRequest echoed =
                MAPPER.convertValue(result.getMetadata().get("request"), DecisionRequest.class);
        assertThat(MAPPER.<JsonNode>valueToTree(echoed)).isEqualTo(MAPPER.valueToTree(request));
        DecisionResult restored =
                MAPPER.readValue(MAPPER.writeValueAsString(result), DecisionResult.class);
        assertThat(MAPPER.<JsonNode>valueToTree(restored)).isEqualTo(MAPPER.valueToTree(result));
    }

    private static Path projectRoot() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("python/pyproject.toml"))) {
            root = root.getParent();
        }
        if (root == null) throw new IllegalStateException("Cannot locate Python sources");
        return root;
    }
}
