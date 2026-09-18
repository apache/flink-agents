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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ollama4j.models.chat.OllamaChatRequest;
import io.github.ollama4j.tools.Tools;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OllamaChatModelConnection}'s tool-schema conversion and native
 * structured-output behavior — no network access. The structured-output assertions inspect the body
 * built by {@code buildRequest}, and exercise the capability predicate directly.
 */
class OllamaChatModelConnectionTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A tool input schema carrying the {@code properties} object {@code convertToOllamaTools}
     * documents as expected. A schema without one raises there, before the native branch this test
     * is about is ever reached.
     */
    private static final String TOOL_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"}}}";

    /** Output schema fixture with a plain field and a map whose values carry a type. */
    public static class Report {
        public String summary;
        public Map<String, Integer> counts;
    }

    /**
     * Output schema fixture whose enum constants are deserialized from values other than their
     * names, one through {@code @JsonProperty} on the constants and one through a
     * {@code @JsonValue} method.
     */
    public static class Ticket {
        public Status status;

        public Phase phase;
    }

    public enum Status {
        @JsonProperty("in-progress")
        IN_PROGRESS,
        @JsonProperty("done")
        DONE
    }

    public enum Phase {
        STARTED("started"),
        FINISHED("finished");

        private final String wire;

        Phase(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    private static OllamaChatModelConnection connection() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(OllamaChatModelConnection.class.getName())
                        .addInitialArgument("endpoint", "http://localhost:11434")
                        .build();
        return new OllamaChatModelConnection(desc, NOOP);
    }

    /** Minimal tool carrying only metadata; never invoked in these tests. */
    private static final class SchemaOnlyTool extends Tool {
        SchemaOnlyTool(String inputSchema) {
            super(new ToolMetadata("add", "Add two numbers.", inputSchema));
        }

        @Override
        public ToolType getToolType() {
            return ToolType.FUNCTION;
        }

        @Override
        public ToolResponse call(ToolParameters parameters) {
            throw new UnsupportedOperationException("not invoked in this test");
        }
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
    @DisplayName("A schema without a 'required' key converts with every property optional")
    void testSchemaWithoutRequiredKey() {
        // SchemaUtils only emits "required" when at least one parameter is required, so an
        // all-optional @Tool produces exactly this shape (#1014).
        String schema =
                "{\"type\":\"object\",\"properties\":{"
                        + "\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"integer\"}}}";

        List<Tools.Tool> converted =
                connection().convertToOllamaTools(List.of(new SchemaOnlyTool(schema)));

        assertThat(converted).hasSize(1);
        Tools.Tool tool = converted.get(0);
        assertThat(tool.getToolSpec().getParameters().getProperties())
                .containsOnlyKeys("a", "b")
                .allSatisfy((name, property) -> assertThat(property.isRequired()).isFalse());
    }

    @Test
    @DisplayName("A schema with a 'required' key still marks the listed parameters required")
    void testSchemaWithRequiredKey() {
        String schema =
                "{\"type\":\"object\",\"properties\":{"
                        + "\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"integer\"}},"
                        + "\"required\":[\"a\"]}";

        List<Tools.Tool> converted =
                connection().convertToOllamaTools(List.of(new SchemaOnlyTool(schema)));

        assertThat(converted).hasSize(1);
        Tools.Tool tool = converted.get(0);
        assertThat(tool.getToolSpec().getParameters().getProperties().get("a").isRequired())
                .isTrue();
        assertThat(tool.getToolSpec().getParameters().getProperties().get("b").isRequired())
                .isFalse();
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

    /** A connection that records what the feasibility query answered on each request it built. */
    private static OllamaChatModelConnection recordingConnection(
            AtomicReference<Boolean> answered) {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(OllamaChatModelConnection.class.getName())
                        .addInitialArgument("endpoint", "http://localhost:11434")
                        .build();
        return new OllamaChatModelConnection(desc, NOOP) {
            @Override
            protected boolean canApplyNativeStructuredOutput(
                    Object outputSchema, List<Tool> tools, Map<String, Object> modelParams) {
                boolean answer =
                        super.canApplyNativeStructuredOutput(outputSchema, tools, modelParams);
                answered.set(answer);
                return answer;
            }
        };
    }

    @Test
    @DisplayName("The feasibility query answers exactly what the native branch decides")
    void feasibilityQueryAgreesWithTheNativeBranch() {
        // Comparing the answer against what the request ends up carrying, rather than against a
        // literal, is what keeps the query and the branch from drifting in step. This connection
        // reports every model capable, so the schema form is the only thing that moves.
        AtomicReference<Boolean> answered = new AtomicReference<>();
        OllamaChatModelConnection connection = recordingConnection(answered);

        for (Object schema : Arrays.asList(Report.class, "row<name STRING>", null)) {
            // No null-tools case here: convertToOllamaTools iterates the list without a null
            // guard, so this builder rejects null well before the native branch. The query itself
            // accepts null, which feasibilityQueryIgnoresBoundTools pins against the query direct.
            for (List<Tool> tools :
                    List.of(List.<Tool>of(), List.<Tool>of(new SchemaOnlyTool(TOOL_SCHEMA)))) {
                answered.set(null);

                OllamaChatRequest request =
                        connection.buildRequest(userMessage(), tools, params("qwen3:4b"), schema);

                // A null here means the branch never consulted the query at all, which is the
                // drift this test exists to catch. The value assertion below would fail too, but
                // on a null comparison that does not say why.
                assertThat(answered.get()).as("query reached for schema %s", schema).isNotNull();
                assertThat(answered.get())
                        .as("schema %s, tools %s", schema, tools)
                        .isEqualTo(request.getFormat() != null);
            }
        }
    }

    @Test
    @DisplayName("Bound tools do not make a POJO schema infeasible here")
    void feasibilityQueryIgnoresBoundTools() {
        // Ollama's native branch imposes no empty-tools precondition, unlike Gemini's. Pinning the
        // answer keeps an override from acquiring one by being copied from a connection that does.
        assertThat(
                        connection()
                                .canApplyNativeStructuredOutput(
                                        Report.class,
                                        List.of(new SchemaOnlyTool(TOOL_SCHEMA)),
                                        params("qwen3:4b")))
                .isTrue();
        // A null list means no tools, and the query accepts one even though this builder does not.
        assertThat(
                        connection()
                                .canApplyNativeStructuredOutput(
                                        Report.class, null, params("qwen3:4b")))
                .isTrue();
    }

    @Test
    @DisplayName("The feasibility query reads its tools and parameters without consuming them")
    void feasibilityQueryDoesNotConsumeItsInputs() {
        // The same tools and parameters go on to build the request the answer was about, so a
        // query that took anything out of either would answer about one request and build another.
        // Both are immutable, so a consuming implementation raises rather than silently differing.
        List<Tool> tools = List.of(new SchemaOnlyTool(TOOL_SCHEMA));
        Map<String, Object> modelParams = Map.of("model", "qwen3:4b", "think", false);

        connection().canApplyNativeStructuredOutput(Report.class, tools, modelParams);

        assertThat(tools).hasSize(1);
        assertThat(modelParams).isEqualTo(Map.of("model", "qwen3:4b", "think", false));
    }

    @Test
    @DisplayName("The generated schema gives map values their own schema")
    void generatedSchemaGivesMapValuesTheirSchema() {
        OllamaChatRequest request =
                connection()
                        .buildRequest(userMessage(), List.of(), params("qwen3:4b"), Report.class);
        JsonNode schema = (JsonNode) request.getFormat();

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
    }

    @Test
    @DisplayName("The generated schema lists enum constants the way Jackson deserializes them")
    void generatedSchemaFollowsJacksonEnumValues() throws Exception {
        OllamaChatRequest request =
                connection()
                        .buildRequest(userMessage(), List.of(), params("qwen3:4b"), Ticket.class);
        JsonNode properties = ((JsonNode) request.getFormat()).path("properties");

        // Every listed value is one the model may emit, so each has to deserialize into the enum.
        // Listed by constant name instead, the mapper reading the response refuses every value
        // the schema allows.
        List<Status> statuses = new ArrayList<>();
        for (JsonNode value : properties.path("status").path("enum")) {
            statuses.add(MAPPER.treeToValue(value, Status.class));
        }
        assertThat(statuses).containsExactlyInAnyOrder(Status.values());

        List<Phase> phases = new ArrayList<>();
        for (JsonNode value : properties.path("phase").path("enum")) {
            phases.add(MAPPER.treeToValue(value, Phase.class));
        }
        assertThat(phases).containsExactlyInAnyOrder(Phase.values());
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
}
