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

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AzureOpenAIChatModelConnection} — constructor validation only, no network
 * access. End-to-end tests against a real Azure OpenAI deployment live in {@link
 * AzureOpenAIChatModelIT}.
 */
class AzureOpenAIChatModelConnectionTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    private static ResourceDescriptor.Builder connectionDescriptor() {
        return ResourceDescriptor.Builder.newBuilder(
                AzureOpenAIChatModelConnection.class.getName());
    }

    @Test
    @DisplayName("Constructor throws when api_key is missing")
    void testConstructorMissingApiKey() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_version", "2024-02-01")
                        .addInitialArgument("azure_endpoint", "https://example.openai.azure.com")
                        .build();
        assertThatThrownBy(() -> new AzureOpenAIChatModelConnection(desc, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api_key");
    }

    @Test
    @DisplayName("Constructor throws when api_version is missing")
    void testConstructorMissingApiVersion() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("azure_endpoint", "https://example.openai.azure.com")
                        .build();
        assertThatThrownBy(() -> new AzureOpenAIChatModelConnection(desc, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api_version");
    }

    @Test
    @DisplayName("Constructor throws when azure_endpoint is missing")
    void testConstructorMissingAzureEndpoint() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("api_version", "2024-02-01")
                        .build();
        assertThatThrownBy(() -> new AzureOpenAIChatModelConnection(desc, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("azure_endpoint");
    }

    @Test
    @DisplayName("Constructor succeeds with all required args (no network call yet)")
    void testConstructorAllRequiredArgs() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("api_version", "2024-02-01")
                        .addInitialArgument("azure_endpoint", "https://example.openai.azure.com")
                        .build();
        AzureOpenAIChatModelConnection conn = new AzureOpenAIChatModelConnection(desc, NOOP);
        assertThat(conn).isInstanceOf(BaseChatModelConnection.class);
    }

    @Test
    @DisplayName("chat() rejects additional_kwargs that collide with reserved typed fields")
    void testChatRejectsReservedKeyInAdditionalKwargs() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("api_version", "2024-02-01")
                        .addInitialArgument("azure_endpoint", "https://example.openai.azure.com")
                        .build();
        AzureOpenAIChatModelConnection conn = new AzureOpenAIChatModelConnection(desc, NOOP);

        Map<String, Object> args =
                Map.of(
                        "model",
                        "my-deployment",
                        "temperature",
                        0.3d,
                        "additional_kwargs",
                        Map.of("temperature", 5.0d));

        assertThatThrownBy(
                        () ->
                                conn.chat(
                                        List.of(new ChatMessage(MessageRole.USER, "hi")),
                                        null,
                                        args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("additional_kwargs")
                .hasMessageContaining("temperature");
    }
}
