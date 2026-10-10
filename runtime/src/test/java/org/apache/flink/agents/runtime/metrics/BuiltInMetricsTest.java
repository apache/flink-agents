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
import org.apache.flink.agents.api.trace.TraceContext;
import org.apache.flink.agents.api.trace.TraceRecord;
import org.apache.flink.agents.api.trace.TraceRecords;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInMetricsTest {

    @Test
    void eventRecordWithExecutionLikeAttributesDoesNotCountAsExecution() {
        FlinkAgentsMetricGroupImpl metricGroup =
                new FlinkAgentsMetricGroupImpl(
                        UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup());
        BuiltInMetrics metrics =
                new BuiltInMetrics(metricGroup, new AgentPlan(Map.of()), ignored -> true);
        TraceContext event =
                TraceContext.forEvent(
                        TraceContext.forInputRun("key", "agent"), "search", "event-id", null, null);

        metrics.markExecutionRecord(
                "action",
                TraceRecord.create(
                        event,
                        null,
                        null,
                        Map.of("status", TraceRecord.Statuses.SUCCESS, "entityType", "tool")));

        assertThat(
                        metricGroup
                                .getSubGroup("action", "action")
                                .getSubGroup("tool", "search")
                                .getCounter(ToolExecutionMetricRecorder.NUM_TOOL_CALLS_SUCCEEDED)
                                .getCount())
                .isZero();
    }

    @Test
    void restoredActionMissingFromCurrentPlanKeepsItsMetricLifecycle() {
        MetricGroup parentMetricGroup =
                UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup();
        FlinkAgentsMetricGroupImpl metricGroup = new FlinkAgentsMetricGroupImpl(parentMetricGroup);
        BuiltInMetrics metrics =
                new BuiltInMetrics(metricGroup, new AgentPlan(Map.of()), ignored -> false);
        TraceContext restoredAction =
                TraceContext.forAction(
                        TraceContext.forInputRun("key", "agent"),
                        "restored_action",
                        "trigger-event");

        metrics.restoreActionTask(restoredAction, true);
        metrics.markActionTaskDequeued(restoredAction, true);
        metrics.markExecutionRecord("restored_action", TraceRecords.reused(restoredAction));
        metrics.markActionExecuted("restored_action");

        FlinkAgentsMetricGroupImpl actionMetricGroup =
                metricGroup.getSubGroup("action", "restored_action");
        assertThat(
                        actionMetricGroup
                                .getGauge(BuiltInActionMetrics.NUM_PENDING_ACTION_TASKS)
                                .getValue())
                .isEqualTo(0L);
        assertThat(
                        actionMetricGroup
                                .getGauge(BuiltInActionMetrics.NUM_ACTIVE_ACTION_EXECUTIONS)
                                .getValue())
                .isEqualTo(0L);
        assertThat(actionMetricGroup.getCounter("numOfActionsExecuted").getCount()).isEqualTo(1L);
    }

    @Test
    void actionTerminalDropsOnlyItsUnpairedChildLatencyState() {
        MetricGroup parentMetricGroup =
                UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup();
        FlinkAgentsMetricGroupImpl metricGroup = new FlinkAgentsMetricGroupImpl(parentMetricGroup);
        BuiltInMetrics metrics =
                new BuiltInMetrics(metricGroup, new AgentPlan(Map.of()), ignored -> false);
        TraceContext inputRun = TraceContext.forInputRun("key", "agent");
        TraceContext completedAction =
                TraceContext.forAction(inputRun, "restored_action", "trigger-event");
        TraceContext activeAction =
                TraceContext.forAction(inputRun, "restored_action", "trigger-event");
        TraceContext completedActionLlm =
                completedAction.childExecution(
                        ExecutionReporter.EntityTypes.LLM, "primary_model", Map.of());
        TraceContext activeActionLlm =
                activeAction.childExecution(
                        ExecutionReporter.EntityTypes.LLM, "secondary_model", Map.of());
        metrics.restoreActionTask(completedAction, false);

        metrics.markExecutionRecord("restored_action", TraceRecords.started(completedActionLlm));
        metrics.markExecutionRecord("restored_action", TraceRecords.started(activeActionLlm));
        metrics.markExecutionRecord("restored_action", TraceRecords.succeeded(completedAction));
        metrics.markExecutionRecord("restored_action", TraceRecords.succeeded(completedActionLlm));
        metrics.markExecutionRecord("restored_action", TraceRecords.succeeded(activeActionLlm));

        assertThat(
                        metricGroup
                                .getSubGroup("action", "restored_action")
                                .getSubGroup("model_resource", "primary_model")
                                .getHistogram(LlmExecutionMetricRecorder.LLM_CALL_LATENCY_MS)
                                .getCount())
                .isZero();
        assertThat(
                        metricGroup
                                .getSubGroup("action", "restored_action")
                                .getSubGroup("model_resource", "secondary_model")
                                .getHistogram(LlmExecutionMetricRecorder.LLM_CALL_LATENCY_MS)
                                .getCount())
                .isEqualTo(1L);
    }
}
