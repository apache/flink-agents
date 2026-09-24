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
 * engine: a worker that redeems a queued dispatch permit while the drain is active must park
 * without holding back the in-flight tasks whose completion the quiesce is waiting for.
 *
 * <p>Timeline (three actions, two workers; the harness mailbox thread is the test thread, so the
 * post-barrier steps are driven from a controller thread):
 *
 * <ol>
 *   <li>One input record queues actions A, B and C. The workers pull A and B, which block in their
 *       async sections; C's dispatch permit stays queued behind the saturated pool.
 *   <li>The barrier arrives: the test thread enters {@code prepareSnapshotPreBarrier} and blocks in
 *       the quiesce loop.
 *   <li>The controller releases A; its worker commits A, redeems C's queued permit, sees the drain
 *       and parks.
 *   <li>The controller releases B; B's async resume must re-acquire the lock so the drain can
 *       quiesce. If it cannot, the controller flags the deadlock and interrupts the mailbox thread
 *       so the test reports instead of hanging until the JUnit timeout.
 * </ol>
 */
class ActionExecutionOperatorDrainDeadlockIntegrationTest {

    private static final int NUM_ASYNC_THREADS = 2;

    /** Bound for every phase that must happen for the timeline to be set up. */
    private static final long SETUP_TIMEOUT_SECONDS = 10;

    /** Time budget for the drain to quiesce after the last in-flight action is released. */
    private static final long DRAIN_COMPLETION_TIMEOUT_SECONDS = 10;

    @Test
    @Timeout(120)
    void drainQuiesceCompletesWhenQueuedPermitIsRedeemedDuringDrain() throws Exception {
        assumeThat(ContinuationActionExecutor.isContinuationSupported()).isFalse();
        DrainAgent.reset();

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

            // Step 1: workers block inside A's and B's async sections; C's permit stays queued.
            testHarness.processElement(new StreamRecord<>(0L));
            assertThat(DrainAgent.A_IN_ASYNC.await(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("action A must reach its async section")
                    .isTrue();
            assertThat(DrainAgent.B_IN_ASYNC.await(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("action B must reach its async section")
                    .isTrue();
            assertThat(DrainAgent.C_STARTED.getCount())
                    .as("action C must not have started: its permit is queued behind the workers")
                    .isEqualTo(1);

            controller =
                    new Thread(() -> driveTimeline(observations, drainReturned, mailboxThread));
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
            assertThat(observations.workerParkedOnQueuedPermit)
                    .as("a worker must have redeemed C's queued permit and parked during the drain")
                    .isTrue();
            assertThat(observations.deadlockDetected)
                    .as(
                            "the drain must quiesce within "
                                    + DRAIN_COMPLETION_TIMEOUT_SECONDS
                                    + "s of releasing the last in-flight action")
                    .isFalse();
            assertThat(drainFailure).as("prepareSnapshotPreBarrier must not fail").isNull();

            // The drain quiesced; the snapshot resumes dispatch, which lets the parked worker
            // run C.
            testHarness.snapshot(1L, 1L);
            operator.waitInFlightEventsFinished();

            List<Object> outputs = new ArrayList<>();
            for (StreamRecord<Object> record : testHarness.getRecordOutput()) {
                outputs.add(record.getValue());
            }
            assertThat(outputs)
                    .as("every action must complete exactly once across the checkpoint")
                    .containsExactlyInAnyOrder(
                            "drain-action-A", "drain-action-B", "drain-action-C");
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
    void oldCheckpointAbortDoesNotResumeCurrentCheckpointDrain() throws Exception {
        assumeThat(ContinuationActionExecutor.isContinuationSupported()).isFalse();
        DrainAgent.reset();

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
            assertThat(DrainAgent.A_IN_ASYNC.await(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(DrainAgent.B_IN_ASYNC.await(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(DrainAgent.C_STARTED.getCount()).isEqualTo(1);

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
                                            mailboxThread));
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
                    .as("the queued action's worker must either park on drain or start")
                    .isTrue();
            assertThat(observations.queuedActionStartedBeforeSnapshot)
                    .as("an older checkpoint abort must not resume the current checkpoint drain")
                    .isFalse();
            assertThat(DrainAgent.C_STARTED.getCount())
                    .as("action C must stay queued until the current checkpoint snapshot resumes")
                    .isEqualTo(1);
            assertThat(observations.deadlockDetected)
                    .as("the current checkpoint drain must still quiesce")
                    .isFalse();
            assertThat(drainFailure).as("prepareSnapshotPreBarrier must not fail").isNull();

            testHarness.snapshot(2L, 2L);
            operator.waitInFlightEventsFinished();

            List<Object> outputs = new ArrayList<>();
            for (StreamRecord<Object> record : testHarness.getRecordOutput()) {
                outputs.add(record.getValue());
            }
            assertThat(outputs)
                    .as("every action must complete exactly once across the checkpoint")
                    .containsExactlyInAnyOrder(
                            "drain-action-A", "drain-action-B", "drain-action-C");
        } finally {
            DrainAgent.releaseAll();
            if (controller != null) {
                controller.join(TimeUnit.SECONDS.toMillis(SETUP_TIMEOUT_SECONDS));
            }
            Thread.interrupted();
        }
    }

    private void driveAbortTimeline(
            CountDownLatch abortExecuted,
            TimelineObservations observations,
            CountDownLatch drainReturned,
            Thread mailboxThread) {
        try {
            if (!abortExecuted.await(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                observations.setupFailure =
                        new AssertionError("old checkpoint abort mail did not execute");
                return;
            }

            DrainAgent.RELEASE_A.countDown();
            observations.queuedActionAdvancedAfterAbort =
                    awaitCondition(
                            () -> DrainAgent.C_STARTED.getCount() == 0 || workerParkedInDrain());
            observations.queuedActionStartedBeforeSnapshot = DrainAgent.C_STARTED.getCount() == 0;

            DrainAgent.RELEASE_B.countDown();
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
            TimelineObservations observations, CountDownLatch drainReturned, Thread mailboxThread) {
        try {
            // Step 2: wait until the mailbox thread is actually parked in the quiesce loop.
            observations.drainEngaged =
                    awaitCondition(
                            ActionExecutionOperatorDrainDeadlockIntegrationTest
                                    ::mailboxInDrainQuiesce);
            if (!observations.drainEngaged) {
                return;
            }

            // Step 3: free one worker; it must pick up C's queued permit and park on the drain.
            DrainAgent.RELEASE_A.countDown();
            observations.workerParkedOnQueuedPermit =
                    awaitCondition(
                            ActionExecutionOperatorDrainDeadlockIntegrationTest
                                    ::workerParkedInDrain);
            if (!observations.workerParkedOnQueuedPermit) {
                return;
            }

            // Step 4: release the still-in-flight action; its async resume must get the lock back.
            DrainAgent.RELEASE_B.countDown();
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
        private volatile boolean workerParkedOnQueuedPermit;
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

    /**
     * Whether some worker thread has redeemed a dispatch permit during the drain and parked on the
     * drain monitor.
     */
    private static boolean workerParkedInDrain() {
        return anyThreadMatches(
                stack ->
                        hasFrame(stack, ParallelExecutionCoordinator.class.getName(), "runOneTask")
                                && hasFrame(stack, "java.lang.Object", "wait"));
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
     * Three actions on one input event: A and B block in their async sections until released; C is
     * an instant no-op.
     */
    public static class DrainAgent {

        private static volatile CountDownLatch A_IN_ASYNC = new CountDownLatch(0);
        private static volatile CountDownLatch B_IN_ASYNC = new CountDownLatch(0);
        private static volatile CountDownLatch C_STARTED = new CountDownLatch(0);
        private static volatile CountDownLatch RELEASE_A = new CountDownLatch(0);
        private static volatile CountDownLatch RELEASE_B = new CountDownLatch(0);

        private static void reset() {
            A_IN_ASYNC = new CountDownLatch(1);
            B_IN_ASYNC = new CountDownLatch(1);
            C_STARTED = new CountDownLatch(1);
            RELEASE_A = new CountDownLatch(1);
            RELEASE_B = new CountDownLatch(1);
        }

        private static void releaseAll() {
            RELEASE_A.countDown();
            RELEASE_B.countDown();
        }

        public static void drainActionA(Event event, RunnerContext context) throws Exception {
            runAction(event, context, "A", A_IN_ASYNC, RELEASE_A);
        }

        public static void drainActionB(Event event, RunnerContext context) throws Exception {
            runAction(event, context, "B", B_IN_ASYNC, RELEASE_B);
        }

        public static void drainActionC(Event event, RunnerContext context) throws Exception {
            runAction(event, context, "C", C_STARTED, null);
        }

        private static void runAction(
                Event event,
                RunnerContext context,
                String name,
                CountDownLatch started,
                CountDownLatch release)
                throws Exception {
            Long input = (Long) InputEvent.fromEvent(event).getInput();
            String actionId = "drain-action-" + name;
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
                                                "Timed out waiting for the release of action "
                                                        + name);
                                    }
                                    return actionId;
                                }
                            })
                    .await();
            context.sendEvent(new OutputEvent(actionId));
        }

        private static AgentPlan getAgentPlan(AgentConfiguration config) throws Exception {
            Map<String, Action> actions = new LinkedHashMap<>();
            for (String name : new String[] {"A", "B", "C"}) {
                String actionName = "drainAction" + name;
                Action action =
                        new Action(
                                actionName,
                                new JavaFunction(
                                        DrainAgent.class,
                                        actionName,
                                        new Class<?>[] {Event.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.EVENT_TYPE));
                actions.put(actionName, action);
            }
            return new AgentPlan(actions, new LinkedHashMap<>(), config);
        }
    }
}
