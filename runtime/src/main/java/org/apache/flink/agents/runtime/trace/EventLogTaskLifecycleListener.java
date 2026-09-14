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

import org.apache.flink.agents.api.trace.ExecutionLifecycleEvents;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.runtime.lifecycle.TaskLifecycleListener;
import org.apache.flink.agents.runtime.operator.ActionTask;
import org.apache.flink.annotation.Internal;

/**
 * Bridges the operator's action lifecycle callbacks onto the event log, emitting the execution
 * lifecycle events independently from the business-event router.
 */
@Internal
public final class EventLogTaskLifecycleListener implements TaskLifecycleListener {

    private final ExecutionEventSink executionEventSink;

    public EventLogTaskLifecycleListener(ExecutionEventSink executionEventSink) {
        this.executionEventSink = executionEventSink;
    }

    @Override
    public void onActionStarted(ActionTask task) {
        executionEventSink.emit(
                ExecutionLifecycleEvents.executionStarted(), task.getTraceContext());
    }

    @Override
    public void onActionReused(ActionTask task) {
        executionEventSink.emit(ExecutionLifecycleEvents.executionReused(), task.getTraceContext());
    }

    @Override
    public void onActionFinished(ActionTask task) {
        executionEventSink.emit(
                ExecutionLifecycleEvents.executionFinished(), task.getTraceContext());
    }

    @Override
    public void onActionFailed(ActionTask task, Throwable error) {
        executionEventSink.emit(
                ExecutionLifecycleEvents.executionFailed(
                        error, ExecutionReporter.ProblemCategories.ACTION_EXECUTION_FAILED),
                task.getTraceContext());
    }
}
