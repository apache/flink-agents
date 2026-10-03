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
import org.apache.flink.agents.api.subagent.SubagentResult;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** An internal invocation admitted on the mailbox, without a blocking async worker. */
public final class InternalSubagentCallable
        implements DurableCallable<SubagentResult>, Callable<SubagentResult> {

    private final InternalSubagentSetup setup;
    private final RunnerContext context;
    private final Object prompt;
    private final String sessionId;
    private final String callId;

    public InternalSubagentCallable(
            InternalSubagentSetup setup,
            RunnerContext context,
            Object prompt,
            String sessionId,
            String callId) {
        this.setup = setup;
        this.context = context;
        this.prompt = prompt;
        this.sessionId = sessionId;
        this.callId = callId;
    }

    @Override
    public String getId() {
        return sessionId + "#" + callId;
    }

    @Override
    public Class<SubagentResult> getResultClass() {
        return SubagentResult.class;
    }

    /** Starts the child on the mailbox after its durable slot has been reserved. */
    public CompletableFuture<SubagentResult> start() {
        setup.bootstrap(context, sessionId, callId, prompt);
        return setup.getCallStatus(sessionId, callId)
                .getResponseFuture()
                .handle(
                        (result, failure) -> {
                            if (failure == null) {
                                return SubagentResult.ok(result);
                            }
                            String summary =
                                    setup.getCallStatus(sessionId, callId).getFailureSummary();
                            if (summary != null) {
                                return SubagentResult.error(summary);
                            }
                            throw new CompletionException(failure);
                        });
    }

    @Override
    public SubagentResult call() {
        throw new IllegalStateException(
                "Internal sub-agent calls require the continuation batch scheduler");
    }
}
