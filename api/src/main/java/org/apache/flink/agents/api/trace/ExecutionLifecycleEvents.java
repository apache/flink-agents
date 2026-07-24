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

package org.apache.flink.agents.api.trace;

import org.apache.flink.agents.api.Event;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Event factory for execution lifecycle reports in the trace model. */
public final class ExecutionLifecycleEvents {

    public static final String EXECUTION_STARTED_EVENT_TYPE = "_execution_started_event";
    public static final String EXECUTION_FINISHED_EVENT_TYPE = "_execution_finished_event";
    public static final String EXECUTION_FAILED_EVENT_TYPE = "_execution_failed_event";
    public static final String EXECUTION_REUSED_EVENT_TYPE = "_execution_reused_event";

    public static final String STATUS_STARTED = "started";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_REUSED = "reused";
    public static final String STATUS_ATTRIBUTE = "status";
    public static final String PROBLEM_CATEGORY_ATTRIBUTE = "problemCategory";

    private ExecutionLifecycleEvents() {}

    public static Event executionStarted() {
        return eventWithStatus(EXECUTION_STARTED_EVENT_TYPE, STATUS_STARTED);
    }

    public static Event executionFinished() {
        return eventWithStatus(EXECUTION_FINISHED_EVENT_TYPE, STATUS_SUCCESS);
    }

    public static Event executionReused() {
        return eventWithStatus(EXECUTION_REUSED_EVENT_TYPE, STATUS_REUSED);
    }

    /** Returns whether the given type identifies an execution lifecycle event. */
    public static boolean isExecutionLifecycleEvent(String eventType) {
        return EXECUTION_STARTED_EVENT_TYPE.equals(eventType)
                || EXECUTION_FINISHED_EVENT_TYPE.equals(eventType)
                || EXECUTION_FAILED_EVENT_TYPE.equals(eventType)
                || EXECUTION_REUSED_EVENT_TYPE.equals(eventType);
    }

    public static Event executionFailed(Throwable error) {
        return executionFailed(error, null);
    }

    public static Event executionFailed(Throwable error, @Nullable String problemCategory) {
        Throwable rootCause = rootCause(error);
        return executionFailed(
                rootCause.getClass().getName(), rootCause.getMessage(), problemCategory);
    }

    public static Event executionFailed(
            String errorType, @Nullable String errorMessage, @Nullable String problemCategory) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(STATUS_ATTRIBUTE, STATUS_FAILED);
        if (problemCategory != null) {
            attributes.put(PROBLEM_CATEGORY_ATTRIBUTE, problemCategory);
        }
        if (errorType != null && !errorType.isEmpty()) {
            attributes.put("errorType", errorType);
        }
        if (errorMessage != null && !errorMessage.isEmpty()) {
            attributes.put("errorMessage", errorMessage);
        }
        return new Event(EXECUTION_FAILED_EVENT_TYPE, attributes);
    }

    private static Event eventWithStatus(String eventType, String status) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(STATUS_ATTRIBUTE, status);
        return new Event(eventType, attributes);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (visited.add(current) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
