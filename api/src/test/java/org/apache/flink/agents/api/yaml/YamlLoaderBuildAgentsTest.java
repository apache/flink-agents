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

import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.function.JavaFunction;
import org.apache.flink.agents.api.function.PythonFunction;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.skills.SkillSourceSpec;
import org.apache.flink.agents.api.skills.Skills;
import org.apache.flink.agents.api.tools.FunctionTool;
import org.apache.flink.agents.api.yaml.YamlLoader.LoadedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlLoaderBuildAgentsTest {

    private static final Path FIXTURES = Paths.get("src/test/resources/yaml/fixtures");

    @Test
    void singleAgent() {
        LoadedFile out = YamlLoader.buildAgents(FIXTURES.resolve("single_agent.yaml"));
        assertThat(out.getAgents()).containsOnlyKeys("incrementer");
        Agent agent = out.getAgents().get("incrementer");

        var actions = agent.getActions();
        var entry = actions.get("increment");
        assertThat(entry.f0).containsExactly(InputEvent.EVENT_TYPE);
        assertThat(entry.f1).isInstanceOf(JavaFunction.class);
        JavaFunction jf = (JavaFunction) entry.f1;
        assertThat(jf.getQualName()).isEqualTo("org.apache.flink.agents.api.yaml.LoaderTargets");
        assertThat(jf.getMethodName()).isEqualTo("increment");
        // Action parameter types default to (Event, RunnerContext) regardless of YAML hint.
        assertThat(jf.getParameterTypes())
                .containsExactly(
                        "org.apache.flink.agents.api.Event",
                        "org.apache.flink.agents.api.context.RunnerContext");
    }

    @Test
    void resolveEventAliasAndClazzAlias() {
        LoadedFile out = YamlLoader.buildAgents(FIXTURES.resolve("with_descriptors.yaml"));
        Agent agent = out.getAgents().get("chat_agent");

        assertThat(agent.getActions().get("increment").f0).containsExactly(InputEvent.EVENT_TYPE);
        assertThat(agent.getActions().get("decrement").f0)
                .containsExactly(ChatResponseEvent.EVENT_TYPE);

        ResourceDescriptor d =
                (ResourceDescriptor)
                        agent.getResources()
                                .get(ResourceType.CHAT_MODEL_CONNECTION)
                                .get("ollama_conn");
        assertThat(d.getClazz()).contains("OllamaChatModelConnection");
        assertThat(d.getInitialArguments())
                .containsEntry("base_url", "http://localhost:11434")
                .containsEntry("request_timeout", 30);
    }

    @Test
    void toolsAndPrompts() {
        LoadedFile out = YamlLoader.buildAgents(FIXTURES.resolve("with_tools_and_prompts.yaml"));
        Agent agent = out.getAgents().get("tool_agent");

        FunctionTool tool =
                (FunctionTool) agent.getResources().get(ResourceType.TOOL).get("notify");
        assertThat(tool.getFunc()).isInstanceOf(JavaFunction.class);

        Prompt p1 = (Prompt) agent.getResources().get(ResourceType.PROMPT).get("text_prompt");
        assertThat(p1.formatString(Map.of("name", "x"))).isEqualTo("hello x");
    }

    @Test
    void sharedSectionsExposedSeparatelyFromAgents() {
        LoadedFile out = YamlLoader.buildAgents(FIXTURES.resolve("with_shared.yaml"));

        assertThat(out.getSharedResources().get(ResourceType.CHAT_MODEL_CONNECTION))
                .containsKey("shared_conn");
        assertThat(out.getSharedActions()).containsKey("shared_inc");

        // buildAgents does not merge shared actions into agents — that's loadYaml's job.
        Agent a1 = out.getAgents().get("a1");
        assertThat(a1.getActions()).doesNotContainKey("shared_inc").containsKey("own_dec");
    }

    @Test
    void duplicateAgentInFile() {
        assertThatThrownBy(() -> YamlLoader.buildAgents(FIXTURES.resolve("dup_agent.yaml")))
                .hasMessageContaining("dup");
    }

    @Test
    void skillsPerAgentAndShared() {
        LoadedFile out = YamlLoader.buildAgents(FIXTURES.resolve("with_skills.yaml"));
        Agent agent = out.getAgents().get("skills_agent");
        Skills own = (Skills) agent.getResources().get(ResourceType.SKILLS).get("agent_skills");
        assertThat(own.getSources())
                .containsExactly(new SkillSourceSpec("local", Map.of("path", "./agent_skill_dir")));

        Skills shared =
                (Skills) out.getSharedResources().get(ResourceType.SKILLS).get("shared_skills");
        assertThat(shared.getSources())
                .containsExactly(
                        new SkillSourceSpec("local", Map.of("path", "./shared_skill_dir")),
                        new SkillSourceSpec("local", Map.of("path", "./more")));
    }

    @Test
    void actionDefaultsToPython(@TempDir Path tmp) throws Exception {
        // Action with no `type:` field defaults to Python (host-neutral default — matches the
        // Python loader so the same YAML behaves identically on either side).
        Path file = tmp.resolve("py_default.yaml");
        Files.writeString(
                file,
                "agents:\n"
                        + "  - name: a\n"
                        + "    actions:\n"
                        + "      - name: act\n"
                        + "        function: pkg.mod:fn\n"
                        + "        trigger_conditions: [input]\n");
        LoadedFile out = YamlLoader.buildAgents(file);
        var entry = out.getAgents().get("a").getActions().get("act");
        assertThat(entry.f1).isInstanceOf(PythonFunction.class);
        PythonFunction pf = (PythonFunction) entry.f1;
        assertThat(pf.getModule()).isEqualTo("pkg.mod");
        assertThat(pf.getQualName()).isEqualTo("fn");
    }

    @Test
    void preservesRawTriggerEntries(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("raw_trigger_conditions.yaml");
        Files.writeString(
                file,
                "agents:\n"
                        + "  - name: a\n"
                        + "    actions:\n"
                        + "      - name: act\n"
                        + "        function: pkg.mod:fn\n"
                        + "        trigger_conditions:\n"
                        + "          - input\n"
                        + "          - input\n"
                        + "          - \"type == '_input_event'\"\n"
                        + "          - ' score > 1 '\n"
                        + "          - 'type =='\n");

        var definition = YamlLoader.buildAgents(file).getAgents().get("a").getActions().get("act");
        assertThat(definition.f0)
                .containsExactly(
                        InputEvent.EVENT_TYPE,
                        InputEvent.EVENT_TYPE,
                        "type == '_input_event'",
                        " score > 1 ",
                        "type ==");
    }

    @Test
    void resolvesOnlyCompleteEventAliases(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("complete_alias_entries.yaml");
        Files.writeString(
                file,
                "agents:\n"
                        + "  - name: a\n"
                        + "    actions:\n"
                        + "      - name: act\n"
                        + "        function: pkg.mod:fn\n"
                        + "        trigger_conditions:\n"
                        + "          - input\n"
                        + "          - \"attributes.kind == 'input'\"\n"
                        + "          - \"'input'\"\n");

        var definition = YamlLoader.buildAgents(file).getAgents().get("a").getActions().get("act");
        assertThat(definition.f0)
                .containsExactly(InputEvent.EVENT_TYPE, "attributes.kind == 'input'", "'input'");
    }

    @Test
    void rejectsMissingOrEmptyConditions(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("deferred_trigger_validation.yaml");
        Files.writeString(
                file,
                "agents:\n"
                        + "  - name: a\n"
                        + "    actions:\n"
                        + "      - name: missing\n"
                        + "        function: pkg.mod:fn\n"
                        + "        type: python\n");

        assertThatThrownBy(() -> YamlLoader.buildAgents(file))
                .rootCause()
                .hasMessageContaining("trigger_conditions");

        Files.writeString(
                file,
                "agents:\n"
                        + "  - name: a\n"
                        + "    actions:\n"
                        + "      - name: empty\n"
                        + "        function: pkg.mod:fn\n"
                        + "        trigger_conditions: []\n");

        assertThatThrownBy(() -> YamlLoader.buildAgents(file))
                .rootCause()
                .hasMessageContaining("trigger_conditions")
                .hasMessageContaining("at least one");
    }

    @Test
    void defersEntryValidationToPlan(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("deferred_trigger_validation.yaml");
        Files.writeString(
                file,
                "agents:\n"
                        + "  - name: a\n"
                        + "    actions:\n"
                        + "      - name: invalid_entries\n"
                        + "        function: pkg.mod:fn\n"
                        + "        trigger_conditions: ['  ', null]\n");

        Agent agent = YamlLoader.buildAgents(file).getAgents().get("a");
        assertThat(agent.getActions().get("invalid_entries").f0).containsExactly("  ", null);
    }

    @Test
    void rejectsNonStringCondition(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("non_string_trigger_condition.yaml");
        Files.writeString(
                file,
                "agents:\n"
                        + "  - name: a\n"
                        + "    actions:\n"
                        + "      - name: invalid\n"
                        + "        function: pkg.mod:fn\n"
                        + "        trigger_conditions: [true]\n");

        assertThatThrownBy(() -> YamlLoader.buildAgents(file))
                .rootCause()
                .hasMessageContaining("trigger_conditions")
                .hasMessageContaining("entry #1")
                .hasMessageContaining("string");
    }

    @Test
    void preservesYamlActionOrder(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("ordered_actions.yaml");
        Files.writeString(
                file,
                "agents:\n"
                        + "  - name: a\n"
                        + "    actions:\n"
                        + "      - name: first\n"
                        + "        function: pkg.mod:fn\n"
                        + "        trigger_conditions: [input]\n"
                        + "      - name: second\n"
                        + "        function: pkg.mod:fn\n"
                        + "        trigger_conditions: [input]\n"
                        + "      - name: third\n"
                        + "        function: pkg.mod:fn\n"
                        + "        trigger_conditions: [input]\n"
                        + "      - name: fourth\n"
                        + "        function: pkg.mod:fn\n"
                        + "        trigger_conditions: [input]\n");

        assertThat(YamlLoader.buildAgents(file).getAgents().get("a").getActions().keySet())
                .containsExactly("first", "second", "third", "fourth");
    }
}
