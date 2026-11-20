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
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.AbstractStreamOperatorTestHarness;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class NativeTimerTest {
    private static final AtomicInteger REGISTRATIONS = new AtomicInteger();
    private static CountDownLatch started;
    private static CountDownLatch release;

    public static void input(Event event, RunnerContext ctx) throws Exception {
        long command = ((Number) ((InputEvent) event).getInput()).longValue();
        if (command == 1 || command == 2) {
            REGISTRATIONS.incrementAndGet();
            ctx.getShortTermMemory().set("value", command);
            Event timeout = new Event("timeout");
            timeout.getAttributes().put("value", command);
            timeout.setSourceTimestamp(99L);
            ctx.registerTimer(
                    "approval",
                    Duration.ofMillis(command == 1 ? 10 : 20),
                    timeout,
                    TimerMode.TRIGGER_AFTER_CURRENT_RUN);
        } else if (command == 3) {
            ctx.cancelTimer("approval");
            ctx.cancelTimer("absent");
        } else if (command == 4) {
            ctx.registerTimer(
                    "first",
                    Duration.ofMillis(10),
                    new Event("timeout"),
                    TimerMode.TRIGGER_AFTER_CURRENT_RUN);
            ctx.registerTimer(
                    "second",
                    Duration.ofMillis(10),
                    new Event("timeout"),
                    TimerMode.TRIGGER_AFTER_CURRENT_RUN);
        } else if (command == 5) {
            ctx.registerTimer(
                    "wait",
                    Duration.ofMillis(10),
                    new Event("timeout"),
                    TimerMode.TRIGGER_AFTER_CURRENT_RUN);
            ctx.executeAsync(
                            () -> {
                                started.countDown();
                                if (!release.await(10, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("Test release timed out");
                                }
                                return null;
                            })
                    .await();
        } else if (command == 6) {
            ctx.registerTimer(
                    "same",
                    Duration.ofMillis(10),
                    new Event("unhandled"),
                    TimerMode.TRIGGER_AFTER_CURRENT_RUN);
            ctx.registerTimer(
                    "same",
                    Duration.ofMillis(10),
                    new Event("timeout"),
                    TimerMode.TRIGGER_AFTER_CURRENT_RUN);
        } else if (command == 7) {
            ctx.registerTimerAt(
                    "past",
                    Instant.EPOCH,
                    new Event("timeout"),
                    TimerMode.TRIGGER_AFTER_CURRENT_RUN);
        } else if (command == 8) {
            ctx.registerTimer(
                    "failed",
                    Duration.ZERO,
                    new Event("timeout"),
                    TimerMode.TRIGGER_AFTER_CURRENT_RUN);
            throw new IllegalStateException("Action failed before commit");
        }
    }

    public static void timeout(Event event, RunnerContext ctx) throws Exception {
        assertThat(event.hasSourceTimestamp()).isFalse();
        assertThat(ctx.getSensoryMemory().isExist("from_previous_run")).isFalse();
        assertThat(event.getAttachments()).isEmpty();
        if (event.getAttributes().isEmpty()) {
            ctx.sendEvent(new OutputEvent("tick"));
        } else {
            long value = ((Number) event.getAttributes().get("value")).longValue();
            ctx.sendEvent(new OutputEvent(value));
            assertThat(ctx.getShortTermMemory().get("value").getValue()).isEqualTo(value);
        }
    }

    private static AgentPlan plan(boolean parallel) throws Exception {
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentExecutionOptions.PARALLEL_EXECUTION_ENABLED, parallel);
        Map<String, Action> actions = new HashMap<>();
        actions.put(
                "input",
                new Action(
                        "input",
                        new JavaFunction(
                                NativeTimerTest.class,
                                "input",
                                new Class<?>[] {Event.class, RunnerContext.class}),
                        List.of(InputEvent.EVENT_TYPE)));
        actions.put(
                "timeout",
                new Action(
                        "timeout",
                        new JavaFunction(
                                NativeTimerTest.class,
                                "timeout",
                                new Class<?>[] {Event.class, RunnerContext.class}),
                        List.of("timeout")));
        return new AgentPlan(actions, new HashMap<>(), config);
    }

    private static KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness(
            boolean parallel, InMemoryActionStateStore store) throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(
                new ActionExecutionOperatorFactory<Long, Object>(plan(parallel), true, store),
                value -> 0L,
                Types.LONG);
    }

    private static void drain(KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness)
            throws Exception {
        ((ActionExecutionOperator<?, ?>) harness.getOperator()).waitInFlightEventsFinished();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firesAfterCompletionAndSurvivesIdleKeyRestore(boolean parallel) throws Exception {
        OperatorSubtaskState snapshot;
        try (var h = harness(parallel, null)) {
            h.open();
            h.processElement(new StreamRecord<>(1L, 123));
            drain(h);
            assertThat(h.numProcessingTimeTimers()).isEqualTo(1);
            h.setProcessingTime(9);
            assertThat(h.getRecordOutput()).isEmpty();
            snapshot = h.snapshot(1, 9);
        }
        try (var h = harness(parallel, null)) {
            h.initializeState(snapshot);
            h.open();
            h.setProcessingTime(10);
            drain(h);
            assertThat(h.extractOutputValues()).containsExactly(1L);
            assertThat(h.numProcessingTimeTimers()).isZero();
            h.setProcessingTime(100);
            drain(h);
            assertThat(h.extractOutputValues()).containsExactly(1L);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void overwritesAndCancels(boolean parallel) throws Exception {
        try (var h = harness(parallel, null)) {
            h.open();
            h.processElement(new StreamRecord<>(1L));
            drain(h);
            h.processElement(new StreamRecord<>(2L));
            drain(h);
            assertThat(h.numProcessingTimeTimers()).isEqualTo(1);
            h.setProcessingTime(10);
            drain(h);
            assertThat(h.getRecordOutput()).isEmpty();
            h.setProcessingTime(20);
            drain(h);
            assertThat(h.extractOutputValues()).containsExactly(2L);
            h.processElement(new StreamRecord<>(1L));
            drain(h);
            h.processElement(new StreamRecord<>(3L));
            drain(h);
            assertThat(h.numProcessingTimeTimers()).isZero();
            h.setProcessingTime(100);
            drain(h);
            assertThat(h.extractOutputValues()).containsExactly(2L);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void differentIdsAtSameDeadlineAreIndependent(boolean parallel) throws Exception {
        try (var h = harness(parallel, null)) {
            h.open();
            h.processElement(new StreamRecord<>(4L));
            drain(h);
            assertThat(h.numProcessingTimeTimers()).isEqualTo(2);
            h.setProcessingTime(10);
            drain(h);
            assertThat(h.extractOutputValues()).containsExactly("tick", "tick");
        }
    }

    @Test
    void queuedTimerEventSurvivesCheckpoint() throws Exception {
        OperatorSubtaskState snapshot;
        try (var h = harness(false, null)) {
            h.open();
            h.processElement(new StreamRecord<>(4L));
            drain(h);
            h.processElement(new StreamRecord<>(0L));
            h.setProcessingTime(10);
            snapshot = h.snapshot(1, 10);
        }
        try (var h = harness(false, null)) {
            h.initializeState(snapshot);
            h.open();
            drain(h);
            assertThat(h.extractOutputValues()).containsExactly("tick", "tick");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replayUsesJournaledDeadlineWithoutRunningActionAgain(boolean parallel) throws Exception {
        InMemoryActionStateStore store = new InMemoryActionStateStore(false);
        OperatorSubtaskState snapshot;
        int before = REGISTRATIONS.get();
        try (var h = harness(parallel, store)) {
            h.open();
            snapshot = h.snapshot(1, 0);
            h.setProcessingTime(5);
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
            h.setProcessingTime(12);
            h.processElement(new StreamRecord<>(1L));
            drain(h);
            assertThat(REGISTRATIONS.get()).isEqualTo(before + 1);
            h.setProcessingTime(15);
            drain(h);
            assertThat(h.extractOutputValues()).containsExactly(1L);
        }
    }

    @Test
    void awaitingDoesNotRegisterAndDelayStartsAtCommit() throws Exception {
        started = new CountDownLatch(1);
        release = new CountDownLatch(1);
        try (var h = harness(true, null)) {
            h.open();
            h.processElement(new StreamRecord<>(5L));
            // On the continuation engine dispatch the initial action mail.
            if (org.apache.flink.agents.runtime.async.ContinuationActionExecutor
                    .isContinuationSupported()) {
                h.getTaskMailbox()
                        .take(
                                org.apache.flink.streaming.runtime.tasks.mailbox.TaskMailbox
                                        .MIN_PRIORITY)
                        .run();
            }
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(h.numProcessingTimeTimers()).isZero();
            h.setProcessingTime(100);
            release.countDown();
            drain(h);
            assertThat(h.numProcessingTimeTimers()).isEqualTo(1);
            h.setProcessingTime(109);
            drain(h);
            assertThat(h.getRecordOutput()).isEmpty();
            h.setProcessingTime(110);
            drain(h);
            assertThat(h.extractOutputValues()).containsExactly("tick");
        } finally {
            release.countDown();
        }
    }

    @Test
    void sameDeadlineReplacementAndPastDeadline() throws Exception {
        try (var h = harness(false, null)) {
            h.open();
            h.processElement(new StreamRecord<>(6L));
            drain(h);
            assertThat(h.numProcessingTimeTimers()).isEqualTo(1);
            h.setProcessingTime(10);
            drain(h);
            assertThat(h.extractOutputValues()).containsExactly("tick");
            h.processElement(new StreamRecord<>(7L));
            drain(h);
            h.setProcessingTime(11);
            drain(h);
            assertThat(h.extractOutputValues()).containsExactly("tick", "tick");
        }
    }

    @Test
    void failedActionDoesNotApplyTimerOperations() throws Exception {
        try (var h = harness(false, null)) {
            h.open();
            h.processElement(new StreamRecord<>(8L));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> drain(h))
                    .hasRootCauseMessage("Action failed before commit");
            assertThat(h.numProcessingTimeTimers()).isZero();
        }
    }

    @Test
    void restoredCancellationWinsOverOverdueTimer() throws Exception {
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
            h.setProcessingTime(100);
            drain(h);
            h.setProcessingTime(101);
            drain(h);
            assertThat(h.getRecordOutput()).isEmpty();
            assertThat(h.numProcessingTimeTimers()).isZero();
        }
    }

    @Test
    void cancellationDoesNotRetractAnAlreadyQueuedEvent() throws Exception {
        try (var h = harness(false, null)) {
            h.open();
            h.processElement(new StreamRecord<>(1L));
            drain(h);
            h.processElement(new StreamRecord<>(3L));
            h.setProcessingTime(10);
            drain(h);
            assertThat(h.extractOutputValues()).containsExactly(1L);
        }
    }

    @Test
    void rescalingKeepsTimersWithTheirBusinessKeys() throws Exception {
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
        List<Object> values = new ArrayList<>();
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
                h.setProcessingTime(20);
                drain(h);
                values.addAll(h.extractOutputValues());
            }
        }
        assertThat(values).containsExactlyInAnyOrder(1L, 2L);
    }
}
