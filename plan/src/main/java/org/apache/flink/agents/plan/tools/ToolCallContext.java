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

package org.apache.flink.agents.plan.tools;

import java.time.Instant;

/**
 * Per-call execution settings, timestamps, and cancellation callback shared with the tool action.
 */
public final class ToolCallContext {
    private final String callId;
    private final boolean async;
    private Runnable cancellation = () -> {};
    private volatile Instant startedAt;
    private volatile Instant finishedAt;

    public ToolCallContext(String callId, boolean async) {
        this.callId = callId;
        this.async = async;
    }

    public void onCancel(Runnable cancellation) {
        this.cancellation = cancellation;
    }

    public void cancel() {
        cancellation.run();
    }

    public String getCallId() {
        return callId;
    }

    public boolean isAsync() {
        return async;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void started() {
        startedAt = Instant.now();
    }

    public void finished() {
        finishedAt = Instant.now();
    }
}
