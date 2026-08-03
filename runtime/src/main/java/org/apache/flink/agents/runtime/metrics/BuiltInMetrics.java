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
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.ExecutionTraceContext;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.Meter;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a group of built-in metrics for monitoring the performance and behavior of a flink
 * agent job. This class is responsible for collecting and managing input-run, event, and action
 * metrics.
 */
public class BuiltInMetrics {

    private final Meter numOfEventProcessedPerSec;

    private final Meter numOfActionsExecutedPerSec;

    private final Counter eventLogTruncatedEvents;

    private final Counter eventLogWriteFailures;

    private final BuiltInInputRunMetrics inputRunMetrics;

    private final BuiltInExecutionMetrics executionMetrics;

    private final Map<String, BuiltInActionMetrics> actionMetricGroups;

    public BuiltInMetrics(FlinkAgentsMetricGroupImpl parentMetricGroup, AgentPlan agentPlan) {
        Counter numOfEventsProcessed = parentMetricGroup.getCounter("numOfEventProcessed");
        this.numOfEventProcessedPerSec =
                parentMetricGroup.getMeter("numOfEventProcessedPerSec", numOfEventsProcessed);

        Counter numOfActionsExecuted = parentMetricGroup.getCounter("numOfActionsExecuted");
        this.numOfActionsExecutedPerSec =
                parentMetricGroup.getMeter("numOfActionsExecutedPerSec", numOfActionsExecuted);

        this.eventLogTruncatedEvents = parentMetricGroup.getCounter("eventLogTruncatedEvents");
        this.eventLogWriteFailures = parentMetricGroup.getCounter("eventLogWriteFailures");
        this.inputRunMetrics = new BuiltInInputRunMetrics(parentMetricGroup, System::nanoTime);
        this.executionMetrics = new BuiltInExecutionMetrics(parentMetricGroup, System::nanoTime);

        this.actionMetricGroups = new HashMap<>();
        for (String actionName : agentPlan.getActions().keySet()) {
            actionMetricGroups.put(
                    actionName,
                    new BuiltInActionMetrics(
                            parentMetricGroup.getSubGroup("action", actionName), System::nanoTime));
        }
    }

    /** Records the occurrence of an event, increasing the count of events processed per second. */
    public void markEventProcessed() {
        numOfEventProcessedPerSec.markEvent();
    }

    /** Marks that an action has finished executing. */
    public void markActionExecuted(String actionName) {
        numOfActionsExecutedPerSec.markEvent();
        actionMetrics(actionName).markActionExecuted();
    }

    public void markInputEventReceived(Event inputEvent) {
        inputRunMetrics.inputEventReceived(inputEvent);
    }

    public void markInputEventFailed(Event inputEvent) {
        inputRunMetrics.inputEventFailed(inputEvent);
    }

    public void markInputRunStarted(Event inputEvent, ExecutionTraceContext traceContext) {
        inputRunMetrics.inputRunStarted(inputEvent, traceContext);
    }

    public void markInputRunCompleted(String inputRunId) {
        inputRunMetrics.inputRunCompleted(inputRunId);
    }

    public void markInputRunFailed(String inputRunId) {
        inputRunMetrics.inputRunFailed(inputRunId);
    }

    public void markPendingInputEventEnqueued() {
        inputRunMetrics.pendingInputEventEnqueued();
    }

    public void markPendingInputEventDequeued() {
        inputRunMetrics.pendingInputEventDequeued();
    }

    public void restorePendingInputEvents(long count) {
        inputRunMetrics.restorePendingInputEvents(count);
    }

    public void restoreActiveInputRuns(long count) {
        inputRunMetrics.restoreActiveInputRuns(count);
    }

    public void markActionTaskEnqueued(
            ExecutionTraceContext traceContext, boolean executionStarted) {
        actionMetrics(traceContext.getEntityName())
                .actionTaskEnqueued(traceContext.getExecutionId(), executionStarted);
    }

    public void markActionTaskDequeued(
            ExecutionTraceContext traceContext, boolean executionStarted) {
        actionMetrics(traceContext.getEntityName())
                .actionTaskDequeued(traceContext.getExecutionId(), executionStarted);
    }

    public void restoreActionTask(ExecutionTraceContext traceContext, boolean executionStarted) {
        inputRunMetrics.identifyRestoredActiveInputRun(traceContext.getInputRunId());
        actionMetrics(traceContext.getEntityName())
                .restoreActionTask(traceContext.getExecutionId(), executionStarted);
    }

    public void markExecutionEvent(
            String actionName, Event event, ExecutionTraceContext traceContext) {
        if (ExecutionReporter.EntityTypes.ACTION.equals(traceContext.getEntityType())) {
            actionMetrics(actionName).executionEventObserved(event, traceContext);
        } else {
            executionMetrics.executionEventObserved(actionName, event, traceContext);
        }
    }

    /** Returns the counter tracking event log truncation occurrences. */
    public Counter getEventLogTruncatedEventsCounter() {
        return eventLogTruncatedEvents;
    }

    /** Returns the counter tracking failed Event Log writes. */
    public Counter getEventLogWriteFailuresCounter() {
        return eventLogWriteFailures;
    }

    private BuiltInActionMetrics actionMetrics(String actionName) {
        BuiltInActionMetrics actionMetrics = actionMetricGroups.get(actionName);
        if (actionMetrics == null) {
            throw new IllegalArgumentException("Unknown action: " + actionName);
        }
        return actionMetrics;
    }
}
