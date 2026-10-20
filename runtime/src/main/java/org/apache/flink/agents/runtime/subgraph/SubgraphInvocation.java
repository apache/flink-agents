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
import org.apache.flink.agents.runtime.memory.MemoryScope;
import org.apache.flink.agents.runtime.operator.parallel.UncapturableException;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Runtime-only state of one internal graph call, shared by Java and Python.
 *
 * <p>All mutations run on the mailbox thread or under the parallel execution lock. A logical action
 * remains in flight across yield/resume; it is retired only after its durable output has been
 * dispatched. This accounts for buffered events and nested calls without counting continuation
 * tasks as new executions. Each dispatch registers all successor actions before its producer is
 * retired.
 *
 * <p>The future is not checkpointed. Root replay rebuilds this object and child ActionState
 * supplies completed work. Waiters capture this object instead of reading a switched context.
 */
public class SubgraphInvocation<R> {
    private final String id;
    private final String durableSlotKey;
    @Nullable private final Object key;
    private final SubgraphDefinition<R> definition;
    @Nullable private final MemoryScope memoryScope;
    @Nullable private final RunnerContextImpl.ResourceScope resourceScope;
    private final CompletableFuture<R> response = new CompletableFuture<>();
    private final List<Event> outputs = new ArrayList<>();
    private int runningActions;
    private boolean started;
    private boolean replayed;
    @Nullable private IllegalStateException failure;

    /** Creates a call with no memory policy of its own: its actions use the enclosing stores. */
    public SubgraphInvocation(
            String id,
            @Nullable Object key,
            SubgraphDefinition<R> definition,
            @Nullable RunnerContextImpl.ResourceScope resourceScope) {
        this(id, key, definition, null, resourceScope);
    }

    public SubgraphInvocation(
            String id,
            @Nullable Object key,
            SubgraphDefinition<R> definition,
            @Nullable MemoryScope memoryScope,
            @Nullable RunnerContextImpl.ResourceScope resourceScope) {
        this.id = id;
        // Ids are only unique within their kind, so the slot carries the kind too.
        this.durableSlotKey = definition.kind() + ":" + id;
        this.key = key;
        this.definition = definition;
        this.memoryScope = memoryScope;
        this.resourceScope = resourceScope;
    }

    public final String getId() {
        return id;
    }

    /** The caller's durable slot this call's terminal result is recorded in and read back from. */
    public final String getDurableSlotKey() {
        return durableSlotKey;
    }

    @Nullable
    public final Object getKey() {
        return key;
    }

    /** The stores this call's actions share, or {@code null} to inherit the enclosing ones. */
    @Nullable
    public final MemoryScope getMemoryScope() {
        return memoryScope;
    }

    @Nullable
    public final RunnerContextImpl.ResourceScope getResourceScope() {
        return resourceScope;
    }

    public final boolean isDone() {
        return response.isDone();
    }

    /** Whether the terminal outcome came from the caller's durable slot rather than the graph. */
    public final boolean isReplayed() {
        return replayed;
    }

    /** Routes one persisted output or an authorized bootstrap under operator ownership. */
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

    /**
     * Only ordinary action-body failures can become a graph outcome. Anything carrying an {@link
     * UncapturableException} is excluded too, so a framework fault fails the job instead of being
     * reported as this call's own result. The scan walks causes because either can arrive wrapped.
     */
    public final boolean capturesActionFailure(Throwable failure) {
        if (!(failure instanceof Exception) || CancellationUtils.isCancellation(failure)) {
            return false;
        }
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 64; depth++, cause = cause.getCause()) {
            if (cause instanceof Error || cause instanceof UncapturableException) {
                return false;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return capturesActionFailures();
    }

    /**
     * Whether this call's graph failure belongs to its own result instead of failing the caller.
     */
    public final boolean capturesActionFailures() {
        return definition.capturesActionFailures();
    }

    public final R captureActionFailure(Throwable cause) {
        return definition.captureActionFailure(cause);
    }

    public final Class<R> resultClass() {
        return definition.resultClass();
    }

    public final Event inputEvent(Object input) {
        return definition.inputEvent(input);
    }

    public final void cancel() {
        response.cancel(false);
    }

    /**
     * Reuses a recorded terminal outcome without starting the graph, marking the call replayed so
     * its result is not persisted a second time.
     */
    public final void installRecorded(R result) {
        if (started || !response.complete(result)) {
            throw new IllegalStateException("Duplicate subgraph result: " + id);
        }
        replayed = true;
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
