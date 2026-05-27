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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.agents.OutputSchema;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ContextRetrievalRequestEvent;
import org.apache.flink.agents.api.event.ContextRetrievalResponseEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.vectorstores.Document;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Cross-language event SerDe snapshot tests. */
class CrossLanguageEventSnapshotTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UUID FIXED_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID FIXED_REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String FIXED_TOOL_CALL_ID = "call_aaaa";
    private static final long FIXED_TIMESTAMP = 1_700_000_000_000L;

    private static Path snapshotDir;

    @BeforeAll
    static void resolveSnapshotDir() {
        Path repoRoot = Paths.get(System.getProperty("user.dir")).getParent();
        snapshotDir = repoRoot.resolve("e2e-test/cross-language-event-snapshots");
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static boolean regenerateRequested() {
        return Boolean.parseBoolean(System.getProperty("regenerate.snapshots", "false"));
    }

    private static void writeJavaSnapshot(String fileName, Event event) throws Exception {
        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(event);
        Path target = snapshotDir.resolve("java/" + fileName);
        Files.createDirectories(target.getParent());
        Files.writeString(target, json + "\n");
    }

    private static void assertJavaSnapshotStable(String fileName, Event event) throws Exception {
        String actualJson = MAPPER.writeValueAsString(event);
        JsonNode actual = MAPPER.readTree(actualJson);

        Path committed = snapshotDir.resolve("java/" + fileName);
        assumeTrue(
                Files.exists(committed),
                "Java snapshot " + fileName + " not committed; run with -Dregenerate.snapshots=true first.");
        JsonNode expected = MAPPER.readTree(Files.readString(committed));

        assertEquals(
                expected,
                actual,
                "Java serialization of " + fileName + " drifted from committed snapshot; if intentional, regenerate.");
    }

    private static Event readPythonSnapshot(String fileName) throws Exception {
        Path pythonSnapshot = snapshotDir.resolve("python/" + fileName);
        assumeTrue(
                Files.exists(pythonSnapshot),
                "Python snapshot " + fileName + " not present; run Python generator first.");
        return Event.fromJson(Files.readString(pythonSnapshot));
    }

    // ── InputEvent ─────────────────────────────────────────────────────────

    private static InputEvent buildInputEvent() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("input", "hello");
        return new InputEvent(FIXED_EVENT_ID, attrs);
    }

    @Test
    void regenerateInputEventJavaSnapshot() throws Exception {
        assumeTrue(regenerateRequested(), "Set -Dregenerate.snapshots=true to refresh.");
        writeJavaSnapshot("input_event.json", buildInputEvent());
    }

    @Test
    void inputEventJavaSnapshotIsStable() throws Exception {
        assertJavaSnapshotStable("input_event.json", buildInputEvent());
    }

    @Test
    void javaCanDeserializeInputEventFromPythonSnapshot() throws Exception {
        Event base = readPythonSnapshot("input_event.json");
        InputEvent typed = InputEvent.fromEvent(base);

        assertEquals(FIXED_EVENT_ID, typed.getId(), "ID lost when deserializing Python InputEvent.");
        assertEquals(InputEvent.EVENT_TYPE, typed.getType());
        assertEquals("hello", typed.getInput(), "InputEvent.input mismatch.");
    }

    // ── OutputEvent ────────────────────────────────────────────────────────

    private static OutputEvent buildOutputEvent() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("output", "world");
        return new OutputEvent(FIXED_EVENT_ID, attrs);
    }

    @Test
    void regenerateOutputEventJavaSnapshot() throws Exception {
        assumeTrue(regenerateRequested(), "Set -Dregenerate.snapshots=true to refresh.");
        writeJavaSnapshot("output_event.json", buildOutputEvent());
    }

    @Test
    void outputEventJavaSnapshotIsStable() throws Exception {
        assertJavaSnapshotStable("output_event.json", buildOutputEvent());
    }

    @Test
    void javaCanDeserializeOutputEventFromPythonSnapshot() throws Exception {
        Event base = readPythonSnapshot("output_event.json");
        OutputEvent typed = OutputEvent.fromEvent(base);

        assertEquals(FIXED_EVENT_ID, typed.getId(), "ID lost when deserializing Python OutputEvent.");
        assertEquals(OutputEvent.EVENT_TYPE, typed.getType());
        assertEquals("world", typed.getOutput(), "OutputEvent.output mismatch.");
    }

    // ── ChatRequestEvent ───────────────────────────────────────────────────

    private static ChatRequestEvent buildChatRequestEvent() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("model", "test-model");
        attrs.put("messages", List.of(new ChatMessage(MessageRole.USER, "hello world")));
        return new ChatRequestEvent(FIXED_EVENT_ID, attrs);
    }

    @Test
    void regenerateChatRequestEventJavaSnapshot() throws Exception {
        assumeTrue(regenerateRequested(), "Set -Dregenerate.snapshots=true to refresh.");
        writeJavaSnapshot("chat_request_event.json", buildChatRequestEvent());
    }

    @Test
    void chatRequestEventJavaSnapshotIsStable() throws Exception {
        assertJavaSnapshotStable("chat_request_event.json", buildChatRequestEvent());
    }

    @Test
    void javaCanDeserializeChatRequestEventFromPythonSnapshot() throws Exception {
        Event base = readPythonSnapshot("chat_request_event.json");
        ChatRequestEvent typed = ChatRequestEvent.fromEvent(base);

        assertEquals(FIXED_EVENT_ID, typed.getId());
        assertEquals(ChatRequestEvent.EVENT_TYPE, typed.getType());
        assertEquals("test-model", typed.getModel());
        assertNotNull(typed.getMessages());
        assertEquals(1, typed.getMessages().size(), "Expected one message.");
        ChatMessage msg = typed.getMessages().get(0);
        assertEquals(MessageRole.USER, msg.getRole(), "Role mismatch on Python-produced message.");
        assertEquals("hello world", msg.getContent());
    }

    @Test
    void chatRequestOutputSchemaWireFormatIsJavaShaped() throws Exception {
        OutputSchema schema = new OutputSchema(
                new RowTypeInfo(
                        new TypeInformation[] {BasicTypeInfo.STRING_TYPE_INFO},
                        new String[] {"name"}));
        ChatRequestEvent event = new ChatRequestEvent(
                "test-model", List.of(new ChatMessage(MessageRole.USER, "hi")), schema);
        String json = MAPPER.writeValueAsString(event);

        assertTrue(json.contains("\"fieldNames\""), "Java wire format uses `fieldNames`.");
        assertFalse(json.contains("\"names\""), "Java wire format does not use Python's `names`.");
    }

    // ── ChatResponseEvent ──────────────────────────────────────────────────

    private static ChatResponseEvent buildChatResponseEvent() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("request_id", FIXED_REQUEST_ID);
        attrs.put("response", new ChatMessage(MessageRole.ASSISTANT, "hi there"));
        attrs.put("retry_count", 0);
        attrs.put("total_retry_wait_sec", 0);
        return new ChatResponseEvent(FIXED_EVENT_ID, attrs);
    }

    @Test
    void regenerateChatResponseEventJavaSnapshot() throws Exception {
        assumeTrue(regenerateRequested(), "Set -Dregenerate.snapshots=true to refresh.");
        writeJavaSnapshot("chat_response_event.json", buildChatResponseEvent());
    }

    @Test
    void chatResponseEventJavaSnapshotIsStable() throws Exception {
        assertJavaSnapshotStable("chat_response_event.json", buildChatResponseEvent());
    }

    @Test
    void javaCanDeserializeChatResponseEventFromPythonSnapshot() throws Exception {
        Event base = readPythonSnapshot("chat_response_event.json");
        ChatResponseEvent typed = ChatResponseEvent.fromEvent(base);

        assertEquals(FIXED_EVENT_ID, typed.getId());
        assertEquals(ChatResponseEvent.EVENT_TYPE, typed.getType());
        assertEquals(FIXED_REQUEST_ID, typed.getRequestId(), "request_id mismatch.");
        ChatMessage response = typed.getResponse();
        assertNotNull(response, "response field is null.");
        assertEquals(MessageRole.ASSISTANT, response.getRole(), "Role mismatch on response.");
        assertEquals("hi there", response.getContent());
    }

    // ── ToolRequestEvent ───────────────────────────────────────────────────

    private static ToolRequestEvent buildToolRequestEvent() {
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", FIXED_TOOL_CALL_ID);
        toolCall.put("name", "echo");
        toolCall.put("arguments", Map.of("value", "ping"));

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("model", "test-model");
        attrs.put("tool_calls", List.of(toolCall));
        return new ToolRequestEvent(FIXED_EVENT_ID, attrs);
    }

    @Test
    void regenerateToolRequestEventJavaSnapshot() throws Exception {
        assumeTrue(regenerateRequested(), "Set -Dregenerate.snapshots=true to refresh.");
        writeJavaSnapshot("tool_request_event.json", buildToolRequestEvent());
    }

    @Test
    void toolRequestEventJavaSnapshotIsStable() throws Exception {
        assertJavaSnapshotStable("tool_request_event.json", buildToolRequestEvent());
    }

    @Test
    void javaCanDeserializeToolRequestEventFromPythonSnapshot() throws Exception {
        Event base = readPythonSnapshot("tool_request_event.json");
        ToolRequestEvent typed = ToolRequestEvent.fromEvent(base);

        assertEquals(FIXED_EVENT_ID, typed.getId());
        assertEquals(ToolRequestEvent.EVENT_TYPE, typed.getType());
        assertEquals("test-model", typed.getModel());
        List<Map<String, Object>> toolCalls = typed.getToolCalls();
        assertNotNull(toolCalls);
        assertEquals(1, toolCalls.size());
        assertEquals(FIXED_TOOL_CALL_ID, toolCalls.get(0).get("id"));
    }

    // ── ToolResponseEvent ──────────────────────────────────────────────────

    private static ToolResponseEvent buildToolResponseEvent() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("request_id", FIXED_REQUEST_ID);
        attrs.put("responses", Map.of(FIXED_TOOL_CALL_ID, ToolResponse.success("pong")));
        attrs.put("success", Map.of(FIXED_TOOL_CALL_ID, true));
        attrs.put("error", new HashMap<String, String>());
        attrs.put("external_ids", new HashMap<String, String>());
        attrs.put("timestamp", FIXED_TIMESTAMP);
        return new ToolResponseEvent(FIXED_EVENT_ID, attrs);
    }

    @Test
    void regenerateToolResponseEventJavaSnapshot() throws Exception {
        assumeTrue(regenerateRequested(), "Set -Dregenerate.snapshots=true to refresh.");
        writeJavaSnapshot("tool_response_event.json", buildToolResponseEvent());
    }

    @Test
    void toolResponseEventJavaSnapshotIsStable() throws Exception {
        assertJavaSnapshotStable("tool_response_event.json", buildToolResponseEvent());
    }

    @Test
    void pythonToolResponseEventLosesDataWhenConsumedByJava() throws Exception {
        Event base = readPythonSnapshot("tool_response_event.json");
        ToolResponseEvent typed = ToolResponseEvent.fromEvent(base);

        assertEquals(FIXED_REQUEST_ID, typed.getRequestId());
        assertTrue(typed.getResponses().isEmpty());

        Map<String, Object> attrs = typed.getAttributes();
        assertFalse(attrs.containsKey("success"));
        assertFalse(attrs.containsKey("error"));
        assertFalse(attrs.containsKey("timestamp"));
    }

    // ── ContextRetrievalRequestEvent ───────────────────────────────────────

    private static ContextRetrievalRequestEvent buildContextRetrievalRequestEvent() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("query", "what is flink");
        attrs.put("vector_store", "test-store");
        attrs.put("max_results", 5);
        return new ContextRetrievalRequestEvent(FIXED_EVENT_ID, attrs);
    }

    @Test
    void regenerateContextRetrievalRequestEventJavaSnapshot() throws Exception {
        assumeTrue(regenerateRequested(), "Set -Dregenerate.snapshots=true to refresh.");
        writeJavaSnapshot(
                "context_retrieval_request_event.json", buildContextRetrievalRequestEvent());
    }

    @Test
    void contextRetrievalRequestEventJavaSnapshotIsStable() throws Exception {
        assertJavaSnapshotStable(
                "context_retrieval_request_event.json", buildContextRetrievalRequestEvent());
    }

    @Test
    void javaCanDeserializeContextRetrievalRequestEventFromPythonSnapshot() throws Exception {
        Event base = readPythonSnapshot("context_retrieval_request_event.json");
        ContextRetrievalRequestEvent typed = ContextRetrievalRequestEvent.fromEvent(base);

        assertEquals(FIXED_EVENT_ID, typed.getId());
        assertEquals(ContextRetrievalRequestEvent.EVENT_TYPE, typed.getType());
        assertEquals("what is flink", typed.getQuery());
        assertEquals("test-store", typed.getVectorStore());
        assertEquals(5, typed.getMaxResults());
    }

    // ── ContextRetrievalResponseEvent ──────────────────────────────────────

    private static ContextRetrievalResponseEvent buildContextRetrievalResponseEvent() {
        Document doc = new Document("doc content", new LinkedHashMap<>(Map.of("k", "v")), "doc-1");
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("request_id", FIXED_REQUEST_ID);
        attrs.put("query", "what is flink");
        attrs.put("documents", new ArrayList<>(List.of(doc)));
        return new ContextRetrievalResponseEvent(FIXED_EVENT_ID, attrs);
    }

    @Test
    void regenerateContextRetrievalResponseEventJavaSnapshot() throws Exception {
        assumeTrue(regenerateRequested(), "Set -Dregenerate.snapshots=true to refresh.");
        writeJavaSnapshot(
                "context_retrieval_response_event.json",
                buildContextRetrievalResponseEvent());
    }

    @Test
    void contextRetrievalResponseEventJavaSnapshotIsStable() throws Exception {
        assertJavaSnapshotStable(
                "context_retrieval_response_event.json",
                buildContextRetrievalResponseEvent());
    }

    @Test
    void javaCanDeserializeContextRetrievalResponseEventFromPythonSnapshot() throws Exception {
        Event base = readPythonSnapshot("context_retrieval_response_event.json");
        ContextRetrievalResponseEvent typed = ContextRetrievalResponseEvent.fromEvent(base);

        assertEquals(FIXED_EVENT_ID, typed.getId());
        assertEquals(ContextRetrievalResponseEvent.EVENT_TYPE, typed.getType());
        assertEquals(FIXED_REQUEST_ID, typed.getRequestId());
        assertEquals("what is flink", typed.getQuery());
        List<Document> docs = typed.getDocuments();
        assertNotNull(docs);
        assertEquals(1, docs.size());
        assertEquals("doc content", docs.get(0).getContent());
        assertEquals("doc-1", docs.get(0).getId());
    }

    // ── Generic Event with primitive attributes (user-authored axis) ───────

    private static final String GENERIC_EVENT_TYPE = "_my_custom_event";

    private static Event buildGenericEvent() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("k_int", 42);
        attrs.put("k_float", 1.5);
        attrs.put("k_bool", true);
        attrs.put("k_str", "hello");
        attrs.put("k_null", null);
        attrs.put("k_list", List.of(1, 2, 3));
        attrs.put("k_dict", Map.of("nested", "value"));
        return new Event(FIXED_EVENT_ID, GENERIC_EVENT_TYPE, attrs);
    }

    @Test
    void regenerateGenericEventJavaSnapshot() throws Exception {
        assumeTrue(regenerateRequested(), "Set -Dregenerate.snapshots=true to refresh.");
        writeJavaSnapshot("generic_event_with_attrs.json", buildGenericEvent());
    }

    @Test
    void genericEventJavaSnapshotIsStable() throws Exception {
        assertJavaSnapshotStable("generic_event_with_attrs.json", buildGenericEvent());
    }

    @Test
    void javaCanDeserializeGenericEventFromPythonSnapshot() throws Exception {
        Event base = readPythonSnapshot("generic_event_with_attrs.json");

        assertEquals(GENERIC_EVENT_TYPE, base.getType());
        Map<String, Object> attrs = base.getAttributes();
        assertEquals(42, attrs.get("k_int"));
        assertTrue(attrs.get("k_int") instanceof Integer);
        assertEquals(1.5, attrs.get("k_float"));
        assertTrue(attrs.get("k_float") instanceof Double);
        assertEquals(true, attrs.get("k_bool"));
        assertEquals("hello", attrs.get("k_str"));
        assertTrue(attrs.containsKey("k_null"));
        assertEquals(null, attrs.get("k_null"));
        assertEquals(List.of(1, 2, 3), attrs.get("k_list"));
        assertEquals(Map.of("nested", "value"), attrs.get("k_dict"));
    }

    // ── Python-only subclass with no Java counterpart (graceful fallback) ──

    @Test
    void javaCanDeserializePythonOnlySubclassEventAsBaseEvent() throws Exception {
        Event base = readPythonSnapshot("python_only_subclass_event.json");

        assertEquals(Event.class, base.getClass());
        assertEquals("_my_python_only_event", base.getType());
        assertEquals(FIXED_EVENT_ID, base.getId());

        Map<String, Object> attrs = base.getAttributes();
        assertEquals("ping", attrs.get("value"));
        assertEquals(7, attrs.get("count"));
    }

    // ── Smoke ──────────────────────────────────────────────────────────────

    @Test
    void snapshotDirectoryExists() {
        assertNotNull(snapshotDir);
        assertTrue(
                Files.isDirectory(snapshotDir),
                "Expected snapshot directory at " + snapshotDir);
    }
}
