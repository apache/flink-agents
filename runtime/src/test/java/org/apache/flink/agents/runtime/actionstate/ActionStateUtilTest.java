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
        String key1 = ActionStateUtil.generateKey(key, action, inputEvent);
        String key2 = ActionStateUtil.generateKey(key, action, inputEvent2);

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
        String key1 = ActionStateUtil.generateKey(key, action, inputEvent1);
        String key2 = ActionStateUtil.generateKey(key, action, inputEvent2);

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
                    ActionStateUtil.generateKey(null, action, inputEvent);
                });
    }

    @Test
    public void testGenerateKeyWithNullAction() {
        Object key = "test-key";
        InputEvent inputEvent = new InputEvent("test-input");

        assertThrows(
                NullPointerException.class,
                () -> {
                    ActionStateUtil.generateKey(key, null, inputEvent);
                });
    }

    @Test
    public void testGenerateKeyWithNullEvent() throws Exception {
        Object key = "test-key";
        Action action = new TestAction("test-action");

        assertThrows(
                NullPointerException.class,
                () -> {
                    ActionStateUtil.generateKey(key, action, null);
                });
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
