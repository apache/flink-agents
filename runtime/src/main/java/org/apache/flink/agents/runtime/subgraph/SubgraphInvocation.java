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
package org.apache.flink.agents.runtime.subgraph;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.utils.CancellationUtils;
import org.apache.flink.agents.runtime.context.JavaRunnerContextImpl;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Runtime-only state of one internal graph call, shared by Java and Python.
 *
 * <p>All mutations run on the mailbox. A logical action remains in flight across yield/resume; it
 * is retired only after its durable output has been dispatched. This accounts for buffered events
 * and nested calls without counting continuation tasks as new executions. Each dispatch registers
 * all successor actions before its producer is retired.
 *
 * <p>The future is not checkpointed. Root replay rebuilds this object and child ActionState
 * supplies completed work. Waiters capture this object instead of reading a switched context.
 */
public class SubgraphInvocation<R> {
    private final String id;
    @Nullable private final Object key;
    private final SubgraphDefinition<R> definition;
    private final RunnerContextImpl.MemoryContext memory;
    @Nullable private final RunnerContextImpl.ResourceScope resourceScope;
    private final CompletableFuture<R> response = new CompletableFuture<>();
    private final List<Event> outputs = new ArrayList<>();
    private int runningActions;
    private boolean started;
    @Nullable private IllegalStateException failure;

    public SubgraphInvocation(
            String id,
            @Nullable Object key,
            SubgraphDefinition<R> definition,
            RunnerContextImpl.MemoryContext memory,
            @Nullable RunnerContextImpl.ResourceScope resourceScope) {
        this.id = id;
        this.key = key;
        this.definition = definition;
        this.memory = memory;
        this.resourceScope = resourceScope;
    }

    public final String getId() {
        return id;
    }

    @Nullable
    public final Object getKey() {
        return key;
    }

    public final RunnerContextImpl.MemoryContext getMemory() {
        return memory;
    }

    @Nullable
    public final RunnerContextImpl.ResourceScope getResourceScope() {
        return resourceScope;
    }

    public final boolean isDone() {
        return response.isDone();
    }

    /** Routes one persisted output or an authorized bootstrap on the mailbox thread. */
    public final List<Action> dispatch(Event event) {
        if (failure != null) {
            // Already scheduled siblings may finish after a captured child failure. Their outputs
            // must not re-open the failed call or escape into the outer graph.
            return List.of();
        }
        if (response.isDone()) {
            throw new IllegalStateException("Event dispatched after subgraph completion: " + id);
        }
        started = true;
        if (event instanceof SubgraphFailureEvent) {
            failure = ((SubgraphFailureEvent) event).asException();
            tryComplete();
            return List.of();
        }
        List<Action> actions;
        if (definition.isOutput(event)) {
            outputs.add(event);
            actions = List.of();
        } else {
            actions = definition.matchActions(event);
            runningActions += actions.size();
        }
        tryComplete();
        return actions;
    }

    /** Retires a logical action after persistence and dispatch, never on an async yield. */
    public final void completeAction() {
        if (runningActions <= 0) {
            throw new IllegalStateException("Unbalanced subgraph action completion: " + id);
        }
        runningActions--;
        tryComplete();
    }

    /** Only ordinary action-body failures can become a graph outcome. */
    public final boolean capturesActionFailure(Throwable failure) {
        if (!(failure instanceof Exception) || CancellationUtils.isCancellation(failure)) {
            return false;
        }
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 64; depth++, cause = cause.getCause()) {
            if (cause instanceof Error) {
                return false;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return definition.capturesActionFailures();
    }

    public final void cancel() {
        response.cancel(false);
    }

    /** Reuses a recorded terminal outcome without starting the graph. */
    protected final void replay(R result) {
        if (started || !response.complete(result)) {
            throw new IllegalStateException("Duplicate subgraph result: " + id);
        }
    }

    public final R awaitResult() throws InterruptedException, ExecutionException {
        return response.get();
    }

    /** Awaits completion by yielding the Java caller's continuation without holding a worker. */
    public final R awaitResult(JavaRunnerContextImpl context) throws Exception {
        return context.awaitCompletableFuture(response);
    }

    private void tryComplete() {
        if (started && runningActions == 0 && !response.isDone()) {
            if (failure != null) {
                // Keep the caller suspended until scheduled siblings retire, so root-only
                // checkpoint recovery always has a caller from which to rebuild the graph.
                response.completeExceptionally(failure);
                return;
            }
            // Invalid/missing terminal output is a protocol error, not a business error result.
            R result = definition.finish(List.copyOf(outputs));
            response.complete(result);
        }
    }
}
