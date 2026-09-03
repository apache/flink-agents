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
import org.apache.flink.agents.api.configuration.ReadableConfiguration;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.Outcome;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.memory.BaseLongTermMemory;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentFutures;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for dispatching a tool call to an {@code AGENT} resource. */
class ToolCallActionSubagentTest {

    @Test
    void delegatesToTheSubagentAndReportsItsNormalizedResult() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("verdict", "approved");
        payload.put("findings", List.of("style"));
        RecordingSubagentSetup agent = new RecordingSubagentSetup(SubagentResult.ok(payload));
        FakeRunnerContext ctx = new FakeRunnerContext().withAgent("reviewer", agent);

        ToolCallAction.processToolRequest(toolRequest("subagent_reviewer"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess()).containsEntry("call-1", true);
        assertThat(response.getResponses().get("call-1").getResult())
                .isEqualTo("{\"verdict\":\"approved\",\"findings\":[\"style\"]}");
        assertThat(response.getError()).doesNotContainKey("call-1");
    }

    @Test
    void handsTheModelArgumentsToTheSubagentAsThePrompt() throws Exception {
        RecordingSubagentSetup agent = new RecordingSubagentSetup(SubagentResult.ok("done"));
        FakeRunnerContext ctx = new FakeRunnerContext().withAgent("reviewer", agent);

        ToolCallAction.processToolRequest(toolRequest("subagent_reviewer"), ctx);

        assertThat(agent.prompts).containsExactly(Map.of("prompt", "review the diff"));
        // A sub-agent call resolves through the setup, which owns its own durable execution.
        assertThat(ctx.durableExecutions).isZero();
    }

    @Test
    void reportsAFailedSubagentResultWithTheDetailExposedToTheModel() throws Exception {
        RecordingSubagentSetup agent =
                new RecordingSubagentSetup(SubagentResult.error("upstream refused"));
        FakeRunnerContext ctx = new FakeRunnerContext().withAgent("reviewer", agent);

        ToolCallAction.processToolRequest(toolRequest("subagent_reviewer"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess()).containsEntry("call-1", false);
        assertThat(response.getResponses().get("call-1").getError())
                .isEqualTo("Sub-agent subagent_reviewer execute failed: upstream refused");
        assertThat(response.getError()).containsEntry("call-1", "upstream refused");
    }

    @Test
    void reportsAFailureRaisedWhileSubmitting() throws Exception {
        RecordingSubagentSetup agent = new RecordingSubagentSetup(SubagentResult.ok("unreachable"));
        agent.submitFailure = new IllegalStateException("mailbox is full");
        FakeRunnerContext ctx = new FakeRunnerContext().withAgent("reviewer", agent);

        ToolCallAction.processToolRequest(toolRequest("subagent_reviewer"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess()).containsEntry("call-1", false);
        assertThat(response.getResponses().get("call-1").getError())
                .isEqualTo("Sub-agent subagent_reviewer execute failed: mailbox is full");
        assertThat(response.getError()).containsEntry("call-1", "mailbox is full");
    }

    @Test
    void rejectsAResultJsonCannotExpress() throws Exception {
        RecordingSubagentSetup agent =
                new RecordingSubagentSetup(SubagentResult.ok(Map.of("handle", new Object())));
        FakeRunnerContext ctx = new FakeRunnerContext().withAgent("reviewer", agent);

        ToolCallAction.processToolRequest(toolRequest("subagent_reviewer"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess()).containsEntry("call-1", false);
        assertThat(response.getResponses().get("call-1").getError())
                .startsWith("Sub-agent subagent_reviewer execute failed")
                .contains("result.handle");
        assertThat(response.getError().get("call-1")).contains("result.handle");
    }

    /** A declared result type is what admits a result JSON cannot express on its own. */
    @Test
    void readsAResultThroughTheTypeTheSubagentDeclares() throws Exception {
        RecordingSubagentSetup agent =
                new TypedRecordingSubagentSetup(SubagentResult.ok(new Verdict(true, "clean")));
        FakeRunnerContext ctx = new FakeRunnerContext().withAgent("reviewer", agent);

        ToolCallAction.processToolRequest(toolRequest("subagent_reviewer"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess()).containsEntry("call-1", true);
        assertThat(response.getResponses().get("call-1").getResult())
                .isEqualTo("{\"approved\":true,\"note\":\"clean\"}");
    }

    /** The reserved prefix routes each namespace on its own, even under one shared name. */
    @Test
    void routesAToolAndASubagentSharingANameToTheirOwnNamespace() throws Exception {
        FakeRunnerContext ctx =
                new FakeRunnerContext()
                        .withAgent(
                                "reviewer", new RecordingSubagentSetup(SubagentResult.ok("done")))
                        .withTool("reviewer", new StubTool("reviewer"));

        ToolCallAction.processToolRequest(toolRequest("subagent_reviewer"), ctx);

        ToolResponseEvent delegated = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(delegated.getSuccess()).containsEntry("call-1", true);
        assertThat(delegated.getResponses().get("call-1").getResult()).isEqualTo("done");
        // A sub-agent call resolves through the setup, which owns its own durable execution.
        assertThat(ctx.durableExecutions).isZero();

        ToolCallAction.processToolRequest(toolRequest("reviewer"), ctx);

        ToolResponseEvent direct = ToolResponseEvent.fromEvent(ctx.sentEvents.get(1));
        assertThat(direct.getSuccess()).containsEntry("call-1", true);
        assertThat(direct.getResponses().get("call-1").getResult()).isEqualTo("reviewer called");
        assertThat(ctx.durableExecutions).isOne();
    }

    @Test
    void refusesAnAgentResourceThatCarriesNoCallableSetup() throws Exception {
        FakeRunnerContext ctx = new FakeRunnerContext();
        ctx.agents.put("reviewer", new StubTool("reviewer"));

        ToolCallAction.processToolRequest(toolRequest("subagent_reviewer"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess()).containsEntry("call-1", false);
        assertThat(response.getResponses().get("call-1").getError())
                .isEqualTo(
                        "Sub-agent subagent_reviewer execute failed: Sub-agent reviewer must"
                                + " resolve to a SubagentSetup, but was "
                                + StubTool.class.getName()
                                + ".");
        assertThat(response.getError().get("call-1"))
                .isEqualTo(
                        "Sub-agent reviewer must resolve to a SubagentSetup, but was "
                                + StubTool.class.getName()
                                + ".");
    }

    @Test
    void stillDispatchesAToolWhenBothKindsAreRegisteredUnderDifferentNames() throws Exception {
        FakeRunnerContext ctx =
                new FakeRunnerContext()
                        .withAgent(
                                "reviewer", new RecordingSubagentSetup(SubagentResult.ok("done")))
                        .withTool("queryOrder", new StubTool("queryOrder"));

        ToolCallAction.processToolRequest(toolRequest("queryOrder"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess()).containsEntry("call-1", true);
        assertThat(response.getResponses().get("call-1").getResult())
                .isEqualTo("queryOrder called");
        assertThat(ctx.durableExecutions).isOne();
    }

    private static ToolRequestEvent toolRequest(String callableName) {
        return new ToolRequestEvent(
                "model",
                List.of(
                        Map.of(
                                "id",
                                "call-1",
                                "type",
                                "function",
                                "function",
                                Map.of(
                                        "name",
                                        callableName,
                                        "arguments",
                                        Map.of("prompt", "review the diff")))));
    }

    /** Captures every prompt it is handed and resolves to a preset outcome. */
    private static class RecordingSubagentSetup extends SubagentSetup {
        private final SubagentResult outcome;
        private final List<Object> prompts = new ArrayList<>();
        private Exception submitFailure;

        RecordingSubagentSetup(SubagentResult outcome) {
            super("Reviews a diff.");
            this.outcome = outcome;
        }

        @Override
        public SubagentFuture submit(RunnerContext ctx, Object prompt) throws Exception {
            return submit(ctx, prompt, "session", "call");
        }

        @Override
        public SubagentFuture submit(RunnerContext ctx, Object prompt, String sessionId)
                throws Exception {
            return submit(ctx, prompt, sessionId, "call");
        }

        @Override
        public SubagentFuture submit(
                RunnerContext ctx, Object prompt, String sessionId, String callId)
                throws Exception {
            if (submitFailure != null) {
                throw submitFailure;
            }
            prompts.add(prompt);
            return new ResolvedSubagentFuture(sessionId, callId, outcome);
        }
    }

    /** Declares a result type, so its result is read through it. */
    private static class TypedRecordingSubagentSetup extends RecordingSubagentSetup {
        TypedRecordingSubagentSetup(SubagentResult outcome) {
            super(outcome);
        }

        @Override
        public Class<?> getResultType() {
            return Verdict.class;
        }
    }

    /** The result {@link TypedRecordingSubagentSetup} declares. */
    public static class Verdict {
        private boolean approved;
        private String note;

        public Verdict() {}

        public Verdict(boolean approved, String note) {
            this.approved = approved;
            this.note = note;
        }

        public boolean isApproved() {
            return approved;
        }

        public void setApproved(boolean approved) {
            this.approved = approved;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }

    private static class ResolvedSubagentFuture extends SubagentFuture {
        private final SubagentResult outcome;

        ResolvedSubagentFuture(String sessionId, String callId, SubagentResult outcome) {
            super(sessionId, callId);
            this.outcome = outcome;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public SubagentResult await() {
            return outcome;
        }

        @Override
        public SubagentFutures combine(SubagentFuture... others) {
            throw new UnsupportedOperationException();
        }
    }

    private static class StubTool extends Tool {
        StubTool(String name) {
            super(new ToolMetadata(name, "Stub.", "{}"));
        }

        @Override
        public ToolType getToolType() {
            return ToolType.FUNCTION;
        }

        @Override
        public ToolResponse call(ToolParameters parameters) {
            return ToolResponse.success(getMetadata().getName() + " called");
        }
    }

    private static class FakeRunnerContext implements RunnerContext {
        private final List<Event> sentEvents = new ArrayList<>();
        private final Map<String, Resource> tools = new LinkedHashMap<>();
        private final Map<String, Resource> agents = new LinkedHashMap<>();
        private final AgentConfiguration config = new AgentConfiguration(Map.of());
        private int durableExecutions;

        FakeRunnerContext withTool(String name, Resource tool) {
            tools.put(name, tool);
            return this;
        }

        FakeRunnerContext withAgent(String name, SubagentSetup agent) {
            agents.put(name, agent);
            return this;
        }

        @Override
        public void sendEvent(Event event) {
            sentEvents.add(event);
        }

        @Override
        public MemoryObject getSensoryMemory() {
            return null;
        }

        @Override
        public MemoryObject getShortTermMemory() {
            return null;
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
            Map<String, Resource> registry = type == ResourceType.AGENT ? agents : tools;
            Resource resource = registry.get(name);
            if (resource == null) {
                throw new IllegalArgumentException("Resource does not exist: " + name);
            }
            return resource;
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
            durableExecutions++;
            return callable.call();
        }

        @Override
        public <T> T durableExecuteAsync(DurableCallable<T> callable) throws Exception {
            durableExecutions++;
            return callable.call();
        }

        @Override
        public <T> List<Outcome<T>> durableExecuteAllAsync(List<DurableCallable<T>> callables)
                throws Exception {
            List<Outcome<T>> outcomes = new ArrayList<>(callables.size());
            for (DurableCallable<T> callable : callables) {
                durableExecutions++;
                outcomes.add(Outcome.success(callable.call()));
            }
            return outcomes;
        }

        @Override
        public void close() {}
    }
}
