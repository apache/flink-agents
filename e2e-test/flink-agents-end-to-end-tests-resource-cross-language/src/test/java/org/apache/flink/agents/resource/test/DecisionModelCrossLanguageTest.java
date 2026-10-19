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
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.DecisionModelConnection;
import org.apache.flink.agents.api.annotation.DecisionModelSetup;
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
import org.apache.flink.agents.api.event.DecisionRequestEvent;
import org.apache.flink.agents.api.event.DecisionResponseEvent;
import org.apache.flink.agents.api.resource.PythonResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.DecisionModelAction;
import org.apache.flink.agents.plan.utils.CancellationUtils;
import org.apache.flink.agents.runtime.PythonMCPResourceDiscovery;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.python.utils.PythonInterpreterManager;
import org.apache.flink.agents.runtime.python.utils.PythonResourceAdapterImpl;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import pemja.core.PythonInterpreter;
import pemja.core.PythonInterpreterConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/** Exercises actual Pemja calls and a Flink job without requiring a remote model service. */
public class DecisionModelCrossLanguageTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FIXTURE =
            "flink_agents.runtime.tests.decision_model_bridge_fixture";

    public static class DecisionAgent extends Agent {
        @DecisionModelConnection
        public static PythonResourceDescriptor connection() {
            return new PythonResourceDescriptor(FIXTURE, "BridgeDecisionConnection", Map.of());
        }

        @DecisionModelConnection
        public static PythonResourceDescriptor alternateConnection() {
            return new PythonResourceDescriptor(
                    FIXTURE, "BridgeDecisionConnection", Map.of("connection_id", "alternate"));
        }

        @DecisionModelSetup
        public static ResourceDescriptor first() {
            return ResourceDescriptor.Builder.newBuilder(BaseDecisionModelSetup.class.getName())
                    .addInitialArgument("connection", "connection")
                    .addInitialArgument("model", "first")
                    .build();
        }

        @DecisionModelSetup
        public static PythonResourceDescriptor second() {
            return new PythonResourceDescriptor(
                    "flink_agents.api.decision_models.decision_model",
                    "BaseDecisionModelSetup",
                    Map.of("connection", "connection", "model", "second"));
        }

        @DecisionModelSetup
        public static PythonResourceDescriptor defaultModel() {
            return new PythonResourceDescriptor(
                    FIXTURE, "BridgeDecisionSetup", Map.of("connection", "connection"));
        }

        @DecisionModelSetup
        public static PythonResourceDescriptor defaultConnection() {
            return new PythonResourceDescriptor(
                    FIXTURE, "BridgeDecisionSetup", Map.of("model", "configured-model"));
        }

        @DecisionModelSetup
        public static PythonResourceDescriptor defaultConfiguration() {
            return new PythonResourceDescriptor(FIXTURE, "BridgeDecisionSetup", Map.of());
        }

        @DecisionModelSetup
        public static PythonResourceDescriptor configuredDefaults() {
            return new PythonResourceDescriptor(
                    FIXTURE,
                    "BridgeDecisionSetup",
                    Map.of("connection", "alternateConnection", "model", "configured-model"));
        }

        @DecisionModelSetup
        public static PythonResourceDescriptor invalidConfiguration() {
            return new PythonResourceDescriptor(
                    FIXTURE, "BridgeDecisionSetup", Map.of("model", " "));
        }

        @Action(EventType.InputEvent)
        public static void decide(Event event, RunnerContext ctx) {
            Object input = InputEvent.fromEvent(event).getInput();
            ctx.sendEvent(
                    new DecisionRequestEvent(
                            "first", request(input), requestParams(input.toString())));
        }

        @Action(DecisionResponseEvent.EVENT_TYPE)
        public static void emitResult(Event event, RunnerContext ctx) throws Exception {
            ctx.sendEvent(
                    new OutputEvent(
                            MAPPER.writeValueAsString(
                                    DecisionResponseEvent.fromEvent(event).getResponse())));
        }
    }

    @Test
    @Timeout(45)
    void preservesContractsAndSharedConnectionLifecycleThroughPemja(
            @TempDir Path temporaryDirectory) throws Exception {
        // Manual interpreter lifecycle checks need a separate CPython process from Flink jobs.
        Path outputFile = temporaryDirectory.resolve("decision-lifecycle.log");
        Process process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "--add-opens=java.base/java.util=ALL-UNNAMED",
                                "--add-opens=java.base/java.nio=ALL-UNNAMED",
                                "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED",
                                "-XX:ErrorFile=" + temporaryDirectory.resolve("hs_err_pid%p.log"),
                                "-cp",
                                System.getProperty(
                                        "surefire.test.class.path",
                                        System.getProperty("java.class.path")),
                                ResourceLifecycleScenario.class.getName())
                        .redirectErrorStream(true)
                        .redirectOutput(outputFile.toFile())
                        .start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor();
        }
        String output = Files.readString(outputFile);
        assertThat(finished).as("Decision lifecycle scenario timed out:%n%s", output).isTrue();
        assertThat(process.exitValue()).as("Decision lifecycle scenario:%n%s", output).isZero();
    }

    public static class ResourceLifecycleScenario {
        public static void main(String[] args) throws Exception {
            verifyResourceLifecycle();
        }
    }

    private static void verifyResourceLifecycle() throws Exception {
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
            interpreter.exec("import " + FIXTURE + " as decision_fixture");
            interpreter.invoke("decision_fixture.reset");
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
                Map<String, Object> firstParams = requestParams("first-call");
                Map<String, Object> secondParams = requestParams("second-call");
                assertResult(
                        first.decide(structured, firstParams), structured, "first", firstParams);
                assertResult(
                        first.decide(structured, secondParams), structured, "first", secondParams);
                assertResult(first.decide(structured), structured, "first");
                // Closing one setup must not close the shared connection.
                first.close();
                DecisionRequest text = request("{\"paid\": true}");
                assertResult(second.decide(text), text, "second");
                DecisionRequest array = request(List.of("evidence", Map.of("paid", true)));
                assertResult(
                        connection.decide(array, "direct", firstParams),
                        array,
                        "direct",
                        firstParams);
                assertResult(
                        connection.decide(array, "direct", secondParams),
                        array,
                        "direct",
                        secondParams);
                assertResult(connection.decide(array, "direct"), array, "direct");
                assertThatThrownBy(() -> second.decide(request("fail")))
                        .hasMessageContaining("decision provider failed");
                JsonNode live =
                        MAPPER.readTree((String) interpreter.invoke("decision_fixture.snapshot"));
                assertThat(live.get("opens").asInt()).isEqualTo(1);
                assertThat(live.get("closes").asInt()).isZero();
                assertThat(live.get("calls").asInt()).isEqualTo(8);
                assertThat(live.get("request_params"))
                        .isEqualTo(
                                MAPPER.valueToTree(
                                        List.of(
                                                firstParams,
                                                secondParams,
                                                Map.of(),
                                                Map.of(),
                                                firstParams,
                                                secondParams,
                                                Map.of(),
                                                Map.of())));
                verifyCancellationSignals(plan, cache, second, connection, interpreter);
                verifyPythonSetupConfiguration(cache);
                second.close();
                assertThatThrownBy(() -> second.decide(structured))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("closed");
                assertResult(connection.decide(structured, "direct"), structured, "direct");
            } finally {
                try {
                    cache.close();
                } finally {
                    adapter.close();
                }
            }
            JsonNode closed =
                    MAPPER.readTree((String) interpreter.invoke("decision_fixture.snapshot"));
            assertThat(closed.get("closes").asInt()).isEqualTo(2);
        }
    }

    private static void verifyPythonSetupConfiguration(ResourceCache cache) {
        assertAll(
                () -> assertPythonSetup(cache, "defaultModel", "default-model", "connection"),
                () ->
                        assertPythonSetup(
                                cache, "defaultConnection", "configured-model", "connection"),
                () ->
                        assertPythonSetup(
                                cache, "defaultConfiguration", "default-model", "connection"),
                () ->
                        assertPythonSetup(
                                cache, "configuredDefaults", "configured-model", "alternate"),
                () ->
                        assertThatThrownBy(
                                        () ->
                                                cache.getResource(
                                                        "invalidConfiguration",
                                                        ResourceType.DECISION_MODEL))
                                .hasMessageContaining(
                                        "A non-blank decision model name is required."));
    }

    private static void assertPythonSetup(
            ResourceCache cache, String name, String expectedModel, String expectedConnection)
            throws Exception {
        BaseDecisionModelSetup setup =
                (BaseDecisionModelSetup) cache.getResource(name, ResourceType.DECISION_MODEL);
        DecisionRequest input = request("setup configuration");
        Map<String, Object> params = requestParams(name);
        DecisionResult result = setup.decide(input, params);
        assertResult(result, input, expectedModel, params);
        assertThat(result.getMetadata().get("connection")).isEqualTo(expectedConnection);
    }

    private static void verifyCancellationSignals(
            AgentPlan plan,
            ResourceCache cache,
            BaseDecisionModelSetup setup,
            BaseDecisionModelConnection connection,
            PythonInterpreter interpreter)
            throws Exception {
        plan.getConfig().set(AgentExecutionOptions.DECISION_ASYNC, false);
        plan.getConfig().set(AgentExecutionOptions.MAX_RETRIES, 1);
        plan.getConfig().set(AgentExecutionOptions.RETRY_WAIT_INTERVAL, 0);
        for (String input : List.of("cancel", "async-cancel", "interrupt")) {
            DecisionRequest cancelled = request(input);
            boolean interrupted = input.equals("interrupt");
            assertThatThrownBy(() -> setup.decide(cancelled))
                    .isInstanceOf(CancellationException.class)
                    .satisfies(
                            error ->
                                    assertThat(CancellationUtils.isInterruption(error))
                                            .isEqualTo(interrupted));
            assertThatThrownBy(() -> connection.decide(cancelled, "direct"))
                    .isInstanceOf(CancellationException.class)
                    .satisfies(
                            error ->
                                    assertThat(CancellationUtils.isInterruption(error))
                                            .isEqualTo(interrupted));
            int callsBefore =
                    MAPPER.readTree((String) interpreter.invoke("decision_fixture.snapshot"))
                            .get("calls")
                            .asInt();
            RunnerContextImpl context =
                    new RunnerContextImpl(
                            new FlinkAgentsMetricGroupImpl(
                                    UnregisteredMetricGroups
                                            .createUnregisteredOperatorMetricGroup()),
                            () -> {},
                            plan,
                            cache,
                            "decision-bridge-test") {
                        {
                            actionName = "decision_model_action";
                        }
                    };
            try {
                assertThatThrownBy(
                                () ->
                                        DecisionModelAction.processDecisionRequest(
                                                new DecisionRequestEvent("second", cancelled),
                                                context))
                        .isInstanceOf(CancellationException.class);
                assertThat(context.getPendingEvents()).isEmpty();
                assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interrupted);
            } finally {
                // The standalone scenario also checks lifecycle calls after interruption.
                Thread.interrupted();
            }
            int callsAfter =
                    MAPPER.readTree((String) interpreter.invoke("decision_fixture.snapshot"))
                            .get("calls")
                            .asInt();
            assertThat(callsAfter).isEqualTo(callsBefore + 1);
        }
        DecisionRequest resumed = request("after cancellation");
        assertResult(setup.decide(resumed), resumed, "second");
    }

    @Test
    void javaEventActionCallsPythonDecisionModelInFlink() throws Exception {
        Configuration config = new Configuration();
        config.setString("restart-strategy.type", "disable");
        config.setString(
                "python.executable", System.getenv().getOrDefault("PYTHON_EXECUTABLE", "python3"));
        config.setString(
                "python.pythonpath",
                projectRoot().resolve("python")
                        + java.io.File.pathSeparator
                        + System.getenv().getOrDefault("PYTHONPATH", ""));
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
            assertResult(result, original, "first", requestParams(original.getInput().toString()));
        }
        assertThat(inputs).containsExactlyInAnyOrder("请退款", "{\"paid\": true}");
    }

    private static DecisionRequest request(Object input) {
        return new DecisionRequest(
                input,
                Map.of(
                        "eligible", Question.predicate(Map.of("condition", "Has a receipt")),
                        "route",
                                Question.choice(
                                        Map.of(
                                                "true",
                                                "Escalate",
                                                "false",
                                                "Resolve automatically"),
                                        "Choose"),
                        "impact",
                                Question.score(
                                        List.of(Map.of("description", "轻微"), List.of("严重")),
                                        "Rate impact."),
                        "blocked", Question.predicate("Can this be answered?")));
    }

    private static void assertResult(DecisionResult result, DecisionRequest request, String model)
            throws Exception {
        assertResult(result, request, model, Map.of());
    }

    private static void assertResult(
            DecisionResult result,
            DecisionRequest request,
            String model,
            Map<String, Object> requestParams)
            throws Exception {
        assertThat(result.getAnswers()).containsOnlyKeys("eligible", "route", "impact", "blocked");
        assertThat(((PredicateAnswer) result.getAnswers().get("eligible")).getProbabilityTrue())
                .isEqualTo(0.9);
        ChoiceAnswer choice = (ChoiceAnswer) result.getAnswers().get("route");
        assertThat(choice.getChoice()).isEqualTo("true");
        assertThat(choice.getProbabilities().keySet()).containsExactly("true", "false");
        assertThat(choice.getProbabilities().values()).containsExactly(0.75, 0.25);
        assertThat(choice.getProviderConfidence()).isEqualTo(0.5);
        ScoreAnswer score = (ScoreAnswer) result.getAnswers().get("impact");
        assertThat(score.getScore()).isEqualTo(0.75);
        assertThat(score.getProviderConfidence()).isNull();
        assertThat(score.getProbabilities())
                .containsExactlyInAnyOrderEntriesOf(Map.of(0, 0.25, 1, 0.75));
        assertThat(score.getLegend())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(0, Map.of("description", "轻微"), 1, List.of("严重")));
        assertThat(((RefusalAnswer) result.getAnswers().get("blocked")).getReason()).isNull();
        assertThat(result.getModel()).isEqualTo("reported-" + model);
        assertThat(result.getUsage().getInputTokens()).isEqualTo(7L);
        assertThat(result.getUsage().getOutputTokens()).isNull();
        assertThat(result.getUsage().getTotalTokens()).isNull();
        // Compare as JSON because Python's model_dump includes explicit null optional fields.
        DecisionRequest echoed =
                MAPPER.convertValue(result.getMetadata().get("request"), DecisionRequest.class);
        assertThat(MAPPER.<JsonNode>valueToTree(echoed)).isEqualTo(MAPPER.valueToTree(request));
        assertThat(MAPPER.<JsonNode>valueToTree(result.getMetadata().get("request_params")))
                .isEqualTo(MAPPER.valueToTree(requestParams));
        DecisionResult restored =
                MAPPER.readValue(MAPPER.writeValueAsString(result), DecisionResult.class);
        assertThat(MAPPER.<JsonNode>valueToTree(restored)).isEqualTo(MAPPER.valueToTree(result));
    }

    private static Map<String, Object> requestParams(String requestId) {
        return Map.of(
                "extra_headers",
                Map.of("x-request-id", requestId),
                "extra_body",
                Map.of("context", Arrays.asList("证据", true, null, Map.of("count", 3))),
                "timeout",
                2.5);
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
