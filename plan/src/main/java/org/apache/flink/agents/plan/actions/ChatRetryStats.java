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

package org.apache.flink.agents.plan.actions;

import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Retry statistics of one logical chat request: the additional invocations its model attempts
 * needed and the backoff they waited, accumulated in sensory memory under the initial request id
 * across every candidate and every round of a tool loop, and reported per attempt on the model
 * resource's {@code retryCount} and {@code retryWaitMs} counters.
 *
 * <p>Whatever calls a model on behalf of the request {@link #recordAttempt records} its attempt,
 * including the ones that failed: the routed round for each candidate it tries, and the judge, the
 * decision model and the quality verifier asked while routing. {@link ChatModelAction} reads the
 * {@link #totals} for the response event and {@link #clear clears} them when the request completes.
 * Retries are recorded per attempt, so a request that ultimately fails contributes its retries too;
 * the totals of a completed request are the sum over its attempts.
 */
public final class ChatRetryStats {

    /** Sensory-memory key of the per-request totals, keyed by initial request id. */
    static final String RETRY_STATS_CONTEXT = "_RETRY_STATS_CONTEXT";

    private static final String TOTAL_RETRY_COUNT = "totalRetryCount";
    private static final String TOTAL_RETRY_WAIT_MS = "totalRetryWaitMs";

    private ChatRetryStats() {}

    /** The totals of one request: additional invocations and the backoff they waited, in ms. */
    public static final class Totals {
        public final int retryCount;
        public final long retryWaitMs;

        Totals(int retryCount, long retryWaitMs) {
            this.retryCount = retryCount;
            this.retryWaitMs = retryWaitMs;
        }
    }

    /**
     * Records one attempt's retries: adds them to the request's totals and to the counters of the
     * model resource that was called. An attempt that needed no retry records nothing.
     */
    public static void recordAttempt(
            RunnerContext ctx,
            UUID requestId,
            String modelResource,
            int retryCount,
            long retryWaitMs)
            throws Exception {
        if (retryCount <= 0) {
            return;
        }
        accumulate(ctx.getSensoryMemory(), requestId, retryCount, retryWaitMs);
        recordMetrics(ctx, modelResource, retryCount, retryWaitMs);
    }

    /** The request's totals so far; zero when no attempt recorded a retry. */
    @SuppressWarnings("unchecked")
    public static Totals totals(MemoryObject memory, UUID requestId) throws Exception {
        if (!memory.isExist(RETRY_STATS_CONTEXT)) {
            return new Totals(0, 0L);
        }
        Map<UUID, Map<String, Long>> context =
                (Map<UUID, Map<String, Long>>) memory.get(RETRY_STATS_CONTEXT).getValue();
        Map<String, Long> stats = context.get(requestId);
        if (stats == null) {
            return new Totals(0, 0L);
        }
        return new Totals(
                stats.getOrDefault(TOTAL_RETRY_COUNT, 0L).intValue(),
                stats.getOrDefault(TOTAL_RETRY_WAIT_MS, 0L));
    }

    /** Removes the request's totals; a no-op when no attempt recorded a retry. */
    @SuppressWarnings("unchecked")
    public static void clear(MemoryObject memory, UUID requestId) throws Exception {
        if (!memory.isExist(RETRY_STATS_CONTEXT)) {
            return;
        }
        Map<UUID, Object> context =
                new HashMap<>((Map<UUID, Object>) memory.get(RETRY_STATS_CONTEXT).getValue());
        if (context.remove(requestId) != null) {
            memory.set(RETRY_STATS_CONTEXT, context);
        }
    }

    @SuppressWarnings("unchecked")
    private static void accumulate(
            MemoryObject memory, UUID requestId, int retryCount, long retryWaitMs)
            throws Exception {
        Map<UUID, Map<String, Long>> context;
        if (memory.isExist(RETRY_STATS_CONTEXT)) {
            context = (Map<UUID, Map<String, Long>>) memory.get(RETRY_STATS_CONTEXT).getValue();
        } else {
            context = new HashMap<>();
        }
        Map<String, Long> stats = context.getOrDefault(requestId, new HashMap<>());
        stats.put(TOTAL_RETRY_COUNT, stats.getOrDefault(TOTAL_RETRY_COUNT, 0L) + retryCount);
        stats.put(TOTAL_RETRY_WAIT_MS, stats.getOrDefault(TOTAL_RETRY_WAIT_MS, 0L) + retryWaitMs);
        context.put(requestId, stats);
        memory.set(RETRY_STATS_CONTEXT, context);
    }

    private static void recordMetrics(
            RunnerContext ctx, String modelResource, int retryCount, long retryWaitMs) {
        FlinkAgentsMetricGroup metricGroup = ctx.getActionMetricGroup();
        if (metricGroup != null) {
            FlinkAgentsMetricGroup modelResourceGroup =
                    metricGroup.getSubGroup("model_resource", modelResource);
            modelResourceGroup.getCounter("retryCount").inc(retryCount);
            modelResourceGroup.getCounter("retryWaitMs").inc(retryWaitMs);
        }
    }
}
