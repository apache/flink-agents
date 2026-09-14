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

package org.apache.flink.agents.plan.resource.python;

import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.annotation.Internal;

import java.util.Map;

/** Converts the internal Python bridge representation into a Java {@link ToolResponse}. */
@Internal
public final class PythonToolResultConverter {

    private static final String RESULT_MARKER = "__flink_agents_tool_result__";

    public static ToolResponse fromBridgeResult(Object result) {
        if (!(result instanceof Map)) {
            return ToolResponse.success(result);
        }

        Map<?, ?> response = (Map<?, ?>) result;
        Object resultKind = response.get(RESULT_MARKER);
        if ("raw".equals(resultKind)) {
            return ToolResponse.success(response.get("result"));
        }
        if (!"response".equals(resultKind)) {
            return ToolResponse.success(result);
        }

        long executionTimeMs = numberValue(response.get("execution_time_ms"));
        String toolName = stringValue(response.get("tool_name"));
        if (Boolean.TRUE.equals(response.get("success"))) {
            return ToolResponse.success(response.get("result"), executionTimeMs, toolName);
        }
        return ToolResponse.error(stringValue(response.get("error")), executionTimeMs, toolName);
    }

    private static long numberValue(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private PythonToolResultConverter() {}
}
