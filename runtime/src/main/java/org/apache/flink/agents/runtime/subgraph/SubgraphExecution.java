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
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.context.JavaRunnerContextImpl;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.memory.EventAttachmentUtils;

import javax.annotation.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * The one execution chain an internal graph call follows: register the invocation, build its
 * delegate event, emit it, release the mailbox so the operator can dispatch the graph, then take
 * the terminal result, persist it and deregister.
 *
 * <p>Every step after {@link #prepare} names its call by the id that step returned, never by the
 * invocation, so once a call is registered the id is the only handle its caller needs. That is what
 * lets a caller prepare every member of a group before waiting for any, so their graphs run
 * concurrently.
 *
 * <p>Two driving protocols are built from the same steps. The blocking one runs {@link #prepare},
 * {@link #await} and {@link #finish} in one turn: {@link #await} releases the operator while the
 * graph runs, and {@link #requireMailboxSuspension} is its fail-fast guard. The polling one, for a
 * bridge that drives its call from a generator yielding between polls, pairs {@link #prepare} with
 * {@link #tryFinish}, which returns {@code null} while the graph still runs so the bridge can yield
 * and come back. Releasing the operator is that yield's job rather than {@link #await}'s, so such a
 * bridge neither calls {@link #await} nor consults {@link #requireMailboxSuspension}.
 *
 * <p>Nothing here is kind-specific. What a built-in chat call and an internal sub-agent call differ
 * in — the environment the graph runs in, the delegate event it consumes, the durable slot's key
 * and result type, and whether the call's own failure becomes its result — lives in the {@link
 * SubgraphDefinition} the call's kind supplies. The identity that separates two calls of one kind
 * is minted by that kind's entry point and travels on the invocation, so this chain reads it back
 * rather than deriving it.
 *
 * <p>Reading the slot before emitting is what makes a replay cheap: the recorded result goes into a
 * fresh invocation and the child graph never starts, so its actions are not dispatched just to
 * replay their own recorded state.
 *
 * <p>Nothing here is checkpointed. A replay rebuilds the invocation from the caller's identity and
 * the durable slot supplies the completed work.
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
        return invocation.getId();
    }

    /**
     * Releases the mailbox while the graph of the call registered under {@code id} runs, so the
     * operator can dispatch its actions. A call whose result came from the durable slot is already
     * done and does not yield.
     *
     * <p>Waiting is all this does; {@link #finish} takes the result. A call whose graph failure is
     * its own result therefore tolerates the wait's exception rather than failing the caller with
     * it.
     *
     * <p>The wait yields the caller's continuation and holds no async-pool worker: the child graph
     * draws its own workers from that same finite pool, so a worker-dispatched wait would hold one
     * for the whole call while the child's durable calls queue behind it, which self-deadlocks at
     * {@code num-async-threads=1} and starves under enough nested or concurrent waits at any pool
     * size (issue #1213).
     */
    public static void await(RunnerContextImpl context, String id) throws Exception {
        SubgraphInvocation<?> invocation = context.getSubgraphCalls().get(id);
        if (invocation.isDone()) {
            return;
        }
        try {
            invocation.awaitResult((JavaRunnerContextImpl) context);
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
     * Fail-fast guard before a chain that waits on the mailbox thread. Waiting for the graph must
     * release the mailbox so the operator can dispatch its actions; without stackful suspension the
     * wait would block the mailbox and deadlock, so the call fails before anything is emitted.
     */
    public static void requireMailboxSuspension(@Nullable RunnerContextImpl context) {
        boolean suspendable =
                ContinuationActionExecutor.isContinuationSupported()
                        && (!(context instanceof JavaRunnerContextImpl)
                                || (((JavaRunnerContextImpl) context).getContinuationExecutor()
                                                != null
                                        && ((JavaRunnerContextImpl) context)
                                                        .getContinuationContext()
                                                != null));
        if (!suspendable) {
            throw new UnsupportedOperationException(
                    "Resolving an internal graph call requires stackful suspension to release the"
                            + " mailbox while the child graph runs (JDK 21+ with the Continuation"
                            + " API); the current runtime would block the mailbox and deadlock.");
        }
    }

    private static <R> R terminal(SubgraphInvocation<R> invocation) throws Exception {
        try {
            return invocation.awaitResult();
        } catch (ExecutionException e) {
            if (!invocation.capturesActionFailures()) {
                throw e;
            }
            Throwable cause = e.getCause();
            return invocation.captureActionFailure(cause != null ? cause : e);
        }
    }
}
