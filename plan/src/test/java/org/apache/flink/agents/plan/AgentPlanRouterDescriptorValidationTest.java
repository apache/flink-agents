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

package org.apache.flink.agents.plan;

import org.apache.flink.agents.api.chat.model.routing.Escalation;
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.chat.model.routing.RoutingStrategy;
import org.apache.flink.agents.api.chat.model.routing.RoutingStrategyType;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.resourceprovider.JavaResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A router descriptor that never passed through {@code ModelRouter.of(...).build()} — hand-built,
 * deserialized, or produced by another language — meets the declaration rules when the agent plan
 * is built. The plan is the only place they are applied: the router resource decodes what passed.
 */
class AgentPlanRouterDescriptorValidationTest {

    private static Map<ResourceType, Map<String, ResourceProvider>> providers(
            Map<String, Object> routerArgs) {
        ResourceDescriptor router =
                new ResourceDescriptor(ResourceName.ModelRouter.MODEL_ROUTER, routerArgs);
        Map<ResourceType, Map<String, ResourceProvider>> providers = new HashMap<>();
        providers.put(
                ResourceType.MODEL_ROUTER,
                new HashMap<>(
                        Map.of(
                                "router",
                                new JavaResourceProvider(
                                        "router", ResourceType.MODEL_ROUTER, router))));
        ResourceDescriptor model = new ResourceDescriptor("some.Clazz", Map.of());
        Map<String, ResourceProvider> chatModels = new HashMap<>();
        chatModels.put("small", new JavaResourceProvider("small", ResourceType.CHAT_MODEL, model));
        chatModels.put("big", new JavaResourceProvider("big", ResourceType.CHAT_MODEL, model));
        chatModels.put("judge", new JavaResourceProvider("judge", ResourceType.CHAT_MODEL, model));
        providers.put(ResourceType.CHAT_MODEL, chatModels);
        providers.put(
                ResourceType.DECISION_MODEL,
                new HashMap<>(
                        Map.of(
                                "dm",
                                new JavaResourceProvider(
                                        "dm",
                                        ResourceType.DECISION_MODEL,
                                        new ResourceDescriptor(
                                                "some.Setup",
                                                Map.of("connection", "conn", "model", "m"))))));
        providers.put(
                ResourceType.DECISION_MODEL_CONNECTION,
                new HashMap<>(
                        Map.of(
                                "conn",
                                new JavaResourceProvider(
                                        "conn", ResourceType.DECISION_MODEL_CONNECTION, model))));
        return providers;
    }

    private static Map<String, Object> rulesRouter(Map<String, Object> rules) {
        Map<String, Object> args = new HashMap<>();
        args.put(ModelRouter.CANDIDATES_KEY, new ArrayList<>(List.of("small", "big")));
        args.put(ModelRouter.STRATEGY_TYPE_KEY, RoutingStrategyType.RULE_BASED.tag());
        args.put(ModelRouter.STRATEGY_ARGS_KEY, Map.of(RoutingStrategy.ARG_RULES, rules));
        return args;
    }

    private static Map<String, Object> decisionRouter(Map<String, Object> descriptions) {
        Map<String, Object> args = new HashMap<>();
        args.put(ModelRouter.CANDIDATES_KEY, List.of("small", "big"));
        args.put(ModelRouter.STRATEGY_TYPE_KEY, RoutingStrategyType.DECISION_MODEL.tag());
        args.put(ModelRouter.STRATEGY_ARGS_KEY, Map.of(RoutingStrategy.ARG_DECISION_MODEL, "dm"));
        if (descriptions != null) {
            args.put(ModelRouter.CANDIDATE_DESCRIPTIONS_KEY, descriptions);
        }
        return args;
    }

    @Test
    void aWellFormedHandBuiltDescriptorPasses() {
        Map<String, Object> args = rulesRouter(Map.of("big", "\\bsql\\b"));
        args.put(ModelRouter.DEFAULT_MODEL_KEY, "small");
        args.put(ModelRouter.FALLBACK_KEY, true);
        Escalation.tiers("small", "big").toArguments(args);
        assertThatCode(() -> new AgentPlan(Map.of(), providers(args))).doesNotThrowAnyException();
    }

    @Test
    void aDescriptorWithoutArgumentsFailsAtPlanConstruction() {
        // The router resource reads its arguments without checks of its own, so the plan has to
        // reject a descriptor that has none instead of letting it fail per routed request.
        ResourceDescriptor router =
                new ResourceDescriptor(
                        ResourceName.ModelRouter.MODEL_ROUTER, (Map<String, Object>) null);
        Map<ResourceType, Map<String, ResourceProvider>> providers = providers(Map.of());
        providers.put(
                ResourceType.MODEL_ROUTER,
                new HashMap<>(
                        Map.of(
                                "router",
                                new JavaResourceProvider(
                                        "router", ResourceType.MODEL_ROUTER, router))));
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Model router 'router' declares no arguments");
    }

    @Test
    void theDefaultModelMustBeACandidate() {
        Map<String, Object> args = rulesRouter(Map.of());
        args.put(ModelRouter.DEFAULT_MODEL_KEY, "huge");
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers(args)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Model router 'router'")
                .hasMessageContaining("default model 'huge' is not one of the candidates");
    }

    @Test
    void aNonStringDefaultModelIsRejected() {
        Map<String, Object> args = rulesRouter(Map.of());
        args.put(ModelRouter.DEFAULT_MODEL_KEY, 123);
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers(args)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default model '123' is not one of the candidates");
    }

    @Test
    void aBlankOrDuplicateCandidateNameIsRejected() {
        Map<String, Object> blank = rulesRouter(Map.of());
        blank.put(ModelRouter.CANDIDATES_KEY, List.of("small", ""));
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers(blank)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidate names must be non-empty strings");
        Map<String, Object> duplicate = rulesRouter(Map.of());
        duplicate.put(ModelRouter.CANDIDATES_KEY, List.of("small", "small"));
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers(duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidate 'small' is duplicated");
    }

    @Test
    void aRuleValueThatIsNotARegexStringIsRejected() {
        Map<String, Object> nullValue = new HashMap<>();
        nullValue.put("big", null);
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers(rulesRouter(nullValue))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a regex String");
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers(rulesRouter(Map.of("big", 42)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a regex String");
    }

    @Test
    void aRuleKeyThatIsNotACandidateIsRejected() {
        assertThatThrownBy(
                        () ->
                                new AgentPlan(
                                        Map.of(),
                                        providers(rulesRouter(Map.of("huge", "\\bx\\b")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rule key 'huge'");
    }

    @Test
    void aStrategyArgumentRuleIsAppliedAtPlanConstruction() {
        // The judge declaration rule the fluent factory applies as it is written is applied here to
        // a descriptor that skipped the factory.
        Map<String, Object> args = new HashMap<>();
        args.put(ModelRouter.CANDIDATES_KEY, List.of("small", "big"));
        args.put(ModelRouter.STRATEGY_TYPE_KEY, RoutingStrategyType.LLM_JUDGE.tag());
        args.put(ModelRouter.STRATEGY_ARGS_KEY, Map.of());
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers(args)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a non-empty 'judge_model'");
    }

    @Test
    void aDecisionStrategyNeedsEveryCandidateDescribed() {
        assertThatThrownBy(
                        () ->
                                new AgentPlan(
                                        Map.of(),
                                        providers(decisionRouter(Map.of("big", "code and sql")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a description for every candidate")
                .hasMessageContaining("[small]");
        assertThatCode(
                        () ->
                                new AgentPlan(
                                        Map.of(),
                                        providers(
                                                decisionRouter(
                                                        Map.of(
                                                                "small",
                                                                "chit-chat",
                                                                "big",
                                                                "code and sql")))))
                .doesNotThrowAnyException();
    }

    @Test
    void aDescriptionThatIsNotAStringIsDiagnosedByCandidateAndType() {
        Map<String, Object> typed = new HashMap<>();
        typed.put("small", "chit-chat");
        typed.put("big", 42);
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers(decisionRouter(typed))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'big' has a Integer description");
    }

    @Test
    void descriptionsThatAreNotAMapAreRejected() {
        Map<String, Object> args = rulesRouter(Map.of());
        args.put(ModelRouter.CANDIDATE_DESCRIPTIONS_KEY, List.of("small", "big"));
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers(args)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected a map of candidate name to description");
    }

    @Test
    void theEscalationDeclarationRulesAreAppliedAtPlanConstruction() {
        Map<String, Object> oneTier = rulesRouter(Map.of());
        oneTier.put(Escalation.TIERS_KEY, List.of("small"));
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers(oneTier)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Model router 'router'")
                .hasMessageContaining("at least two tiers");

        Map<String, Object> unknownSignal = rulesRouter(Map.of());
        unknownSignal.put(Escalation.TIERS_KEY, List.of("small", "big"));
        unknownSignal.put(Escalation.SIGNALS_KEY, List.of(Map.of("type", "telepathy")));
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers(unknownSignal)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown type 'telepathy'");

        Map<String, Object> zeroHops = rulesRouter(Map.of());
        zeroHops.put(Escalation.TIERS_KEY, List.of("small", "big"));
        zeroHops.put(Escalation.MAX_ESCALATIONS_KEY, 0);
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers(zeroHops)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive integer");

        Map<String, Object> notAList = rulesRouter(Map.of());
        notAList.put(Escalation.TIERS_KEY, "small,big");
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers(notAList)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected a list of names");
    }
}
