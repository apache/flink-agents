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
import org.apache.flink.agents.api.context.Outcome;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.context.JavaRunnerContextImpl;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.memory.EventAttachmentUtils;
import org.apache.flink.agents.runtime.operator.parallel.UncapturableException;
import org.apache.flink.util.ExceptionUtils;

import javax.annotation.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * Shared execution chain for internal graph calls, with durable result reuse and runtime-specific
 * waiting.
 *
 * <p>Every step after {@link #prepare} names its call by the id that step returned, never by the
 * invocation, so a caller can prepare every member of a group before waiting for any and their
 * graphs run concurrently.
 *
 * <p>Two driving protocols are built from the same steps. The blocking one runs {@link #prepare},
 * {@link #await} and {@link #finish} in one turn, with {@link #requireReleasableOperator} as its
 * fail-fast guard. The polling one, for a bridge that drives its call from a generator yielding
 * between polls, pairs {@link #prepare} with {@link #tryFinish}, which returns {@code null} while
 * the graph still runs; releasing the operator is that yield's job, so such a bridge never calls
 * {@link #await}.
 *
 * <p>What a built-in chat call and an internal sub-agent call differ in lives in the {@link
 * SubgraphDefinition} the call's kind supplies. Nothing here is checkpointed: a replay rebuilds the
 * invocation from the caller's identity and the durable slot supplies the completed work.
 */
public final class SubgraphExecution {

    private SubgraphExecution() {}

    /**
     * Registers one call and either installs its recorded terminal result or emits the event that
     * starts its graph. Returns the id used by later steps. Requires operator ownership.
     *
     * <p>A recorded terminal result skips input construction and child execution. Without one,
     * recovery starts the graph again and may reuse its actions' recorded state.
     *
     * <p>The journal is an ordered cursor and one call may consult it only once, so a caller that
     * prepares through here must not read the slot again itself.
     */
    public static <R> String prepare(
            RunnerContextImpl context, SubgraphInvocation<R> invocation, Object input)
            throws Exception {
        context.checkMailboxThread();
        context.getSubgraphCalls().register(invocation);
        Optional<Outcome<R>> recorded =
                context.tryGetCachedResult(
                        invocation.getDurableSlotKey(), invocation.resultClass());
        if (recorded.isPresent()) {
            invocation.installRecorded(recorded.get().getValue());
            return invocation.getId();
        }
        Event delegate = invocation.inputEvent(input);
        // The delegate rides inside the envelope, so its attachments must be offloaded before the
        // envelope's own JSON-serializability is checked.
        EventAttachmentUtils.storeEventAttachments(delegate, context);
        // Start a nested graph; the caller remains in flight while awaiting its result.
        context.addPendingEvent(new SubgraphEvent(invocation.getId(), delegate, true));
        // Under continuations the caller yields next and the operator drains the bootstrap then.
        // An engine whose action never yields installs a dispatcher here instead, so the graph
        // starts before the caller waits for it rather than after it finishes.
        context.dispatchSubgraphBootstrap();
        return invocation.getId();
    }

    /**
     * Releases the operator while the graph of the call registered under {@code id} runs, so it can
     * dispatch the graph's actions. A call whose result came from the durable slot is already done
     * and does not yield.
     *
     * <p>Waiting is all this does; {@link #finish} takes the result. A call whose graph failure is
     * its own result therefore tolerates the wait's exception rather than failing the caller with
     * it.
     *
     * <p>Yielding a continuation holds no async-pool worker. The parallel engine instead waits on
     * the caller's worker and hands off only the execution lock, so its pool must hold both the
     * caller and the graph's work.
     */
    public static void await(RunnerContextImpl context, String id) throws Exception {
        SubgraphInvocation<?> invocation = context.getSubgraphCalls().get(id);
        if (invocation.isDone()) {
            return;
        }
        try {
            JavaRunnerContextImpl javaContext =
                    context instanceof JavaRunnerContextImpl
                            ? (JavaRunnerContextImpl) context
                            : null;
            if (javaContext != null && canYieldContinuation(javaContext)) {
                invocation.awaitResult(javaContext);
            } else {
                context.resolveAsync(invocation::awaitResult);
            }
        } catch (Exception e) {
            if (!invocation.capturesActionFailure(e)) {
                throw e;
            }
        }
    }

    /**
     * Takes the terminal result of the call registered under {@code id}, which must have been
     * waited for and therefore be done.
     *
     * @throws NullPointerException if the call's graph has not quiesced yet; a caller that polls
     *     rather than waits uses {@link #tryFinish}, which reports that as {@code null}.
     */
    public static <R> R finish(RunnerContextImpl context, String id) throws Exception {
        return Objects.requireNonNull(
                tryFinish(context, id), "Graph call returned before its result: " + id);
    }

    /**
     * Takes and persists the terminal result, or returns {@code null} while the graph still runs. A
     * replayed result is not recorded again. Calls that capture action failures remain registered
     * until record cleanup; other calls are removed here.
     */
    @Nullable
    public static <R> R tryFinish(RunnerContextImpl context, String id) throws Exception {
        @SuppressWarnings("unchecked")
        SubgraphInvocation<R> invocation =
                (SubgraphInvocation<R>) context.getSubgraphCalls().get(id);
        context.checkMailboxThread();
        if (!invocation.isDone()) {
            return null;
        }
        R result = terminal(invocation);
        if (!invocation.isReplayed()) {
            context.recordDurableCompletion(invocation.getDurableSlotKey(), result, null);
        }
        if (!invocation.capturesActionFailures()) {
            context.getSubgraphCalls().remove(invocation.getId());
        }
        return result;
    }

    /**
     * Fail-fast guard before a chain that waits for a child graph. Waiting must release the
     * operator so it can dispatch the graph's actions; an engine that can neither yield a
     * continuation nor hand off its execution lock would block the operator and deadlock, so the
     * call fails before anything is emitted.
     */
    public static void requireReleasableOperator(@Nullable RunnerContextImpl context) {
        if (!canReleaseOperator(context)) {
            throw new UnsupportedOperationException(
                    "Resolving an internal graph call requires a runtime that releases the"
                            + " operator while the child graph runs: either stackful suspension"
                            + " (JDK 21+ with the Continuation API) or the parallel execution"
                            + " engine's lock handoff. The current runtime would block the"
                            + " operator and deadlock.");
        }
    }

    /**
     * Whether waiting for a child graph can release the operator, by either mechanism: yielding the
     * caller's continuation, or handing off the parallel engine's execution lock. A caller that is
     * not a Java context drives its call through a polling bridge and never waits here, so
     * continuation support for the graph it starts is all that is asked of it.
     */
    private static boolean canReleaseOperator(@Nullable RunnerContext context) {
        if (!(context instanceof JavaRunnerContextImpl)) {
            return ContinuationActionExecutor.isContinuationSupported();
        }
        JavaRunnerContextImpl javaContext = (JavaRunnerContextImpl) context;
        ContinuationActionExecutor executor = javaContext.getContinuationExecutor();
        if (executor == null) {
            return false;
        }
        return canYieldContinuation(javaContext) || executor.canHandOffExecutionLock();
    }

    /** Whether this caller can release the operator by yielding its own continuation. */
    private static boolean canYieldContinuation(JavaRunnerContextImpl context) {
        return ContinuationActionExecutor.isContinuationSupported()
                && context.getContinuationExecutor() != null
                && context.getContinuationContext() != null;
    }

    private static <R> R terminal(SubgraphInvocation<R> invocation) throws Exception {
        try {
            return invocation.awaitResult();
        } catch (ExecutionException e) {
            if (!invocation.capturesActionFailures()) {
                throw e;
            }
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            // The last gate before a failure becomes this call's own result, so a framework fault
            // is rethrown as found -- the very instance, keeping its type and stack -- rather than
            // folded into an answer the caller cannot distinguish from its child's own.
            Optional<UncapturableException> uncapturable =
                    ExceptionUtils.findThrowable(cause, UncapturableException.class);
            if (uncapturable.isPresent()) {
                throw uncapturable.get();
            }
            return invocation.captureActionFailure(cause);
        }
    }
}
