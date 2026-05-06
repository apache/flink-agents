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

package org.apache.flink.agents.api.chat.model;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.skills.Skills;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseChatModelSetupSkillsTest {

    /** Stub chat model setup that exposes a configurable parameters map. */
    private static class StubChatSetup extends BaseChatModelSetup {
        public StubChatSetup(ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public Map<String, Object> getParameters() {
            return new HashMap<>();
        }
    }

    private static class StubConnection extends BaseChatModelConnection {
        List<ChatMessage> capturedMessages;
        List<Tool> capturedTools;

        StubConnection(ResourceDescriptor d, ResourceContext c) {
            super(d, c);
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages, List<Tool> tools, Map<String, Object> arguments) {
            this.capturedMessages = new ArrayList<>(messages);
            this.capturedTools = new ArrayList<>(tools);
            return new ChatMessage(MessageRole.ASSISTANT, "ok");
        }
    }

    private static class StubTool extends Tool {
        public StubTool(String name) {
            super(new ToolMetadata(name, "stub", "{}"));
        }

        @Override
        public ToolType getToolType() {
            return ToolType.FUNCTION;
        }

        @Override
        public ToolResponse call(ToolParameters parameters) {
            return ToolResponse.success("");
        }
    }

    @Test
    void openInjectsSkillToolsAndDiscoveryPrompt() throws Exception {
        Map<String, Resource> store = new HashMap<>();
        StubConnection connection = new StubConnection(new ResourceDescriptor("X", Map.of()), null);
        Tool loadSkillTool = new StubTool(Skills.LOAD_SKILL_TOOL);
        Tool bashTool = new StubTool(Skills.BASH_TOOL);
        store.put("conn", connection);
        store.put(Skills.LOAD_SKILL_TOOL, loadSkillTool);
        store.put(Skills.BASH_TOOL, bashTool);
        ResourceContext ctx =
                new ResourceContext() {
                    @Override
                    public Resource getResource(String name, ResourceType type) {
                        return store.get(name);
                    }

                    @Override
                    public String generateAvailableSkillsPrompt(List<String> skillNames) {
                        return "<available_skills>\n<skill>\n<name>"
                                + skillNames.get(0)
                                + "</name>\n</skill>\n</available_skills>";
                    }

                    @Override
                    public List<String> getSkillDirs(List<String> skillNames) {
                        return List.of();
                    }
                };

        Map<String, Object> args = new HashMap<>();
        args.put("connection", "conn");
        args.put("skills", Arrays.asList("github"));
        ResourceDescriptor descriptor = new ResourceDescriptor("X", args);

        StubChatSetup setup = new StubChatSetup(descriptor, ctx);
        setup.open();

        assertNotNull(setup.getSkillDiscoveryPrompt());
        assertTrue(setup.getSkillDiscoveryPrompt().contains("<available_skills>"));
        assertTrue(setup.getToolNames().contains(Skills.LOAD_SKILL_TOOL));
        assertTrue(setup.getToolNames().contains(Skills.BASH_TOOL));
    }

    @Test
    void chatInjectsSkillPromptAfterFirstSystemMessage() throws Exception {
        Map<String, Resource> store = new HashMap<>();
        StubConnection connection = new StubConnection(new ResourceDescriptor("X", Map.of()), null);
        store.put("conn", connection);
        store.put(Skills.LOAD_SKILL_TOOL, new StubTool(Skills.LOAD_SKILL_TOOL));
        store.put(Skills.BASH_TOOL, new StubTool(Skills.BASH_TOOL));
        ResourceContext ctx =
                new ResourceContext() {
                    @Override
                    public Resource getResource(String name, ResourceType type) {
                        return store.get(name);
                    }

                    @Override
                    public String generateAvailableSkillsPrompt(List<String> skillNames) {
                        return "<available_skills>marker</available_skills>";
                    }

                    @Override
                    public List<String> getSkillDirs(List<String> skillNames) {
                        return List.of();
                    }
                };
        Map<String, Object> args = new HashMap<>();
        args.put("connection", "conn");
        args.put("skills", Arrays.asList("github"));
        StubChatSetup setup = new StubChatSetup(new ResourceDescriptor("X", args), ctx);
        setup.open();

        List<ChatMessage> input =
                Arrays.asList(
                        new ChatMessage(MessageRole.SYSTEM, "you are an agent"),
                        new ChatMessage(MessageRole.USER, "hi"));
        setup.chat(input);

        // Expected: SYSTEM, SYSTEM(skill_prompt), USER
        assertEquals(3, connection.capturedMessages.size());
        assertEquals(MessageRole.SYSTEM, connection.capturedMessages.get(0).getRole());
        assertEquals("you are an agent", connection.capturedMessages.get(0).getContent());
        assertEquals(MessageRole.SYSTEM, connection.capturedMessages.get(1).getRole());
        assertTrue(connection.capturedMessages.get(1).getContent().contains("<available_skills>"));
        assertEquals(MessageRole.USER, connection.capturedMessages.get(2).getRole());
    }
}
