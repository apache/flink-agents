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

import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.context.MemoryUpdate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link ActionState} with focus on fine-grained durable execution fields. */
public class ActionStateTest {

    @Test
    public void testConstructorWithEvent() {
        InputEvent event = new InputEvent("test");
        ActionState state = new ActionState(event);

        assertEquals(event, state.getTaskEvent());
        assertTrue(state.getSensoryMemoryUpdates().isEmpty());
        assertTrue(state.getShortTermMemoryUpdates().isEmpty());
        assertTrue(state.getOutputEvents().isEmpty());
        assertTrue(state.getCallResults().isEmpty());
        assertFalse(state.isCompleted());
    }

    @Test
    public void testFullConstructorWithCallResults() {
        InputEvent taskEvent = new InputEvent("test");
        List<MemoryUpdate> sensoryUpdates = new ArrayList<>();
        sensoryUpdates.add(new MemoryUpdate("sm.path", "value"));
        List<MemoryUpdate> shortTermUpdates = new ArrayList<>();
        shortTermUpdates.add(new MemoryUpdate("stm.path", "value"));
        List<org.apache.flink.agents.api.Event> outputEvents = new ArrayList<>();
        outputEvents.add(new OutputEvent("output"));
        List<CallResult> callResults = new ArrayList<>();
        callResults.add(new CallResult("func1", "digest1", "result1".getBytes()));
        callResults.add(new CallResult("func2", "digest2", "result2".getBytes()));
        boolean completed = true;

        ActionState state =
                new ActionState(
                        taskEvent,
                        sensoryUpdates,
                        shortTermUpdates,
                        outputEvents,
                        callResults,
                        completed);

        assertEquals(taskEvent, state.getTaskEvent());
        assertEquals(1, state.getSensoryMemoryUpdates().size());
        assertEquals(1, state.getShortTermMemoryUpdates().size());
        assertEquals(1, state.getOutputEvents().size());
        assertEquals(2, state.getCallResults().size());
        assertTrue(state.isCompleted());
    }

    @Test
    public void testAddCallResult() {
        ActionState state = new ActionState(new InputEvent("test"));

        CallResult result1 = new CallResult("func1", "digest1", "result1".getBytes());
        CallResult result2 = new CallResult("func2", "digest2", "result2".getBytes());

        state.addCallResult(result1);
        assertEquals(1, state.getCallResultCount());
        assertEquals(result1, state.getCallResult(0));

        state.addCallResult(result2);
        assertEquals(2, state.getCallResultCount());
        assertEquals(result2, state.getCallResult(1));
    }

    @Test
    public void testGetCallResultOutOfBounds() {
        ActionState state = new ActionState(new InputEvent("test"));

        assertNull(state.getCallResult(-1));
        assertNull(state.getCallResult(0));
        assertNull(state.getCallResult(100));

        state.addCallResult(new CallResult("func", "digest", "result".getBytes()));
        assertNull(state.getCallResult(1));
        assertNotNull(state.getCallResult(0));
    }

    @Test
    public void testClearCallResults() {
        ActionState state = new ActionState(new InputEvent("test"));
        state.addCallResult(new CallResult("func1", "digest1", "result1".getBytes()));
        state.addCallResult(new CallResult("func2", "digest2", "result2".getBytes()));
        assertEquals(2, state.getCallResultCount());

        state.clearCallResults();
        assertEquals(0, state.getCallResultCount());
        assertTrue(state.getCallResults().isEmpty());
    }

    @Test
    public void testClearCallResultsFrom() {
        ActionState state = new ActionState(new InputEvent("test"));
        state.addCallResult(new CallResult("func0", "digest0", "result0".getBytes()));
        state.addCallResult(new CallResult("func1", "digest1", "result1".getBytes()));
        state.addCallResult(new CallResult("func2", "digest2", "result2".getBytes()));
        state.addCallResult(new CallResult("func3", "digest3", "result3".getBytes()));
        assertEquals(4, state.getCallResultCount());

        // Clear from index 2 onwards (keep func0, func1)
        state.clearCallResultsFrom(2);

        assertEquals(2, state.getCallResultCount());
        assertEquals("func0", state.getCallResult(0).getFunctionId());
        assertEquals("func1", state.getCallResult(1).getFunctionId());
    }

    @Test
    public void testClearCallResultsFromInvalidIndex() {
        ActionState state = new ActionState(new InputEvent("test"));
        state.addCallResult(new CallResult("func", "digest", "result".getBytes()));

        // Negative index - should do nothing
        state.clearCallResultsFrom(-1);
        assertEquals(1, state.getCallResultCount());

        // Out of bounds index - should do nothing
        state.clearCallResultsFrom(10);
        assertEquals(1, state.getCallResultCount());
    }

    @Test
    public void testClearCallResultsFromZero() {
        ActionState state = new ActionState(new InputEvent("test"));
        state.addCallResult(new CallResult("func1", "digest1", "result1".getBytes()));
        state.addCallResult(new CallResult("func2", "digest2", "result2".getBytes()));

        // Clear from index 0 - should clear all
        state.clearCallResultsFrom(0);
        assertEquals(0, state.getCallResultCount());
    }

    @Test
    public void testMarkCompleted() {
        ActionState state = new ActionState(new InputEvent("test"));
        state.addCallResult(new CallResult("func1", "digest1", "result1".getBytes()));
        state.addCallResult(new CallResult("func2", "digest2", "result2".getBytes()));

        assertFalse(state.isCompleted());
        assertEquals(2, state.getCallResultCount());

        state.markCompleted();

        assertTrue(state.isCompleted());
        assertEquals(0, state.getCallResultCount());
    }

    @Test
    public void testEqualsWithCallResultsAndCompleted() {
        InputEvent event = new InputEvent("test");
        List<CallResult> callResults1 = new ArrayList<>();
        callResults1.add(new CallResult("func", "digest", "result".getBytes()));

        List<CallResult> callResults2 = new ArrayList<>();
        callResults2.add(new CallResult("func", "digest", "result".getBytes()));

        ActionState state1 = new ActionState(event, null, null, null, callResults1, true);
        ActionState state2 = new ActionState(event, null, null, null, callResults2, true);
        ActionState state3 = new ActionState(event, null, null, null, callResults1, false);

        assertEquals(state1, state2);
        assertNotEquals(state1, state3); // Different completed flag
    }

    @Test
    public void testHashCodeWithCallResultsAndCompleted() {
        InputEvent event = new InputEvent("test");
        List<CallResult> callResults = new ArrayList<>();
        callResults.add(new CallResult("func", "digest", "result".getBytes()));

        ActionState state1 = new ActionState(event, null, null, null, callResults, true);
        ActionState state2 =
                new ActionState(event, null, null, null, new ArrayList<>(callResults), true);

        assertEquals(state1.hashCode(), state2.hashCode());
    }

    @Test
    public void testToStringIncludesNewFields() {
        ActionState state = new ActionState(new InputEvent("test"));
        state.addCallResult(new CallResult("func", "digest", "result".getBytes()));
        state.markCompleted();

        String str = state.toString();

        assertTrue(str.contains("callResults"));
        assertTrue(str.contains("completed=true"));
    }

    @Test
    public void testNullListsInFullConstructor() {
        ActionState state = new ActionState(null, null, null, null, null, false);

        assertNull(state.getTaskEvent());
        assertNotNull(state.getSensoryMemoryUpdates());
        assertNotNull(state.getShortTermMemoryUpdates());
        assertNotNull(state.getOutputEvents());
        assertNotNull(state.getCallResults());
        assertTrue(state.getSensoryMemoryUpdates().isEmpty());
        assertTrue(state.getShortTermMemoryUpdates().isEmpty());
        assertTrue(state.getOutputEvents().isEmpty());
        assertTrue(state.getCallResults().isEmpty());
    }

    @Test
    public void testIntegrationScenario() {
        // Simulate a typical fine-grained durable execution flow

        // 1. Create initial state
        ActionState state = new ActionState(new InputEvent("test"));
        assertFalse(state.isCompleted());
        assertEquals(0, state.getCallResultCount());

        // 2. First code block completes
        CallResult result1 = new CallResult("llm.call", "hash1", "response1".getBytes());
        state.addCallResult(result1);
        assertEquals(1, state.getCallResultCount());
        assertFalse(state.isCompleted());

        // 3. Second code block completes
        CallResult result2 = new CallResult("db.query", "hash2", "data".getBytes());
        state.addCallResult(result2);
        assertEquals(2, state.getCallResultCount());

        // 4. Action completes - mark completed and clear results
        state.addSensoryMemoryUpdate(new MemoryUpdate("sm.key", "value"));
        state.addShortTermMemoryUpdate(new MemoryUpdate("stm.key", "value"));
        state.addEvent(new OutputEvent("final_output"));
        state.markCompleted();

        assertTrue(state.isCompleted());
        assertEquals(0, state.getCallResultCount()); // Results cleared
        assertEquals(1, state.getSensoryMemoryUpdates().size()); // Memory preserved
        assertEquals(1, state.getShortTermMemoryUpdates().size());
        assertEquals(1, state.getOutputEvents().size()); // Events preserved
    }

    @Test
    public void testRecoveryScenario() {
        // Simulate recovery scenario where we need to check call results

        // State from before failure (with 2 completed code blocks)
        ActionState recoveredState = new ActionState(new InputEvent("test"));
        recoveredState.addCallResult(new CallResult("func1", "digest1", "result1".getBytes()));
        recoveredState.addCallResult(new CallResult("func2", "digest2", "result2".getBytes()));

        // Check if action is completed
        assertFalse(recoveredState.isCompleted());

        // During re-execution, check if call result matches
        CallResult result0 = recoveredState.getCallResult(0);
        assertTrue(result0.matches("func1", "digest1"));
        assertTrue(result0.isSuccess());

        CallResult result1 = recoveredState.getCallResult(1);
        assertTrue(result1.matches("func2", "digest2"));

        // Third call is new (not in results)
        assertNull(recoveredState.getCallResult(2));
    }

    @Test
    public void testNonDeterministicRecovery() {
        // Simulate detection of non-deterministic call order
        ActionState state = new ActionState(new InputEvent("test"));
        state.addCallResult(new CallResult("func1", "digest1", "result1".getBytes()));
        state.addCallResult(new CallResult("func2", "digest2", "result2".getBytes()));
        state.addCallResult(new CallResult("func3", "digest3", "result3".getBytes()));

        // During recovery, call 1 matches
        CallResult result0 = state.getCallResult(0);
        assertTrue(result0.matches("func1", "digest1"));

        // Call 2 doesn't match (different function called)
        CallResult result1 = state.getCallResult(1);
        assertFalse(result1.matches("different_func", "digest2"));

        // Clear results from index 1 onwards
        state.clearCallResultsFrom(1);
        assertEquals(1, state.getCallResultCount());
        assertEquals("func1", state.getCallResult(0).getFunctionId());
    }
}
