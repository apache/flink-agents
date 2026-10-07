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
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameterInjection;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.apache.flink.agents.plan.Function;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.plan.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.plan.resource.python.PythonToolResultConverter;
import org.apache.flink.agents.plan.tools.serializer.FunctionToolJsonDeserializer;
import org.apache.flink.agents.plan.tools.serializer.FunctionToolJsonSerializer;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
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
    @JsonIgnore private transient FunctionSchema schema;
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
        super(
                metadata == null && function instanceof JavaFunction
                        ? new ToolMetadata("", "", "{}")
                        : metadata);
        this.function = function;
        this.injectedArgs = normalizeInjectedArgs(injectedArgs);
        if (function instanceof JavaFunction) {
            Method method;
            try {
                method = ((JavaFunction) function).getMethod();
            } catch (ReflectiveOperationException error) {
                throw new IllegalArgumentException("Cannot resolve function tool", error);
            }
            this.injectedArgs =
                    mergeInjectedArgs(getInjectedArgs(method), this.injectedArgs, method.getName());
            schema = new FunctionSchema(method, this.injectedArgs.keySet());
            ToolMetadata derived = schema.getMetadata();
            setMetadata(
                    new ToolMetadata(
                            metadata == null ? derived.getName() : metadata.getName(),
                            metadata == null ? derived.getDescription() : metadata.getDescription(),
                            derived.getInputSchema()));
        }
    }

    /** Create a FunctionTool from a static method annotated with @Tool */
    public static FunctionTool fromStaticMethod(Method method) throws Exception {
        return fromStaticMethod(null, method);
    }

    /** Create a function tool with an optional explicit description. */
    public static FunctionTool fromStaticMethod(String description, Method method)
            throws Exception {
        FunctionTool tool =
                new FunctionTool(
                        null,
                        new JavaFunction(
                                method.getDeclaringClass(),
                                method.getName(),
                                method.getParameterTypes()),
                        getInjectedArgs(method));
        if (description != null) {
            ToolMetadata metadata = tool.getMetadata();
            tool.setMetadata(
                    new ToolMetadata(metadata.getName(), description, metadata.getInputSchema()));
        }
        return tool;
    }

    @Override
    public ToolType getToolType() {
        return ToolType.FUNCTION;
    }

    @Override
    public ToolResponse call(ToolParameters parameters) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (String name : parameters.getParameterNames()) {
            arguments.put(name, parameters.getParameter(name));
        }
        try {
            if (function instanceof PythonFunction) {
                PythonFunction pf = (PythonFunction) function;
                if (pythonResourceAdapter == null) {
                    throw new IllegalStateException("Python tool has no PythonResourceAdapter");
                }
                Object result =
                        pythonResourceAdapter.invokePythonTool(
                                pf.getModule(), pf.getQualName(), arguments);
                return PythonToolResultConverter.fromBridgeResult(result);
            }
            Object result = function.call(schema.bind(arguments));
            return ToolResponse.success(result);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Function tool interrupted");
        } catch (java.util.concurrent.CancellationException error) {
            throw error;
        } catch (Exception error) {
            return ToolResponse.error(error);
        }
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
