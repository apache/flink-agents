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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentMetadata;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.async.MailboxCallable;
import org.apache.flink.agents.runtime.condition.ActionMatcher;
import org.apache.flink.agents.runtime.context.JavaRunnerContextImpl;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.memory.MemoryObjectImpl;
import org.apache.flink.agents.runtime.operator.ActionTask;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.util.ExceptionUtils;

import javax.annotation.Nullable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;

/**
 * Runtime setup for an internal sub-agent: a child {@link AgentPlan} compiled from an {@code Agent}
 * registered as an {@code AGENT} resource. The plan module serializes the child plan and scope
 * (through {@code InternalSubagentProvider}); this runtime class owns everything the invocation
 * needs at execution time.
 *
 * <p>Execution mode is the deferred one, inherited from {@link BaseDeferredSubagentSetup}: {@link
 * #submit} returns a deferred handle whose request is prepared on first resolve. {@link #prepare}
 * then runs the mailbox-confined bootstrap — registering the call status and sending one {@code
 * InternalSubagentCallEvent} — and returns a mailbox callable. Its completion barrier suspends the
 * caller without using an async worker; once ready, its result is recorded durably on the mailbox.
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
        this(scope, childPlan, null);
    }

    public InternalSubagentSetup(String scope, AgentPlan childPlan, SubagentMetadata metadata) {
        this(
                ResourceDescriptor.Builder.newBuilder(InternalSubagentSetup.class.getName())
                        .addInitialArgument("scope", scope)
                        .addInitialArgument("child_plan", childPlan)
                        .addInitialArgument(
                                FIELD_DESCRIPTION,
                                metadata == null ? "" : metadata.getDescription())
                        .addInitialArgument(
                                FIELD_INPUT_SCHEMA,
                                metadata == null ? null : metadata.getInputSchema())
                        .build(),
                null);
    }

    /** Rebuild both the child plan and callable metadata after descriptor serialization. */
    public InternalSubagentSetup(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.scope = descriptor.getArgument("scope");
        Object plan = descriptor.getArgument("child_plan");
        this.childPlan =
                plan instanceof AgentPlan
                        ? (AgentPlan) plan
                        : new ObjectMapper().convertValue(plan, AgentPlan.class);
    }

    @Override
    public Class<?> getResultType() {
        return List.class;
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
     * Bootstrap on the mailbox, then await the child's completion barrier without using a worker.
     * Reading the ready result stays inside the deferred handle's durable execution boundary.
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
        return new MailboxCallable<SubagentResult>() {
            @Override
            public Future<?> completion() {
                return getCallStatus(sessionId, callId).getResponseFuture();
            }

            @Override
            public String getId() {
                return sessionId + "#" + callId;
            }

            @Override
            public Class<SubagentResult> getResultClass() {
                return SubagentResult.class;
            }

            @Override
            public SubagentResult call() throws Exception {
                // The continuation waits for completion without using the model/tool pool.
                // Only reading and recording the ready result runs on the mailbox.
                try {
                    return SubagentResult.ok(awaitSubagentCall(sessionId, callId));
                } catch (InterruptedException | CancellationException e) {
                    throw e;
                } catch (Exception e) {
                    InternalSubagentCallStatus status = getCallStatus(sessionId, callId);
                    if (status != null && status.getFailureMessage() != null) {
                        return SubagentResult.error(status.getFailureMessage());
                    }
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
        boolean restored = getCallStatus(sessionId, callId) != null;
        if (!restored) {
            InternalSubagentCallStatus cs =
                    new InternalSubagentCallStatus(callId, scope, sessionId, this);
            callStatuses.computeIfAbsent(sessionId, k -> new HashMap<>()).put(callId, cs);
        }
        ActionTask task = currentTask();
        Object key = task != null ? task.getKey() : null;
        if (key != null) {
            List<String> sessions = keySessionIds.computeIfAbsent(key, k -> new ArrayList<>());
            if (!sessions.contains(sessionId)) {
                sessions.add(sessionId);
            }
        }
        if (ctx instanceof RunnerContextImpl) {
            // Let the shared context resolve this session off the mailbox thread (pemja await),
            // independent of whichever scope is wired onto it at that moment.
            RunnerContextImpl runnerContext = (RunnerContextImpl) ctx;
            runnerContext.registerInternalCallOwner(sessionId, this);
            ownerContexts.put(sessionId, runnerContext);
        }
        // A restored parent continuation re-executes its submit/await. Its child tasks and
        // call state already came from the same checkpoint; emitting again would duplicate work.
        if (!restored) {
            ctx.sendEvent(
                    InternalSubagentCallEvent.bootstrap(
                            new InputEvent(prompt), scope, callId, sessionId));
        }
    }

    /** Checkpoint data for one setup subtree, addressed by resource name within its parent. */
    public static final class Snapshot implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String name;
        private final List<InternalSubagentCallStatus.Snapshot> calls;
        private final Map<String, Snapshot> children;

        private Snapshot(
                String name,
                List<InternalSubagentCallStatus.Snapshot> calls,
                Map<String, Snapshot> children) {
            this.name = name;
            this.calls = calls;
            this.children = children;
        }

        public String getName() {
            return name;
        }
    }

    /** Capture only the calls owned by this record key, including nested setups. */
    public Snapshot snapshotCalls(Object key) {
        List<InternalSubagentCallStatus.Snapshot> calls = new ArrayList<>();
        for (String session : keySessionIds.getOrDefault(key, Collections.emptyList())) {
            for (InternalSubagentCallStatus call : callStatuses.get(session).values()) {
                calls.add(call.snapshot());
            }
        }
        Map<String, Snapshot> children = new LinkedHashMap<>();
        for (ResourceCache cache : childCaches.values()) {
            for (Resource resource : cache.materializedResources(ResourceType.AGENT)) {
                if (resource instanceof InternalSubagentSetup) {
                    InternalSubagentSetup child = (InternalSubagentSetup) resource;
                    children.put(child.getSubagentName(), child.snapshotCalls(key));
                }
            }
        }
        return new Snapshot(getSubagentName(), calls, children);
    }

    /** Restore call coordination before the operator resumes any checkpointed child task. */
    public void restoreCalls(
            Object key, Snapshot snapshot, TypeSerializer<MemoryObjectImpl.MemoryItem> serializer) {
        for (InternalSubagentCallStatus.Snapshot call : snapshot.calls) {
            callStatuses
                    .computeIfAbsent(call.getSessionId(), ignored -> new HashMap<>())
                    .put(
                            call.getCallId(),
                            InternalSubagentCallStatus.restore(call, this, serializer));
            List<String> sessions =
                    keySessionIds.computeIfAbsent(key, ignored -> new ArrayList<>());
            if (!sessions.contains(call.getSessionId())) {
                sessions.add(call.getSessionId());
            }
        }
        for (ResourceCache cache : childCaches.values()) {
            for (Resource resource : cache.materializedResources(ResourceType.AGENT)) {
                if (resource instanceof InternalSubagentSetup) {
                    InternalSubagentSetup child = (InternalSubagentSetup) resource;
                    Snapshot childSnapshot = snapshot.children.get(child.getSubagentName());
                    if (childSnapshot != null) {
                        child.restoreCalls(key, childSnapshot, serializer);
                    }
                }
            }
        }
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
        if (!cs.isDone()) {
            throw new IllegalStateException("Internal sub-agent result is not ready: " + callId);
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

    /** Releases owned child resources and wakes any calls still waiting during shutdown. */
    @Override
    public void close() throws Exception {
        for (Map<String, InternalSubagentCallStatus> calls : callStatuses.values()) {
            for (InternalSubagentCallStatus call : calls.values()) {
                call.cancel();
            }
        }
        callStatuses.clear();
        keySessionIds.clear();
        ownerContexts.forEach(
                (sessionId, context) -> context.unregisterInternalCallOwner(sessionId));
        ownerContexts.clear();

        // Drop ownership before closing so repeated cleanup cannot close resources twice.
        List<ResourceCache> caches = new ArrayList<>(childCaches.values());
        childCaches.clear();
        Throwable firstFailure = null;
        for (ResourceCache cache : caches) {
            try {
                // ResourceCache closes only its own resources, including nested setups.
                cache.close();
            } catch (Throwable failure) {
                firstFailure = ExceptionUtils.firstOrSuppressed(failure, firstFailure);
            }
        }
        if (firstFailure != null) {
            ExceptionUtils.rethrowException(firstFailure);
        }
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
