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
import org.apache.flink.agents.api.configuration.Configuration;
import org.apache.flink.agents.api.function.JavaFunction;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.skills.SkillSourceSpec;
import org.apache.flink.agents.api.skills.Skills;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.table.api.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlLoaderLoadYamlTest {

    private static final Path FIXTURES = Paths.get("src/test/resources/yaml/fixtures");

    @Test
    void registersSingleAgentOnEnv() {
        AgentsExecutionEnvironment env = new TestEnv();
        env.loadYaml(FIXTURES.resolve("single_agent.yaml"));
        assertThat(env.getAgents()).containsKey("incrementer");
    }

    @Test
    void mergesSharedActionIntoAgents() {
        AgentsExecutionEnvironment env = new TestEnv();
        env.loadYaml(FIXTURES.resolve("with_shared.yaml"));

        Agent a1 = env.getAgents().get("a1");
        Agent a2 = env.getAgents().get("a2");

        var a1Actions = a1.getActions();
        var a2Actions = a2.getActions();
        assertThat(a1Actions).containsKey("shared_inc").containsKey("own_dec");
        assertThat(a1Actions.keySet()).containsExactly("shared_inc", "own_dec");
        assertThat(a2Actions).containsKey("shared_inc");

        assertThat(a1Actions.get("shared_inc").f1).isInstanceOf(JavaFunction.class);
    }

    @Test
    void registersSharedResourcesOnEnv() {
        AgentsExecutionEnvironment env = new TestEnv();
        env.loadYaml(FIXTURES.resolve("with_shared.yaml"));
        assertThat(env.getResources().get(ResourceType.CHAT_MODEL_CONNECTION))
                .containsKey("shared_conn");
    }

    @Test
    void multipleFilesAccumulate() {
        AgentsExecutionEnvironment env = new TestEnv();
        env.loadYaml(
                List.of(
                        FIXTURES.resolve("multi_file_a.yaml"),
                        FIXTURES.resolve("multi_file_b.yaml")));
        assertThat(env.getAgents()).containsKeys("file_a_agent", "file_b_agent");
        assertThat(env.getResources().get(ResourceType.CHAT_MODEL_CONNECTION))
                .containsKeys("conn_from_a", "conn_from_b");
    }

    @Test
    void duplicateAgentAcrossCallsRejected() {
        AgentsExecutionEnvironment env = new TestEnv();
        env.loadYaml(FIXTURES.resolve("multi_file_a.yaml"));
        assertThatThrownBy(() -> env.loadYaml(FIXTURES.resolve("multi_file_a.yaml")))
                .hasMessageContaining("file_a_agent");
    }

    @Test
    void missingSharedActionRejected(@TempDir Path tmp) throws Exception {
        Path bad = tmp.resolve("bad.yaml");
        Files.writeString(bad, "agents:\n  - name: a\n    actions:\n      - undefined_action\n");
        AgentsExecutionEnvironment env = new TestEnv();
        assertThatThrownBy(() -> env.loadYaml(bad)).hasMessageContaining("undefined_action");
    }

    @Test
    void sharedSkillsRegistered() {
        AgentsExecutionEnvironment env = new TestEnv();
        env.loadYaml(FIXTURES.resolve("with_skills.yaml"));
        Skills shared = (Skills) env.getResources().get(ResourceType.SKILLS).get("shared_skills");
        assertThat(shared.getSources())
                .containsExactly(
                        new SkillSourceSpec("local", Map.of("path", "./shared_skill_dir")),
                        new SkillSourceSpec("local", Map.of("path", "./more")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resolvesPreviouslyLoadedChild(boolean separateCalls, @TempDir Path tmp) throws Exception {
        Path childPath = Files.writeString(tmp.resolve("child.yaml"), "agents: [{name: child}]");
        Path parentPath =
                Files.writeString(
                        tmp.resolve("parent.yaml"),
                        "agents: [{name: parent, subagents: [{name: reviewer, agent: child}]}]");
        AgentsExecutionEnvironment env = new TestEnv();
        if (separateCalls) {
            env.loadYaml(childPath);
            env.loadYaml(parentPath);
        } else {
            env.loadYaml(List.of(childPath, parentPath));
        }
        ResourceDescriptor descriptor =
                (ResourceDescriptor)
                        env.getAgents()
                                .get("parent")
                                .getResources()
                                .get(ResourceType.AGENT)
                                .get("reviewer");
        assertThat(descriptor.getInitialArguments().get("agent"))
                .isSameAs(env.getAgents().get("child"));
        assertThat(env.getAgents()).containsOnlyKeys("parent", "child");
        assertThat(env.getAgents().get("child").getResources().get(ResourceType.AGENT)).isEmpty();
        assertThatThrownBy(() -> new TestEnv().loadYaml(parentPath))
                .hasMessageContaining("unknown agent 'child'");
    }

    @Test
    void childMustBeLoadedFirstAndFailurePreservesState(@TempDir Path tmp) throws Exception {
        AgentsExecutionEnvironment env = new TestEnv();
        env.loadYaml(
                Files.writeString(
                        tmp.resolve("existing.yaml"),
                        "agents: [{name: existing}]\nprompts: [{name: existing_prompt, text: keep}]"));
        Agent original = env.getAgents().get("existing");
        Object prompt = env.getResources().get(ResourceType.PROMPT).get("existing_prompt");
        Path childPath = Files.writeString(tmp.resolve("child.yaml"), "agents: [{name: child}]");
        Path parentPath =
                Files.writeString(
                        tmp.resolve("parent.yaml"),
                        "agents: [{name: parent, subagents: [{name: reviewer, agent: child}]}]\n"
                                + "prompts: [{name: new_prompt, text: 'not committed'}]");
        assertThatThrownBy(() -> env.loadYaml(List.of(parentPath, childPath)))
                .hasMessageContaining("reviewer")
                .hasMessageContaining("unknown agent 'child'")
                .hasMessageContaining("already registered");
        assertThat(env.getAgents()).containsExactlyEntriesOf(Map.of("existing", original));
        assertThat(env.getResources().get(ResourceType.PROMPT))
                .containsExactlyEntriesOf(Map.of("existing_prompt", prompt));
        env.loadYaml(List.of(childPath, parentPath));
        assertThat(env.getAgents()).containsOnlyKeys("existing", "child", "parent");
    }

    @Test
    void currentFileCannotReplaceRegisteredChild(@TempDir Path tmp) throws Exception {
        AgentsExecutionEnvironment env = new TestEnv();
        env.loadYaml(Files.writeString(tmp.resolve("child.yaml"), "agents: [{name: child}]"));
        Agent original = env.getAgents().get("child");
        Path duplicate =
                Files.writeString(
                        tmp.resolve("duplicate.yaml"),
                        "agents: [{name: parent, subagents: [{name: reviewer, agent: child}]}, "
                                + "{name: child, prompts: [{name: replacement, text: bad}]}]\n"
                                + "prompts: [{name: new_prompt, text: 'not committed'}]");
        assertThatThrownBy(() -> env.loadYaml(duplicate))
                .hasMessageContaining("Duplicate agent name 'child'");
        assertThat(env.getAgents()).containsExactlyEntriesOf(Map.of("child", original));
        assertThat(original.getResources().get(ResourceType.PROMPT)).isEmpty();
        assertThat(env.getResources().values())
                .allSatisfy(resources -> assertThat(resources).isEmpty());
    }

    /** Minimal env stub — we can't instantiate LocalExecutionEnvironment from the api module. */
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
