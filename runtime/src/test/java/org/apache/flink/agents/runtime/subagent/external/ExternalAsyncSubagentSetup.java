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

import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.runtime.subagent.BaseAsyncSubagentSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Integration of {@link BaseAsyncSubagentSetup} against the external async-task agent demo service:
 * the pub is {@code POST /tasks}, the sub probes {@code GET /tasks/{id}} and fetches {@code GET
 * /tasks/{id}/result}. The remote task id is derived deterministically from the {@code (sessionId,
 * callId)} pair through {@link ExternalAgentClient#taskIdFor} and sent as the idempotency key of
 * the POST, so the setup keeps no local state: after a failover the probe finds the original task
 * again, and a reconciled resubmission never creates a duplicate. A probe hitting an unknown id
 * reports {@link RunStatus#notStarted()}, letting the base's reconciler re-post the submission. The
 * service offers no cancel endpoint, so the cancel hook stays the default no-op.
 *
 * <p>Pacing: the await probes the remote status right after the submission, at the interval the
 * setup is created with, which is chosen so that a run of the service's task delay is checked
 * several times before it finishes. Every probe is logged and recorded for test assertions.
 */
public class ExternalAsyncSubagentSetup extends BaseAsyncSubagentSetup {

    private static final Logger LOG = LoggerFactory.getLogger(ExternalAsyncSubagentSetup.class);

    private final String baseUrl;
    private final List<Long> probeTimestamps = Collections.synchronizedList(new ArrayList<>());

    @Nullable private transient ExternalAgentClient client;

    public ExternalAsyncSubagentSetup(String baseUrl, long probeIntervalMillis) {
        this.baseUrl = baseUrl;
        this.statusPollIntervalMillis = probeIntervalMillis;
    }

    private ExternalAgentClient client() {
        if (client == null) {
            client = new ExternalAgentClient(baseUrl);
        }
        return client;
    }

    @Override
    protected void callSubmitRequest(String sessionId, String callId, Object prompt)
            throws Exception {
        LOG.info("[async] submit {}#{} prompt={}", sessionId, callId, prompt);
        client().submit(String.valueOf(prompt), sessionId, taskId(sessionId, callId));
    }

    @Override
    protected RunStatus callQueryStatus(String sessionId, String callId) {
        probeTimestamps.add(System.currentTimeMillis());
        ExternalAgentClient.TaskStatus probe;
        try {
            probe = client().status(taskId(sessionId, callId));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // A broken probe is a system-level failure: propagate and let the job fail over.
            throw new RuntimeException("status probe failed for " + sessionId + "#" + callId, e);
        }
        if (probe == null) {
            // No task under the deterministic id: the POST never landed.
            return RunStatus.notStarted();
        }
        switch (probe.getStatus()) {
            case ExternalAgentClient.SUCCEEDED:
                return RunStatus.completed();
            case ExternalAgentClient.FAILED:
                return RunStatus.failed(
                        probe.getError() == null ? "remote task failed" : probe.getError());
            default:
                // pending or running
                return RunStatus.running();
        }
    }

    @Override
    protected SubagentResult callFetchResult(String sessionId, String callId) throws Exception {
        LOG.info("[async] fetch {}#{}", sessionId, callId);
        return client().fetchResult(taskId(sessionId, callId));
    }

    private static String taskId(String sessionId, String callId) {
        return ExternalAgentClient.taskIdFor(sessionId, callId);
    }

    /** Test-facing entry into the crash-window reconciliation of the durable POST. */
    void reconcileForTest(String sessionId, String callId, Object prompt) throws Exception {
        reconcileSubmitRequest(sessionId, callId, prompt);
    }

    /** Timestamps of every status probe, for asserting the probe pacing in tests. */
    public List<Long> probeTimestamps() {
        synchronized (probeTimestamps) {
            return new ArrayList<>(probeTimestamps);
        }
    }
}
