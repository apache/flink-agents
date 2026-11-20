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
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.operator.parallel.ParallelExecutionLock;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.mailbox.TaskMailbox;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@Timeout(30)
public class CurrentRunTimerRecoveryConcurrencyTest {
    private static CountDownLatch workerEntered;
    private static CountDownLatch releaseWorker;

    public static void input(Event event, RunnerContext ctx) throws Exception {
        long command = ((Number) InputEvent.fromEvent(event).getInput()).longValue();
        if (command == 1) {
            ctx.getShortTermMemory().set("owner", "A");
            ctx.registerTimer(
                    "hold",
                    Duration.ofSeconds(60),
                    new Event("finish"),
                    TimerMode.CURRENT_RUN_WAIT_FOR_TRIGGER);
        } else if (command == 2) {
            ctx.getShortTermMemory().set("owner", "B");
        } else {
            // Deliberately keep worker ownership of the execution lock while the mailbox
            // handles the other key's recovery mail. No asynchronous API releases the lock.
            workerEntered.countDown();
            assertThat(releaseWorker.await(10, TimeUnit.SECONDS)).isTrue();
            ctx.sendEvent(new OutputEvent(ctx.getShortTermMemory().get("owner").getValue()));
        }
    }

    private static KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness()
            throws Exception {
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentExecutionOptions.PARALLEL_EXECUTION_ENABLED, true);
        Action action =
                new Action(
                        "input",
                        new JavaFunction(
                                CurrentRunTimerRecoveryConcurrencyTest.class,
                                "input",
                                new Class<?>[] {Event.class, RunnerContext.class}),
                        List.of(InputEvent.EVENT_TYPE));
        AgentPlan plan = new AgentPlan(Map.of("input", action), Map.of(), config);
        return new KeyedOneInputStreamOperatorTestHarness<>(
                new ActionExecutionOperatorFactory<Long, Object>(plan, true),
                command -> command == 1 ? 1L : 2L,
                Types.LONG);
    }

    @Test
    void waitingRunRecoveryPropagatesLockAcquisitionFailure() throws Exception {
        assumeFalse(ContinuationActionExecutor.isContinuationSupported());
        OperatorSubtaskState snapshot;
        try (var h = harness()) {
            h.open();
            h.processElement(new StreamRecord<>(1L));
            CurrentRunTimerTest.pumpUntil(h, () -> h.numProcessingTimeTimers() == 1);
            snapshot = h.snapshot(1, 0);
        }
        try (var h = harness()) {
            h.initializeState(snapshot);
            h.open();
            var field = ActionExecutionOperator.class.getDeclaredField("parallelExecutionLock");
            field.setAccessible(true);
            ParallelExecutionLock lock = (ParallelExecutionLock) field.get(h.getOperator());
            ParallelExecutionLock failingLock =
                    mock(ParallelExecutionLock.class, delegatesTo(lock));
            InterruptedException failure = new InterruptedException("Recovery lock interrupted");
            doThrow(failure).when(failingLock).acquireByMain();
            field.set(h.getOperator(), failingLock);
            try {
                // The recovery mail must propagate failures before entering Action scheduling.
                var mail = h.getTaskMailbox().tryTake(TaskMailbox.MIN_PRIORITY).orElseThrow();
                assertThatThrownBy(mail::run).isSameAs(failure);
            } finally {
                field.set(h.getOperator(), lock);
            }
        }
    }

    @Test
    void waitingRunRecoveryMailMustNotOverwriteAWorkersKey() throws Exception {
        assumeFalse(ContinuationActionExecutor.isContinuationSupported());
        OperatorSubtaskState snapshot;
        try (var h = harness()) {
            h.open();
            h.processElement(new StreamRecord<>(2L));
            ((ActionExecutionOperator<?, ?>) h.getOperator()).waitInFlightEventsFinished();
            h.processElement(new StreamRecord<>(1L));
            CurrentRunTimerTest.pumpUntil(h, () -> h.numProcessingTimeTimers() == 1);
            snapshot = h.snapshot(1, 0);
        }
        workerEntered = new CountDownLatch(1);
        releaseWorker = new CountDownLatch(1);
        try (var h = harness()) {
            h.initializeState(snapshot);
            h.open();
            h.processElement(new StreamRecord<>(3L));
            assertThat(workerEntered.await(10, TimeUnit.SECONDS)).isTrue();
            var field = ActionExecutionOperator.class.getDeclaredField("parallelExecutionLock");
            field.setAccessible(true);
            ParallelExecutionLock lock = (ParallelExecutionLock) field.get(h.getOperator());
            ParallelExecutionLock observedLock =
                    mock(ParallelExecutionLock.class, delegatesTo(lock));
            doAnswer(
                            invocation -> {
                                // Release B only when the recovery mail tries to acquire its lock.
                                // B reads its memory before handing ownership back to the mailbox.
                                releaseWorker.countDown();
                                lock.acquireByMain();
                                return null;
                            })
                    .when(observedLock)
                    .acquireByMain();
            field.set(h.getOperator(), observedLock);
            try {
                // The only mailbox task is key A's "resume waiting run" callback.
                h.getTaskMailbox().tryTake(TaskMailbox.MIN_PRIORITY).orElseThrow().run();
            } finally {
                // Also release B on the old unguarded path, so it observes the wrong key
                // and fails the memory assertion instead of hanging the test.
                releaseWorker.countDown();
                field.set(h.getOperator(), lock);
            }
            CurrentRunTimerTest.pumpUntil(h, () -> !h.getOutput().isEmpty());
            assertThat(h.extractOutputValues()).containsExactly("B");
        } finally {
            releaseWorker.countDown();
        }
    }
}
