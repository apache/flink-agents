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
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInActionMetricsTest {

    private final AtomicLong nanoTime = new AtomicLong();
    private FlinkAgentsMetricGroupImpl metricGroup;
    private BuiltInActionMetrics metrics;

    @BeforeEach
    void setUp() {
        metricGroup =
                new FlinkAgentsMetricGroupImpl(
                        UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup());
        metrics = new BuiltInActionMetrics(metricGroup, nanoTime::get);
    }

    @Test
    void initialTaskRecordsSchedulingLatencyAndPendingCount() {
        metrics.actionTaskEnqueued("execution", false);
        assertThat(gauge(BuiltInActionMetrics.NUM_PENDING_ACTION_TASKS)).isEqualTo(1L);

        nanoTime.set(25_000_000L);
        metrics.actionTaskDequeued("execution", false);

        assertThat(gauge(BuiltInActionMetrics.NUM_PENDING_ACTION_TASKS)).isZero();
        assertThat(
                        metricGroup
                                .getHistogram(BuiltInActionMetrics.ACTION_SCHEDULING_LATENCY_MS)
                                .getStatistics()
                                .getMax())
                .isEqualTo(25L);
    }

    @Test
    void actionLifecycleRecordsExecutionLatency() {
        ExecutionTraceContext action = actionExecution();

        metrics.executionEventObserved(ExecutionLifecycleEvents.executionStarted(), action);
        nanoTime.set(35_000_000L);
        metrics.executionEventObserved(ExecutionLifecycleEvents.executionFinished(), action);

        assertThat(
                        metricGroup
                                .getHistogram(BuiltInActionMetrics.ACTION_EXECUTION_LATENCY_MS)
                                .getStatistics()
                                .getMax())
                .isEqualTo(35L);
        assertThat(gauge(BuiltInActionMetrics.NUM_ACTIVE_ACTION_EXECUTIONS)).isZero();
    }

    @Test
    void continuationCanBePendingWhileLogicalExecutionIsActive() {
        ExecutionTraceContext action = actionExecution();
        String executionId = action.getExecutionId();

        metrics.executionEventObserved(ExecutionLifecycleEvents.executionStarted(), action);
        metrics.actionTaskEnqueued(executionId, true);

        assertThat(gauge(BuiltInActionMetrics.NUM_ACTIVE_ACTION_EXECUTIONS)).isEqualTo(1L);
        assertThat(gauge(BuiltInActionMetrics.NUM_PENDING_ACTION_TASKS)).isEqualTo(1L);

        metrics.actionTaskDequeued(executionId, true);
        metrics.executionEventObserved(ExecutionLifecycleEvents.executionFinished(), action);

        assertThat(gauge(BuiltInActionMetrics.NUM_ACTIVE_ACTION_EXECUTIONS)).isZero();
        assertThat(gauge(BuiltInActionMetrics.NUM_PENDING_ACTION_TASKS)).isZero();
        assertThat(
                        metricGroup
                                .getHistogram(BuiltInActionMetrics.ACTION_SCHEDULING_LATENCY_MS)
                                .getCount())
                .isZero();
    }

    @Test
    void restoredContinuationRebuildsPendingAndActiveGauges() {
        ExecutionTraceContext action = actionExecution();

        metrics.restoreActionTask(action.getExecutionId(), true);
        assertThat(gauge(BuiltInActionMetrics.NUM_PENDING_ACTION_TASKS)).isEqualTo(1L);
        assertThat(gauge(BuiltInActionMetrics.NUM_ACTIVE_ACTION_EXECUTIONS)).isEqualTo(1L);

        metrics.actionTaskDequeued(action.getExecutionId(), true);
        metrics.executionEventObserved(ExecutionLifecycleEvents.executionFinished(), action);

        assertThat(gauge(BuiltInActionMetrics.NUM_PENDING_ACTION_TASKS)).isZero();
        assertThat(gauge(BuiltInActionMetrics.NUM_ACTIVE_ACTION_EXECUTIONS)).isZero();
        assertThat(
                        metricGroup
                                .getHistogram(BuiltInActionMetrics.ACTION_EXECUTION_LATENCY_MS)
                                .getCount())
                .isZero();
    }

    private long gauge(String name) {
        return (Long) metricGroup.getGauge(name).getValue();
    }

    private static ExecutionTraceContext actionExecution() {
        return ExecutionTraceContext.forAction(
                ExecutionTraceContext.forInputRun("key", "agent"), "action");
    }
}
