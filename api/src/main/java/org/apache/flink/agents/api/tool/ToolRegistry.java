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

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing tools available to agents. Works with the merged design where BaseTool
 * contains ToolMetadata.
 */
public class ToolRegistry {

    private final Map<String, BaseTool> tools = new ConcurrentHashMap<>();

    /** Register a tool instance directly. */
    public void registerTool(BaseTool tool) {
        String name = tool.getName();
        if (tools.containsKey(name)) {
            throw new IllegalArgumentException(
                    "Tool with name '" + name + "' is already registered");
        }
        tools.put(name, tool);
    }

    /** Register all @Tool annotated methods from an object instance. */
    public void registerToolsFromObject(Object toolProvider) {
        Class<?> clazz = toolProvider.getClass();
        Method[] methods = clazz.getDeclaredMethods();

        for (Method method : methods) {
            Tool toolAnnotation = method.getAnnotation(Tool.class);
            if (toolAnnotation != null) {
                FunctionTool functionTool = FunctionTool.fromMethod(method, toolProvider);
                registerTool(functionTool);
            }
        }
    }

    /** Register all static @Tool annotated methods from a class. */
    public void registerToolsFromClass(Class<?> clazz) {
        Method[] methods = clazz.getDeclaredMethods();

        for (Method method : methods) {
            Tool toolAnnotation = method.getAnnotation(Tool.class);
            if (toolAnnotation != null
                    && java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                FunctionTool functionTool = FunctionTool.fromMethod(method, null);
                registerTool(functionTool);
            }
        }
    }

    /** Get a tool by name. */
    public BaseTool getTool(String name) {
        return tools.get(name);
    }

    /** Get tool metadata by name. */
    public ToolMetadata getToolMetadata(String name) {
        BaseTool tool = tools.get(name);
        return tool != null ? tool.getMetadata() : null;
    }

    /** Check if a tool is registered. */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /** Get all registered tool names. */
    public Set<String> getToolNames() {
        return new HashSet<>(tools.keySet());
    }

    /** Get all registered tool metadata. */
    public List<ToolMetadata> getAllToolMetadata() {
        return tools.values().stream()
                .map(BaseTool::getMetadata)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Invoke a tool by name with parameters. Uses the tool's execute method which includes
     * validation and error handling.
     */
    public ToolResponse invokeTool(String toolName, ToolParameters parameters) {
        BaseTool tool = tools.get(toolName);
        if (tool == null) {
            return ToolResponse.error("Tool '" + toolName + "' not found");
        }

        return tool.execute(parameters);
    }

    /** Remove a tool from the registry. */
    public boolean unregisterTool(String name) {
        return tools.remove(name) != null;
    }

    /** Clear all registered tools. */
    public void clear() {
        tools.clear();
    }

    /** Get the number of registered tools. */
    public int size() {
        return tools.size();
    }

    /** Get all registered tools grouped by type. */
    public Map<ToolType, List<BaseTool>> getToolsByType() {
        Map<ToolType, List<BaseTool>> toolsByType = new HashMap<>();

        for (BaseTool tool : tools.values()) {
            toolsByType.computeIfAbsent(tool.getToolType(), k -> new ArrayList<>()).add(tool);
        }

        return toolsByType;
    }
}
