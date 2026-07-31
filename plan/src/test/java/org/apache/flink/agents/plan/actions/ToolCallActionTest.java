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
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.configuration.ReadableConfiguration;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.MemoryRef;
import org.apache.flink.agents.api.context.Outcome;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.memory.BaseLongTermMemory;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameterInjection;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.tools.FunctionTool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class ToolCallActionTest {

    public static String queryOrder(
            @ToolParam(name = "orderId") String orderId,
            @ToolParam(name = "tenant_id") String tenantId) {
        return tenantId + ":" + orderId;
    }

    static class FailingTool extends Tool {
        FailingTool() {
            super(new ToolMetadata("queryOrder", "Query order.", "{}"));
        }

        @Override
        public ToolType getToolType() {
            return ToolType.FUNCTION;
        }

        @Override
        public ToolResponse call(ToolParameters parameters) {
            throw new RuntimeException("database timeout");
        }
    }

    static class FakeRunnerContext implements RunnerContext {
        private final List<Event> sentEvents = new ArrayList<>();
        private final AgentConfiguration config =
                new AgentConfiguration(
                        Map.of(
                                "tenant_id",
                                "tenant-1",
                                AgentExecutionOptions.TOOL_CALL_ASYNC.getKey(),
                                true,
                                AgentExecutionOptions.TOOL_CALL_PARALLEL.getKey(),
                                true));
        private final List<String> durableExecuteIds = new ArrayList<>();
        private final List<String> durableExecuteAsyncIds = new ArrayList<>();
        private final List<List<String>> durableExecuteAllAsyncIds = new ArrayList<>();
        private final AtomicInteger queryOrderCalls = new AtomicInteger();
        private List<Outcome<ToolResponse>> durableExecuteAllAsyncOutcomes;
        private ToolParameterInjection injection = ToolParameterInjection.fromConfig("tenant_id");
        private MemoryObject sensoryMemory;
        private MemoryObject shortTermMemory;

        FakeRunnerContext withInjection(ToolParameterInjection injection) {
            this.injection = injection;
            return this;
        }

        FakeRunnerContext withToolCallAsync(boolean enabled) {
            config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, enabled);
            return this;
        }

        FakeRunnerContext withToolCallParallel(boolean enabled) {
            config.set(AgentExecutionOptions.TOOL_CALL_PARALLEL, enabled);
            return this;
        }

        FakeRunnerContext withDurableExecuteAllAsyncOutcomes(List<Outcome<ToolResponse>> outcomes) {
            this.durableExecuteAllAsyncOutcomes = outcomes;
            return this;
        }

        FakeRunnerContext withSensoryMemory(Map<String, Object> values) {
            this.sensoryMemory = new FakeMemoryObject(values);
            return this;
        }

        FakeRunnerContext withShortTermMemory(Map<String, Object> values) {
            this.shortTermMemory = new FakeMemoryObject(values);
            return this;
        }

        @Override
        public void sendEvent(Event event) {
            sentEvents.add(event);
        }

        @Override
        public MemoryObject getSensoryMemory() {
            return sensoryMemory;
        }

        @Override
        public MemoryObject getShortTermMemory() {
            return shortTermMemory;
        }

        @Override
        public BaseLongTermMemory getLongTermMemory() {
            return null;
        }

        @Override
        public FlinkAgentsMetricGroup getAgentMetricGroup() {
            return null;
        }

        @Override
        public FlinkAgentsMetricGroup getActionMetricGroup() {
            return null;
        }

        @Override
        public Resource getResource(String name, ResourceType type) throws Exception {
            assertThat(name).isEqualTo("queryOrder");
            assertThat(type).isEqualTo(ResourceType.TOOL);
            return new FunctionTool(
                    new ToolMetadata("queryOrder", "Query order.", "{}"),
                    new JavaFunction(
                            ToolCallActionTest.class,
                            "queryOrder",
                            new Class[] {String.class, String.class}),
                    Map.of("tenant_id", injection));
        }

        @Override
        public ReadableConfiguration getConfig() {
            return config;
        }

        @Override
        public Map<String, Object> getActionConfig() {
            return Map.of();
        }

        @Override
        public Object getActionConfigValue(String key) {
            return null;
        }

        @Override
        public <T> T durableExecute(DurableCallable<T> callable) throws Exception {
            durableExecuteIds.add(callable.getId());
            return callable.call();
        }

        @Override
        public <T> T durableExecuteAsync(DurableCallable<T> callable) throws Exception {
            durableExecuteAsyncIds.add(callable.getId());
            return callable.call();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<Outcome<T>> durableExecuteAllAsync(List<DurableCallable<T>> callables)
                throws Exception {
            List<String> ids = new ArrayList<>();
            for (DurableCallable<T> callable : callables) {
                ids.add(callable.getId());
            }
            durableExecuteAllAsyncIds.add(ids);
            if (durableExecuteAllAsyncOutcomes != null) {
                return (List<Outcome<T>>) (List<?>) durableExecuteAllAsyncOutcomes;
            }
            List<Outcome<T>> outcomes = new ArrayList<>(callables.size());
            for (DurableCallable<T> callable : callables) {
                outcomes.add(Outcome.success(callable.call()));
            }
            return outcomes;
        }

        @Override
        public void close() {}
    }

    static class FakeMemoryObject implements MemoryObject {
        private final Map<String, Object> values;
        private final Object value;

        FakeMemoryObject(Map<String, Object> values) {
            this(values, null);
        }

        FakeMemoryObject(Map<String, Object> values, Object value) {
            this.values = values;
            this.value = value;
        }

        @Override
        public MemoryObject get(String path) throws Exception {
            if (!values.containsKey(path)) {
                throw new IllegalArgumentException("Missing path: " + path);
            }
            return new FakeMemoryObject(values, values.get(path));
        }

        @Override
        public MemoryObject get(MemoryRef ref) throws Exception {
            return get(ref.getPath());
        }

        @Override
        public MemoryRef set(String path, Object value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MemoryObject newObject(String path, boolean overwrite) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isExist(String path) {
            return values.containsKey(path);
        }

        @Override
        public List<String> getFieldNames() {
            return new ArrayList<>(values.keySet());
        }

        @Override
        public Map<String, Object> getFields() {
            return Collections.unmodifiableMap(values);
        }

        @Override
        public Object getValue() {
            return value;
        }

        @Override
        public boolean isNestedObject() {
            return value == null;
        }
    }

    @Test
    void processToolRequestInjectsArgsFromConfigBeforeDurableToolCall() throws Exception {
        FakeRunnerContext ctx = new FakeRunnerContext();

        Map<String, Object> arguments =
                new HashMap<>(Map.of("orderId", "order-1", "tenant_id", "model-tenant"));
        ToolRequestEvent event =
                new ToolRequestEvent(
                        "model",
                        List.of(
                                Map.of(
                                        "id",
                                        "call-1",
                                        "type",
                                        "function",
                                        "function",
                                        Map.of("name", "queryOrder", "arguments", arguments))));

        ToolCallAction.processToolRequest(event, ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getResponses().get("call-1").getResult()).isEqualTo("tenant-1:order-1");
        assertThat(arguments)
                .containsOnly(
                        Map.entry("orderId", "order-1"), Map.entry("tenant_id", "model-tenant"));
    }

    @Test
    void processToolRequestInjectsArgsFromSensoryMemory() throws Exception {
        FakeRunnerContext ctx =
                new FakeRunnerContext()
                        .withInjection(
                                ToolParameterInjection.fromSensoryMemory("request.tenant_id"))
                        .withSensoryMemory(Map.of("request.tenant_id", "tenant-sensory"));

        ToolCallAction.processToolRequest(toolRequest("queryOrder"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getResponses().get("call-1").getResult())
                .isEqualTo("tenant-sensory:order-1");
    }

    @Test
    void processToolRequestInjectsArgsFromShortTermMemory() throws Exception {
        FakeRunnerContext ctx =
                new FakeRunnerContext()
                        .withInjection(
                                ToolParameterInjection.fromShortTermMemory("session.tenant_id"))
                        .withShortTermMemory(Map.of("session.tenant_id", "tenant-short"));

        ToolCallAction.processToolRequest(toolRequest("queryOrder"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getResponses().get("call-1").getResult())
                .isEqualTo("tenant-short:order-1");
    }

    @Test
    void processToolRequestReportsMissingMemoryPath() throws Exception {
        FakeRunnerContext ctx =
                new FakeRunnerContext()
                        .withInjection(
                                ToolParameterInjection.fromSensoryMemory("request.tenant_id"))
                        .withSensoryMemory(Map.of());

        ToolCallAction.processToolRequest(toolRequest("queryOrder"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess()).containsEntry("call-1", false);
        assertThat(response.getError())
                .containsEntry(
                        "call-1",
                        "Missing memory path for injected tool parameter: request.tenant_id");
    }

    @Test
    void processToolRequestReportsNestedMemoryPath() throws Exception {
        FakeRunnerContext ctx =
                new FakeRunnerContext()
                        .withInjection(
                                ToolParameterInjection.fromSensoryMemory("request.tenant_id"))
                        .withSensoryMemory(Collections.singletonMap("request.tenant_id", null));

        ToolCallAction.processToolRequest(toolRequest("queryOrder"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess()).containsEntry("call-1", false);
        assertThat(response.getError())
                .containsEntry(
                        "call-1",
                        "Memory path for injected tool parameter must reference a value: request.tenant_id");
    }

    @Test
    void processToolRequestReportsUninitializedMemory() throws Exception {
        FakeRunnerContext ctx =
                new FakeRunnerContext()
                        .withInjection(
                                ToolParameterInjection.fromSensoryMemory("request.tenant_id"));

        ToolCallAction.processToolRequest(toolRequest("queryOrder"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess()).containsEntry("call-1", false);
        assertThat(response.getError())
                .containsEntry(
                        "call-1",
                        "Cannot inject tool parameter from sensory_memory because memory is not initialized.");
    }

    @Test
    void processToolRequestKeepsDiagnosticErrorWhenToolExecutionFails() throws Exception {
        FakeRunnerContext ctx =
                new FakeRunnerContext() {
                    @Override
                    public Resource getResource(String name, ResourceType type) {
                        return new FailingTool();
                    }
                };

        ToolCallAction.processToolRequest(toolRequest("queryOrder"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess()).containsEntry("call-1", false);
        assertThat(response.getResponses().get("call-1").getError())
                .isEqualTo("Tool queryOrder execute failed.");
        assertThat(response.getError()).containsEntry("call-1", "database timeout");
    }

    @Test
    void processToolRequestReturnsErrorForMissingTool() throws Exception {
        FakeRunnerContext ctx =
                new FakeRunnerContext() {
                    @Override
                    public Resource getResource(String name, ResourceType type) {
                        throw new RuntimeException("missing resource");
                    }
                };

        ToolCallAction.processToolRequest(toolRequest("queryOrder"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess()).containsEntry("call-1", false);
        assertThat(response.getResponses().get("call-1").getError())
                .isEqualTo("Tool queryOrder does not exist.");
        assertThat(response.getError()).containsEntry("call-1", "missing resource");
    }

    @Test
    void processToolRequestUsesParallelBatchForMultipleTools() throws Exception {
        FakeRunnerContext ctx = new FakeRunnerContext();

        ToolCallAction.processToolRequest(toolRequest("queryOrder", "call-1", "call-2"), ctx);

        assertThat(ctx.durableExecuteAllAsyncIds)
                .containsExactly(List.of("tool-call", "tool-call"));
        assertThat(ctx.durableExecuteAsyncIds).isEmpty();
        assertThat(ctx.durableExecuteIds).isEmpty();
        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getResponses().get("call-1").getResult()).isEqualTo("tenant-1:order-1");
        assertThat(response.getResponses().get("call-2").getResult()).isEqualTo("tenant-1:order-2");
    }

    @Test
    void processToolRequestUsesSerialAsyncWhenParallelDisabled() throws Exception {
        FakeRunnerContext ctx = new FakeRunnerContext().withToolCallParallel(false);

        ToolCallAction.processToolRequest(toolRequest("queryOrder", "call-1", "call-2"), ctx);

        assertThat(ctx.durableExecuteAllAsyncIds).isEmpty();
        assertThat(ctx.durableExecuteAsyncIds).containsExactly("tool-call", "tool-call");
        assertThat(ctx.durableExecuteIds).isEmpty();
    }

    @Test
    void processToolRequestUsesSyncWhenAsyncDisabled() throws Exception {
        FakeRunnerContext ctx = new FakeRunnerContext().withToolCallAsync(false);

        ToolCallAction.processToolRequest(toolRequest("queryOrder", "call-1", "call-2"), ctx);

        assertThat(ctx.durableExecuteAllAsyncIds).isEmpty();
        assertThat(ctx.durableExecuteAsyncIds).isEmpty();
        assertThat(ctx.durableExecuteIds).containsExactly("tool-call", "tool-call");
    }

    @Test
    void processToolRequestDoesNotBatchSingleTool() throws Exception {
        FakeRunnerContext ctx = new FakeRunnerContext();

        ToolCallAction.processToolRequest(toolRequest("queryOrder"), ctx);

        assertThat(ctx.durableExecuteAllAsyncIds).isEmpty();
        assertThat(ctx.durableExecuteAsyncIds).containsExactly("tool-call");
        assertThat(ctx.durableExecuteIds).isEmpty();
    }

    @Test
    void processToolRequestExcludesMissingToolFromParallelBatch() throws Exception {
        FakeRunnerContext ctx =
                new FakeRunnerContext() {
                    @Override
                    public Resource getResource(String name, ResourceType type) throws Exception {
                        if ("missingTool".equals(name)) {
                            throw new RuntimeException("missing resource");
                        }
                        return super.getResource(name, type);
                    }
                };

        ToolCallAction.processToolRequest(
                new ToolRequestEvent(
                        "model",
                        List.of(
                                toolCall("missingTool", "missing-call", "order-0"),
                                toolCall("queryOrder", "call-1", "order-1"),
                                toolCall("queryOrder", "call-2", "order-2"))),
                ctx);

        assertThat(ctx.durableExecuteAllAsyncIds)
                .containsExactly(List.of("tool-call", "tool-call"));
        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess()).containsEntry("missing-call", false);
        assertThat(response.getError()).containsEntry("missing-call", "missing resource");
    }

    @Test
    void processToolRequestRecordsOutcomeFailureAsToolError() throws Exception {
        FakeRunnerContext ctx =
                new FakeRunnerContext()
                        .withDurableExecuteAllAsyncOutcomes(
                                List.of(
                                        Outcome.success(ToolResponse.success("ok")),
                                        Outcome.failure(new RuntimeException("boom"))));

        ToolCallAction.processToolRequest(toolRequest("queryOrder", "call-1", "call-2"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess()).containsEntry("call-1", true);
        assertThat(response.getResponses().get("call-1").getResult()).isEqualTo("ok");
        assertThat(response.getSuccess()).containsEntry("call-2", false);
        assertThat(response.getResponses().get("call-2").getError())
                .isEqualTo("Tool queryOrder execute failed.");
        assertThat(response.getError()).containsEntry("call-2", "boom");
    }

    private static ToolRequestEvent toolRequest(String toolName) {
        return new ToolRequestEvent("model", List.of(toolCall(toolName, "call-1", "order-1")));
    }

    private static ToolRequestEvent toolRequest(String toolName, String... ids) {
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            toolCalls.add(toolCall(toolName, ids[i], "order-" + (i + 1)));
        }
        return new ToolRequestEvent("model", toolCalls);
    }

    private static Map<String, Object> toolCall(String toolName, String id, String orderId) {
        return Map.of(
                "id",
                id,
                "type",
                "function",
                "function",
                Map.of("name", toolName, "arguments", Map.of("orderId", orderId)));
    }
}
