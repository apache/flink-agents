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

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

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
    @DisplayName("withVLLMDefaults preserves explicit values and injects defaults only when absent")
    void testWithVLLMDefaultsPreservesExplicitValues() {
        ResourceDescriptor explicit =
                connectionDescriptor()
                        .addInitialArgument("api_key", "secret-key")
                        .addInitialArgument("api_base_url", "http://vllm-host:8000/v1")
                        .build();
        assertThat(VLLMChatModelConnection.withVLLMDefaults(explicit).getInitialArguments())
                .containsEntry("api_key", "secret-key")
                .containsEntry("api_base_url", "http://vllm-host:8000/v1");

        ResourceDescriptor empty = connectionDescriptor().build();
        assertThat(VLLMChatModelConnection.withVLLMDefaults(empty).getInitialArguments())
                .containsEntry("api_key", VLLMChatModelConnection.DEFAULT_VLLM_API_KEY)
                .containsEntry("api_base_url", VLLMChatModelConnection.DEFAULT_VLLM_API_BASE_URL);
    }

    /** A representative POJO output schema. */
    public static class Person {
        public String name;
        public int age;
    }

    @Test
    @DisplayName("Structured-output capability follows the served model, not OpenAI model names")
    void testSupportsNativeStructuredOutputForServedModels() {
        VLLMChatModelConnection conn =
                new VLLMChatModelConnection(connectionDescriptor().build(), NOOP);
        assertThat(conn.supportsNativeStructuredOutput("Qwen/Qwen2.5-7B-Instruct")).isTrue();
        assertThat(conn.supportsNativeStructuredOutput("meta-llama/Llama-3.1-8B-Instruct"))
                .isTrue();
        assertThat(conn.supportsNativeStructuredOutput(null)).isFalse();
        assertThat(conn.supportsNativeStructuredOutput(" ")).isFalse();
    }

    @Test
    @DisplayName("Native response_format json_schema applied when serving a Qwen model")
    void testNativeResponseFormatAppliedForQwenModel() {
        VLLMChatModelConnection conn =
                new VLLMChatModelConnection(connectionDescriptor().build(), NOOP);
        java.util.Map<String, Object> modelParams = new HashMap<>();
        modelParams.put("model", "Qwen/Qwen2.5-7B-Instruct");

        ChatCompletionCreateParams params =
                conn.buildRequest(
                        List.of(ChatMessage.user("hi")), List.of(), modelParams, Person.class);

        assertThat(params.responseFormat()).isPresent();
    }

    @Test
    @DisplayName("Defaults do not leak into the caller's descriptor")
    void testCallerDescriptorNotMutated() {
        ResourceDescriptor desc = connectionDescriptor().build();
        new VLLMChatModelConnection(desc, NOOP);
        assertThat(desc.getInitialArguments()).doesNotContainKeys("api_key", "api_base_url");
    }
}
