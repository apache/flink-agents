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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameterInjection;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.apache.flink.agents.plan.Function;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.plan.tools.serializer.FunctionToolJsonDeserializer;
import org.apache.flink.agents.plan.tools.serializer.FunctionToolJsonSerializer;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan-level implementation of a tool that wraps a static Java method. This belongs in the plan
 * module as it handles the implementation logic for converting user-defined @Tool methods into
 * executable tools.
 */
@JsonSerialize(using = FunctionToolJsonSerializer.class)
@JsonDeserialize(using = FunctionToolJsonDeserializer.class)
public class FunctionTool extends Tool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Function function;
    private Map<String, ToolParameterInjection> injectedArgs;

    @JsonIgnore private transient PythonResourceAdapter pythonResourceAdapter;

    /** Create a FunctionTool from ToolMetadata and Function */
    public FunctionTool(ToolMetadata metadata, Function function) {
        this(metadata, function, Map.of());
    }

    public FunctionTool(
            ToolMetadata metadata,
            Function function,
            Map<String, ToolParameterInjection> injectedArgs) {
        super(metadata);
        this.function = function;
        this.injectedArgs = normalizeInjectedArgs(injectedArgs);
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
        Map<String, ToolParameterInjection> injectedArgs = getInjectedArgs(method);

        ToolMetadata metadata =
                new ToolMetadata(
                        name,
                        description,
                        SchemaUtils.generateSchema(method, injectedArgs.keySet()));
        JavaFunction javaFunction =
                new JavaFunction(
                        method.getDeclaringClass(), method.getName(), method.getParameterTypes());

        return new FunctionTool(metadata, javaFunction, injectedArgs);
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
        Map<String, ToolParameterInjection> injectedArgs = getInjectedArgs(method);
        ToolMetadata metadata =
                new ToolMetadata(
                        method.getName(),
                        description != null ? description : "",
                        SchemaUtils.generateSchema(method, injectedArgs.keySet()));
        JavaFunction javaFunction =
                new JavaFunction(
                        method.getDeclaringClass(), method.getName(), method.getParameterTypes());
        return new FunctionTool(metadata, javaFunction, injectedArgs);
    }

    @Override
    public ToolType getToolType() {
        return ToolType.FUNCTION;
    }

    @Override
    public ToolResponse call(ToolParameters parameters) {
        try {
            if (function instanceof PythonFunction) {
                return callPython((PythonFunction) function, parameters);
            }
            return callJava(parameters);
        } catch (Exception e) {
            return ToolResponse.error(e);
        }
    }

    private ToolResponse callJava(ToolParameters parameters) throws Exception {
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
                    throw new IllegalArgumentException("Missing required parameter: " + paramName);
                }
            }
            args[i] = value;
        }
        Object result = function.call(args);
        return ToolResponse.success(result);
    }

    private ToolResponse callPython(PythonFunction pf, ToolParameters parameters) {
        if (pythonResourceAdapter == null) {
            return ToolResponse.error(
                    new IllegalStateException(
                            "Python tool '"
                                    + pf.getQualName()
                                    + "' has no PythonResourceAdapter; runtime should inject one"
                                    + " before invocation."));
        }
        // ToolCallAction resolves injected parameters before FunctionTool is invoked; this adapter
        // only forwards the final keyword arguments across the language boundary.
        Map<String, Object> kwargs = new HashMap<>();
        for (String name : parameters.getParameterNames()) {
            kwargs.put(name, parameters.getParameter(name));
        }
        Object result =
                pythonResourceAdapter.invokePythonTool(pf.getModule(), pf.getQualName(), kwargs);
        return ToolResponse.success(result);
    }

    public Function getFunction() {
        return function;
    }

    public Map<String, ToolParameterInjection> getInjectedArgs() {
        return injectedArgs;
    }

    public List<String> getInjectedArgNames() {
        return List.copyOf(injectedArgs.keySet());
    }

    /**
     * Refresh this tool's metadata via the Python bridge when the underlying function is a {@link
     * PythonFunction}. No-op for Java-backed tools.
     *
     * <p>Called by the runtime resource cache the first time the tool is resolved, so the
     * placeholder metadata that {@code AgentPlan.registerApiFunctionTool} writes for Python tools
     * gets replaced with real introspected values (name, description, inputSchema) sourced from the
     * Python callable's signature and docstring. Callable-declared injected args returned by the
     * bridge are merged into this tool so the Java-side ToolCallAction can inject them at execution
     * time.
     */
    public void setPythonResourceAdapter(PythonResourceAdapter adapter) {
        if (!(function instanceof PythonFunction)) {
            return;
        }
        this.pythonResourceAdapter = adapter;
        PythonFunction pf = (PythonFunction) function;
        Map<String, String> flat =
                adapter.getPythonToolMetadata(
                        pf.getModule(), pf.getQualName(), getInjectedArgNames());
        this.injectedArgs =
                mergeInjectedArgs(
                        parseInjectedArgs(flat.get("injectedArgs")),
                        this.injectedArgs,
                        flat.getOrDefault("name", pf.getQualName()));
        setMetadata(
                new ToolMetadata(
                        flat.get("name"),
                        flat.getOrDefault("description", ""),
                        flat.getOrDefault("inputSchema", "{}")));
    }

    public static Map<String, ToolParameterInjection> getInjectedArgs(Method method) {
        Map<String, ToolParameterInjection> result = new LinkedHashMap<>();
        for (Parameter parameter : method.getParameters()) {
            if (!parameter.isAnnotationPresent(ToolParam.class)) {
                continue;
            }
            ToolParam toolParam = parameter.getAnnotation(ToolParam.class);
            if (!toolParam.injected()) {
                continue;
            }
            String name = toolParam.name().isEmpty() ? parameter.getName() : toolParam.name();
            ToolParameterInjection injection =
                    new ToolParameterInjection(toolParam.source(), toolParam.key())
                            .withDefaultKey(name);
            result.put(name, injection);
        }
        return result;
    }

    private static Map<String, ToolParameterInjection> parseInjectedArgs(String payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        try {
            return normalizeInjectedArgs(
                    OBJECT_MAPPER.readValue(
                            payload, new TypeReference<Map<String, ToolParameterInjection>>() {}));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse Python tool injectedArgs.", e);
        }
    }

    private static Map<String, ToolParameterInjection> mergeInjectedArgs(
            Map<String, ToolParameterInjection> annotatedArgs,
            Map<String, ToolParameterInjection> declaredArgs,
            String toolName) {
        Map<String, ToolParameterInjection> merged = new LinkedHashMap<>();
        if (annotatedArgs != null) {
            merged.putAll(annotatedArgs);
        }
        if (declaredArgs != null) {
            declaredArgs.forEach(
                    (name, injection) -> {
                        ToolParameterInjection existing = merged.get(name);
                        if (existing != null && !existing.equals(injection)) {
                            throw new IllegalArgumentException(
                                    "Tool '"
                                            + toolName
                                            + "': injected_args conflict for parameter '"
                                            + name
                                            + "' between callable annotation and descriptor.");
                        }
                        merged.put(name, injection);
                    });
        }
        return Map.copyOf(merged);
    }

    private static Map<String, ToolParameterInjection> normalizeInjectedArgs(
            Map<String, ToolParameterInjection> injectedArgs) {
        Map<String, ToolParameterInjection> result = new LinkedHashMap<>();
        if (injectedArgs != null) {
            injectedArgs.forEach((name, spec) -> result.put(name, spec.withDefaultKey(name)));
        }
        return Map.copyOf(result);
    }
}
