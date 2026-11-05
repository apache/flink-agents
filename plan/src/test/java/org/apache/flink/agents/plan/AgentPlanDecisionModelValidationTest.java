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

import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.chat.model.routing.RoutingStrategy;
import org.apache.flink.agents.api.chat.model.routing.RoutingStrategyType;
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

/** Plan-construction validation of decision-model routing declarations. */
public class AgentPlanDecisionModelValidationTest {

    private static Map<ResourceType, Map<String, ResourceProvider>> providers(
            boolean withDecisionModel) {
        Map<ResourceType, Map<String, ResourceProvider>> providers = new HashMap<>();
        setRouter(
                providers,
                ModelRouter.of("small", "big")
                        .describe("small", "short factual questions")
                        .describe("big", "code and sql")
                        .strategy(Strategies.decision("dm"))
                        .defaultModel("small")
                        .build());
        Map<String, ResourceProvider> chatModels = new HashMap<>();
        ResourceDescriptor model = new ResourceDescriptor("some.Clazz", Map.of());
        chatModels.put("small", new JavaResourceProvider("small", ResourceType.CHAT_MODEL, model));
        chatModels.put("big", new JavaResourceProvider("big", ResourceType.CHAT_MODEL, model));
        providers.put(ResourceType.CHAT_MODEL, chatModels);
        if (withDecisionModel) {
            Map<String, ResourceProvider> decisionModels = new HashMap<>();
            decisionModels.put(
                    "dm",
                    new JavaResourceProvider(
                            "dm",
                            ResourceType.DECISION_MODEL,
                            new ResourceDescriptor(
                                    "some.Setup", Map.of("connection", "conn", "model", "m"))));
            providers.put(ResourceType.DECISION_MODEL, decisionModels);
            Map<String, ResourceProvider> connections = new HashMap<>();
            connections.put(
                    "conn",
                    new JavaResourceProvider(
                            "conn", ResourceType.DECISION_MODEL_CONNECTION, model));
            providers.put(ResourceType.DECISION_MODEL_CONNECTION, connections);
        }
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
    void registeredDecisionModelWithDescribedCandidatesPassesPlanConstruction() {
        assertThatCode(() -> new AgentPlan(Map.of(), providers(true))).doesNotThrowAnyException();
    }

    @Test
    void typoedDecisionModelFailsAtPlanConstruction() {
        // Without this check every routed request would fail at resource lookup on the
        // TaskManager; a static constraint belongs at plan construction.
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers(false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decision model 'dm'")
                .hasMessageContaining("router");
    }

    /**
     * The setup's connection is opened per request, so a typo'd name fails here, not per record.
     */
    @Test
    void typoedDecisionConnectionFailsAtPlanConstruction() {
        Map<ResourceType, Map<String, ResourceProvider>> providers = providers(true);
        providers.remove(ResourceType.DECISION_MODEL_CONNECTION);
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connection 'conn'")
                .hasMessageContaining("router");
    }

    /** A setup that declares no connection may resolve one from provider defaults; not checked. */
    @Test
    void decisionSetupWithoutExplicitConnectionPassesPlanConstruction() {
        Map<ResourceType, Map<String, ResourceProvider>> providers = providers(true);
        providers.remove(ResourceType.DECISION_MODEL_CONNECTION);
        providers
                .get(ResourceType.DECISION_MODEL)
                .put(
                        "dm",
                        new JavaResourceProvider(
                                "dm",
                                ResourceType.DECISION_MODEL,
                                new ResourceDescriptor("some.Setup", Map.of("model", "m"))));
        assertThatCode(() -> new AgentPlan(Map.of(), providers)).doesNotThrowAnyException();
    }

    /** A non-String connection can never open; it fails at plan time with the router named. */
    @Test
    void nonStringDecisionConnectionFailsAtPlanConstruction() {
        Map<ResourceType, Map<String, ResourceProvider>> providers = providers(true);
        providers
                .get(ResourceType.DECISION_MODEL)
                .put(
                        "dm",
                        new JavaResourceProvider(
                                "dm",
                                ResourceType.DECISION_MODEL,
                                new ResourceDescriptor(
                                        "some.Setup", Map.of("connection", 5, "model", "m"))));
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected a connection name");
    }

    /** A non-String candidate description is diagnosed at plan time, for any strategy. */
    @Test
    void nonStringCandidateDescriptionFailsAtPlanConstruction() {
        Map<ResourceType, Map<String, ResourceProvider>> providers = providers(true);
        Map<String, Object> args = new HashMap<>();
        args.put(ModelRouter.CANDIDATES_KEY, List.of("small", "big"));
        args.put(
                ModelRouter.CANDIDATE_DESCRIPTIONS_KEY,
                Map.of("small", "short factual questions", "big", 42));
        args.put(ModelRouter.STRATEGY_TYPE_KEY, RoutingStrategyType.LLM_JUDGE.tag());
        args.put(ModelRouter.STRATEGY_ARGS_KEY, Map.of(RoutingStrategy.ARG_JUDGE_MODEL, "small"));
        setRouter(providers, new ResourceDescriptor(ModelRouter.class.getName(), args));
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidate 'big' has a Integer description");
    }

    /** A declared but blank model can never open; it fails at plan time. */
    @Test
    void blankDecisionModelNameFailsAtPlanConstruction() {
        Map<ResourceType, Map<String, ResourceProvider>> providers = providers(true);
        providers
                .get(ResourceType.DECISION_MODEL)
                .put(
                        "dm",
                        new JavaResourceProvider(
                                "dm",
                                ResourceType.DECISION_MODEL,
                                new ResourceDescriptor(
                                        "some.Setup", Map.of("connection", "conn", "model", " "))));
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank or non-String 'model'");
    }

    /** An explicitly blank connection can never open; it fails at plan time. */
    @Test
    void blankDecisionConnectionFailsAtPlanConstruction() {
        Map<ResourceType, Map<String, ResourceProvider>> providers = providers(true);
        providers
                .get(ResourceType.DECISION_MODEL)
                .put(
                        "dm",
                        new JavaResourceProvider(
                                "dm",
                                ResourceType.DECISION_MODEL,
                                new ResourceDescriptor(
                                        "some.Setup", Map.of("connection", " ", "model", "m"))));
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank 'connection'");
    }

    /**
     * The builder already rejects an undescribed candidate; a descriptor built by hand (e.g.
     * deserialized) never ran it, so the plan applies the same rule.
     */
    @Test
    void undescribedCandidateFailsAtPlanConstruction() {
        Map<ResourceType, Map<String, ResourceProvider>> providers = providers(true);
        Map<String, Object> args = new HashMap<>();
        args.put(ModelRouter.CANDIDATES_KEY, List.of("small", "big"));
        args.put(ModelRouter.CANDIDATE_DESCRIPTIONS_KEY, Map.of("big", "code and sql"));
        args.put(ModelRouter.DEFAULT_MODEL_KEY, "small");
        args.put("fallback", false);
        args.put(ModelRouter.STRATEGY_TYPE_KEY, RoutingStrategyType.DECISION_MODEL.tag());
        args.put(ModelRouter.STRATEGY_ARGS_KEY, Map.of(RoutingStrategy.ARG_DECISION_MODEL, "dm"));
        setRouter(providers, new ResourceDescriptor(ModelRouter.class.getName(), args));
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a description for every candidate")
                .hasMessageContaining("small");
    }

    /** A structurally invalid declaration (no decision model) fails like a judge without one. */
    @Test
    void missingDecisionModelArgumentFailsAtPlanConstruction() {
        Map<ResourceType, Map<String, ResourceProvider>> providers = providers(true);
        Map<String, Object> args = new HashMap<>();
        args.put(ModelRouter.CANDIDATES_KEY, List.of("small", "big"));
        args.put(
                ModelRouter.CANDIDATE_DESCRIPTIONS_KEY,
                Map.of("small", "short factual questions", "big", "code and sql"));
        args.put(ModelRouter.STRATEGY_TYPE_KEY, RoutingStrategyType.DECISION_MODEL.tag());
        args.put(ModelRouter.STRATEGY_ARGS_KEY, Map.of());
        setRouter(providers, new ResourceDescriptor(ModelRouter.class.getName(), args));
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(RoutingStrategy.ARG_DECISION_MODEL);
    }
}
