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

package org.apache.flink.agents.runtime.subagent;

import org.apache.flink.agents.api.context.AsyncFuture;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.apache.flink.agents.plan.subagent.SubagentConstants;
import org.apache.flink.agents.plan.tools.AsyncTool;
import org.apache.flink.agents.plan.tools.ToolCallContext;
import org.apache.flink.agents.plan.utils.ToolResultUtils;
import org.apache.flink.agents.runtime.context.AsyncFutureImpl;

import java.util.HashMap;
import java.util.Map;

/** Executable view of a subagent, using the setup's own durable execution boundary. */
public class SubagentTool extends Tool implements AsyncTool {
    private final String agentName;
    private final SubagentSetup setup;

    public SubagentTool(String agentName, SubagentSetup setup, ResourceContext resourceContext) {
        super(
                new ToolMetadata(
                        SubagentConstants.TOOL_NAME_PREFIX + agentName,
                        effectiveDescription(agentName, setup.getDescription()),
                        setup.getInputSchema()));
        this.agentName = agentName;
        this.setup = setup;
        this.resourceContext = resourceContext;
    }

    @Override
    public AsyncFuture<ToolResponse> callAsync(
            RunnerContext ctx, ToolParameters parameters, ToolCallContext callContext)
            throws Exception {
        Map<String, Object> input = new HashMap<>();
        for (String name : parameters.getParameterNames()) {
            input.put(name, parameters.getParameter(name));
        }
        // Resolving the tool view binds metrics to this wrapper; the setup executes the call.
        setup.setMetricGroup(ctx.getActionMetricGroup());
        callContext.started();
        var handle = setup.submit(ctx, input);
        callContext.onCancel(handle::cancel);
        return AsyncFutureImpl.map(
                ctx,
                handle,
                result -> {
                    try {
                        if (!result.isSuccess()) {
                            throw result.getException();
                        }
                        return ToolResponse.text(
                                ToolResultUtils.toChatMessageContent(
                                        ToolResultUtils.normalizeAgentResult(
                                                result.getResult(), setup.getResultType())));
                    } catch (StackOverflowError error) {
                        throw new IllegalArgumentException(
                                "Sub-agent result is cyclic or too deeply nested to normalize as JSON");
                    } finally {
                        callContext.finished();
                    }
                });
    }

    /**
     * Falls back to a generic delegation description, so an undescribed sub-agent stays usable.
     * Every description ends with the sub-agent marker, which replaces a separate listing message:
     * the model learns that the callable is a delegation from the description alone.
     */
    private static String effectiveDescription(String agentName, String description) {
        String effective =
                description == null || description.isBlank()
                        ? "Delegate a standalone task to sub-agent " + agentName
                        : description;
        return effective + " This is subagent.";
    }

    /**
     * Sub-agents are declared to the model as plain functions: the model builds the call the same
     * way it builds a tool call, and only the executing side tells them apart.
     */
    @Override
    public ToolType getToolType() {
        return ToolType.FUNCTION;
    }

    @Override
    public ToolResponse call(ToolParameters parameters) {
        throw new UnsupportedOperationException("Use SubagentTool.callAsync with a RunnerContext.");
    }
}
