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

package org.apache.flink.agents.runtime.subagent.external;

import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.runtime.subagent.BaseDeferredSubagentSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Integration of {@link BaseDeferredSubagentSetup} against the external async-task agent demo
 * service: each deferred handle, when resolved, runs one self-contained invocation — submit the
 * task under the deterministic idempotency key derived from {@code (sessionId, callId)} through
 * {@link ExternalAgentClient#taskIdFor}, then poll the status until a terminal state, and fetch the
 * result. The poll interval is chosen so that a run of the service's task delay is checked several
 * times before it finishes. The durable id is derived solely from the {@code (sessionId, callId)}
 * pair, so a replay after failover re-issues the same logical invocation and the idempotent submit
 * reuses the original remote task.
 */
public class ExternalDeferredSubagentSetup extends BaseDeferredSubagentSetup {

    private static final Logger LOG = LoggerFactory.getLogger(ExternalDeferredSubagentSetup.class);

    private final String baseUrl;
    private final long pollIntervalMillis;
    private final AtomicInteger pollCount = new AtomicInteger();

    @Nullable private transient ExternalAgentClient client;

    public ExternalDeferredSubagentSetup(String baseUrl, long pollIntervalMillis) {
        this.baseUrl = baseUrl;
        this.pollIntervalMillis = pollIntervalMillis;
    }

    private ExternalAgentClient client() {
        if (client == null) {
            client = new ExternalAgentClient(baseUrl);
        }
        return client;
    }

    @Override
    protected DurableCallable<SubagentResult> prepare(
            RunnerContext ctx, Object prompt, String sessionId, String callId) {
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
                try {
                    LOG.info("[deferred] resolve {}#{} prompt={}", sessionId, callId, prompt);
                    String taskId =
                            client().submit(
                                            String.valueOf(prompt),
                                            sessionId,
                                            ExternalAgentClient.taskIdFor(sessionId, callId));
                    LOG.info(
                            "[deferred] {}#{} submitted as {}; polling once per second",
                            sessionId,
                            callId,
                            taskId);
                    while (true) {
                        int check = pollCount.incrementAndGet();
                        ExternalAgentClient.TaskStatus probe = client().status(taskId);
                        LOG.info(
                                "[deferred] {}#{} check #{} -> {}",
                                sessionId,
                                callId,
                                check,
                                probe == null ? "404" : probe.getStatus());
                        if (probe == null) {
                            return SubagentResult.error("remote task disappeared: " + taskId);
                        }
                        String status = probe.getStatus();
                        if (ExternalAgentClient.SUCCEEDED.equals(status)
                                || ExternalAgentClient.FAILED.equals(status)) {
                            SubagentResult result = client().fetchResult(taskId);
                            LOG.info(
                                    "[deferred] {}#{} finished: success={}, result={}, error={}",
                                    sessionId,
                                    callId,
                                    result.isSuccess(),
                                    result.getResult(),
                                    result.getErrorMessage());
                            return result;
                        }
                        Thread.sleep(pollIntervalMillis);
                    }
                } catch (Exception e) {
                    return SubagentResult.error(e);
                }
            }
        };
    }

    /** Total number of status polls across all invocations, for test pacing assertions. */
    public int pollCount() {
        return pollCount.get();
    }
}
