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
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.context.TimerMode;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.actionstate.InMemoryActionStateStore;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.AbstractStreamOperatorTestHarness;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(30)
public class EventTimeTimerTest {
    private static CountDownLatch started;
    private static CountDownLatch release;
    private static final AtomicInteger REGISTRATIONS = new AtomicInteger();

    public static void input(Event event, RunnerContext ctx) throws Exception {
        long command = ((Number) InputEvent.fromEvent(event).getInput()).longValue();
        ctx.getSensoryMemory().set("origin", command);
        if (command == 3) {
            ctx.cancelTimer("timer");
            return;
        }
        if (command == 7) {
            ctx.registerTimer(
                    "timer",
                    Duration.ofMillis(10),
                    new Event("unexpected"),
                    TimerMode.TRIGGER_AFTER_CURRENT_RUN);
        }
        REGISTRATIONS.incrementAndGet();
        Event tick = new Event("tick", Map.of("command", command));
        tick.setSourceTimestamp(999L);
        ctx.registerEventTimeTimer(
                "timer", Instant.ofEpochMilli(command == 2 ? 20 : command == 8 ? 5 : 10), tick);
        if (command == 6) {
            ctx.registerTimer(
                    "timer",
                    Duration.ofMillis(10),
                    new Event("processing"),
                    TimerMode.TRIGGER_AFTER_CURRENT_RUN);
        }
        if (command == 9) {
            ctx.registerTimer(
                    "input-wait",
                    Duration.ofMillis(100),
                    new Event("input-done"),
                    TimerMode.CURRENT_RUN_WAIT_FOR_TRIGGER);
        }
    }

    public static void tick(Event event, RunnerContext ctx) throws Exception {
        assertThat(ctx.getSensoryMemory().isExist("origin")).isFalse();
        long command = ((Number) event.getAttributes().get("command")).longValue();
        if (command == 11) {
            ctx.executeAsync(
                            () -> {
                                started.countDown();
                                if (!release.await(10, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("Test release timed out");
                                }
                                return null;
                            })
                    .await();
        }
        if (command == 4) {
            ctx.registerTimer(
                    "hold",
                    Duration.ofMillis(100),
                    new Event("release"),
                    TimerMode.CURRENT_RUN_WAIT_FOR_TRIGGER);
        } else {
            ctx.sendEvent(new OutputEvent("tick:" + event.getSourceTimestamp()));
        }
        if (command == 5) {
            ctx.registerEventTimeTimer(
                    "due", Instant.ofEpochMilli(9), new Event("tick", Map.of("command", 0)));
            ctx.registerEventTimeTimer(
                    "future", Instant.ofEpochMilli(20), new Event("tick", Map.of("command", 0)));
        }
    }

    public static void output(Event event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent(event.getType()));
    }

    private static KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness(
            boolean parallel) throws Exception {
        return harness(parallel, null);
    }

    private static KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness(
            boolean parallel, InMemoryActionStateStore store) throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(
                new ActionExecutionOperatorFactory<Long, Object>(plan(parallel), true, store),
                value -> 0L,
                Types.LONG);
    }

    private static AgentPlan plan(boolean parallel) throws Exception {
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentExecutionOptions.PARALLEL_EXECUTION_ENABLED, parallel);
        Map<String, Action> actions =
                Map.of(
                        "input", action("input", List.of(InputEvent.EVENT_TYPE)),
                        "tick", action("tick", List.of("tick")),
                        "output",
                                action(
                                        "output",
                                        List.of(
                                                "release",
                                                "input-done",
                                                "processing",
                                                "unexpected")));
        return new AgentPlan(actions, Map.of(), config);
    }

    private static Action action(String method, List<String> types) throws Exception {
        return new Action(
                method,
                new JavaFunction(
                        EventTimeTimerTest.class,
                        method,
                        new Class<?>[] {Event.class, RunnerContext.class}),
                types);
    }

    private static void drain(KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> h)
            throws Exception {
        ((ActionExecutionOperator<?, ?>) h.getOperator()).waitInFlightEventsFinished();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void watermarkWaitsForInputsThenTimerRuns(boolean parallel) throws Exception {
        try (var h = harness(parallel)) {
            h.open();
            h.processElement(new StreamRecord<>(9L, 1));
            CurrentRunTimerTest.pumpUntil(h, () -> h.numProcessingTimeTimers() == 1);
            h.processWatermark(new Watermark(10));
            assertThat(h.getOutput()).isEmpty();
            h.setProcessingTime(100);
            drain(h);
            assertThat(h.getOutput())
                    .containsExactly(
                            new StreamRecord<>("input-done", 1),
                            new StreamRecord<>("tick:10", 10),
                            new Watermark(10));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void holdsSuccessiveWatermarksUntilTimerRunFinishes(boolean parallel) throws Exception {
        try (var h = harness(parallel)) {
            h.open();
            h.processElement(new StreamRecord<>(4L));
            drain(h);
            h.processWatermark(new Watermark(10));
            CurrentRunTimerTest.pumpUntil(h, () -> h.numProcessingTimeTimers() == 1);
            h.processWatermark(new Watermark(20));
            assertThat(h.getOutput()).isEmpty();
            h.setProcessingTime(100);
            drain(h);
            assertThat(h.getOutput())
                    .containsExactly(
                            new StreamRecord<>("release", 10),
                            new Watermark(10),
                            new Watermark(20));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void checksNewlyDueTimersWithTheSameWatermark(boolean parallel) throws Exception {
        try (var h = harness(parallel)) {
            h.open();
            h.processElement(new StreamRecord<>(5L));
            drain(h);
            h.processWatermark(new Watermark(10));
            drain(h);
            assertThat(h.getOutput())
                    .containsExactly(
                            new StreamRecord<>("tick:10", 10),
                            new StreamRecord<>("tick:9", 9),
                            new Watermark(10));
            assertThat(h.numEventTimeTimers()).isEqualTo(1);
            h.processWatermark(new Watermark(20));
            drain(h);
            assertThat(h.getOutput())
                    .containsSubsequence(new StreamRecord<>("tick:20", 20), new Watermark(20));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateTimerFiresWithoutAnotherWatermark(boolean parallel) throws Exception {
        try (var h = harness(parallel)) {
            h.open();
            h.processWatermark(new Watermark(10));
            h.processElement(new StreamRecord<>(8L));
            drain(h);
            CurrentRunTimerTest.pumpUntil(h, () -> h.extractOutputValues().size() == 1);
            drain(h);
            assertThat(h.getOutput())
                    .containsExactly(new Watermark(10), new StreamRecord<>("tick:5", 5));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacementAndCancellationUseOneIdAcrossDomains(boolean parallel) throws Exception {
        try (var h = harness(parallel)) {
            h.open();
            h.processElement(new StreamRecord<>(6L));
            drain(h);
            assertThat(h.numEventTimeTimers()).isZero();
            h.processElement(new StreamRecord<>(7L));
            drain(h);
            assertThat(h.numProcessingTimeTimers()).isZero();
            assertThat(h.numEventTimeTimers()).isEqualTo(1);
            h.processElement(new StreamRecord<>(3L));
            drain(h);
            assertThat(h.numEventTimeTimers()).isZero();
            h.processWatermark(new Watermark(10));
            h.setProcessingTime(10);
            drain(h);
            assertThat(h.extractOutputValues()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void timerOnIdleKeySurvivesCheckpoint(boolean parallel) throws Exception {
        OperatorSubtaskState snapshot;
        try (var h = harness(parallel)) {
            h.open();
            h.processElement(new StreamRecord<>(1L));
            drain(h);
            h.setProcessingTime(100);
            assertThat(h.extractOutputValues()).isEmpty();
            snapshot = h.snapshot(1, 100);
        }
        try (var h = harness(parallel)) {
            h.initializeState(snapshot);
            h.open();
            h.processWatermark(new Watermark(9));
            assertThat(h.extractOutputValues()).isEmpty();
            h.processWatermark(new Watermark(10));
            drain(h);
            assertThat(h.getOutput())
                    .containsExactly(
                            new Watermark(9), new StreamRecord<>("tick:10", 10), new Watermark(10));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restoresRunWaitingAfterEventTimerFired(boolean parallel) throws Exception {
        OperatorSubtaskState snapshot;
        try (var h = harness(parallel)) {
            h.open();
            h.processElement(new StreamRecord<>(4L));
            drain(h);
            h.processWatermark(new Watermark(10));
            CurrentRunTimerTest.pumpUntil(h, () -> h.numProcessingTimeTimers() == 1);
            snapshot = h.snapshot(1, 0);
        }
        try (var h = harness(parallel)) {
            h.initializeState(snapshot);
            h.open();
            h.processWatermark(new Watermark(10));
            CurrentRunTimerTest.pumpUntil(h, () -> h.getTaskMailbox().size() == 0);
            h.setProcessingTime(100);
            drain(h);
            assertThat(h.getOutput())
                    .containsExactly(new StreamRecord<>("release", 10), new Watermark(10));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completedActionReplayPreservesEventTimeDomain(boolean parallel) throws Exception {
        InMemoryActionStateStore store = new InMemoryActionStateStore(false);
        OperatorSubtaskState snapshot;
        int before = REGISTRATIONS.get();
        try (var h = harness(parallel, store)) {
            h.open();
            snapshot = h.snapshot(1, 0);
            h.processElement(new StreamRecord<>(1L));
            drain(h);
            for (Map<String, ActionState> states : store.getKeyedActionStates().values()) {
                for (Map.Entry<String, ActionState> entry : states.entrySet()) {
                    entry.setValue(
                            ActionStateSerde.deserialize(
                                    ActionStateSerde.serialize(entry.getValue())));
                }
            }
        }
        try (var h = harness(parallel, store)) {
            h.initializeState(snapshot);
            h.open();
            h.processElement(new StreamRecord<>(1L));
            drain(h);
            assertThat(REGISTRATIONS.get()).isEqualTo(before + 1);
            assertThat(h.numEventTimeTimers()).isEqualTo(1);
            h.processWatermark(new Watermark(10));
            drain(h);
            assertThat(h.getOutput())
                    .containsExactly(new StreamRecord<>("tick:10", 10), new Watermark(10));
        }
    }

    @Test
    void recoveredCancellationRunsBeforeWatermarkAdvancement() throws Exception {
        InMemoryActionStateStore store = new InMemoryActionStateStore(false);
        OperatorSubtaskState snapshot;
        try (var h = harness(false, store)) {
            h.open();
            h.processElement(new StreamRecord<>(1L));
            drain(h);
            h.processElement(new StreamRecord<>(3L));
            snapshot = h.snapshot(1, 0);
            drain(h);
        }
        try (var h = harness(false, store)) {
            h.initializeState(snapshot);
            h.open();
            h.processWatermark(new Watermark(10));
            drain(h);
            assertThat(h.numEventTimeTimers()).isZero();
            assertThat(h.getOutput()).containsExactly(new Watermark(10));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void timerRunQueuesBehindLaterInputWithoutReleasingWatermark(boolean parallel)
            throws Exception {
        try (var h = harness(parallel)) {
            h.open();
            h.processElement(new StreamRecord<>(1L));
            // W is received before the second input, but the first input is still pending.
            h.processWatermark(new Watermark(10));
            h.processElement(new StreamRecord<>(9L, 2));
            CurrentRunTimerTest.pumpUntil(h, () -> h.numProcessingTimeTimers() == 1);
            assertThat(h.getOutput()).isEmpty();
            h.setProcessingTime(100);
            drain(h);
            assertThat(h.getOutput())
                    .containsExactly(
                            new StreamRecord<>("input-done", 2),
                            new StreamRecord<>("tick:10", 10),
                            new StreamRecord<>("tick:10", 10),
                            new Watermark(10));
        }
    }

    @Test
    void rescalingKeepsEventTimersWithTheirKeys() throws Exception {
        OperatorSubtaskState snapshot;
        try (var h =
                new KeyedOneInputStreamOperatorTestHarness<Long, Long, Object>(
                        new ActionExecutionOperatorFactory<>(plan(false), true),
                        value -> value,
                        Types.LONG,
                        4,
                        1,
                        0)) {
            h.open();
            h.processElement(new StreamRecord<>(1L));
            h.processElement(new StreamRecord<>(2L));
            drain(h);
            snapshot = h.snapshot(1, 0);
        }
        List<Object> outputs = new ArrayList<>();
        for (int subtask = 0; subtask < 2; subtask++) {
            try (var h =
                    new KeyedOneInputStreamOperatorTestHarness<Long, Long, Object>(
                            new ActionExecutionOperatorFactory<>(plan(false), true),
                            value -> value,
                            Types.LONG,
                            4,
                            2,
                            subtask)) {
                h.initializeState(
                        AbstractStreamOperatorTestHarness.repartitionOperatorState(
                                snapshot, 4, 1, 2, subtask));
                h.open();
                h.processWatermark(new Watermark(20));
                drain(h);
                assertThat(h.getOutput().peek()).isNotNull();
                assertThat(new ArrayList<>(h.getOutput()).get(h.getOutput().size() - 1))
                        .isEqualTo(new Watermark(20));
                outputs.addAll(h.extractOutputValues());
            }
        }
        assertThat(outputs).containsExactlyInAnyOrder("tick:10", "tick:20");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waitsForAsyncTimerActionBeforeForwardingWatermark(boolean parallel) throws Exception {
        assumeTrue(parallel || ContinuationActionExecutor.isContinuationSupported());
        started = new CountDownLatch(1);
        release = new CountDownLatch(1);
        try (var h = harness(parallel)) {
            h.open();
            h.processElement(new StreamRecord<>(11L));
            drain(h);
            h.processWatermark(new Watermark(10));
            CurrentRunTimerTest.pumpUntil(h, () -> started.getCount() == 0);
            assertThat(h.getOutput()).isEmpty();
            release.countDown();
            drain(h);
            assertThat(h.getOutput())
                    .containsExactly(new StreamRecord<>("tick:10", 10), new Watermark(10));
        } finally {
            release.countDown();
        }
    }
}
