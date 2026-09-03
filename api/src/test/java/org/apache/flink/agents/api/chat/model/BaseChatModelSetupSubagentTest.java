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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers how a setup declares its {@code AGENT} resources to a chat model. */
class BaseChatModelSetupSubagentTest {

    private static final String CUSTOM_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Resource> store = new HashMap<>();

    /**
     * AGENT-type overrides, so one name can hold a tool and a sub-agent at the same time; an AGENT
     * lookup falls back to {@link #store} when there is no override.
     */
    private final Map<String, Resource> agentStore = new HashMap<>();

    private final StubConnection connection =
            new StubConnection(new ResourceDescriptor("X", Map.of()), null);

    private final ResourceContext resourceContext =
            new ResourceContext() {
                @Override
                public Resource getResource(String name, ResourceType type) {
                    Resource resource =
                            type == ResourceType.AGENT
                                    ? agentStore.getOrDefault(name, store.get(name))
                                    : store.get(name);
                    if (resource == null) {
                        throw new IllegalArgumentException("No such resource: " + name);
                    }
                    return resource;
                }

                @Override
                public String generateAvailableSkillsPrompt(List<String> skillNames) {
                    return "<available_skills>" + skillNames + "</available_skills>";
                }

                @Override
                public List<String> getSkillDirs(List<String> skillNames) {
                    return List.of();
                }
            };

    private static class StubChatSetup extends BaseChatModelSetup {
        StubChatSetup(ResourceDescriptor descriptor, ResourceContext resourceContext) {
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
                List<ChatMessage> messages, List<Tool> tools, Map<String, Object> modelParams) {
            this.capturedMessages = new ArrayList<>(messages);
            this.capturedTools = new ArrayList<>(tools);
            return new ChatMessage(MessageRole.ASSISTANT, "ok");
        }
    }

    private static class StubTool extends Tool {
        StubTool(String name) {
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

    /** Metadata-carrying sub-agent double: declaring it is all these tests exercise. */
    private static class StubSubagentSetup extends SubagentSetup {

        private static final long serialVersionUID = 1L;

        StubSubagentSetup(String description) {
            super(description);
        }

        StubSubagentSetup(String description, String inputSchema) {
            super(description, inputSchema);
        }

        @Override
        public SubagentFuture submit(RunnerContext ctx, Object prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SubagentFuture submit(RunnerContext ctx, Object prompt, String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SubagentFuture submit(
                RunnerContext ctx, Object prompt, String sessionId, String callId) {
            throw new UnsupportedOperationException();
        }
    }

    /** Types its arguments, so the schema it is declared with is derived rather than written. */
    private static class TypedStubSubagentSetup extends StubSubagentSetup {

        private static final long serialVersionUID = 1L;

        TypedStubSubagentSetup(String description) {
            super(description);
        }

        @Override
        public Class<?> getInputType() {
            return Review.class;
        }
    }

    /** The arguments of {@link TypedStubSubagentSetup}. */
    public static class Review {
        private String path;

        public String getPath() {
            return path;
        }
    }

    private StubChatSetup setupWith(Map<String, Object> extraArgs) {
        store.put("conn", connection);
        Map<String, Object> args = new HashMap<>();
        args.put("connection", "conn");
        args.putAll(extraArgs);
        return new StubChatSetup(new ResourceDescriptor("X", args), resourceContext);
    }

    @Test
    void declaredSubagentsReachTheModelAsCallablesAfterTheTools() throws Exception {
        store.put("lookup", new StubTool("lookup"));
        store.put("reviewer", new StubSubagentSetup("Reviews a file.", CUSTOM_SCHEMA));
        StubChatSetup setup =
                setupWith(Map.of("tools", List.of("lookup"), "subagents", List.of("reviewer")));

        setup.open();
        setup.chat(new ArrayList<>());

        assertThat(connection.capturedTools).hasSize(2);
        assertThat(connection.capturedTools.get(0).getMetadata().getName()).isEqualTo("lookup");
        ToolMetadata delegated = connection.capturedTools.get(1).getMetadata();
        assertThat(delegated.getName()).isEqualTo("subagent_reviewer");
        assertThat(delegated.getDescription()).isEqualTo("Reviews a file. This is subagent.");
        assertThat(delegated.getInputSchema()).isEqualTo(CUSTOM_SCHEMA);
    }

    @Test
    void anUndescribedSubagentIsStillDelegable() throws Exception {
        store.put("reviewer", new StubSubagentSetup("", CUSTOM_SCHEMA));
        StubChatSetup setup = setupWith(Map.of("subagents", List.of("reviewer")));

        setup.open();

        assertThat(setup.getTools().get(0).getMetadata().getName()).isEqualTo("subagent_reviewer");
        assertThat(setup.getTools().get(0).getMetadata().getDescription())
                .isEqualTo("Delegate a standalone task to sub-agent reviewer This is subagent.");
        assertThat(setup.getTools().get(0).getMetadata().getInputSchema()).isEqualTo(CUSTOM_SCHEMA);
    }

    @Test
    void aSubagentThatTypesItsArgumentsIsDeclaredWithTheDerivedSchema() throws Exception {
        store.put("reviewer", new TypedStubSubagentSetup("Reviews a file."));
        StubChatSetup setup = setupWith(Map.of("subagents", List.of("reviewer")));

        setup.open();

        String declared = setup.getTools().get(0).getMetadata().getInputSchema();
        JsonNode schema = MAPPER.readTree(declared);
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").path("path").path("type").asText())
                .isEqualTo("string");
    }

    /**
     * A sub-agent that states no shape for its arguments leaves the model nothing to build a call
     * from, so it is dropped rather than declared as a callable it could only misuse.
     */
    @Test
    void aSubagentThatStatesNoInputShapeIsNotOfferedToTheModel() throws Exception {
        store.put("reviewer", new StubSubagentSetup("Reviews a file."));
        StubChatSetup setup = setupWith(Map.of("subagents", List.of("reviewer")));

        setup.open();

        assertThat(setup.getTools()).isEmpty();
    }

    /** Dropping one is not dropping the rest: the other callables stay usable. */
    @Test
    void aSubagentWithoutAnInputShapeDoesNotStopTheOthersFromBeingDeclared() throws Exception {
        store.put("opaque", new StubSubagentSetup("Reviews a file."));
        store.put("coder", new StubSubagentSetup("Writes a patch.", CUSTOM_SCHEMA));
        StubChatSetup setup = setupWith(Map.of("subagents", List.of("opaque", "coder")));

        setup.open();

        assertThat(setup.getTools()).hasSize(1);
        assertThat(setup.getTools().get(0).getMetadata().getName()).isEqualTo("subagent_coder");
    }

    /** The reserved prefix keeps the two namespaces apart, so one name may serve both. */
    @Test
    void aToolAndASubagentMayShareAName() throws Exception {
        store.put("reviewer", new StubTool("reviewer"));
        agentStore.put("reviewer", new StubSubagentSetup("Reviews a file.", CUSTOM_SCHEMA));
        StubChatSetup setup =
                setupWith(Map.of("tools", List.of("reviewer"), "subagents", List.of("reviewer")));

        setup.open();

        assertThat(setup.getTools()).hasSize(2);
        assertThat(setup.getTools().get(0).getMetadata().getName()).isEqualTo("reviewer");
        assertThat(setup.getTools().get(1).getMetadata().getName()).isEqualTo("subagent_reviewer");
    }

    @Test
    void aRepeatedToolNameIsRejected() {
        store.put("lookup", new StubTool("lookup"));
        StubChatSetup setup = setupWith(Map.of("tools", List.of("lookup", "lookup")));

        assertThatThrownBy(setup::open)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate callable name: lookup");
    }

    @Test
    void aRepeatedSubagentNameIsRejected() {
        store.put("reviewer", new StubSubagentSetup("Reviews a file.", CUSTOM_SCHEMA));
        StubChatSetup setup = setupWith(Map.of("subagents", List.of("reviewer", "reviewer")));

        assertThatThrownBy(setup::open)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate callable name: subagent_reviewer");
    }

    /**
     * A sub-agent owned by the other language resolves to a bridge handle with no schema on it, so
     * declaring it must fail loudly rather than leave the model with a callable it cannot call.
     */
    @Test
    void aSubagentWithoutASetupIsRejected() {
        store.put("reviewer", new StubTool("reviewer"));
        StubChatSetup setup = setupWith(Map.of("subagents", List.of("reviewer")));

        assertThatThrownBy(setup::open)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must resolve to a SubagentSetup");
    }

    @Test
    void reopeningDoesNotDuplicateTheCallables() throws Exception {
        store.put("reviewer", new StubSubagentSetup("Reviews a file.", CUSTOM_SCHEMA));
        StubChatSetup setup = setupWith(Map.of("subagents", List.of("reviewer")));

        setup.open();
        setup.open();

        assertThat(setup.getTools()).hasSize(1);
    }

    /** Delegation is offered through the callables alone: no listing message is injected. */
    @Test
    void declaredSubagentsInjectNoListingMessage() throws Exception {
        store.put("reviewer", new StubSubagentSetup("Reviews a file.", CUSTOM_SCHEMA));
        store.put("coder", new StubSubagentSetup("Writes a patch.", CUSTOM_SCHEMA));
        StubChatSetup setup = setupWith(Map.of("subagents", List.of("reviewer", "coder")));
        setup.open();

        setup.chat(
                new ArrayList<>(
                        List.of(
                                new ChatMessage(MessageRole.SYSTEM, "You are helpful."),
                                new ChatMessage(MessageRole.USER, "review it"))));

        assertThat(connection.capturedMessages).hasSize(2);
        assertThat(connection.capturedMessages.get(0).getContent()).isEqualTo("You are helpful.");
        assertThat(connection.capturedTools).hasSize(2);
    }

    @Test
    void onlyTheSkillListingIsInjectedWhenSubagentsAreDeclared() throws Exception {
        store.put("reviewer", new StubSubagentSetup("Reviews a file.", CUSTOM_SCHEMA));
        store.put("load_skill", new StubTool("load_skill"));
        store.put("bash", new StubTool("bash"));
        StubChatSetup setup =
                setupWith(
                        Map.of(
                                "subagents", List.of("reviewer"),
                                "skills", List.of("github")));
        setup.open();

        setup.chat(new ArrayList<>(List.of(new ChatMessage(MessageRole.USER, "review it"))));

        assertThat(connection.capturedMessages).hasSize(2);
        assertThat(connection.capturedMessages.get(0).getContent())
                .startsWith("<available_skills>");
        assertThat(connection.capturedMessages.get(1).getContent()).isEqualTo("review it");
    }

    @Test
    void aSetupWithoutSubagentsInjectsNothing() throws Exception {
        StubChatSetup setup = setupWith(Map.of());
        setup.open();

        setup.chat(new ArrayList<>(List.of(new ChatMessage(MessageRole.USER, "hi"))));

        assertThat(connection.capturedMessages).hasSize(1);
        assertThat(connection.capturedTools).isEmpty();
    }
}
