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

package org.apache.flink.agents.runtime.operator;

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.agents.ReActAgent;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.skills.Skills;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Offline MiniCluster coverage of delegation, child skills, and single-worker execution. */
@Timeout(60)
class ReActSubagentMiniClusterTest {
    @TempDir Path temporaryDirectory;

    public static class SkillConnection extends BaseChatModelConnection {
        public SkillConnection(ResourceDescriptor descriptor, ResourceContext context) {
            super(descriptor, context);
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages, List<Tool> tools, Map<String, Object> params) {
            String model = (String) params.get("model");
            ChatMessage last = messages.get(messages.size() - 1);
            if (model.equals("child")) {
                assertThat(tools).extracting(Tool::getName).containsExactly("load_skill", "bash");
                assertThat(messages.toString()).doesNotContain("Parent private instructions");
                if (last.getRole() == MessageRole.TOOL) {
                    assertThat(last.getText()).contains("child-only-evidence");
                    return new ChatMessage(MessageRole.ASSISTANT, "child answer");
                }
                assertThat(messages)
                        .anySatisfy(
                                message ->
                                        assertThat(message.getText())
                                                .contains("Child research instructions"));
                return request("load_skill", Map.of("name", "research"));
            }
            if (last.getRole() == MessageRole.TOOL) {
                assertThat(last.getText()).contains("child answer");
                return new ChatMessage(MessageRole.ASSISTANT, "parent answer");
            }
            assertThat(tools).extracting(Tool::getName).containsExactly("_subagent_researcher");
            return request("_subagent_researcher", Map.of("prompt", "investigate"));
        }
    }

    private static ChatMessage request(String name, Map<String, Object> arguments) {
        return ChatMessage.assistant(
                "",
                List.of(
                        Map.of(
                                "id",
                                "call",
                                "type",
                                "function",
                                "function",
                                Map.of("name", name, "arguments", arguments))));
    }

    private static ResourceDescriptor model(String name) {
        ResourceDescriptor.Builder builder =
                ResourceDescriptor.Builder.newBuilder(
                                ReActSubagentTest.ScriptedSetup.class.getName())
                        .addInitialArgument("connection", "connection")
                        .addInitialArgument("model", name);
        if (name.equals("child")) {
            builder.addInitialArgument("skills", List.of("research"));
        } else {
            builder.addInitialArgument("subagents", List.of("researcher"));
        }
        return builder.build();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void delegationLoadsChildSkillsWithOneWorker(boolean modelDriven) throws Exception {
        assumeTrue(Runtime.version().feature() >= 21, "Java internal calls require continuations");
        Path childSkills = Files.createDirectories(temporaryDirectory.resolve("child/research"));
        Files.writeString(
                childSkills.resolve("SKILL.md"),
                "---\nname: research\ndescription: Child research instructions\n---\nchild-only-evidence\n");
        Path parentSkills = Files.createDirectories(temporaryDirectory.resolve("parent/research"));
        Files.writeString(
                parentSkills.resolve("SKILL.md"),
                "---\nname: research\ndescription: Parent private instructions\n---\nparent-only-evidence\n");
        Agent child = ReActAgent.forSubagent(model("child"), "Research a task");
        child.addResource(
                "skills",
                ResourceType.SKILLS,
                Skills.fromLocalDir(childSkills.getParent().toString()));
        Agent parent = modelDriven ? new ReActAgent(model("parent"), null, null) : new Agent();
        if (!modelDriven) {
            parent.addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    ReActSubagentTest.class.getMethod("invoke", Event.class, RunnerContext.class));
        }
        parent.addResource("researcher", ResourceType.AGENT, child);
        parent.addResource(
                "skills",
                ResourceType.SKILLS,
                Skills.fromLocalDir(parentSkills.getParent().toString()));
        parent.addResource(
                "connection",
                ResourceType.CHAT_MODEL_CONNECTION,
                new ResourceDescriptor(SkillConnection.class.getName(), Map.of()));

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        AgentsExecutionEnvironment agents = AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agents.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 1);
        List<String> output = new ArrayList<>();
        try (CloseableIterator<String> results =
                agents.fromDataStream(env.fromData(1L), value -> value)
                        .apply(parent)
                        .toDataStream()
                        .map(Object::toString)
                        .executeAndCollect()) {
            results.forEachRemaining(output::add);
        }
        assertThat(output).containsExactly(modelDriven ? "parent answer" : "[child answer]");
    }
}
