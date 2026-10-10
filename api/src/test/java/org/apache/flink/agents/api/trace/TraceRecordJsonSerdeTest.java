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

package org.apache.flink.agents.api.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for the JSON contract of {@link TraceRecord}. */
class TraceRecordJsonSerdeTest {
    private static final String TIMESTAMP = "2026-10-09T01:00:00Z";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void eventPayloadIsSeparateFromItsIdentityAndSourceReferences() throws Exception {
        Event event =
                new Event(
                        "_execution_failed_event",
                        Map.of(
                                "orderId",
                                "order-1",
                                "status",
                                "business-status",
                                "problemCategory",
                                "business-category"));
        TraceContext action =
                TraceContext.forAction(
                        TraceContext.forInputRun("order-1", "agent"), "create_order", "input");
        TraceContext context =
                TraceContext.forEvent(
                        action, event.getType(), event.getId().toString(), "input", "create_order");
        TraceRecord record = new TraceRecord(context, TIMESTAMP, null, null, event.getAttributes());
        String json = mapper.writeValueAsString(record);
        JsonNode root = mapper.readTree(json);

        assertThat(root.get("entityType").asText()).isEqualTo("event");
        assertThat(root.get("entityName").asText()).isEqualTo(event.getType());
        assertThat(root.get("entityMetadata").get("eventId").asText())
                .isEqualTo(event.getId().toString());
        assertThat(root.get("entityMetadata").get("producerExecutionId").asText())
                .isEqualTo(action.getExecutionId());
        assertThat(root.get("attributes").get("status").asText()).isEqualTo("business-status");
        assertThat(root.get("attributes").get("problemCategory").asText())
                .isEqualTo("business-category");
        for (String absent :
                List.of(
                        "event",
                        "context",
                        "eventId",
                        "eventType",
                        "eventAttributes",
                        "executionId",
                        "parentExecutionId",
                        "upstreamEventId",
                        "upstreamActionName",
                        "status",
                        "problemCategory")) {
            assertThat(root.has(absent)).as(absent).isFalse();
        }
        assertRoundTrip(record, json);
    }

    @ParameterizedTest
    @ValueSource(strings = {"started", "failed"})
    void executionLifecycleRecordsUseStatusWithoutSyntheticEventFields(String status)
            throws Exception {
        TraceContext action =
                TraceContext.forAction(
                        TraceContext.forInputRun("key", "agent"), "classify", "input");
        TraceContext context = action.childExecution("llm", "model", Map.of("model", "demo"));
        Map<String, Object> attributes =
                "failed".equals(status)
                        ? Map.of("errorType", "ParseError", "errorMessage", "malformed JSON")
                        : Map.of();
        String problemCategory = "failed".equals(status) ? "model_output_parse_error" : null;
        TraceRecord record =
                new TraceRecord(context, TIMESTAMP, status, problemCategory, attributes);
        String json = mapper.writeValueAsString(record);
        JsonNode root = mapper.readTree(json);

        assertThat(root.get("executionId").asText()).isEqualTo(context.getExecutionId());
        assertThat(root.get("parentExecutionId").asText()).isEqualTo(action.getExecutionId());
        assertThat(root.get("status").asText()).isEqualTo(status);
        assertThat(root.get("timestamp").asText()).isEqualTo(TIMESTAMP);
        assertThat(root.has("eventId")).isFalse();
        assertThat(root.has("eventType")).isFalse();
        assertThat(root.get("attributes").has("status")).isFalse();
        if (problemCategory != null) {
            assertThat(root.get("problemCategory").asText()).isEqualTo(problemCategory);
            assertThat(root.get("attributes").get("errorType").asText()).isEqualTo("ParseError");
        } else {
            assertThat(root.has("problemCategory")).isFalse();
        }
        assertRoundTrip(record, json);
    }

    @Test
    void attributesAreCopiedWithoutMutatingTheOriginalEventPayload() throws Exception {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("items", List.of("one", "two"));
        attributes.put("nullable", null);
        TraceRecord record =
                new TraceRecord(
                        TraceContext.forEvent(null, "CustomEvent", "event", null, null),
                        TIMESTAMP,
                        null,
                        null,
                        attributes);
        attributes.put("added-after-observation", "value");

        assertThat(record.getAttributes()).doesNotContainKey("added-after-observation");
        assertThatThrownBy(() -> record.getAttributes().put("changed", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertRoundTrip(record, mapper.writeValueAsString(record));
    }

    @Test
    void optionalRunFieldsAndEmptyAttributesDoNotGenerateIdentities() throws Exception {
        TraceRecord record =
                TraceRecord.create(
                        TraceContext.forEvent(null, "CustomEvent", "existing-id", null, null),
                        null,
                        null,
                        null);
        JsonNode root = mapper.readTree(mapper.writeValueAsString(record));

        assertThat(Instant.parse(record.getTimestamp())).isNotNull();
        assertThat(root.has("inputRunId")).isFalse();
        assertThat(root.has("executionId")).isFalse();
        assertThat(root.get("entityMetadata").get("eventId").asText()).isEqualTo("existing-id");
        assertThat(root.get("attributes").isObject()).isTrue();
        assertThat(root.get("attributes").isEmpty()).isTrue();
    }

    @Test
    void outputMetadataCanBeReadWithoutChangingTheTraceContext() throws Exception {
        String json =
                "{\"timestamp\":\""
                        + TIMESTAMP
                        + "\",\"entityType\":\"event\","
                        + "\"entityName\":\"InputEvent\",\"entityMetadata\":{\"eventId\":\"input\"},"
                        + "\"attributes\":{},\"detail\":\"STANDARD\",\"jobId\":\"job\","
                        + "\"taskName\":\"task\",\"subtaskId\":1}";
        TraceRecord record = mapper.readValue(json, TraceRecord.class);

        assertThat(record.getContext().getEntityMetadata())
                .containsExactlyEntriesOf(Map.of("eventId", "input"));
        assertThat(record.getAttributes()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidRecordFields")
    void malformedRecordsRejectTheInvalidField(
            String field, Object invalidValue, String expectedMessage) throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("timestamp", TIMESTAMP);
        fields.put("entityType", "event");
        fields.put("entityName", "InputEvent");
        fields.put("entityMetadata", Map.of("eventId", "input"));
        fields.put("attributes", Map.of());
        if (invalidValue == null) {
            fields.remove(field);
        } else {
            fields.put(field, invalidValue);
        }
        String json = mapper.writeValueAsString(fields);

        assertThatThrownBy(() -> mapper.readValue(json, TraceRecord.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class)
                .hasMessageContaining(expectedMessage);
    }

    private static Stream<Arguments> invalidRecordFields() {
        return Stream.of(
                Arguments.of("timestamp", null, "timestamp"),
                Arguments.of("entityMetadata", Map.of(), "entityMetadata.eventId"),
                Arguments.of("status", "success", "status and problemCategory"),
                Arguments.of("attributes", List.of(), "'attributes' must be an object"),
                Arguments.of("timestamp", 17, "'timestamp' must be a string"),
                Arguments.of("attributes", null, "'attributes' must be an object"));
    }

    @Test
    void executionRecordRequiresIdentityForLifecycleCorrelation() {
        TraceContext context =
                TraceContext.fromExistingIds(
                        "run", "key", "agent", null, null, "action", "classify", null);
        assertThatThrownBy(
                        () ->
                                new TraceRecord(
                                        context,
                                        TIMESTAMP,
                                        TraceRecord.Statuses.STARTED,
                                        null,
                                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("executionId");
        String json =
                "{\"timestamp\":\""
                        + TIMESTAMP
                        + "\",\"entityType\":\"action\","
                        + "\"entityName\":\"classify\",\"status\":\"started\",\"attributes\":{}}";
        assertThatThrownBy(() -> mapper.readValue(json, TraceRecord.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class)
                .hasMessageContaining("executionId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"executionId", "parentExecutionId"})
    void eventRecordRejectsExecutionIdentity(String identityField) throws Exception {
        TraceContext context =
                TraceContext.fromExistingIds(
                        "run",
                        "key",
                        "agent",
                        "executionId".equals(identityField) ? "execution" : null,
                        "parentExecutionId".equals(identityField) ? "parent" : null,
                        "event",
                        "OrderCreated",
                        Map.of("eventId", "event-1"));
        assertThatThrownBy(() -> new TraceRecord(context, TIMESTAMP, null, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(identityField);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("timestamp", TIMESTAMP);
        fields.put("entityType", "event");
        fields.put("entityName", "OrderCreated");
        fields.put("entityMetadata", Map.of("eventId", "event-1"));
        fields.put("attributes", Map.of());
        fields.put(identityField, "execution");
        String json = mapper.writeValueAsString(fields);

        assertThatThrownBy(() -> mapper.readValue(json, TraceRecord.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class)
                .hasMessageContaining(identityField);
    }

    @Test
    void runScopeCannotBeWrittenAsAnEntityObservation() {
        assertThatThrownBy(
                        () ->
                                new TraceRecord(
                                        TraceContext.forInputRun("key", "agent"),
                                        TIMESTAMP,
                                        null,
                                        null,
                                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entity type and name");
    }

    private void assertRoundTrip(TraceRecord record, String json) throws Exception {
        TraceRecord restored = mapper.readValue(json, TraceRecord.class);
        assertThat(restored.getContext()).isEqualTo(record.getContext());
        assertThat(restored.getTimestamp()).isEqualTo(record.getTimestamp());
        assertThat(restored.getStatus()).isEqualTo(record.getStatus());
        assertThat(restored.getProblemCategory()).isEqualTo(record.getProblemCategory());
        assertThat(restored.getAttributes()).isEqualTo(record.getAttributes());
    }
}
