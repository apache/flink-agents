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
package org.apache.flink.agents.runtime.operator;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.InMemoryActionStateStore;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.runtime.state.hashmap.HashMapStateBackend;
import org.apache.flink.state.rocksdb.EmbeddedRocksDBStateBackend;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies, against real keyed state and across a snapshot / restore cycle, that a write below a
 * value field is rejected without side effects, and that an action which catches such a rejection
 * can be replayed from its recorded memory updates on recovery.
 */
public class MemoryWriteBelowValueRecoveryTest {

    private static final KeySelector<Long, Long> CONSTANT_KEY = value -> 0L;

    private static final long SET_VALUE = 1L;
    private static final long SET_GRANDCHILD_UNDER_VALUE = 2L;
    private static final long NEW_OBJECT_UNDER_VALUE = 3L;
    private static final long INSPECT = 4L;

    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicReference<Throwable> REJECTION = new AtomicReference<>();
    private static final AtomicReference<Map<String, Object>> INSPECTION = new AtomicReference<>();

    /** Test agent; the input value selects the step. */
    public static class WriteBelowValueAgent {
        public static void step(Event event, RunnerContext ctx) throws Exception {
            CALLS.incrementAndGet();
            long step = (Long) InputEvent.fromEvent(event).getInput();
            MemoryObject stm = ctx.getShortTermMemory();
            if (step == SET_VALUE) {
                stm.set("a", 1);
            } else if (step == SET_GRANDCHILD_UNDER_VALUE) {
                try {
                    stm.set("a.b.c", 2);
                } catch (Exception e) {
                    REJECTION.set(e);
                }
            } else if (step == NEW_OBJECT_UNDER_VALUE) {
                try {
                    stm.newObject("a.b");
                } catch (Exception e) {
                    REJECTION.set(e);
                }
            } else if (step == INSPECT) {
                Map<String, Object> seen = new HashMap<>();
                seen.put("fields", stm.getFields());
                seen.put("a.value", stm.get("a").getValue());
                seen.put("a.fields", stm.get("a").getFields());
                seen.put("a.b exists", stm.isExist("a.b"));
                seen.put("a.b.c exists", stm.isExist("a.b.c"));
                INSPECTION.set(seen);
            }
            ctx.sendEvent(new OutputEvent(step));
        }
    }

    @BeforeEach
    void reset() {
        CALLS.set(0);
        REJECTION.set(null);
        INSPECTION.set(null);
    }

    private static AgentPlan plan() throws Exception {
        Action step =
                new Action(
                        "step",
                        new JavaFunction(
                                WriteBelowValueAgent.class,
                                "step",
                                new Class<?>[] {Event.class, RunnerContext.class}),
                        Collections.singletonList(InputEvent.EVENT_TYPE));
        Map<String, Action> actions = new HashMap<>();
        actions.put(step.getName(), step);
        return new AgentPlan(actions, new HashMap<>(), new AgentConfiguration());
    }

    private static KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> openHarness(
            String backendName, InMemoryActionStateStore store, OperatorSubtaskState snapshot)
            throws Exception {
        KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        store == null
                                ? new ActionExecutionOperatorFactory<>(plan(), true)
                                : new ActionExecutionOperatorFactory<>(plan(), true, store),
                        CONSTANT_KEY,
                        TypeInformation.of(Long.class));
        boolean rocksDb = "rocksdb".equals(backendName);
        harness.setStateBackend(
                rocksDb ? new EmbeddedRocksDBStateBackend() : new HashMapStateBackend());
        if (snapshot != null) {
            harness.initializeState(snapshot);
        }
        harness.open();
        assertThat(operator(harness).getKeyedStateBackend().getClass().getSimpleName())
                .isEqualTo(rocksDb ? "RocksDBKeyedStateBackend" : "HeapKeyedStateBackend");
        return harness;
    }

    @SuppressWarnings("unchecked")
    private static ActionExecutionOperator<Long, Object> operator(
            KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness) {
        return (ActionExecutionOperator<Long, Object>) harness.getOperator();
    }

    private static void process(
            KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness, long step)
            throws Exception {
        harness.processElement(new StreamRecord<>(step));
        operator(harness).waitInFlightEventsFinished();
    }

    /** Runs the inspection action and asserts that memory holds only the value {@code a}. */
    private static void assertMemoryHoldsOnlyValueA(
            KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness) throws Exception {
        INSPECTION.set(null);
        process(harness, INSPECT);
        Map<String, Object> expected = new HashMap<>();
        expected.put("fields", Collections.singletonMap("a", 1));
        expected.put("a.value", 1);
        expected.put("a.fields", Collections.emptyMap());
        expected.put("a.b exists", false);
        expected.put("a.b.c exists", false);
        assertThat(INSPECTION.get()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hashmap", "rocksdb"})
    void rejectedWriteBelowValueLeavesNoOrphanAcrossRestore(String backendName) throws Exception {
        OperatorSubtaskState snapshot;
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                openHarness(backendName, null, null)) {
            process(harness, SET_VALUE);

            process(harness, SET_GRANDCHILD_UNDER_VALUE);
            assertThat(REJECTION.get())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Cannot write field 'a.b.c': 'a' exists but is not an object.");
            assertMemoryHoldsOnlyValueA(harness);

            REJECTION.set(null);
            process(harness, NEW_OBJECT_UNDER_VALUE);
            assertThat(REJECTION.get())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Cannot write field 'a.b': 'a' exists but is not an object.");
            assertMemoryHoldsOnlyValueA(harness);

            snapshot = harness.snapshot(1L, 1L);
        }

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                openHarness(backendName, null, snapshot)) {
            assertMemoryHoldsOnlyValueA(harness);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"hashmap", "rocksdb"})
    void replayOfActionThatCaughtRejectedNewObjectCompletesRecovery(String backendName)
            throws Exception {
        InMemoryActionStateStore store = new InMemoryActionStateStore(false);
        OperatorSubtaskState snapshot;
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                openHarness(backendName, store, null)) {
            process(harness, SET_VALUE);
            snapshot = harness.snapshot(1L, 1L);
            // Completes after the checkpoint, so its ActionState survives the "failure" below
            // while the memory state it saw is rolled back to the checkpoint.
            process(harness, NEW_OBJECT_UNDER_VALUE);
            assertThat(REJECTION.get()).as("the write below a value is rejected").isNotNull();
            assertThat(CALLS.get()).isEqualTo(2);
        }

        // Recovery: the completed action is replayed from its recorded memory updates. The
        // rejected newObject must not be among them, otherwise replay throws and the input
        // fails on every recovery attempt.
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                openHarness(backendName, store, snapshot)) {
            assertThatCode(() -> process(harness, NEW_OBJECT_UNDER_VALUE))
                    .as("replaying the completed action must not fail recovery")
                    .doesNotThrowAnyException();
            assertThat(CALLS.get())
                    .as("completed action must be replayed, not re-executed")
                    .isEqualTo(2);
            assertThat(harness.getRecordOutput()).hasSize(1);
            // The inspection is a new input (next sequence number), so it executes rather than
            // being replayed from a retained ActionState.
            assertMemoryHoldsOnlyValueA(harness);
        }
    }
}
