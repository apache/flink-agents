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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.subagent.InternalSubagentResourceDescriptorBuilder;
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.subagent.InternalSubagentPlans;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.condition.ActionMatcher;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.subgraph.SubgraphExecution;
import org.apache.flink.agents.runtime.subgraph.SubgraphInvocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime setup for an internal sub-agent: a child {@link AgentPlan} compiled from an {@code Agent}
 * registered as an {@code AGENT} resource. The plan module serializes the child plan and scope
 * (through an ordinary resource descriptor); this runtime class owns everything the invocation
 * needs at execution time.
 *
 * <p>Calls share {@link SubgraphExecution} with built-in chat calls.
 *
 * <p>This adapter retains only what outlives one call: the pooled child resource caches. The calls
 * themselves live in the operator-wide invocation registry under their {@code sessionId#callId} id,
 * and record cleanup goes through {@code SubgraphCallManager}.
 */
public class InternalSubagentSetup extends BaseSubagentSetup {

    /**
     * Serializes the child plan with the same configuration {@code RunnerContextImpl} uses for the
     * active-scope plan JSON, so the eager materialization the operator runs at open and the lazy
     * resolution a child action performs at call time key the Python scope cache by one string.
     */
    private static final ObjectMapper PLAN_MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private final String scope;

    private final AgentPlan plan;

    private final Map<String, ResourceCache> childCaches = new HashMap<>();

    /** This scope's graph contract, one instance per setup since it holds no per-call state. */
    private final InternalSubagentDefinition definition;

    /** Lazily built event-to-action matcher for the plan. */
    private ActionMatcher actionMatcher;

    /** Lazily serialized plan JSON. See {@link #getPlanJson()}. */
    private String planJson;

    public InternalSubagentSetup(ResourceDescriptor descriptor, ResourceContext context) {
        super(descriptor, context);
        this.scope = descriptor.getArgument(InternalSubagentResourceDescriptorBuilder.SCOPE);
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("Internal sub-agent requires a scope");
        }
        this.plan = InternalSubagentPlans.decode(descriptor);
        this.definition = new InternalSubagentDefinition(this);
    }

    InternalSubagentDefinition definition() {
        return definition;
    }

    public String getScope() {
        return scope;
    }

    public AgentPlan getPlan() {
        return plan;
    }

    /**
     * The child plan JSON, serialized once and cached. This is the single source of the scope's
     * plan JSON: the operator hands it to the child resource cache so the Python runtime
     * materializes the scope's Python-owned resources against the child plan, and {@code
     * RunnerContextImpl.getActiveScopePlanJson()} returns the same string so a child action
     * resolves its resources against that plan. Both paths then key one Python scope cache, so a
     * Python-owned resource of the scope is built exactly once.
     */
    public String getPlanJson() {
        if (planJson == null) {
            try {
                planJson = PLAN_MAPPER.writeValueAsString(plan);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(
                        "Failed to serialize the child plan for scope " + scope, e);
            }
        }
        return planJson;
    }

    /**
     * Matches the child plan's actions against a forwarded event, applying the same event-type and
     * condition-expression filtering the root router uses. Built lazily because the index depends
     * only on the immutable child plan.
     */
    public List<Action> matchActions(Event event) {
        if (actionMatcher == null) {
            actionMatcher = new ActionMatcher(plan);
        }
        return actionMatcher.match(event);
    }

    /**
     * Returns the handle running the common graph-call chain when it resolves. Nothing is issued
     * here, so a handle that is never resolved issues no call.
     */
    @Override
    public SubagentFuture submit(
            RunnerContext ctx, Object prompt, String sessionId, String callId) {
        return new InternalSubagentFuture(
                sessionId, callId, ctx, this, prompt, currentTaskRegistry());
    }

    /** The call one action issues against this scope's child plan under the assigned identity. */
    SubgraphInvocation<SubagentResult> invocation(
            RunnerContextImpl ctx, String sessionId, String callId) {
        return definition.newInvocation(
                ctx, InternalSubagentDefinition.identity(sessionId, callId), ctx.getRecordKey());
    }

    /**
     * Prepares one call for a bridge that polls it afterwards: the common chain registers the call
     * and either installs the result its durable slot recorded or emits its bootstrap envelope.
     *
     * <p>The identity is supplied rather than minted here so it is reproducible after failover: a
     * replayed call sends an envelope with the same attributes, which is what lets the child action
     * resolve to its persisted action state instead of running again.
     */
    public void bootstrap(RunnerContext ctx, String sessionId, String callId, Object prompt) {
        try {
            RunnerContextImpl runner = (RunnerContextImpl) ctx;
            SubgraphExecution.prepare(runner, invocation(runner, sessionId, callId), prompt);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to bootstrap internal sub-agent call for scope " + scope, e);
        }
    }

    /**
     * The environment this scope's child actions run in: the child plan, its cache and its JSON.
     */
    public RunnerContextImpl.ResourceScope childScope(RunnerContextImpl runner) {
        ResourceCache childCache =
                getOrCreateChildCache(
                        Thread.currentThread().getContextClassLoader(),
                        runner.currentResourceCache());
        return new RunnerContextImpl.ResourceScope(
                runner.getAgentMetricGroup(), plan, childCache, getPlanJson());
    }

    /**
     * The pooled child resource cache for this scope, inheriting resource resolution and the Python
     * bridge from the root cache. It carries the child plan JSON so the Python runtime materializes
     * this scope's Python-owned resources against the child plan rather than the root plan.
     */
    public ResourceCache getOrCreateChildCache(
            ClassLoader userCodeClassLoader, ResourceCache rootResourceCache) {
        String planJson = getPlanJson();
        return childCaches.computeIfAbsent(
                scope,
                k ->
                        new ResourceCache(
                                plan.getResourceProviders(),
                                userCodeClassLoader,
                                rootResourceCache,
                                planJson));
    }
}
