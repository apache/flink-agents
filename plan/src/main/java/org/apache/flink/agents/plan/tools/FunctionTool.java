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

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.apache.flink.agents.plan.Function;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.tools.serializer.FunctionToolJsonDeserializer;
import org.apache.flink.agents.plan.tools.serializer.FunctionToolJsonSerializer;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;

/**
 * Plan-level implementation of a tool that wraps a static Java method. This belongs in the plan
 * module as it handles the implementation logic for converting user-defined @Tool methods into
 * executable tools.
 */
@JsonSerialize(using = FunctionToolJsonSerializer.class)
@JsonDeserialize(using = FunctionToolJsonDeserializer.class)
public class FunctionTool extends Tool {

    private final Function function;

    /** Create a FunctionTool from ToolMetadata and Function */
    public FunctionTool(ToolMetadata metadata, Function function) {
        super(metadata);
        this.function = function;
    }

    /** Create a FunctionTool from a static method annotated with @Tool */
    public static FunctionTool fromStaticMethod(Method method) throws Exception {
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException(
                    "FunctionTool only supports static methods. Method: " + method.getName());
        }

        org.apache.flink.agents.api.annotation.Tool toolAnnotation =
                method.getAnnotation(org.apache.flink.agents.api.annotation.Tool.class);
        String name = method.getName();
        String description = toolAnnotation != null ? toolAnnotation.description() : "";

        ToolMetadata metadata =
                new ToolMetadata(name, description, SchemaUtils.generateSchema(method));
        JavaFunction javaFunction =
                new JavaFunction(
                        method.getDeclaringClass(), method.getName(), method.getParameterTypes());

        return new FunctionTool(metadata, javaFunction);
    }

    /**
     * Create a FunctionTool from a static method with explicit description. This does not require a
     * method-level @Tool annotation.
     */
    public static FunctionTool fromStaticMethod(String description, Method method)
            throws Exception {
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException(
                    "FunctionTool only supports static methods. Method: " + method.getName());
        }
        ToolMetadata metadata =
                new ToolMetadata(
                        method.getName(),
                        description != null ? description : "",
                        SchemaUtils.generateSchema(method));
        JavaFunction javaFunction =
                new JavaFunction(
                        method.getDeclaringClass(), method.getName(), method.getParameterTypes());
        return new FunctionTool(metadata, javaFunction);
    }

    @Override
    public ToolType getToolType() {
        return ToolType.FUNCTION;
    }

    @Override
    public ToolResponse call(ToolParameters parameters) {
        try {
            // Map ToolParameters to method arguments by name and type
            Method method = ((JavaFunction) function).getMethod();
            Parameter[] methodParams = method.getParameters();
            Object[] args = new Object[methodParams.length];
            for (int i = 0; i < methodParams.length; i++) {
                Parameter p = methodParams[i];
                String paramName = p.getName();
                if (p.isAnnotationPresent(ToolParam.class)) {
                    ToolParam ann = p.getAnnotation(ToolParam.class);
                    if (!ann.name().isEmpty()) {
                        paramName = ann.name();
                    }
                }
                Object value = parameters.getParameter(paramName, p.getType());
                if (value == null && p.isAnnotationPresent(ToolParam.class)) {
                    ToolParam ann = p.getAnnotation(ToolParam.class);
                    if (ann.required() && ann.defaultValue().isEmpty()) {
                        throw new IllegalArgumentException(
                                "Missing required parameter: " + paramName);
                    }
                }
                args[i] = value;
            }

            Object result = function.call(args);
            return ToolResponse.success(result);
        } catch (Exception e) {
            return ToolResponse.error(e);
        }
    }

    public Function getFunction() {
        return function;
    }
}
