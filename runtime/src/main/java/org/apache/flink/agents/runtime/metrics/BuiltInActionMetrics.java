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
import org.apache.flink.agents.api.trace.ExecutionLifecycleEvents;
import org.apache.flink.agents.api.trace.ExecutionTraceContext;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.Histogram;
import org.apache.flink.metrics.Meter;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Tracks execution rate, scheduling latency, and current task/execution counts for one Action. */
public class BuiltInActionMetrics {

    static final String ACTION_SCHEDULING_LATENCY_MS = "actionSchedulingLatencyMs";
    static final String ACTION_EXECUTION_LATENCY_MS = "actionExecutionLatencyMs";
    static final String NUM_PENDING_ACTION_TASKS = "numOfPendingActionTasks";
    static final String NUM_ACTIVE_ACTION_EXECUTIONS = "numOfActiveActionExecutions";

    private final Meter numOfActionsExecutedPerSec;
    private final Histogram schedulingLatencyHistogram;
    private final Histogram executionLatencyHistogram;
    private final CurrentCountGauge pendingActionTasks;
    private final CurrentCountGauge activeActionExecutions;
    private final LongSupplier nanoTime;

    private final Map<String, Long> initialTaskEnqueueNanos = new HashMap<>();
    private final Map<String, OptionalLong> activeExecutions = new HashMap<>();

    public BuiltInActionMetrics(FlinkAgentsMetricGroupImpl parentMetricGroup) {
        this(parentMetricGroup, System::nanoTime);
    }

    BuiltInActionMetrics(FlinkAgentsMetricGroupImpl parentMetricGroup, LongSupplier nanoTime) {
        Counter numOfActionsExecuted = parentMetricGroup.getCounter("numOfActionsExecuted");
        this.numOfActionsExecutedPerSec =
                parentMetricGroup.getMeter("numOfActionsExecutedPerSec", numOfActionsExecuted);
        this.schedulingLatencyHistogram =
                parentMetricGroup.getHistogram(ACTION_SCHEDULING_LATENCY_MS);
        this.executionLatencyHistogram =
                parentMetricGroup.getHistogram(ACTION_EXECUTION_LATENCY_MS);
        this.pendingActionTasks =
                new CurrentCountGauge(parentMetricGroup, NUM_PENDING_ACTION_TASKS);
        this.activeActionExecutions =
                new CurrentCountGauge(parentMetricGroup, NUM_ACTIVE_ACTION_EXECUTIONS);
        this.nanoTime = nanoTime;
    }

    /** Marks that an action has finished executing. */
    public void markActionExecuted() {
        numOfActionsExecutedPerSec.markEvent();
    }

    void actionTaskEnqueued(String executionId, boolean executionStarted) {
        pendingActionTasks.increment();
        if (!executionStarted && !isBlank(executionId)) {
            initialTaskEnqueueNanos.putIfAbsent(executionId, nanoTime.getAsLong());
        }
    }

    void actionTaskDequeued(String executionId, boolean executionStarted) {
        pendingActionTasks.decrement();
        if (executionStarted || isBlank(executionId)) {
            return;
        }

        Long enqueueNanos = initialTaskEnqueueNanos.remove(executionId);
        if (enqueueNanos != null) {
            schedulingLatencyHistogram.update(
                    TimeUnit.NANOSECONDS.toMillis(
                            Math.max(0L, nanoTime.getAsLong() - enqueueNanos)));
        }
    }

    void restoreActionTask(String executionId, boolean executionStarted) {
        pendingActionTasks.increment();
        if (executionStarted
                && !isBlank(executionId)
                && activeExecutions.putIfAbsent(executionId, OptionalLong.empty()) == null) {
            activeActionExecutions.increment();
        }
    }

    void executionEventObserved(Event event, ExecutionTraceContext traceContext) {
        String executionId = traceContext.getExecutionId();
        if (isBlank(executionId)) {
            return;
        }

        if (ExecutionLifecycleEvents.EXECUTION_STARTED_EVENT_TYPE.equals(event.getType())) {
            if (activeExecutions.putIfAbsent(executionId, OptionalLong.of(nanoTime.getAsLong()))
                    == null) {
                activeActionExecutions.increment();
            }
            return;
        }

        if (!ExecutionLifecycleEvents.EXECUTION_FINISHED_EVENT_TYPE.equals(event.getType())
                && !ExecutionLifecycleEvents.EXECUTION_FAILED_EVENT_TYPE.equals(event.getType())
                && !ExecutionLifecycleEvents.EXECUTION_REUSED_EVENT_TYPE.equals(event.getType())) {
            return;
        }

        OptionalLong startNanos = activeExecutions.remove(executionId);
        if (startNanos == null) {
            return;
        }
        activeActionExecutions.decrement();
        if (startNanos.isPresent()) {
            executionLatencyHistogram.update(
                    TimeUnit.NANOSECONDS.toMillis(
                            Math.max(0L, nanoTime.getAsLong() - startNanos.getAsLong())));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
