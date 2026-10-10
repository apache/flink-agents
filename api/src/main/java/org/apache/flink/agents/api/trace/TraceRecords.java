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

import org.apache.flink.annotation.Internal;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Creates {@link TraceRecord} instances and formats their error details. */
@Internal
public final class TraceRecords {
    private TraceRecords() {}

    public static TraceRecord created(TraceContext context) {
        return TraceRecord.create(context, TraceRecord.Statuses.CREATED, null, null);
    }

    public static TraceRecord started(TraceContext context) {
        return TraceRecord.create(context, TraceRecord.Statuses.STARTED, null, null);
    }

    public static TraceRecord succeeded(TraceContext context) {
        return TraceRecord.create(context, TraceRecord.Statuses.SUCCESS, null, null);
    }

    public static TraceRecord reused(TraceContext context) {
        return TraceRecord.create(context, TraceRecord.Statuses.REUSED, null, null);
    }

    public static TraceRecord failed(
            TraceContext context, Throwable error, @Nullable String problemCategory) {
        return TraceRecord.create(
                context, TraceRecord.Statuses.FAILED, problemCategory, errorAttributes(error));
    }

    public static TraceRecord failed(
            TraceContext context,
            @Nullable String errorType,
            @Nullable String errorMessage,
            @Nullable String problemCategory) {
        return TraceRecord.create(
                context,
                TraceRecord.Statuses.FAILED,
                problemCategory,
                errorAttributes(errorType, errorMessage));
    }

    /** Returns error details for the root cause; cyclic cause chains terminate safely. */
    public static Map<String, Object> errorAttributes(Throwable error) {
        Throwable current = error;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (visited.add(current) && current.getCause() != null) {
            current = current.getCause();
        }
        return errorAttributes(current.getClass().getName(), current.getMessage());
    }

    /** Returns portable error details reported by either language. */
    public static Map<String, Object> errorAttributes(
            @Nullable String errorType, @Nullable String errorMessage) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (errorType != null && !errorType.isEmpty()) {
            attributes.put("errorType", errorType);
        }
        if (errorMessage != null && !errorMessage.isEmpty()) {
            attributes.put("errorMessage", errorMessage);
        }
        return attributes;
    }
}
