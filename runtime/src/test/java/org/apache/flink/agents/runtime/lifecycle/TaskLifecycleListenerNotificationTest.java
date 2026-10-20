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
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateStore;
import org.apache.flink.agents.runtime.actionstate.InMemoryActionStateStore;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperator;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperatorFactory;
import org.apache.flink.agents.runtime.operator.ActionTask;
import org.apache.flink.agents.runtime.operator.parallel.ParallelExecutionCoordinator;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.api.operators.StreamOperatorParameters;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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

    private static boolean usesParallelWorker(AgentPlan plan) {
        return plan.getConfig().get(AgentExecutionOptions.PARALLEL_EXECUTION_ENABLED)
                && !ContinuationActionExecutor.isContinuationSupported();
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
                                                // Force the action to yield, suspend, and transfer.
                                                Thread.sleep(50);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                            return input * 2;
                                        }
                                    })
                            .await();
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

            List<String> expectedRound =
                    new ArrayList<>(
                            List.of(
                                    "recordStart:7",
                                    "prepared:handleInput",
                                    "started:handleInput"));
            if (usesParallelWorker(plan)) {
                expectedRound.addAll(
                        List.of("transferred:handleInput->handleInput", "prepared:handleInput"));
            }
            expectedRound.addAll(
                    List.of("finishing:handleInput", "finished:handleInput", "recordFinished:7"));
            assertThat(RecordingListener.EVENTS).containsExactlyElementsOf(expectedRound);

            // A second record on the same key starts and finishes its own round.
            testHarness.processElement(new StreamRecord<>(7L));
            operator.waitInFlightEventsFinished();

            List<String> expectedRecords = new ArrayList<>(expectedRound);
            expectedRecords.addAll(expectedRound);
            assertThat(RecordingListener.EVENTS).containsExactlyElementsOf(expectedRecords);
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

            List<String> expected =
                    new ArrayList<>(List.of("recordStart:7", "prepared:handleInput"));
            if (usesParallelWorker(plan)) {
                expected.addAll(
                        List.of("transferred:handleInput->handleInput", "prepared:handleInput"));
            }
            expected.addAll(List.of("reused:handleInput", "recordFinished:7"));
            assertThat(RecordingListener.EVENTS).containsExactlyElementsOf(expected);
        }
    }

    /** Agent with no actions at all; input records trigger nothing. */
    public static class EmptyAgent extends Agent {}

    @Test
    void resumedInFlightRecordReEmitsRecordStart() throws Exception {
        // Serial restore: the record is in flight because its task is still pending on the mailbox.
        // The queued-task branch on the parallel engine has its own test below.
        AgentConfiguration serialConfig = new AgentConfiguration();
        serialConfig.set(AgentExecutionOptions.PARALLEL_EXECUTION_ENABLED, false);
        AgentPlan plan = new AgentPlan(new SyncAgent(), serialConfig);
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

    /**
     * Agent whose input action parks inside a durable async call. On the parallel engine that parks
     * the worker holding it, which is how a test fills the pool and leaves a later task queued.
     */
    public static class ParkingAgent extends Agent {

        static volatile CountDownLatch started;
        static volatile CountDownLatch allow;

        static void resetFixture(int startedCount) {
            started = new CountDownLatch(startedCount);
            allow = new CountDownLatch(1);
        }

        @org.apache.flink.agents.api.annotation.Action(EventType.InputEvent)
        public static void handleInput(Event event, RunnerContext context) throws Exception {
            Long input = (Long) InputEvent.fromEvent(event).getInput();
            Long result =
                    context.durableExecuteAsync(
                                    new DurableCallable<Long>() {
                                        @Override
                                        public String getId() {
                                            return "parking";
                                        }

                                        @Override
                                        public Class<Long> getResultClass() {
                                            return Long.class;
                                        }

                                        @Override
                                        public Long call() {
                                            started.countDown();
                                            try {
                                                allow.await();
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                            return input * 2;
                                        }
                                    })
                            .await();
            context.sendEvent(new OutputEvent(result));
        }
    }

    @Test
    @Timeout(30)
    void resumedInFlightRecordReEmitsRecordStartOnParallelRestore() throws Exception {
        assumeFalse(ContinuationActionExecutor.isContinuationSupported());
        int numAsyncThreads = 1;
        // Every worker has to be parked before the key under test queues its task, or a free
        // worker pulls that task and it retires during the drain, leaving nothing in flight to
        // restore. Derived from the ceiling rather than hard-coded, so it tracks the pool it fills.
        int workers = ParallelExecutionCoordinator.workerCeiling(numAsyncThreads);
        long queuedKey = workers + 1L;
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentExecutionOptions.NUM_ASYNC_THREADS, numAsyncThreads);
        config.set(AgentExecutionOptions.MAX_IN_FLIGHT_INPUT_RECORDS, workers + 2);
        AgentPlan plan = new AgentPlan(new ParkingAgent(), config);
        assertThat(usesParallelWorker(plan)).isTrue();

        // Phase 1: fill the pool, queue one record no worker is left to pull, then drain. The
        // drain retires the parked keys and forfeits the queued record's dispatch permit, so the
        // snapshot captures it as a processing key whose task is still queued.
        OperatorSubtaskState snapshot;
        ParkingAgent.resetFixture(workers);
        Thread releaser = null;
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(plan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            for (long key = 1; key <= workers; key++) {
                testHarness.processElement(new StreamRecord<>(key));
            }
            assertThat(ParkingAgent.started.await(5, TimeUnit.SECONDS)).isTrue();
            testHarness.processElement(new StreamRecord<>(queuedKey));

            // Released only once the drain has paused dispatch: any earlier and a retiring worker
            // pulls the queued task, which then retires and empties the snapshot.
            releaser =
                    new Thread(
                            () -> {
                                try {
                                    Thread.sleep(300);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                ParkingAgent.allow.countDown();
                            });
            releaser.setDaemon(true);
            releaser.start();
            operator.prepareSnapshotPreBarrier(1L);
            snapshot = testHarness.snapshot(1L, 1L);
        } finally {
            ParkingAgent.allow.countDown();
            if (releaser != null) {
                releaser.join();
            }
        }

        // Phase 2: the restored round opens its bracket before dispatching, so the record start
        // precedes every action callback the resumed task emits and the bracket closes on it.
        ParkingAgent.resetFixture(1);
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> restored =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ListenerInjectingOperatorFactory(plan, new RecordingListener()),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            restored.initializeState(snapshot);
            restored.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) restored.getOperator();
            // Proof the snapshot really held the queued record: it was re-dispatched and reached
            // the async stage. Without this the assertions below would pass on an empty restore.
            assertThat(ParkingAgent.started.await(5, TimeUnit.SECONDS)).isTrue();
            ParkingAgent.allow.countDown();
            operator.waitInFlightEventsFinished();

            assertThat(RecordingListener.EVENTS)
                    .containsExactly(
                            "recordStart:" + queuedKey,
                            "prepared:handleInput",
                            "started:handleInput",
                            "transferred:handleInput->handleInput",
                            "prepared:handleInput",
                            "transferred:handleInput->handleInput",
                            "prepared:handleInput",
                            "finishing:handleInput",
                            "finished:handleInput",
                            "recordFinished:" + queuedKey);
            List<StreamRecord<Object>> output =
                    (List<StreamRecord<Object>>) restored.getRecordOutput();
            assertThat(output).extracting(StreamRecord::getValue).containsExactly(queuedKey * 2);
        }
    }

    @Test
    void resumedInFlightRecordCountsAsOneActiveInputRun() throws Exception {
        AgentConfiguration serialConfig = new AgentConfiguration();
        serialConfig.set(AgentExecutionOptions.PARALLEL_EXECUTION_ENABLED, false);
        AgentPlan plan = new AgentPlan(new SyncAgent(), serialConfig);
        OperatorSubtaskState snapshot;

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(plan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            testHarness.processElement(new StreamRecord<>(7L));
            snapshot = testHarness.snapshot(1L, 1L);
        }

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(plan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.initializeState(snapshot);
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();
            Field metricGroupField = ActionExecutionOperator.class.getDeclaredField("metricGroup");
            metricGroupField.setAccessible(true);
            FlinkAgentsMetricGroupImpl metricGroup =
                    (FlinkAgentsMetricGroupImpl) metricGroupField.get(operator);

            assertThat(
                            ((Number) metricGroup.getGauge("numOfActiveInputRuns").getValue())
                                    .longValue())
                    .isEqualTo(1L);

            operator.waitInFlightEventsFinished();

            assertThat(
                            ((Number) metricGroup.getGauge("numOfActiveInputRuns").getValue())
                                    .longValue())
                    .isZero();
        }
    }

    @Test
    void recordWithoutTriggeredActionsStillEmitsTheRecordBracket() throws Exception {
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

            // The record was admitted and retired even though no task was ever created, so the
            // bracket still closes -- with nothing between its two ends, since the internal noop
            // that retires the round on the parallel engine reports no action callback.
            assertThat(RecordingListener.EVENTS)
                    .containsExactly("recordStart:3", "recordFinished:3");
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
                        .startsWith("recordStart:5", "prepared:handleInput", "started:handleInput")
                        .endsWith(
                                "finishing:handleInput",
                                "finished:handleInput",
                                "recordFinished:5");
                assertThat(
                                RecordingListener.EVENTS.stream()
                                        .filter(event -> event.equals("started:handleInput"))
                                        .count())
                        .as("started fires once per action execution, not per suspension round")
                        .isEqualTo(1);
                List<String> suspensionRounds =
                        RecordingListener.EVENTS.subList(3, RecordingListener.EVENTS.size() - 3);
                assertThat(suspensionRounds)
                        .as("suspension rounds alternate transferred -> prepared")
                        .isNotEmpty();
                assertThat(suspensionRounds.size() % 2).isZero();
                for (int i = 0; i < suspensionRounds.size(); i += 2) {
                    assertThat(suspensionRounds.get(i))
                            .isEqualTo("transferred:handleInput->handleInput");
                    assertThat(suspensionRounds.get(i + 1)).isEqualTo("prepared:handleInput");
                }
            } else if (usesParallelWorker(plan)) {
                // One off-lock async call, then the worker-to-mailbox handoff.
                assertThat(RecordingListener.EVENTS)
                        .containsExactly(
                                "recordStart:5",
                                "prepared:handleInput",
                                "started:handleInput",
                                "transferred:handleInput->handleInput",
                                "prepared:handleInput",
                                "transferred:handleInput->handleInput",
                                "prepared:handleInput",
                                "finishing:handleInput",
                                "finished:handleInput",
                                "recordFinished:5");
            } else {
                // Serial fallback runs the action synchronously: no suspension, no transfer.
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
            List<String> expected =
                    new ArrayList<>(
                            List.of(
                                    "recordStart:7",
                                    "prepared:handleInput",
                                    "started:handleInput"));
            if (usesParallelWorker(plan)) {
                expected.add("transferred:handleInput->handleInput");
            }
            expected.add("failed:handleInput");
            assertThat(RecordingListener.EVENTS).containsExactlyElementsOf(expected);
        }
    }
}
