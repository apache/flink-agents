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
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.mailbox.Mail;
import org.apache.flink.streaming.runtime.tasks.mailbox.TaskMailbox;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Component-level regression test for the checkpoint drain quiesce of the JDK&lt;21 parallel
 * engine: a worker that redeems a queued dispatch permit while the drain is active must hand the
 * permit back without holding back the in-flight tasks whose completion the quiesce is waiting for.
 *
 * <p>Timeline (one blocking action per worker plus one queued action; the harness mailbox thread is
 * the test thread, so the post-barrier steps are driven from a controller thread):
 *
 * <ol>
 *   <li>One input record queues every action. The workers pull the blocking ones, which each park
 *       in their async section holding a worker; the queued action's dispatch permit stays behind
 *       the saturated pool.
 *   <li>The barrier arrives: the test thread enters {@code prepareSnapshotPreBarrier} and blocks in
 *       the quiesce loop.
 *   <li>The controller releases the first blocking action; its worker commits, redeems the queued
 *       permit, sees the drain and forfeits the permit, returning its thread to the pool.
 *   <li>The controller releases the rest; the last async resume must re-acquire the lock so the
 *       drain can quiesce. If it cannot, the controller flags the deadlock and interrupts the
 *       mailbox thread so the test reports instead of hanging until the JUnit timeout.
 * </ol>
 *
 * <p>Two further tests cover the other end of that wait, which has no bound of its own: an abort
 * for the checkpoint being drained must end it with a failure naming the pool state, while an abort
 * for an older checkpoint must leave the drain running.
 */
class ActionExecutionOperatorDrainDeadlockIntegrationTest {

    private static final int NUM_ASYNC_THREADS = 2;

    /** Workers the operator sizes the pool with; {@link DrainAgent} blocks every one of them. */
    private static final int WORKERS =
            ParallelExecutionCoordinator.workerCeiling(NUM_ASYNC_THREADS);

    /** Bound for every phase that must happen for the timeline to be set up. */
    private static final long SETUP_TIMEOUT_SECONDS = 10;

    /** Time budget for the drain to quiesce after the last in-flight action is released. */
    private static final long DRAIN_COMPLETION_TIMEOUT_SECONDS = 10;

    @Test
    @Timeout(120)
    void drainQuiesceCompletesWhenQueuedPermitIsRedeemedDuringDrain() throws Exception {
        assumeThat(ContinuationActionExecutor.isContinuationSupported()).isFalse();
        DrainAgent.reset();
        assertThat(DrainAgent.BLOCKING)
                .as("one blocking action per worker, so no worker is free for the queued permit")
                .hasSize(WORKERS);

        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentExecutionOptions.NUM_ASYNC_THREADS, NUM_ASYNC_THREADS);

        Thread mailboxThread = Thread.currentThread();
        CountDownLatch drainReturned = new CountDownLatch(1);
        TimelineObservations observations = new TimelineObservations();
        Thread controller = null;

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(DrainAgent.getAgentPlan(config), true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            // Step 1: every worker blocks in a blocking action's async section, so the queued
            // action's permit has no worker left to redeem it.
            testHarness.processElement(new StreamRecord<>(0L));
            assertThat(DrainAgent.BLOCKING_IN_ASYNC.await(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("every blocking action must reach its async section")
                    .isTrue();
            assertThat(DrainAgent.QUEUED_STARTED.getCount())
                    .as("the queued action must not have started: no worker is free for its permit")
                    .isEqualTo(1);

            controller =
                    new Thread(
                            () ->
                                    driveTimeline(
                                            observations,
                                            drainReturned,
                                            mailboxThread,
                                            operator.getExecutionCoordinator()));
            controller.setName("drain-timeline-controller");
            controller.setDaemon(true);
            controller.start();

            // Steps 2-4 as seen from the mailbox thread: start the drain and wait for quiesce.
            Throwable drainFailure = catchThrowable(() -> operator.prepareSnapshotPreBarrier(1L));
            drainReturned.countDown();
            controller.join(TimeUnit.SECONDS.toMillis(SETUP_TIMEOUT_SECONDS));
            // Clear a possible interrupt from the controller before asserting and tearing down.
            Thread.interrupted();

            assertThat(observations.setupFailure)
                    .as("controller could not establish the timeline")
                    .isNull();
            assertThat(observations.drainEngaged)
                    .as("the mailbox thread must have entered the drain quiesce loop")
                    .isTrue();
            assertThat(observations.workerForfeitedQueuedPermit)
                    .as("a worker must have redeemed C's queued permit and forfeited it on drain")
                    .isTrue();
            assertThat(observations.deadlockDetected)
                    .as(
                            "the drain must quiesce within "
                                    + DRAIN_COMPLETION_TIMEOUT_SECONDS
                                    + "s of releasing the last in-flight action")
                    .isFalse();
            assertThat(drainFailure).as("prepareSnapshotPreBarrier must not fail").isNull();

            // The drain quiesced; the snapshot resumes dispatch, which re-submits the forfeited
            // permit and lets C run.
            testHarness.snapshot(1L, 1L);
            operator.waitInFlightEventsFinished();

            List<Object> outputs = new ArrayList<>();
            for (StreamRecord<Object> record : testHarness.getRecordOutput()) {
                outputs.add(record.getValue());
            }
            assertThat(outputs)
                    .as("every action must complete exactly once across the checkpoint")
                    .containsExactlyInAnyOrderElementsOf(DrainAgent.expectedOutputs());
        } finally {
            DrainAgent.releaseAll();
            if (controller != null) {
                controller.join(TimeUnit.SECONDS.toMillis(SETUP_TIMEOUT_SECONDS));
            }
            Thread.interrupted();
        }
    }

    @Test
    @Timeout(120)
    void oldCheckpointAbortDoesNotEndCurrentCheckpointDrain() throws Exception {
        assumeThat(ContinuationActionExecutor.isContinuationSupported()).isFalse();
        DrainAgent.reset();
        assertThat(DrainAgent.BLOCKING)
                .as("one blocking action per worker, so no worker is free for the queued permit")
                .hasSize(WORKERS);

        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentExecutionOptions.NUM_ASYNC_THREADS, NUM_ASYNC_THREADS);

        Thread mailboxThread = Thread.currentThread();
        CountDownLatch abortExecuted = new CountDownLatch(1);
        CountDownLatch drainReturned = new CountDownLatch(1);
        TimelineObservations observations = new TimelineObservations();
        Thread controller = null;

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(DrainAgent.getAgentPlan(config), true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(0L));
            assertThat(DrainAgent.BLOCKING_IN_ASYNC.await(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(DrainAgent.QUEUED_STARTED.getCount()).isEqualTo(1);

            testHarness
                    .getTaskMailbox()
                    .put(
                            new Mail(
                                    () -> {
                                        abortExecuted.countDown();
                                        operator.notifyCheckpointAborted(1L);
                                    },
                                    TaskMailbox.MAX_PRIORITY,
                                    "abort old checkpoint"));
            controller =
                    new Thread(
                            () ->
                                    driveAbortTimeline(
                                            abortExecuted,
                                            observations,
                                            drainReturned,
                                            mailboxThread,
                                            operator.getExecutionCoordinator()));
            controller.setName("abort-during-drain-controller");
            controller.setDaemon(true);
            controller.start();

            Throwable drainFailure = catchThrowable(() -> operator.prepareSnapshotPreBarrier(2L));
            drainReturned.countDown();
            controller.join(TimeUnit.SECONDS.toMillis(SETUP_TIMEOUT_SECONDS));
            Thread.interrupted();

            assertThat(observations.setupFailure)
                    .as("controller could not establish the abort-during-drain timeline")
                    .isNull();
            assertThat(observations.queuedActionAdvancedAfterAbort)
                    .as("the queued action's worker must either forfeit its permit or start")
                    .isTrue();
            assertThat(observations.queuedActionStartedBeforeSnapshot)
                    .as("an older checkpoint's abort must not end the current checkpoint's drain")
                    .isFalse();
            assertThat(DrainAgent.QUEUED_STARTED.getCount())
                    .as("the queued action must stay queued until the current snapshot resumes")
                    .isEqualTo(1);
            assertThat(observations.deadlockDetected)
                    .as("the current checkpoint drain must still quiesce")
                    .isFalse();
            assertThat(drainFailure)
                    .as("an older checkpoint's abort must not fail the current drain")
                    .isNull();

            testHarness.snapshot(2L, 2L);
            operator.waitInFlightEventsFinished();

            List<Object> outputs = new ArrayList<>();
            for (StreamRecord<Object> record : testHarness.getRecordOutput()) {
                outputs.add(record.getValue());
            }
            assertThat(outputs)
                    .as("every action must complete exactly once across the checkpoint")
                    .containsExactlyInAnyOrderElementsOf(DrainAgent.expectedOutputs());
        } finally {
            DrainAgent.releaseAll();
            if (controller != null) {
                controller.join(TimeUnit.SECONDS.toMillis(SETUP_TIMEOUT_SECONDS));
            }
            Thread.interrupted();
        }
    }

    @Test
    @Timeout(120)
    void abortOfTheDrainedCheckpointEndsTheQuiesceWait() throws Exception {
        assumeThat(ContinuationActionExecutor.isContinuationSupported()).isFalse();
        DrainAgent.reset();

        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentExecutionOptions.NUM_ASYNC_THREADS, NUM_ASYNC_THREADS);

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(DrainAgent.getAgentPlan(config), true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(0L));
            assertThat(DrainAgent.BLOCKING_IN_ASYNC.await(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("every worker must be blocked, so the drain cannot quiesce on its own")
                    .isTrue();

            // Nothing releases the blocked actions, so only this abort can end the wait. Flink
            // delivers it as a mail, which is what the quiesce loop yields.
            testHarness
                    .getTaskMailbox()
                    .put(
                            new Mail(
                                    () -> operator.notifyCheckpointAborted(2L),
                                    TaskMailbox.MAX_PRIORITY,
                                    "abort the checkpoint being drained"));

            Throwable drainFailure = catchThrowable(() -> operator.prepareSnapshotPreBarrier(2L));

            assertThat(drainFailure)
                    .as("the drain of an aborted checkpoint must fail instead of waiting forever")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Checkpoint 2 was aborted")
                    .hasMessageContaining("Pool state: workers=");

            // The failure path resumed dispatch, so the operator still completes the record instead
            // of staying wedged in a drain for a checkpoint that will never be taken.
            DrainAgent.releaseAll();
            operator.waitInFlightEventsFinished();

            List<Object> outputs = new ArrayList<>();
            for (StreamRecord<Object> record : testHarness.getRecordOutput()) {
                outputs.add(record.getValue());
            }
            assertThat(outputs)
                    .as("every action must still complete exactly once after the aborted drain")
                    .containsExactlyInAnyOrderElementsOf(DrainAgent.expectedOutputs());
        } finally {
            DrainAgent.releaseAll();
        }
    }

    private void driveAbortTimeline(
            CountDownLatch abortExecuted,
            TimelineObservations observations,
            CountDownLatch drainReturned,
            Thread mailboxThread,
            ParallelExecutionCoordinator coordinator) {
        try {
            if (!abortExecuted.await(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                observations.setupFailure =
                        new AssertionError("old checkpoint abort mail did not execute");
                return;
            }

            DrainAgent.RELEASE_FIRST.countDown();
            observations.queuedActionAdvancedAfterAbort =
                    awaitCondition(
                            () ->
                                    DrainAgent.QUEUED_STARTED.getCount() == 0
                                            || coordinator.getForfeitedPermitsForTesting() > 0);
            observations.queuedActionStartedBeforeSnapshot =
                    DrainAgent.QUEUED_STARTED.getCount() == 0;

            DrainAgent.RELEASE_REST.countDown();
            if (!drainReturned.await(DRAIN_COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                observations.deadlockDetected = true;
                mailboxThread.interrupt();
            }
        } catch (Throwable t) {
            observations.setupFailure = t;
        } finally {
            DrainAgent.releaseAll();
        }
    }

    /** Drives the post-barrier timeline off the mailbox thread and records what happened. */
    private void driveTimeline(
            TimelineObservations observations,
            CountDownLatch drainReturned,
            Thread mailboxThread,
            ParallelExecutionCoordinator coordinator) {
        try {
            // Step 2: wait until the mailbox thread is actually parked in the quiesce loop.
            observations.drainEngaged =
                    awaitCondition(
                            ActionExecutionOperatorDrainDeadlockIntegrationTest
                                    ::mailboxInDrainQuiesce);
            if (!observations.drainEngaged) {
                return;
            }

            // Step 3: free one worker; it must pick up the queued permit and hand it back.
            DrainAgent.RELEASE_FIRST.countDown();
            observations.workerForfeitedQueuedPermit =
                    awaitCondition(() -> coordinator.getForfeitedPermitsForTesting() > 0);
            if (!observations.workerForfeitedQueuedPermit) {
                return;
            }

            // Step 4: release the rest; the last async resume must get the lock back.
            DrainAgent.RELEASE_REST.countDown();
            if (!drainReturned.await(DRAIN_COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                observations.deadlockDetected = true;
                // Break the mailbox out of its blocking yield so the test reports instead of
                // hanging.
                mailboxThread.interrupt();
            }
        } catch (Throwable t) {
            observations.setupFailure = t;
        } finally {
            DrainAgent.releaseAll();
        }
    }

    /** Records what the controller observed; read by the mailbox thread after the drain call. */
    private static final class TimelineObservations {
        private volatile boolean drainEngaged;
        private volatile boolean workerForfeitedQueuedPermit;
        private volatile boolean queuedActionAdvancedAfterAbort;
        private volatile boolean queuedActionStartedBeforeSnapshot;
        private volatile boolean deadlockDetected;
        private volatile Throwable setupFailure;
    }

    private static boolean awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SETUP_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    /** Whether some thread is blocked in the operator's drain quiesce yield loop. */
    private static boolean mailboxInDrainQuiesce() {
        return anyThreadMatches(
                stack ->
                        hasFrame(
                                        stack,
                                        ActionExecutionOperator.class.getName(),
                                        "prepareSnapshotPreBarrier")
                                && hasFrameMethod(stack, "yield"));
    }

    private static boolean anyThreadMatches(Predicate<StackTraceElement[]> test) {
        for (ThreadInfo info : ManagementFactory.getThreadMXBean().dumpAllThreads(false, false)) {
            if (info != null && test.test(info.getStackTrace())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFrame(
            StackTraceElement[] stack, String className, String methodName) {
        return Arrays.stream(stack)
                .anyMatch(
                        frame ->
                                frame.getClassName().equals(className)
                                        && frame.getMethodName().equals(methodName));
    }

    private static boolean hasFrameMethod(StackTraceElement[] stack, String methodName) {
        return Arrays.stream(stack).anyMatch(frame -> frame.getMethodName().equals(methodName));
    }

    /**
     * One blocking action per worker, each parking in its async section until released, plus one
     * instant action declared last so its dispatch permit has no worker left to redeem it. The
     * first blocking action is released on its own, so exactly one worker becomes free.
     */
    public static class DrainAgent {

        private static final String[] BLOCKING = {
            "drainAction0",
            "drainAction1",
            "drainAction2",
            "drainAction3",
            "drainAction4",
            "drainAction5",
            "drainAction6",
            "drainAction7",
            "drainAction8",
            "drainAction9"
        };

        private static final String QUEUED = "drainActionQueued";

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
            outputs.add("drain-action-queued");
            return outputs;
        }

        private static String blockingActionId(int index) {
            return "drain-action-" + index;
        }

        public static void drainAction0(Event event, RunnerContext context) throws Exception {
            blockWorker(event, context, 0);
        }

        public static void drainAction1(Event event, RunnerContext context) throws Exception {
            blockWorker(event, context, 1);
        }

        public static void drainAction2(Event event, RunnerContext context) throws Exception {
            blockWorker(event, context, 2);
        }

        public static void drainAction3(Event event, RunnerContext context) throws Exception {
            blockWorker(event, context, 3);
        }

        public static void drainAction4(Event event, RunnerContext context) throws Exception {
            blockWorker(event, context, 4);
        }

        public static void drainAction5(Event event, RunnerContext context) throws Exception {
            blockWorker(event, context, 5);
        }

        public static void drainAction6(Event event, RunnerContext context) throws Exception {
            blockWorker(event, context, 6);
        }

        public static void drainAction7(Event event, RunnerContext context) throws Exception {
            blockWorker(event, context, 7);
        }

        public static void drainAction8(Event event, RunnerContext context) throws Exception {
            blockWorker(event, context, 8);
        }

        public static void drainAction9(Event event, RunnerContext context) throws Exception {
            blockWorker(event, context, 9);
        }

        public static void drainActionQueued(Event event, RunnerContext context) throws Exception {
            runAction(event, context, "drain-action-queued", QUEUED_STARTED, null);
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
                            DrainAgent.class,
                            actionName,
                            new Class<?>[] {Event.class, RunnerContext.class}),
                    Collections.singletonList(InputEvent.EVENT_TYPE));
        }
    }
}
