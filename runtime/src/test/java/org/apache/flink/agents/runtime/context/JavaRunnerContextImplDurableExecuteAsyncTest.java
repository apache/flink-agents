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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.configuration.Configuration;
import org.apache.flink.agents.api.context.Outcome;
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

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
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

    @Test
    void testDurableExecuteAsyncCompletionOnlyReExecutesPendingSlot() throws Exception {
        InspectingContinuationActionExecutor executor = new InspectingContinuationActionExecutor();
        ActionState actionState = new ActionState(null);
        actionState.addCallResult(CallResult.pending("tool-call", ""));
        JavaRunnerContextImpl context = createContext(actionState, executor);
        TestDurableCallable<String> callable =
                new TestDurableCallable<>("tool-call", String.class, () -> "recovered");

        String result = context.durableExecuteAsync(callable);

        assertEquals("recovered", result);
        assertEquals(1, callable.getCallCount());
        assertEquals(1, executor.getExecuteAsyncCallCount());
        assertEquals(1, persistCallCount.get());
        assertEquals(1, context.getDurableExecutionContext().getCurrentCallIndex());
        CallResult persisted =
                context.getDurableExecutionContext().getActionState().getCallResults().get(0);
        assertTrue(persisted.isSuccess());
    }

    @Test
    void testDurableExecuteAllAsyncInitialBatchPersistsOutcomes() throws Exception {
        InspectingContinuationActionExecutor executor = new InspectingContinuationActionExecutor();
        JavaRunnerContextImpl context = createContext(new ActionState(null), executor);
        TestDurableCallable<String> first =
                new TestDurableCallable<>("batch-1", String.class, () -> "one");
        TestDurableCallable<String> second =
                new TestDurableCallable<>("batch-2", String.class, () -> "two");

        List<Outcome<String>> outcomes = context.durableExecuteAllAsync(List.of(first, second));

        assertEquals("one", outcomes.get(0).getValue());
        assertEquals("two", outcomes.get(1).getValue());
        assertEquals(1, executor.getExecuteAllAsyncCallCount());
        assertEquals(List.of(2), executor.getExecuteAllAsyncBatchSizes());
        assertEquals(1, first.getCallCount());
        assertEquals(1, second.getCallCount());
        assertEquals(3, persistCallCount.get());
        assertEquals(2, context.getDurableExecutionContext().getCurrentCallIndex());
        List<CallResult> persisted =
                context.getDurableExecutionContext().getActionState().getCallResults();
        assertEquals(2, persisted.size());
        assertEquals("batch-1", persisted.get(0).getFunctionId());
        assertTrue(persisted.get(0).isSuccess());
        assertEquals("batch-2", persisted.get(1).getFunctionId());
        assertTrue(persisted.get(1).isSuccess());
    }

    @Test
    void testDurableExecuteAllAsyncReconcilesPendingSlot() throws Exception {
        InspectingContinuationActionExecutor executor = new InspectingContinuationActionExecutor();
        ActionState actionState = new ActionState(null);
        actionState.addCallResult(CallResult.pending("batch-1", ""));
        JavaRunnerContextImpl context = createContext(actionState, executor);
        TestReconcilableCallable<String> callable =
                new TestReconcilableCallable<>(
                        "batch-1",
                        String.class,
                        () -> fail("call should not be executed"),
                        () -> "recovered");

        List<Outcome<String>> outcomes = context.durableExecuteAllAsync(List.of(callable));

        assertEquals("recovered", outcomes.get(0).getValue());
        assertEquals(0, callable.getCallCount());
        assertEquals(1, callable.getReconcileCount());
        assertEquals(1, executor.getExecuteAllAsyncCallCount());
        assertEquals(1, persistCallCount.get());
        assertTrue(actionState.getCallResults().get(0).isSuccess());
        assertEquals(1, context.getDurableExecutionContext().getCurrentCallIndex());
    }

    @Test
    void testDurableExecuteAllAsyncRecoversPartialFinalizedBatch() throws Exception {
        InspectingContinuationActionExecutor executor = new InspectingContinuationActionExecutor();
        ActionState actionState = new ActionState(null);
        actionState.addCallResult(
                new CallResult("batch-1", "", OBJECT_MAPPER.writeValueAsBytes("cached-one")));
        actionState.addCallResult(
                new CallResult("batch-2", "", OBJECT_MAPPER.writeValueAsBytes("cached-two")));
        actionState.addCallResult(CallResult.pending("batch-3", ""));
        JavaRunnerContextImpl context = createContext(actionState, executor);
        TestDurableCallable<String> first =
                new TestDurableCallable<>(
                        "batch-1", String.class, () -> fail("cached slot should not execute"));
        TestDurableCallable<String> second =
                new TestDurableCallable<>(
                        "batch-2", String.class, () -> fail("cached slot should not execute"));
        TestDurableCallable<String> third =
                new TestDurableCallable<>("batch-3", String.class, () -> "fresh-three");

        List<Outcome<String>> outcomes =
                context.durableExecuteAllAsync(List.of(first, second, third));

        assertEquals("cached-one", outcomes.get(0).getValue());
        assertEquals("cached-two", outcomes.get(1).getValue());
        assertEquals("fresh-three", outcomes.get(2).getValue());
        assertEquals(0, first.getCallCount());
        assertEquals(0, second.getCallCount());
        assertEquals(1, third.getCallCount());
        assertEquals(1, executor.getExecuteAllAsyncCallCount());
        assertEquals(List.of(1), executor.getExecuteAllAsyncBatchSizes());
        assertEquals("batch-3", actionState.getCallResults().get(2).getFunctionId());
        assertTrue(actionState.getCallResults().get(2).isSuccess());
        assertEquals(1, persistCallCount.get());
        assertEquals(3, context.getDurableExecutionContext().getCurrentCallIndex());
    }

    @Test
    void testDurableExecuteAllAsyncReturnsCachedFailureOutcome() throws Exception {
        InspectingContinuationActionExecutor executor = new InspectingContinuationActionExecutor();
        ActionState actionState = new ActionState(null);
        actionState.addCallResult(
                new CallResult(
                        "batch-1",
                        "",
                        null,
                        OBJECT_MAPPER.writeValueAsBytes(
                                RunnerContextImpl.DurableExecutionException.fromException(
                                        new IllegalStateException("cached failure")))));
        JavaRunnerContextImpl context = createContext(actionState, executor);
        TestDurableCallable<String> callable =
                new TestDurableCallable<>(
                        "batch-1", String.class, () -> fail("cached slot should not execute"));

        List<Outcome<String>> outcomes = context.durableExecuteAllAsync(List.of(callable));

        assertTrue(outcomes.get(0).isFailure());
        assertTrue(outcomes.get(0).getError().getMessage().contains("cached failure"));
        assertEquals(0, callable.getCallCount());
        assertEquals(0, executor.getExecuteAllAsyncCallCount());
        assertEquals(0, persistCallCount.get());
        assertEquals(1, context.getDurableExecutionContext().getCurrentCallIndex());
    }

    @Test
    void testDurableExecuteAllAsyncTimeoutKeepsCompletedOutcomes() throws Exception {
        InspectingContinuationActionExecutor executor = new InspectingContinuationActionExecutor();
        executor.setUseTimeoutCollection(true);
        JavaRunnerContextImpl context = createContext(new ActionState(null), executor);
        ((Configuration) context.getConfig())
                .set(AgentExecutionOptions.TOOL_CALL_BATCH_TIMEOUT_MS, 10L);
        TestDurableCallable<String> first =
                new TestDurableCallable<>("batch-1", String.class, () -> "fast");
        TestDurableCallable<String> second =
                new TestDurableCallable<>(
                        "batch-2",
                        String.class,
                        () -> {
                            Thread.sleep(100);
                            return "slow";
                        });

        List<Outcome<String>> outcomes = context.durableExecuteAllAsync(List.of(first, second));

        assertEquals("fast", outcomes.get(0).getValue());
        assertTrue(outcomes.get(1).isFailure());
        assertInstanceOf(TimeoutException.class, outcomes.get(1).getError());
        assertEquals(Duration.ofMillis(10), executor.getLastExecuteAllAsyncTimeout());
        assertEquals(1, first.getCallCount());
        assertEquals(1, second.getCallCount());
        List<CallResult> persisted =
                context.getDurableExecutionContext().getActionState().getCallResults();
        assertTrue(persisted.get(0).isSuccess());
        assertTrue(persisted.get(1).isFailure());
        assertEquals(2, context.getDurableExecutionContext().getCurrentCallIndex());
        executor.close();
    }

    @Test
    void testDurableExecuteAllAsyncFinalizeFailureAbortsBatch() throws Exception {
        InspectingContinuationActionExecutor executor = new InspectingContinuationActionExecutor();
        FailingSerializeOnValueContext context =
                new FailingSerializeOnValueContext(
                        metricGroup,
                        () -> {},
                        new AgentPlan(new HashMap<>(), new HashMap<>()),
                        null,
                        "test-job",
                        executor,
                        "two");
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
                        new ActionState(null),
                        persister));
        TestDurableCallable<String> first =
                new TestDurableCallable<>("batch-1", String.class, () -> "one");
        TestDurableCallable<String> second =
                new TestDurableCallable<>("batch-2", String.class, () -> "two");

        JsonProcessingException thrown =
                assertThrows(
                        JsonProcessingException.class,
                        () -> context.durableExecuteAllAsync(List.of(first, second)));

        assertTrue(thrown.getMessage().contains("serialize failed"));
        List<CallResult> persisted =
                context.getDurableExecutionContext().getActionState().getCallResults();
        assertTrue(persisted.get(0).isSuccess());
        assertEquals(0, context.getDurableExecutionContext().getCurrentCallIndex());
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
        private boolean useTimeoutCollection;
        private Duration lastExecuteAllAsyncTimeout;
        private int executeAsyncCallCount;
        private int executeAllAsyncCallCount;
        private final List<Integer> executeAllAsyncBatchSizes = new java.util.ArrayList<>();

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

        @Override
        public <T> List<Outcome<T>> executeAllAsync(
                ContinuationContext context,
                List<Callable<T>> suppliers,
                Duration timeout) {
            executeAllAsyncCallCount++;
            executeAllAsyncBatchSizes.add(suppliers.size());
            lastExecuteAllAsyncTimeout = timeout;
            if (useTimeoutCollection) {
                return executeAllAsyncWithDeadline(suppliers, timeout);
            }
            List<Outcome<T>> outcomes = new java.util.ArrayList<>(suppliers.size());
            for (Callable<T> supplier : suppliers) {
                try {
                    outcomes.add(Outcome.success(supplier.call()));
                } catch (Exception e) {
                    outcomes.add(Outcome.failure(e));
                }
            }
            return outcomes;
        }

        private <T> List<Outcome<T>> executeAllAsyncWithDeadline(
                List<Callable<T>> suppliers, Duration timeout) {
            List<CompletableFuture<Outcome<T>>> futures = new java.util.ArrayList<>(suppliers.size());
            for (Callable<T> supplier : suppliers) {
                futures.add(
                        CompletableFuture.supplyAsync(
                                () -> {
                                    try {
                                        return Outcome.success(supplier.call());
                                    } catch (Exception e) {
                                        return Outcome.<T>failure(e);
                                    }
                                }));
            }

            CompletableFuture<Void> barrier =
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            long deadlineNanos = getDeadlineNanos(timeout);
            while (!barrier.isDone()) {
                if (System.nanoTime() >= deadlineNanos) {
                    TimeoutException exception =
                            new TimeoutException(
                                    "Async durable batch execution timed out after " + timeout);
                    barrier.cancel(true);
                    return collectBatchOutcomesOnTimeout(futures, exception);
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    TimeoutException exception =
                            new TimeoutException("Async durable batch execution interrupted");
                    return collectBatchOutcomesOnTimeout(futures, exception);
                }
            }
            return collectBatchOutcomes(futures);
        }

        private static long getDeadlineNanos(Duration timeout) {
            return timeout == null || timeout.isZero() || timeout.isNegative()
                    ? Long.MAX_VALUE
                    : System.nanoTime() + timeout.toNanos();
        }

        private static <T> List<Outcome<T>> collectBatchOutcomes(
                List<CompletableFuture<Outcome<T>>> futures) {
            List<Outcome<T>> results = new java.util.ArrayList<>(futures.size());
            for (CompletableFuture<Outcome<T>> future : futures) {
                results.add(future.join());
            }
            return results;
        }

        private static <T> List<Outcome<T>> collectBatchOutcomesOnTimeout(
                List<CompletableFuture<Outcome<T>>> futures,
                TimeoutException timeoutException) {
            List<Outcome<T>> results = new java.util.ArrayList<>(futures.size());
            for (CompletableFuture<Outcome<T>> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
                if (future.isDone() && !future.isCancelled()) {
                    results.add(future.join());
                } else {
                    results.add(Outcome.failure(timeoutException));
                }
            }
            return results;
        }

        private void setBeforeExecute(Runnable beforeExecute) {
            this.beforeExecute = beforeExecute;
        }

        private void setUseTimeoutCollection(boolean useTimeoutCollection) {
            this.useTimeoutCollection = useTimeoutCollection;
        }

        private int getExecuteAsyncCallCount() {
            return executeAsyncCallCount;
        }

        private int getExecuteAllAsyncCallCount() {
            return executeAllAsyncCallCount;
        }

        private List<Integer> getExecuteAllAsyncBatchSizes() {
            return executeAllAsyncBatchSizes;
        }

        private Duration getLastExecuteAllAsyncTimeout() {
            return lastExecuteAllAsyncTimeout;
        }
    }

    private static final class FailingSerializeOnValueContext extends JavaRunnerContextImpl {
        private final String failOnResult;

        private FailingSerializeOnValueContext(
                FlinkAgentsMetricGroupImpl agentMetricGroup,
                Runnable mailboxThreadChecker,
                AgentPlan agentPlan,
                org.apache.flink.agents.runtime.ResourceCache resourceCache,
                String jobIdentifier,
                ContinuationActionExecutor continuationExecutor,
                String failOnResult) {
            super(
                    agentMetricGroup,
                    mailboxThreadChecker,
                    agentPlan,
                    resourceCache,
                    jobIdentifier,
                    continuationExecutor);
            this.failOnResult = failOnResult;
        }

        @Override
        protected byte[] serializeDurableResult(Object result) throws JsonProcessingException {
            if (failOnResult != null && failOnResult.equals(result)) {
                throw new JsonProcessingException("serialize failed") {};
            }
            return super.serializeDurableResult(result);
        }
    }
}
