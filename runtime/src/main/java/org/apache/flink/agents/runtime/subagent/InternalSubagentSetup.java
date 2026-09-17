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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.condition.ActionMatcher;
import org.apache.flink.agents.runtime.context.JavaRunnerContextImpl;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.operator.ActionTask;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime setup for an internal sub-agent: a child {@link AgentPlan} compiled from an {@code Agent}
 * registered as an {@code AGENT} resource. The plan module serializes the child plan and scope
 * (through {@code InternalSubagentProvider}); this runtime class owns everything the invocation
 * needs at execution time.
 *
 * <p>Execution mode is the deferred one, inherited from {@link BaseDeferredSubagentSetup}: {@link
 * #submit} returns a deferred handle whose request is prepared on first resolve. {@link #prepare}
 * then runs the mailbox-confined bootstrap — registering the call status and sending one {@code
 * InternalSubagentCallEvent} — and returns the durable callable that waits off the mailbox thread
 * for the child to quiesce, so the operator can dispatch the child agent's actions in between.
 *
 * <p>Orchestration state lives here, not in the operator: the per-call quiesce statuses, the
 * per-scope child resource caches, and the per-key session index used to clean up when a record
 * finishes. The operator only dispatches envelope events and reports lifecycle through the
 * inherited {@link org.apache.flink.agents.runtime.lifecycle.TaskLifecycleListener} hooks.
 *
 * <p>Sending the event outside the durable boundary is deliberate: a replayed send carries the same
 * event attributes, so the child action resolves to the same persisted action state and replays its
 * recorded output instead of running again.
 */
public class InternalSubagentSetup extends BaseDeferredSubagentSetup {

    private static final long serialVersionUID = 1L;

    private final String scope;

    private final AgentPlan childPlan;

    /** Nested map: sessionId → callId → callStatus. */
    private final transient Map<String, Map<String, InternalSubagentCallStatus>> callStatuses =
            new HashMap<>();

    private final transient Map<String, ResourceCache> childCaches = new HashMap<>();

    private final transient Map<Object, List<String>> keySessionIds = new HashMap<>();

    /**
     * The runner contexts this setup registered a session-owner entry on, per session id. Used to
     * unregister those entries when the owning record finishes.
     */
    private final transient Map<String, RunnerContextImpl> ownerContexts = new HashMap<>();

    /** Lazily built event-to-action matcher for the child plan; not part of the serialized form. */
    private transient ActionMatcher actionMatcher;

    public InternalSubagentSetup(String scope, AgentPlan childPlan) {
        this.scope = scope;
        this.childPlan = childPlan;
    }

    public String getScope() {
        return scope;
    }

    public AgentPlan getChildPlan() {
        return childPlan;
    }

    /**
     * Matches the child plan's actions against a forwarded event, applying the same event-type and
     * condition-expression filtering the root router uses. Built lazily because the index depends
     * only on the immutable child plan, and this class is serialized as a resource.
     */
    public List<Action> matchActions(Event event) {
        if (actionMatcher == null) {
            actionMatcher = new ActionMatcher(childPlan);
        }
        return actionMatcher.match(event);
    }

    /**
     * The mailbox-confined bootstrap plus the off-mailbox wait. Sending the bootstrap event must
     * happen before the wait releases the mailbox, so it runs here, on the resolving thread.
     */
    @Override
    protected DurableCallable<SubagentResult> prepare(
            RunnerContext ctx, Object prompt, String sessionId, String callId) {
        requireMailboxSuspension(ctx);
        try {
            bootstrap(ctx, sessionId, callId, prompt);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to bootstrap internal sub-agent call for scope " + scope, e);
        }
        return new DurableCallable<SubagentResult>() {
            @Override
            public String getId() {
                return sessionId + "#" + callId;
            }

            @Override
            public Class<SubagentResult> getResultClass() {
                return SubagentResult.class;
            }

            @Override
            public SubagentResult call() {
                // Runs off the mailbox thread, so the operator can dispatch the child's
                // actions; failures converge into a failed SubagentResult like the other
                // deferred setups.
                try {
                    return SubagentResult.ok(awaitSubagentCall(sessionId, callId));
                } catch (Exception e) {
                    return SubagentResult.error(e);
                }
            }
        };
    }

    /**
     * Fail-fast guard before the wait starts. Waiting for the child must release the mailbox so the
     * operator can dispatch the child's actions; without stackful suspension the wait would block
     * the mailbox and deadlock, so resolving fails immediately instead.
     */
    private static void requireMailboxSuspension(RunnerContext ctx) {
        boolean suspendable =
                ContinuationActionExecutor.isContinuationSupported()
                        && (!(ctx instanceof JavaRunnerContextImpl)
                                || (((JavaRunnerContextImpl) ctx).getContinuationExecutor() != null
                                        && ((JavaRunnerContextImpl) ctx).getContinuationContext()
                                                != null));
        if (!suspendable) {
            throw new IllegalStateException(
                    "Resolving an internal sub-agent call requires stackful suspension to release"
                            + " the mailbox while the child runs (JDK 21+ with the Continuation"
                            + " API); the current runtime would block the mailbox and deadlock.");
        }
    }

    /**
     * Registers the call status under the framework-assigned {@code (sessionId, callId)} identity
     * and sends the bootstrap event (mailbox-thread only), without blocking.
     *
     * <p>The identity is supplied rather than minted here so it is reproducible after failover: a
     * replayed call sends an envelope with the same attributes, which is what lets the child action
     * resolve to its persisted action state instead of running again. The record key is read from
     * the executing task rather than an ambient holder.
     */
    public void bootstrap(RunnerContext ctx, String sessionId, String callId, Object prompt) {
        InternalSubagentCallStatus cs =
                new InternalSubagentCallStatus(callId, scope, sessionId, this);
        callStatuses.computeIfAbsent(sessionId, k -> new HashMap<>()).put(callId, cs);
        ActionTask task = currentTask();
        Object key = task != null ? task.getKey() : null;
        if (key != null) {
            keySessionIds.computeIfAbsent(key, k -> new ArrayList<>()).add(sessionId);
        }
        if (ctx instanceof RunnerContextImpl) {
            // Let the shared context resolve this session off the mailbox thread (pemja await),
            // independent of whichever scope is wired onto it at that moment.
            RunnerContextImpl runnerContext = (RunnerContextImpl) ctx;
            runnerContext.registerInternalCallOwner(sessionId, this);
            ownerContexts.put(sessionId, runnerContext);
        }
        ctx.sendEvent(
                InternalSubagentCallEvent.bootstrap(
                        new InputEvent(prompt), scope, callId, sessionId));
    }

    /**
     * Blocks until the internal sub-agent call identified by {@code (sessionId, callId)} completes
     * and returns its accumulated output. Must be invoked off the mailbox thread.
     */
    public List<Object> awaitSubagentCall(String sessionId, String callId) throws Exception {
        InternalSubagentCallStatus cs = getCallStatus(sessionId, callId);
        if (cs == null) {
            throw new IllegalStateException(
                    "No internal sub-agent call registered for sessionId="
                            + sessionId
                            + ", callId="
                            + callId);
        }
        try {
            return cs.getResponseFuture().get();
        } catch (java.util.concurrent.ExecutionException e) {
            // Surface the child's failure directly, so the caller's SubagentResult carries
            // the failing action's exception rather than the future's plumbing.
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    /** The quiesce status of the identified call, or {@code null} when this setup owns none. */
    @Nullable
    public InternalSubagentCallStatus getCallStatus(String sessionId, String callId) {
        Map<String, InternalSubagentCallStatus> calls = callStatuses.get(sessionId);
        if (calls == null) {
            return null;
        }
        return calls.get(callId);
    }

    /**
     * Locates the quiesce status of the identified call anywhere in this setup's subtree: own
     * statuses first, then recursively the setups already materialized in this setup's child caches
     * (nested calls). The operator uses this to resolve envelope events without knowing how deep
     * the owning setup sits.
     */
    @Nullable
    public InternalSubagentCallStatus findCallStatus(String sessionId, String callId) {
        InternalSubagentCallStatus cs = getCallStatus(sessionId, callId);
        if (cs != null) {
            return cs;
        }
        for (ResourceCache childCache : childCaches.values()) {
            for (Resource resource : childCache.materializedResources(ResourceType.AGENT)) {
                if (resource instanceof InternalSubagentSetup) {
                    cs = ((InternalSubagentSetup) resource).findCallStatus(sessionId, callId);
                    if (cs != null) {
                        return cs;
                    }
                }
            }
        }
        return null;
    }

    /** The pooled child resource cache for this scope, inheriting from the root cache. */
    public ResourceCache getOrCreateChildCache(
            ClassLoader userCodeClassLoader, ResourceCache rootResourceCache) {
        return childCaches.computeIfAbsent(
                scope,
                k ->
                        new ResourceCache(
                                childPlan.getResourceProviders(),
                                userCodeClassLoader,
                                rootResourceCache));
    }

    /**
     * Record lifecycle: drop this setup's sessions minted under {@code key} and their statuses.
     * Idempotent — safe across layers and repeated notifications.
     */
    @Override
    public void onRecordFinished(Object key) {
        List<String> sessionIds = keySessionIds.remove(key);
        if (sessionIds != null) {
            for (String sid : sessionIds) {
                callStatuses.remove(sid);
                RunnerContextImpl owner = ownerContexts.remove(sid);
                if (owner != null) {
                    owner.unregisterInternalCallOwner(sid);
                }
            }
        }
    }
}
