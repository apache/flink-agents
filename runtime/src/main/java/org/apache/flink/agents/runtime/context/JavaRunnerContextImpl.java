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
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.async.ContinuationContext;
import org.apache.flink.agents.runtime.metrics.FlinkAgentsMetricGroupImpl;

import java.time.Duration;
import java.util.ArrayList;
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
    private final ContinuationActionExecutor toolCallContinuationExecutor;
    private ContinuationContext continuationContext;

    public JavaRunnerContextImpl(
            FlinkAgentsMetricGroupImpl agentMetricGroup,
            Runnable mailboxThreadChecker,
            AgentPlan agentPlan,
            ResourceCache resourceCache,
            String jobIdentifier,
            ContinuationActionExecutor continuationExecutor,
            ContinuationActionExecutor toolCallContinuationExecutor) {
        super(agentMetricGroup, mailboxThreadChecker, agentPlan, resourceCache, jobIdentifier);
        this.continuationExecutor = continuationExecutor;
        this.toolCallContinuationExecutor = toolCallContinuationExecutor;
    }

    public JavaRunnerContextImpl(
            FlinkAgentsMetricGroupImpl agentMetricGroup,
            Runnable mailboxThreadChecker,
            AgentPlan agentPlan,
            ResourceCache resourceCache,
            String jobIdentifier,
            ContinuationActionExecutor continuationExecutor) {
        this(
                agentMetricGroup,
                mailboxThreadChecker,
                agentPlan,
                resourceCache,
                jobIdentifier,
                continuationExecutor,
                continuationExecutor);
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
    public <T> T durableExecuteAsync(DurableCallable<T> callable) throws Exception {
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
    public <T> List<Outcome<T>> durableExecuteAllAsync(List<DurableCallable<T>> callables)
            throws Exception {
        if (callables.isEmpty()) {
            return List.of();
        }
        if (durableExecutionContext == null) {
            return executeAllWithoutDurableState(callables);
        }

        String argsDigest = "";
        int base = durableExecutionContext.getCurrentCallIndex();
        BatchExecutionPlan<T> plan = buildBatchExecutionPlan(callables, base, argsDigest);

        reservePendingBatchIfNeeded(callables, argsDigest, plan);

        List<Outcome<T>> executed = executeOutcomeSuppliers(plan.suppliers);
        finalizeExecutedOutcomes(callables, base, argsDigest, plan, executed);

        advanceCallIndexBy(callables.size());
        return plan.outcomes;
    }

    private <T> BatchExecutionPlan<T> buildBatchExecutionPlan(
            List<DurableCallable<T>> callables, int base, String argsDigest) throws Exception {
        BatchExecutionPlan<T> plan = new BatchExecutionPlan<>(callables.size());
        for (int i = 0; i < callables.size(); i++) {
            DurableCallable<T> callable = callables.get(i);
            CallResult current = getCallResultAt(base + i);
            if (current == null) {
                markReservationStart(plan, i);
                addExecutableCall(plan, i, callable::call);
                continue;
            }
            if (!current.matches(callable.getId(), argsDigest)) {
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
                                base + i, callable.getId(), argsDigest, callable.getResultClass()));
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
            List<DurableCallable<T>> callables, String argsDigest, BatchExecutionPlan<T> plan) {
        if (!plan.needsReservation) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (DurableCallable<T> callable :
                callables.subList(plan.executionStart, callables.size())) {
            ids.add(callable.getId());
        }
        reservePendingBatch(ids, argsDigest);
    }

    private <T> void finalizeExecutedOutcomes(
            List<DurableCallable<T>> callables,
            int base,
            String argsDigest,
            BatchExecutionPlan<T> plan,
            List<Outcome<T>> executed)
            throws Exception {
        for (int i = 0; i < executed.size(); i++) {
            int callIndex = plan.executableCallIndexes.get(i);
            Outcome<T> outcome = executed.get(i);
            DurableCallable<T> callable = callables.get(callIndex);
            finalizeCallAt(
                    base + callIndex,
                    callable.getId(),
                    argsDigest,
                    serializeDurableResult(outcome.getValue()),
                    serializeDurableException(outcome.getError()));
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
            throws Exception {
        List<Callable<T>> suppliers = new ArrayList<>();
        for (DurableCallable<T> callable : callables) {
            suppliers.add(callable::call);
        }
        return executeOutcomeSuppliers(suppliers);
    }

    private <T> List<Outcome<T>> executeOutcomeSuppliers(List<Callable<T>> suppliers)
            throws Exception {
        if (suppliers.isEmpty()) {
            return List.of();
        }
        if (continuationExecutor == null || continuationContext == null) {
            return suppliers.stream()
                    .map(
                            supplier -> {
                                try {
                                    return Outcome.success(supplier.call());
                                } catch (Exception e) {
                                    return Outcome.<T>failure(e);
                                }
                            })
                    .collect(Collectors.toList());
        }
        Duration timeout = getConfig().get(AgentExecutionOptions.TOOL_CALL_BATCH_TIMEOUT);
        return toolCallContinuationExecutor.executeAllAsync(
                continuationContext, suppliers, timeout);
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
