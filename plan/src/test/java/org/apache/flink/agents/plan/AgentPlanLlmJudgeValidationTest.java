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

import org.apache.flink.agents.api.chat.model.routing.CustomRoutingExecutor;
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.chat.model.routing.RoutingContext;
import org.apache.flink.agents.api.chat.model.routing.RoutingDecision;
import org.apache.flink.agents.api.chat.model.routing.RoutingStrategy;
import org.apache.flink.agents.api.chat.model.routing.Strategies;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.resourceprovider.JavaResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plan-construction validation of routing-strategy declarations. */
public class AgentPlanLlmJudgeValidationTest {

    private static Map<ResourceType, Map<String, ResourceProvider>> providers(
            boolean withJudgeModel) {
        Map<ResourceType, Map<String, ResourceProvider>> providers = new HashMap<>();
        setRouter(
                providers,
                ModelRouter.of("small", "big")
                        .strategy(Strategies.llm("judge"))
                        .defaultModel("small")
                        .build());
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

    private static void setRouter(
            Map<ResourceType, Map<String, ResourceProvider>> providers,
            ResourceDescriptor routerDescriptor) {
        providers
                .computeIfAbsent(ResourceType.MODEL_ROUTER, k -> new HashMap<>())
                .put(
                        "router",
                        new JavaResourceProvider(
                                "router", ResourceType.MODEL_ROUTER, routerDescriptor));
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

    /**
     * A judge declaration with structurally invalid arguments (here: no judge model) must fail plan
     * construction, not per record at runtime. The descriptor is built by hand to simulate a plan
     * produced outside the builder (e.g. deserialized), where the factory validation never ran.
     */
    @Test
    void judgeWithMissingJudgeModelFailsAtPlanConstruction() {
        Map<ResourceType, Map<String, ResourceProvider>> providers = providers(true);
        Map<String, Object> args = new HashMap<>();
        args.put("candidates", List.of("small", "big"));
        args.put("default_model", "small");
        args.put("fallback", false);
        args.put(ModelRouter.STRATEGY_TYPE_KEY, "llm_judge");
        args.put(ModelRouter.STRATEGY_ARGS_KEY, Map.of());
        setRouter(providers, new ResourceDescriptor(ModelRouter.class.getName(), args));
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(RoutingStrategy.ARG_JUDGE_MODEL);
    }

    /**
     * The judge must be a plain chat model: a bound prompt/tools/skills silently breaks verdict
     * parsing on every request, so it fails at plan construction (W5 — static config constraint).
     */
    @Test
    void judgeWithBoundPromptFailsAtPlanConstruction() {
        Map<ResourceType, Map<String, ResourceProvider>> providers = providers(false);
        ResourceDescriptor promptBound =
                new ResourceDescriptor("some.Clazz", Map.of("prompt", "review-prompt"));
        providers
                .get(ResourceType.CHAT_MODEL)
                .put(
                        "judge",
                        new JavaResourceProvider("judge", ResourceType.CHAT_MODEL, promptBound));
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plain chat model")
                .hasMessageContaining("prompt");
    }

    @Test
    void judgeWithBoundToolsFailsAtPlanConstruction() {
        Map<ResourceType, Map<String, ResourceProvider>> providers = providers(false);
        ResourceDescriptor toolBound =
                new ResourceDescriptor("some.Clazz", Map.of("tools", List.of("calculator")));
        providers
                .get(ResourceType.CHAT_MODEL)
                .put(
                        "judge",
                        new JavaResourceProvider("judge", ResourceType.CHAT_MODEL, toolBound));
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plain chat model")
                .hasMessageContaining("tools");
    }

    /** Plan construction must never run custom-executor constructors. */
    public static class SideEffectingExecutor implements CustomRoutingExecutor {
        static final java.util.concurrent.atomic.AtomicInteger CONSTRUCTIONS =
                new java.util.concurrent.atomic.AtomicInteger();

        public SideEffectingExecutor(Map<String, Object> args) {
            CONSTRUCTIONS.incrementAndGet();
        }

        @Override
        public RoutingDecision route(RoutingStrategy strategy, RoutingContext context) {
            return RoutingDecision.abstain();
        }
    }

    @Test
    void customExecutorIsNotInstantiatedDuringPlanConstruction() throws Exception {
        Map<ResourceType, Map<String, ResourceProvider>> providers = providers(true);
        setRouter(
                providers,
                ModelRouter.of("small", "big")
                        .strategy(Strategies.custom(SideEffectingExecutor.class))
                        .defaultModel("small")
                        .build());
        int before = SideEffectingExecutor.CONSTRUCTIONS.get();
        new AgentPlan(Map.of(), providers);
        org.junit.jupiter.api.Assertions.assertEquals(
                before, SideEffectingExecutor.CONSTRUCTIONS.get());
    }

    /** Not a CustomRoutingExecutor at all. */
    public static class NotAnExecutor {
        public NotAnExecutor() {}
    }

    @Test
    void customClassNotImplementingExecutorFailsAtPlanConstruction() {
        Map<ResourceType, Map<String, ResourceProvider>> providers = providers(true);
        setRouter(
                providers,
                ModelRouter.of("small", "big")
                        .strategy(Strategies.custom(NotAnExecutor.class.getName(), Map.of()))
                        .defaultModel("small")
                        .build());
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not implement CustomRoutingExecutor");
    }

    @Test
    void customClassAbsentFromClasspathFailsAtPlanConstruction() {
        Map<ResourceType, Map<String, ResourceProvider>> providers = providers(true);
        setRouter(
                providers,
                ModelRouter.of("small", "big")
                        .strategy(Strategies.custom("no.such.ExecutorClazz", Map.of()))
                        .defaultModel("small")
                        .build());
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not on the classpath");
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
