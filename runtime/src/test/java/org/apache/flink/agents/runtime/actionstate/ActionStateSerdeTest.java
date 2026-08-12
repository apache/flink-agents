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
package org.apache.flink.agents.runtime.actionstate;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.context.MemoryUpdate;
import org.apache.flink.agents.api.event.AgentRunBeginEvent;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ContextRetrievalRequestEvent;
import org.apache.flink.agents.api.event.ContextRetrievalResponseEvent;
import org.apache.flink.agents.api.event.MemoryEvent;
import org.apache.flink.agents.api.event.ShortTermWriteEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.vectorstores.Document;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Test class for ActionState serialization and deserialization. */
public class ActionStateSerdeTest {

    @Test
    public void testActionStateSerializationDeserialization() throws Exception {
        // Create test data
        InputEvent inputEvent = new InputEvent("test input");
        inputEvent.setAttr("testAttr", "testValue");

        OutputEvent outputEvent = new OutputEvent("test output");
        outputEvent.setAttr("outputAttr", 123);

        MemoryUpdate sensoryMemoryUpdate = new MemoryUpdate("sm.test.path", "sm test value");
        MemoryUpdate shortTermMemoryUpdate = new MemoryUpdate("stm.test.path", "stm test value");

        // Create ActionState
        ActionState originalState = new ActionState(inputEvent);
        originalState.addSensoryMemoryUpdate(sensoryMemoryUpdate);
        originalState.addShortTermMemoryUpdate(shortTermMemoryUpdate);
        originalState.addEvent(outputEvent);

        // Serialize
        byte[] serialized = ActionStateSerde.serialize(originalState);
        assertNotNull(serialized);
        assertTrue(serialized.length > 0);

        // Deserialize
        ActionState deserializedState = ActionStateSerde.deserialize(serialized);
        assertNotNull(deserializedState);

        // Verify taskEvent
        assertNotNull(deserializedState.getTaskEvent());
        assertEquals(InputEvent.class, deserializedState.getTaskEvent().getClass());
        InputEvent deserializedInputEvent = (InputEvent) deserializedState.getTaskEvent();
        assertEquals("test input", deserializedInputEvent.getInput());
        assertEquals("testValue", deserializedInputEvent.getAttr("testAttr"));

        // Verify memoryUpdates
        assertEquals(1, deserializedState.getSensoryMemoryUpdates().size());
        MemoryUpdate deserializedSensoryMemoryUpdate =
                deserializedState.getSensoryMemoryUpdates().get(0);
        assertEquals("sm.test.path", deserializedSensoryMemoryUpdate.getPath());
        assertEquals("sm test value", deserializedSensoryMemoryUpdate.getValue());
        assertEquals(1, deserializedState.getShortTermMemoryUpdates().size());
        MemoryUpdate deserializedShortTermMemoryUpdate =
                deserializedState.getShortTermMemoryUpdates().get(0);
        assertEquals("stm.test.path", deserializedShortTermMemoryUpdate.getPath());
        assertEquals("stm test value", deserializedShortTermMemoryUpdate.getValue());

        // Verify outputEvents
        assertEquals(1, deserializedState.getOutputEvents().size());
        Event deserializedOutputEvent = deserializedState.getOutputEvents().get(0);
        assertEquals(OutputEvent.class, deserializedOutputEvent.getClass());
        OutputEvent deserializedOutputEventTyped = (OutputEvent) deserializedOutputEvent;
        assertEquals("test output", deserializedOutputEventTyped.getOutput());
        assertEquals(123, deserializedOutputEventTyped.getAttr("outputAttr"));
    }

    @Test
    public void testActionStateWithNullTaskEvent() throws Exception {
        // Create ActionState with null taskEvent
        ActionState originalState = new ActionState(null, null, null, null, null, false);
        MemoryUpdate memoryUpdate = new MemoryUpdate("test.path", "test value");
        originalState.addShortTermMemoryUpdate(memoryUpdate);
        originalState.addSensoryMemoryUpdate(memoryUpdate);

        // Test serialization/deserialization
        byte[] serialized = ActionStateSerde.serialize(originalState);
        ActionState deserializedState = ActionStateSerde.deserialize(serialized);

        // Verify taskEvent is null
        assertNull(deserializedState.getTaskEvent());

        // Verify other fields
        assertEquals(1, deserializedState.getSensoryMemoryUpdates().size());
        assertEquals(1, deserializedState.getShortTermMemoryUpdates().size());
        assertEquals(0, deserializedState.getOutputEvents().size());
    }

    @Test
    public void testActionStateWithComplexAttributes() throws Exception {
        // Create InputEvent with complex attributes
        InputEvent inputEvent = new InputEvent("test input");
        Map<String, Object> complexMap = new HashMap<>();
        complexMap.put("nested", "value");
        complexMap.put("number", 42);
        inputEvent.setAttr("complexAttr", complexMap);

        ActionState originalState = new ActionState(inputEvent);

        // Test serialization/deserialization
        byte[] serialized = ActionStateSerde.serialize(originalState);
        ActionState deserializedState = ActionStateSerde.deserialize(serialized);

        // Verify complex attributes are preserved
        InputEvent deserializedInputEvent = (InputEvent) deserializedState.getTaskEvent();
        @SuppressWarnings("unchecked")
        Map<String, Object> deserializedComplexAttr =
                (Map<String, Object>) deserializedInputEvent.getAttr("complexAttr");
        assertNotNull(deserializedComplexAttr);
        assertEquals("value", deserializedComplexAttr.get("nested"));
        assertEquals(42, deserializedComplexAttr.get("number"));
    }

    @Test
    public void testActionStateWithCallResults() throws Exception {
        // Create ActionState with call results
        InputEvent inputEvent = new InputEvent("test input");
        ActionState originalState = new ActionState(inputEvent);

        // Add call results
        CallResult result1 = new CallResult("module.func1", "digest1", "result1".getBytes());
        CallResult result2 =
                new CallResult("module.func2", "digest2", null, "exception".getBytes());
        originalState.addCallResult(result1);
        originalState.addCallResult(result2);

        // Test serialization/deserialization
        byte[] serialized = ActionStateSerde.serialize(originalState);
        ActionState deserializedState = ActionStateSerde.deserialize(serialized);

        // Verify call results
        assertEquals(2, deserializedState.getCallResultCount());

        CallResult deserializedResult1 = deserializedState.getCallResult(0);
        assertEquals("module.func1", deserializedResult1.getFunctionId());
        assertEquals("digest1", deserializedResult1.getArgsDigest());
        assertArrayEquals("result1".getBytes(), deserializedResult1.getResultPayload());
        assertNull(deserializedResult1.getExceptionPayload());
        assertTrue(deserializedResult1.isSuccess());

        CallResult deserializedResult2 = deserializedState.getCallResult(1);
        assertEquals("module.func2", deserializedResult2.getFunctionId());
        assertEquals("digest2", deserializedResult2.getArgsDigest());
        assertNull(deserializedResult2.getResultPayload());
        assertArrayEquals("exception".getBytes(), deserializedResult2.getExceptionPayload());
        assertTrue(deserializedResult2.isFailure());
    }

    @Test
    public void testActionStateWithPendingCallResult() throws Exception {
        InputEvent inputEvent = new InputEvent("test input");
        ActionState originalState = new ActionState(inputEvent);
        originalState.addCallResult(CallResult.pending("module.func", "digest"));

        byte[] serialized = ActionStateSerde.serialize(originalState);
        ActionState deserializedState = ActionStateSerde.deserialize(serialized);

        assertEquals(1, deserializedState.getCallResultCount());
        CallResult result = deserializedState.getCallResult(0);
        assertTrue(result.isPending());
        assertNull(result.getResultPayload());
        assertNull(result.getExceptionPayload());
    }

    @Test
    public void testActionStateWithCompletedFlag() throws Exception {
        // Create completed ActionState
        InputEvent inputEvent = new InputEvent("test input");
        List<MemoryUpdate> sensoryUpdates = new ArrayList<>();
        sensoryUpdates.add(new MemoryUpdate("sm.path", "value"));
        List<MemoryUpdate> shortTermUpdates = new ArrayList<>();
        shortTermUpdates.add(new MemoryUpdate("stm.path", "value"));
        List<Event> outputEvents = new ArrayList<>();
        outputEvents.add(new OutputEvent("output"));

        // Create with completed = true and empty callResults (simulating markCompleted)
        ActionState originalState =
                new ActionState(
                        inputEvent, sensoryUpdates, shortTermUpdates, outputEvents, null, true);

        // Test serialization/deserialization
        byte[] serialized = ActionStateSerde.serialize(originalState);
        ActionState deserializedState = ActionStateSerde.deserialize(serialized);

        // Verify completed flag
        assertTrue(deserializedState.isCompleted());
        assertEquals(0, deserializedState.getCallResultCount());

        // Verify other fields preserved
        assertEquals(1, deserializedState.getSensoryMemoryUpdates().size());
        assertEquals(1, deserializedState.getShortTermMemoryUpdates().size());
        assertEquals(1, deserializedState.getOutputEvents().size());
    }

    @Test
    public void testActionStateInProgressWithCallResults() throws Exception {
        // Create in-progress ActionState with call results (simulating partial execution)
        InputEvent inputEvent = new InputEvent("test input");
        List<CallResult> callResults = new ArrayList<>();
        callResults.add(new CallResult("func1", "hash1", "result1".getBytes()));
        callResults.add(new CallResult("func2", "hash2", "result2".getBytes()));

        ActionState originalState =
                new ActionState(inputEvent, null, null, null, callResults, false);

        // Test serialization/deserialization
        byte[] serialized = ActionStateSerde.serialize(originalState);
        ActionState deserializedState = ActionStateSerde.deserialize(serialized);

        // Verify state
        assertFalse(deserializedState.isCompleted());
        assertEquals(2, deserializedState.getCallResultCount());
        assertTrue(deserializedState.getCallResult(0).matches("func1", "hash1"));
        assertTrue(deserializedState.getCallResult(1).matches("func2", "hash2"));
    }

    @Test
    public void testCallResultWithNullPayloads() throws Exception {
        // Test CallResult with null payloads
        InputEvent inputEvent = new InputEvent("test");
        ActionState originalState = new ActionState(inputEvent);
        originalState.addCallResult(new CallResult("func", "digest", null, null));

        byte[] serialized = ActionStateSerde.serialize(originalState);
        ActionState deserializedState = ActionStateSerde.deserialize(serialized);

        assertEquals(1, deserializedState.getCallResultCount());
        CallResult result = deserializedState.getCallResult(0);
        assertEquals("func", result.getFunctionId());
        assertEquals("digest", result.getArgsDigest());
        assertNull(result.getResultPayload());
        assertNull(result.getExceptionPayload());
        assertTrue(result.isSuccess());
    }

    @Test
    public void testDeserializeLegacyCallResultWithoutStatus() throws Exception {
        // Legacy JSON sample: unlike current serializer output, CallResult entries do not include
        // `status`.
        String legacySuccessPayload =
                Base64.getEncoder().encodeToString("result".getBytes(StandardCharsets.UTF_8));
        String legacyFailurePayload =
                Base64.getEncoder().encodeToString("exception".getBytes(StandardCharsets.UTF_8));
        String json =
                "{"
                        + "\"taskEvent\":null,"
                        + "\"sensoryMemoryUpdates\":[],"
                        + "\"shortTermMemoryUpdates\":[],"
                        + "\"outputEvents\":[],"
                        + "\"callResults\":["
                        + "{"
                        + "\"functionId\":\"legacy.success\","
                        + "\"argsDigest\":\"digest-success\","
                        + "\"resultPayload\":\""
                        + legacySuccessPayload
                        + "\","
                        + "\"exceptionPayload\":null"
                        + "},"
                        + "{"
                        + "\"functionId\":\"legacy.failure\","
                        + "\"argsDigest\":\"digest-failure\","
                        + "\"resultPayload\":null,"
                        + "\"exceptionPayload\":\""
                        + legacyFailurePayload
                        + "\""
                        + "}"
                        + "],"
                        + "\"completed\":false"
                        + "}";

        ActionState deserializedState =
                ActionStateSerde.deserialize(json.getBytes(StandardCharsets.UTF_8));

        assertEquals(2, deserializedState.getCallResultCount());

        CallResult legacySuccess = deserializedState.getCallResult(0);
        assertTrue(legacySuccess.isSuccess());
        assertArrayEquals(
                "result".getBytes(StandardCharsets.UTF_8), legacySuccess.getResultPayload());

        CallResult legacyFailure = deserializedState.getCallResult(1);
        assertTrue(legacyFailure.isFailure());
        assertArrayEquals(
                "exception".getBytes(StandardCharsets.UTF_8), legacyFailure.getExceptionPayload());
    }

    @Test
    public void testByteArrayMemoryValuePreserved() throws Exception {
        byte[] value = new byte[] {1, 2, 3, 4, 5};
        ActionState originalState = new ActionState(new InputEvent("in"));
        originalState.addShortTermMemoryUpdate(new MemoryUpdate("stm.bytes", value));

        ActionState deserializedState =
                ActionStateSerde.deserialize(ActionStateSerde.serialize(originalState));

        Object recovered = deserializedState.getShortTermMemoryUpdates().get(0).getValue();
        assertInstanceOf(byte[].class, recovered);
        assertArrayEquals(value, (byte[]) recovered);
    }

    @Test
    public void testLongMemoryValuePreserved() throws Exception {
        Long value = 42L;
        ActionState originalState = new ActionState(new InputEvent("in"));
        originalState.addShortTermMemoryUpdate(new MemoryUpdate("stm.long", value));

        ActionState deserializedState =
                ActionStateSerde.deserialize(ActionStateSerde.serialize(originalState));

        Object recovered = deserializedState.getShortTermMemoryUpdates().get(0).getValue();
        assertInstanceOf(Long.class, recovered);
        assertEquals(42L, recovered);
    }

    @Test
    public void testNestedCollectionMemoryValuePreserved() throws Exception {
        byte[] nestedBytes = new byte[] {9, 8, 7};
        List<Object> innerList = new ArrayList<>();
        innerList.add(nestedBytes);
        innerList.add(7L);
        Map<String, Object> value = new HashMap<>();
        value.put("items", innerList);

        ActionState originalState = new ActionState(new InputEvent("in"));
        originalState.addShortTermMemoryUpdate(new MemoryUpdate("stm.nested", value));

        ActionState deserializedState =
                ActionStateSerde.deserialize(ActionStateSerde.serialize(originalState));

        Object recovered = deserializedState.getShortTermMemoryUpdates().get(0).getValue();
        assertInstanceOf(Map.class, recovered);
        @SuppressWarnings("unchecked")
        List<Object> recoveredList = (List<Object>) ((Map<String, Object>) recovered).get("items");
        assertInstanceOf(byte[].class, recoveredList.get(0));
        assertArrayEquals(nestedBytes, (byte[]) recoveredList.get(0));
        assertInstanceOf(Long.class, recoveredList.get(1));
        assertEquals(7L, recoveredList.get(1));
    }

    @Test
    public void testPojoMemoryValuePreserved() throws Exception {
        MemoryValuePojo value = new MemoryValuePojo("hello", 99);
        ActionState originalState = new ActionState(new InputEvent("in"));
        originalState.addShortTermMemoryUpdate(new MemoryUpdate("stm.pojo", value));

        ActionState deserializedState =
                ActionStateSerde.deserialize(ActionStateSerde.serialize(originalState));

        Object recovered = deserializedState.getShortTermMemoryUpdates().get(0).getValue();
        assertInstanceOf(MemoryValuePojo.class, recovered);
        assertEquals(value, recovered);
    }

    @Test
    public void testNullMemoryValuePreserved() throws Exception {
        ActionState originalState = new ActionState(new InputEvent("in"));
        originalState.addShortTermMemoryUpdate(new MemoryUpdate("stm.null", null));

        ActionState deserializedState =
                ActionStateSerde.deserialize(ActionStateSerde.serialize(originalState));

        assertEquals(1, deserializedState.getShortTermMemoryUpdates().size());
        assertNull(deserializedState.getShortTermMemoryUpdates().get(0).getValue());
    }

    @Test
    public void testNonEnvelopeMemoryValueRejected() throws Exception {
        ActionState originalState = new ActionState(new InputEvent("in"));
        originalState.addShortTermMemoryUpdate(new MemoryUpdate("stm.raw", "some value"));

        byte[] serialized = ActionStateSerde.serialize(originalState);
        String json = new String(serialized, StandardCharsets.UTF_8);
        // Replace the whole envelope object with a bare JSON value, as a legacy pre-envelope
        // journal would have stored it.
        int start = json.indexOf("{\"serde\":");
        int end = json.indexOf('}', start) + 1;
        byte[] patched =
                (json.substring(0, start) + "\"some value\"" + json.substring(end))
                        .getBytes(StandardCharsets.UTF_8);

        assertThrows(RuntimeException.class, () -> ActionStateSerde.deserialize(patched));
    }

    @Test
    public void testUnknownEnvelopeVersionRejected() throws Exception {
        ActionState originalState = new ActionState(new InputEvent("in"));
        originalState.addShortTermMemoryUpdate(new MemoryUpdate("stm.version", "some value"));

        byte[] serialized = ActionStateSerde.serialize(originalState);
        String json = new String(serialized, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"version\":1"));
        byte[] patched =
                json.replace("\"version\":1", "\"version\":2").getBytes(StandardCharsets.UTF_8);

        assertThrows(RuntimeException.class, () -> ActionStateSerde.deserialize(patched));
    }

    @Test
    public void testMemoryEventsSurviveActionStateRoundTrip() throws Exception {
        ActionState state = new ActionState(new InputEvent("test input"));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("bytes", new byte[] {1, 2, 3});
        value.put("pojo", new ObservationValuePojo("hello", 7));
        state.addEvent(new ShortTermWriteEvent("user-42", value));
        state.addEvent(new AgentRunBeginEvent("user-42", value));

        MemoryEvent liveMemory = (MemoryEvent) state.getOutputEvents().get(0);
        AgentRunBeginEvent liveRunBegin = (AgentRunBeginEvent) state.getOutputEvents().get(1);

        ActionState restored = ActionStateSerde.deserialize(ActionStateSerde.serialize(state));

        assertEquals(2, restored.getOutputEvents().size());
        MemoryEvent memory = (MemoryEvent) restored.getOutputEvents().get(0);
        AgentRunBeginEvent runBegin = (AgentRunBeginEvent) restored.getOutputEvents().get(1);

        assertEquals(liveMemory.getValue(), memory.getValue());
        assertEquals(liveRunBegin.getValue(), runBegin.getValue());
        assertEquals("AQID", memory.getValue().get("bytes"));
        assertEquals(Map.of("name", "hello", "count", 7), memory.getValue().get("pojo"));
        assertEquals("AQID", runBegin.getValue().get("bytes"));
        assertEquals(Map.of("name", "hello", "count", 7), runBegin.getValue().get("pojo"));
    }

    public static class ObservationValuePojo {
        private final String name;
        private final int count;

        public ObservationValuePojo(String name, int count) {
            this.name = name;
            this.count = count;
        }

        public String getName() {
            return name;
        }

        public int getCount() {
            return count;
        }
    }

    /** Serializable POJO used to verify Kryo preserves user types across durable recovery. */
    public static class MemoryValuePojo implements java.io.Serializable {
        private String name;
        private int count;

        public MemoryValuePojo() {}

        public MemoryValuePojo(String name, int count) {
            this.name = name;
            this.count = count;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MemoryValuePojo)) return false;
            MemoryValuePojo that = (MemoryValuePojo) o;
            return count == that.count && java.util.Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(name, count);
        }
    }

    @Test
    public void testKafkaSederDelegatesToActionStateSerde() throws Exception {
        InputEvent inputEvent = new InputEvent("test delegation");
        ActionState originalState = new ActionState(inputEvent);

        // Serialize via ActionStateSerde, deserialize via Kafka seder (and vice versa)
        ActionStateKafkaSeder kafkaSeder = new ActionStateKafkaSeder();

        byte[] serializedBySerde = ActionStateSerde.serialize(originalState);
        byte[] serializedByKafka = kafkaSeder.serialize("test-topic", originalState);

        ActionState fromSerde = ActionStateSerde.deserialize(serializedByKafka);
        ActionState fromKafka = kafkaSeder.deserialize("test-topic", serializedBySerde);

        // Both should produce identical results
        assertArrayEquals(serializedBySerde, serializedByKafka);
        assertEquals(
                ((InputEvent) fromSerde.getTaskEvent()).getInput(),
                ((InputEvent) fromKafka.getTaskEvent()).getInput());

        // Kafka seder should handle nulls
        assertNull(kafkaSeder.serialize("test-topic", null));
        assertNull(kafkaSeder.deserialize("test-topic", null));
    }

    @Test
    public void testBuiltInEventSerDeRoundTrip() throws Exception {
        ChatMessage msg = new ChatMessage(MessageRole.USER, "hello");
        UUID requestId = UUID.randomUUID();
        Document doc = new Document("doc content", Map.of("source", "unit-test"), "doc-1");

        // Built-in events are persisted both as the triggering taskEvent and as
        // outputEvents; cover both paths.
        ActionState originalState = new ActionState(new ChatRequestEvent("myModel", List.of(msg)));
        originalState.addEvent(new ChatRequestEvent("myModel", List.of(msg)));
        originalState.addEvent(new ChatResponseEvent(requestId, msg));
        originalState.addEvent(new ToolRequestEvent("myModel", List.of(Map.of("name", "myTool"))));
        originalState.addEvent(
                new ToolResponseEvent(
                        requestId,
                        Map.of("call-1", ToolResponse.success("result")),
                        Map.of("call-1", true),
                        Map.of()));
        originalState.addEvent(new ContextRetrievalRequestEvent("query text", "myVectorStore", 5));
        originalState.addEvent(
                new ContextRetrievalResponseEvent(requestId, "query text", List.of(doc)));

        byte[] serialized = ActionStateSerde.serialize(originalState);
        ActionState deserializedState = ActionStateSerde.deserialize(serialized);

        assertEquals(ChatRequestEvent.class, deserializedState.getTaskEvent().getClass());

        List<Event> outputEvents = deserializedState.getOutputEvents();
        assertEquals(6, outputEvents.size());
        assertEquals(ChatRequestEvent.class, outputEvents.get(0).getClass());
        assertEquals(ChatResponseEvent.class, outputEvents.get(1).getClass());
        assertEquals(ToolRequestEvent.class, outputEvents.get(2).getClass());
        assertEquals(ToolResponseEvent.class, outputEvents.get(3).getClass());
        assertEquals(ContextRetrievalRequestEvent.class, outputEvents.get(4).getClass());
        assertEquals(ContextRetrievalResponseEvent.class, outputEvents.get(5).getClass());

        // Typed getters must return typed values directly on the deserialized events.
        ChatRequestEvent chatRequest = (ChatRequestEvent) outputEvents.get(0);
        assertEquals("hello", chatRequest.getMessages().get(0).getContent());
        assertEquals(MessageRole.USER, chatRequest.getMessages().get(0).getRole());

        ChatResponseEvent chatResponse = (ChatResponseEvent) outputEvents.get(1);
        assertEquals(requestId, chatResponse.getRequestId());
        assertEquals("hello", chatResponse.getResponse().getContent());

        ToolResponseEvent toolResponse = (ToolResponseEvent) outputEvents.get(3);
        assertEquals(requestId, toolResponse.getRequestId());
        assertEquals("result", toolResponse.getResponses().get("call-1").getResult());

        ContextRetrievalResponseEvent retrievalResponse =
                (ContextRetrievalResponseEvent) outputEvents.get(5);
        assertEquals(requestId, retrievalResponse.getRequestId());
        assertEquals("doc content", retrievalResponse.getDocuments().get(0).getContent());
        assertEquals("doc-1", retrievalResponse.getDocuments().get(0).getId());
    }
}
