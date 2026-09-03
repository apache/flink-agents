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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.api.trace.ExecutionLifecycleEvents;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.ExecutionTraceContext;
import org.apache.flink.agents.api.trace.ToolExecutionMetadataKeys;
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
        ExecutionTraceContext success =
                execution(ExecutionReporter.EntityTypes.LLM, "primary_model", Map.of());
        observe(ExecutionLifecycleEvents.executionStarted(), success, 0);
        observe(ExecutionLifecycleEvents.executionFinished(), success, 25);

        ExecutionTraceContext failure =
                execution(ExecutionReporter.EntityTypes.LLM, "primary_model", Map.of());
        observe(ExecutionLifecycleEvents.executionStarted(), failure, 100);
        observe(
                ExecutionLifecycleEvents.executionFailed(new RuntimeException("failed")),
                failure,
                140);

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
        ExecutionTraceContext success =
                execution(ExecutionReporter.EntityTypes.TOOL, "search", Map.of());
        observe(ExecutionLifecycleEvents.executionStarted(), success, 0);
        observe(ExecutionLifecycleEvents.executionFinished(), success, 15);

        ExecutionTraceContext failure =
                execution(ExecutionReporter.EntityTypes.TOOL, "search", Map.of());
        observe(ExecutionLifecycleEvents.executionStarted(), failure, 5);
        observe(
                ExecutionLifecycleEvents.executionFailed(new RuntimeException("failed")),
                failure,
                25);

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
    void aggregatesUnregisteredToolNamesIntoUnknownScope() {
        ExecutionTraceContext first =
                execution(ExecutionReporter.EntityTypes.TOOL, "hallucinated_one", Map.of());
        ExecutionTraceContext second =
                execution(ExecutionReporter.EntityTypes.TOOL, "hallucinated_two", Map.of());

        observe(
                ExecutionLifecycleEvents.executionFailed(new RuntimeException("missing")),
                first,
                0);
        observe(
                ExecutionLifecycleEvents.executionFailed(new RuntimeException("missing")),
                second,
                1);

        FlinkAgentsMetricGroupImpl unknown =
                actionMetricGroup()
                        .getSubGroup("tool", ToolExecutionMetricRecorder.UNKNOWN_TOOL_NAME);
        assertThat(unknown.getCounter(ToolExecutionMetricRecorder.NUM_TOOL_CALLS_FAILED).getCount())
                .isEqualTo(2);
    }

    @Test
    void recordsExplicitSkillLoads() {
        ExecutionTraceContext loadSkill =
                execution(
                        ExecutionReporter.EntityTypes.TOOL,
                        "load_skill",
                        Map.of(
                                ToolExecutionMetadataKeys.SKILL_NAME,
                                "calculator",
                                ToolExecutionMetadataKeys.SKILL_REGISTERED,
                                true));
        observe(ExecutionLifecycleEvents.executionStarted(), loadSkill, 0);
        observe(ExecutionLifecycleEvents.executionFinished(), loadSkill, 12);

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
        ExecutionTraceContext first =
                execution(
                        ExecutionReporter.EntityTypes.TOOL,
                        "load_skill",
                        Map.of(
                                ToolExecutionMetadataKeys.SKILL_NAME,
                                "hallucinated_one",
                                ToolExecutionMetadataKeys.SKILL_REGISTERED,
                                false));
        ExecutionTraceContext second =
                execution(
                        ExecutionReporter.EntityTypes.TOOL,
                        "load_skill",
                        Map.of(
                                ToolExecutionMetadataKeys.SKILL_NAME,
                                "hallucinated_two",
                                ToolExecutionMetadataKeys.SKILL_REGISTERED,
                                false));

        observe(ExecutionLifecycleEvents.executionFinished(), first, 0);
        observe(ExecutionLifecycleEvents.executionFinished(), second, 1);

        FlinkAgentsMetricGroupImpl unknown =
                actionMetricGroup()
                        .getSubGroup("skill", ToolExecutionMetricRecorder.UNKNOWN_SKILL_NAME);
        assertThat(unknown.getCounter(ToolExecutionMetricRecorder.NUM_SKILL_LOADS).getCount())
                .isEqualTo(2);
    }

    @Test
    void aggregatesMcpToolOutcomesByServer() {
        ExecutionTraceContext success =
                execution(
                        ExecutionReporter.EntityTypes.TOOL,
                        "search",
                        Map.of(ToolExecutionMetadataKeys.MCP_SERVER, "search_server"));
        observe(ExecutionLifecycleEvents.executionStarted(), success, 0);
        observe(ExecutionLifecycleEvents.executionFinished(), success, 30);

        ExecutionTraceContext failure =
                execution(
                        ExecutionReporter.EntityTypes.TOOL,
                        "fetch",
                        Map.of(ToolExecutionMetadataKeys.MCP_SERVER, "search_server"));
        observe(ExecutionLifecycleEvents.executionStarted(), failure, 10);
        observe(
                ExecutionLifecycleEvents.executionFailed(new RuntimeException("failed")),
                failure,
                60);

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
    void terminalEventWithoutLocalStartDoesNotRecordLatency() {
        ExecutionTraceContext llm =
                execution(ExecutionReporter.EntityTypes.LLM, "restored_model", Map.of());
        observe(ExecutionLifecycleEvents.executionFinished(), llm, 0);

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

    private void observe(Event event, ExecutionTraceContext traceContext, long timestampMillis) {
        metrics.executionEventObserved(
                ACTION_NAME,
                new EventContext(
                        event.getType(), Instant.EPOCH.plusMillis(timestampMillis).toString()),
                event,
                traceContext);
    }

    private FlinkAgentsMetricGroupImpl actionMetricGroup() {
        return metricGroup.getSubGroup("action", ACTION_NAME);
    }

    private static ExecutionTraceContext execution(
            String entityType, String entityName, Map<String, Object> metadata) {
        ExecutionTraceContext action =
                ExecutionTraceContext.forAction(
                        ExecutionTraceContext.forInputRun("key", "agent"), ACTION_NAME);
        return action.childExecution(entityType, entityName, metadata);
    }
}
