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

package org.apache.flink.agents.api.tool;

import org.apache.flink.agents.api.tool.annotation.Tool;
import org.apache.flink.agents.api.tool.annotation.ToolParam;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for generating JSON schemas from tool methods. Corresponds to the Python schema
 * utilities in utils.py.
 */
public class ToolSchema {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final Map<Class<?>, String> TYPE_MAPPING = new HashMap<>();

    static {
        TYPE_MAPPING.put(String.class, "string");
        TYPE_MAPPING.put(Integer.class, "integer");
        TYPE_MAPPING.put(int.class, "integer");
        TYPE_MAPPING.put(Long.class, "integer");
        TYPE_MAPPING.put(long.class, "integer");
        TYPE_MAPPING.put(Double.class, "number");
        TYPE_MAPPING.put(double.class, "number");
        TYPE_MAPPING.put(Float.class, "number");
        TYPE_MAPPING.put(float.class, "number");
        TYPE_MAPPING.put(Boolean.class, "boolean");
        TYPE_MAPPING.put(boolean.class, "boolean");
        TYPE_MAPPING.put(Object.class, "object");
        TYPE_MAPPING.put(Map.class, "object");
        TYPE_MAPPING.put(java.util.List.class, "array");
        TYPE_MAPPING.put(java.util.Collection.class, "array");
    }

    /** Create a JSON schema from a method annotated with @Tool. */
    public static String createSchemaFromMethod(Method method) {
        Tool toolAnnotation = method.getAnnotation(Tool.class);
        if (toolAnnotation == null) {
            throw new IllegalArgumentException("Method must be annotated with @Tool");
        }

        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();
        ArrayNode required = objectMapper.createArrayNode();

        Parameter[] parameters = method.getParameters();
        for (Parameter parameter : parameters) {
            ToolParam paramAnnotation = parameter.getAnnotation(ToolParam.class);

            String paramName = parameter.getName();
            ObjectNode paramSchema = createParameterSchema(parameter, paramAnnotation);
            properties.set(paramName, paramSchema);

            // Add to required if no default value and required=true
            if (paramAnnotation == null
                    || (paramAnnotation.required() && paramAnnotation.defaultValue().isEmpty())) {
                required.add(paramName);
            }
        }

        schema.set("properties", properties);
        if (required.size() > 0) {
            schema.set("required", required);
        }

        return schema.toString();
    }

    private static ObjectNode createParameterSchema(
            Parameter parameter, ToolParam paramAnnotation) {
        ObjectNode paramSchema = objectMapper.createObjectNode();

        Class<?> paramType = parameter.getType();
        String jsonType = TYPE_MAPPING.getOrDefault(paramType, "string");
        paramSchema.put("type", jsonType);

        if (paramAnnotation != null) {
            if (!paramAnnotation.description().isEmpty()) {
                paramSchema.put("description", paramAnnotation.description());
            }

            if (!paramAnnotation.defaultValue().isEmpty()) {
                // Convert default value based on type
                Object defaultValue =
                        convertDefaultValue(paramAnnotation.defaultValue(), paramType);
                if (defaultValue instanceof String) {
                    paramSchema.put("default", (String) defaultValue);
                } else if (defaultValue instanceof Integer) {
                    paramSchema.put("default", (Integer) defaultValue);
                } else if (defaultValue instanceof Double) {
                    paramSchema.put("default", (Double) defaultValue);
                } else if (defaultValue instanceof Boolean) {
                    paramSchema.put("default", (Boolean) defaultValue);
                }
            }
        }

        return paramSchema;
    }

    private static Object convertDefaultValue(String defaultValue, Class<?> targetType) {
        if (targetType == String.class) {
            return defaultValue;
        } else if (targetType == Integer.class || targetType == int.class) {
            return Integer.parseInt(defaultValue);
        } else if (targetType == Long.class || targetType == long.class) {
            return Long.parseLong(defaultValue);
        } else if (targetType == Double.class || targetType == double.class) {
            return Double.parseDouble(defaultValue);
        } else if (targetType == Float.class || targetType == float.class) {
            return Float.parseFloat(defaultValue);
        } else if (targetType == Boolean.class || targetType == boolean.class) {
            return Boolean.parseBoolean(defaultValue);
        }
        return defaultValue;
    }

    /** Validate parameters against a JSON schema. */
    public static boolean validateParameters(ToolParameters parameters, String schema) {
        try {
            JsonNode schemaNode = objectMapper.readTree(schema);
            return validateParametersAgainstSchema(parameters, schemaNode);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean validateParametersAgainstSchema(
            ToolParameters parameters, JsonNode schema) {
        JsonNode properties = schema.get("properties");
        JsonNode required = schema.get("required");

        // Check required parameters
        if (required != null && required.isArray()) {
            for (JsonNode requiredParam : required) {
                String paramName = requiredParam.asText();
                if (!parameters.hasParameter(paramName)) {
                    return false;
                }
            }
        }

        // Validate parameter types (basic validation)
        if (properties != null && properties.isObject()) {
            for (String paramName : parameters.getParameterNames()) {
                JsonNode paramSchema = properties.get(paramName);
                if (paramSchema != null) {
                    Object paramValue = parameters.getParameter(paramName);
                    if (!validateParameterType(paramValue, paramSchema)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private static boolean validateParameterType(Object value, JsonNode paramSchema) {
        if (value == null) {
            return true; // Null values are generally acceptable unless specifically prohibited
        }

        String expectedType = paramSchema.get("type").asText();

        switch (expectedType) {
            case "string":
                return value instanceof String;
            case "integer":
                return value instanceof Integer || value instanceof Long;
            case "number":
                return value instanceof Number;
            case "boolean":
                return value instanceof Boolean;
            case "object":
                return value instanceof Map || value instanceof Object;
            case "array":
                return value instanceof java.util.Collection;
            default:
                return true; // Unknown types pass validation
        }
    }
}
