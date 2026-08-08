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

package org.apache.flink.agents.integrations.chatmodels.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ollama4j.models.chat.OllamaChatRequest;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OllamaChatModelConnection}'s native structured-output behavior. These
 * assert the built request body without a live call by inspecting {@code buildRequest}, and
 * exercise the capability predicate directly.
 */
class OllamaChatModelConnectionTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";

    /**
     * Output schema fixture shaped to expose the schema-generation settings.
     *
     * <p>Fields are declared out of alphabetical order, {@code counts} is a map whose values carry
     * a type, {@code note} is the only optional field, and {@code getDerived} is a getter backed by
     * no field.
     */
    public static class Report {
        public String summary;
        public Map<String, Integer> counts;
        public Optional<String> note;
        public int total;

        public String getDerived() {
            return summary + total;
        }
    }

    private static OllamaChatModelConnection connection() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(OllamaChatModelConnection.class.getName())
                        .addInitialArgument("endpoint", "http://localhost:11434")
                        .build();
        return new OllamaChatModelConnection(desc, NOOP);
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
    @DisplayName("A POJO output schema is sent as the native format")
    void buildRequestSetsFormatForPojoSchema() {
        OllamaChatRequest request =
                connection()
                        .buildRequest(userMessage(), List.of(), params("qwen3:4b"), Report.class);

        assertThat(request.getFormat()).isInstanceOf(JsonNode.class);
        JsonNode schema = (JsonNode) request.getFormat();
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").has("summary")).isTrue();
    }

    @Test
    @DisplayName("No output schema leaves the request without a format")
    void buildRequestOmitsFormatWithoutSchema() {
        OllamaChatRequest request =
                connection().buildRequest(userMessage(), List.of(), params("qwen3:4b"), null);

        assertThat(request.getFormat()).isNull();
    }

    @Test
    @DisplayName("A RowTypeInfo-shaped schema stays on the prompt fallback")
    void buildRequestLeavesFormatUnsetForRowTypeInfo() {
        // A RowTypeInfo schema arrives wrapped in OutputSchema rather than as a bare POJO Class, so
        // it must not activate native structured output. OutputSchema cannot be instantiated here
        // because RowTypeInfo is not on this module's classpath; any non-Class schema object
        // exercises the same gate.
        Object nonClassSchema = "row<name STRING>";

        OllamaChatRequest request =
                connection()
                        .buildRequest(userMessage(), List.of(), params("qwen3:4b"), nonClassSchema);

        assertThat(request.getFormat()).isNull();
    }

    @Test
    @DisplayName("The generated schema constrains draft, property order, map values and required")
    void generatedSchemaShapeIsConstraining() {
        OllamaChatRequest request =
                connection()
                        .buildRequest(userMessage(), List.of(), params("qwen3:4b"), Report.class);
        JsonNode schema = (JsonNode) request.getFormat();

        // Ollama fixes generation order to the order the schema declares its properties, so the
        // emitted order has to follow the class rather than the alphabet. A getter backed by no
        // field must not surface as a property of its own.
        assertThat(schema.path("$schema").asText()).isEqualTo(DRAFT_2020_12);
        assertThat(schema.path("properties").fieldNames())
                .toIterable()
                .containsExactly("summary", "counts", "note", "total");

        // A map without a value schema admits any value, which the model does take up and which
        // then fails to deserialize into the declared type.
        assertThat(schema.path("properties").path("counts").path("additionalProperties").isObject())
                .isTrue();
        assertThat(
                        schema.path("properties")
                                .path("counts")
                                .path("additionalProperties")
                                .path("type")
                                .asText())
                .isEqualTo("integer");

        // Every field is required except the one the caller declared omissible.
        assertThat(textValues(schema.path("required")))
                .containsExactlyInAnyOrder("summary", "counts", "total");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"qwen3:4b", "llama3.2", "gpt-oss:20b", "some-private-local-model"})
    @DisplayName("Capability is reported for any model, since the server provides it")
    void supportsNativeStructuredOutputIsServerNotModelGated(String model) {
        // Null and empty are included because the capability does not depend on the argument at
        // all, so the guard the sibling connections need for their allowlists would be a silent
        // behavior change here.
        assertThat(connection().supportsNativeStructuredOutput(model)).isTrue();
    }

    private static List<String> textValues(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        arrayNode.forEach(element -> values.add(element.asText()));
        return values;
    }
}
