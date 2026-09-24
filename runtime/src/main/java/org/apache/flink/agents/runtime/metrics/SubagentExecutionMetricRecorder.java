/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.flink.agents.runtime.metrics;

import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.ExecutionTraceContext;
import org.apache.flink.metrics.Histogram;

/** Records framework-observed sub-agent delegation outcomes and latency by registered name. */
final class SubagentExecutionMetricRecorder implements ExecutionMetricRecorder {

    static final String NUM_SUBAGENT_CALLS_SUCCEEDED = "numOfSubagentCallsSucceeded";
    static final String NUM_SUBAGENT_CALLS_FAILED = "numOfSubagentCallsFailed";
    static final String SUBAGENT_CALL_LATENCY_MS = "subagentCallLatencyMs";

    @Override
    public String entityType() {
        return ExecutionReporter.EntityTypes.SUBAGENT;
    }

    @Override
    public void record(
            FlinkAgentsMetricGroupImpl actionMetricGroup,
            ExecutionTraceContext traceContext,
            Outcome outcome,
            Long latencyMs) {
        String entityName = traceContext.getEntityName();
        if (entityName == null || entityName.isBlank()) {
            return;
        }
        // Only a resolved delegation is reported under this entity type, keyed by the registered
        // sub-agent name, so the scope cardinality is bounded by the plan and needs no unknown
        // bucket; an unresolved _subagent_ name is reported as a tool and lands in tool=unknown.
        FlinkAgentsMetricGroupImpl subagentMetricGroup =
                actionMetricGroup.getSubGroup("subagent", entityName);
        subagentMetricGroup
                .getCounter(
                        outcome == Outcome.SUCCEEDED
                                ? NUM_SUBAGENT_CALLS_SUCCEEDED
                                : NUM_SUBAGENT_CALLS_FAILED)
                .inc();
        Histogram latencyHistogram = subagentMetricGroup.getHistogram(SUBAGENT_CALL_LATENCY_MS);
        if (latencyMs != null) {
            latencyHistogram.update(latencyMs);
        }
    }
}
