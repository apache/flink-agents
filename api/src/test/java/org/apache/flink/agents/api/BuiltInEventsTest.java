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

package org.apache.flink.agents.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.agents.ReActLoopBudget;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.decision.DecisionRequest;
import org.apache.flink.agents.api.decision.DecisionResult;
import org.apache.flink.agents.api.event.AgentRunBeginEvent;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ContextRetrievalRequestEvent;
import org.apache.flink.agents.api.event.ContextRetrievalResponseEvent;
import org.apache.flink.agents.api.event.DecisionRequestEvent;
import org.apache.flink.agents.api.event.DecisionResponseEvent;
import org.apache.flink.agents.api.event.LongTermGetEvent;
import org.apache.flink.agents.api.event.LongTermSearchEvent;
import org.apache.flink.agents.api.event.LongTermUpdateEvent;
import org.apache.flink.agents.api.event.ModelRoutingEvent;
import org.apache.flink.agents.api.event.SensoryReadEvent;
import org.apache.flink.agents.api.event.SensoryWriteEvent;
import org.apache.flink.agents.api.event.ShortTermReadEvent;
import org.apache.flink.agents.api.event.ShortTermWriteEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for the {@link BuiltInEvents} registry and its {@code Event.fromJson} hookup. */
class BuiltInEventsTest {
    @Test
    void invalidBudgetRejectedDuringEventRestoration() {
        for (Object raw :
                List.of(
                        "not-a-map",
                        42,
                        List.of(),
                        Map.of("max_turns", 0),
                        Map.of("max_time_ms", 0),
                        Map.of("max_time_ms", -1),
                        Map.of("max_time_ms", 1.5),
                        Map.of("max_time_ms", true),
                        Map.of("max_time_ms", "1 ms"),
                        Map.of("max_tokens", 1.5),
                        Map.of("mode", "unknown"),
                        Map.of("unknown", 1))) {
            var event =
                    new Event(
                            ChatRequestEvent.EVENT_TYPE,
                            Map.of("model", "model", "messages", List.of(), "budget", raw));
            assertThatThrownBy(() -> ChatRequestEvent.fromEvent(event))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void budgetModeAcceptsCaseInsensitiveInputAndSerializesUppercase() throws Exception {
        for (String mode : List.of("soft", "SOFT", "SoFt", "hard", "HARD", "HaRd")) {
            var budget = MAPPER.readValue("{\"mode\":\"" + mode + "\"}", ReActLoopBudget.class);
            var expected =
                    ReActLoopBudget.BudgetMode.valueOf(mode.toUpperCase(java.util.Locale.ROOT));
            assertThat(budget.getMode()).isEqualTo(expected);
            String json = MAPPER.writeValueAsString(budget);
            assertThat(MAPPER.readTree(json).path("mode").asText()).isEqualTo(expected.name());
            assertThat(MAPPER.readValue(json, ReActLoopBudget.class).getMode()).isEqualTo(expected);
        }
    }

    @Test
    void budgetMillisecondsPreserveIntegerPrecision() throws Exception {
        for (long millis : new long[] {1L, 60001L, 9007199254740993L}) {
            var budget =
                    MAPPER.readValue("{\"max_time_ms\":" + millis + "}", ReActLoopBudget.class);
            assertThat(budget.getMaxTime()).isEqualTo(java.time.Duration.ofMillis(millis));
            assertThat(
                            MAPPER.readTree(MAPPER.writeValueAsString(budget))
                                    .path("max_time_ms")
                                    .longValue())
                    .isEqualTo(millis);
        }
        assertThat(MAPPER.readValue("{\"max_time_ms\":null}", ReActLoopBudget.class).getMaxTime())
                .isNull();
    }

    @Test
    void budgetRemainsTypedAcrossEventSerialization() throws Exception {
        for (ReActLoopBudget budget :
                List.of(
                        ReActLoopBudget.builder().build(),
                        ReActLoopBudget.builder()
                                .maxTurns(2)
                                .maxTime(java.time.Duration.ofMillis(60001))
                                .maxTokens(Long.MAX_VALUE)
                                .mode(ReActLoopBudget.BudgetMode.HARD)
                                .build())) {
            var request =
                    new ChatRequestEvent(
                            "model", List.of(ChatMessage.user("hi")), null, null, null, budget);
            assertThat(request.getAttr("budget")).isSameAs(budget);
            assertThat(ChatRequestEvent.fromEvent(request).getBudget()).isSameAs(budget);
            String json = MAPPER.writeValueAsString(request);
            var wire = MAPPER.readTree(json).path("attributes").path("budget");
            assertThat(wire.has("max_time")).isFalse();
            if (budget.getMaxTime() != null) {
                assertThat(wire.path("max_time_ms").isIntegralNumber()).isTrue();
                assertThat(wire.path("max_time_ms").longValue()).isEqualTo(60001L);
            } else {
                assertThat(wire.has("max_time_ms")).isFalse();
            }
            for (ChatRequestEvent restored :
                    List.of(
                            MAPPER.readValue(json, ChatRequestEvent.class),
                            ChatRequestEvent.fromEvent(Event.fromJson(json)))) {
                assertThat(restored.getAttr("budget")).isInstanceOf(ReActLoopBudget.class);
                assertThat(restored.getBudget().toMap()).isEqualTo(budget.toMap());
                assertThat(restored.getBudget()).isSameAs(restored.getBudget());
            }
            var restored =
                    MAPPER.readValue(MAPPER.writeValueAsString(budget), ReActLoopBudget.class);
            assertThat(restored.toMap()).isEqualTo(budget.toMap());
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID FIXED_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /**
     * Serializes a typed event and reads it back as the base {@link Event}, reproducing the
     * cross-language shape where nested typed values arrive as generic maps.
     */
    private static Event roundTripToBase(Event typed) throws Exception {
        return MAPPER.readValue(MAPPER.writeValueAsString(typed), Event.class);
    }

    private static ChatRequestEvent chatRequest() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("model", "test-model");
        attrs.put("messages", List.of(new ChatMessage(MessageRole.USER, "hello world")));
        return new ChatRequestEvent(FIXED_ID, attrs);
    }

    /** Attributes for a {@link ModelRoutingEvent}. */
    private static Map<String, Object> routingAttrs() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("request_id", REQUEST_ID);
        attrs.put("router", "test-router");
        attrs.put("candidates", List.of("model-a", "model-b"));
        attrs.put("selected_model", "model-a");
        attrs.put("decision_source", ModelRoutingEvent.SOURCE_DEFAULT);
        attrs.put("fallback_enabled", false);
        attrs.put("metadata", new LinkedHashMap<>());
        return attrs;
    }

    // ── Registry completeness ──────────────────────────────────────────────

    @Test
    void registryCoversEveryBuiltInEventTypeConstant() {
        assertThat(BuiltInEvents.registeredTypes())
                .containsExactlyInAnyOrderElementsOf(EventType.allConstants().values());
    }

    @Test
    void registryCoversEveryConcreteBuiltInEventSubclass() throws Exception {
        // Derive the expected set from the real class hierarchy (the compiled main output)
        // rather than from another hand-maintained list, so a new built-in event that is
        // added but forgotten in BuiltInEvents fails here instead of silently degrading to
        // a generic Event. Scanning Event's own code source keeps this to main classes and
        // excludes test-only Event subclasses such as EventTest.CustomPayloadEvent.
        Set<String> discovered = scanConcreteBuiltInEventTypeConstants();

        assertThat(discovered).containsExactlyInAnyOrderElementsOf(BuiltInEvents.registeredTypes());
    }

    /**
     * Reflectively collects the {@code EVENT_TYPE} of every concrete {@link Event} subclass
     * compiled into the main output, excluding {@link Event} itself and abstract bases such as
     * {@code MemoryEvent} (whose subclasses each pin their own type). Also fails if two concrete
     * subclasses declare the same serialized type.
     */
    private static Set<String> scanConcreteBuiltInEventTypeConstants() throws Exception {
        URL codeSource = Event.class.getProtectionDomain().getCodeSource().getLocation();
        assertThat(codeSource).as("code source of Event").isNotNull();
        Path root = new File(codeSource.toURI()).toPath();
        assertThat(Files.isDirectory(root))
                .as("expected exploded main classes at %s, not a packaged jar", root)
                .isTrue();

        // Track which class claimed each type so two events serializing to the same type fail
        // here with a precise diagnostic, rather than collapsing silently in a Set or surfacing
        // only as an opaque Map.ofEntries "Duplicate key" error when BuiltInEvents first loads.
        Map<String, String> typeToClass = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> classFiles =
                    paths.filter(path -> path.toString().endsWith(".class"))
                            .collect(Collectors.toList());
            for (Path classFile : classFiles) {
                String relative = root.relativize(classFile).toString();
                String className =
                        relative.substring(0, relative.length() - ".class".length())
                                .replace(File.separatorChar, '.');
                if (!className.startsWith("org.apache.flink.agents.api")) {
                    continue;
                }
                Class<?> candidate = Class.forName(className, false, Event.class.getClassLoader());
                if (!Event.class.isAssignableFrom(candidate)
                        || candidate == Event.class
                        || Modifier.isAbstract(candidate.getModifiers())) {
                    continue;
                }
                Field eventType = candidate.getDeclaredField("EVENT_TYPE");
                eventType.setAccessible(true);
                Object value = eventType.get(null);
                assertThat(value)
                        .as("EVENT_TYPE of concrete built-in event %s", candidate.getName())
                        .isNotNull();
                String previous = typeToClass.putIfAbsent((String) value, candidate.getName());
                assertThat(previous)
                        .as(
                                "serialized type '%s' is claimed by both %s and %s",
                                value, previous, candidate.getName())
                        .isNull();
            }
        }
        return typeToClass.keySet();
    }

    // ── Core restoration (the issue's headline example) ────────────────────

    @Test
    void restoreReconstructsChatRequestWithTypedMessages() throws Exception {
        Event base = roundTripToBase(chatRequest());

        // Pre-restore: a generic Event whose messages degraded to maps.
        assertThat(base).isExactlyInstanceOf(Event.class);
        List<?> rawMessages = (List<?>) base.getAttributes().get("messages");
        assertThat(rawMessages.get(0)).isInstanceOf(Map.class);

        Event restored = BuiltInEvents.restore(base);

        assertThat(restored).isInstanceOf(ChatRequestEvent.class);
        ChatRequestEvent chat = (ChatRequestEvent) restored;
        assertThat(chat.getModel()).isEqualTo("test-model");
        assertThat(chat.getId()).isEqualTo(FIXED_ID);
        assertThat(chat.getMessages()).hasSize(1);
        assertThat(chat.getMessages().get(0)).isInstanceOf(ChatMessage.class);
        assertThat(chat.getMessages().get(0).getRole()).isEqualTo(MessageRole.USER);
        assertThat(chat.getMessages().get(0).getText()).isEqualTo("hello world");
    }

    @Test
    void restoreReconstructsEveryBuiltInCategoryToItsConcreteType() throws Exception {
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("type", "tool_call");
        toolCall.put("call_id", "call_aaaa");
        toolCall.put("name", "echo");
        toolCall.put("input", Map.of("value", "ping"));

        Map<String, Object> toolAttrs = new LinkedHashMap<>();
        toolAttrs.put("model", "test-model");
        toolAttrs.put("tool_calls", List.of(toolCall));

        Map<String, Object> responseAttrs = new LinkedHashMap<>();
        responseAttrs.put("request_id", REQUEST_ID);
        responseAttrs.put("status", ChatResponseEvent.SUCCESS);
        responseAttrs.put("response", new ChatResult(ChatMessage.assistant("hi there")));
        responseAttrs.put("retry_count", 0);
        responseAttrs.put("total_retry_wait_ms", 0);

        Map<String, Object> contextAttrs = new LinkedHashMap<>();
        contextAttrs.put("query", "what is flink");
        contextAttrs.put("vector_store", "test-store");
        contextAttrs.put("max_results", 5);

        Map<String, Object> memoryAttrs = new LinkedHashMap<>();
        memoryAttrs.put("key", "user-42");
        memoryAttrs.put("value", new LinkedHashMap<>(Map.of("user.tier", "gold")));

        List<Event> typed =
                List.of(
                        new InputEvent(FIXED_ID, Map.of("input", "hello")),
                        new OutputEvent(FIXED_ID, Map.of("output", "world")),
                        chatRequest(),
                        new ChatResponseEvent(FIXED_ID, responseAttrs),
                        new DecisionRequestEvent(
                                "decision",
                                new DecisionRequest(
                                        "evidence",
                                        Map.of(
                                                "eligible",
                                                DecisionRequest.Question.predicate("Eligible?")))),
                        DecisionResponseEvent.success(
                                REQUEST_ID,
                                new DecisionResult(
                                        Map.of("eligible", new DecisionResult.PredicateAnswer(0.9)),
                                        null,
                                        null,
                                        Map.of()),
                                0,
                                0),
                        new ToolRequestEvent(FIXED_ID, toolAttrs),
                        new ContextRetrievalRequestEvent(FIXED_ID, contextAttrs),
                        new AgentRunBeginEvent(FIXED_ID, memoryAttrs),
                        new ShortTermWriteEvent(FIXED_ID, memoryAttrs),
                        new ModelRoutingEvent(FIXED_ID, routingAttrs()));

        for (Event original : typed) {
            Event base = roundTripToBase(original);
            assertThat(base)
                    .as("pre-restore shape of %s", original.getType())
                    .isExactlyInstanceOf(Event.class);

            Event restored = BuiltInEvents.restore(base);

            assertThat(restored.getClass()).isEqualTo(original.getClass());
            assertThat(restored.getType()).isEqualTo(original.getType());
            assertThat(restored.getId()).isEqualTo(original.getId());
        }
    }

    @Test
    void restoreDispatchesMemorySubtypeToConcreteClass() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("key", "user-42");
        attrs.put("value", new LinkedHashMap<>(Map.of("user.tier", "gold")));
        Event base = new Event(FIXED_ID, ShortTermWriteEvent.EVENT_TYPE, attrs);

        Event restored = BuiltInEvents.restore(base);

        assertThat(restored).isInstanceOf(ShortTermWriteEvent.class);
        assertThat(((ShortTermWriteEvent) restored).getKey()).isEqualTo("user-42");
    }

    // ── Fallback, idempotency, reconstruction, null ───────────────────────

    @Test
    void restoreReturnsUnknownTypeUnchanged() {
        Event base = new Event(FIXED_ID, "_my_custom_event", Map.of("value", "ping"));

        Event restored = BuiltInEvents.restore(base);

        assertThat(restored).isSameAs(base);
        assertThat(restored).isExactlyInstanceOf(Event.class);
        assertThat(restored.getAttr("value")).isEqualTo("ping");
    }

    @Test
    void restoreIsIdempotentForAlreadyTypedEvents() throws Exception {
        ChatRequestEvent typed =
                (ChatRequestEvent) BuiltInEvents.restore(roundTripToBase(chatRequest()));

        Event again = BuiltInEvents.restore(typed);

        assertThat(again).isInstanceOf(ChatRequestEvent.class);
        assertThat(((ChatRequestEvent) again).getMessages().get(0)).isInstanceOf(ChatMessage.class);
        assertThat(again.getId()).isEqualTo(typed.getId());
    }

    @Test
    void restorePreservesIdentityTimestampAndAttachments() {
        Event base = new Event(FIXED_ID, InputEvent.EVENT_TYPE, Map.of("input", "hello"));
        base.setSourceTimestamp(1_700_000_000_000L);
        base.setAttachment("payload", "attachment-value");

        Event restored = BuiltInEvents.restore(base);

        assertThat(restored).isInstanceOf(InputEvent.class);
        assertThat(restored.getId()).isEqualTo(FIXED_ID);
        assertThat(restored.getSourceTimestamp()).isEqualTo(1_700_000_000_000L);
        assertThat(restored.getAttachment("payload")).isEqualTo("attachment-value");
        assertThat(((InputEvent) restored).getInput()).isEqualTo("hello");
    }

    @Test
    void restorePreservesIdentityTimestampAndAttachmentsForModelRoutingEvent() {
        // ModelRoutingEvent.fromEvent must use the shared reconstruction path to preserve
        // attachments, identity, and source timestamp.
        Event base = new Event(FIXED_ID, ModelRoutingEvent.EVENT_TYPE, routingAttrs());
        base.setSourceTimestamp(1_700_000_000_000L);
        base.setAttachment("payload", "attachment-value");

        Event restored = BuiltInEvents.restore(base);

        assertThat(restored).isInstanceOf(ModelRoutingEvent.class);
        assertThat(restored.getId()).isEqualTo(FIXED_ID);
        assertThat(restored.getSourceTimestamp()).isEqualTo(1_700_000_000_000L);
        assertThat(restored.getAttachment("payload")).isEqualTo("attachment-value");
        assertThat(((ModelRoutingEvent) restored).getSelectedModel()).isEqualTo("model-a");
    }

    @Test
    void restoreReturnsNullForNullInput() {
        assertThat(BuiltInEvents.restore(null)).isNull();
    }

    // ── Malformed built-in events fail clearly ─────────────────────────────

    @Test
    void restoreThrowsForMalformedMemoryEvent() {
        Event base = new Event(FIXED_ID, ShortTermWriteEvent.EVENT_TYPE, Map.of("key", "user-42"));

        assertThatThrownBy(() -> BuiltInEvents.restore(base))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed built-in event of type '_short_term_write_event'");
    }

    @Test
    void restoreRejectsOutputEventCarryingAttachments() {
        Event base =
                new Event(
                        FIXED_ID,
                        OutputEvent.EVENT_TYPE,
                        Map.of("output", "world"),
                        Map.of("payload", "attachment-value"));

        assertThatThrownBy(() -> BuiltInEvents.restore(base))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed built-in event of type '_output_event'");
    }

    // ── Public boundary: Event.fromJson ────────────────────────────────────

    @Test
    void fromJsonRestoresBuiltInTypeAtTheBoundary() throws Exception {
        String json = MAPPER.writeValueAsString(chatRequest());

        Event event = Event.fromJson(json);

        assertThat(event).isInstanceOf(ChatRequestEvent.class);
        assertThat(((ChatRequestEvent) event).getMessages().get(0)).isInstanceOf(ChatMessage.class);
    }

    @Test
    void fromJsonKeepsUserDefinedTypeGeneric() throws Exception {
        Event event =
                Event.fromJson("{\"type\":\"_my_custom_event\",\"attributes\":{\"k\":\"v\"}}");

        assertThat(event).isExactlyInstanceOf(Event.class);
        assertThat(event.getAttr("k")).isEqualTo("v");
    }

    // ── Malformed built-in events are rejected at the JSON boundary ────────

    @Test
    void fromJsonRejectsChatRequestMissingRequiredAttributes() {
        // A ChatRequestEvent with empty attributes must fail at the boundary rather than being
        // accepted with a null model and no messages.
        assertThatThrownBy(
                        () ->
                                Event.fromJson(
                                        "{\"type\":\"_chat_request_event\",\"attributes\":{}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed built-in event of type '_chat_request_event'");
    }

    @Test
    void fromJsonRejectsChatRequestWithInvalidMessageElementType() {
        // messages:[1] must be rejected, not silently converted to an empty list.
        assertThatThrownBy(
                        () ->
                                Event.fromJson(
                                        "{\"type\":\"_chat_request_event\",\"attributes\":"
                                                + "{\"model\":\"m\",\"messages\":[1]}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed built-in event of type '_chat_request_event'");
    }

    // ── Every registered built-in type enforces its schema at the boundary ──

    /**
     * Builds an attribute map from alternating key/value arguments, so each fixture below reads as
     * a plain literal without generic-inference noise.
     */
    private static Map<String, Object> attrs(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** The {@code {key, value}} shape shared by every memory observation event. */
    private static Map<String, Object> memoryAttrs() {
        return attrs("key", "user-42", "value", Map.of("user.tier", "gold"));
    }

    /**
     * Minimal schema-valid attributes for every registered built-in type. Each entry satisfies both
     * its {@code fromEvent} schema check and the concrete constructor, so {@link
     * #fromJsonRestoresEveryBuiltInTypeFromMinimalValidAttributes} proves the fixtures are
     * genuinely valid and the unknown-attribute test below fails only because of the injected key.
     */
    private static Map<String, Map<String, Object>> validAttributesByType() {
        Map<String, Map<String, Object>> byType = new LinkedHashMap<>();
        byType.put(InputEvent.EVENT_TYPE, attrs("input", "hello"));
        byType.put(OutputEvent.EVENT_TYPE, attrs("output", "world"));
        byType.put(
                ChatRequestEvent.EVENT_TYPE, attrs("model", "test-model", "messages", List.of()));
        byType.put(
                ChatResponseEvent.EVENT_TYPE,
                attrs(
                        "request_id",
                        REQUEST_ID,
                        "status",
                        ChatResponseEvent.FAILED,
                        "error",
                        "boom"));
        byType.put(
                DecisionRequestEvent.EVENT_TYPE,
                attrs(
                        "model",
                        "decision",
                        "request",
                        new DecisionRequest(
                                "evidence",
                                Map.of(
                                        "eligible",
                                        DecisionRequest.Question.predicate("Eligible?")))));
        byType.put(
                DecisionResponseEvent.EVENT_TYPE,
                attrs(
                        "request_id",
                        REQUEST_ID,
                        "status",
                        DecisionResponseEvent.FAILED,
                        "error",
                        "boom"));
        byType.put(
                ToolRequestEvent.EVENT_TYPE, attrs("model", "test-model", "tool_calls", List.of()));
        byType.put(
                ToolResponseEvent.EVENT_TYPE,
                attrs("request_id", REQUEST_ID, "responses", Map.of()));
        byType.put(
                ContextRetrievalRequestEvent.EVENT_TYPE,
                attrs("query", "what is flink", "vector_store", "test-store", "max_results", 5));
        byType.put(
                ContextRetrievalResponseEvent.EVENT_TYPE,
                attrs("request_id", REQUEST_ID, "query", "what is flink", "documents", List.of()));
        byType.put(ModelRoutingEvent.EVENT_TYPE, routingAttrs());
        byType.put(AgentRunBeginEvent.EVENT_TYPE, memoryAttrs());
        byType.put(ShortTermWriteEvent.EVENT_TYPE, memoryAttrs());
        byType.put(ShortTermReadEvent.EVENT_TYPE, memoryAttrs());
        byType.put(SensoryWriteEvent.EVENT_TYPE, memoryAttrs());
        byType.put(SensoryReadEvent.EVENT_TYPE, memoryAttrs());
        byType.put(LongTermGetEvent.EVENT_TYPE, memoryAttrs());
        byType.put(LongTermSearchEvent.EVENT_TYPE, memoryAttrs());
        byType.put(LongTermUpdateEvent.EVENT_TYPE, memoryAttrs());
        return byType;
    }

    static Stream<String> builtInEventTypes() {
        return BuiltInEvents.registeredTypes().stream().sorted();
    }

    private static String eventJson(String type, Map<String, Object> attributes) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", FIXED_ID);
        root.put("type", type);
        root.put("attributes", attributes);
        return MAPPER.writeValueAsString(root);
    }

    @Test
    void validAttributesCoverEveryRegisteredBuiltInType() {
        // Guards the fixtures above: a newly registered type without a fixture fails here rather
        // than silently escaping the parameterized schema tests below.
        assertThat(validAttributesByType().keySet())
                .containsExactlyInAnyOrderElementsOf(BuiltInEvents.registeredTypes());
    }

    @ParameterizedTest
    @MethodSource("builtInEventTypes")
    void fromJsonRestoresEveryBuiltInTypeFromMinimalValidAttributes(String type) throws Exception {
        // Positive control: the minimal fixtures are genuinely schema-valid, so restoring each to
        // its concrete subtype confirms the rejection tests below fail for the right reason.
        Event event = Event.fromJson(eventJson(type, validAttributesByType().get(type)));

        assertThat(event.getType()).isEqualTo(type);
        assertThat(event).isNotExactlyInstanceOf(Event.class);
    }

    @ParameterizedTest
    @MethodSource("builtInEventTypes")
    void fromJsonRejectsUnknownAttributeForEveryBuiltInType(String type) throws Exception {
        // A valid event plus one attribute outside the fixed schema must be rejected, not silently
        // carried into the reconstructed event.
        Map<String, Object> attributes = new LinkedHashMap<>(validAttributesByType().get(type));
        attributes.put("__unknown_attribute__", "bogus");
        String json = eventJson(type, attributes);

        assertThatThrownBy(() -> Event.fromJson(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed built-in event of type '" + type + "'");
    }

    @ParameterizedTest
    @MethodSource("builtInEventTypes")
    void fromJsonRejectsMissingRequiredAttributesForEveryBuiltInType(String type) {
        // Every built-in type declares at least one required attribute, so empty attributes must be
        // rejected rather than restored with absent or null fields.
        String json = "{\"type\":\"" + type + "\",\"attributes\":{}}";

        assertThatThrownBy(() -> Event.fromJson(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed built-in event of type '" + type + "'");
    }

    // ── Representative invalid field types across the distinct type checks ──

    @Test
    void fromJsonRejectsChatRequestWithNonStringModel() {
        assertThatThrownBy(
                        () ->
                                Event.fromJson(
                                        "{\"type\":\"_chat_request_event\",\"attributes\":"
                                                + "{\"model\":123,\"messages\":[]}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed built-in event of type '_chat_request_event'");
    }

    @Test
    void fromJsonRejectsToolRequestWithNonMapToolCallElement() {
        assertThatThrownBy(
                        () ->
                                Event.fromJson(
                                        "{\"type\":\"_tool_request_event\",\"attributes\":"
                                                + "{\"model\":\"m\",\"tool_calls\":[1]}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed built-in event of type '_tool_request_event'");
    }

    @Test
    void fromJsonRejectsToolResponseWithNonUuidRequestId() {
        assertThatThrownBy(
                        () ->
                                Event.fromJson(
                                        "{\"type\":\"_tool_response_event\",\"attributes\":"
                                                + "{\"request_id\":\"not-a-uuid\","
                                                + "\"responses\":{}}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed built-in event of type '_tool_response_event'");
    }

    @Test
    void fromJsonRejectsContextRetrievalRequestWithNonNumberMaxResults() {
        assertThatThrownBy(
                        () ->
                                Event.fromJson(
                                        "{\"type\":\"_context_retrieval_request_event\","
                                                + "\"attributes\":{\"query\":\"q\","
                                                + "\"vector_store\":\"vs\","
                                                + "\"max_results\":\"many\"}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "Malformed built-in event of type '_context_retrieval_request_event'");
    }
}
