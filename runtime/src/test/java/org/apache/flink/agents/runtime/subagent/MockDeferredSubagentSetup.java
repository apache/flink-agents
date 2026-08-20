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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock integration of {@link BaseDeferredSubagentSetup}: echoes the prompt back as the resolved
 * value. Records every {@code (sessionId, callId)} pair assigned to it.
 *
 * <p>Capture happens at prepare time rather than in the call body, so tests can assert on assigned
 * ids even when the durable call is served from cache; {@link #executionCount()} separately tracks
 * real executions. State is static because one setup instance is shared by every resolving task —
 * call {@link #reset()} before each independent scenario.
 */
public class MockDeferredSubagentSetup extends BaseDeferredSubagentSetup {

    /** One {@code (sessionId, callId)} assignment captured at prepare time. */
    public static final class Capture {
        public final String sessionId;
        public final String callId;
        public final Object prompt;

        Capture(String sessionId, String callId, Object prompt) {
            this.sessionId = sessionId;
            this.callId = callId;
            this.prompt = prompt;
        }

        @Override
        public String toString() {
            return "Capture{sessionId="
                    + sessionId
                    + ", callId="
                    + callId
                    + ", prompt="
                    + prompt
                    + "}";
        }
    }

    private static final List<Capture> CAPTURES = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger EXECUTION_COUNT = new AtomicInteger();

    /** Clears all captures and the execution counter. Call before each independent scenario. */
    public static void reset() {
        CAPTURES.clear();
        EXECUTION_COUNT.set(0);
    }

    /** Snapshot of every assignment captured since the last {@link #reset()}, in creation order. */
    public static List<Capture> captures() {
        synchronized (CAPTURES) {
            return new ArrayList<>(CAPTURES);
        }
    }

    /** Number of times a prepared call body actually ran. */
    public static int executionCount() {
        return EXECUTION_COUNT.get();
    }

    @Override
    protected DurableCallable<SubagentResult> prepare(
            RunnerContext ctx, Object prompt, String sessionId, String callId) {
        CAPTURES.add(new Capture(sessionId, callId, prompt));
        return new DurableCallable<SubagentResult>() {
            @Override
            public String getId() {
                return sessionId + "#" + callId;
            }

            @Override
            public Class<SubagentResult> getResultClass() {
                return SubagentResult.class;
            }

            @Override
            public SubagentResult call() {
                EXECUTION_COUNT.incrementAndGet();
                return SubagentResult.ok(sessionId + "|" + callId + "|" + prompt);
            }
        };
    }
}
