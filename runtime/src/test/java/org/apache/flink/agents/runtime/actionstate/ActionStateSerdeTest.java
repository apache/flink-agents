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
import org.apache.flink.agents.api.context.MemoryUpdate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        // Test Kafka seder/deserializer
        ActionStateKafkaSeder seder = new ActionStateKafkaSeder();

        // Serialize
        byte[] serialized = seder.serialize("test-topic", originalState);
        assertNotNull(serialized);
        assertTrue(serialized.length > 0);

        // Deserialize
        ActionState deserializedState = seder.deserialize("test-topic", serialized);
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
        ActionStateKafkaSeder seder = new ActionStateKafkaSeder();

        byte[] serialized = seder.serialize("test-topic", originalState);
        ActionState deserializedState = seder.deserialize("test-topic", serialized);

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
        ActionStateKafkaSeder seder = new ActionStateKafkaSeder();

        byte[] serialized = seder.serialize("test-topic", originalState);
        ActionState deserializedState = seder.deserialize("test-topic", serialized);

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
                CallResult.ofException("module.func2", "digest2", "exception".getBytes());
        originalState.addCallResult(result1);
        originalState.addCallResult(result2);

        // Test serialization/deserialization
        ActionStateKafkaSeder seder = new ActionStateKafkaSeder();

        byte[] serialized = seder.serialize("test-topic", originalState);
        ActionState deserializedState = seder.deserialize("test-topic", serialized);

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
        assertFalse(deserializedResult2.isSuccess());
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
        ActionStateKafkaSeder seder = new ActionStateKafkaSeder();

        byte[] serialized = seder.serialize("test-topic", originalState);
        ActionState deserializedState = seder.deserialize("test-topic", serialized);

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
        ActionStateKafkaSeder seder = new ActionStateKafkaSeder();

        byte[] serialized = seder.serialize("test-topic", originalState);
        ActionState deserializedState = seder.deserialize("test-topic", serialized);

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

        ActionStateKafkaSeder seder = new ActionStateKafkaSeder();

        byte[] serialized = seder.serialize("test-topic", originalState);
        ActionState deserializedState = seder.deserialize("test-topic", serialized);

        assertEquals(1, deserializedState.getCallResultCount());
        CallResult result = deserializedState.getCallResult(0);
        assertEquals("func", result.getFunctionId());
        assertEquals("digest", result.getArgsDigest());
        assertNull(result.getResultPayload());
        assertNull(result.getExceptionPayload());
    }
}
