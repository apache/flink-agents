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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link VLLMChatModelConnection} — constructor/default handling only, no network
 * access.
 */
class VLLMChatModelConnectionTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    private static ResourceDescriptor.Builder connectionDescriptor() {
        return ResourceDescriptor.Builder.newBuilder(VLLMChatModelConnection.class.getName());
    }

    @Test
    @DisplayName("Constructor succeeds with no arguments: api_key and api_base_url are defaulted")
    void testConstructorNoArguments() {
        // The parent OpenAI connection throws when api_key is missing, so a successful
        // construction here proves the vLLM defaults were injected.
        ResourceDescriptor desc = connectionDescriptor().build();
        VLLMChatModelConnection conn = new VLLMChatModelConnection(desc, NOOP);
        assertThat(conn).isInstanceOf(OpenAICompletionsConnection.class);
    }

    @Test
    @DisplayName("Constructor defaults blank api_key and api_base_url")
    void testConstructorBlankArgumentsAreDefaulted() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "")
                        .addInitialArgument("api_base_url", " ")
                        .build();
        assertThatCode(() -> new VLLMChatModelConnection(desc, NOOP)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Constructor honors explicit api_key and api_base_url")
    void testConstructorExplicitArguments() {
        ResourceDescriptor desc =
                connectionDescriptor()
                        .addInitialArgument("api_key", "secret-key")
                        .addInitialArgument("api_base_url", "http://vllm-host:8000/v1")
                        .addInitialArgument("timeout", 30)
                        .addInitialArgument("max_retries", 1)
                        .build();
        assertThatCode(() -> new VLLMChatModelConnection(desc, NOOP)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Defaults do not leak into the caller's descriptor")
    void testCallerDescriptorNotMutated() {
        ResourceDescriptor desc = connectionDescriptor().build();
        new VLLMChatModelConnection(desc, NOOP);
        assertThat(desc.getInitialArguments()).doesNotContainKeys("api_key", "api_base_url");
    }
}
