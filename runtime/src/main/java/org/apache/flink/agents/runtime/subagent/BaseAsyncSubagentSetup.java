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

package org.apache.flink.agents.runtime.subagent;

import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.subagent.Result;
import org.apache.flink.agents.api.subagent.SubagentFuture;

import javax.annotation.Nullable;

import java.util.concurrent.Callable;

/**
 * Production base for sub-agents whose protocol is an asynchronous job, run in pub/sub mode: {@code
 * submit} (the pub) starts the run remotely through one durable POST and immediately returns a
 * handle carrying the {@code (sessionId, callId)} identity; {@code isDone}, {@code await} and
 * {@code cancel} on the handle (the sub) query or steer that run. The shape matches LangGraph runs,
 * OpenAI Assistants runs, and A2A long-running tasks.
 *
 * <h2>Integration primitives</h2>
 *
 * <p>Integrations only provide the transport primitives they already understand, with no durable
 * concepts involved:
 *
 * <ul>
 *   <li>{@link #callSubmitRequest} — start the run remotely; a thrown exception fails the action;
 *   <li>{@link #callQueryStatus} — a read-only probe of the run's current state;
 *   <li>{@link #callFetchResult} — fetch the result of a run that reached a terminal state;
 *   <li>{@link #callCancelRequest} — optional hook propagating a cancellation to the remote run.
 * </ul>
 *
 * <h2>Persistence conventions</h2>
 *
 * <p>The framework wrappers decide which operation runs through durable execution:
 *
 * <ul>
 *   <li>{@link #submitRequest} — durable, id {@code sessionId#callId}, the only wrapper wired to a
 *       reconciler ({@link #reconcileSubmitRequest}), so the remote run is started at most once
 *       even across a crash between the POST landing and its result being persisted;
 *   <li>{@link #queryStatus} — not durable: a direct read-only probe on the mailbox thread. The
 *       state advances monotonically toward a terminal state, so a replay observing a fresher state
 *       is harmless;
 *   <li>{@link #fetchResult} — durable, id {@code sessionId#callId#fetch}: the result enters the
 *       caller's data flow and must replay deterministically. No reconciler; recovery re-executes
 *       the fetch, which is an idempotent read;
 *   <li>the await composition of {@code await} — durable, id {@code sessionId#callId#await}: poll
 *       the status until a terminal state, then fetch;
 *   <li>{@link #cancelRequest} — not durable: a direct, synchronous propagation. Remote
 *       cancellations are expected to be idempotent, so a replay propagating the cancellation again
 *       is harmless.
 * </ul>
 *
 * <p>The fetch and await ids are fixed per identity: both compositions are built from idempotent
 * reads, so a recovery re-executing them converges to the same outcome as the original run.
 *
 * <h2>Cancellation contract (dev-facing)</h2>
 *
 * <p>Cancel decisions typically depend on nondeterministic inputs such as processing time. A
 * failover replay therefore does not promise control flow equivalent to the original execution: the
 * original may have taken a cancel branch that the replay skips, or vice versa. The only
 * at-most-once guarantee is the POST, enforced by the reconciler; cancellation propagation is
 * best-effort and idempotent. The hook returns nothing: a cancelled {@code await} always fails as a
 * {@link java.util.concurrent.CancellationException}, and a hook failure propagates from {@code
 * cancel} and fails the action.
 *
 * <h2>Known limitations</h2>
 *
 * <ul>
 *   <li>If the remote session or run record expires after a failover, the non-durable {@link
 *       #queryStatus} may report a different state than before the crash, and a replay may not be
 *       able to reproduce the original fetch path; persisted fetch records still short-circuit;
 *   <li>If a fetch was in flight when the process crashed and the remote fetch is consume-once
 *       rather than an idempotent read, the recovery re-execution cannot recover the result — a
 *       reconciler cannot fix this; the remote protocol must guarantee idempotent reads;
 *   <li>Any cancellation governs the subsequent {@code await} and may discard a fetch that had
 *       actually succeeded, even when its durable record exists — cancel is the authoritative
 *       control-flow decision.
 * </ul>
 */
public abstract class BaseAsyncSubagentSetup extends BaseSubagentSetup {

    /** Delay between status probes while waiting for the run to reach a terminal state. */
    protected long statusPollIntervalMillis = 10;

    // ------------------------------------------------------------------------------------------
    // pub: submit starts the run immediately through one durable POST
    // ------------------------------------------------------------------------------------------

    /**
     * Starts the remote run through the durable POST and returns its handle. A POST failure throws
     * and fails the action.
     */
    @Override
    public final SubagentFuture submit(
            RunnerContext ctx, Object prompt, String sessionId, String callId) throws Exception {
        ctx.durableExecuteAsync(submitRequest(ctx, sessionId, callId, prompt));
        return new AsyncSubagentFuture(this, ctx, sessionId, callId, currentTaskRegistry());
    }

    // ------------------------------------------------------------------------------------------
    // Framework wrappers: defaults composing the primitives, overridable
    // ------------------------------------------------------------------------------------------

    /** The durable POST of one invocation; the only wrapper wired to a reconciler. */
    protected DurableCallable<Void> submitRequest(
            RunnerContext ctx, String sessionId, String callId, Object prompt) {
        return new DurableCallable<Void>() {
            @Override
            public String getId() {
                return sessionId + "#" + callId;
            }

            @Override
            public Class<Void> getResultClass() {
                return Void.class;
            }

            @Override
            public Void call() throws Exception {
                callSubmitRequest(sessionId, callId, prompt);
                return null;
            }

            @Override
            public Callable<Void> reconciler() {
                // Recovery never assumes the POST was lost: probe first, resend only a missing
                // run, so a crash after the POST landed never duplicates the prompt.
                return () -> {
                    reconcileSubmitRequest(sessionId, callId, prompt);
                    return null;
                };
            }
        };
    }

    /** The status probe; not durable, a direct read-only query on the mailbox thread. */
    protected RunStatus queryStatus(String sessionId, String callId) throws Exception {
        return callQueryStatus(sessionId, callId);
    }

    /** The durable fetch of a terminal run's result, keyed by {@code sessionId#callId#fetch}. */
    protected DurableCallable<Result> fetchResult(
            RunnerContext ctx, String sessionId, String callId) {
        return new DurableCallable<Result>() {
            @Override
            public String getId() {
                return sessionId + "#" + callId + "#fetch";
            }

            @Override
            public Class<Result> getResultClass() {
                return Result.class;
            }

            @Override
            public Result call() {
                try {
                    return callFetchResult(sessionId, callId);
                } catch (Exception e) {
                    return Result.error(e);
                }
            }
        };
    }

    /**
     * The durable wait of one resolve: poll the status until the run reaches a terminal state, then
     * fetch the result. Keyed by {@code sessionId#callId#await}.
     */
    protected DurableCallable<Result> awaitResult(
            RunnerContext ctx, String sessionId, String callId) {
        return new DurableCallable<Result>() {
            @Override
            public String getId() {
                return sessionId + "#" + callId + "#await";
            }

            @Override
            public Class<Result> getResultClass() {
                return Result.class;
            }

            @Override
            public Result call() {
                try {
                    while (true) {
                        RunStatus probe = callQueryStatus(sessionId, callId);
                        switch (probe.getState()) {
                            case COMPLETED:
                                return callFetchResult(sessionId, callId);
                            case FAILED:
                                return Result.error(probe.getError());
                            default:
                                // NOT_STARTED or RUNNING: keep probing. A NOT_STARTED run after a
                                // durable POST means the remote session expired; see the known
                                // limitations in the class javadoc.
                                Thread.sleep(statusPollIntervalMillis);
                        }
                    }
                } catch (Exception e) {
                    return Result.error(e);
                }
            }
        };
    }

    /** The cancellation propagation; not durable, a direct synchronous call to the hook. */
    protected void cancelRequest(RunnerContext ctx, String sessionId, String callId) {
        callCancelRequest(sessionId, callId);
    }

    // ------------------------------------------------------------------------------------------
    // Transport primitives provided by the integration
    // ------------------------------------------------------------------------------------------

    /** Starts the run remotely. A thrown exception fails the enclosing action. */
    protected abstract void callSubmitRequest(String sessionId, String callId, Object prompt)
            throws Exception;

    /**
     * Read-only probe of the run's current state; must not alter the remote run. The status never
     * carries the result payload — the result is fetched separately through {@link
     * #callFetchResult}.
     */
    protected abstract RunStatus callQueryStatus(String sessionId, String callId) throws Exception;

    /** Fetches the result of a run that reached a terminal state; failures go into the Result. */
    protected abstract Result callFetchResult(String sessionId, String callId) throws Exception;

    /**
     * The crash-window recovery of the POST: probes the status and handles every state explicitly,
     * so a landed POST is never duplicated. A probe failure propagates and fails the recovery.
     */
    protected void reconcileSubmitRequest(String sessionId, String callId, Object prompt)
            throws Exception {
        RunStatus probe = callQueryStatus(sessionId, callId);
        switch (probe.getState()) {
            case NOT_STARTED:
                // The service has no record of the run: the POST never landed. Start it.
                callSubmitRequest(sessionId, callId, prompt);
                break;
            case RUNNING:
                // The POST landed and the run is in flight; the subsequent await keeps
                // polling it. Nothing to repair.
                break;
            case COMPLETED:
            case FAILED:
                // The run reached a terminal state while the caller was down; the
                // subsequent await picks up the outcome — the fetch or the reported
                // error. Nothing to repair.
                break;
            default:
                // Fail loudly instead of silently skipping an unknown state.
                throw new IllegalStateException("Unknown run state: " + probe.getState());
        }
    }

    /**
     * Hook propagating a cancellation to the remote run; the default is a no-op. The hook returns
     * nothing: a cancelled {@code await} always fails as a {@link
     * java.util.concurrent.CancellationException}. A replay may propagate the cancellation again;
     * remote cancellations must be idempotent.
     */
    protected void callCancelRequest(String sessionId, String callId) {}

    // ------------------------------------------------------------------------------------------
    // The state snapshot of a remote run
    // ------------------------------------------------------------------------------------------

    /**
     * The state snapshot of a remote run, as reported by the read-only {@link #callQueryStatus}
     * probe. A state other than {@link State#NOT_STARTED} means the submission landed on the
     * service, which is the sole basis for {@link #reconcileSubmitRequest} deciding between
     * re-posting and polling. The snapshot never carries the result payload.
     */
    public static final class RunStatus {

        /** Lifecycle of the remote run. */
        public enum State {
            NOT_STARTED,
            RUNNING,
            COMPLETED,
            FAILED
        }

        private final State state;
        @Nullable private final String error;

        private RunStatus(State state, @Nullable String error) {
            this.state = state;
            this.error = error;
        }

        /** The service has no record of the run: the POST never landed (or the id mismatches). */
        public static RunStatus notStarted() {
            return new RunStatus(State.NOT_STARTED, null);
        }

        /** The run is in progress. */
        public static RunStatus running() {
            return new RunStatus(State.RUNNING, null);
        }

        /** The run finished successfully. */
        public static RunStatus completed() {
            return new RunStatus(State.COMPLETED, null);
        }

        /** The run failed, carrying the error message. */
        public static RunStatus failed(String error) {
            return new RunStatus(State.FAILED, error);
        }

        public State getState() {
            return state;
        }

        @Nullable
        public String getError() {
            return error;
        }
    }
}
