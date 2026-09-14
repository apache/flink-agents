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

package org.apache.flink.agents.runtime.lifecycle;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateStore;
import org.apache.flink.agents.runtime.actionstate.InMemoryActionStateStore;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperator;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperatorFactory;
import org.apache.flink.agents.runtime.operator.ActionTask;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.api.operators.StreamOperatorParameters;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@link ActionExecutionOperator} broadcasts the record/task lifecycle events to
 * injected {@link TaskLifecycleListener}s in the expected order, independently of any particular
 * listener implementation.
 */
public class TaskLifecycleListenerNotificationTest {

    @BeforeEach
    void resetRecording() {
        RecordingListener.EVENTS.clear();
    }

    /** Plain listener that records every lifecycle notification it receives. */
    public static class RecordingListener implements TaskLifecycleListener {

        static final List<String> EVENTS = new CopyOnWriteArrayList<>();

        @Override
        public void onRecordStart(Object key) {
            EVENTS.add("recordStart:" + key);
        }

        @Override
        public void onActionPrepared(ActionTask task) {
            EVENTS.add("prepared:" + task.getAction().getName());
        }

        @Override
        public void onActionStarted(ActionTask task) {
            EVENTS.add("started:" + task.getAction().getName());
        }

        @Override
        public void onActionTransferred(ActionTask from, ActionTask to) {
            EVENTS.add(
                    "transferred:" + from.getAction().getName() + "->" + to.getAction().getName());
        }

        @Override
        public void onActionFinishing(ActionTask task) {
            EVENTS.add("finishing:" + task.getAction().getName());
        }

        @Override
        public void onActionFinished(ActionTask task) {
            EVENTS.add("finished:" + task.getAction().getName());
        }

        @Override
        public void onActionReused(ActionTask task) {
            EVENTS.add("reused:" + task.getAction().getName());
        }

        @Override
        public void onActionFailed(ActionTask task, Throwable error) {
            EVENTS.add("failed:" + task.getAction().getName());
        }

        @Override
        public void onRecordFinished(Object key) {
            EVENTS.add("recordFinished:" + key);
        }
    }

    /** Agent with a plain synchronous action. */
    public static class SyncAgent extends Agent {

        @org.apache.flink.agents.api.annotation.Action(EventType.InputEvent)
        public static void handleInput(Event event, RunnerContext context) {
            Long input = (Long) InputEvent.fromEvent(event).getInput();
            context.sendEvent(new OutputEvent(input * 2));
        }
    }

    /** Agent whose input action suspends on a durable async call, forcing a task transfer. */
    public static class AsyncAgent extends Agent {

        @org.apache.flink.agents.api.annotation.Action(EventType.InputEvent)
        public static void handleInput(Event event, RunnerContext context) throws Exception {
            Long input = (Long) InputEvent.fromEvent(event).getInput();
            Long result =
                    context.durableExecuteAsync(
                            new DurableCallable<Long>() {
                                @Override
                                public String getId() {
                                    return "lifecycle-notification";
                                }

                                @Override
                                public Class<Long> getResultClass() {
                                    return Long.class;
                                }

                                @Override
                                public Long call() {
                                    try {
                                        // Force the action to yield before the call completes,
                                        // so the task is suspended and transferred.
                                        Thread.sleep(50);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return input * 2;
                                }
                            });
            context.sendEvent(new OutputEvent(result));
        }
    }

    @Test
    void recordStartAndFinishedPairAroundSyncTasks() throws Exception {
        AgentPlan plan = new AgentPlan(new SyncAgent());
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(plan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();
            operator.addTaskLifecycleListener(new RecordingListener());

            testHarness.processElement(new StreamRecord<>(7L));
            operator.waitInFlightEventsFinished();

            assertThat(RecordingListener.EVENTS)
                    .containsExactly(
                            "recordStart:7",
                            "prepared:handleInput",
                            "started:handleInput",
                            "finishing:handleInput",
                            "finished:handleInput",
                            "recordFinished:7");

            // A second record on the same key starts and finishes its own round.
            testHarness.processElement(new StreamRecord<>(7L));
            operator.waitInFlightEventsFinished();

            assertThat(RecordingListener.EVENTS)
                    .containsExactly(
                            "recordStart:7",
                            "prepared:handleInput",
                            "started:handleInput",
                            "finishing:handleInput",
                            "finished:handleInput",
                            "recordFinished:7",
                            "recordStart:7",
                            "prepared:handleInput",
                            "started:handleInput",
                            "finishing:handleInput",
                            "finished:handleInput",
                            "recordFinished:7");
        }
    }

    /**
     * Exposes the test-only {@link ActionExecutionOperatorFactory} constructor, which is
     * package-private to the operator package, to tests in this package.
     */
    private static class TestableOperatorFactory
            extends ActionExecutionOperatorFactory<Long, Object> {

        TestableOperatorFactory(AgentPlan agentPlan, ActionStateStore actionStateStore) {
            super(agentPlan, true, actionStateStore);
        }
    }

    /**
     * Registers listeners on the operator right after creation, before {@code initializeState} and
     * {@code open} run, so notifications emitted while resuming in-flight work during {@code open}
     * are captured as well.
     */
    private static class ListenerInjectingOperatorFactory
            extends ActionExecutionOperatorFactory<Long, Object> {

        private final List<TaskLifecycleListener> listeners;

        ListenerInjectingOperatorFactory(AgentPlan agentPlan, TaskLifecycleListener listener) {
            super(agentPlan, true);
            this.listeners = new ArrayList<>();
            this.listeners.add(listener);
        }

        @Override
        public <T extends StreamOperator<Object>> T createStreamOperator(
                StreamOperatorParameters<Object> parameters) {
            T operator = super.createStreamOperator(parameters);
            ActionExecutionOperator<Long, Object> actionOperator =
                    (ActionExecutionOperator<Long, Object>) operator;
            listeners.forEach(actionOperator::addTaskLifecycleListener);
            return operator;
        }
    }

    /**
     * Listener that captures the durable action-state picture observed at {@code onActionFinishing}
     * and {@code onActionFinished} time, so the test can assert the finishing notification arrives
     * before the completed state is persisted and the finished notification after.
     */
    private static class CompletionStateObservingListener implements TaskLifecycleListener {

        private final InMemoryActionStateStore store;
        private final List<String> events = new CopyOnWriteArrayList<>();
        private volatile Integer stateCountAtFinishing = null;
        private volatile Boolean anyStateCompletedAtFinishing = null;
        private volatile Boolean anyStateCompletedAtFinished = null;

        CompletionStateObservingListener(InMemoryActionStateStore store) {
            this.store = store;
        }

        @Override
        public void onActionFinishing(ActionTask task) {
            List<ActionState> states = currentStates();
            stateCountAtFinishing = states.size();
            anyStateCompletedAtFinishing = states.stream().anyMatch(ActionState::isCompleted);
            events.add("finishing:" + task.getAction().getName());
        }

        @Override
        public void onActionFinished(ActionTask task) {
            anyStateCompletedAtFinished =
                    currentStates().stream().anyMatch(ActionState::isCompleted);
            events.add("finished:" + task.getAction().getName());
        }

        private List<ActionState> currentStates() {
            return store.getKeyedActionStates().values().stream()
                    .flatMap(perKey -> perKey.values().stream())
                    .collect(java.util.stream.Collectors.toList());
        }
    }

    @Test
    void actionFinishingIsNotifiedBeforeAndFinishedAfterCompletedStateIsDurable() throws Exception {
        AgentPlan plan = new AgentPlan(new SyncAgent());
        InMemoryActionStateStore actionStateStore = new InMemoryActionStateStore(false);
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new TestableOperatorFactory(plan, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();
            CompletionStateObservingListener listener =
                    new CompletionStateObservingListener(actionStateStore);
            operator.addTaskLifecycleListener(listener);

            testHarness.processElement(new StreamRecord<>(7L));
            operator.waitInFlightEventsFinished();

            assertThat(listener.events)
                    .containsExactly("finishing:handleInput", "finished:handleInput");
            // The action state was initialized before the invocation, but the finishing
            // notification must arrive before the completed state is persisted, so the
            // listener still sees an uncompleted state at notification time.
            assertThat(listener.stateCountAtFinishing).isEqualTo(1);
            assertThat(listener.anyStateCompletedAtFinishing).isFalse();
            // The finished notification arrives after the completed state is durable.
            assertThat(listener.anyStateCompletedAtFinished).isTrue();

            // After processing finishes, the durable state records the completion.
            assertThat(
                            actionStateStore.getKeyedActionStates().values().stream()
                                    .flatMap(perKey -> perKey.values().stream())
                                    .allMatch(ActionState::isCompleted))
                    .isTrue();
        }
    }

    @Test
    void replayOfCompletedActionEmitsPreparedAndReusedPair() throws Exception {
        AgentPlan plan = new AgentPlan(new SyncAgent());
        InMemoryActionStateStore actionStateStore = new InMemoryActionStateStore(false);

        // First execution runs the action and persists its completed state.
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new TestableOperatorFactory(plan, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(7L));
            operator.waitInFlightEventsFinished();
        }

        // Replay the same input against the persisted completed state: the invocation is
        // skipped, but the prepared/reused pair must still be emitted so listener bookkeeping
        // that opened on preparation is closed on the reuse path as well.
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new TestableOperatorFactory(plan, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();
            operator.addTaskLifecycleListener(new RecordingListener());

            testHarness.processElement(new StreamRecord<>(7L));
            operator.waitInFlightEventsFinished();

            assertThat(RecordingListener.EVENTS)
                    .containsExactly(
                            "recordStart:7",
                            "prepared:handleInput",
                            "reused:handleInput",
                            "recordFinished:7");
        }
    }

    /** Agent with no actions at all; input records trigger nothing. */
    public static class EmptyAgent extends Agent {}

    @Test
    void resumedInFlightRecordReEmitsRecordStart() throws Exception {
        AgentPlan plan = new AgentPlan(new SyncAgent());
        OperatorSubtaskState snapshot;

        // First execution: admit the record but snapshot before its tasks run, so the record
        // is in flight (processing key + pending task) at snapshot time.
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(plan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            testHarness.processElement(new StreamRecord<>(7L));
            snapshot = testHarness.snapshot(1L, 1L);
        }

        // Restore: the in-flight record resumes during open(), and its record start is
        // re-emitted to align with the replayed task-level callbacks, giving listeners a
        // paired bracket for the replayed round.
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ListenerInjectingOperatorFactory(plan, new RecordingListener()),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.initializeState(snapshot);
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();
            operator.waitInFlightEventsFinished();

            assertThat(RecordingListener.EVENTS)
                    .containsExactly(
                            "recordStart:7",
                            "prepared:handleInput",
                            "started:handleInput",
                            "finishing:handleInput",
                            "finished:handleInput",
                            "recordFinished:7");
        }
    }

    @Test
    void recordWithoutTriggeredActionsEmitsNoLifecycleEvents() throws Exception {
        AgentPlan plan = new AgentPlan(new EmptyAgent());
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(plan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();
            operator.addTaskLifecycleListener(new RecordingListener());

            testHarness.processElement(new StreamRecord<>(3L));
            operator.waitInFlightEventsFinished();

            // No task was ever created, so the start/finished pair stays closed.
            assertThat(RecordingListener.EVENTS).isEmpty();
        }
    }

    @Test
    void suspendedTaskEmitsTransferBeforeItsSuccessorIsPrepared() throws Exception {
        AgentPlan plan = new AgentPlan(new AsyncAgent());
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(plan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();
            operator.addTaskLifecycleListener(new RecordingListener());

            testHarness.processElement(new StreamRecord<>(5L));
            operator.waitInFlightEventsFinished();

            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo(10L);

            if (ContinuationActionExecutor.isContinuationSupported()) {
                // JDK 21+: the action suspends on the durable async call and the operator
                // re-polls the suspended task, emitting a transferred->prepared pair per
                // suspension round. Assert the invariant the test is named after: every
                // suspension transfers the task before its successor is prepared, bounded by
                // recordStart/prepared/started first and finishing/finished last. The started
                // notification fires once per action execution, not per suspension round.
                assertThat(RecordingListener.EVENTS)
                        .startsWith("recordStart:5", "prepared:handleInput")
                        .endsWith(
                                "finishing:handleInput",
                                "finished:handleInput",
                                "recordFinished:5");
                List<String> middle =
                        RecordingListener.EVENTS.subList(3, RecordingListener.EVENTS.size() - 3);
                assertThat(middle.get(0)).isEqualTo("started:handleInput");
                List<String> suspensionRounds = middle.subList(1, middle.size());
                assertThat(suspensionRounds)
                        .as("suspension rounds alternate transferred -> prepared")
                        .isNotEmpty();
                assertThat(suspensionRounds.size() % 2).isZero();
                for (int i = 0; i < suspensionRounds.size(); i += 2) {
                    assertThat(suspensionRounds.get(i))
                            .isEqualTo("transferred:handleInput->handleInput");
                    assertThat(suspensionRounds.get(i + 1)).isEqualTo("prepared:handleInput");
                }
            } else {
                // JDK 11 fallback runs the action synchronously: no suspension, no transfer.
                assertThat(RecordingListener.EVENTS)
                        .containsExactly(
                                "recordStart:5",
                                "prepared:handleInput",
                                "started:handleInput",
                                "finishing:handleInput",
                                "finished:handleInput",
                                "recordFinished:5");
            }
        }
    }

    /** Agent whose action always fails, exercising the failure notification path. */
    public static class FailingAgent extends Agent {

        @org.apache.flink.agents.api.annotation.Action(EventType.InputEvent)
        public static void handleInput(Event event, RunnerContext context) {
            throw new IllegalStateException("action boom");
        }
    }

    @Test
    void failedActionNotifiesFailureWithoutTerminalSuccessCallbacks() throws Exception {
        AgentPlan plan = new AgentPlan(new FailingAgent());
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(plan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();
            operator.addTaskLifecycleListener(new RecordingListener());

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> {
                                testHarness.processElement(new StreamRecord<>(7L));
                                operator.waitInFlightEventsFinished();
                            })
                    .hasStackTraceContaining("action boom");

            // The failure notification replaces the finishing/finished/recordFinished tail.
            assertThat(RecordingListener.EVENTS)
                    .containsExactly(
                            "recordStart:7",
                            "prepared:handleInput",
                            "started:handleInput",
                            "failed:handleInput");
        }
    }
}
