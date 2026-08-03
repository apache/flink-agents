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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Derives built-in LLM and Tool metrics from execution lifecycle events. */
final class BuiltInExecutionMetrics {

    private final FlinkAgentsMetricGroupImpl agentMetricGroup;
    private final LongSupplier nanoTime;
    private final Map<String, ExecutionMetricRecorder> metricRecordersByEntityType;
    private final Map<String, Long> activeExecutionStartNanos = new HashMap<>();

    BuiltInExecutionMetrics(FlinkAgentsMetricGroupImpl agentMetricGroup, LongSupplier nanoTime) {
        this.agentMetricGroup = agentMetricGroup;
        this.nanoTime = nanoTime;
        ExecutionMetricRecorder llmMetricRecorder = new LlmExecutionMetricRecorder();
        ExecutionMetricRecorder toolMetricRecorder = new ToolExecutionMetricRecorder();
        this.metricRecordersByEntityType =
                Map.of(
                        llmMetricRecorder.entityType(),
                        llmMetricRecorder,
                        toolMetricRecorder.entityType(),
                        toolMetricRecorder);
    }

    void executionEventObserved(
            String actionName, Event event, ExecutionTraceContext traceContext) {
        ExecutionMetricRecorder recorder =
                metricRecordersByEntityType.get(traceContext.getEntityType());
        if (isBlank(actionName) || recorder == null) {
            return;
        }

        String executionId = traceContext.getExecutionId();
        if (ExecutionLifecycleEvents.EXECUTION_STARTED_EVENT_TYPE.equals(event.getType())) {
            if (!isBlank(executionId)) {
                activeExecutionStartNanos.putIfAbsent(executionId, nanoTime.getAsLong());
            }
            return;
        }

        boolean succeeded =
                ExecutionLifecycleEvents.EXECUTION_FINISHED_EVENT_TYPE.equals(event.getType());
        boolean failed =
                ExecutionLifecycleEvents.EXECUTION_FAILED_EVENT_TYPE.equals(event.getType());
        if (!succeeded && !failed) {
            return;
        }

        Long startNanos =
                isBlank(executionId) ? null : activeExecutionStartNanos.remove(executionId);
        Long latencyMs =
                startNanos == null
                        ? null
                        : TimeUnit.NANOSECONDS.toMillis(
                                Math.max(0L, nanoTime.getAsLong() - startNanos));

        FlinkAgentsMetricGroupImpl actionMetricGroup =
                agentMetricGroup.getSubGroup("action", actionName);
        ExecutionMetricRecorder.Outcome outcome =
                succeeded
                        ? ExecutionMetricRecorder.Outcome.SUCCEEDED
                        : ExecutionMetricRecorder.Outcome.FAILED;
        recorder.record(actionMetricGroup, traceContext, outcome, latencyMs);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
