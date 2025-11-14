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

import java.util.HashMap;
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
        ActionState originalState = new ActionState();
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
}
