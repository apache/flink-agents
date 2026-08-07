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
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolExecutionMetadataProvider;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.ToolExecutionMetadataKeys;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** Tests for tool-call execution reports. */
class ToolCallActionReportTest {

    @Test
    void processToolRequestReportsEachToolCall() throws Exception {
        RunnerContext ctx =
                mock(RunnerContext.class, withSettings().extraInterfaces(ExecutionReporter.class));
        List<Event> sentEvents = new ArrayList<>();
        Tool tool =
                new ReportingTool(
                        ToolType.MCP,
                        Map.of(ToolExecutionMetadataKeys.MCP_SERVER, "search-server"),
                        ToolResponse.success("ok"));
        when(ctx.getResource("search", ResourceType.TOOL)).thenReturn(tool);
        when(ctx.getConfig()).thenReturn(toolCallConfig());
        when(ctx.<ToolResponse>durableExecute(any()))
                .thenAnswer(inv -> inv.<DurableCallable<ToolResponse>>getArgument(0).call());
        doAnswer(inv -> sentEvents.add(inv.getArgument(0))).when(ctx).sendEvent(any());

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "search");
        function.put("arguments", Map.of("query", "flink"));
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", "call-1");
        toolCall.put("original_id", "external-call-1");
        toolCall.put("function", function);
        ToolRequestEvent request = new ToolRequestEvent("test-model", List.of(toolCall));

        ToolCallAction.processToolRequest(request, ctx);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ToolExecutionMetadataKeys.TOOL_REQUEST_EVENT_ID, request.getId().toString());
        metadata.put(ToolExecutionMetadataKeys.TOOL_CALL_ID, "call-1");
        metadata.put(ToolExecutionMetadataKeys.EXTERNAL_ID, "external-call-1");
        metadata.put(ToolExecutionMetadataKeys.TOOL_TYPE, ToolType.MCP.getValue());
        metadata.put(ToolExecutionMetadataKeys.MCP_SERVER, "search-server");
        ExecutionReporter reporter = (ExecutionReporter) ctx;
        verify(reporter)
                .reportExecutionStarted(ExecutionReporter.EntityTypes.TOOL, "search", metadata);
        verify(reporter)
                .reportExecutionSucceeded(ExecutionReporter.EntityTypes.TOOL, "search", metadata);

        assertThat(sentEvents).hasSize(1);
        assertThat(sentEvents.get(0)).isInstanceOf(ToolResponseEvent.class);
    }

    @Test
    void processToolRequestMarksErrorResponseAsFailed() throws Exception {
        RunnerContext ctx =
                mock(RunnerContext.class, withSettings().extraInterfaces(ExecutionReporter.class));
        List<Event> sentEvents = new ArrayList<>();
        Tool tool = mock(Tool.class);
        when(tool.call(any())).thenReturn(ToolResponse.error("tool rejected request"));
        when(ctx.getResource("search", ResourceType.TOOL)).thenReturn(tool);
        when(ctx.getConfig()).thenReturn(toolCallConfig());
        when(ctx.<ToolResponse>durableExecute(any()))
                .thenAnswer(inv -> inv.<DurableCallable<ToolResponse>>getArgument(0).call());
        doAnswer(inv -> sentEvents.add(inv.getArgument(0))).when(ctx).sendEvent(any());

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "search");
        function.put("arguments", Map.of("query", "flink"));
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", "call-1");
        toolCall.put("function", function);
        ToolRequestEvent request = new ToolRequestEvent("test-model", List.of(toolCall));

        ToolCallAction.processToolRequest(request, ctx);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ToolExecutionMetadataKeys.TOOL_REQUEST_EVENT_ID, request.getId().toString());
        metadata.put(ToolExecutionMetadataKeys.TOOL_CALL_ID, "call-1");
        ExecutionReporter reporter = (ExecutionReporter) ctx;
        verify(reporter)
                .reportExecutionFailed(
                        eq(ExecutionReporter.EntityTypes.TOOL),
                        eq("search"),
                        eq(metadata),
                        any(Throwable.class),
                        eq(ExecutionReporter.ProblemCategories.TOOL_CALL_FAILED));
        verify(reporter, never())
                .reportExecutionSucceeded(ExecutionReporter.EntityTypes.TOOL, "search", metadata);

        ToolResponseEvent responseEvent = (ToolResponseEvent) sentEvents.get(0);
        assertThat(responseEvent.getSuccess()).containsEntry("call-1", false);
        assertThat(responseEvent.getError()).containsEntry("call-1", "tool rejected request");
    }

    @Test
    void processLoadSkillToolRequestAddsSkillMetadata() throws Exception {
        RunnerContext ctx =
                mock(RunnerContext.class, withSettings().extraInterfaces(ExecutionReporter.class));
        Tool tool =
                new ReportingTool(
                        ToolType.FUNCTION,
                        Map.of(
                                ToolExecutionMetadataKeys.SKILL_NAME,
                                "math-calculator",
                                ToolExecutionMetadataKeys.SKILL_RESOURCE_PATH,
                                "README.md"),
                        ToolResponse.success("skill content"));
        when(ctx.getResource("load_skill", ResourceType.TOOL)).thenReturn(tool);
        when(ctx.getConfig()).thenReturn(toolCallConfig());
        when(ctx.<ToolResponse>durableExecute(any()))
                .thenAnswer(inv -> inv.<DurableCallable<ToolResponse>>getArgument(0).call());

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "load_skill");
        function.put("arguments", Map.of("name", "math-calculator", "path", "README.md"));
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", "call-1");
        toolCall.put("function", function);
        ToolRequestEvent request = new ToolRequestEvent("test-model", List.of(toolCall));

        ToolCallAction.processToolRequest(request, ctx);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ToolExecutionMetadataKeys.TOOL_REQUEST_EVENT_ID, request.getId().toString());
        metadata.put(ToolExecutionMetadataKeys.TOOL_CALL_ID, "call-1");
        metadata.put(ToolExecutionMetadataKeys.TOOL_TYPE, ToolType.FUNCTION.getValue());
        metadata.put(ToolExecutionMetadataKeys.SKILL_NAME, "math-calculator");
        metadata.put(ToolExecutionMetadataKeys.SKILL_RESOURCE_PATH, "README.md");
        verify((ExecutionReporter) ctx)
                .reportExecutionStarted(ExecutionReporter.EntityTypes.TOOL, "load_skill", metadata);
    }

    @Test
    void executionMetadataCannotMutateToolCallParameters() throws Exception {
        RunnerContext ctx =
                mock(RunnerContext.class, withSettings().extraInterfaces(ExecutionReporter.class));
        List<Event> sentEvents = new ArrayList<>();
        when(ctx.getResource("search", ResourceType.TOOL)).thenReturn(new MutatingMetadataTool());
        when(ctx.getConfig()).thenReturn(toolCallConfig());
        when(ctx.<ToolResponse>durableExecute(any()))
                .thenAnswer(inv -> inv.<DurableCallable<ToolResponse>>getArgument(0).call());
        doAnswer(inv -> sentEvents.add(inv.getArgument(0))).when(ctx).sendEvent(any());

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "search");
        function.put("arguments", Map.of("query", "flink"));
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", "call-1");
        toolCall.put("function", function);

        ToolCallAction.processToolRequest(
                new ToolRequestEvent("test-model", List.of(toolCall)), ctx);

        ToolResponseEvent responseEvent = (ToolResponseEvent) sentEvents.get(0);
        assertThat(responseEvent.getResponses().get("call-1").getResult()).isEqualTo("flink");
    }

    private static org.apache.flink.agents.api.configuration.ReadableConfiguration
            toolCallConfig() {
        return new org.apache.flink.agents.api.configuration.ReadableConfiguration() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T get(org.apache.flink.agents.api.configuration.ConfigOption<T> option) {
                if (option == AgentExecutionOptions.TOOL_CALL_ASYNC) {
                    return (T) Boolean.FALSE;
                }
                return option.getDefaultValue();
            }

            @Override
            public Integer getInt(String key, Integer defaultValue) {
                return defaultValue;
            }

            @Override
            public Long getLong(String key, Long defaultValue) {
                return defaultValue;
            }

            @Override
            public Float getFloat(String key, Float defaultValue) {
                return defaultValue;
            }

            @Override
            public Double getDouble(String key, Double defaultValue) {
                return defaultValue;
            }

            @Override
            public Boolean getBool(String key, Boolean defaultValue) {
                return defaultValue;
            }

            @Override
            public String getStr(String key, String defaultValue) {
                return defaultValue;
            }
        };
    }

    private static final class ReportingTool extends Tool implements ToolExecutionMetadataProvider {
        private final ToolType toolType;
        private final Map<String, Object> entityMetadata;
        private final ToolResponse response;

        private ReportingTool(
                ToolType toolType, Map<String, Object> entityMetadata, ToolResponse response) {
            super(new ToolMetadata("search", "Search", "{\"type\":\"object\"}"));
            this.toolType = toolType;
            this.entityMetadata = entityMetadata;
            this.response = response;
        }

        @Override
        public ToolType getToolType() {
            return toolType;
        }

        @Override
        public ToolResponse call(ToolParameters parameters) {
            return response;
        }

        @Override
        public Map<String, Object> getToolExecutionMetadata(ToolParameters parameters) {
            return entityMetadata;
        }
    }

    private static final class MutatingMetadataTool extends Tool
            implements ToolExecutionMetadataProvider {

        private MutatingMetadataTool() {
            super(new ToolMetadata("search", "Search", "{\"type\":\"object\"}"));
        }

        @Override
        public ToolType getToolType() {
            return ToolType.FUNCTION;
        }

        @Override
        public ToolResponse call(ToolParameters parameters) {
            return ToolResponse.success(parameters.getParameter("query"));
        }

        @Override
        public Map<String, Object> getToolExecutionMetadata(ToolParameters parameters) {
            parameters.addParameter("query", "mutated");
            return Map.of();
        }
    }
}
