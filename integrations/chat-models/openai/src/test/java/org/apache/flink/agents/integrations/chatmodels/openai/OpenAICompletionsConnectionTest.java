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
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
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
 * assert the built request body without a live API call by inspecting {@code buildRequest}.
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

    private static Map<String, Object> paramsWithSchema(Object schema) {
        Map<String, Object> params = new HashMap<>();
        params.put("model", "gpt-4o");
        if (schema != null) {
            params.put(BaseChatModelConnection.STRUCTURED_OUTPUT_SCHEMA_KEY, schema);
        }
        return params;
    }

    private static List<ChatMessage> userMessage() {
        return List.of(new ChatMessage(MessageRole.USER, "hi"));
    }

    @Test
    @DisplayName("Native response_format json_schema strict applied for a POJO and no tools")
    void testNativeAppliedForPojoNoTools() {
        ChatCompletionCreateParams params =
                connection().buildRequest(userMessage(), List.of(), paramsWithSchema(Person.class));

        assertThat(params.responseFormat()).isPresent();
        ResponseFormatJsonSchema jsonSchema = params.responseFormat().get().asJsonSchema();
        assertThat(jsonSchema.jsonSchema().strict()).contains(true);
    }

    /** Minimal tool stub; only its presence in the list matters for the empty-tools gate. */
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

    @Test
    @DisplayName("Native NOT applied when tools are bound (empty-tools gate)")
    void testNativeNotAppliedWithTools() {
        ChatCompletionCreateParams params =
                connection()
                        .buildRequest(
                                userMessage(),
                                List.of(new StubTool()),
                                paramsWithSchema(Person.class));

        assertThat(params.responseFormat()).isEmpty();
    }

    @Test
    @DisplayName("Native NOT applied for a non-POJO schema form (BaseModel/POJO-only scope)")
    void testNativeNotAppliedForNonClassSchema() {
        // A RowTypeInfo schema arrives wrapped (not a bare POJO Class), so it must not activate
        // native structured output; any non-Class schema object exercises the same gate.
        Object nonClassSchema = "row<name STRING>";

        ChatCompletionCreateParams params =
                connection()
                        .buildRequest(userMessage(), List.of(), paramsWithSchema(nonClassSchema));

        assertThat(params.responseFormat()).isEmpty();
    }

    @Test
    @DisplayName(
            "Reserved schema key is consumed as response_format, not passed through as a body property")
    void testReservedKeyConsumedNotLeaked() {
        // The reserved key is consumed by the native path into response_format rather than left
        // in the modelParams to leak. The pop-helper's remove-and-return contract (which makes
        // this possible) is exercised directly in BaseChatModelTest; this case pins that for the
        // OpenAI connection the reserved key drives response_format and is absent from the body.
        ChatCompletionCreateParams params =
                connection().buildRequest(userMessage(), List.of(), paramsWithSchema(Person.class));

        assertThat(params.responseFormat()).isPresent();
        assertThat(params._additionalBodyProperties())
                .doesNotContainKey(BaseChatModelConnection.STRUCTURED_OUTPUT_SCHEMA_KEY);
    }

    @Test
    @DisplayName("OpenAI completions declares native structured-output support")
    void testDeclaresNativeCapability() {
        assertThat(connection().supportsNativeStructuredOutput()).isTrue();
    }
}
