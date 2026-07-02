/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.flink.agents.plan.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.annotation.ToolParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class SchemaUtils {
    /** Generate JSON schema from method signature for tool parameters. */
    public static String generateSchema(Method method) throws JsonProcessingException {
        return generateSchema(method, Set.of());
    }

    /**
     * Generate JSON schema from method signature for model-visible tool parameters.
     *
     * <p>Injected parameters may be declared either on the Java method via {@link ToolParam} or
     * externally, for example by YAML/API descriptors. Both forms must be hidden from the
     * model-facing schema.
     */
    public static String generateSchema(Method method, Collection<String> injectedParamNames)
            throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        List<String> required = new java.util.ArrayList<>();
        Set<String> injected =
                injectedParamNames == null ? Set.of() : new HashSet<>(injectedParamNames);

        Parameter[] parameters = method.getParameters();
        for (Parameter param : parameters) {
            ToolParam toolParam = param.getAnnotation(ToolParam.class);
            String paramName = param.getName();
            String paramDescription = null;

            // Check for custom parameter name from annotation
            if (toolParam != null && !Objects.requireNonNull(toolParam).name().isEmpty()) {
                paramName = toolParam.name();
            }
            if ((toolParam != null && toolParam.injected()) || injected.contains(paramName)) {
                continue;
            }
            if (toolParam != null) {
                if (toolParam.required()) {
                    required.add(paramName);
                }
                if (!toolParam.description().isEmpty()) {
                    paramDescription = toolParam.description();
                }
            }

            Map<String, Object> paramSchema = getParamSchema(param);
            if (paramDescription != null) {
                paramSchema.put("description", paramDescription);
            }

            properties.put(paramName, paramSchema);
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }

        return mapper.writeValueAsString(schema);
    }

    private static Map<String, Object> getParamSchema(Parameter param) {
        Map<String, Object> paramSchema = new HashMap<>();
        Class<?> paramType = param.getType();

        if (paramType == String.class) {
            paramSchema.put("type", "string");
        } else if (paramType == int.class || paramType == Integer.class) {
            paramSchema.put("type", "integer");
        } else if (paramType == double.class || paramType == Double.class) {
            paramSchema.put("type", "number");
        } else if (paramType == boolean.class || paramType == Boolean.class) {
            paramSchema.put("type", "boolean");
        } else {
            paramSchema.put("type", "object");
        }
        return paramSchema;
    }
}
