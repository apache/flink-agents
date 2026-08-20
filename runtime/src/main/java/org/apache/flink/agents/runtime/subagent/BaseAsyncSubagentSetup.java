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
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentResult;

import javax.annotation.Nullable;

import java.util.concurrent.Callable;

/**
 * Production base for sub-agents whose protocol is an asynchronous job, run in durable pub/sub
 * mode: {@code submit} publishes the run through one durable POST, the returned handle subscribes
 * to it.
 */
public abstract class BaseAsyncSubagentSetup extends BaseSubagentSetup {

    /**
     * Delay between status probes while waiting for the run to reach a terminal state. Defaults to
     * {@code 500}. The descriptor-based constructor reads the optional {@code
     * statusPollIntervalMillis} argument over it, and subclasses may override it directly.
     */
    protected long statusPollIntervalMillis = 500;

    protected BaseAsyncSubagentSetup() {}

    /**
     * Descriptor-based construction, as used by YAML-declared {@code subagents:} entries: reads the
     * optional {@code statusPollIntervalMillis} argument, falling back to the default of {@code
     * 500} when absent.
     */
    protected BaseAsyncSubagentSetup(
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        Number statusPollInterval = descriptor.getArgument("statusPollIntervalMillis");
        if (statusPollInterval != null) {
            this.statusPollIntervalMillis = statusPollInterval.longValue();
        }
    }

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

    /** The durable POST of one invocation. It is the only wrapper wired to a reconciler. */
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
                // Recovery probes first through reconcileSubmitRequest, so a landed POST is never
                // duplicated.
                return () -> {
                    reconcileSubmitRequest(sessionId, callId, prompt);
                    return null;
                };
            }
        };
    }

    /**
     * The status probe. It is a direct read-only query on the mailbox thread, so durable execution
     * does not record it and a failover replay probes again.
     */
    protected RunStatus queryStatus(String sessionId, String callId) {
        return callQueryStatus(sessionId, callId);
    }

    /**
     * The durable wait of one resolve: poll the status until the run reaches a terminal state, then
     * fetch the result. Keyed by {@code sessionId#callId#await}. A probe or fetch failure that
     * escapes the body is a system-level failure: it propagates instead of being folded into an
     * error result.
     */
    protected DurableCallable<SubagentResult> awaitResult(
            RunnerContext ctx, String sessionId, String callId) {
        return new DurableCallable<SubagentResult>() {
            @Override
            public String getId() {
                return sessionId + "#" + callId + "#await";
            }

            @Override
            public Class<SubagentResult> getResultClass() {
                return SubagentResult.class;
            }

            @Override
            public SubagentResult call() throws Exception {
                while (true) {
                    RunStatus probe = callQueryStatus(sessionId, callId);
                    switch (probe.getState()) {
                        case COMPLETED:
                            return callFetchResult(sessionId, callId);
                        case FAILED:
                            return SubagentResult.error(probe.getError());
                        default:
                            // NOT_STARTED or RUNNING: keep probing. A NOT_STARTED run after a
                            // durable POST means the remote session expired; the replay then
                            // observes the fresh state instead of the original probe path.
                            Thread.sleep(statusPollIntervalMillis);
                    }
                }
            }
        };
    }

    /**
     * The cancellation propagation. The wrapper calls the hook synchronously, so durable execution
     * does not record the propagation and a failover replay propagates it again.
     */
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
     *
     * <p>Implementations must report comprehensible failures (an expired endpoint, expired
     * credentials, a rejected run) as a FAILED status rather than throwing; a RuntimeException
     * escaping this probe is treated as a system-level failure, propagates, and triggers a job
     * failover.
     */
    protected abstract RunStatus callQueryStatus(String sessionId, String callId);

    /**
     * Fetches the result of a run that reached a terminal state; comprehensible failures go into
     * the {@link SubagentResult}, while an escaping exception is a system-level failure that
     * propagates. The fetch must be an idempotent read: a failover re-executes it when the crash
     * hit the fetch in flight.
     */
    protected abstract SubagentResult callFetchResult(String sessionId, String callId)
            throws Exception;

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
     * Hook propagating a cancellation to the remote run. The default is a no-op. A replay may
     * propagate the cancellation again, so remote cancellations must be idempotent.
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
