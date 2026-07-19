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
import org.apache.flink.agents.api.context.Outcome;
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

import java.util.ArrayList;
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
        boolean toolCallParallel = ctx.getConfig().get(AgentExecutionOptions.TOOL_CALL_PARALLEL);

        Map<String, Boolean> success = new HashMap<>();
        Map<String, String> error = new HashMap<>();
        Map<String, ToolResponse> responses = new HashMap<>();
        Map<String, String> externalIds = new HashMap<>();
        List<ToolCallExecution> executions = buildToolCallExecutions(toolRequest, ctx, externalIds);
        List<ToolCallExecution> callableExecutions = new ArrayList<>();
        List<DurableCallable<ToolResponse>> callables = new ArrayList<>();

        for (ToolCallExecution execution : executions) {
            if (execution.response != null) {
                applyInlineResponse(execution, success, error, responses);
            } else {
                callableExecutions.add(execution);
                callables.add(execution.callable);
            }
        }

        if (toolCallAsync && toolCallParallel && callables.size() > 1) {
            executeParallel(callableExecutions, callables, ctx, success, error, responses);
        } else {
            executeSequentially(callableExecutions, toolCallAsync, ctx, success, error, responses);
        }

        ctx.sendEvent(
                new ToolResponseEvent(toolRequest.getId(), responses, success, error, externalIds));
    }

    @SuppressWarnings("unchecked")
    private static List<ToolCallExecution> buildToolCallExecutions(
            ToolRequestEvent toolRequest, RunnerContext ctx, Map<String, String> externalIds) {
        List<ToolCallExecution> executions = new ArrayList<>();
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

            if (tool == null) {
                executions.add(
                        ToolCallExecution.withResponse(
                                id,
                                name,
                                ToolResponse.error(String.format("Tool %s does not exist.", name)),
                                diagnosticError != null
                                        ? diagnosticError
                                        : "Tool does not exist."));
                continue;
            }

            try {
                // Framework-owned injected args must win over model-provided values so hidden
                // context such as tenant ids cannot be spoofed by a tool call payload.
                mergedArguments.putAll(resolveInjectedArguments(tool, ctx));
            } catch (Exception e) {
                executions.add(
                        ToolCallExecution.withResponse(
                                id,
                                name,
                                ToolResponse.error(String.format("Tool %s execute failed.", name)),
                                e.getMessage()));
                continue;
            }

            final Tool toolRef = tool;
            final Map<String, Object> callArguments = mergedArguments;
            DurableCallable<ToolResponse> callable =
                    new DurableCallable<>() {
                        @Override
                        public String getId() {
                            return "tool-call-" + id;
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
            executions.add(ToolCallExecution.withCallable(id, name, callable));
        }
        return executions;
    }

    private static void executeParallel(
            List<ToolCallExecution> executions,
            List<DurableCallable<ToolResponse>> callables,
            RunnerContext ctx,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, ToolResponse> responses) {
        try {
            List<Outcome<ToolResponse>> outcomes = ctx.durableExecuteAllAsync(callables);
            for (int i = 0; i < outcomes.size(); i++) {
                applyOutcome(executions.get(i), outcomes.get(i), success, error, responses);
            }
        } catch (Exception e) {
            for (ToolCallExecution execution : executions) {
                applyExecutionException(execution, e, success, error, responses);
            }
        }
    }

    private static void executeSequentially(
            List<ToolCallExecution> executions,
            boolean toolCallAsync,
            RunnerContext ctx,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, ToolResponse> responses) {
        for (ToolCallExecution execution : executions) {
            try {
                ToolResponse response =
                        toolCallAsync
                                ? ctx.durableExecuteAsync(execution.callable)
                                : ctx.durableExecute(execution.callable);
                applyToolResponse(execution.id, response, success, error, responses);
            } catch (Exception e) {
                applyExecutionException(execution, e, success, error, responses);
            }
        }
    }

    private static void applyOutcome(
            ToolCallExecution execution,
            Outcome<ToolResponse> outcome,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, ToolResponse> responses) {
        if (outcome.isFailure()) {
            applyExecutionException(execution, outcome.getError(), success, error, responses);
        } else {
            applyToolResponse(execution.id, outcome.getValue(), success, error, responses);
        }
    }

    private static void applyInlineResponse(
            ToolCallExecution execution,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, ToolResponse> responses) {
        applyToolResponse(execution.id, execution.response, success, error, responses);
        if (execution.diagnosticError != null) {
            error.put(execution.id, execution.diagnosticError);
        }
    }

    private static void applyExecutionException(
            ToolCallExecution execution,
            Exception exception,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, ToolResponse> responses) {
        success.put(execution.id, false);
        responses.put(
                execution.id,
                ToolResponse.error(String.format("Tool %s execute failed.", execution.name)));
        error.put(execution.id, exception.getMessage());
    }

    private static void applyToolResponse(
            String id,
            ToolResponse response,
            Map<String, Boolean> success,
            Map<String, String> error,
            Map<String, ToolResponse> responses) {
        success.put(id, response.isSuccess());
        responses.put(id, response);
        if (!response.isSuccess() && response.getError() != null) {
            error.put(id, response.getError());
        }
    }

    private static class ToolCallExecution {
        private final String id;
        private final String name;
        private final DurableCallable<ToolResponse> callable;
        private final ToolResponse response;

        private ToolCallExecution(
                String id,
                String name,
                DurableCallable<ToolResponse> callable,
                ToolResponse response) {
            this.id = id;
            this.name = name;
            this.callable = callable;
            this.response = response;
        }

        private static ToolCallExecution withCallable(
                String id, String name, DurableCallable<ToolResponse> callable) {
            return new ToolCallExecution(id, name, callable, null);
        }

        private static ToolCallExecution withResponse(
                String id, String name, ToolResponse response, String diagnosticError) {
            ToolCallExecution execution = new ToolCallExecution(id, name, null, response);
            execution.diagnosticError = diagnosticError;
            return execution;
        }

        private String diagnosticError;
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
