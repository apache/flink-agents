/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.agents.runtime.trace;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.trace.ExecutionLifecycleEvents;
import org.apache.flink.agents.api.trace.ExecutionTraceContext;
import org.apache.flink.agents.runtime.lifecycle.ComponentExecutionListener;
import org.apache.flink.annotation.Internal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-action-execution adapter that turns component execution reports into event log records under
 * the action's trace context. Its bookkeeping never leaks across actions because each execution
 * gets its own instance, and the start/terminal pairing survives continuation task transfers
 * because the adapter is tied to the action execution rather than the individual task.
 */
@Internal
public final class EventLogComponentExecutionListener implements ComponentExecutionListener {

    private static final Logger LOG =
            LoggerFactory.getLogger(EventLogComponentExecutionListener.class);

    private final ExecutionTraceContext actionTraceContext;
    private final ExecutionEventSink executionEventSink;
    private final Map<ReportedExecutionKey, ExecutionTraceContext> activeReportedExecutions =
            new HashMap<>();

    public EventLogComponentExecutionListener(
            ExecutionTraceContext actionTraceContext, ExecutionEventSink executionEventSink) {
        this.actionTraceContext = actionTraceContext;
        this.executionEventSink = executionEventSink;
    }

    @Override
    public void onComponentExecution(
            String entityType, String entityName, Map<String, Object> entityMetadata, Event event) {
        ReportedExecutionKey key = new ReportedExecutionKey(entityType, entityName, entityMetadata);
        ExecutionTraceContext reportTraceContext;
        if (ExecutionLifecycleEvents.EXECUTION_STARTED_EVENT_TYPE.equals(event.getType())) {
            reportTraceContext =
                    actionTraceContext.childExecution(
                            entityType, entityName, key.getEntityMetadata());
            ExecutionTraceContext previous = activeReportedExecutions.put(key, reportTraceContext);
            if (previous != null) {
                LOG.debug(
                        "Execution start report for {}:{} replaced an active report with the same metadata.",
                        entityType,
                        entityName);
            }
        } else {
            reportTraceContext = activeReportedExecutions.remove(key);
            if (reportTraceContext == null) {
                LOG.debug(
                        "Execution terminal report for {}:{} has no matching start report; emitting it with a new execution id.",
                        entityType,
                        entityName);
                reportTraceContext =
                        actionTraceContext.childExecution(
                                entityType, entityName, key.getEntityMetadata());
            }
        }

        executionEventSink.emit(event, reportTraceContext);
    }
}
