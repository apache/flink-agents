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
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Unit tests for durable execution in {@link RunnerContextImpl}. */
class RunnerContextImplDurableExecuteTest {

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
    void testDurableExecuteLegacyCall() throws Exception {
        RunnerContextImpl context = createContext(new ActionState(null));
        TestDurableCallable<String> callable =
                new TestDurableCallable<>("legacy-call", String.class, () -> "ok");

        String result = context.durableExecute(callable);

        assertEquals("ok", result);
        assertEquals(1, callable.getCallCount());
        assertEquals(1, persistCallCount.get());
        assertEquals(1, context.getDurableExecutionContext().getCurrentCallIndex());
        assertEquals(1, context.getDurableExecutionContext().getActionState().getCallResultCount());
        CallResult persisted =
                context.getDurableExecutionContext().getActionState().getCallResults().get(0);
        assertTrue(persisted.isSuccess());
        assertSame(context.getDurableExecutionContext().getActionState(), lastPersistedState);
    }

    @Test
    void testDurableExecuteReconcilableSuccessCall() throws Exception {
        RunnerContextImpl context = createContext(new ActionState(null));
        TestReconcilableCallable<String> callable =
                new TestReconcilableCallable<>(
                        "recon-call",
                        String.class,
                        () -> "ok",
                        () -> fail("reconcile should not be called on initial execution"));

        String result = context.durableExecute(callable);

        assertEquals("ok", result);
        assertEquals(1, callable.getCallCount());
        assertEquals(0, callable.getReconcileCount());
        assertEquals(2, persistCallCount.get());
        assertEquals(1, context.getDurableExecutionContext().getCurrentCallIndex());
        CallResult persisted =
                context.getDurableExecutionContext().getActionState().getCallResults().get(0);
        assertTrue(persisted.isSuccess());
    }

    @Test
    void testDurableExecuteAsyncFallsBackToSyncExecution() throws Exception {
        RunnerContextImpl context = createContext(new ActionState(null));
        TestReconcilableCallable<String> callable =
                new TestReconcilableCallable<>(
                        "recon-async",
                        String.class,
                        () -> "ok",
                        () -> fail("reconcile should not be called on initial async fallback"));

        String result = context.durableExecuteAsync(callable);

        assertEquals("ok", result);
        assertEquals(1, callable.getCallCount());
        assertEquals(0, callable.getReconcileCount());
        assertEquals(2, persistCallCount.get());
        assertEquals(1, context.getDurableExecutionContext().getCurrentCallIndex());
        CallResult persisted =
                context.getDurableExecutionContext().getActionState().getCallResults().get(0);
        assertTrue(persisted.isSuccess());
    }

    @Test
    void testDurableExecuteReconcilableFailCall() {
        RunnerContextImpl context = createContext(new ActionState(null));
        RuntimeException failure = new RuntimeException("call failed");
        TestReconcilableCallable<String> callable =
                new TestReconcilableCallable<>(
                        "recon-call",
                        String.class,
                        () -> {
                            throw failure;
                        },
                        () -> fail("reconcile should not be called on initial execution"));

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> context.durableExecute(callable));

        assertSame(failure, thrown);
        assertEquals(1, callable.getCallCount());
        assertEquals(0, callable.getReconcileCount());
        assertEquals(2, persistCallCount.get());
        CallResult persisted =
                context.getDurableExecutionContext().getActionState().getCallResults().get(0);
        assertTrue(persisted.isFailure());
    }

    @Test
    void testDurableExecuteReconcilableReplaySuccess() throws Exception {
        ActionState actionState = new ActionState(null);
        actionState.addCallResult(
                new CallResult("recon-call", "", OBJECT_MAPPER.writeValueAsBytes("cached")));
        RunnerContextImpl context = createContext(actionState);
        TestReconcilableCallable<String> callable =
                new TestReconcilableCallable<>(
                        "recon-call",
                        String.class,
                        () -> fail("call should not be re-executed"),
                        () -> fail("reconcile should not be called for terminal slot"));

        String result = context.durableExecute(callable);

        assertEquals("cached", result);
        assertEquals(0, callable.getCallCount());
        assertEquals(0, callable.getReconcileCount());
        assertEquals(0, persistCallCount.get());
        assertEquals(1, context.getDurableExecutionContext().getCurrentCallIndex());
    }

    @Test
    void testDurableExecuteReconcilableReplayFailure() throws Exception {
        ActionState actionState = new ActionState(null);
        actionState.addCallResult(
                new CallResult(
                        "recon-call",
                        "",
                        null,
                        OBJECT_MAPPER.writeValueAsBytes(
                                RunnerContextImpl.DurableExecutionException.fromException(
                                        new IllegalStateException("cached failure")))));
        RunnerContextImpl context = createContext(actionState);
        TestReconcilableCallable<String> callable =
                new TestReconcilableCallable<>(
                        "recon-call",
                        String.class,
                        () -> fail("call should not be re-executed"),
                        () -> fail("reconcile should not be called for terminal slot"));

        Exception thrown = assertThrows(Exception.class, () -> context.durableExecute(callable));

        assertTrue(thrown.getMessage().contains("IllegalStateException"));
        assertTrue(thrown.getMessage().contains("cached failure"));
        assertEquals(0, callable.getCallCount());
        assertEquals(0, callable.getReconcileCount());
        assertEquals(0, persistCallCount.get());
        assertEquals(1, context.getDurableExecutionContext().getCurrentCallIndex());
    }

    @Test
    void testDurableExecuteReconcilableReconcileSuccess() throws Exception {
        ActionState actionState = new ActionState(null);
        actionState.addCallResult(CallResult.pending("recon-call", ""));
        RunnerContextImpl context = createContext(actionState);
        TestReconcilableCallable<String> callable =
                new TestReconcilableCallable<>(
                        "recon-call",
                        String.class,
                        () -> fail("call should not be re-executed"),
                        () -> "recovered");

        String result = context.durableExecute(callable);

        assertEquals("recovered", result);
        assertEquals(0, callable.getCallCount());
        assertEquals(1, callable.getReconcileCount());
        assertEquals(1, persistCallCount.get());
        CallResult persisted =
                context.getDurableExecutionContext().getActionState().getCallResults().get(0);
        assertTrue(persisted.isSuccess());
        assertEquals(1, context.getDurableExecutionContext().getCurrentCallIndex());
    }

    @Test
    void testDurableExecuteReconcilableReconcileExceptionPropagates() throws Exception {
        ActionState actionState = new ActionState(null);
        actionState.addCallResult(CallResult.pending("recon-call", ""));
        RunnerContextImpl context = createContext(actionState);
        IllegalStateException failure = new IllegalStateException("reconcile unavailable");
        TestReconcilableCallable<String> callable =
                new TestReconcilableCallable<>(
                        "recon-call",
                        String.class,
                        () -> fail("call should not be re-executed"),
                        () -> {
                            throw failure;
                        });

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> context.durableExecute(callable));

        assertSame(failure, thrown);
        assertEquals(0, callable.getCallCount());
        assertEquals(1, callable.getReconcileCount());
        assertEquals(0, persistCallCount.get());
        CallResult persisted =
                context.getDurableExecutionContext().getActionState().getCallResults().get(0);
        assertTrue(persisted.isPending());
        assertEquals(0, context.getDurableExecutionContext().getCurrentCallIndex());
    }

    @Test
    void testDurableExecuteReconcilableMismatchStartsNewCall() throws Exception {
        ActionState actionState = new ActionState(null);
        actionState.addCallResult(CallResult.pending("stale-call", ""));
        RunnerContextImpl context = createContext(actionState);
        TestReconcilableCallable<String> callable =
                new TestReconcilableCallable<>(
                        "recon-call",
                        String.class,
                        () -> "fresh",
                        () -> fail("reconcile should not be called for mismatched slot"));

        String result = context.durableExecute(callable);

        assertEquals("fresh", result);
        assertEquals(1, callable.getCallCount());
        assertEquals(0, callable.getReconcileCount());
        assertEquals(3, persistCallCount.get());
        assertEquals(1, context.getDurableExecutionContext().getActionState().getCallResultCount());
        CallResult persisted =
                context.getDurableExecutionContext().getActionState().getCallResults().get(0);
        assertEquals("recon-call", persisted.getFunctionId());
        assertTrue(persisted.isSuccess());
    }

    private RunnerContextImpl createContext(ActionState actionState) {
        RunnerContextImpl context =
                new RunnerContextImpl(
                        metricGroup,
                        () -> {},
                        new AgentPlan(new HashMap<>(), new HashMap<>()),
                        null,
                        "test-job");
        ActionStatePersister persister =
                (key, sequenceNumber, action, event, state) -> {
                    persistCallCount.incrementAndGet();
                    lastPersistedState = state;
                };
        context.durableExecutionContext =
                new RunnerContextImpl.DurableExecutionContext(
                        "test-key",
                        1L,
                        mock(Action.class),
                        mock(Event.class),
                        actionState,
                        persister);
        return context;
    }
}
