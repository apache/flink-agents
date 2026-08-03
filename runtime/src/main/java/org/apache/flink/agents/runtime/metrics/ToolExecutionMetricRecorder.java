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
import org.apache.flink.agents.api.trace.ToolExecutionMetadataKeys;
import org.apache.flink.metrics.Histogram;

/** Records Tool metrics and additional Skill and MCP projections. */
final class ToolExecutionMetricRecorder implements ExecutionMetricRecorder {

    static final String NUM_TOOL_CALLS_SUCCEEDED = "numOfToolCallsSucceeded";
    static final String NUM_TOOL_CALLS_FAILED = "numOfToolCallsFailed";
    static final String TOOL_CALL_LATENCY_MS = "toolCallLatencyMs";

    static final String NUM_SKILL_LOADS = "numOfSkillLoads";
    static final String SKILL_LOAD_LATENCY_MS = "skillLoadLatencyMs";

    static final String NUM_MCP_TOOL_CALLS_SUCCEEDED = "numOfMcpToolCallsSucceeded";
    static final String NUM_MCP_TOOL_CALLS_FAILED = "numOfMcpToolCallsFailed";
    static final String MCP_TOOL_CALL_LATENCY_MS = "mcpToolCallLatencyMs";

    @Override
    public String entityType() {
        return ExecutionReporter.EntityTypes.TOOL;
    }

    @Override
    public void record(
            FlinkAgentsMetricGroupImpl actionMetricGroup,
            ExecutionTraceContext traceContext,
            Outcome outcome,
            Long latencyMs) {
        String toolName = traceContext.getEntityName();
        if (!isBlank(toolName)) {
            recordOutcome(
                    actionMetricGroup.getSubGroup("tool", toolName),
                    outcome,
                    NUM_TOOL_CALLS_SUCCEEDED,
                    NUM_TOOL_CALLS_FAILED,
                    TOOL_CALL_LATENCY_MS,
                    latencyMs);
        }

        String skillName = metadataValue(traceContext, ToolExecutionMetadataKeys.SKILL_NAME);
        if (!isBlank(skillName)) {
            FlinkAgentsMetricGroupImpl skillMetricGroup =
                    actionMetricGroup.getSubGroup("skill", skillName);
            skillMetricGroup.getCounter(NUM_SKILL_LOADS).inc();
            updateLatency(skillMetricGroup.getHistogram(SKILL_LOAD_LATENCY_MS), latencyMs);
        }

        String mcpServer = metadataValue(traceContext, ToolExecutionMetadataKeys.MCP_SERVER);
        if (!isBlank(mcpServer)) {
            recordOutcome(
                    actionMetricGroup.getSubGroup("mcp_server", mcpServer),
                    outcome,
                    NUM_MCP_TOOL_CALLS_SUCCEEDED,
                    NUM_MCP_TOOL_CALLS_FAILED,
                    MCP_TOOL_CALL_LATENCY_MS,
                    latencyMs);
        }
    }

    private static void recordOutcome(
            FlinkAgentsMetricGroupImpl metricGroup,
            Outcome outcome,
            String succeededCounter,
            String failedCounter,
            String latencyHistogram,
            Long latencyMs) {
        metricGroup
                .getCounter(outcome == Outcome.SUCCEEDED ? succeededCounter : failedCounter)
                .inc();
        updateLatency(metricGroup.getHistogram(latencyHistogram), latencyMs);
    }

    private static void updateLatency(Histogram histogram, Long latencyMs) {
        if (latencyMs != null) {
            histogram.update(latencyMs);
        }
    }

    private static String metadataValue(ExecutionTraceContext traceContext, String metadataKey) {
        Object value = traceContext.getEntityMetadata().get(metadataKey);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
