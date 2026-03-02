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

package org.apache.flink.agents.integrations.chatmodels.bedrock;

import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for {@link BedrockChatModelSetup}. */
class BedrockChatModelSetupTest {

    private static final BiFunction<String, ResourceType, Resource> NOOP = (a, b) -> null;

    @Test
    @DisplayName("getParameters includes model and default temperature")
    void testGetParametersDefaults() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(BedrockChatModelSetup.class.getName())
                        .addInitialArgument("connection", "conn")
                        .addInitialArgument("model", "us.anthropic.claude-sonnet-4-20250514-v1:0")
                        .build();
        BedrockChatModelSetup setup = new BedrockChatModelSetup(desc, NOOP);

        Map<String, Object> params = setup.getParameters();
        assertThat(params).containsEntry("model", "us.anthropic.claude-sonnet-4-20250514-v1:0");
        assertThat(params).containsEntry("temperature", 0.1);
    }

    @Test
    @DisplayName("getParameters uses custom temperature")
    void testGetParametersCustomTemperature() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(BedrockChatModelSetup.class.getName())
                        .addInitialArgument("connection", "conn")
                        .addInitialArgument("model", "test-model")
                        .addInitialArgument("temperature", 0.7)
                        .build();
        BedrockChatModelSetup setup = new BedrockChatModelSetup(desc, NOOP);

        assertThat(setup.getParameters()).containsEntry("temperature", 0.7);
    }

    @Test
    @DisplayName("Extends BaseChatModelSetup")
    void testInheritance() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(BedrockChatModelSetup.class.getName())
                        .addInitialArgument("connection", "conn")
                        .addInitialArgument("model", "m")
                        .build();
        assertThat(new BedrockChatModelSetup(desc, NOOP)).isInstanceOf(BaseChatModelSetup.class);
    }
}
