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

package org.apache.flink.agents.api.subagent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.SerializableResource;

/**
 * Base setup for an external sub-agent resource. Serialized into the agent plan as an {@code AGENT}
 * resource.
 *
 * <p>Hosts the id-resolution chain behind {@link Subagent}: omitted ids are assigned via the
 * context before an implementation is ever invoked, and {@link #call} runs the deferred callable
 * through durable execution. Implementations only provide the terminal {@code asAsyncCallable}.
 */
public abstract class SubagentSetup extends SerializableResource implements Subagent {

    @Override
    @JsonIgnore
    public ResourceType getResourceType() {
        return ResourceType.AGENT;
    }

    @Override
    public Result call(RunnerContext ctx, Object prompt) throws Exception {
        return call(ctx, prompt, ctx.nextSessionId());
    }

    @Override
    public Result call(RunnerContext ctx, Object prompt, String sessionId) throws Exception {
        return call(ctx, prompt, sessionId, ctx.nextCallId(sessionId));
    }

    /**
     * Synchronously invokes the sub-agent with already-assigned {@code sessionId} and {@code
     * callId}, running the deferred callable through durable execution. Framework-facing: the ids
     * are assigned by the shorter variants, never supplied by callers.
     */
    public Result call(RunnerContext ctx, Object prompt, String sessionId, String callId)
            throws Exception {
        return ctx.durableExecuteAsync(asAsyncCallable(ctx, prompt, sessionId, callId));
    }

    @Override
    public DurableCallable<Result> asAsyncCallable(RunnerContext ctx, Object prompt) {
        return asAsyncCallable(ctx, prompt, ctx.nextSessionId());
    }

    @Override
    public DurableCallable<Result> asAsyncCallable(
            RunnerContext ctx, Object prompt, String sessionId) {
        return asAsyncCallable(ctx, prompt, sessionId, ctx.nextCallId(sessionId));
    }

    /**
     * Produces the deferred, durable callable for one invocation; both ids are already assigned.
     * The only method implementations must provide. Contract: the returned {@link
     * DurableCallable#getId()} MUST be derived solely from the {@code (sessionId, callId)} pair.
     */
    protected abstract DurableCallable<Result> asAsyncCallable(
            RunnerContext ctx, Object prompt, String sessionId, String callId);
}
