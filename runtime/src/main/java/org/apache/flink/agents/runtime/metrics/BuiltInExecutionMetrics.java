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

import org.apache.flink.agents.api.trace.TraceContext;
import org.apache.flink.agents.api.trace.TraceRecord;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Derives built-in LLM, tool, and sub-agent metrics from {@link TraceRecord} observations. */
final class BuiltInExecutionMetrics {

    private final FlinkAgentsMetricGroupImpl agentMetricGroup;
    private final Map<String, ExecutionMetricRecorder> metricRecordersByEntityType;
    private final Map<String, Map<String, Instant>> startTimesByActionExecutionId = new HashMap<>();

    BuiltInExecutionMetrics(
            FlinkAgentsMetricGroupImpl agentMetricGroup, Predicate<String> isRegisteredTool) {
        this.agentMetricGroup = agentMetricGroup;
        ExecutionMetricRecorder llmMetricRecorder = new LlmExecutionMetricRecorder();
        ExecutionMetricRecorder toolMetricRecorder =
                new ToolExecutionMetricRecorder(isRegisteredTool);
        ExecutionMetricRecorder subagentMetricRecorder = new SubagentExecutionMetricRecorder();
        this.metricRecordersByEntityType =
                Map.of(
                        llmMetricRecorder.entityType(),
                        llmMetricRecorder,
                        toolMetricRecorder.entityType(),
                        toolMetricRecorder,
                        subagentMetricRecorder.entityType(),
                        subagentMetricRecorder);
    }

    void executionRecordObserved(String actionName, TraceRecord record) {
        TraceContext traceContext = record.getContext();
        ExecutionMetricRecorder recorder =
                metricRecordersByEntityType.get(traceContext.getEntityType());
        if (isBlank(actionName) || recorder == null) {
            return;
        }

        String executionId = traceContext.getExecutionId();
        String actionExecutionId = traceContext.getParentExecutionId();
        if (TraceRecord.Statuses.STARTED.equals(record.getStatus())) {
            Instant startTime = parseTimestamp(record.getTimestamp());
            if (startTime != null && !isBlank(actionExecutionId) && !isBlank(executionId)) {
                startTimesByActionExecutionId
                        .computeIfAbsent(actionExecutionId, ignored -> new HashMap<>())
                        .putIfAbsent(executionId, startTime);
            }
            return;
        }

        boolean succeeded = TraceRecord.Statuses.SUCCESS.equals(record.getStatus());
        boolean failed = TraceRecord.Statuses.FAILED.equals(record.getStatus());
        if (!succeeded && !failed) {
            return;
        }

        Instant startTime = removeExecutionStart(actionExecutionId, executionId);
        Instant terminalTime = parseTimestamp(record.getTimestamp());
        Long latencyMs = latencyBetween(startTime, terminalTime);

        FlinkAgentsMetricGroupImpl actionMetricGroup =
                agentMetricGroup.getSubGroup("action", actionName);
        ExecutionMetricRecorder.Outcome outcome =
                succeeded
                        ? ExecutionMetricRecorder.Outcome.SUCCEEDED
                        : ExecutionMetricRecorder.Outcome.FAILED;
        recorder.record(actionMetricGroup, traceContext, outcome, latencyMs);
    }

    void actionExecutionTerminated(String actionExecutionId) {
        if (!isBlank(actionExecutionId)) {
            startTimesByActionExecutionId.remove(actionExecutionId);
        }
    }

    private Instant removeExecutionStart(String actionExecutionId, String executionId) {
        if (isBlank(actionExecutionId) || isBlank(executionId)) {
            return null;
        }

        Map<String, Instant> actionExecutionStarts =
                startTimesByActionExecutionId.get(actionExecutionId);
        if (actionExecutionStarts == null) {
            return null;
        }

        Instant startTime = actionExecutionStarts.remove(executionId);
        if (actionExecutionStarts.isEmpty()) {
            startTimesByActionExecutionId.remove(actionExecutionId);
        }
        return startTime;
    }

    private static Instant parseTimestamp(String timestamp) {
        try {
            return Instant.parse(timestamp);
        } catch (DateTimeParseException | NullPointerException ignored) {
            return null;
        }
    }

    private static Long latencyBetween(Instant startTime, Instant terminalTime) {
        if (startTime == null || terminalTime == null) {
            return null;
        }
        return Math.max(0L, Duration.between(startTime, terminalTime).toMillis());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
