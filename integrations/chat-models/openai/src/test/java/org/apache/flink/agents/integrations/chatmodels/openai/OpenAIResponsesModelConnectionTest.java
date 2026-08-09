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

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link OpenAIResponsesModelConnection} — constructor validation, default
 * resolution and error propagation, none of which need a live API call.
 */
class OpenAIResponsesModelConnectionTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    private static ResourceDescriptor.Builder connectionDescriptor() {
        return ResourceDescriptor.Builder.newBuilder(
                OpenAIResponsesModelConnection.class.getName());
    }

    private static OpenAIResponsesModelConnection connection() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("model", "gpt-4o")
                        .build();
        return new OpenAIResponsesModelConnection(desc, NOOP);
    }

    private static Map<String, Object> params(String model) {
        Map<String, Object> params = new HashMap<>();
        params.put("model", model);
        return params;
    }

    @Test
    @DisplayName("Constructor throws when api_key is missing")
    void testConstructorMissingApiKey() {
        ResourceDescriptor desc = connectionDescriptor().build();
        assertThatThrownBy(() -> new OpenAIResponsesModelConnection(desc, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api_key");
    }

    @Test
    @DisplayName("Constructor succeeds with api_key only (no network call)")
    void testConstructorMinimal() {
        ResourceDescriptor desc =
                connectionDescriptor().addInitialArgument("api_key", "test-key").build();
        OpenAIResponsesModelConnection conn = new OpenAIResponsesModelConnection(desc, NOOP);
        assertThat(conn).isInstanceOf(BaseChatModelConnection.class);
    }

    @Test
    @DisplayName("Defaults resolve to timeout=60 and max_retries=3 when not specified")
    void testDefaultTimeoutAndMaxRetries() {
        ResourceDescriptor desc =
                connectionDescriptor().addInitialArgument("api_key", "test-key").build();
        OpenAIResponsesModelConnection conn = new OpenAIResponsesModelConnection(desc, NOOP);

        assertThat(conn.getTimeout())
                .isEqualTo(Duration.ofSeconds(OpenAIChatCompletionsUtils.DEFAULT_TIMEOUT_SECONDS));
        assertThat(conn.getMaxRetries()).isEqualTo(OpenAIChatCompletionsUtils.DEFAULT_MAX_RETRIES);
    }

    @Test
    @DisplayName("Explicit timeout and max_retries override the defaults")
    void testExplicitOverrides() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("timeout", 120)
                        .addInitialArgument("max_retries", 5)
                        .build();
        OpenAIResponsesModelConnection conn = new OpenAIResponsesModelConnection(desc, NOOP);

        assertThat(conn.getTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(conn.getMaxRetries()).isEqualTo(5);
    }

    @Test
    @DisplayName("Negative timeout throws IllegalArgumentException")
    void testNegativeTimeoutThrows() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("timeout", -5)
                        .build();
        assertThatThrownBy(() -> new OpenAIResponsesModelConnection(desc, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    @DisplayName("Negative max_retries throws IllegalArgumentException")
    void testNegativeMaxRetriesThrows() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("max_retries", -1)
                        .build();
        assertThatThrownBy(() -> new OpenAIResponsesModelConnection(desc, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max_retries");
    }

    @Test
    @DisplayName("Negative fractional timeout throws instead of truncating to zero")
    void testNegativeFractionalTimeoutThrows() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("timeout", -0.5)
                        .build();
        assertThatThrownBy(() -> new OpenAIResponsesModelConnection(desc, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    @DisplayName("Fractional max_retries throws instead of truncating")
    void testFractionalMaxRetriesThrows() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("max_retries", 2.5)
                        .build();
        assertThatThrownBy(() -> new OpenAIResponsesModelConnection(desc, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max_retries");
    }

    @Test
    @DisplayName("Negative fractional max_retries throws instead of truncating to zero")
    void testNegativeFractionalMaxRetriesThrows() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("max_retries", -0.5)
                        .build();
        assertThatThrownBy(() -> new OpenAIResponsesModelConnection(desc, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max_retries");
    }

    @Test
    @DisplayName("max_retries beyond int range throws instead of overflowing")
    void testOverflowMaxRetriesThrows() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("max_retries", 4294967296L)
                        .build();
        assertThatThrownBy(() -> new OpenAIResponsesModelConnection(desc, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max_retries");
    }

    @Test
    @DisplayName("Zero timeout disables the effective SDK timeout")
    void testZeroTimeoutDisablesSdkTimeout() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("timeout", 0)
                        .build();
        OpenAIResponsesModelConnection conn = new OpenAIResponsesModelConnection(desc, NOOP);
        assertThat(conn.getTimeout()).isEqualTo(Duration.ZERO);
        OpenAIClientTestUtils.assertNoTimeoutConfigured(conn);
    }

    @Test
    @DisplayName("Sub-millisecond timeout rounds up to the SDK precision")
    void testSubMillisecondTimeoutRoundsUpToSdkPrecision() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("timeout", 0.0001)
                        .build();
        OpenAIResponsesModelConnection conn = new OpenAIResponsesModelConnection(desc, NOOP);
        assertThat(conn.getTimeout()).isEqualTo(Duration.ofMillis(1));
    }

    @Test
    @DisplayName("Zero max_retries is accepted as valid")
    void testZeroMaxRetriesAccepted() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("max_retries", 0)
                        .build();
        OpenAIResponsesModelConnection conn = new OpenAIResponsesModelConnection(desc, NOOP);
        assertThat(conn.getMaxRetries()).isEqualTo(0);
    }

    @Test
    @DisplayName("A request-building failure reaches the caller as its own type, not a wrapper")
    void testRequestBuildingFailurePropagatesUnwrapped() {
        List<ChatMessage> toolMessageWithoutExternalId =
                List.of(new ChatMessage(MessageRole.TOOL, "result", Map.of()));

        assertThatThrownBy(
                        () ->
                                connection()
                                        .chat(
                                                toolMessageWithoutExternalId,
                                                List.of(),
                                                params("gpt-4o")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("externalId");
    }
}
