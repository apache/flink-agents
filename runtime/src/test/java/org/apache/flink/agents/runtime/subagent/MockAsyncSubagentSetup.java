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
import org.apache.flink.agents.api.subagent.SubagentResult;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Example integration of {@link BaseAsyncSubagentSetup}: an in-memory asynchronous agent service.
 * Demonstrates that an integration only supplies the transport primitives — {@link
 * #callSubmitRequest}, {@link #callQueryStatus} and {@link #callFetchResult} — plus the optional
 * cancel hook; all durable composition and recovery knowledge stays in the base. Counters let tests
 * assert how many times each endpoint was hit.
 */
public class MockAsyncSubagentSetup extends BaseAsyncSubagentSetup {

    /** One recorded remote run, keyed by {@code sessionId#callId}. */
    private static final class Run {
        private final Object result;
        @Nullable private final String error;
        private int queriesRemaining;

        private Run(Object result, @Nullable String error, int queriesRemaining) {
            this.result = result;
            this.error = error;
            this.queriesRemaining = queriesRemaining;
        }
    }

    private final Map<String, Run> runs = new HashMap<>();
    private final int queriesUntilComplete;
    private final boolean failOnPost;

    private int postCount;
    private int statusQueryCount;
    private int fetchCount;
    private int cancelCount;

    public MockAsyncSubagentSetup() {
        this(2, false);
    }

    /**
     * Creates a setup whose runs need {@code queriesUntilComplete} RUNNING probes before turning
     * terminal; {@code failOnPost} makes every submission fail.
     */
    public MockAsyncSubagentSetup(int queriesUntilComplete, boolean failOnPost) {
        this.queriesUntilComplete = queriesUntilComplete;
        this.failOnPost = failOnPost;
        // Runs turn terminal after a fixed number of probes rather than after elapsed time, so
        // probing without a delay keeps the counts identical and the tests fast.
        this.statusPollIntervalMillis = 0;
    }

    @Override
    protected void callSubmitRequest(String sessionId, String callId, Object prompt) {
        postCount++;
        if (failOnPost) {
            throw new IllegalStateException("post failed");
        }
        runs.put(sessionId + "#" + callId, new Run("done:" + prompt, null, queriesUntilComplete));
    }

    @Override
    protected RunStatus callQueryStatus(String sessionId, String callId) {
        statusQueryCount++;
        Run run = runs.get(sessionId + "#" + callId);
        if (run == null) {
            return RunStatus.notStarted();
        }
        if (run.queriesRemaining > 0) {
            run.queriesRemaining--;
            return RunStatus.running();
        }
        return run.error == null ? RunStatus.completed() : RunStatus.failed(run.error);
    }

    @Override
    protected SubagentResult callFetchResult(String sessionId, String callId) {
        fetchCount++;
        Run run = runs.get(sessionId + "#" + callId);
        if (run == null) {
            return SubagentResult.error("no run on record");
        }
        return run.error == null ? SubagentResult.ok(run.result) : SubagentResult.error(run.error);
    }

    @Override
    protected void callCancelRequest(String sessionId, String callId) {
        cancelCount++;
    }

    /** Test hook: injects a run that already exists remotely, exercising reconciler reuse. */
    public void seedRun(
            String sessionId,
            String callId,
            Object result,
            @Nullable String error,
            int queriesUntilComplete) {
        runs.put(sessionId + "#" + callId, new Run(result, error, queriesUntilComplete));
    }

    /** Number of times the POST endpoint has been hit. */
    public int postCount() {
        return postCount;
    }

    /** Number of times the status endpoint has been probed. */
    public int statusQueryCount() {
        return statusQueryCount;
    }

    /** Number of times the result endpoint has been fetched. */
    public int fetchCount() {
        return fetchCount;
    }

    /** Number of times the cancel hook has been invoked. */
    public int cancelCount() {
        return cancelCount;
    }

    /** Exposes the pub durable call for unit-style POST and reconciler assertions. */
    public DurableCallable<Void> submitRequestForTest(
            Object prompt, String sessionId, String callId) {
        return submitRequest(null, sessionId, callId, prompt);
    }

    /** Exposes the await durable call for unit-style assertions. */
    public DurableCallable<SubagentResult> awaitResultForTest(String sessionId, String callId) {
        return awaitResult(null, sessionId, callId);
    }
}
