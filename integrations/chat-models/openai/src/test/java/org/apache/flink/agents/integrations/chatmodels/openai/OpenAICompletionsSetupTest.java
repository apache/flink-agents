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

package org.apache.flink.agents.integrations.chatmodels.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.TestResourceContexts;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link OpenAICompletionsSetup}. */
class OpenAICompletionsSetupTest {

    private static final ResourceContext NOOP =
            TestResourceContexts.fromGetResource((a, b) -> null);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ResourceDescriptor.Builder base() {
        return ResourceDescriptor.Builder.newBuilder(OpenAICompletionsSetup.class.getName())
                .addInitialArgument("connection", "conn");
    }

    private static ResourceDescriptor.Builder withTool() {
        return base().addInitialArgument("tools", List.of("add"));
    }

    /**
     * A context that resolves every tool name and sub-agent to a stub tool and the connection name
     * to {@code connection}, which may be null when the test never sends a request.
     */
    private static ResourceContext context(BaseChatModelConnection connection) {
        return new ResourceContext() {
            @Override
            public Resource getResource(String name, ResourceType type) {
                if (type == ResourceType.CHAT_MODEL_CONNECTION) {
                    return connection;
                }
                return type == ResourceType.TOOL ? new StubTool(name) : null;
            }

            @Override
            public Tool getSubagentTool(String subagentName) {
                return new StubTool("delegate_to_" + subagentName);
            }

            @Override
            public String generateAvailableSkillsPrompt(List<String> skillNames) {
                return "";
            }

            @Override
            public List<String> getSkillDirs(List<String> skillNames) {
                return List.of();
            }
        };
    }

    private static OpenAICompletionsSetup opened(ResourceDescriptor descriptor) throws Exception {
        OpenAICompletionsSetup setup = new OpenAICompletionsSetup(descriptor, context(null));
        setup.open();
        return setup;
    }

    @Test
    @DisplayName("Default model applies when the model argument is omitted")
    void testDefaultModel() {
        OpenAICompletionsSetup setup = new OpenAICompletionsSetup(base().build(), NOOP);

        assertThat(setup.getParameters()).containsEntry("model", "gpt-6-luna");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("Constructor rejects a blank model instead of defaulting it")
    void testBlankModelRejected(String model) {
        ResourceDescriptor desc = base().addInitialArgument("model", model).build();

        assertThatThrownBy(() -> new OpenAICompletionsSetup(desc, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Chat model setup model must not be blank.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "low", "medium", "high"})
    @DisplayName("Each accepted reasoning_effort, none included, is sent as configured")
    void testAcceptedReasoningEffortSent(String effort) throws Exception {
        OpenAICompletionsSetup setup =
                opened(base().addInitialArgument("reasoning_effort", effort).build());

        assertThat(setup.getParameters()).containsEntry("reasoning_effort", effort);
    }

    @ParameterizedTest
    @ValueSource(strings = {"minimal", "xhigh", "None", ""})
    @DisplayName("Constructor rejects a reasoning_effort outside none, low, medium and high")
    void testUnknownReasoningEffortRejected(String effort) {
        ResourceDescriptor desc = base().addInitialArgument("reasoning_effort", effort).build();

        assertThatThrownBy(() -> new OpenAICompletionsSetup(desc, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reasoning_effort must be one of: none, low, medium, high");
    }

    @Test
    @DisplayName("The default model with a tool gets reasoning_effort none after open()")
    void testDefaultModelWithToolGetsNone() throws Exception {
        assertThat(opened(withTool().build()).getParameters())
                .containsEntry("model", "gpt-6-luna")
                .containsEntry("reasoning_effort", "none");
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-6-luna", "gpt-6-sol"})
    @DisplayName("A model that calls tools only without reasoning gets none when it has a tool")
    void testKnownModelWithToolGetsNone(String model) throws Exception {
        OpenAICompletionsSetup setup =
                opened(withTool().addInitialArgument("model", model).build());

        assertThat(setup.getParameters()).containsEntry("reasoning_effort", "none");
    }

    @Test
    @DisplayName("Tools added for skills and for sub-agents count as tools")
    void testSkillAndSubagentToolsCount() throws Exception {
        OpenAICompletionsSetup withSkill =
                opened(base().addInitialArgument("skills", List.of("my-skill")).build());
        OpenAICompletionsSetup withSubagent =
                opened(base().addInitialArgument("subagents", List.of("child")).build());

        assertThat(withSkill.getTools()).hasSize(1);
        assertThat(withSkill.getParameters()).containsEntry("reasoning_effort", "none");
        assertThat(withSubagent.getTools()).hasSize(1);
        assertThat(withSubagent.getParameters()).containsEntry("reasoning_effort", "none");
    }

    @Test
    @DisplayName("No reasoning_effort is sent for a known model without tools")
    void testNoDefaultWithoutTools() throws Exception {
        for (String model : List.of("gpt-6-luna", "gpt-6-sol")) {
            OpenAICompletionsSetup setup =
                    opened(base().addInitialArgument("model", model).build());

            assertThat(setup.getTools()).as(model).isEmpty();
            assertThat(setup.getParameters()).as(model).doesNotContainKey("reasoning_effort");
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "gpt-6.2-astra",
                "gpt-6.2-sol",
                "gpt-6.1-sol",
                "gpt-6.1-terra",
                "gpt-6-astra",
                "gpt-5.6-luna",
                "gpt-5.5",
                "gpt-4o-mini"
            })
    @DisplayName("No reasoning_effort is sent for other models, even with a tool")
    void testNoDefaultForOtherModels(String model) throws Exception {
        OpenAICompletionsSetup setup =
                opened(withTool().addInitialArgument("model", model).build());

        assertThat(setup.getParameters()).doesNotContainKey("reasoning_effort");
    }

    @ParameterizedTest
    @ValueSource(strings = {"low", "medium", "high"})
    @DisplayName("A configured reasoning_effort is kept on a known model with a tool")
    void testConfiguredReasoningEffortKept(String effort) throws Exception {
        OpenAICompletionsSetup setup =
                opened(withTool().addInitialArgument("reasoning_effort", effort).build());

        assertThat(setup.getParameters()).containsEntry("reasoning_effort", effort);
    }

    @Test
    @DisplayName("A reasoning_effort in additional_kwargs is kept and no default is added")
    void testReasoningEffortInAdditionalKwargsKept() throws Exception {
        OpenAICompletionsSetup setup =
                opened(
                        withTool()
                                .addInitialArgument(
                                        "additional_kwargs", Map.of("reasoning_effort", "high"))
                                .build());

        assertThat(setup.getParameters())
                .doesNotContainKey("reasoning_effort")
                .containsEntry("additional_kwargs", Map.of("reasoning_effort", "high"));
    }

    @Test
    @DisplayName("The constructor sets no default; open() chooses it from the resolved tools")
    void testNoDefaultBeforeOpen() {
        OpenAICompletionsSetup setup = new OpenAICompletionsSetup(withTool().build(), NOOP);

        assertThat(setup.getParameters()).doesNotContainKey("reasoning_effort");
    }

    @Test
    @DisplayName("The reasoning_effort chosen by the setup is the one the request body carries")
    void testReasoningEffortReachesRequestBody() throws Exception {
        assertThat(requestBody(withTool().build()).path("reasoning_effort").asText())
                .isEqualTo("none");
        assertThat(
                        requestBody(
                                        withTool()
                                                .addInitialArgument("reasoning_effort", "medium")
                                                .build())
                                .path("reasoning_effort")
                                .asText())
                .isEqualTo("medium");
        assertThat(
                        requestBody(
                                        withTool()
                                                .addInitialArgument(
                                                        "additional_kwargs",
                                                        Map.of("reasoning_effort", "high"))
                                                .build())
                                .path("reasoning_effort")
                                .asText())
                .isEqualTo("high");
        assertThat(requestBody(base().build()).has("reasoning_effort")).isFalse();
        assertThat(
                        requestBody(withTool().addInitialArgument("model", "gpt-6.2-astra").build())
                                .has("reasoning_effort"))
                .isFalse();
    }

    /**
     * Opens a setup on a connection to a loopback endpoint, sends one chat through the setup and
     * returns the Chat Completions request body the endpoint received.
     */
    private static JsonNode requestBody(ResourceDescriptor setupDescriptor) throws Exception {
        try (FakeOpenAICompletionsEndpoint endpoint =
                FakeOpenAICompletionsEndpoint.servingFinishReason("stop")) {
            OpenAICompletionsConnection connection =
                    new OpenAICompletionsConnection(
                            ResourceDescriptor.Builder.newBuilder(
                                            OpenAICompletionsConnection.class.getName())
                                    .addInitialArgument("api_key", "test-key")
                                    .addInitialArgument("api_base_url", endpoint.baseUrl())
                                    .build(),
                            NOOP);
            try {
                OpenAICompletionsSetup setup =
                        new OpenAICompletionsSetup(setupDescriptor, context(connection));
                setup.open();
                setup.chat(List.of(new ChatMessage(MessageRole.USER, "hi")));
                JsonNode body = MAPPER.readTree(endpoint.lastRequestBody());
                assertThat(body.has("tools")).isEqualTo(!setup.getTools().isEmpty());
                return body;
            } finally {
                connection.close();
            }
        }
    }

    /** Minimal tool stub; only its name and its presence in the tools list matter. */
    private static class StubTool extends Tool {
        StubTool(String name) {
            super(new ToolMetadata(name, "stub", "{\"type\":\"object\"}"));
        }

        @Override
        public ToolType getToolType() {
            return ToolType.FUNCTION;
        }

        @Override
        public ToolResponse call(ToolParameters parameters) {
            return ToolResponse.success(List.of());
        }
    }
}
