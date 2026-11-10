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

package org.apache.flink.agents.api.yaml;

import org.apache.flink.agents.api.AgentBuilder;
import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.chat.model.routing.ModelRouter;
import org.apache.flink.agents.api.configuration.Configuration;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.yaml.YamlLoader.LoadedFile;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.table.api.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Loader coverage for YAML {@code model_routers} declarations. */
class YamlModelRouterLoaderTest {

    @TempDir Path tmp;

    @Test
    void declaresAgentLocalAndSharedRouters() throws Exception {
        LoadedFile loaded = YamlLoader.buildAgents(write(routersYaml()));

        ResourceDescriptor shared = router(loaded.getSharedResources(), "shared_router");
        ResourceDescriptor local =
                router(loaded.getAgents().get("routed").getResources(), "local_router");

        assertThat(shared.getLanguage()).isEqualTo("java");
        assertThat(local.getLanguage()).isEqualTo("java");
        assertThat(shared.getClazz()).isEqualTo(ModelRouter.class.getName());
        assertThat(local.getClazz()).isEqualTo(ModelRouter.class.getName());
        assertThat(shared.getModule()).isEmpty();
        assertThat(shared.getInitialArguments())
                .containsEntry("candidates", List.of("small", "big"))
                .containsEntry("strategy_type", "rule_based")
                .containsEntry("default_model", "small")
                .containsEntry("strategy_args", Map.of("rules", Map.of("big", "sql")));
        assertThat(local.getInitialArguments())
                .containsEntry("candidates", List.of("small", "big"))
                .containsEntry(
                        "candidate_descriptions",
                        Map.of("small", "cheap model", "big", "code model"))
                .containsEntry("strategy_type", "rule_based")
                .containsEntry("strategy_args", Map.of("rules", Map.of("big", "code")))
                .containsEntry("default_model", "small")
                .containsEntry("fallback", true);
    }

    @Test
    void loadYamlRegistersSharedRouterOnTheEnvironment() throws Exception {
        AgentsExecutionEnvironment env = new TestEnv();
        env.loadYaml(write(routersYaml()));

        assertThat(env.getResources().get(ResourceType.MODEL_ROUTER)).containsKey("shared_router");
        assertThat(env.getAgents()).containsKey("routed");
        assertThat(env.getAgents().get("routed").getResources().get(ResourceType.MODEL_ROUTER))
                .containsKey("local_router");
    }

    @Test
    void pythonDeclarationIsRejected() throws Exception {
        String yaml =
                "model_routers:\n"
                        + "  - name: router\n"
                        + "    clazz: model_router\n"
                        + "    candidates: [small]\n"
                        + "    strategy_type: rule_based\n";
        assertThatThrownBy(() -> YamlLoader.buildAgents(write(yaml)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("type='python' is not supported for model_router");
    }

    @Test
    void nameCollisionMatchesProgrammaticRegistration() throws Exception {
        String yaml =
                "agents:\n"
                        + "  - name: routed\n"
                        + "    chat_model_setups:\n"
                        + "      - name: same\n"
                        + "        clazz: ollama\n"
                        + "        type: java\n"
                        + "    model_routers:\n"
                        + "      - name: same\n"
                        + "        clazz: model_router\n"
                        + "        type: java\n"
                        + "        candidates: [small]\n"
                        + "        strategy_type: rule_based\n";
        Throwable yamlFailure =
                org.assertj.core.api.Assertions.catchThrowable(
                        () -> YamlLoader.buildAgents(write(yaml)));

        Agent agent = new Agent();
        agent.addResource(
                "same",
                ResourceType.CHAT_MODEL,
                new ResourceDescriptor("org.example.Setup", Map.of()));
        Throwable programmatic =
                org.assertj.core.api.Assertions.catchThrowable(
                        () ->
                                agent.addResource(
                                        "same",
                                        ResourceType.MODEL_ROUTER,
                                        new ResourceDescriptor(
                                                ModelRouter.class.getName(), Map.of())));

        assertThat(yamlFailure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(programmatic.getMessage());
    }

    private Path write(String yaml) throws Exception {
        return Files.writeString(tmp.resolve("routers.yaml"), yaml);
    }

    private static ResourceDescriptor router(
            Map<ResourceType, Map<String, Object>> resources, String name) {
        return (ResourceDescriptor) resources.get(ResourceType.MODEL_ROUTER).get(name);
    }

    private static String routersYaml() {
        return ""
                + "model_routers:\n"
                + "  - name: shared_router\n"
                + "    clazz: model_router\n"
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
                + "        clazz: org.apache.flink.agents.api.chat.model.routing.ModelRouter\n"
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
                + "        fallback: true\n";
    }

    /** Minimal env stub — LocalExecutionEnvironment is not visible from the api module. */
    private static final class TestEnv extends AgentsExecutionEnvironment {
        @Override
        public Configuration getConfig() {
            return null;
        }

        @Override
        public AgentBuilder fromList(List<Object> input) {
            return null;
        }

        @Override
        public <T, K> AgentBuilder fromDataStream(
                DataStream<T> input, KeySelector<T, K> keySelector) {
            return null;
        }

        @Override
        public <K> AgentBuilder fromTable(Table input, KeySelector<Object, K> keySelector) {
            return null;
        }

        @Override
        public void execute() {}

        @Override
        public void execute(String jobName) {}
    }
}
