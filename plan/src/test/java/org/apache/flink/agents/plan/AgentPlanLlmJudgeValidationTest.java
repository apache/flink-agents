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

import org.apache.flink.agents.api.chat.model.routing.LlmJudgeRoutingStrategy;
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.chat.model.routing.Strategies;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.resourceprovider.JavaResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plan-construction validation for {@code Strategies.llm(...)} judge references. */
public class AgentPlanLlmJudgeValidationTest {

    private static Map<ResourceType, Map<String, ResourceProvider>> providers(
            boolean withJudgeModel) {
        Map<ResourceType, Map<String, ResourceProvider>> providers = new HashMap<>();
        ResourceDescriptor routerDescriptor =
                ModelRouter.of("small", "big")
                        .strategy(Strategies.llm("judge"))
                        .defaultModel("small")
                        .build();
        providers
                .computeIfAbsent(ResourceType.MODEL_ROUTER, k -> new HashMap<>())
                .put(
                        "router",
                        new JavaResourceProvider(
                                "router", ResourceType.MODEL_ROUTER, routerDescriptor));
        Map<String, ResourceProvider> chatModels = new HashMap<>();
        ResourceDescriptor model = new ResourceDescriptor("some.Clazz", Map.of());
        chatModels.put("small", new JavaResourceProvider("small", ResourceType.CHAT_MODEL, model));
        chatModels.put("big", new JavaResourceProvider("big", ResourceType.CHAT_MODEL, model));
        if (withJudgeModel) {
            chatModels.put(
                    "judge", new JavaResourceProvider("judge", ResourceType.CHAT_MODEL, model));
        }
        providers.put(ResourceType.CHAT_MODEL, chatModels);
        return providers;
    }

    @Test
    void typoedJudgeModelFailsAtPlanConstruction() {
        // Without this check the job would run with every judge call failing and every request
        // abstaining to the default model — routing silently disabled.
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers(false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("judge model 'judge'")
                .hasMessageContaining("router");
    }

    /** A subclass takes the judge runtime path (instanceof), so it must be validated too. */
    public static class CustomJudge extends LlmJudgeRoutingStrategy {
        public CustomJudge(java.util.Map<String, Object> args) {
            super(args);
        }
    }

    @Test
    void judgeSubclassIsValidatedByAssignability() {
        Map<ResourceType, Map<String, ResourceProvider>> providers = providers(false);
        ResourceDescriptor routerDescriptor =
                ModelRouter.of("small", "big")
                        .strategy(
                                org.apache.flink.agents.api.chat.model.routing.Strategies.of(
                                        CustomJudge.class.getName(),
                                        Map.of(
                                                LlmJudgeRoutingStrategy.ARG_JUDGE_MODEL,
                                                "missing-judge")))
                        .defaultModel("small")
                        .build();
        providers
                .get(ResourceType.MODEL_ROUTER)
                .put(
                        "router",
                        new JavaResourceProvider(
                                "router", ResourceType.MODEL_ROUTER, routerDescriptor));
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing-judge");
    }

    /** A no-arg-constructor subclass supplies its judge internally; validation must use it. */
    public static class SelfConfiguredJudge extends LlmJudgeRoutingStrategy {
        public SelfConfiguredJudge() {
            super(java.util.Map.of(LlmJudgeRoutingStrategy.ARG_JUDGE_MODEL, "judge"));
        }
    }

    @Test
    void noArgJudgeSubclassIsValidatedByItsInstance() {
        Map<ResourceType, Map<String, ResourceProvider>> providers = providers(true);
        ResourceDescriptor routerDescriptor =
                ModelRouter.of("small", "big")
                        .strategy(
                                org.apache.flink.agents.api.chat.model.routing.Strategies.of(
                                        SelfConfiguredJudge.class))
                        .defaultModel("small")
                        .build();
        providers
                .get(ResourceType.MODEL_ROUTER)
                .put(
                        "router",
                        new JavaResourceProvider(
                                "router", ResourceType.MODEL_ROUTER, routerDescriptor));
        // "judge" is registered (providers(true)) and the instance reports it -> passes
        assertThatCode(() -> new AgentPlan(Map.of(), providers)).doesNotThrowAnyException();
    }

    /** Plan construction must never run non-judge custom-strategy constructors. */
    public static class SideEffectingStrategy
            implements org.apache.flink.agents.api.chat.model.routing.RoutingStrategy {
        static final java.util.concurrent.atomic.AtomicInteger CONSTRUCTIONS =
                new java.util.concurrent.atomic.AtomicInteger();

        public SideEffectingStrategy(java.util.Map<String, Object> args) {
            CONSTRUCTIONS.incrementAndGet();
        }

        @Override
        public org.apache.flink.agents.api.chat.model.routing.RoutingDecision route(
                org.apache.flink.agents.api.chat.model.routing.RoutingContext context) {
            return org.apache.flink.agents.api.chat.model.routing.RoutingDecision.abstain();
        }
    }

    @Test
    void nonJudgeCustomStrategyIsNotInstantiatedDuringPlanConstruction() throws Exception {
        Map<ResourceType, Map<String, ResourceProvider>> providers = providers(true);
        ResourceDescriptor routerDescriptor =
                ModelRouter.of("small", "big")
                        .strategy(
                                org.apache.flink.agents.api.chat.model.routing.Strategies.of(
                                        SideEffectingStrategy.class.getName(), Map.of()))
                        .defaultModel("small")
                        .build();
        providers
                .get(ResourceType.MODEL_ROUTER)
                .put(
                        "router",
                        new JavaResourceProvider(
                                "router", ResourceType.MODEL_ROUTER, routerDescriptor));
        int before = SideEffectingStrategy.CONSTRUCTIONS.get();
        new AgentPlan(Map.of(), providers);
        org.junit.jupiter.api.Assertions.assertEquals(
                before, SideEffectingStrategy.CONSTRUCTIONS.get());
    }

    @Test
    void nullResourceProvidersStillConstruct() {
        assertThatCode(() -> new AgentPlan(Map.of(), null)).doesNotThrowAnyException();
    }

    @Test
    void registeredJudgeModelPassesValidation() {
        assertThatCode(() -> new AgentPlan(Map.of(), providers(true))).doesNotThrowAnyException();
    }
}
