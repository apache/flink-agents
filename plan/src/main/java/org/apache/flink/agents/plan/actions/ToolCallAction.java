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
import org.apache.flink.agents.api.tools.ToolExecutionMetadataProvider;
import org.apache.flink.agents.api.tools.ToolParameterInjection;
import org.apache.flink.agents.api.tools.ToolParameterSource;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.ExecutionReporters;
import org.apache.flink.agents.api.trace.ToolExecutionMetadataKeys;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.tools.FunctionTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Built-in action for processing tool call. */
public class ToolCallAction {
    private static final Logger LOG = LoggerFactory.getLogger(ToolCallAction.class);

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
            Exception preparationError = null;
            try {
                tool = (Tool) ctx.getResource(name, ResourceType.TOOL);
            } catch (Exception e) {
                preparationError = e;
            }

            if (tool != null) {
                try {
                    // Framework-owned injected args must win over model-provided values so hidden
                    // context such as tenant ids cannot be spoofed by a tool call payload.
                    mergedArguments.putAll(resolveInjectedArguments(tool, ctx));
                } catch (Exception e) {
                    preparationError = e;
                }
            }

            ToolParameters metadataParameters = new ToolParameters(mergedArguments);
            Map<String, Object> entityMetadata =
                    toolEntityMetadata(
                            toolRequest.getId(),
                            id,
                            externalIds.get(id),
                            name,
                            tool,
                            metadataParameters);
            ExecutionReporters.started(
                    ctx, ExecutionReporter.EntityTypes.TOOL, name, entityMetadata);

            if (tool == null || preparationError != null) {
                Exception failure =
                        preparationError != null
                                ? preparationError
                                : new IllegalArgumentException("Tool does not exist.");
                success.put(id, false);
                responses.put(
                        id,
                        ToolResponse.error(
                                String.format(
                                        tool == null
                                                ? "Tool %s does not exist."
                                                : "Tool %s execute failed.",
                                        name)));
                String failureMessage = failure.getMessage();
                if (failureMessage == null && tool == null) {
                    failureMessage = "Tool does not exist.";
                }
                error.put(id, failureMessage);
                ExecutionReporters.failed(
                        ctx,
                        ExecutionReporter.EntityTypes.TOOL,
                        name,
                        entityMetadata,
                        failure,
                        ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED);
                continue;
            }

            ToolResponse response = null;
            try {
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
                ExecutionReporters.failed(
                        ctx,
                        ExecutionReporter.EntityTypes.TOOL,
                        name,
                        entityMetadata,
                        e,
                        ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED);
            }
            if (response != null) {
                if (response.isSuccess()) {
                    ExecutionReporters.succeeded(
                            ctx, ExecutionReporter.EntityTypes.TOOL, name, entityMetadata);
                } else {
                    ExecutionReporters.failed(
                            ctx,
                            ExecutionReporter.EntityTypes.TOOL,
                            name,
                            entityMetadata,
                            new RuntimeException(response.getError()),
                            ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED);
                }
            }
        }
        ctx.sendEvent(
                new ToolResponseEvent(toolRequest.getId(), responses, success, error, externalIds));
    }

    private static Map<String, Object> toolEntityMetadata(
            UUID toolRequestEventId,
            String toolCallId,
            String externalId,
            String toolName,
            Tool tool,
            ToolParameters parameters) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(
                ToolExecutionMetadataKeys.TOOL_REQUEST_EVENT_ID, toolRequestEventId.toString());
        metadata.put(ToolExecutionMetadataKeys.TOOL_CALL_ID, toolCallId);
        if (externalId != null) {
            metadata.put(ToolExecutionMetadataKeys.EXTERNAL_ID, externalId);
        }
        ToolType toolType = tool == null ? null : tool.getToolType();
        if (toolType != null) {
            metadata.put(ToolExecutionMetadataKeys.TOOL_TYPE, toolType.getValue());
        }
        if (tool instanceof ToolExecutionMetadataProvider) {
            Map<String, Object> extra;
            try {
                extra = ((ToolExecutionMetadataProvider) tool).getToolExecutionMetadata(parameters);
            } catch (RuntimeException e) {
                LOG.debug("Failed to collect execution metadata for tool {}.", toolName, e);
                extra = Map.of();
            }
            if (extra != null && !extra.isEmpty()) {
                mergeSupplementalMetadata(metadata, extra);
            }
        }
        return metadata;
    }

    private static void mergeSupplementalMetadata(
            Map<String, Object> target, Map<String, Object> supplemental) {
        for (Map.Entry<String, Object> entry : supplemental.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                target.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
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
