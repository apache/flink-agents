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
import org.apache.flink.agents.api.chat.model.routing.QualityContext;
import org.apache.flink.agents.api.chat.model.routing.QualitySignal;
import org.apache.flink.agents.api.chat.model.routing.QualitySignals;
import org.apache.flink.agents.api.chat.model.routing.QualityVerdict;
import org.apache.flink.agents.api.chat.model.routing.Strategies;
import org.apache.flink.agents.api.context.RunnerContext;
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

/** Plan-construction validation of escalation declarations. */
class AgentPlanEscalationValidationTest {

    public static final class AcceptAll implements QualitySignal {
        @Override
        public QualityVerdict evaluate(QualityContext context, RunnerContext runnerContext) {
            return QualityVerdict.accept();
        }
    }

    private static Map<ResourceType, Map<String, ResourceProvider>> providers(
            ResourceDescriptor router, boolean withDecisionModel) {
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
        providers.put(ResourceType.CHAT_MODEL, chatModels);
        if (withDecisionModel) {
            providers.put(
                    ResourceType.DECISION_MODEL,
                    new HashMap<>(
                            Map.of(
                                    "verifier",
                                    new JavaResourceProvider(
                                            "verifier",
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
                                            "conn",
                                            ResourceType.DECISION_MODEL_CONNECTION,
                                            model))));
        }
        return providers;
    }

    @Test
    void tiersWithoutAStrategyPassPlanConstruction() {
        ResourceDescriptor router =
                ModelRouter.of("small", "big").escalation(Escalation.tiers("small", "big")).build();
        assertThatCode(() -> new AgentPlan(Map.of(), providers(router, false)))
                .doesNotThrowAnyException();
    }

    @Test
    void aRegisteredDecisionModelSignalAndALoadableCustomSignalPass() {
        ResourceDescriptor router =
                ModelRouter.of("small", "big")
                        .strategy(Strategies.rules(Map.of("big", "\\bsql\\b")))
                        .defaultModel("small")
                        .escalation(
                                Escalation.tiers("small", "big")
                                        .when(QualitySignals.decision("verifier", 0.7))
                                        .when(QualitySignals.custom(AcceptAll.class)))
                        .build();
        assertThatCode(() -> new AgentPlan(Map.of(), providers(router, true)))
                .doesNotThrowAnyException();
    }

    @Test
    void anUnregisteredDecisionModelSignalFailsAtPlanConstruction() {
        ResourceDescriptor router =
                ModelRouter.of("small", "big")
                        .escalation(
                                Escalation.tiers("small", "big")
                                        .when(QualitySignals.decision("verifier", 0.7)))
                        .build();
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers(router, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decision model 'verifier'")
                .hasMessageContaining("router");
    }

    @Test
    void anUnloadableOrWrongTypedCustomSignalFailsAtPlanConstruction() {
        ResourceDescriptor missing =
                ModelRouter.of("small", "big")
                        .escalation(
                                Escalation.tiers("small", "big")
                                        .when(QualitySignals.custom("no.such.Signal", Map.of())))
                        .build();
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers(missing, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be loaded");
        ResourceDescriptor wrongType =
                ModelRouter.of("small", "big")
                        .escalation(
                                Escalation.tiers("small", "big")
                                        .when(
                                                QualitySignals.custom(
                                                        String.class.getName(), Map.of())))
                        .build();
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers(wrongType, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not implement QualitySignal");
    }

    /** A hand-built descriptor skipped the builder; the plan applies the same tier rule. */
    @Test
    void aTierThatIsNotACandidateFailsAtPlanConstruction() {
        Map<String, Object> args = new HashMap<>();
        args.put(ModelRouter.CANDIDATES_KEY, List.of("small", "big"));
        args.put(Escalation.TIERS_KEY, List.of("small", "huge"));
        ResourceDescriptor router = new ResourceDescriptor(ModelRouter.class.getName(), args);
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers(router, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tier 'huge' is not one of the candidates");
    }

    @Test
    void neitherAStrategyNorTiersStillFails() {
        Map<String, Object> args = new HashMap<>();
        args.put(ModelRouter.CANDIDATES_KEY, List.of("small", "big"));
        ResourceDescriptor router = new ResourceDescriptor(ModelRouter.class.getName(), args);
        assertThatThrownBy(() -> new AgentPlan(Map.of(), providers(router, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no routing strategy")
                .hasMessageContaining("no escalation tiers");
    }
}
