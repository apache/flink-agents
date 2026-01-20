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

import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.plan.JavaFunction;

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
                        new Class[] {ToolRequestEvent.class, RunnerContext.class}),
                List.of(ToolRequestEvent.class.getName()));
    }

    @SuppressWarnings("unchecked")
    public static void processToolRequest(ToolRequestEvent event, RunnerContext ctx) {
        boolean toolCallAsync = ctx.getConfig().get(AgentExecutionOptions.TOOL_CALL_ASYNC);

        Map<String, Boolean> success = new HashMap<>();
        Map<String, String> error = new HashMap<>();
        Map<String, ToolResponse> responses = new HashMap<>();
        Map<String, String> externalIds = new HashMap<>();
        for (Map<String, Object> toolCall : event.getToolCalls()) {
            String id = String.valueOf(toolCall.get("id"));
            Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
            String name = (String) function.get("name");
            Map<String, Object> arguments = (Map<String, Object>) function.get("arguments");

            if (toolCall.containsKey("original_id")) {
                externalIds.put(id, (String) toolCall.get("original_id"));
            }

            Tool tool = null;
            try {
                tool = (Tool) ctx.getResource(name, ResourceType.TOOL);
            } catch (Exception e) {
                success.put(id, false);
                responses.put(
                        id, ToolResponse.error(String.format("Tool %s does not exist.", name)));
                error.put(id, e.getMessage());
            }

            if (tool != null) {
                try {
                    ToolResponse response;
                    final Tool toolRef = tool;
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
                                    return toolRef.call(new ToolParameters(arguments));
                                }
                            };
                    response =
                            toolCallAsync
                                    ? ctx.durableExecuteAsync(callable)
                                    : ctx.durableExecute(callable);
                    success.put(id, true);
                    responses.put(id, response);
                } catch (Exception e) {
                    success.put(id, false);
                    responses.put(
                            id, ToolResponse.error(String.format("Tool %s execute failed.", name)));
                    error.put(id, e.getMessage());
                }
            }
        }
        ctx.sendEvent(new ToolResponseEvent(event.getId(), responses, success, error, externalIds));
    }
}
