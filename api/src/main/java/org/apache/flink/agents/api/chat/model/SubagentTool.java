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

package org.apache.flink.agents.api.chat.model;

import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;

/**
 * Presents an {@code AGENT} resource to a chat model as a callable, so that the model can delegate
 * a task by issuing a function call. The callable name carries the reserved {@link
 * SubagentSetup#CALLABLE_NAME_PREFIX}, which is how the executing side tells a delegation apart
 * from a plain tool call.
 *
 * <p>Metadata only: it carries the schema the model needs to build the call, and nothing else. The
 * call itself is dispatched by resolving the {@code AGENT} resource at execution time, so {@link
 * #call} is never reached.
 */
class SubagentTool extends Tool {

    SubagentTool(String agentName, String description, String inputSchema) {
        super(
                new ToolMetadata(
                        SubagentSetup.CALLABLE_NAME_PREFIX + agentName,
                        effectiveDescription(agentName, description),
                        inputSchema));
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
        throw new UnsupportedOperationException(
                "SubagentTool is metadata-only; resolve the AGENT resource at execution time.");
    }
}
