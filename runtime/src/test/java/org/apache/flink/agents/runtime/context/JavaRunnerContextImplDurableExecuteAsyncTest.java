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

package org.apache.flink.agents.runtime.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.CallResult;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Unit tests for async durable execution in {@link JavaRunnerContextImpl}. */
class JavaRunnerContextImplDurableExecuteAsyncTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private FlinkAgentsMetricGroupImpl metricGroup;
    private AtomicInteger persistCallCount;
    private ActionState lastPersistedState;

    @BeforeEach
    void setUp() {
        metricGroup =
                new FlinkAgentsMetricGroupImpl(
                        UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup());
        persistCallCount = new AtomicInteger();
        lastPersistedState = null;
    }

    @Test
    void testDurableExecuteAsyncLegacyCall() throws Exception {
        InspectingContinuationActionExecutor executor = new InspectingContinuationActionExecutor();
        JavaRunnerContextImpl context = createContext(new ActionState(null), executor);
        TestDurableCallable<String> callable =
                new TestDurableCallable<>("legacy-async", String.class, () -> "ok");

        String result = context.durableExecuteAsync(callable);

        assertEquals("ok", result);
        assertEquals(1, callable.getCallCount());
        assertEquals(1, executor.getExecuteAsyncCallCount());
        assertEquals(1, persistCallCount.get());
        CallResult persisted =
                context.getDurableExecutionContext().getActionState().getCallResults().get(0);
        assertTrue(persisted.isSuccess());
        assertSame(context.getDurableExecutionContext().getActionState(), lastPersistedState);
    }

    @Test
    void testDurableExecuteAsyncReconcilableSuccessCall() throws Exception {
        InspectingContinuationActionExecutor executor = new InspectingContinuationActionExecutor();
        JavaRunnerContextImpl context = createContext(new ActionState(null), executor);
        executor.setBeforeExecute(
                () -> {
                    CallResult current =
                            context.getDurableExecutionContext().getCurrentCallResult();
                    assertNotNull(current);
                    assertTrue(current.isPending());
                    assertEquals(0, context.getDurableExecutionContext().getCurrentCallIndex());
                    assertEquals(1, persistCallCount.get());
                });
        TestReconcilableCallable<String> callable =
                new TestReconcilableCallable<>(
                        "recon-async",
                        String.class,
                        () -> "ok",
                        () -> fail("reconcile should not be called on initial async execution"));

        String result = context.durableExecuteAsync(callable);

        assertEquals("ok", result);
        assertEquals(1, callable.getCallCount());
        assertEquals(0, callable.getReconcileCount());
        assertEquals(1, executor.getExecuteAsyncCallCount());
        assertEquals(2, persistCallCount.get());
        CallResult persisted =
                context.getDurableExecutionContext().getActionState().getCallResults().get(0);
        assertTrue(persisted.isSuccess());
    }

    @Test
    void testDurableExecuteAsyncReconcilableReplaySuccess() throws Exception {
        InspectingContinuationActionExecutor executor = new InspectingContinuationActionExecutor();
        ActionState actionState = new ActionState(null);
        actionState.addCallResult(
                new CallResult("recon-async", "", OBJECT_MAPPER.writeValueAsBytes("cached")));
        JavaRunnerContextImpl context = createContext(actionState, executor);
        TestReconcilableCallable<String> callable =
                new TestReconcilableCallable<>(
                        "recon-async",
                        String.class,
                        () -> fail("call should not be executed"),
                        () -> fail("reconcile should not be called for terminal slot"));

        String result = context.durableExecuteAsync(callable);

        assertEquals("cached", result);
        assertEquals(0, callable.getCallCount());
        assertEquals(0, callable.getReconcileCount());
        assertEquals(0, executor.getExecuteAsyncCallCount());
        assertEquals(0, persistCallCount.get());
    }

    @Test
    void testDurableExecuteAsyncReconcilableReconcileSuccess() throws Exception {
        InspectingContinuationActionExecutor executor = new InspectingContinuationActionExecutor();
        ActionState actionState = new ActionState(null);
        actionState.addCallResult(CallResult.pending("recon-async", ""));
        JavaRunnerContextImpl context = createContext(actionState, executor);
        TestReconcilableCallable<String> callable =
                new TestReconcilableCallable<>(
                        "recon-async",
                        String.class,
                        () -> fail("call should not be executed"),
                        () -> "recovered");

        String result = context.durableExecuteAsync(callable);

        assertEquals("recovered", result);
        assertEquals(0, callable.getCallCount());
        assertEquals(1, callable.getReconcileCount());
        assertEquals(0, executor.getExecuteAsyncCallCount());
        assertEquals(1, persistCallCount.get());
        CallResult persisted =
                context.getDurableExecutionContext().getActionState().getCallResults().get(0);
        assertTrue(persisted.isSuccess());
    }

    @Test
    void testDurableExecuteAsyncReconcilableReconcileExceptionPersistsFailure() throws Exception {
        InspectingContinuationActionExecutor executor = new InspectingContinuationActionExecutor();
        ActionState actionState = new ActionState(null);
        actionState.addCallResult(CallResult.pending("recon-async", ""));
        JavaRunnerContextImpl context = createContext(actionState, executor);
        executor.setBeforeExecute(
                () -> {
                    CallResult current =
                            context.getDurableExecutionContext().getCurrentCallResult();
                    assertNotNull(current);
                    assertTrue(current.isPending());
                    assertEquals(0, persistCallCount.get());
                });
        IllegalArgumentException failure = new IllegalArgumentException("reconcile unavailable");
        TestReconcilableCallable<String> callable =
                new TestReconcilableCallable<>(
                        "recon-async",
                        String.class,
                        () -> fail("call should not be executed"),
                        () -> {
                            throw failure;
                        });

        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> context.durableExecuteAsync(callable));

        assertSame(failure, thrown);
        assertEquals(0, callable.getCallCount());
        assertEquals(1, callable.getReconcileCount());
        assertEquals(0, executor.getExecuteAsyncCallCount());
        assertEquals(1, persistCallCount.get());
        CallResult persisted =
                context.getDurableExecutionContext().getActionState().getCallResults().get(0);
        assertTrue(persisted.isFailure());
        assertEquals(1, context.getDurableExecutionContext().getCurrentCallIndex());
    }

    private JavaRunnerContextImpl createContext(
            ActionState actionState, ContinuationActionExecutor executor) {
        JavaRunnerContextImpl context =
                new JavaRunnerContextImpl(
                        metricGroup,
                        () -> {},
                        new AgentPlan(new HashMap<>(), new HashMap<>()),
                        null,
                        "test-job",
                        executor);
        context.setContinuationContext(new ContinuationContext());
        ActionStatePersister persister =
                (key, sequenceNumber, action, event, state) -> {
                    persistCallCount.incrementAndGet();
                    lastPersistedState = state;
                };
        context.setDurableExecutionContext(
                new RunnerContextImpl.DurableExecutionContext(
                        "test-key",
                        1L,
                        mock(Action.class),
                        mock(Event.class),
                        actionState,
                        persister));
        return context;
    }

    private static final class InspectingContinuationActionExecutor
            extends ContinuationActionExecutor {
        private Runnable beforeExecute;
        private int executeAsyncCallCount;

        private InspectingContinuationActionExecutor() {
            super(1);
        }

        @Override
        public <T> T executeAsync(ContinuationContext context, Supplier<T> supplier) {
            executeAsyncCallCount++;
            if (beforeExecute != null) {
                beforeExecute.run();
            }
            return supplier.get();
        }

        private void setBeforeExecute(Runnable beforeExecute) {
            this.beforeExecute = beforeExecute;
        }

        private int getExecuteAsyncCallCount() {
            return executeAsyncCallCount;
        }
    }
}
