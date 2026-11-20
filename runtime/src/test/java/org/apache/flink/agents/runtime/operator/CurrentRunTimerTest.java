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
import org.apache.flink.agents.api.context.MemoryRef;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.context.TimerMode;
import org.apache.flink.agents.api.trace.TraceContext;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.actionstate.InMemoryActionStateStore;
import org.apache.flink.agents.runtime.lifecycle.TaskLifecycleListener;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.mailbox.TaskMailbox;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(30)
public class CurrentRunTimerTest {
    private static final AtomicInteger REGISTRATIONS = new AtomicInteger();
    private static final AtomicInteger CONTINUATIONS = new AtomicInteger();

    public static void input(Event event, RunnerContext ctx) throws Exception {
        long command = ((Number) InputEvent.fromEvent(event).getInput()).longValue();
        if (command == 2) {
            assertThat(ctx.getSensoryMemory().isExist("origin")).isFalse();
            ctx.sendEvent(new OutputEvent("next"));
            return;
        }
        if (command == 99) {
            assertThat(ctx.getSensoryMemory().get("origin").getValue()).isEqualTo(12L);
            ctx.sendEvent(new OutputEvent("continued-input"));
            return;
        }
        REGISTRATIONS.incrementAndGet();
        ctx.getSensoryMemory().set("origin", command);
        Event continuation = new Event("continue", Map.of("command", command, "step", 1));
        continuation.setAttachment("value", new byte[] {7});
        continuation.setAttachment("ref", ctx.getSensoryMemory().set("reference", new byte[] {8}));
        if (command == 5 || command == 6) {
            ctx.registerTimer(
                    "wait",
                    Duration.ofMillis(10),
                    new Event("background"),
                    TimerMode.TRIGGER_AFTER_CURRENT_RUN);
        }
        ctx.registerTimerAt(
                "wait",
                Instant.ofEpochMilli(10),
                command == 7
                        ? new Event("unhandled")
                        : command == 12 ? new InputEvent(99L) : continuation,
                TimerMode.CURRENT_RUN_WAIT_FOR_TRIGGER);
        if (command != 7 && command != 12) {
            assertThat(continuation.getAttachments().get("value")).isInstanceOf(MemoryRef.class);
        }
        if (command == 4) {
            ctx.cancelTimer("wait");
        } else if (command == 5) {
            ctx.registerTimer(
                    "wait",
                    Duration.ofMillis(10),
                    new Event("background"),
                    TimerMode.TRIGGER_AFTER_CURRENT_RUN);
        } else if (command == 8 || command == 11) {
            ctx.registerTimerAt(
                    "other",
                    Instant.ofEpochMilli(command == 8 ? 20 : 10),
                    new Event("continue", Map.of("command", command, "step", 2)),
                    TimerMode.CURRENT_RUN_WAIT_FOR_TRIGGER);
        }
        if (command == 9) {
            ctx.sendEvent(new Event("cancel"));
        }
        ctx.sendEvent(new OutputEvent("started"));
    }

    public static void continuation(Event event, RunnerContext ctx) throws Exception {
        CONTINUATIONS.incrementAndGet();
        long command = ((Number) event.getAttributes().get("command")).longValue();
        int step = ((Number) event.getAttributes().get("step")).intValue();
        assertThat(ctx.getSensoryMemory().get("origin").getValue()).isEqualTo(command);
        if (!event.getAttachments().isEmpty()) {
            assertThat((byte[]) event.getAttachment("value")).containsExactly((byte) 7);
            assertThat((byte[]) event.getAttachment("ref")).containsExactly((byte) 8);
        }
        ctx.sendEvent(new OutputEvent("continued:" + step));
        if (command == 3 && step == 1) {
            ctx.registerTimer(
                    "wait",
                    Duration.ofMillis(10),
                    new Event("continue", Map.of("command", command, "step", 2)),
                    TimerMode.CURRENT_RUN_WAIT_FOR_TRIGGER);
        } else if (command == 8) {
            ctx.cancelTimer("other");
        }
    }

    public static void cancel(Event event, RunnerContext ctx) {
        ctx.cancelTimer("wait");
    }

    public static void background(Event event, RunnerContext ctx) throws Exception {
        assertThat(ctx.getSensoryMemory().isExist("origin")).isFalse();
        ctx.sendEvent(new OutputEvent("background"));
    }

    private static AgentPlan plan(boolean parallel) throws Exception {
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentExecutionOptions.PARALLEL_EXECUTION_ENABLED, parallel);
        return new AgentPlan(
                Map.of(
                        "input", action("input", InputEvent.EVENT_TYPE),
                        "cancel", action("cancel", "cancel"),
                        "continuation", action("continuation", "continue"),
                        "background", action("background", "background")),
                Map.of(),
                config);
    }

    private static Action action(String name, String type) throws Exception {
        return new Action(
                name,
                new JavaFunction(
                        CurrentRunTimerTest.class,
                        name,
                        new Class<?>[] {Event.class, RunnerContext.class}),
                List.of(type));
    }

    private static KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness(
            boolean parallel, InMemoryActionStateStore store) throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(
                new ActionExecutionOperatorFactory<Long, Object>(plan(parallel), true, store),
                value -> 0L,
                Types.LONG);
    }

    static void pumpUntil(
            KeyedOneInputStreamOperatorTestHarness<?, ?, ?> h, BooleanSupplier condition)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            var mail = h.getTaskMailbox().tryTake(TaskMailbox.MIN_PRIORITY);
            if (mail.isPresent()) {
                mail.get().run();
            } else {
                Thread.sleep(1);
            }
        }
    }

    private static void finish(KeyedOneInputStreamOperatorTestHarness<?, ?, ?> h) throws Exception {
        ((ActionExecutionOperator<?, ?>) h.getOperator()).waitInFlightEventsFinished();
        var field = ActionExecutionOperator.class.getDeclaredField("metricGroup");
        field.setAccessible(true);
        var metrics = (FlinkAgentsMetricGroupImpl) field.get(h.getOperator());
        assertThat(((Number) metrics.getGauge("numOfActiveInputRuns").getValue()).longValue())
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retainsContextAndHoldsNextInputAndWatermark(boolean parallel) throws Exception {
        try (var h = harness(parallel, null)) {
            h.open();
            List<TraceContext> contexts = new ArrayList<>();
            AtomicInteger starts = new AtomicInteger();
            AtomicInteger finishes = new AtomicInteger();
            ((ActionExecutionOperator<?, ?>) h.getOperator())
                    .addTaskLifecycleListener(
                            new TaskLifecycleListener() {
                                @Override
                                public void onRecordStart(Object key) {
                                    starts.incrementAndGet();
                                }

                                @Override
                                public void onRecordFinished(Object key) {
                                    finishes.incrementAndGet();
                                }

                                @Override
                                public void onActionStarted(ActionTask task) {
                                    contexts.add(task.getTraceContext());
                                }
                            });
            h.processElement(new StreamRecord<>(1L, 123));
            pumpUntil(h, () -> h.numProcessingTimeTimers() == 1);
            h.processWatermark(new Watermark(200));
            h.processElement(new StreamRecord<>(2L, 456));
            assertThat(h.extractOutputValues()).containsExactly("started");
            assertThat(h.getOutput()).doesNotContain(new Watermark(200));
            h.setProcessingTime(10);
            finish(h);
            assertThat(h.extractOutputValues()).containsExactly("started", "continued:1", "next");
            assertThat(contexts).hasSize(3);
            assertThat(contexts.get(1).getInputRunId()).isEqualTo(contexts.get(0).getInputRunId());
            assertThat(contexts.get(2).getInputRunId())
                    .isNotEqualTo(contexts.get(0).getInputRunId());
            assertThat(starts.get()).isEqualTo(2);
            assertThat(finishes.get()).isEqualTo(2);
            assertThat(h.getOutput())
                    .containsSubsequence(
                            new StreamRecord<>("continued:1", 123),
                            new Watermark(200),
                            new StreamRecord<>("next", 456));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restoresRunWaitingOnlyForTimer(boolean parallel) throws Exception {
        OperatorSubtaskState snapshot;
        try (var h = harness(parallel, null)) {
            h.open();
            h.processElement(new StreamRecord<>(1L, 123));
            pumpUntil(h, () -> h.numProcessingTimeTimers() == 1);
            h.processElement(new StreamRecord<>(2L));
            snapshot = h.snapshot(1, 0);
        }
        try (var h = harness(parallel, null)) {
            h.initializeState(snapshot);
            h.open();
            h.setProcessingTime(10);
            // A consumed callback is rearmed once restored Action work is quiescent.
            pumpUntil(h, () -> h.getTaskMailbox().size() == 0);
            h.setProcessingTime(11);
            finish(h);
            assertThat(h.extractOutputValues()).containsExactly("continued:1", "next");
            assertThat(h.getOutput()).contains(new StreamRecord<>("continued:1", 123));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rearmingKeepsRunOpenUntilLastContinuation(boolean parallel) throws Exception {
        try (var h = harness(parallel, null)) {
            h.open();
            h.processElement(new StreamRecord<>(3L));
            pumpUntil(h, () -> h.numProcessingTimeTimers() == 1);
            h.processElement(new StreamRecord<>(2L));
            h.setProcessingTime(10);
            pumpUntil(h, () -> h.numProcessingTimeTimers() == 1);
            assertThat(h.extractOutputValues()).containsExactly("started", "continued:1");
            h.setProcessingTime(20);
            finish(h);
            assertThat(h.extractOutputValues())
                    .containsExactly("started", "continued:1", "continued:2", "next");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationAndModeReplacementUpdateRunWait(boolean parallel) throws Exception {
        for (long command : List.of(4L, 5L, 6L, 7L, 8L, 11L, 12L)) {
            try (var h = harness(parallel, null)) {
                h.open();
                h.processElement(new StreamRecord<>(command));
                pumpUntil(h, () -> h.extractOutputValues().size() == 1);
                h.processElement(new StreamRecord<>(2L));
                if (command == 4 || command == 5) {
                    finish(h);
                    assertThat(h.extractOutputValues()).containsExactly("started", "next");
                } else {
                    assertThat(h.extractOutputValues()).containsExactly("started");
                }
                h.setProcessingTime(10);
                finish(h);
                List<Object> expected = new ArrayList<>(List.of("started"));
                if (command == 6 || command == 8 || command == 11) {
                    expected.add("continued:1");
                }
                if (command == 11) {
                    expected.add("continued:2");
                }
                if (command == 12) {
                    expected.add("continued-input");
                }
                expected.add("next");
                if (command == 5) {
                    expected.add("background");
                }
                assertThat(h.extractOutputValues()).containsExactlyElementsOf(expected);
                assertThat(h.numProcessingTimeTimers()).isZero();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void journalReplayKeepsDeadlineAndReusesContinuation(boolean parallel) throws Exception {
        InMemoryActionStateStore store = new InMemoryActionStateStore(false);
        OperatorSubtaskState beforeInput;
        int registered = REGISTRATIONS.get();
        int continued = CONTINUATIONS.get();
        try (var h = harness(parallel, store)) {
            h.open();
            beforeInput = h.snapshot(1, 0);
            h.processElement(new StreamRecord<>(1L, 123));
            pumpUntil(h, () -> h.numProcessingTimeTimers() == 1);
            h.setProcessingTime(10);
            finish(h);
            for (Map<String, ActionState> states : store.getKeyedActionStates().values()) {
                for (Map.Entry<String, ActionState> entry : states.entrySet()) {
                    entry.setValue(
                            ActionStateSerde.deserialize(
                                    ActionStateSerde.serialize(entry.getValue())));
                }
            }
        }
        try (var h = harness(parallel, store)) {
            h.initializeState(beforeInput);
            h.open();
            h.setProcessingTime(5);
            h.processElement(new StreamRecord<>(1L, 123));
            pumpUntil(h, () -> h.numProcessingTimeTimers() == 1);
            h.setProcessingTime(10);
            finish(h);
            assertThat(REGISTRATIONS.get()).isEqualTo(registered + 1);
            assertThat(CONTINUATIONS.get()).isEqualTo(continued + 1);
            assertThat(h.extractOutputValues()).containsExactly("started", "continued:1");
        }
    }

    @Test
    void replayedCancellationPrecedesOverdueTimer() throws Exception {
        InMemoryActionStateStore store = new InMemoryActionStateStore(false);
        OperatorSubtaskState snapshot;
        int before = CONTINUATIONS.get();
        try (var h = harness(false, store)) {
            h.open();
            h.processElement(new StreamRecord<>(9L));
            h.getTaskMailbox().take(TaskMailbox.MIN_PRIORITY).run();
            snapshot = h.snapshot(1, 0);
            finish(h);
        }
        try (var h = harness(false, store)) {
            h.initializeState(snapshot);
            h.open();
            h.setProcessingTime(100);
            finish(h);
            assertThat(h.numProcessingTimeTimers()).isZero();
            assertThat(CONTINUATIONS.get()).isEqualTo(before);
            assertThat(h.extractOutputValues()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waitingRunDoesNotPreventAnotherKeyFromExecuting(boolean parallel) throws Exception {
        try (var h =
                new KeyedOneInputStreamOperatorTestHarness<Long, Long, Object>(
                        new ActionExecutionOperatorFactory<>(plan(parallel), true),
                        value -> value,
                        Types.LONG)) {
            h.open();
            h.processElement(new StreamRecord<>(1L));
            pumpUntil(h, () -> h.numProcessingTimeTimers() == 1);
            h.processElement(new StreamRecord<>(2L));
            pumpUntil(h, () -> h.extractOutputValues().size() == 2);
            assertThat(h.extractOutputValues()).containsExactly("started", "next");
            h.setProcessingTime(10);
            finish(h);
        }
    }

    @Test
    void restoresAlreadyDispatchedTimerEvent() throws Exception {
        OperatorSubtaskState snapshot;
        try (var h = harness(false, null)) {
            h.open();
            h.processElement(new StreamRecord<>(1L, 123));
            pumpUntil(h, () -> h.numProcessingTimeTimers() == 1);
            h.setProcessingTime(10);
            snapshot = h.snapshot(1, 10);
        }
        try (var h = harness(false, null)) {
            h.initializeState(snapshot);
            h.open();
            finish(h);
            assertThat(h.extractOutputValues()).containsExactly("continued:1");
            assertThat(h.getOutput()).contains(new StreamRecord<>("continued:1", 123));
        }
    }

    @Test
    void rescalingPreservesWaitingRuns() throws Exception {
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
            h.processElement(new StreamRecord<>(6L));
            pumpUntil(h, () -> h.numProcessingTimeTimers() == 2);
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
                pumpUntil(h, () -> h.getTaskMailbox().size() == 0);
                h.setProcessingTime(10);
                finish(h);
                outputs.addAll(h.extractOutputValues());
            }
        }
        assertThat(outputs).containsExactlyInAnyOrder("continued:1", "continued:1");
    }
}
