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

import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link OpenAICompletionsConnection} — constructor validation and default
 * resolution only, no network access.
 */
class OpenAICompletionsConnectionTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    private static ResourceDescriptor.Builder connectionDescriptor() {
        return ResourceDescriptor.Builder.newBuilder(OpenAICompletionsConnection.class.getName());
    }

    @Test
    @DisplayName("Constructor throws when api_key is missing")
    void testConstructorMissingApiKey() {
        ResourceDescriptor desc = connectionDescriptor().build();
        assertThatThrownBy(() -> new OpenAICompletionsConnection(desc, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api_key");
    }

    @Test
    @DisplayName("Constructor succeeds with api_key only (no network call)")
    void testConstructorMinimal() {
        ResourceDescriptor desc =
                connectionDescriptor().addInitialArgument("api_key", "test-key").build();
        OpenAICompletionsConnection conn = new OpenAICompletionsConnection(desc, NOOP);
        assertThat(conn).isInstanceOf(BaseChatModelConnection.class);
    }

    @Test
    @DisplayName("Defaults resolve to timeout=60 and max_retries=3 when not specified")
    void testDefaultTimeoutAndMaxRetries() {
        ResourceDescriptor desc =
                connectionDescriptor().addInitialArgument("api_key", "test-key").build();
        OpenAICompletionsConnection conn = new OpenAICompletionsConnection(desc, NOOP);

        assertThat(conn.getTimeoutSeconds())
                .isEqualTo(OpenAIChatCompletionsUtils.DEFAULT_TIMEOUT_SECONDS);
        assertThat(conn.getMaxRetries())
                .isEqualTo(OpenAIChatCompletionsUtils.DEFAULT_MAX_RETRIES);
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
        OpenAICompletionsConnection conn = new OpenAICompletionsConnection(desc, NOOP);

        assertThat(conn.getTimeoutSeconds()).isEqualTo(120);
        assertThat(conn.getMaxRetries()).isEqualTo(5);
    }
}
