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

import org.apache.flink.agents.api.context.Outcome;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentFutures;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.context.SchedulableFuture;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/** A handle for an invocation that has already produced its value. */
public final class CompletedSubagentFuture extends SubagentFuture
        implements SchedulableFuture<SubagentResult> {

    private final SubagentResult value;

    @Override
    public RunnerContext getOwnerContext() {
        return null;
    }

    @Override
    public Outcome<SubagentResult> prepare(RunnerContextImpl owner) {
        return Outcome.success(value);
    }

    @Override
    public CompletableFuture<?> start() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public Outcome<SubagentResult> finish(TimeoutException timeout) {
        return Outcome.success(value);
    }

    @Override
    public void close() {}

    public CompletedSubagentFuture(String sessionId, String callId, SubagentResult value) {
        super(sessionId, callId);
        this.value = value;
    }

    @Override
    public boolean isDone() {
        return true;
    }

    @Override
    public SubagentResult await() {
        return value;
    }

    @Override
    public SubagentFutures combine(SubagentFuture... others) {
        return new SubagentFutureGroup(this, others);
    }
}
