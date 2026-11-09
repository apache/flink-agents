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
import org.apache.flink.runtime.state.hashmap.HashMapStateBackend;
import org.apache.flink.state.rocksdb.EmbeddedRocksDBStateBackend;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.mailbox.TaskMailbox;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Checkpoints a saturated worker pool with both pulled tasks and an unstarted FIFO tail. Recovery
 * must preserve their commit order and must not dispatch live workers twice.
 */
class ActionExecutionOperatorInFlightCheckpointTest {
    private static final int NUM_ASYNC_THREADS = 2;
    private static final int WORKERS =
            ParallelExecutionCoordinator.workerCeiling(NUM_ASYNC_THREADS);
    private static final long SETUP_TIMEOUT_SECONDS = 10;

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    @Timeout(60)
    void checkpointsAndRestoresSaturatedWorkersInOrder(boolean rocksDb, boolean finishLaterTasks)
            throws Exception {
        assumeThat(ContinuationActionExecutor.isContinuationSupported()).isFalse();
        CheckpointAgent.reset();
        assertThat(CheckpointAgent.BLOCKING).hasSize(WORKERS);
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentExecutionOptions.NUM_ASYNC_THREADS, NUM_ASYNC_THREADS);
        AgentPlan plan = CheckpointAgent.getAgentPlan(config);
        OperatorSubtaskState snapshot;
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> original =
                harness(plan, rocksDb)) {
            original.open();
            ActionExecutionOperator<?, ?> operator =
                    (ActionExecutionOperator<?, ?>) original.getOperator();
            original.processElement(new StreamRecord<>(0L));
            assertThat(
                            CheckpointAgent.BLOCKING_IN_ASYNC.await(
                                    SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(CheckpointAgent.QUEUED_STARTED.getCount()).isEqualTo(1);
            if (finishLaterTasks) {
                // Finish every task except the FIFO head, including the formerly queued tail.
                // Their completion mails cannot commit past the blocked head. Such completed
                // results are transient too and must retain their recovery records.
                CheckpointAgent.RELEASE_REST.countDown();
                for (int i = 0; i < WORKERS; i++) {
                    original.getTaskMailbox().take(TaskMailbox.MIN_PRIORITY).run();
                }
                assertThat(CheckpointAgent.QUEUED_STARTED.getCount()).isZero();
            }
            operator.prepareSnapshotPreBarrier(1L);
            snapshot = original.snapshot(1L, 1L);
            assertThat(original.getRecordOutput()).isEmpty();
            operator.notifyCheckpointAborted(1L);
            original.snapshot(2L, 2L);
            CheckpointAgent.releaseAll();
            operator.waitInFlightEventsFinished();
            assertOutputs(original);
        } finally {
            CheckpointAgent.releaseAll();
        }

        AgentConfiguration serialConfig = new AgentConfiguration();
        serialConfig.set(AgentExecutionOptions.PARALLEL_EXECUTION_ENABLED, false);
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> inspect =
                harness(CheckpointAgent.getAgentPlan(serialConfig), rocksDb)) {
            inspect.initializeState(snapshot);
            inspect.open();
            ActionExecutionOperator<?, ?> operator =
                    (ActionExecutionOperator<?, ?>) inspect.getOperator();
            operator.setCurrentKey(0L);
            for (int i = 0; i <= WORKERS; i++) {
                ActionTask task = operator.getOperatorStateManager().pollNextActionTask();
                assertThat(task).isNotNull();
                assertThat(task.isExecutionStartedNotified())
                        .isEqualTo(i < WORKERS || finishLaterTasks);
            }
        }

        CheckpointAgent.reset();
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> restored =
                harness(plan, rocksDb)) {
            restored.initializeState(snapshot);
            restored.open();
            // Restored in-flight tasks precede the unstarted tail, again saturating the pool.
            assertThat(
                            CheckpointAgent.BLOCKING_IN_ASYNC.await(
                                    SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(CheckpointAgent.QUEUED_STARTED.getCount()).isEqualTo(1);
            CheckpointAgent.releaseAll();
            ((ActionExecutionOperator<?, ?>) restored.getOperator()).waitInFlightEventsFinished();
            assertOutputs(restored);
        } finally {
            CheckpointAgent.releaseAll();
        }
    }

    private static KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness(
            AgentPlan plan, boolean rocksDb) throws Exception {
        KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(plan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class));
        harness.setStateBackend(
                rocksDb ? new EmbeddedRocksDBStateBackend() : new HashMapStateBackend());
        return harness;
    }

    private static void assertOutputs(
            KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness) {
        List<Object> outputs = new ArrayList<>();
        for (StreamRecord<Object> record : harness.getRecordOutput()) {
            outputs.add(record.getValue());
        }
        assertThat(outputs).containsExactlyElementsOf(CheckpointAgent.expectedOutputs());
    }

    /**
     * One blocking action per worker, each parking in its async section until released, plus one
     * instant action declared last so its dispatch permit has no worker left to redeem it. The
     * first blocking action can stay blocked while the other tasks finish out of order.
     */
    public static class CheckpointAgent {

        private static final String[] BLOCKING = {
            "checkpointAction0",
            "checkpointAction1",
            "checkpointAction2",
            "checkpointAction3",
            "checkpointAction4",
            "checkpointAction5",
            "checkpointAction6",
            "checkpointAction7",
            "checkpointAction8",
            "checkpointAction9"
        };

        private static final String QUEUED = "checkpointActionQueued";

        private static volatile CountDownLatch BLOCKING_IN_ASYNC = new CountDownLatch(0);
        private static volatile CountDownLatch QUEUED_STARTED = new CountDownLatch(0);
        private static volatile CountDownLatch RELEASE_FIRST = new CountDownLatch(0);
        private static volatile CountDownLatch RELEASE_REST = new CountDownLatch(0);

        private static void reset() {
            BLOCKING_IN_ASYNC = new CountDownLatch(BLOCKING.length);
            QUEUED_STARTED = new CountDownLatch(1);
            RELEASE_FIRST = new CountDownLatch(1);
            RELEASE_REST = new CountDownLatch(1);
        }

        private static void releaseAll() {
            RELEASE_FIRST.countDown();
            RELEASE_REST.countDown();
        }

        private static List<String> expectedOutputs() {
            List<String> outputs = new ArrayList<>();
            for (int index = 0; index < BLOCKING.length; index++) {
                outputs.add(blockingActionId(index));
            }
            outputs.add("checkpoint-action-queued");
            return outputs;
        }

        private static String blockingActionId(int index) {
            return "checkpoint-action-" + index;
        }

        public static void checkpointAction0(Event event, RunnerContext context) throws Exception {
            blockWorker(event, context, 0);
        }

        public static void checkpointAction1(Event event, RunnerContext context) throws Exception {
            blockWorker(event, context, 1);
        }

        public static void checkpointAction2(Event event, RunnerContext context) throws Exception {
            blockWorker(event, context, 2);
        }

        public static void checkpointAction3(Event event, RunnerContext context) throws Exception {
            blockWorker(event, context, 3);
        }

        public static void checkpointAction4(Event event, RunnerContext context) throws Exception {
            blockWorker(event, context, 4);
        }

        public static void checkpointAction5(Event event, RunnerContext context) throws Exception {
            blockWorker(event, context, 5);
        }

        public static void checkpointAction6(Event event, RunnerContext context) throws Exception {
            blockWorker(event, context, 6);
        }

        public static void checkpointAction7(Event event, RunnerContext context) throws Exception {
            blockWorker(event, context, 7);
        }

        public static void checkpointAction8(Event event, RunnerContext context) throws Exception {
            blockWorker(event, context, 8);
        }

        public static void checkpointAction9(Event event, RunnerContext context) throws Exception {
            blockWorker(event, context, 9);
        }

        public static void checkpointActionQueued(Event event, RunnerContext context)
                throws Exception {
            runAction(event, context, "checkpoint-action-queued", QUEUED_STARTED, null);
        }

        private static void blockWorker(Event event, RunnerContext context, int index)
                throws Exception {
            runAction(
                    event,
                    context,
                    blockingActionId(index),
                    BLOCKING_IN_ASYNC,
                    index == 0 ? RELEASE_FIRST : RELEASE_REST);
        }

        private static void runAction(
                Event event,
                RunnerContext context,
                String actionId,
                CountDownLatch started,
                CountDownLatch release)
                throws Exception {
            Long input = (Long) InputEvent.fromEvent(event).getInput();
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
                                    started.countDown();
                                    if (release != null
                                            && !release.await(
                                                    SETUP_TIMEOUT_SECONDS * 6, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException(
                                                "Timed out waiting for the release of " + actionId);
                                    }
                                    return actionId;
                                }
                            })
                    .await();
            context.sendEvent(new OutputEvent(actionId));
        }

        private static AgentPlan getAgentPlan(AgentConfiguration config) throws Exception {
            Map<String, Action> actions = new LinkedHashMap<>();
            for (String actionName : BLOCKING) {
                actions.put(actionName, action(actionName));
            }
            actions.put(QUEUED, action(QUEUED));
            return new AgentPlan(actions, new LinkedHashMap<>(), config);
        }

        private static Action action(String actionName) throws Exception {
            return new Action(
                    actionName,
                    new JavaFunction(
                            CheckpointAgent.class,
                            actionName,
                            new Class<?>[] {Event.class, RunnerContext.class}),
                    Collections.singletonList(InputEvent.EVENT_TYPE));
        }
    }
}
