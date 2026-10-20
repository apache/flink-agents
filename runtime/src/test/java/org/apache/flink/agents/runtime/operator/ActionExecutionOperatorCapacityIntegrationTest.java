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
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.operator.parallel.ParallelExecutionCoordinator;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/** Component-level integration tests for action backlog beyond the worker count. */
class ActionExecutionOperatorCapacityIntegrationTest {

    private static final int NUM_ASYNC_THREADS = 2;

    /** Workers the operator sizes the pool with for {@link #NUM_ASYNC_THREADS}. */
    private static final int WORKERS =
            ParallelExecutionCoordinator.workerCeiling(NUM_ASYNC_THREADS);

    /** Actions left without a worker, since the first {@link #WORKERS} each block holding one. */
    private static final int WORKER_BACKLOG_ACTIONS = 2;

    private static final int NUM_ACTIONS = WORKERS + WORKER_BACKLOG_ACTIONS;

    @Test
    @Timeout(20)
    void actionsBeyondWorkerCountEventuallyCompleteExactlyOnce() throws Exception {
        assumeThat(ContinuationActionExecutor.isContinuationSupported()).isFalse();
        CapacityAgent.reset();

        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentExecutionOptions.NUM_ASYNC_THREADS, NUM_ASYNC_THREADS);

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(
                                CapacityAgent.getAgentPlan(config), true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(0L));
            // Workers pull and start the tasks on their own once the input is admitted, but only
            // WORKERS of them can be in the async stage at once: a call there keeps its worker
            // and only hands off the execution lock.

            assertThat(CapacityAgent.FIRST_BATCH_STARTED.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(CapacityAgent.STARTED_COUNT).hasValue(WORKERS);
            assertThat(testHarness.getRecordOutput()).isEmpty();

            CapacityAgent.ALLOW_FIRST_BATCH.countDown();
            operator.waitInFlightEventsFinished();

            List<Object> outputValues = outputValues(testHarness);

            Set<String> expected = expectedActions();
            assertThat(outputValues)
                    .hasSize(NUM_ACTIONS)
                    .containsExactlyInAnyOrderElementsOf(expected);
        } finally {
            CapacityAgent.ALLOW_FIRST_BATCH.countDown();
        }
    }

    @Test
    @Timeout(30)
    void actionsBeyondWorkerCountRecoverFromCheckpointWithExactlyOnceOutput() throws Exception {
        assumeThat(ContinuationActionExecutor.isContinuationSupported()).isFalse();
        CapacityAgent.reset();
        CapacityAgent.blockActionsAfterFirstBatch();

        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentExecutionOptions.NUM_ASYNC_THREADS, NUM_ASYNC_THREADS);

        OperatorSubtaskState snapshot;
        List<Object> beforeRestore;
        Thread firstBatchReleaser = null;
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(
                                CapacityAgent.getAgentPlan(config), true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(0L));
            assertThat(CapacityAgent.FIRST_BATCH_STARTED.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(testHarness.getRecordOutput()).isEmpty();

            firstBatchReleaser =
                    new Thread(
                            () -> {
                                try {
                                    Thread.sleep(300L);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                CapacityAgent.ALLOW_FIRST_BATCH.countDown();
                            });
            firstBatchReleaser.setDaemon(true);
            firstBatchReleaser.start();

            operator.prepareSnapshotPreBarrier(1L);
            snapshot = testHarness.snapshot(1L, 1L);
            assertThat(CapacityAgent.REMAINING_ACTION_STARTED.await(5, TimeUnit.SECONDS))
                    .as(
                            "at least one post-checkpoint callable attempt should start before failover")
                    .isTrue();

            beforeRestore = outputValues(testHarness);
            assertThat(beforeRestore).containsExactlyInAnyOrderElementsOf(firstBatchActions());

            CapacityAgent.ALLOW_REMAINING_ACTIONS.countDown();
            operator.waitInFlightEventsFinished();
        } finally {
            CapacityAgent.ALLOW_FIRST_BATCH.countDown();
            CapacityAgent.ALLOW_REMAINING_ACTIONS.countDown();
            if (firstBatchReleaser != null) {
                firstBatchReleaser.join();
            }
        }

        CapacityAgent.allowActionsAfterFirstBatch();
        CapacityAgent.resetFirstBatchGate();
        CapacityAgent.ALLOW_FIRST_BATCH.countDown();
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> restored =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(
                                CapacityAgent.getAgentPlan(config), true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            restored.initializeState(snapshot);
            restored.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) restored.getOperator();

            operator.waitInFlightEventsFinished();

            List<Object> allOutputs = new ArrayList<>(beforeRestore);
            allOutputs.addAll(outputValues(restored));
            Set<String> expected = expectedActions();
            assertThat(allOutputs)
                    .hasSize(NUM_ACTIONS)
                    .containsExactlyInAnyOrderElementsOf(expected);
            for (String action : expected) {
                assertThat(allOutputs).filteredOn(action::equals).hasSize(1);
            }

            assertThat(CapacityAgent.TOTAL_STARTED_COUNT.get()).isGreaterThan(NUM_ACTIONS);
            assertThat(CapacityAgent.CALL_COUNTS.values())
                    .anySatisfy(counter -> assertThat(counter.get()).isGreaterThan(1));
        } finally {
            CapacityAgent.ALLOW_FIRST_BATCH.countDown();
            CapacityAgent.ALLOW_REMAINING_ACTIONS.countDown();
        }
    }

    private static List<Object> outputValues(
            KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness) {
        List<Object> outputValues = new ArrayList<>();
        for (StreamRecord<Object> record : testHarness.getRecordOutput()) {
            outputValues.add(record.getValue());
        }
        return outputValues;
    }

    private static Set<String> expectedActions() {
        return actionNames(1, NUM_ACTIONS);
    }

    /**
     * The actions whose output the snapshot already contains. They were in flight when the drain
     * started, so quiesce waits for them; the backlog's permits are handed back instead, leaving
     * those actions pending in the snapshot for the restore to run.
     */
    private static Set<String> firstBatchActions() {
        return actionNames(1, WORKERS);
    }

    private static Set<String> actionNames(int from, int to) {
        Set<String> names = new LinkedHashSet<>();
        for (int i = from; i <= to; i++) {
            names.add("capacity-action-" + i);
        }
        return names;
    }

    public static class CapacityAgent {

        private static volatile CountDownLatch FIRST_BATCH_STARTED = new CountDownLatch(0);
        private static volatile CountDownLatch ALLOW_FIRST_BATCH = new CountDownLatch(0);
        private static volatile CountDownLatch REMAINING_ACTION_STARTED = new CountDownLatch(0);
        private static volatile CountDownLatch ALLOW_REMAINING_ACTIONS = new CountDownLatch(0);
        private static volatile boolean blockActionsAfterFirstBatch;
        private static final AtomicInteger STARTED_COUNT = new AtomicInteger();
        private static final AtomicInteger TOTAL_STARTED_COUNT = new AtomicInteger();
        private static final Map<String, AtomicInteger> CALL_COUNTS = new ConcurrentHashMap<>();

        private static void reset() {
            resetFirstBatchGate();
            REMAINING_ACTION_STARTED = new CountDownLatch(0);
            ALLOW_REMAINING_ACTIONS = new CountDownLatch(0);
            blockActionsAfterFirstBatch = false;
            TOTAL_STARTED_COUNT.set(0);
            CALL_COUNTS.clear();
        }

        private static void resetFirstBatchGate() {
            FIRST_BATCH_STARTED = new CountDownLatch(WORKERS);
            ALLOW_FIRST_BATCH = new CountDownLatch(1);
            STARTED_COUNT.set(0);
        }

        private static void blockActionsAfterFirstBatch() {
            blockActionsAfterFirstBatch = true;
            REMAINING_ACTION_STARTED = new CountDownLatch(1);
            ALLOW_REMAINING_ACTIONS = new CountDownLatch(1);
        }

        private static void allowActionsAfterFirstBatch() {
            blockActionsAfterFirstBatch = false;
            ALLOW_REMAINING_ACTIONS.countDown();
        }

        private static void runAction(Event event, RunnerContext context, int actionNumber)
                throws Exception {
            Long input = (Long) InputEvent.fromEvent(event).getInput();
            String actionId = "capacity-action-" + actionNumber;
            context.durableExecuteAsync(
                            new DurableCallable<String>() {
                                @Override
                                public String getId() {
                                    return actionId + "-" + input;
                                }

                                @Override
                                public Class<String> getResultClass() {
                                    return String.class;
                                }

                                @Override
                                public String call() throws Exception {
                                    STARTED_COUNT.incrementAndGet();
                                    TOTAL_STARTED_COUNT.incrementAndGet();
                                    CALL_COUNTS
                                            .computeIfAbsent(
                                                    actionId, ignored -> new AtomicInteger())
                                            .incrementAndGet();
                                    if (actionNumber <= WORKERS) {
                                        FIRST_BATCH_STARTED.countDown();
                                        if (!ALLOW_FIRST_BATCH.await(5, TimeUnit.SECONDS)) {
                                            throw new IllegalStateException(
                                                    "Timed out waiting for first capacity batch");
                                        }
                                    } else if (blockActionsAfterFirstBatch) {
                                        REMAINING_ACTION_STARTED.countDown();
                                        if (!ALLOW_REMAINING_ACTIONS.await(30, TimeUnit.SECONDS)) {
                                            throw new IllegalStateException(
                                                    "Timed out waiting for remaining capacity"
                                                            + " actions");
                                        }
                                    }
                                    return actionId;
                                }
                            })
                    .await();
            context.sendEvent(new OutputEvent(actionId));
        }

        public static void capacityAction1(Event event, RunnerContext context) throws Exception {
            runAction(event, context, 1);
        }

        public static void capacityAction2(Event event, RunnerContext context) throws Exception {
            runAction(event, context, 2);
        }

        public static void capacityAction3(Event event, RunnerContext context) throws Exception {
            runAction(event, context, 3);
        }

        public static void capacityAction4(Event event, RunnerContext context) throws Exception {
            runAction(event, context, 4);
        }

        public static void capacityAction5(Event event, RunnerContext context) throws Exception {
            runAction(event, context, 5);
        }

        public static void capacityAction6(Event event, RunnerContext context) throws Exception {
            runAction(event, context, 6);
        }

        public static void capacityAction7(Event event, RunnerContext context) throws Exception {
            runAction(event, context, 7);
        }

        public static void capacityAction8(Event event, RunnerContext context) throws Exception {
            runAction(event, context, 8);
        }

        public static void capacityAction9(Event event, RunnerContext context) throws Exception {
            runAction(event, context, 9);
        }

        public static void capacityAction10(Event event, RunnerContext context) throws Exception {
            runAction(event, context, 10);
        }

        public static void capacityAction11(Event event, RunnerContext context) throws Exception {
            runAction(event, context, 11);
        }

        public static void capacityAction12(Event event, RunnerContext context) throws Exception {
            runAction(event, context, 12);
        }

        private static AgentPlan getAgentPlan(AgentConfiguration config) throws Exception {
            Map<String, Action> actions = new LinkedHashMap<>();
            for (int i = 1; i <= NUM_ACTIONS; i++) {
                String actionName = "capacityAction" + i;
                Action action =
                        new Action(
                                actionName,
                                new JavaFunction(
                                        CapacityAgent.class,
                                        actionName,
                                        new Class<?>[] {Event.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.EVENT_TYPE));
                actions.put(actionName, action);
            }
            return new AgentPlan(actions, new LinkedHashMap<>(), config);
        }
    }
}
