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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Self-coordinating quiesce state machine for a single sub-agent call. Completion is driven by two
 * counters: {@code runningActions} (child actions executing) and {@code pendingEvents} (child
 * events not yet dispatched). Every mutating method checks whether both reached zero and completes
 * the response future itself, so callers never invoke a separate tryComplete step.
 */
public class InternalSubagentCallStatus {

    private final String callId;
    private final String scope;
    private final String sessionId;
    private final InternalSubagentSetup setup;
    private final CompletableFuture<List<Object>> responseFuture = new CompletableFuture<>();
    private int runningActions;
    private int pendingEvents;
    private final List<Object> output = new ArrayList<>();

    public InternalSubagentCallStatus(
            String callId, String scope, String sessionId, InternalSubagentSetup setup) {
        this.callId = callId;
        this.scope = scope;
        this.sessionId = sessionId;
        this.setup = setup;
    }

    /**
     * The sub-agent this call targets, resolved by the caller when the call was bootstrapped.
     *
     * <p>Carried here rather than re-resolved from the scope name at dispatch time: a nested call
     * targets a scope registered in the caller's own child plan, which the root resource cache
     * cannot see.
     */
    public InternalSubagentSetup getSetup() {
        return setup;
    }

    public String getCallId() {
        return callId;
    }

    public String getScope() {
        return scope;
    }

    public String getSessionId() {
        return sessionId;
    }

    public CompletableFuture<List<Object>> getResponseFuture() {
        return responseFuture;
    }

    public void emitEvent() {
        pendingEvents++;
    }

    public void dispatchEvent(int triggeredActions) {
        if (pendingEvents > 0) {
            pendingEvents--;
        }
        runningActions += triggeredActions;
        tryComplete();
    }

    /**
     * Accounts for one envelope this call emitted being dispatched. The emitter and the envelope's
     * target are different calls when the call is nested, so this (not {@link #dispatchEvent}) is
     * what releases the emitter's pending count.
     */
    public void markEmittedEventDispatched() {
        if (pendingEvents > 0) {
            pendingEvents--;
        }
        tryComplete();
    }

    /** Adds actions triggered on behalf of this call (an envelope's target side). */
    public void addTriggeredActions(int count) {
        runningActions += count;
        tryComplete();
    }

    public void completeAction() {
        runningActions--;
        tryComplete();
    }

    public void accumulateOutput(Object payload) {
        output.add(payload);
    }

    public int getRunningActions() {
        return runningActions;
    }

    public int getPendingEvents() {
        return pendingEvents;
    }

    public void failAction(Throwable cause) {
        responseFuture.completeExceptionally(cause);
    }

    public void cancel() {
        responseFuture.cancel(false);
    }

    public boolean isDone() {
        return responseFuture.isDone();
    }

    private void tryComplete() {
        if (responseFuture.isDone()) {
            return;
        }
        if (runningActions != 0 || pendingEvents != 0) {
            return;
        }
        responseFuture.complete(new ArrayList<>(output));
    }
}
