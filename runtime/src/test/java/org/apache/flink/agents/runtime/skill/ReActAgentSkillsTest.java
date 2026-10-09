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

package org.apache.flink.agents.runtime.skill;

import org.apache.flink.agents.api.agents.ReActAgent;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.skills.Skills;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.ResourceCache;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Skills passed to the {@link ReActAgent} constructor must reach its chat model along the path a
 * job takes: {@link AgentPlan} registers the providers and {@link ResourceCache} builds and opens
 * the setup. Checking the agent's descriptor alone would not show that the setup discovers the
 * skills, exposes {@code load_skill} and {@code bash}, or grants the skill directories to {@code
 * bash}.
 */
class ReActAgentSkillsTest {

    private static final String CHAT_MODEL = "_default_chat_model";
    private static final String CONNECTION = "connection";
    private static final String SKILLS_DIR =
            Path.of("src/test/resources/skills").toAbsolutePath().toString();

    /** Resolved by the setup on open; never asked to chat. */
    public static class StubConnection extends BaseChatModelConnection {
        public StubConnection(ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages, List<Tool> tools, Map<String, Object> modelParams) {
            throw new UnsupportedOperationException("These tests only open the setup.");
        }
    }

    /** Built reflectively by the plan's provider, hence public with the provider constructor. */
    public static class StubChatModelSetup extends BaseChatModelSetup {
        public StubChatModelSetup(ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public Map<String, Object> getParameters() {
            return new HashMap<>();
        }
    }

    @Test
    void constructorSkillsAloneAreLoadableByTheChatModel() throws Exception {
        ReActAgent agent = new ReActAgent(chatModel().build(), null, null, List.of("github"));

        try (ResourceCache cache = cacheFor(agent)) {
            BaseChatModelSetup setup = openChatModel(cache);

            assertThat(setup.getSkills()).containsExactly("github");
            assertThat(setup.getSkillDiscoveryPrompt())
                    .contains("<name>github</name>")
                    .doesNotContain("<name>nano-banana-pro</name>");
            assertThat(setup.getTools())
                    .extracting(Tool::getName)
                    .containsExactly(Skills.LOAD_SKILL_TOOL, Skills.BASH_TOOL);
            Object loaded =
                    toolNamed(setup, Skills.LOAD_SKILL_TOOL)
                            .call(new ToolParameters(Map.of("name", "github")))
                            .getResult();
            assertThat((String) loaded).startsWith("<skill_content name=\"github\">");
        }
    }

    @Test
    void descriptorAndConstructorSkillsAreBothAvailableUnderTheDescriptorCommandPolicy()
            throws Exception {
        ReActAgent agent =
                new ReActAgent(
                        chatModel()
                                .addInitialArgument("skills", List.of("github"))
                                .addInitialArgument("allowed_commands", List.of("gh"))
                                .build(),
                        null,
                        null,
                        List.of("nano-banana-pro", "github"));

        try (ResourceCache cache = cacheFor(agent)) {
            BaseChatModelSetup setup = openChatModel(cache);

            assertThat(setup.getSkills()).containsExactly("github", "nano-banana-pro");
            assertThat(setup.getSkillDiscoveryPrompt())
                    .contains("<name>github</name>", "<name>nano-banana-pro</name>");
            assertThat(setup.getAllowedCommands()).containsExactly("gh");
            // ChatModelAction grants bash these directories as allowed script dirs, so a skill
            // added only through the constructor can still run its own scripts.
            assertThat(setup.getResourceContext().getSkillDirs(setup.getSkills()))
                    .hasSize(2)
                    .anySatisfy(dir -> assertThat(dir).endsWith("nano-banana-pro"));
        }
    }

    @Test
    void emptyConstructorSkillsLeaveTheChatModelWithoutSkills() throws Exception {
        // Skill sources are registered, so load_skill and bash exist in the plan; the chat model
        // must still not pick them up when no skill is named.
        ReActAgent agent = new ReActAgent(chatModel().build(), null, null, List.of());

        try (ResourceCache cache = cacheFor(agent)) {
            BaseChatModelSetup setup = openChatModel(cache);

            assertThat(setup.getSkills()).isNull();
            assertThat(setup.getSkillDiscoveryPrompt()).isNull();
            assertThat(setup.getTools()).isEmpty();
        }
    }

    private static ResourceDescriptor.Builder chatModel() {
        return ResourceDescriptor.Builder.newBuilder(StubChatModelSetup.class.getName())
                .addInitialArgument("connection", CONNECTION);
    }

    private static ResourceCache cacheFor(ReActAgent agent) throws Exception {
        agent.addResource(
                CONNECTION,
                ResourceType.CHAT_MODEL_CONNECTION,
                ResourceDescriptor.Builder.newBuilder(StubConnection.class.getName()).build());
        agent.addResource("test_skills", ResourceType.SKILLS, Skills.fromLocalDir(SKILLS_DIR));
        return new ResourceCache(new AgentPlan(agent).getResourceProviders());
    }

    private static BaseChatModelSetup openChatModel(ResourceCache cache) throws Exception {
        return (BaseChatModelSetup) cache.getResource(CHAT_MODEL, ResourceType.CHAT_MODEL);
    }

    private static Tool toolNamed(BaseChatModelSetup setup, String name) {
        return setup.getTools().stream()
                .filter(tool -> tool.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void skillsObjectConstructorResolvesSourcesToSkillNames() throws Exception {
        Skills skills = Skills.fromLocalDir(SKILLS_DIR);
        ReActAgent agent = new ReActAgent(chatModel().build(), null, null, skills);

        try (ResourceCache cache = cacheForSkillsObjectAgent(agent)) {
            BaseChatModelSetup setup = openChatModel(cache);

            assertThat(setup.getSkills()).containsExactlyInAnyOrder("github", "nano-banana-pro");
            assertThat(setup.getSkillDiscoveryPrompt())
                    .contains("<name>github</name>", "<name>nano-banana-pro</name>");
            assertThat(setup.getTools())
                    .extracting(Tool::getName)
                    .containsExactly(Skills.LOAD_SKILL_TOOL, Skills.BASH_TOOL);
        }
    }

    @Test
    void skillsObjectWithExplicitSkillsMergesBothAtRuntime() throws Exception {
        Skills skills = Skills.fromLocalDir(SKILLS_DIR);
        ResourceDescriptor chatModelDesc =
                chatModel().addInitialArgument("skills", List.of("github")).build();
        ReActAgent agent = new ReActAgent(chatModelDesc, null, null, skills);

        try (ResourceCache cache = cacheForSkillsObjectAgent(agent)) {
            BaseChatModelSetup setup = openChatModel(cache);

            // Explicit skills come first, then resolved from sources. Both "github" and
            // "nano-banana-pro" are present, with "github" deduplicated.
            assertThat(setup.getSkills()).containsExactly("github", "nano-banana-pro");
        }
    }

    @Test
    void nullSkillsObjectProducesNoSkills() throws Exception {
        ReActAgent agent = new ReActAgent(chatModel().build(), null, null, (Skills) null);

        try (ResourceCache cache = cacheForSkillsObjectAgent(agent)) {
            BaseChatModelSetup setup = openChatModel(cache);

            assertThat(setup.getSkills()).isNull();
            assertThat(setup.getSkillDiscoveryPrompt()).isNull();
            assertThat(setup.getTools()).isEmpty();
        }
    }

    private static ResourceCache cacheForSkillsObjectAgent(ReActAgent agent) throws Exception {
        agent.addResource(
                CONNECTION,
                ResourceType.CHAT_MODEL_CONNECTION,
                ResourceDescriptor.Builder.newBuilder(StubConnection.class.getName()).build());
        return new ResourceCache(new AgentPlan(agent).getResourceProviders());
    }
}
