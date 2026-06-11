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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.configuration.CelEvaluationFailurePolicy;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for ActionRouter: typed-only, CEL-only, mixed-dedup, and lifecycle. */
class ActionRouterTest {

    /** Stub handler — only its signature matters for {@link Action} construction. */
    public static void handlerStub(Event event, RunnerContext ctx) {}

    private ActionRouter router;

    @AfterEach
    void tearDown() {
        if (router != null) {
            router.close();
            router = null;
        }
        // Reset facade cache so the "no CEL in plan ⇔ evaluator is null" invariant stays clean
        // under repeated runs.
        CelExpressionFacade.clearProgramCacheForTests();
    }

    // -- Helpers --

    private static JavaFunction execStub() throws Exception {
        return new JavaFunction(
                ActionRouterTest.class.getName(),
                "handlerStub",
                new Class[] {Event.class, RunnerContext.class});
    }

    /** Builds an AgentPlan mirroring extraction-time indexing. */
    private static AgentPlan planOf(Action... actions) {
        Map<String, Action> byName = new HashMap<>();
        Map<String, List<Action>> byType = new HashMap<>();
        for (Action a : actions) {
            byName.put(a.getName(), a);
            List<String> typeNames = a.getListenEventTypes();
            for (String t : typeNames) {
                byType.computeIfAbsent(t, k -> new java.util.ArrayList<>()).add(a);
            }
        }
        return new AgentPlan(byName, byType);
    }

    // -- Constructor + lifecycle --

    @Test
    void constructor_rejectsNullPlan() {
        assertThatThrownBy(() -> new ActionRouter(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentPlan");
    }

    @Test
    void close_isIdempotent() throws Exception {
        Action a = new Action("celOnly", execStub(), List.of("type == 'x'"), null);
        router = new ActionRouter(planOf(a));
        router.open();
        router.close();
        router.close(); // must not throw
    }

    // -- Routing: typed-only fast path --

    @Test
    void route_typedOnly_returnsTypedHitsWithoutCelEvaluation() throws Exception {
        Action onInput = new Action("onInput", execStub(), List.of(InputEvent.EVENT_TYPE), null);
        Action onOutput = new Action("onOutput", execStub(), List.of(OutputEvent.EVENT_TYPE), null);
        router = new ActionRouter(planOf(onInput, onOutput));
        router.open();

        List<Action> matched = router.route(new InputEvent("hello"));
        assertThat(matched).containsExactly(onInput);
    }

    @Test
    void route_typedOnly_returnsEmptyListForUnknownEventType() throws Exception {
        Action onInput = new Action("onInput", execStub(), List.of(InputEvent.EVENT_TYPE), null);
        router = new ActionRouter(planOf(onInput));
        router.open();

        // Unknown event type, no CEL candidates → empty trigger set.
        Event other = new Event("_some_other_event") {};
        assertThat(router.route(other)).isEmpty();
    }

    // -- Routing: CEL-only slow path --

    @Test
    void route_celOnly_firesActionWhenAnyCelExpressionMatches() throws Exception {
        // Two CEL exprs on one action — within-action OR semantics; first matches.
        Action onPriceOrType =
                new Action(
                        "onPriceOrType",
                        execStub(),
                        Arrays.asList(
                                "type == '_input_event'",
                                "has(attributes.priceX) && attributes.priceX > 100"),
                        null);
        router = new ActionRouter(planOf(onPriceOrType));
        router.open();

        assertThat(router.route(new InputEvent("anything"))).containsExactly(onPriceOrType);
    }

    @Test
    void route_celOnly_doesNotFireWhenNoCelExpressionMatches() throws Exception {
        // CEL gates on attributes.priceX — field absent in InputEvent attributes.
        Action onPrice =
                new Action(
                        "onPrice",
                        execStub(),
                        List.of("has(attributes.priceX) && attributes.priceX > 100"),
                        null);
        router = new ActionRouter(planOf(onPrice));
        router.open();

        assertThat(router.route(new InputEvent("anything"))).isEmpty();
    }

    @Test
    void route_celOnly_shortCircuitsOnFirstMatchingExpression() throws Exception {
        // Two matching exprs on one action — still fires it exactly once (OR semantics).
        Action onMulti =
                new Action(
                        "onMulti",
                        execStub(),
                        Arrays.asList("type == '_input_event'", "type == '_input_event'"),
                        null);
        router = new ActionRouter(planOf(onMulti));
        router.open();

        List<Action> matched = router.route(new InputEvent("x"));
        assertThat(matched).containsExactly(onMulti);
    }

    // -- Routing: mixed typed + CEL → dedup --

    @Test
    void route_mixedActionAppearsExactlyOnce() throws Exception {
        // Type + CEL both match the same action — must dedupe to one entry (H1 regression).
        Action mixed =
                new Action(
                        "mixed",
                        execStub(),
                        Arrays.asList(InputEvent.EVENT_TYPE, "type == '_input_event'"),
                        null);
        router = new ActionRouter(planOf(mixed));
        router.open();

        List<Action> matched = router.route(new InputEvent("x"));
        assertThat(matched).containsExactly(mixed); // exactly once
    }

    @Test
    void route_typedAndCelMix_preservesTypedFirstThenCelOrdering() throws Exception {
        // Contract: typed hits ordered before CEL hits.
        Action pureType = new Action("pureType", execStub(), List.of(InputEvent.EVENT_TYPE), null);
        Action pureCel = new Action("pureCel", execStub(), List.of("type == '_input_event'"), null);
        router = new ActionRouter(planOf(pureType, pureCel));
        router.open();

        List<Action> matched = router.route(new InputEvent("x"));
        assertThat(matched).containsExactly(pureType, pureCel);
    }

    // -- Routing: CEL failure policy from plan config --

    @Test
    void route_failPolicyFromConfig_throwsOnConditionEvaluationError() throws Exception {
        // Compiles fine; errors at runtime because `attributes.nonexistent` is unset.
        Action a = new Action("celFail", execStub(), List.of("attributes.nonexistent > 3"), null);
        AgentPlan plan = planOf(a);
        plan.getConfig()
                .set(
                        AgentConfigOptions.CEL_EVALUATION_FAILURE_POLICY,
                        CelEvaluationFailurePolicy.FAIL);

        router = new ActionRouter(plan);
        router.open();

        assertThatThrownBy(() -> router.route(new Event("any_type") {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CEL condition evaluation failed");
    }

    @Test
    void route_defaultWarnAndSkip_swallowsConditionEvaluationError() throws Exception {
        // Same expression, same event — but no policy set in config. Default must remain
        // WARN_AND_SKIP, so route() returns empty rather than throwing.
        Action a =
                new Action("celWarnSkip", execStub(), List.of("attributes.nonexistent > 3"), null);
        router = new ActionRouter(planOf(a));
        router.open();

        assertThat(router.route(new Event("any_type") {})).isEmpty();
    }
}
