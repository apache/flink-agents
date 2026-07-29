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

import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;

/**
 * Caller-facing interface for all sub-agents (external and internal).
 *
 * <p>An invocation is identified by a {@code (sessionId, callId)} pair; the session groups a
 * conversation across invocations. Both ids are assigned by the framework ({@link
 * RunnerContext#nextSessionId()} and {@link RunnerContext#nextCallId(String)}): callers may supply
 * a session id to continue a prior session but never supply a call id.
 */
public interface Subagent {

    /** Synchronously invokes the sub-agent, creating a new session. */
    Result call(RunnerContext ctx, Object prompt) throws Exception;

    /** Synchronously invokes the sub-agent continuing {@code sessionId}. */
    Result call(RunnerContext ctx, Object prompt, String sessionId) throws Exception;

    /** Produces a deferred, durable callable for this sub-agent call, creating a new session. */
    DurableCallable<Result> asAsyncCallable(RunnerContext ctx, Object prompt);

    /**
     * Produces a deferred, durable callable for this sub-agent call continuing {@code sessionId}.
     */
    DurableCallable<Result> asAsyncCallable(RunnerContext ctx, Object prompt, String sessionId);
}
