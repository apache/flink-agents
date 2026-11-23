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

package org.apache.flink.agents.plan.routing;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.routing.Escalation;
import org.apache.flink.agents.api.chat.model.routing.QualityContext;
import org.apache.flink.agents.api.chat.model.routing.QualitySignal;
import org.apache.flink.agents.api.chat.model.routing.QualitySignals;
import org.apache.flink.agents.api.chat.model.routing.QualityVerdict;
import org.apache.flink.agents.api.chat.model.routing.RoutingContext;
import org.apache.flink.agents.api.chat.model.routing.RoutingStrategy;
import org.apache.flink.agents.api.chat.model.routing.RoutingStrategyType;
import org.apache.flink.agents.api.chat.model.routing.Strategies;
import org.apache.flink.agents.api.resource.PythonResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.apache.flink.agents.api.chat.model.routing.ModelRouter.CANDIDATES_KEY;
import static org.apache.flink.agents.api.chat.model.routing.ModelRouter.DEFAULT_MODEL_KEY;
import static org.apache.flink.agents.api.chat.model.routing.ModelRouter.STRATEGY_ARGS_KEY;
import static org.apache.flink.agents.api.chat.model.routing.ModelRouter.STRATEGY_TYPE_KEY;
import static org.apache.flink.agents.api.chat.model.routing.ModelRouter.of;
import static org.assertj.core.api.Assertions.assertThat;

/** The plan-side {@link ModelRouter} resource decodes the descriptor the api builder produced. */
class ModelRouterResourceTest {

    /** A loadable custom signal for the round-trip. */
    public static final class AlwaysAccept implements QualitySignal {
        @Override
        public QualityVerdict evaluate(QualityContext context) {
            return QualityVerdict.accept();
        }
    }

    @Test
    void theResourceDecodesADeclarationThatCarriesThePlaceholder() {
        ResourceDescriptor descriptor =
                of("small", "big").strategy(Strategies.rules(Map.of())).build();
        // A declaration does not choose the resource class: it carries a placeholder, and the
        // plan names this class before it creates a provider (AgentPlanModelRouterProviderTest).
        assertThat(descriptor.getClazz()).isEqualTo(ResourceName.ModelRouter.MODEL_ROUTER);
        ModelRouter router = new ModelRouter(descriptor, null);
        assertThat(router.getResourceType()).isEqualTo(ResourceType.MODEL_ROUTER);
        assertThat(router.getCandidateNames()).containsExactly("small", "big");
    }

    @Test
    void theResourceDescriptorNamesThisClassAndKeepsTheDeclaration() {
        Map<String, Object> arguments =
                of("small", "big")
                        .strategy(Strategies.rules(Map.of()))
                        .build()
                        .getInitialArguments();
        // Whatever class a Java descriptor names, and whatever module it carries.
        ResourceDescriptor provided =
                ModelRouter.resourceDescriptor(
                        new ResourceDescriptor(null, "example.OtherRouter", arguments));
        assertThat(provided.getClazz()).isEqualTo(ModelRouter.class.getName());
        assertThat(provided.getModule()).isNull();
        assertThat(provided.getInitialArguments()).isSameAs(arguments);
        // Already named: returned as it is.
        assertThat(ModelRouter.resourceDescriptor(provided)).isSameAs(provided);
        // A descriptor without arguments keeps none, so the plan can still report it by name.
        assertThat(
                        ModelRouter.resourceDescriptor(
                                        new ResourceDescriptor(
                                                "some.Other", (Map<String, Object>) null))
                                .getInitialArguments())
                .isNull();
        // A descriptor of another language is not this resource's to name: it stays as it is,
        // for the Java provider to reject.
        ResourceDescriptor python =
                new PythonResourceDescriptor("some_module", "SomeRouter", arguments);
        assertThat(ModelRouter.resourceDescriptor(python)).isSameAs(python);
    }

    @Test
    void rulesDeclarationRoundTripsThroughTheRouter() {
        // The router carries the declaration only; compilation and execution live in the
        // executors, so the round-trip is declaration data, not Patterns.
        ModelRouter router =
                new ModelRouter(
                        of("small", "big")
                                .strategy(Strategies.rules(Map.of("big", "\\b(code|sql)\\b")))
                                .defaultModel("small")
                                .build(),
                        null);
        assertThat(router.getStrategy().getType()).isEqualTo(RoutingStrategyType.RULE_BASED);
        Object rules = router.getStrategy().getArguments().get(RoutingStrategy.ARG_RULES);
        assertThat(rules).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) rules).get("big")).isEqualTo("\\b(code|sql)\\b");
    }

    @Test
    void theRouterDecodesCandidatesDefaultAndFallback() {
        ModelRouter router =
                new ModelRouter(
                        of("small", "big")
                                .strategy(Strategies.rules(Map.of("big", "\\b(code|sql)\\b")))
                                .defaultModel("small")
                                .fallback(true)
                                .build(),
                        null);
        assertThat(router.getCandidateNames()).containsExactly("small", "big");
        assertThat(router.getDefaultModel()).contains("small");
        assertThat(router.isFallbackEnabled()).isTrue();
        assertThat(router.isCandidate("big")).isTrue();
        assertThat(router.isCandidate("unknown")).isFalse();
        assertThat(router.getEscalation()).isEmpty();
        UUID requestId = UUID.randomUUID();
        RoutingContext context =
                new RoutingContext(
                        requestId,
                        "router",
                        List.of(new ChatMessage(MessageRole.USER, "write code")),
                        Map.of(),
                        router.getCandidates());
        assertThat(context.getRequestId()).isEqualTo(requestId);
    }

    @Test
    void fallbackIsOffUnlessDeclared() {
        ModelRouter router =
                new ModelRouter(
                        of("small", "big").strategy(Strategies.rules(Map.of())).build(), null);
        assertThat(router.isFallbackEnabled()).isFalse();
        assertThat(router.getDefaultModel()).isEmpty();
    }

    @Test
    void candidateDescriptionsReachStrategies() {
        // Descriptions declared on the router flow into the RoutingContext candidates, which is
        // how semantic strategies learn what each candidate is for.
        ModelRouter router =
                new ModelRouter(
                        of("small", "big")
                                .describe("small", "fast and cheap; chit-chat")
                                .describe("big", "strong; code and SQL")
                                .strategy(Strategies.rules(Map.of()))
                                .defaultModel("small")
                                .build(),
                        null);
        assertThat(router.getCandidates().get(0).getDescription())
                .isEqualTo("fast and cheap; chit-chat");
        assertThat(router.getCandidates().get(1).getDescription())
                .isEqualTo("strong; code and SQL");
    }

    @Test
    void decisionStrategyArgumentsAreDecoded() {
        ModelRouter router =
                new ModelRouter(
                        of("small", "big")
                                .describe("small", "chit-chat")
                                .describe("big", "code and sql")
                                .strategy(Strategies.decision("dm").withMinProbability(0.6))
                                .defaultModel("small")
                                .build(),
                        null);
        assertThat(router.getStrategy().getType()).isEqualTo(RoutingStrategyType.DECISION_MODEL);
        assertThat(router.getStrategy().getArguments().get(RoutingStrategy.ARG_MIN_PROBABILITY))
                .isEqualTo(0.6);
        assertThat(router.getCandidates().get(1).getDescription()).isEqualTo("code and sql");
    }

    @Test
    void tiersRoundTripThroughTheDescriptorWithSignalsAndCap() {
        Escalation declared =
                Escalation.tiers("small", "medium", "big")
                        .when(QualitySignals.decision("verifier", 0.7, "Does the answer fit?"))
                        .when(QualitySignals.custom(AlwaysAccept.class))
                        .maxEscalations(1);
        ModelRouter router =
                new ModelRouter(
                        of("small", "medium", "big")
                                .strategy(Strategies.rules(Map.of("big", "\\bsql\\b")))
                                .defaultModel("small")
                                .escalation(declared)
                                .build(),
                        null);
        assertThat(router.getEscalation()).contains(declared);
        Escalation parsed = router.getEscalation().get();
        assertThat(parsed.getTiers()).containsExactly("small", "medium", "big");
        assertThat(parsed.getMaxEscalations()).isEqualTo(1);
        assertThat(parsed.getSignals()).hasSize(2);
        assertThat(parsed.getSignals().get(0).decisionModel()).isEqualTo("verifier");
        assertThat(parsed.getSignals().get(1).signalClass())
                .isEqualTo(AlwaysAccept.class.getName());
        // The strategy is untouched by the policy.
        assertThat(router.getStrategy().getType()).isEqualTo(RoutingStrategyType.RULE_BASED);
    }

    @Test
    void tiersWithoutAStrategyStartOnTheFirstTierWhichBecomesTheDefault() {
        ResourceDescriptor descriptor =
                of("small", "big").escalation(Escalation.tiers("small", "big")).build();
        assertThat(descriptor.getInitialArguments()).doesNotContainKey(STRATEGY_TYPE_KEY);
        ModelRouter router = new ModelRouter(descriptor, null);
        assertThat(router.getDefaultModel()).contains("small");
        assertThat(router.getStrategy().getType()).isEqualTo(RoutingStrategyType.RULE_BASED);
        assertThat(router.getEscalation().get().getMaxEscalations()).isEqualTo(1);
    }

    @Test
    void aDescriptorWithTiersAndNoDefaultDecodesTheFirstTierAsDefault() {
        // A Python- or YAML-built descriptor may omit the default the Java builder writes.
        Map<String, Object> args = new HashMap<>();
        args.put(CANDIDATES_KEY, List.of("small", "big"));
        Escalation.tiers("small", "big").toArguments(args);
        ModelRouter router =
                new ModelRouter(
                        new ResourceDescriptor(ResourceName.ModelRouter.MODEL_ROUTER, args), null);
        assertThat(router.getDefaultModel()).contains("small");
    }

    @Test
    void aDeclaredDefaultModelWinsOverTheFirstTier() {
        ModelRouter router =
                new ModelRouter(
                        of("small", "big")
                                .escalation(Escalation.tiers("small", "big"))
                                .defaultModel("big")
                                .build(),
                        null);
        assertThat(router.getDefaultModel()).contains("big");
    }

    @Test
    void theConstructorDecodesWithoutApplyingTheDeclarationRules() {
        // The declaration rules are the plan's job (AgentPlan#validateRoutingStrategies, see
        // AgentPlanRouterDescriptorValidationTest); a descriptor that reaches the resource has
        // passed them, so the constructor only decodes what it is given.
        Map<String, Object> args = new HashMap<>();
        args.put(CANDIDATES_KEY, List.of("small", "big"));
        args.put(DEFAULT_MODEL_KEY, "huge");
        args.put(STRATEGY_TYPE_KEY, RoutingStrategyType.LLM_JUDGE.tag());
        args.put(STRATEGY_ARGS_KEY, Map.of());
        ModelRouter router =
                new ModelRouter(
                        new ResourceDescriptor(ResourceName.ModelRouter.MODEL_ROUTER, args), null);
        assertThat(router.getDefaultModel()).contains("huge");
        assertThat(router.getStrategy().getType()).isEqualTo(RoutingStrategyType.LLM_JUDGE);
    }
}
