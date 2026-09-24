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

import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.Outcome;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.actionstate.CallResult;
import org.apache.flink.agents.runtime.async.BatchExecutionResult;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Java-specific implementation of RunnerContext that includes ContinuationActionExecutor for async
 * execution support.
 */
public class JavaRunnerContextImpl extends RunnerContextImpl {
    private final ContinuationActionExecutor continuationExecutor;
    private ContinuationContext continuationContext;

    public JavaRunnerContextImpl(
            FlinkAgentsMetricGroupImpl agentMetricGroup,
            Runnable mailboxThreadChecker,
            AgentPlan agentPlan,
            ResourceCache resourceCache,
            String jobIdentifier,
            ContinuationActionExecutor continuationExecutor) {
        super(agentMetricGroup, mailboxThreadChecker, agentPlan, resourceCache, jobIdentifier);
        this.continuationExecutor = continuationExecutor;
    }

    public ContinuationActionExecutor getContinuationExecutor() {
        return continuationExecutor;
    }

    public void setContinuationContext(ContinuationContext continuationContext) {
        this.continuationContext = continuationContext;
    }

    public ContinuationContext getContinuationContext() {
        return continuationContext;
    }

    @Override
    protected <T> T resolveDurableAsync(DurableCallable<T> callable) throws Exception {
        if (durableExecutionContext != null) {
            Callable<T> reconcileCallable = callable.reconciler();
            if (reconcileCallable != null) {
                return durableExecuteAsyncWithReconcile(callable, reconcileCallable);
            }
        }
        return durableExecuteCompletionOnly(callable, () -> executeAsyncCallable(callable));
    }

    private <T> T durableExecuteAsyncWithReconcile(
            DurableCallable<T> callable, Callable<T> reconcileCallable) throws Exception {
        return durableExecuteWithReconcile(
                callable, reconcileCallable, () -> executeAsyncCallable(callable));
    }

    @Override
    protected <T> List<Outcome<T>> resolveDurableBatch(List<DurableCallable<T>> callables)
            throws Exception {
        if (callables.isEmpty()) {
            return List.of();
        }
        if (durableExecutionContext == null) {
            return executeAllWithoutDurableState(callables);
        }

        int base = durableExecutionContext.getCurrentCallIndex();
        BatchExecutionPlan<T> plan = buildBatchExecutionPlan(callables, base);

        reservePendingBatchIfNeeded(callables, plan);

        BatchExecutionResult<T> executed = executeOutcomeSuppliers(plan.suppliers);
        finalizeExecutedOutcomes(callables, base, plan, executed);

        advanceCallIndexBy(callables.size());
        return plan.outcomes;
    }

    private <T> BatchExecutionPlan<T> buildBatchExecutionPlan(
            List<DurableCallable<T>> callables, int base) throws Exception {
        BatchExecutionPlan<T> plan = new BatchExecutionPlan<>(callables.size());
        for (int i = 0; i < callables.size(); i++) {
            DurableCallable<T> callable = callables.get(i);
            CallResult current = getCallResultAt(base + i);
            if (current == null) {
                markReservationStart(plan, i);
                addExecutableCall(plan, i, callable::call);
                continue;
            }
            if (!current.matches(callable.getId())) {
                clearCallResultsFromAndPersist(base + i);
                plan.needsReservation = true;
                plan.executionStart = i;
                addExecutableCall(plan, i, callable::call);
                appendRemainingExecutions(callables, plan, i + 1);
                break;
            }
            if (current.isPending()) {
                Callable<T> reconcileCallable = callable.reconciler();
                Callable<T> executionCallable =
                        reconcileCallable != null ? reconcileCallable : callable::call;
                addExecutableCall(plan, i, executionCallable);
            } else {
                plan.outcomes.add(
                        readTerminalOutcomeAt(
                                base + i, callable.getId(), callable.getResultClass()));
            }
        }
        return plan;
    }

    private <T> void markReservationStart(BatchExecutionPlan<T> plan, int callIndex) {
        plan.needsReservation = true;
        if (plan.executionStart < 0) {
            plan.executionStart = callIndex;
        }
    }

    private <T> void addExecutableCall(
            BatchExecutionPlan<T> plan, int callIndex, Callable<T> executionCallable) {
        plan.outcomes.add(null);
        plan.suppliers.add(executionCallable);
        plan.executableCallIndexes.add(callIndex);
    }

    private <T> void appendRemainingExecutions(
            List<DurableCallable<T>> callables, BatchExecutionPlan<T> plan, int startIndex) {
        for (int i = startIndex; i < callables.size(); i++) {
            DurableCallable<T> remaining = callables.get(i);
            addExecutableCall(plan, i, remaining::call);
        }
    }

    private <T> void reservePendingBatchIfNeeded(
            List<DurableCallable<T>> callables, BatchExecutionPlan<T> plan) {
        if (!plan.needsReservation) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (DurableCallable<T> callable :
                callables.subList(plan.executionStart, callables.size())) {
            ids.add(callable.getId());
        }
        reservePendingBatch(ids);
    }

    private <T> void finalizeExecutedOutcomes(
            List<DurableCallable<T>> callables,
            int base,
            BatchExecutionPlan<T> plan,
            BatchExecutionResult<T> executed)
            throws Exception {
        for (int i = 0; i < executed.getOutcomes().size(); i++) {
            int callIndex = plan.executableCallIndexes.get(i);
            Outcome<T> outcome = executed.getOutcomes().get(i);
            DurableCallable<T> callable = callables.get(callIndex);
            if (!executed.wasStarted(i)) {
                plan.outcomes.set(callIndex, outcome);
                continue;
            }
            if (outcome.isFailure() && outcome.getError() instanceof InterruptedException) {
                // A cancellation signal a batch executor caught and folded into a failed
                // Outcome, not a genuine tool failure: leave this slot and every slot after it
                // in the batch unfinalized (pending), so recovery re-executes them, and
                // propagate immediately instead of persisting it as a durable failure. Slots
                // finalized earlier in this loop keep their persisted outcome.
                Thread.currentThread().interrupt();
                throw (InterruptedException) outcome.getError();
            }
            try {
                finalizeCallAt(
                        base + callIndex,
                        callable.getId(),
                        serializeDurableResult(outcome.getValue()),
                        serializeDurableException(outcome.getError()));
            } catch (Exception e) {
                outcome = Outcome.failure(e);
            }
            plan.outcomes.set(callIndex, outcome);
        }
    }

    private static class BatchExecutionPlan<T> {
        private final List<Outcome<T>> outcomes;
        private final List<Callable<T>> suppliers = new ArrayList<>();
        private final List<Integer> executableCallIndexes = new ArrayList<>();
        private boolean needsReservation;
        private int executionStart = -1;

        private BatchExecutionPlan(int size) {
            this.outcomes = new ArrayList<>(size);
        }
    }

    private <T> List<Outcome<T>> executeAllWithoutDurableState(List<DurableCallable<T>> callables)
            throws InterruptedException {
        List<Callable<T>> suppliers = new ArrayList<>();
        for (DurableCallable<T> callable : callables) {
            suppliers.add(callable::call);
        }
        List<Outcome<T>> outcomes = executeOutcomeSuppliers(suppliers).getOutcomes();
        for (Outcome<T> outcome : outcomes) {
            if (outcome.isFailure() && outcome.getError() instanceof InterruptedException) {
                // Without a durable store there is nothing to leave pending, but a cancellation
                // signal folded into a failed Outcome must still propagate as an interruption
                // instead of being handed to the caller as an ordinary tool failure.
                Thread.currentThread().interrupt();
                throw (InterruptedException) outcome.getError();
            }
        }
        return outcomes;
    }

    private <T> BatchExecutionResult<T> executeOutcomeSuppliers(List<Callable<T>> suppliers) {
        if (suppliers.isEmpty()) {
            return new BatchExecutionResult<>(List.of(), new boolean[0]);
        }
        if (continuationExecutor == null || continuationContext == null) {
            List<Outcome<T>> outcomes =
                    suppliers.stream()
                            .map(
                                    supplier -> {
                                        try {
                                            return Outcome.success(supplier.call());
                                        } catch (Exception e) {
                                            return Outcome.<T>failure(e);
                                        }
                                    })
                            .collect(Collectors.toList());
            boolean[] started = new boolean[suppliers.size()];
            Arrays.fill(started, true);
            return new BatchExecutionResult<>(outcomes, started);
        }
        Long timeoutMs = getConfig().get(AgentExecutionOptions.TOOL_CALL_BATCH_TIMEOUT_MS);
        Duration timeout =
                timeoutMs == null || timeoutMs <= 0 ? null : Duration.ofMillis(timeoutMs);
        Integer parallelism = getConfig().get(AgentExecutionOptions.TOOL_CALL_PARALLELISM);
        int parallelismCap = parallelism == null ? 1 : Math.max(parallelism, 1);
        return continuationExecutor.executeAllAsync(
                continuationContext, suppliers, timeout, parallelismCap);
    }

    private <T> T executeAsyncCallable(DurableCallable<T> callable) throws Exception {

        Supplier<T> wrappedSupplier =
                () -> {
                    T innerResult = null;
                    Exception innerException = null;
                    try {
                        innerResult = callable.call();
                    } catch (Exception e) {
                        innerException = e;
                    }

                    if (innerException != null) {
                        throw new DurableExecutionRuntimeException(innerException);
                    }
                    return innerResult;
                };

        try {
            if (continuationExecutor == null || continuationContext == null) {
                return wrappedSupplier.get();
            } else {
                return continuationExecutor.executeAsync(continuationContext, wrappedSupplier);
            }
        } catch (DurableExecutionRuntimeException e) {
            throw (Exception) e.getCause();
        }
    }

    @Override
    public void close() throws Exception {
        super.close();
    }
}
