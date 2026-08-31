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
import org.apache.flink.agents.api.trace.ExecutionTraceContext;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInMetricsTest {

    @Test
    void restoredActionMissingFromCurrentPlanKeepsItsMetricLifecycle() {
        MetricGroup parentMetricGroup =
                UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup();
        FlinkAgentsMetricGroupImpl metricGroup = new FlinkAgentsMetricGroupImpl(parentMetricGroup);
        BuiltInMetrics metrics = new BuiltInMetrics(metricGroup, new AgentPlan(Map.of()));
        ExecutionTraceContext restoredAction =
                ExecutionTraceContext.forAction(
                        ExecutionTraceContext.forInputRun("key", "agent"), "restored_action");

        metrics.restoreActionTask(restoredAction, true);
        metrics.markActionTaskDequeued(restoredAction, true);
        metrics.markExecutionEvent(
                "restored_action", ExecutionLifecycleEvents.executionReused(), restoredAction);
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
}
