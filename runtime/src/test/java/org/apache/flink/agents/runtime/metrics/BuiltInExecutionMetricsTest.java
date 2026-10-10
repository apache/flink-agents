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
import org.apache.flink.agents.api.trace.ToolExecutionMetadataKeys;
import org.apache.flink.agents.api.trace.TraceContext;
import org.apache.flink.agents.api.trace.TraceRecord;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInExecutionMetricsTest {

    private static final String ACTION_NAME = "chat_model_action";

    private FlinkAgentsMetricGroupImpl metricGroup;
    private BuiltInExecutionMetrics metrics;

    @BeforeEach
    void setUp() {
        MetricGroup parentMetricGroup =
                UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup();
        metricGroup = new FlinkAgentsMetricGroupImpl(parentMetricGroup);
        Set<String> registeredTools = Set.of("search", "fetch", "load_skill");
        metrics = new BuiltInExecutionMetrics(metricGroup, registeredTools::contains);
    }

    @Test
    void recordsLlmOutcomeByModelResource() {
        TraceContext success =
                execution(ExecutionReporter.EntityTypes.LLM, "primary_model", Map.of());
        observe(TraceRecord.Statuses.STARTED, success, 0);
        observe(TraceRecord.Statuses.SUCCESS, success, 25);

        TraceContext failure =
                execution(ExecutionReporter.EntityTypes.LLM, "primary_model", Map.of());
        observe(TraceRecord.Statuses.STARTED, failure, 100);
        observe(TraceRecord.Statuses.FAILED, failure, 140);

        FlinkAgentsMetricGroupImpl modelResource =
                actionMetricGroup().getSubGroup("model_resource", "primary_model");
        assertThat(
                        modelResource
                                .getCounter(LlmExecutionMetricRecorder.NUM_LLM_CALLS_SUCCEEDED)
                                .getCount())
                .isEqualTo(1);
        assertThat(
                        modelResource
                                .getCounter(LlmExecutionMetricRecorder.NUM_LLM_CALLS_FAILED)
                                .getCount())
                .isEqualTo(1);
        assertThat(
                        modelResource
                                .getHistogram(LlmExecutionMetricRecorder.LLM_CALL_LATENCY_MS)
                                .getCount())
                .isEqualTo(2);
    }

    @Test
    void recordsToolOutcomeByToolName() {
        TraceContext success = execution(ExecutionReporter.EntityTypes.TOOL, "search", Map.of());
        observe(TraceRecord.Statuses.STARTED, success, 0);
        observe(TraceRecord.Statuses.SUCCESS, success, 15);

        TraceContext failure = execution(ExecutionReporter.EntityTypes.TOOL, "search", Map.of());
        observe(TraceRecord.Statuses.STARTED, failure, 5);
        observe(TraceRecord.Statuses.FAILED, failure, 25);

        FlinkAgentsMetricGroupImpl tool = actionMetricGroup().getSubGroup("tool", "search");
        assertThat(tool.getCounter(ToolExecutionMetricRecorder.NUM_TOOL_CALLS_SUCCEEDED).getCount())
                .isEqualTo(1);
        assertThat(tool.getCounter(ToolExecutionMetricRecorder.NUM_TOOL_CALLS_FAILED).getCount())
                .isEqualTo(1);
        assertThat(tool.getHistogram(ToolExecutionMetricRecorder.TOOL_CALL_LATENCY_MS).getCount())
                .isEqualTo(2);
        assertThat(
                        tool.getHistogram(ToolExecutionMetricRecorder.TOOL_CALL_LATENCY_MS)
                                .getStatistics()
                                .getMax())
                .isEqualTo(20L);
    }

    @Test
    void recordsSubagentOutcomeByRegisteredAgentName() {
        TraceContext success =
                execution(ExecutionReporter.EntityTypes.SUBAGENT, "reviewer", Map.of());
        observe(TraceRecord.Statuses.STARTED, success, 0);
        observe(TraceRecord.Statuses.SUCCESS, success, 25);

        TraceContext failure =
                execution(ExecutionReporter.EntityTypes.SUBAGENT, "reviewer", Map.of());
        observe(TraceRecord.Statuses.STARTED, failure, 100);
        observe(TraceRecord.Statuses.FAILED, failure, 140);

        FlinkAgentsMetricGroupImpl subagent =
                actionMetricGroup().getSubGroup("subagent", "reviewer");
        assertThat(
                        subagent.getCounter(
                                        SubagentExecutionMetricRecorder
                                                .NUM_SUBAGENT_CALLS_SUCCEEDED)
                                .getCount())
                .isEqualTo(1);
        assertThat(
                        subagent.getCounter(
                                        SubagentExecutionMetricRecorder.NUM_SUBAGENT_CALLS_FAILED)
                                .getCount())
                .isEqualTo(1);
        assertThat(
                        subagent.getHistogram(
                                        SubagentExecutionMetricRecorder.SUBAGENT_CALL_LATENCY_MS)
                                .getCount())
                .isEqualTo(2);
        assertThat(
                        subagent.getHistogram(
                                        SubagentExecutionMetricRecorder.SUBAGENT_CALL_LATENCY_MS)
                                .getStatistics()
                                .getMax())
                .isEqualTo(40L);
    }

    @Test
    void aggregatesUnregisteredToolNamesIntoUnknownScope() {
        TraceContext first =
                execution(ExecutionReporter.EntityTypes.TOOL, "hallucinated_one", Map.of());
        TraceContext second =
                execution(ExecutionReporter.EntityTypes.TOOL, "hallucinated_two", Map.of());

        observe(TraceRecord.Statuses.FAILED, first, 0);
        observe(TraceRecord.Statuses.FAILED, second, 1);

        FlinkAgentsMetricGroupImpl unknown =
                actionMetricGroup()
                        .getSubGroup("tool", ToolExecutionMetricRecorder.UNKNOWN_TOOL_NAME);
        assertThat(unknown.getCounter(ToolExecutionMetricRecorder.NUM_TOOL_CALLS_FAILED).getCount())
                .isEqualTo(2);
    }

    @Test
    void recordsExplicitSkillLoads() {
        TraceContext loadSkill =
                execution(
                        ExecutionReporter.EntityTypes.TOOL,
                        "load_skill",
                        Map.of(
                                ToolExecutionMetadataKeys.SKILL_NAME,
                                "calculator",
                                ToolExecutionMetadataKeys.SKILL_REGISTERED,
                                true));
        observe(TraceRecord.Statuses.STARTED, loadSkill, 0);
        observe(TraceRecord.Statuses.SUCCESS, loadSkill, 12);

        FlinkAgentsMetricGroupImpl skill = actionMetricGroup().getSubGroup("skill", "calculator");
        assertThat(skill.getCounter(ToolExecutionMetricRecorder.NUM_SKILL_LOADS).getCount())
                .isEqualTo(1);
        assertThat(
                        skill.getHistogram(ToolExecutionMetricRecorder.SKILL_LOAD_LATENCY_MS)
                                .getStatistics()
                                .getMax())
                .isEqualTo(12L);

        FlinkAgentsMetricGroupImpl tool = actionMetricGroup().getSubGroup("tool", "load_skill");
        assertThat(tool.getCounter(ToolExecutionMetricRecorder.NUM_TOOL_CALLS_SUCCEEDED).getCount())
                .isEqualTo(1);
    }

    @Test
    void aggregatesUnregisteredSkillNamesIntoUnknownScope() {
        TraceContext first =
                execution(
                        ExecutionReporter.EntityTypes.TOOL,
                        "load_skill",
                        Map.of(
                                ToolExecutionMetadataKeys.SKILL_NAME,
                                "hallucinated_one",
                                ToolExecutionMetadataKeys.SKILL_REGISTERED,
                                false));
        TraceContext second =
                execution(
                        ExecutionReporter.EntityTypes.TOOL,
                        "load_skill",
                        Map.of(
                                ToolExecutionMetadataKeys.SKILL_NAME,
                                "hallucinated_two",
                                ToolExecutionMetadataKeys.SKILL_REGISTERED,
                                false));

        observe(TraceRecord.Statuses.SUCCESS, first, 0);
        observe(TraceRecord.Statuses.SUCCESS, second, 1);

        FlinkAgentsMetricGroupImpl unknown =
                actionMetricGroup()
                        .getSubGroup("skill", ToolExecutionMetricRecorder.UNKNOWN_SKILL_NAME);
        assertThat(unknown.getCounter(ToolExecutionMetricRecorder.NUM_SKILL_LOADS).getCount())
                .isEqualTo(2);
    }

    @Test
    void aggregatesMcpToolOutcomesByServer() {
        TraceContext success =
                execution(
                        ExecutionReporter.EntityTypes.TOOL,
                        "search",
                        Map.of(ToolExecutionMetadataKeys.MCP_SERVER, "search_server"));
        observe(TraceRecord.Statuses.STARTED, success, 0);
        observe(TraceRecord.Statuses.SUCCESS, success, 30);

        TraceContext failure =
                execution(
                        ExecutionReporter.EntityTypes.TOOL,
                        "fetch",
                        Map.of(ToolExecutionMetadataKeys.MCP_SERVER, "search_server"));
        observe(TraceRecord.Statuses.STARTED, failure, 10);
        observe(TraceRecord.Statuses.FAILED, failure, 60);

        FlinkAgentsMetricGroupImpl mcpServer =
                actionMetricGroup().getSubGroup("mcp_server", "search_server");
        assertThat(
                        mcpServer
                                .getCounter(
                                        ToolExecutionMetricRecorder.NUM_MCP_TOOL_CALLS_SUCCEEDED)
                                .getCount())
                .isEqualTo(1);
        assertThat(
                        mcpServer
                                .getCounter(ToolExecutionMetricRecorder.NUM_MCP_TOOL_CALLS_FAILED)
                                .getCount())
                .isEqualTo(1);
        assertThat(
                        mcpServer
                                .getHistogram(ToolExecutionMetricRecorder.MCP_TOOL_CALL_LATENCY_MS)
                                .getCount())
                .isEqualTo(2);
    }

    @Test
    void terminalRecordWithoutLocalStartDoesNotRecordLatency() {
        TraceContext llm = execution(ExecutionReporter.EntityTypes.LLM, "restored_model", Map.of());
        observe(TraceRecord.Statuses.SUCCESS, llm, 0);

        FlinkAgentsMetricGroupImpl modelResource =
                actionMetricGroup().getSubGroup("model_resource", "restored_model");
        assertThat(
                        modelResource
                                .getCounter(LlmExecutionMetricRecorder.NUM_LLM_CALLS_SUCCEEDED)
                                .getCount())
                .isEqualTo(1);
        assertThat(
                        modelResource
                                .getHistogram(LlmExecutionMetricRecorder.LLM_CALL_LATENCY_MS)
                                .getCount())
                .isZero();
    }

    @Test
    void toolFailureAfterCreationWithoutStartCountsFailureWithoutLatency() {
        TraceContext traceContext =
                execution(ExecutionReporter.EntityTypes.TOOL, "search", Map.of());
        observe(TraceRecord.Statuses.CREATED, traceContext, 0);
        observe(TraceRecord.Statuses.FAILED, traceContext, 1000);

        FlinkAgentsMetricGroupImpl tool = actionMetricGroup().getSubGroup("tool", "search");
        assertThat(tool.getCounter(ToolExecutionMetricRecorder.NUM_TOOL_CALLS_FAILED).getCount())
                .isEqualTo(1);
        assertThat(tool.getHistogram(ToolExecutionMetricRecorder.TOOL_CALL_LATENCY_MS).getCount())
                .isZero();
    }

    @Test
    void createdAndReusedRecordsDoNotCountAsComponentOutcomes() {
        TraceContext tool = execution(ExecutionReporter.EntityTypes.TOOL, "search", Map.of());

        observe(TraceRecord.Statuses.CREATED, tool, 0);
        observe(TraceRecord.Statuses.REUSED, tool, 25);

        FlinkAgentsMetricGroupImpl toolMetrics = actionMetricGroup().getSubGroup("tool", "search");
        assertThat(
                        toolMetrics
                                .getCounter(ToolExecutionMetricRecorder.NUM_TOOL_CALLS_SUCCEEDED)
                                .getCount())
                .isZero();
        assertThat(
                        toolMetrics
                                .getCounter(ToolExecutionMetricRecorder.NUM_TOOL_CALLS_FAILED)
                                .getCount())
                .isZero();
        assertThat(
                        toolMetrics
                                .getHistogram(ToolExecutionMetricRecorder.TOOL_CALL_LATENCY_MS)
                                .getCount())
                .isZero();
    }

    @Test
    void invalidRecordTimestampStillCountsOutcomeWithoutLatency() {
        TraceContext tool = execution(ExecutionReporter.EntityTypes.TOOL, "search", Map.of());
        observe(TraceRecord.Statuses.STARTED, tool, 0);

        metrics.executionRecordObserved(
                ACTION_NAME,
                new TraceRecord(tool, "invalid", TraceRecord.Statuses.FAILED, null, Map.of()));

        FlinkAgentsMetricGroupImpl toolMetrics = actionMetricGroup().getSubGroup("tool", "search");
        assertThat(
                        toolMetrics
                                .getCounter(ToolExecutionMetricRecorder.NUM_TOOL_CALLS_FAILED)
                                .getCount())
                .isEqualTo(1L);
        assertThat(
                        toolMetrics
                                .getHistogram(ToolExecutionMetricRecorder.TOOL_CALL_LATENCY_MS)
                                .getCount())
                .isZero();
    }

    private void observe(String status, TraceContext traceContext, long timestampMillis) {
        metrics.executionRecordObserved(
                ACTION_NAME,
                new TraceRecord(
                        traceContext,
                        Instant.EPOCH.plusMillis(timestampMillis).toString(),
                        status,
                        null,
                        Map.of()));
    }

    private FlinkAgentsMetricGroupImpl actionMetricGroup() {
        return metricGroup.getSubGroup("action", ACTION_NAME);
    }

    private static TraceContext execution(
            String entityType, String entityName, Map<String, Object> metadata) {
        TraceContext action =
                TraceContext.forAction(
                        TraceContext.forInputRun("key", "agent"), ACTION_NAME, "trigger-event");
        return action.childExecution(entityType, entityName, metadata);
    }
}
