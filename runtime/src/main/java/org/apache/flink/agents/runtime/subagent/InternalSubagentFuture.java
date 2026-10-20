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

import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentFutures;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.subgraph.SubgraphExecution;

import javax.annotation.Nullable;

import java.util.concurrent.CancellationException;

/**
 * Handle to one internal sub-agent invocation, backed by {@link SubgraphExecution} rather than a
 * durable callable of its own.
 */
final class InternalSubagentFuture extends SubagentFuture implements PhasedSubagentFuture {

    private final RunnerContext ctx;
    private final InternalSubagentSetup setup;
    private final Object prompt;
    @Nullable private final PendingSubagentCallRegistry registry;

    /** The id every step after registration names this call by. */
    @Nullable private String id;

    private boolean done;
    private boolean cancelled;
    @Nullable private SubagentResult value;

    InternalSubagentFuture(
            String sessionId,
            String callId,
            RunnerContext ctx,
            InternalSubagentSetup setup,
            Object prompt,
            @Nullable PendingSubagentCallRegistry registry) {
        super(sessionId, callId);
        this.ctx = ctx;
        this.setup = setup;
        this.prompt = prompt;
        this.registry = registry;
        if (registry != null) {
            registry.trackPendingSubagentCall(identity());
        }
    }

    /** Prepares the call, or installs the terminal result recorded in its durable slot. */
    @Override
    public void prepare() throws Exception {
        if (cancelled) {
            throw new CancellationException("Sub-agent call cancelled: " + identity());
        }
        if (id == null) {
            RunnerContextImpl runner = (RunnerContextImpl) ctx;
            SubgraphExecution.requireReleasableOperator(runner);
            id =
                    SubgraphExecution.prepare(
                            runner, setup.invocation(runner, getSessionId(), getCallId()), prompt);
        }
    }

    /**
     * Waits for the child graph through {@link SubgraphExecution#await}, then takes and persists
     * its result.
     */
    @Override
    public void execute() throws Exception {
        prepare();
        RunnerContextImpl runner = (RunnerContextImpl) ctx;
        SubgraphExecution.await(runner, id);
        complete(SubgraphExecution.finish(runner, id));
    }

    @Override
    public SubagentResult await() throws Exception {
        if (!done) {
            execute();
        }
        return value;
    }

    /**
     * Cancels before the call is issued: nothing was emitted, so there is nothing to undo. An
     * already resolved handle ignores the cancellation request.
     */
    @Override
    public void cancel() {
        if (done) {
            return;
        }
        cancelled = true;
        if (registry != null) {
            registry.untrackPendingSubagentCall(identity());
        }
    }

    @Override
    public boolean isDone() {
        return done || cancelled;
    }

    @Override
    public SubagentFutures combine(SubagentFuture... others) {
        return new SubagentFutureGroup(this, others);
    }

    private String identity() {
        return InternalSubagentDefinition.identity(getSessionId(), getCallId());
    }

    private void complete(SubagentResult outcome) {
        this.value = outcome;
        this.done = true;
        if (registry != null) {
            registry.untrackPendingSubagentCall(identity());
        }
    }
}
