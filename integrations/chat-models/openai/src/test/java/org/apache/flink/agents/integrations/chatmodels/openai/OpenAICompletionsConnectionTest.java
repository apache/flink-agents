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

import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OpenAICompletionsConnection}'s native structured-output behavior. These
 * assert the built request body without a live API call by inspecting {@code buildRequest}, and
 * exercise the model-dependent capability predicate directly.
 */
class OpenAICompletionsConnectionTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    /** A representative POJO output schema. */
    public static class Person {
        public String name;
        public int age;
    }

    private static OpenAICompletionsConnection connection() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(OpenAICompletionsConnection.class.getName())
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("model", "gpt-4o")
                        .build();
        return new OpenAICompletionsConnection(desc, NOOP);
    }

    private static Map<String, Object> params(String model) {
        Map<String, Object> params = new HashMap<>();
        params.put("model", model);
        return params;
    }

    private static List<ChatMessage> userMessage() {
        return List.of(new ChatMessage(MessageRole.USER, "hi"));
    }

    @Test
    @DisplayName("Native response_format json_schema strict applied for a POJO on a capable model")
    void testNativeAppliedForPojoCapableModel() {
        ChatCompletionCreateParams params =
                connection().buildRequest(userMessage(), List.of(), params("gpt-4o"), Person.class);

        assertThat(params.responseFormat()).isPresent();
        ResponseFormatJsonSchema jsonSchema = params.responseFormat().get().asJsonSchema();
        assertThat(jsonSchema.jsonSchema().strict()).contains(true);
    }

    @Test
    @DisplayName("Native NOT applied for a POJO on an incapable model (prompt fallback)")
    void testNativeNotAppliedForIncapableModel() {
        ChatCompletionCreateParams params =
                connection()
                        .buildRequest(
                                userMessage(), List.of(), params("gpt-3.5-turbo"), Person.class);

        assertThat(params.responseFormat()).isEmpty();
    }

    @Test
    @DisplayName("Native NOT applied for a pre-cutoff same-family gpt-4o snapshot")
    void testNativeNotAppliedForPreCutoffSnapshot() {
        // gpt-4o-2024-05-13 predates the Structured Outputs cutoff even though it shares the gpt-4o
        // prefix; treating it as capable would fail silently at the provider.
        ChatCompletionCreateParams params =
                connection()
                        .buildRequest(
                                userMessage(),
                                List.of(),
                                params("gpt-4o-2024-05-13"),
                                Person.class);

        assertThat(params.responseFormat()).isEmpty();
    }

    @Test
    @DisplayName("Native NOT applied when no output schema is supplied")
    void testNativeNotAppliedWhenSchemaNull() {
        ChatCompletionCreateParams params =
                connection().buildRequest(userMessage(), List.of(), params("gpt-4o"), null);

        assertThat(params.responseFormat()).isEmpty();
    }

    @Test
    @DisplayName("Native NOT applied for a non-POJO schema form (POJO-only scope)")
    void testNativeNotAppliedForNonPojoSchema() {
        // A RowTypeInfo schema arrives wrapped in OutputSchema (not a bare POJO Class), so it must
        // not activate native structured output; any non-Class schema object exercises the same
        // instanceof gate.
        Object nonClassSchema = "row<name STRING>";

        ChatCompletionCreateParams params =
                connection()
                        .buildRequest(userMessage(), List.of(), params("gpt-4o"), nonClassSchema);

        assertThat(params.responseFormat()).isEmpty();
    }

    @Test
    @DisplayName("Native applied for a POJO even when tools are bound (no empty-tools gate)")
    void testNativeAppliedEvenWhenToolsBound() {
        ChatCompletionCreateParams params =
                connection()
                        .buildRequest(
                                userMessage(),
                                List.of(new StubTool()),
                                params("gpt-4o"),
                                Person.class);

        assertThat(params.responseFormat()).isPresent();
    }

    @Test
    @DisplayName("Capability predicate accepts the documented capable models")
    void testCapabilityPredicateAcceptsCapableModels() {
        OpenAICompletionsConnection connection = connection();

        assertThat(connection.supportsNativeStructuredOutput("gpt-4o")).isTrue();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4o-2024-08-06")).isTrue();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4o-2024-11-20")).isTrue();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4o-mini")).isTrue();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4o-mini-2024-07-18")).isTrue();
    }

    @Test
    @DisplayName("Capability predicate rejects incapable, pre-cutoff, unknown, and null models")
    void testCapabilityPredicateRejectsIncapableModels() {
        OpenAICompletionsConnection connection = connection();

        assertThat(connection.supportsNativeStructuredOutput("gpt-3.5-turbo")).isFalse();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4-turbo")).isFalse();
        assertThat(connection.supportsNativeStructuredOutput("gpt-4o-2024-05-13")).isFalse();
        assertThat(connection.supportsNativeStructuredOutput("some-unknown-model")).isFalse();
        assertThat(connection.supportsNativeStructuredOutput(null)).isFalse();
    }

    /** Minimal tool stub; only its presence in the tools list matters. */
    private static class StubTool extends Tool {
        StubTool() {
            super(new ToolMetadata("add", "adds", "{\"type\":\"object\"}"));
        }

        @Override
        public ToolType getToolType() {
            return ToolType.FUNCTION;
        }

        @Override
        public ToolResponse call(ToolParameters parameters) {
            return ToolResponse.success(null);
        }
    }
}
