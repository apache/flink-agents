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

/**
 * Convenience base for the {@link DurableCallable} returned by {@code asAsyncCallable}.
 *
 * <p>Keys the durable call by the framework-assigned identity as {@code sessionId#callId} (the
 * {@link SubagentSetup} contract) and captures exceptions thrown by {@link #callInternal()} into
 * {@link Result#error(Exception)}, so failures are reported through the result rather than thrown.
 * Implementations only provide {@link #callInternal()}.
 */
public abstract class BaseSubagentCallable implements DurableCallable<Result> {

    private final String sessionId;
    private final String callId;

    protected BaseSubagentCallable(String sessionId, String callId) {
        this.sessionId = sessionId;
        this.callId = callId;
    }

    @Override
    public String getId() {
        return sessionId + "#" + callId;
    }

    @Override
    public Class<Result> getResultClass() {
        return Result.class;
    }

    @Override
    public final Result call() {
        try {
            return Result.ok(callInternal());
        } catch (Exception e) {
            return Result.error(e);
        }
    }

    /**
     * Performs the invocation and returns the JSON-serializable payload. Thrown exceptions are
     * captured into a failed {@link Result}.
     */
    protected abstract Object callInternal() throws Exception;
}
