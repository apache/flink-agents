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

package org.apache.flink.agents.runtime.condition;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.configuration.AgentConfigOptions.ConditionEvaluationFailureStrategy;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runtime matching contract for unified trigger-condition entries. */
class ActionMatcherTest {

    private static final String FIXTURE_PATH =
            "e2e-test/cross-language-action-selector-fixtures/action_selector_conformance.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final JsonNode FIXTURE = loadFixture();

    public static void handler(Event event, RunnerContext context) {}

    @Test
    void constructorRejectsNullPlan() {
        assertThatThrownBy(() -> new ActionMatcher(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentPlan");
    }

    static Stream<Arguments> runtimeSuccessCases() {
        return Stream.concat(
                        fixtureCases("expression", "pass"),
                        fixtureCases("event_type", "not_applicable"))
                .map(ActionMatcherTest::probeArguments);
    }

    static Stream<Arguments> runtimeFailureCases() {
        return fixtureCases("expression", "fail")
                .map(
                        testCase ->
                                Arguments.of(
                                        testCase.get("name").asText(),
                                        testCase.get("entry").asText(),
                                        testCase.get("runtime_error_fragment").asText()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("runtimeSuccessCases")
    void matchesSharedSuccessCases(
            String name,
            String source,
            String probeEventType,
            Map<String, Object> probeAttributes,
            boolean matchesProbe)
            throws Exception {
        Action action = action(name, source);
        List<Action> matches =
                new ActionMatcher(plan(action)).match(new Event(probeEventType, probeAttributes));

        if (matchesProbe) {
            assertThat(matches).containsExactly(action);
        } else {
            assertThat(matches).isEmpty();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("runtimeFailureCases")
    void rejectsSharedRuntimeFailures(String name, String source, String errorFragment)
            throws Exception {
        Action action = action(name, source);

        assertThatThrownBy(() -> new ActionMatcher(plan(action)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(errorFragment);
    }

    @Test
    void typeAndConditionEntriesUseOr() throws Exception {
        Action mixed = action("mixed", InputEvent.EVENT_TYPE, "score > 10");
        ActionMatcher matcher = new ActionMatcher(plan(mixed));

        assertThat(matcher.match(new InputEvent("without-score"))).containsExactly(mixed);
        assertThat(matcher.match(new Event("other", Map.of("score", 20)))).containsExactly(mixed);
        assertThat(matcher.match(new Event("other", Map.of("score", 5)))).isEmpty();
    }

    @Test
    void typeMatchShortCircuitsFailingCondition() throws Exception {
        Action mixed = action("mixed", InputEvent.EVENT_TYPE, "attributes.missing > 0");
        ActionMatcher matcher =
                new ActionMatcher(plan(ConditionEvaluationFailureStrategy.FAIL, mixed));

        assertThat(matcher.match(new InputEvent("x"))).containsExactly(mixed);
    }

    @Test
    void matchingConditionShortCircuitsLaterConditions() throws Exception {
        Action action = action("conditional", "true", "attributes.missing > 0");
        ActionMatcher matcher =
                new ActionMatcher(plan(ConditionEvaluationFailureStrategy.FAIL, action));

        assertThat(matcher.match(new Event("other"))).containsExactly(action);
    }

    @Test
    void shortCircuitSkipsLaterVariableBuild() throws Exception {
        Event event = eventWithFailingAttribute();
        Action warnAction = action("warn", "true", "bad != null");
        Action failAction = action("fail", "true", "bad != null");

        assertThat(
                        new ActionMatcher(
                                        plan(
                                                ConditionEvaluationFailureStrategy.WARN_AND_SKIP,
                                                warnAction))
                                .match(event))
                .containsExactly(warnAction);
        assertThat(
                        new ActionMatcher(plan(ConditionEvaluationFailureStrategy.FAIL, failAction))
                                .match(event))
                .containsExactly(failAction);
    }

    @Test
    void warnAndSkipContinuesAfterVariableBuildFailure() throws Exception {
        Action fallback = action("fallback", "bad != null", "true");
        Action independent = action("independent", "type == 'other'");
        ActionMatcher matcher =
                new ActionMatcher(
                        plan(
                                ConditionEvaluationFailureStrategy.WARN_AND_SKIP,
                                fallback,
                                independent));

        assertThat(matcher.match(eventWithFailingAttribute()))
                .containsExactly(fallback, independent);
    }

    @Test
    void failPropagatesVariableBuildFailure() throws Exception {
        Action broken = action("broken", "bad != null", "true");
        ActionMatcher matcher =
                new ActionMatcher(plan(ConditionEvaluationFailureStrategy.FAIL, broken));

        assertThatThrownBy(() -> matcher.match(eventWithFailingAttribute()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Building trigger condition variables failed");
    }

    @Test
    void laterConditionCanMatch() throws Exception {
        Action action = action("conditional", "has(missing) && missing > 0", "score == 7");
        ActionMatcher matcher = new ActionMatcher(plan(action));

        assertThat(matcher.match(new Event("other", Map.of("score", 7)))).containsExactly(action);
    }

    @Test
    void deduplicatesMatchesInTypeFirstOrder() throws Exception {
        JsonNode scenario = FIXTURE.get("ordering_dedupe_type_first");
        Map<String, Action> actions = new LinkedHashMap<>();
        for (JsonNode actionNode : scenario.withArray("actions")) {
            Action action =
                    action(
                            actionNode.get("name").asText(),
                            jsonStringList(actionNode.get("entries")));
            actions.put(action.getName(), action);
        }

        assertThat(
                        new ActionMatcher(new AgentPlan(actions))
                                .match(new Event(scenario.get("probe_event_type").asText())))
                .extracting(Action::getName)
                .containsExactlyElementsOf(jsonStringList(scenario.get("expected_matches")));
    }

    @Test
    void warnAndSkipKeepsTypeHits() throws Exception {
        Action type = action("type", InputEvent.EVENT_TYPE);
        Action broken = action("broken", "attributes.missing > 0");
        ActionMatcher matcher = new ActionMatcher(plan(type, broken));

        assertThat(matcher.match(new InputEvent("x"))).containsExactly(type);
    }

    @Test
    void failPropagatesEvaluationError() throws Exception {
        Action broken = action("broken", "attributes.missing > 0");
        ActionMatcher matcher =
                new ActionMatcher(plan(ConditionEvaluationFailureStrategy.FAIL, broken));

        assertThatThrownBy(() -> matcher.match(new Event("other")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Trigger condition evaluation failed");
    }

    @Test
    void matchesOutputEventDirectly() throws Exception {
        Action action = action("output", OutputEvent.EVENT_TYPE);

        assertThat(new ActionMatcher(plan(action)).match(new OutputEvent("x")))
                .containsExactly(action);
    }

    @Test
    void serializedPlanRebuildsSelectors() throws Exception {
        Action mixed = action("mixed", "'custom.type'", "score == 7");
        AgentPlan restored = javaRoundTrip(plan(mixed));
        Action restoredAction = restored.getActions().get("mixed");
        ActionMatcher matcher = new ActionMatcher(restored);

        assertThat(matcher.match(new Event("custom.type"))).containsExactly(restoredAction);
        assertThat(matcher.match(new Event("other", Map.of("score", 7))))
                .containsExactly(restoredAction);
    }

    private static Action action(String name, String... triggerConditions) throws Exception {
        return new Action(name, function(), Arrays.asList(triggerConditions), null);
    }

    private static Action action(String name, List<String> triggerConditions) throws Exception {
        return new Action(name, function(), triggerConditions, null);
    }

    private static Event eventWithFailingAttribute() {
        return new Event("other", Map.of("bad", new FailingMap()));
    }

    /** Throws only when condition variable building tries to normalize the value. */
    private static final class FailingMap extends AbstractMap<String, Object> {
        @Override
        public Set<Entry<String, Object>> entrySet() {
            throw new IllegalStateException("bad attribute must not be normalized");
        }
    }

    private static JavaFunction function() throws Exception {
        return new JavaFunction(
                ActionMatcherTest.class.getName(),
                "handler",
                new Class[] {Event.class, RunnerContext.class});
    }

    private static AgentPlan plan(Action... actions) {
        Map<String, Action> byName = new LinkedHashMap<>();
        for (Action action : actions) {
            byName.put(action.getName(), action);
        }
        return new AgentPlan(byName);
    }

    private static AgentPlan plan(
            ConditionEvaluationFailureStrategy failureStrategy, Action... actions) {
        Map<String, Action> byName = new LinkedHashMap<>();
        for (Action action : actions) {
            byName.put(action.getName(), action);
        }
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentConfigOptions.CONDITION_EVALUATION_FAILURE_STRATEGY, failureStrategy);
        return new AgentPlan(byName, Map.<ResourceType, Map<String, ResourceProvider>>of(), config);
    }

    private static AgentPlan javaRoundTrip(AgentPlan plan) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(plan);
        }
        try (ObjectInputStream input =
                new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (AgentPlan) input.readObject();
        }
    }

    private static Stream<JsonNode> fixtureCases(String classification, String runtimeCompilation) {
        List<JsonNode> matchingCases = new ArrayList<>();
        for (JsonNode testCase : FIXTURE.withArray("cases")) {
            if ("pass".equals(testCase.get("plan_validation").asText())
                    && classification.equals(testCase.get("classification").asText())
                    && runtimeCompilation.equals(testCase.get("runtime_compilation").asText())) {
                matchingCases.add(testCase);
            }
        }
        return matchingCases.stream();
    }

    private static Arguments probeArguments(JsonNode testCase) {
        return Arguments.of(
                testCase.get("name").asText(),
                testCase.get("entry").asText(),
                testCase.get("probe_event_type").asText(),
                jsonMap(testCase.get("probe_attributes")),
                testCase.get("matches_probe").asBoolean());
    }

    private static Map<String, Object> jsonMap(JsonNode node) {
        return OBJECT_MAPPER.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }

    private static List<String> jsonStringList(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : arrayNode) {
            values.add(value.asText());
        }
        return values;
    }

    private static JsonNode loadFixture() {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(FIXTURE_PATH);
            if (Files.isRegularFile(candidate)) {
                try {
                    return OBJECT_MAPPER.readTree(candidate.toFile());
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "Failed to read action-selector fixture " + candidate, e);
                }
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Could not find action-selector fixture " + FIXTURE_PATH);
    }
}
