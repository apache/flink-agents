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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.plan.utils.ToolResultUtils;
import org.apache.flink.annotation.Internal;

import java.util.HashMap;
import java.util.Map;

/** Converts the internal Python bridge representation into a Java {@link ToolResponse}. */
@Internal
public final class PythonToolResultConverter {

    private static final String RESULT_MARKER = "__flink_agents_tool_result__";

    @SuppressWarnings("unchecked")
    public static ToolResponse fromBridgeResult(Object result) {
        if (!(result instanceof Map)) {
            return ToolResultUtils.toToolResponse(result);
        }

        Map<?, ?> response = (Map<?, ?>) result;
        Object resultKind = response.get(RESULT_MARKER);
        if ("raw".equals(resultKind)) {
            return ToolResultUtils.toToolResponse(response.get("result"));
        }
        if (!"response".equals(resultKind)) {
            return ToolResultUtils.toToolResponse(result);
        }

        Map<String, Object> fields = new HashMap<>();
        response.forEach(
                (key, value) -> {
                    if (!RESULT_MARKER.equals(key) && !"metadata".equals(key)) {
                        fields.put((String) key, value);
                    }
                });
        return new ObjectMapper()
                .convertValue(fields, ToolResponse.class)
                .withMetadata((Map<String, Object>) response.get("metadata"));
    }

    private PythonToolResultConverter() {}
}
