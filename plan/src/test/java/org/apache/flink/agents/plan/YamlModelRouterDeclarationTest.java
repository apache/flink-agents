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

import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.model.routing.CustomRoutingExecutor;
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.chat.model.routing.RoutingCandidate;
import org.apache.flink.agents.api.chat.model.routing.RoutingContext;
import org.apache.flink.agents.api.chat.model.routing.RoutingDecision;
import org.apache.flink.agents.api.chat.model.routing.RoutingStrategy;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ModelRoutingEvent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.yaml.YamlLoader;
import org.apache.flink.agents.api.yaml.YamlLoader.LoadedFile;
import org.apache.flink.agents.plan.resourceprovider.JavaResourceProvider;
import org.apache.flink.agents.plan.routing.ModelRoutingResolver;
import org.apache.flink.agents.plan.routing.ResolvedModelRoute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * YAML-declared routers use the same plan validation and routing execution as a programmatic
 * descriptor.
 */
class YamlModelRouterDeclarationTest {

    @TempDir Path tmp;

    /** Selects {@code big} so a YAML custom strategy can be executed without extra arguments. */
    public static final class SelectBig implements CustomRoutingExecutor {
        public SelectBig() {}

        @Override
        public RoutingDecision route(RoutingStrategy strategy, RoutingContext context) {
            return RoutingDecision.builder("big").reason("custom").build();
        }
    }

    @Test
    void yamlRoutersExecuteRulesAndCustomStrategies() throws Exception {
        String executor = SelectBig.class.getName();
        LoadedFile loaded =
                YamlLoader.buildAgents(
                        write(
                                ""
                                        + "model_routers:\n"
                                        + "  - name: shared_router\n"
                                        + "    clazz: router\n"
                                        + "    type: java\n"
                                        + "    candidates: [small, big]\n"
                                        + "    strategy_type: rule_based\n"
                                        + "    strategy_args:\n"
                                        + "      rules:\n"
                                        + "        big: sql\n"
                                        + "    default_model: small\n"
                                        + "agents:\n"
                                        + "  - name: routed\n"
                                        + "    model_routers:\n"
                                        + "      - name: local_router\n"
                                        + "        clazz: router\n"
                                        + "        type: java\n"
                                        + "        candidates: [small, big]\n"
                                        + "        candidate_descriptions:\n"
                                        + "          small: cheap model\n"
                                        + "          big: code model\n"
                                        + "        strategy_type: rule_based\n"
                                        + "        strategy_args:\n"
                                        + "          rules:\n"
                                        + "            big: code\n"
                                        + "        default_model: small\n"
                                        + "        fallback: true\n"
                                        + "      - name: custom_router\n"
                                        + "        clazz: router\n"
                                        + "        type: java\n"
                                        + "        candidates: [small, big]\n"
                                        + "        strategy_type: custom\n"
                                        + "        strategy_executor_class: "
                                        + executor
                                        + "\n"));
        Agent agent = loaded.getAgents().get("routed");
        agent.addResourcesIfAbsent(loaded.getSharedResources());
        AgentPlan plan = new AgentPlan(agent);

        ModelRouter local = instantiate(plan, "local_router");
        assertThat(local.getCandidates())
                .extracting(RoutingCandidate::getName)
                .containsExactly("small", "big");
        assertThat(local.getCandidates())
                .extracting(RoutingCandidate::getDescription)
                .containsExactly("cheap model", "code model");
        assertThat(local.getDefaultModel()).contains("small");
        assertThat(local.isFallbackEnabled()).isTrue();

        ResolvedModelRoute matched = resolve(local, "local_router", "please write code");
        assertThat(matched.getSelectedModel()).isEqualTo("big");
        assertThat(matched.isFallbackEnabled()).isTrue();
        assertThat(matched.attemptOrder()).containsExactly("big", "small");
        assertThat(metadata(matched))
                .containsEntry("decision_source", ModelRoutingEvent.SOURCE_STRATEGY)
                .containsEntry("reason", "matched rule: code");

        ModelRouter shared = instantiate(plan, "shared_router");
        ResolvedModelRoute abstained = resolve(shared, "shared_router", "hello");
        assertThat(abstained.getSelectedModel()).isEqualTo("small");
        assertThat(metadata(abstained))
                .containsEntry("decision_source", ModelRoutingEvent.SOURCE_DEFAULT);

        ResolvedModelRoute custom =
                resolve(instantiate(plan, "custom_router"), "custom_router", "hello");
        assertThat(custom.getSelectedModel()).isEqualTo("big");
        assertThat(metadata(custom)).containsEntry("reason", "custom");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "strategy_type: rule_based\n"
                        + "        strategy_args:\n"
                        + "          rules:\n"
                        + "            missing: x\n",
                "strategy_type: not_a_strategy\n",
                "strategy_type: llm_judge\n"
                        + "        strategy_args:\n"
                        + "          judge_model: missing_judge\n",
                "strategy_type: custom\n" + "        strategy_executor_class: missing.Executor\n",
                "candidates: [small, big]\n"
            })
    void invalidYamlFailsLikeTheSameProgrammaticDescriptor(String strategyYaml) throws Exception {
        String body =
                strategyYaml.startsWith("candidates:")
                        ? strategyYaml
                        : "candidates: [small, big]\n        " + strategyYaml;
        Agent yamlAgent =
                YamlLoader.buildAgents(
                                write(
                                        "agents:\n"
                                                + "  - name: routed\n"
                                                + "    model_routers:\n"
                                                + "      - name: router\n"
                                                + "        clazz: router\n"
                                                + "        type: java\n"
                                                + "        "
                                                + body))
                        .getAgents()
                        .get("routed");
        ResourceDescriptor descriptor =
                (ResourceDescriptor)
                        yamlAgent.getResources().get(ResourceType.MODEL_ROUTER).get("router");
        Agent programmatic = new Agent();
        programmatic.addResource("router", ResourceType.MODEL_ROUTER, descriptor);

        Throwable fromYaml =
                org.assertj.core.api.Assertions.catchThrowable(() -> new AgentPlan(yamlAgent));
        Throwable fromProgrammatic =
                org.assertj.core.api.Assertions.catchThrowable(() -> new AgentPlan(programmatic));

        assertThat(fromYaml).isInstanceOf(Exception.class);
        assertThat(fromProgrammatic)
                .isInstanceOf(fromYaml.getClass())
                .hasMessage(fromYaml.getMessage());
    }

    private static ModelRouter instantiate(AgentPlan plan, String name) throws Exception {
        JavaResourceProvider provider =
                (JavaResourceProvider)
                        plan.getResourceProviders().get(ResourceType.MODEL_ROUTER).get(name);
        return (ModelRouter) provider.provide(null);
    }

    private static ResolvedModelRoute resolve(ModelRouter router, String name, String text)
            throws Exception {
        RunnerContext ctx = mock(RunnerContext.class);
        when(ctx.hasResource(name, ResourceType.MODEL_ROUTER)).thenReturn(true);
        when(ctx.getResource(name, ResourceType.MODEL_ROUTER)).thenReturn(router);
        when(ctx.durableExecute(any()))
                .thenAnswer(invocation -> ((DurableCallable<?>) invocation.getArgument(0)).call());
        when(ctx.getActionMetricGroup()).thenReturn(null);
        return ModelRoutingResolver.resolve(
                UUID.randomUUID(), name, List.of(ChatMessage.user(text)), Map.of(), ctx);
    }

    private static Map<String, Object> metadata(ResolvedModelRoute route) {
        return route.buildResponseMetadata(
                route.getSelectedModel(), List.of(route.getSelectedModel()));
    }

    private Path write(String yaml) throws Exception {
        return Files.writeString(tmp.resolve("routers-" + UUID.randomUUID() + ".yaml"), yaml);
    }
}
