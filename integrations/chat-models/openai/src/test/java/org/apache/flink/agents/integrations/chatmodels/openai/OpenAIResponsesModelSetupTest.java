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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link OpenAIResponsesModelSetup}. */
class OpenAIResponsesModelSetupTest {

    private static final ResourceContext NOOP =
            TestResourceContexts.fromGetResource((a, b) -> null);

    /** Resolves every tool name to a stub tool; the connection stays unresolved. */
    private static final ResourceContext TOOLS =
            TestResourceContexts.fromGetResource(
                    (name, type) -> type == ResourceType.TOOL ? new StubTool(name) : null);

    private static ResourceDescriptor.Builder base() {
        return ResourceDescriptor.Builder.newBuilder(OpenAIResponsesModelSetup.class.getName())
                .addInitialArgument("connection", "conn");
    }

    @Test
    @DisplayName("Default model applies when the model argument is omitted")
    void testDefaultModel() {
        OpenAIResponsesModelSetup setup = new OpenAIResponsesModelSetup(base().build(), NOOP);

        assertThat(setup.getParameters()).containsEntry("model", "gpt-6-luna");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("Constructor rejects a blank model instead of defaulting it")
    void testBlankModelRejected(String model) {
        ResourceDescriptor desc = base().addInitialArgument("model", model).build();

        assertThatThrownBy(() -> new OpenAIResponsesModelSetup(desc, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Chat model setup model must not be blank.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "low", "medium", "high"})
    @DisplayName("Each accepted reasoning_effort, none included, is sent as configured")
    void testAcceptedReasoningEffortSent(String effort) {
        OpenAIResponsesModelSetup setup =
                new OpenAIResponsesModelSetup(
                        base().addInitialArgument("reasoning_effort", effort).build(), NOOP);

        assertThat(setup.getParameters()).containsEntry("reasoning_effort", effort);
    }

    @ParameterizedTest
    @ValueSource(strings = {"minimal", "xhigh", "None", ""})
    @DisplayName("Constructor rejects a reasoning_effort outside none, low, medium and high")
    void testUnknownReasoningEffortRejected(String effort) {
        ResourceDescriptor desc = base().addInitialArgument("reasoning_effort", effort).build();

        assertThatThrownBy(() -> new OpenAIResponsesModelSetup(desc, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reasoning_effort must be one of: none, low, medium, high");
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-6-luna", "gpt-6-sol"})
    @DisplayName("reasoning_effort stays unset after open(), even with a tool on a known model")
    void testNoDefaultReasoningEffortWithTools(String model) throws Exception {
        OpenAIResponsesModelSetup setup =
                new OpenAIResponsesModelSetup(
                        base().addInitialArgument("model", model)
                                .addInitialArgument("tools", List.of("add"))
                                .build(),
                        TOOLS);
        setup.open();

        assertThat(setup.getTools()).hasSize(1);
        assertThat(setup.getParameters()).doesNotContainKey("reasoning_effort");
    }

    /** Minimal tool stub; only its presence in the tools list matters. */
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
