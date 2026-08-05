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

import org.apache.flink.agents.api.context.RunnerContext;

/**
 * Caller-facing interface for all sub-agents (external and internal).
 *
 * <p>An invocation is identified by a {@code (sessionId, callId)} pair; the session groups a
 * conversation across invocations. Callers do not manage ids: the short forms below leave the
 * missing ids to the implementation, which assigns them (runtime setups typically through a
 * deterministic id allocator, stable across failover replays) or rejects the call.
 *
 * <p>The full form taking the complete {@code (sessionId, callId)} identity is the
 * implementation-side contract, declared by {@link SubagentSetup}; resolving a returned handle is
 * {@code await}.
 */
public interface Subagent {

    /**
     * Issues an invocation under the given {@code sessionId}; the implementation picks the call id.
     */
    SubagentFuture submit(RunnerContext ctx, Object prompt, String sessionId) throws Exception;

    /** Issues an invocation whose full identity the implementation picks. */
    SubagentFuture submit(RunnerContext ctx, Object prompt) throws Exception;
}
