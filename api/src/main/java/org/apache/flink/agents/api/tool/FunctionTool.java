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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Function-based tool implementation that wraps annotated methods. Now uses the merged design with
 * ToolMetadata.
 */
public class FunctionTool extends BaseTool {

    private final Method method;
    private final Object instance;

    public FunctionTool(ToolMetadata metadata, Method method, Object instance) {
        super(metadata);
        this.method = method;
        this.instance = instance;
    }

    /** Factory method to create FunctionTool from annotated method. */
    public static FunctionTool fromMethod(Method method, Object instance) {
        Tool toolAnnotation = method.getAnnotation(Tool.class);
        if (toolAnnotation == null) {
            throw new IllegalArgumentException("Method must be annotated with @Tool");
        }

        String toolName =
                toolAnnotation.name().isEmpty() ? method.getName() : toolAnnotation.name();

        String description = toolAnnotation.description();
        String inputSchema = ToolSchema.createSchemaFromMethod(method);

        // Create ToolDefinition - you'll need to add the import or create this class
        ToolDefinition definition = new ToolDefinition(toolName, description, inputSchema);

        // Create parameter info
        List<ToolMetadata.ToolParameterInfo> parameters = extractParameterInfo(method);

        // Create ToolMetadata
        ToolMetadata metadata =
                new ToolMetadata(definition, ToolType.FUNCTION, method, instance, parameters);

        return new FunctionTool(metadata, method, instance);
    }

    private static List<ToolMetadata.ToolParameterInfo> extractParameterInfo(Method method) {
        List<ToolMetadata.ToolParameterInfo> parameters = new ArrayList<>();
        java.lang.reflect.Parameter[] methodParams = method.getParameters();

        for (java.lang.reflect.Parameter param : methodParams) {
            ToolParam paramAnnotation = param.getAnnotation(ToolParam.class);

            // Use parameter name directly since ToolParam doesn't have value()
            String paramName = param.getName();

            String description = paramAnnotation != null ? paramAnnotation.description() : "";
            boolean required = paramAnnotation == null || paramAnnotation.required();
            String defaultValue = ""; // No defaultValue in annotation, so empty string

            parameters.add(
                    new ToolMetadata.ToolParameterInfo(
                            paramName, description, param.getType(), required, defaultValue));
        }

        return parameters;
    }

    @Override
    public ToolType getToolType() {
        return ToolType.FUNCTION;
    }

    @Override
    public ToolResponse call(ToolParameters parameters) {
        try {
            // Prepare method arguments
            java.lang.reflect.Parameter[] methodParams = method.getParameters();
            Object[] args = new Object[methodParams.length];

            for (int i = 0; i < methodParams.length; i++) {
                java.lang.reflect.Parameter param = methodParams[i];
                ToolParam paramAnnotation = param.getAnnotation(ToolParam.class);

                String paramName = param.getName();

                // Assuming ToolParameters has contains() and get() methods
                if (parameters.hasParameter(paramName)) {
                    args[i] = parameters.getParameter(paramName, param.getType());
                } else if (paramAnnotation != null && !paramAnnotation.required()) {
                    // Optional parameter, use null or default for primitives
                    if (param.getType().isPrimitive()) {
                        args[i] = getDefaultPrimitiveValue(param.getType());
                    } else {
                        args[i] = null;
                    }
                } else {
                    throw new IllegalArgumentException(
                            "Required parameter '" + paramName + "' not provided");
                }
            }

            method.setAccessible(true);
            Object result = method.invoke(instance, args);
            return ToolResponse.success(result);

        } catch (Exception e) {
            return ToolResponse.error(e);
        }
    }

    private Object getDefaultPrimitiveValue(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return '\0';
        return null;
    }

    /** Get the underlying method. */
    public Method getMethod() {
        return method;
    }

    /** Get the instance (null for static methods). */
    public Object getInstance() {
        return instance;
    }

    /** Check if this is a static method. */
    public boolean isStatic() {
        return instance == null;
    }
}
