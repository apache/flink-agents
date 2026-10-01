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
import io.github.ollama4j.utils.Utils;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.ReasoningBlock;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OllamaChatModelConnection}'s tool-schema conversion and native
 * structured-output behavior — no network access. The structured-output assertions inspect the body
 * built by {@code buildRequest}, and exercise the capability predicate directly.
 */
class OllamaChatModelConnectionTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        // A schema may omit "required" when every parameter is optional (#1014).
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
    @DisplayName("Assistant tool calls in the history are sent back to Ollama")
    void buildRequestForwardsAssistantToolCalls() {
        Map<String, Object> call =
                Map.of(
                        "id",
                        "fa-call-1",
                        "type",
                        "function",
                        "function",
                        Map.of("name", "get_weather", "arguments", Map.of("city", "Paris")));
        Map<String, Object> jsonArgumentsCall =
                Map.of("function", Map.of("name", "get_time", "arguments", "{\"zone\":\"CET\"}"));
        List<ChatMessage> history =
                List.of(
                        new ChatMessage(MessageRole.USER, "Weather and time in Paris?"),
                        ChatMessage.assistant(
                                List.of(
                                        new ToolCallBlock(
                                                "call-1", "get_weather", Map.of("city", "Paris")),
                                        new ToolCallBlock(
                                                "call-2", "get_time", Map.of("zone", "CET")))),
                        ChatMessage.tool(ToolResponse.success("sunny").toResultBlock("call-1")),
                        ChatMessage.tool(ToolResponse.success("10:00").toResultBlock("call-2")));

        OllamaChatRequest request =
                connection().buildRequest(history, List.of(), params("qwen3:4b"), null);
        JsonNode wire = Utils.getObjectMapper().valueToTree(request);

        JsonNode toolCalls = wire.at("/messages/1/tool_calls");
        assertThat(toolCalls).hasSize(2);
        assertThat(toolCalls.at("/0/function/name").asText()).isEqualTo("get_weather");
        assertThat(toolCalls.at("/0/function/arguments/city").asText()).isEqualTo("Paris");
        assertThat(toolCalls.at("/1/function/arguments/zone").asText()).isEqualTo("CET");
        assertThat(
                        wire.at("/messages/0/tool_calls").isMissingNode()
                                || wire.at("/messages/0/tool_calls").isNull())
                .isTrue();
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

    @Test
    void parsesEnvelopeAndPreservesProviderCallId() throws Exception {
        io.github.ollama4j.models.chat.OllamaChatResponseModel wire =
                MAPPER.readValue(
                        "{\"message\":{\"role\":\"assistant\",\"content\":\"answer\",\"thinking\":\"private\",\"tool_calls\":[{\"id\":\"provider-id\",\"function\":{\"name\":\"add\",\"arguments\":{\"x\":1}}}]},\"done_reason\":\"stop\",\"prompt_eval_count\":0}",
                        io.github.ollama4j.models.chat.OllamaChatResponseModel.class);
        org.apache.flink.agents.api.chat.messages.ChatResult response =
                connection().convertResponse(wire, "local", true);
        assertThat(response.getText()).isEqualTo("answer");
        assertThat(response.getToolCalls().get(0).getCallId()).isEqualTo("provider-id");
        assertThat(response.getUsage().getPromptTokens()).isZero();
        assertThat(response.getUsage().getCompletionTokens()).isNull();
        assertThat(response.getFinishReason()).isEqualTo("stop");
        assertThat(response.getMetadata()).isEmpty();
        ReasoningBlock reasoning = (ReasoningBlock) response.getMessage().getBlocks().get(0);
        reasoning.getMetadata().put("custom", "retained in history");
        JsonNode replay =
                MAPPER.valueToTree(
                        connection()
                                .buildRequest(
                                        List.of(response.getMessage()),
                                        List.of(),
                                        params("local"),
                                        null));
        assertThat(replay.at("/messages/0/content").asText()).isEqualTo("answer");
        assertThat(replay.at("/messages/0/thinking").asText()).isNotEqualTo("private");
        wire.setDoneReason("length");
        assertThat(connection().convertResponse(wire, "local", true).getFinishReason())
                .isEqualTo("length");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"stop", "tool_calls", "length", "content_filter", "some_vendor_reason"})
    void preservesFinishReasonRegardlessOfToolCalls(String reason) throws Exception {
        for (String toolCalls :
                List.of(
                        "",
                        ",\"tool_calls\":[{\"function\":{\"name\":\"add\",\"arguments\":{\"x\":1}}}]")) {
            io.github.ollama4j.models.chat.OllamaChatResponseModel wire =
                    MAPPER.readValue(
                            "{\"message\":{\"role\":\"assistant\",\"content\":\"answer\""
                                    + toolCalls
                                    + "}}",
                            io.github.ollama4j.models.chat.OllamaChatResponseModel.class);
            wire.setDoneReason(reason);
            org.apache.flink.agents.api.chat.messages.ChatResult response =
                    connection().convertResponse(wire, "local", true);
            assertThat(response.getFinishReason()).isEqualTo(reason);
            assertThat(response.getMetadata()).isEmpty();
            assertThat(response.getToolCalls()).hasSize(toolCalls.isEmpty() ? 0 : 1);
        }
    }
}
