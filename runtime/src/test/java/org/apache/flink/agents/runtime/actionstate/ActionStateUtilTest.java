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
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.Action;
import org.apache.flink.agents.plan.JavaFunction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test class for {@link ActionStateUtil}. */
public class ActionStateUtilTest {

    @Test
    public void testGenerateKeyConsistency() throws Exception {
        // Create test data
        Object key = "consistency-test";
        Action action = new TestAction("consistency-action");
        InputEvent inputEvent = new InputEvent("same-input");
        InputEvent inputEvent2 = new InputEvent("same-input");

        // Generate keys multiple times
        String key1 = ActionStateUtil.generateKey(key, 1, action, inputEvent);
        String key2 = ActionStateUtil.generateKey(key, 1, action, inputEvent2);

        // Keys should be the same for the same input
        assertEquals(key1, key2);
    }

    @Test
    public void testGenerateKeyDifferentInputs() throws Exception {
        // Create test data
        Object key = "diff-test";
        Action action = new TestAction("diff-action");
        InputEvent inputEvent1 = new InputEvent("input1");
        InputEvent inputEvent2 = new InputEvent("input2");

        // Generate keys
        String key1 = ActionStateUtil.generateKey(key, 1, action, inputEvent1);
        String key2 = ActionStateUtil.generateKey(key, 1, action, inputEvent2);

        // Keys should be different for different inputs
        assertNotEquals(key1, key2);
    }

    @Test
    public void testGenerateKeyWithNullKey() throws Exception {
        Action action = new TestAction("test-action");
        InputEvent inputEvent = new InputEvent("test-input");

        assertThrows(
                NullPointerException.class,
                () -> {
                    ActionStateUtil.generateKey(null, 1, action, inputEvent);
                });
    }

    @Test
    public void testGenerateKeyWithNullAction() {
        Object key = "test-key";
        InputEvent inputEvent = new InputEvent("test-input");

        assertThrows(
                NullPointerException.class,
                () -> {
                    ActionStateUtil.generateKey(key, 1, null, inputEvent);
                });
    }

    @Test
    public void testGenerateKeyWithNullEvent() throws Exception {
        Object key = "test-key";
        Action action = new TestAction("test-action");

        assertThrows(
                NullPointerException.class,
                () -> {
                    ActionStateUtil.generateKey(key, 1, action, null);
                });
    }

    @Test
    public void testParseKeyValidKey() throws Exception {
        // Create test data and generate a key
        Object key = "test-key";
        Action action = new TestAction("test-action");
        InputEvent inputEvent = new InputEvent("test-input");
        long seqNum = 123;

        String generatedKey = ActionStateUtil.generateKey(key, seqNum, action, inputEvent);

        // Parse the generated key
        List<String> parsedParts = ActionStateUtil.parseKey(generatedKey);

        // Verify the parsed components
        assertEquals(4, parsedParts.size());
        assertEquals(key.toString(), parsedParts.get(0));
        assertEquals(String.valueOf(seqNum), parsedParts.get(1));
        // The third and fourth parts are UUIDs - just verify they're non-empty
        assertTrue(parsedParts.get(2).length() > 0);
        assertTrue(parsedParts.get(3).length() > 0);
    }

    @Test
    public void testParseKeyRoundTrip() throws Exception {
        // Test that generate -> parse -> values match original inputs
        Object originalKey = "round-trip-test";
        Action action = new TestAction("round-trip-action");
        InputEvent inputEvent = new InputEvent("round-trip-input");
        long seqNum = 456;

        String generatedKey = ActionStateUtil.generateKey(originalKey, seqNum, action, inputEvent);
        List<String> parsedParts = ActionStateUtil.parseKey(generatedKey);

        assertEquals(originalKey.toString(), parsedParts.get(0));
        assertEquals(String.valueOf(seqNum), parsedParts.get(1));
    }

    @Test
    public void testParseKeyWithNullInput() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    ActionStateUtil.parseKey(null);
                });
    }

    @Test
    public void testParseKeyWithInvalidFormat() {
        // Test with too few parts
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    ActionStateUtil.parseKey("only_three_parts");
                });

        // Test with too many parts
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    ActionStateUtil.parseKey("one_two_three_four_five_six");
                });

        // Test with empty string
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    ActionStateUtil.parseKey("");
                });
    }

    @Test
    public void testParseKeyWithSpecialCharacters() throws Exception {
        // Test with keys containing special characters (but not the separator)
        Object key = "key-with-special@chars#123";
        Action action = new TestAction("action-with-special@chars");
        InputEvent inputEvent = new InputEvent("input-with-special@chars");
        long seqNum = 789;

        String generatedKey = ActionStateUtil.generateKey(key, seqNum, action, inputEvent);
        List<String> parsedParts = ActionStateUtil.parseKey(generatedKey);

        assertEquals(key.toString(), parsedParts.get(0));
        assertEquals(String.valueOf(seqNum), parsedParts.get(1));
    }

    @Test
    public void testParseKeyConsistencyWithDifferentKeys() throws Exception {
        // Generate keys with different inputs and verify parsing consistency
        Action action = new TestAction("consistency-action");
        InputEvent inputEvent = new InputEvent("consistency-input");

        String key1 = ActionStateUtil.generateKey("key1", 100, action, inputEvent);
        String key2 = ActionStateUtil.generateKey("key2", 200, action, inputEvent);

        List<String> parsed1 = ActionStateUtil.parseKey(key1);
        List<String> parsed2 = ActionStateUtil.parseKey(key2);

        // Keys should be different
        assertNotEquals(parsed1.get(0), parsed2.get(0));
        assertNotEquals(parsed1.get(1), parsed2.get(1));

        // But event and action UUIDs should be the same (same event and action)
        assertEquals(parsed1.get(2), parsed2.get(2)); // Event UUID
        assertEquals(parsed1.get(3), parsed2.get(3)); // Action UUID
    }

    private static class TestAction extends Action {

        public static void doNothing(Event event, RunnerContext context) {
            // No operation
        }

        public TestAction(String name) throws Exception {
            super(
                    name,
                    new JavaFunction(
                            TestAction.class.getName(),
                            "doNothing",
                            new Class[] {Event.class, RunnerContext.class}),
                    List.of(InputEvent.class.getName()));
        }
    }
}
