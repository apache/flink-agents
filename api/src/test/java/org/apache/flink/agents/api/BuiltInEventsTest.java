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
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.event.AgentRunBeginEvent;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ContextRetrievalRequestEvent;
import org.apache.flink.agents.api.event.ModelRoutingEvent;
import org.apache.flink.agents.api.event.ShortTermWriteEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.junit.jupiter.api.Test;

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

    /** Attributes for a {@link ModelRoutingEvent}, whose reconstructor must keep full lineage. */
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
        toolCall.put("id", "call_aaaa");
        toolCall.put("name", "echo");
        toolCall.put("arguments", Map.of("value", "ping"));

        Map<String, Object> toolAttrs = new LinkedHashMap<>();
        toolAttrs.put("model", "test-model");
        toolAttrs.put("tool_calls", List.of(toolCall));

        Map<String, Object> responseAttrs = new LinkedHashMap<>();
        responseAttrs.put("request_id", REQUEST_ID);
        responseAttrs.put("status", ChatResponseEvent.SUCCESS);
        responseAttrs.put("response", new ChatMessage(MessageRole.ASSISTANT, "hi there"));
        responseAttrs.put("retry_count", 0);
        responseAttrs.put("total_retry_wait_sec", 0);

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

    // ── Fallback, idempotency, lineage, null ───────────────────────────────

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
    void restorePreservesLineageAndAttachments() {
        UUID upstream = UUID.randomUUID();
        Event base = new Event(FIXED_ID, InputEvent.EVENT_TYPE, Map.of("input", "hello"));
        base.setUpstreamEventId(upstream);
        base.setUpstreamActionName("input_action");
        base.setSourceTimestamp(1_700_000_000_000L);
        base.setAttachment("payload", "attachment-value");

        Event restored = BuiltInEvents.restore(base);

        assertThat(restored).isInstanceOf(InputEvent.class);
        assertThat(restored.getId()).isEqualTo(FIXED_ID);
        assertThat(restored.getUpstreamEventId()).isEqualTo(upstream);
        assertThat(restored.getUpstreamActionName()).isEqualTo("input_action");
        assertThat(restored.getSourceTimestamp()).isEqualTo(1_700_000_000_000L);
        assertThat(restored.getAttachment("payload")).isEqualTo("attachment-value");
        assertThat(((InputEvent) restored).getInput()).isEqualTo("hello");
    }

    @Test
    void restorePreservesLineageAndAttachmentsForModelRoutingEvent() {
        // Regression: ModelRoutingEvent.fromEvent must reconstruct through the shared path like
        // every other built-in, so restoring it at the JSON boundary keeps upstream lineage and
        // attachments instead of silently dropping all but id, attributes, and source timestamp.
        UUID upstream = UUID.randomUUID();
        Event base = new Event(FIXED_ID, ModelRoutingEvent.EVENT_TYPE, routingAttrs());
        base.setUpstreamEventId(upstream);
        base.setUpstreamActionName("router_action");
        base.setSourceTimestamp(1_700_000_000_000L);
        base.setAttachment("payload", "attachment-value");

        Event restored = BuiltInEvents.restore(base);

        assertThat(restored).isInstanceOf(ModelRoutingEvent.class);
        assertThat(restored.getId()).isEqualTo(FIXED_ID);
        assertThat(restored.getUpstreamEventId()).isEqualTo(upstream);
        assertThat(restored.getUpstreamActionName()).isEqualTo("router_action");
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
}
