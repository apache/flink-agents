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
package org.apache.flink.agents.plan.actions;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.configuration.ConfigOption;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolParameterInjection;
import org.apache.flink.agents.api.tools.ToolParameterSource;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.tools.FunctionTool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Built-in action for processing tool call. */
public class ToolCallAction {
    public static Action getToolCallAction() throws Exception {
        return new Action(
                "tool_call_action",
                new JavaFunction(
                        ToolCallAction.class,
                        "processToolRequest",
                        new Class[] {Event.class, RunnerContext.class}),
                List.of(ToolRequestEvent.EVENT_TYPE));
    }

    @SuppressWarnings("unchecked")
    public static void processToolRequest(Event event, RunnerContext ctx) {
        ToolRequestEvent toolRequest = ToolRequestEvent.fromEvent(event);
        boolean toolCallAsync = ctx.getConfig().get(AgentExecutionOptions.TOOL_CALL_ASYNC);

        Map<String, Boolean> success = new HashMap<>();
        Map<String, String> error = new HashMap<>();
        Map<String, ToolResponse> responses = new HashMap<>();
        Map<String, String> externalIds = new HashMap<>();
        for (Map<String, Object> toolCall : toolRequest.getToolCalls()) {
            String id = String.valueOf(toolCall.get("id"));
            Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
            String name = (String) function.get("name");
            Map<String, Object> arguments = (Map<String, Object>) function.get("arguments");
            Map<String, Object> mergedArguments =
                    arguments == null ? new HashMap<>() : new HashMap<>(arguments);

            if (toolCall.containsKey("original_id")) {
                externalIds.put(id, (String) toolCall.get("original_id"));
            }

            Tool tool = null;
            String diagnosticError = null;
            try {
                tool = (Tool) ctx.getResource(name, ResourceType.TOOL);
            } catch (Exception e) {
                diagnosticError = e.getMessage();
            }

            if (tool != null) {
                try {
                    // Framework-owned injected args must win over model-provided values so hidden
                    // context such as tenant ids cannot be spoofed by a tool call payload.
                    mergedArguments.putAll(resolveInjectedArguments(tool, ctx));
                    ToolResponse response;
                    final Tool toolRef = tool;
                    final Map<String, Object> callArguments = mergedArguments;
                    DurableCallable<ToolResponse> callable =
                            new DurableCallable<>() {
                                @Override
                                public String getId() {
                                    return "tool-call";
                                }

                                @Override
                                public Class<ToolResponse> getResultClass() {
                                    return ToolResponse.class;
                                }

                                @Override
                                public ToolResponse call() throws Exception {
                                    return toolRef.call(new ToolParameters(callArguments));
                                }
                            };
                    response =
                            toolCallAsync
                                    ? ctx.durableExecuteAsync(callable)
                                    : ctx.durableExecute(callable);
                    success.put(id, response.isSuccess());
                    responses.put(id, response);
                    if (!response.isSuccess() && response.getError() != null) {
                        error.put(id, response.getError());
                    }
                } catch (Exception e) {
                    success.put(id, false);
                    responses.put(
                            id, ToolResponse.error(String.format("Tool %s execute failed.", name)));
                    error.put(id, e.getMessage());
                }
            } else {
                success.put(id, false);
                responses.put(
                        id, ToolResponse.error(String.format("Tool %s does not exist.", name)));
                error.put(id, diagnosticError != null ? diagnosticError : "Tool does not exist.");
            }
        }
        ctx.sendEvent(
                new ToolResponseEvent(toolRequest.getId(), responses, success, error, externalIds));
    }

    private static Map<String, Object> resolveInjectedArguments(Tool tool, RunnerContext ctx)
            throws Exception {
        Map<String, Object> result = new HashMap<>();
        if (!(tool instanceof FunctionTool)) {
            return result;
        }
        FunctionTool functionTool = (FunctionTool) tool;
        for (Map.Entry<String, ToolParameterInjection> entry :
                functionTool.getInjectedArgs().entrySet()) {
            result.put(entry.getKey(), resolveInjectedArgument(entry.getValue(), ctx));
        }
        return result;
    }

    private static Object resolveInjectedArgument(
            ToolParameterInjection injection, RunnerContext ctx) throws Exception {
        String key = injection.getKey();
        ToolParameterSource source = injection.getSource();
        switch (source) {
            case CONFIG:
                Object value = ctx.getConfig().get(new ConfigOption<>(key, Object.class, null));
                if (value == null) {
                    throw new IllegalArgumentException(
                            "Missing config for injected tool parameter: " + key);
                }
                return value;
            case SENSORY_MEMORY:
                return getMemoryValue(ctx.getSensoryMemory(), "sensory_memory", key);
            case SHORT_TERM_MEMORY:
                return getMemoryValue(ctx.getShortTermMemory(), "short_term_memory", key);
            default:
                throw new IllegalArgumentException("Unsupported tool parameter source: " + source);
        }
    }

    private static Object getMemoryValue(MemoryObject memory, String source, String path)
            throws Exception {
        if (memory == null) {
            throw new IllegalStateException(
                    "Cannot inject tool parameter from "
                            + source
                            + " because memory is not initialized.");
        }
        if (!memory.isExist(path)) {
            throw new IllegalArgumentException(
                    "Missing memory path for injected tool parameter: " + path);
        }
        MemoryObject value = memory.get(path);
        if (value == null || value.isNestedObject()) {
            throw new IllegalArgumentException(
                    "Memory path for injected tool parameter must reference a value: " + path);
        }
        return value.getValue();
    }
}
