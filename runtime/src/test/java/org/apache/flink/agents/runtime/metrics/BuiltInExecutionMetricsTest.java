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

import org.apache.flink.agents.api.trace.ExecutionLifecycleEvents;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.ExecutionTraceContext;
import org.apache.flink.agents.api.trace.ToolExecutionMetadataKeys;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInExecutionMetricsTest {

    private static final String ACTION_NAME = "chat_model_action";

    private final AtomicLong nanoTime = new AtomicLong();
    private FlinkAgentsMetricGroupImpl metricGroup;
    private BuiltInExecutionMetrics metrics;

    @BeforeEach
    void setUp() {
        MetricGroup parentMetricGroup =
                UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup();
        metricGroup = new FlinkAgentsMetricGroupImpl(parentMetricGroup);
        Set<String> registeredTools = Set.of("search", "fetch", "load_skill");
        metrics =
                new BuiltInExecutionMetrics(metricGroup, nanoTime::get, registeredTools::contains);
    }

    @Test
    void recordsLlmOutcomeByModelResource() {
        ExecutionTraceContext success =
                execution(ExecutionReporter.EntityTypes.LLM, "primary_model", Map.of());
        metrics.executionEventObserved(
                ACTION_NAME, ExecutionLifecycleEvents.executionStarted(), success);
        nanoTime.addAndGet(25_000_000L);
        metrics.executionEventObserved(
                ACTION_NAME, ExecutionLifecycleEvents.executionFinished(), success);

        ExecutionTraceContext failure =
                execution(ExecutionReporter.EntityTypes.LLM, "primary_model", Map.of());
        metrics.executionEventObserved(
                ACTION_NAME, ExecutionLifecycleEvents.executionStarted(), failure);
        nanoTime.addAndGet(40_000_000L);
        metrics.executionEventObserved(
                ACTION_NAME,
                ExecutionLifecycleEvents.executionFailed(new RuntimeException("failed")),
                failure);

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
        metrics.executionEventObserved(
                ACTION_NAME, ExecutionLifecycleEvents.executionStarted(), success);
        nanoTime.addAndGet(15_000_000L);
        metrics.executionEventObserved(
                ACTION_NAME, ExecutionLifecycleEvents.executionFinished(), success);

        ExecutionTraceContext failure =
                execution(ExecutionReporter.EntityTypes.TOOL, "search", Map.of());
        metrics.executionEventObserved(
                ACTION_NAME, ExecutionLifecycleEvents.executionStarted(), failure);
        nanoTime.addAndGet(20_000_000L);
        metrics.executionEventObserved(
                ACTION_NAME,
                ExecutionLifecycleEvents.executionFailed(new RuntimeException("failed")),
                failure);

        FlinkAgentsMetricGroupImpl tool = actionMetricGroup().getSubGroup("tool", "search");
        assertThat(tool.getCounter(ToolExecutionMetricRecorder.NUM_TOOL_CALLS_SUCCEEDED).getCount())
                .isEqualTo(1);
        assertThat(tool.getCounter(ToolExecutionMetricRecorder.NUM_TOOL_CALLS_FAILED).getCount())
                .isEqualTo(1);
        assertThat(tool.getHistogram(ToolExecutionMetricRecorder.TOOL_CALL_LATENCY_MS).getCount())
                .isEqualTo(2);
    }

    @Test
    void aggregatesUnregisteredToolNamesIntoUnknownScope() {
        ExecutionTraceContext first =
                execution(ExecutionReporter.EntityTypes.TOOL, "hallucinated_one", Map.of());
        ExecutionTraceContext second =
                execution(ExecutionReporter.EntityTypes.TOOL, "hallucinated_two", Map.of());

        metrics.executionEventObserved(
                ACTION_NAME,
                ExecutionLifecycleEvents.executionFailed(new RuntimeException("missing")),
                first);
        metrics.executionEventObserved(
                ACTION_NAME,
                ExecutionLifecycleEvents.executionFailed(new RuntimeException("missing")),
                second);

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
                        Map.of(ToolExecutionMetadataKeys.SKILL_NAME, "calculator"));
        metrics.executionEventObserved(
                ACTION_NAME, ExecutionLifecycleEvents.executionStarted(), loadSkill);
        nanoTime.addAndGet(12_000_000L);
        metrics.executionEventObserved(
                ACTION_NAME, ExecutionLifecycleEvents.executionFinished(), loadSkill);

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
    void aggregatesMcpToolOutcomesByServer() {
        ExecutionTraceContext success =
                execution(
                        ExecutionReporter.EntityTypes.TOOL,
                        "search",
                        Map.of(ToolExecutionMetadataKeys.MCP_SERVER, "search_server"));
        metrics.executionEventObserved(
                ACTION_NAME, ExecutionLifecycleEvents.executionStarted(), success);
        nanoTime.addAndGet(30_000_000L);
        metrics.executionEventObserved(
                ACTION_NAME, ExecutionLifecycleEvents.executionFinished(), success);

        ExecutionTraceContext failure =
                execution(
                        ExecutionReporter.EntityTypes.TOOL,
                        "fetch",
                        Map.of(ToolExecutionMetadataKeys.MCP_SERVER, "search_server"));
        metrics.executionEventObserved(
                ACTION_NAME, ExecutionLifecycleEvents.executionStarted(), failure);
        nanoTime.addAndGet(50_000_000L);
        metrics.executionEventObserved(
                ACTION_NAME,
                ExecutionLifecycleEvents.executionFailed(new RuntimeException("failed")),
                failure);

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
        metrics.executionEventObserved(
                ACTION_NAME, ExecutionLifecycleEvents.executionFinished(), llm);

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
