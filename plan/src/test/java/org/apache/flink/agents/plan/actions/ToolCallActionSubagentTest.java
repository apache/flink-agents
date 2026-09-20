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
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Tests for dispatching a tool call to an {@code AGENT} resource. */
class ToolCallActionSubagentTest {

    @Test
    void delegatesToTheSubagentAndReportsItsNormalizedResult() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("verdict", "approved");
        payload.put("findings", List.of("style"));
        RecordingSubagentSetup agent = new RecordingSubagentSetup(SubagentResult.ok(payload));
        FakeRunnerContext ctx = new FakeRunnerContext().withAgent("reviewer", agent);

        ToolCallAction.processToolRequest(toolRequest("_subagent_reviewer"), ctx);

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

        ToolCallAction.processToolRequest(toolRequest("_subagent_reviewer"), ctx);

        assertThat(agent.prompts).containsExactly(Map.of("prompt", "review the diff"));
        // A sub-agent call resolves through the setup, which owns its own durable execution.
        assertThat(ctx.durableExecutions).isZero();
    }

    @Test
    void reportsAFailedSubagentResultWithTheDetailExposedToTheModel() throws Exception {
        RecordingSubagentSetup agent =
                new RecordingSubagentSetup(SubagentResult.error("upstream refused"));
        FakeRunnerContext ctx = new FakeRunnerContext().withAgent("reviewer", agent);

        ToolCallAction.processToolRequest(toolRequest("_subagent_reviewer"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess()).containsEntry("call-1", false);
        assertThat(response.getResponses().get("call-1").getError())
                .isEqualTo("Sub-agent _subagent_reviewer execute failed: upstream refused");
        assertThat(response.getError()).containsEntry("call-1", "upstream refused");
    }

    @Test
    void reportsAFailureRaisedWhileSubmitting() throws Exception {
        RecordingSubagentSetup agent = new RecordingSubagentSetup(SubagentResult.ok("unreachable"));
        agent.submitFailure = new IllegalStateException("mailbox is full");
        FakeRunnerContext ctx = new FakeRunnerContext().withAgent("reviewer", agent);

        ToolCallAction.processToolRequest(toolRequest("_subagent_reviewer"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess()).containsEntry("call-1", false);
        assertThat(response.getResponses().get("call-1").getError())
                .isEqualTo("Sub-agent _subagent_reviewer execute failed: mailbox is full");
        assertThat(response.getError()).containsEntry("call-1", "mailbox is full");
    }

    @Test
    void rejectsAResultJsonCannotExpress() throws Exception {
        RecordingSubagentSetup agent =
                new RecordingSubagentSetup(SubagentResult.ok(Map.of("handle", new Object())));
        FakeRunnerContext ctx = new FakeRunnerContext().withAgent("reviewer", agent);

        ToolCallAction.processToolRequest(toolRequest("_subagent_reviewer"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess()).containsEntry("call-1", false);
        assertThat(response.getResponses().get("call-1").getError())
                .startsWith("Sub-agent _subagent_reviewer execute failed")
                .contains("result.handle");
        assertThat(response.getError().get("call-1")).contains("result.handle");
    }

    /** A declared result type is what admits a result JSON cannot express on its own. */
    @Test
    void readsAResultThroughTheTypeTheSubagentDeclares() throws Exception {
        RecordingSubagentSetup agent =
                new TypedRecordingSubagentSetup(SubagentResult.ok(new Verdict(true, "clean")));
        FakeRunnerContext ctx = new FakeRunnerContext().withAgent("reviewer", agent);

        ToolCallAction.processToolRequest(toolRequest("_subagent_reviewer"), ctx);

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

        ToolCallAction.processToolRequest(toolRequest("_subagent_reviewer"), ctx);

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

        ToolCallAction.processToolRequest(toolRequest("_subagent_reviewer"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess()).containsEntry("call-1", false);
        assertThat(response.getResponses().get("call-1").getError())
                .isEqualTo(
                        "Sub-agent _subagent_reviewer execute failed: Sub-agent reviewer must"
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

    /**
     * The batched path runs sub-agent calls concurrently: every call is submitted before any is
     * awaited, so the async setups' remote runs overlap instead of blocking one behind the next.
     * The serial path interleaves submit and await per call, which this order assertion rejects.
     */
    @Test
    void submitsEverySubagentCallBeforeAwaitingAnyUnderParallelDispatch() throws Exception {
        List<String> ops = new ArrayList<>();
        FakeRunnerContext ctx =
                new FakeRunnerContext()
                        .withParallelToolCalls()
                        .withAgent("a", new OrderRecordingSubagentSetup("a", ops))
                        .withAgent("b", new OrderRecordingSubagentSetup("b", ops));

        ToolCallAction.processToolRequest(twoSubagentRequest("a", "b"), ctx);

        assertThat(ops).containsExactly("submit:a", "submit:b", "await:a", "await:b");
        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess())
                .containsEntry("call-1", true)
                .containsEntry("call-2", true);
        assertThat(response.getResponses().get("call-1").getResult()).isEqualTo("a done");
        assertThat(response.getResponses().get("call-2").getResult()).isEqualTo("b done");
    }

    /**
     * A cancelled sub-agent call must propagate like a cancelled tool call (#1111), not be folded
     * into a tool-error response: no ToolResponseEvent goes out, so no further chat call is driven
     * off a cancelled delegation and the action is not persisted as completed on the back of it.
     */
    @Test
    void propagatesInterruptionFromASubagentCallInsteadOfRecordingAFailure() throws Exception {
        RecordingSubagentSetup agent = new RecordingSubagentSetup(SubagentResult.ok("unreachable"));
        agent.submitFailure = new InterruptedException("cancelled");
        FakeRunnerContext ctx = new FakeRunnerContext().withAgent("reviewer", agent);

        Thread.interrupted();

        assertThatExceptionOfType(InterruptedException.class)
                .isThrownBy(
                        () ->
                                ToolCallAction.processToolRequest(
                                        toolRequest("_subagent_reviewer"), ctx));

        assertThat(Thread.interrupted()).as("interrupt status should be restored").isTrue();
        assertThat(ctx.sentEvents).isEmpty();
    }

    /**
     * Under the batched path, a cancellation while awaiting one sub-agent must propagate (#1111)
     * and must not leave the other already-submitted handles dangling: the interrupted handle and
     * every later one, submitted but now never awaited, are cancelled on the way out.
     */
    @Test
    void propagatesInterruptionUnderParallelDispatchAndCancelsSubmittedHandles() throws Exception {
        List<String> ops = new ArrayList<>();
        FakeRunnerContext ctx =
                new FakeRunnerContext()
                        .withParallelToolCalls()
                        .withAgent("a", new InterruptingSubagentSetup("a", ops))
                        .withAgent("b", new OrderRecordingSubagentSetup("b", ops));

        Thread.interrupted();

        assertThatExceptionOfType(InterruptedException.class)
                .isThrownBy(
                        () -> ToolCallAction.processToolRequest(twoSubagentRequest("a", "b"), ctx));

        assertThat(Thread.interrupted()).as("interrupt status should be restored").isTrue();
        assertThat(ctx.sentEvents).isEmpty();
        assertThat(ops).containsExactly("submit:a", "submit:b", "await:a", "cancel:a", "cancel:b");
    }

    /**
     * Under the batched path, sub-agent calls split off from the tool batch and run on their own
     * track, then the tool batch runs through {@code durableExecuteAllAsync} against a list built
     * alongside {@code toolExecutions}. Each call id must land on its own result: if the two lists
     * drift against each other, one call's response ends up under another call's id, and neither a
     * sub-agent-only nor a tool-only case can see it. Two tools keep the reverse-index shape of the
     * drift observable.
     */
    @Test
    void parallelDispatchKeepsToolAndSubagentResultsOnTheirOwnIds() throws Exception {
        FakeRunnerContext ctx =
                new FakeRunnerContext()
                        .withParallelToolCalls()
                        .withAgent(
                                "reviewer",
                                new RecordingSubagentSetup(SubagentResult.ok("agent-result")))
                        .withTool("alpha", new StubTool("alpha"))
                        .withTool("beta", new StubTool("beta"));

        ToolCallAction.processToolRequest(mixedRequest("reviewer", "alpha", "beta"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess())
                .containsEntry("call-1", true)
                .containsEntry("call-2", true)
                .containsEntry("call-3", true);
        assertThat(response.getResponses().get("call-1").getResult()).isEqualTo("agent-result");
        assertThat(response.getResponses().get("call-2").getResult()).isEqualTo("alpha called");
        assertThat(response.getResponses().get("call-3").getResult()).isEqualTo("beta called");
    }

    /**
     * A sub-agent that returns a result reaching back into itself cannot be normalized to JSON, and
     * the walk used to recurse until the stack gave out. A {@link StackOverflowError} is an {@link
     * Error}, so it slipped past the {@code catch (Exception)} that reports a rejected result and
     * failed the whole job. The cycle is now reported while the walk still can, so it lands as a
     * failed delegation the model can see, exactly like any other result JSON cannot express.
     */
    @Test
    void rejectsACyclicResultAsAFailedDelegation() throws Exception {
        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("self", cyclic);
        RecordingSubagentSetup agent = new RecordingSubagentSetup(SubagentResult.ok(cyclic));
        FakeRunnerContext ctx = new FakeRunnerContext().withAgent("reviewer", agent);

        ToolCallAction.processToolRequest(toolRequest("_subagent_reviewer"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess()).containsEntry("call-1", false);
        assertThat(response.getResponses().get("call-1").getError())
                .startsWith("Sub-agent _subagent_reviewer execute failed")
                .contains("cycle detected");
        assertThat(response.getError().get("call-1")).contains("cycle detected");
    }

    /**
     * A {@link StackOverflowError} raised while a single sub-agent call is handled must be absorbed
     * into a failed delegation, not left to escape as an {@link Error} that fails the job. The
     * result walk refuses a cycle before it can overflow, so what reaches here is a result nested
     * deeper than the stack allows; the double injects that directly, since one deep enough to
     * overflow a real stack is impractical to build.
     */
    @Test
    void absorbsAStackOverflowFromASubagentCallInsteadOfFailingTheJob() throws Exception {
        FakeRunnerContext ctx =
                new FakeRunnerContext().withAgent("reviewer", new OverflowingSubagentSetup());

        ToolCallAction.processToolRequest(toolRequest("_subagent_reviewer"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess()).containsEntry("call-1", false);
        assertThat(response.getResponses().get("call-1").getError())
                .startsWith("Sub-agent _subagent_reviewer execute failed");
    }

    /**
     * The batched path awaits each submitted handle in its own try, so an overflow while awaiting
     * one sub-agent is absorbed into that call's failed delegation and the rest are still awaited,
     * rather than escaping as an {@link Error} that fails the job mid-batch.
     */
    @Test
    void absorbsAStackOverflowUnderParallelDispatchInsteadOfFailingTheJob() throws Exception {
        FakeRunnerContext ctx =
                new FakeRunnerContext()
                        .withParallelToolCalls()
                        .withAgent("a", new OverflowingSubagentSetup())
                        .withAgent("b", new OverflowingSubagentSetup());

        ToolCallAction.processToolRequest(twoSubagentRequest("a", "b"), ctx);

        ToolResponseEvent response = ToolResponseEvent.fromEvent(ctx.sentEvents.get(0));
        assertThat(response.getSuccess())
                .containsEntry("call-1", false)
                .containsEntry("call-2", false);
        assertThat(response.getResponses().get("call-1").getError())
                .startsWith("Sub-agent _subagent_a execute failed");
        assertThat(response.getResponses().get("call-2").getError())
                .startsWith("Sub-agent _subagent_b execute failed");
    }

    /**
     * A resolved delegation is reported under the sub-agent scope keyed by the registered agent
     * name -- not the reserved callable name and not the tool scope -- and carries a start
     * occurrence, so the runtime attributes a latency window to it instead of bucketing it as an
     * unknown tool with no latency.
     */
    @Test
    void reportsAResolvedSubagentDelegationUnderTheSubagentScope() throws Exception {
        RecordingSubagentSetup agent = new RecordingSubagentSetup(SubagentResult.ok("done"));
        FakeRunnerContext ctx = new FakeRunnerContext().withAgent("reviewer", agent);

        ToolCallAction.processToolRequest(toolRequest("_subagent_reviewer"), ctx);

        assertThat(ctx.reports)
                .extracting(report -> report.phase)
                .containsExactly("created", "started", "succeeded");
        assertThat(ctx.reports)
                .allSatisfy(
                        report -> {
                            assertThat(report.entityType)
                                    .isEqualTo(ExecutionReporter.EntityTypes.SUBAGENT);
                            assertThat(report.entityName).isEqualTo("reviewer");
                        });
        ExecutionReport started = ctx.reports.get(1);
        ExecutionReport succeeded = ctx.reports.get(2);
        assertThat(started.timestamp).isNotNull();
        assertThat(succeeded.timestamp).isNotNull();
        assertThat(Instant.parse(succeeded.timestamp))
                .isAfterOrEqualTo(Instant.parse(started.timestamp));
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

    /** One request carrying two sub-agent calls, so the batched path has more than one to run. */
    private static ToolRequestEvent twoSubagentRequest(String first, String second) {
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
                                        SubagentSetup.CALLABLE_NAME_PREFIX + first,
                                        "arguments",
                                        Map.of("prompt", "review the diff"))),
                        Map.of(
                                "id",
                                "call-2",
                                "type",
                                "function",
                                "function",
                                Map.of(
                                        "name",
                                        SubagentSetup.CALLABLE_NAME_PREFIX + second,
                                        "arguments",
                                        Map.of("prompt", "review the diff")))));
    }

    /**
     * One request carrying a sub-agent call and two plain tool calls, so the batched path splits
     * into a single-entry agent track and a two-entry tool track, and the tool track has more than
     * one entry to keep in order against the outcomes list.
     */
    private static ToolRequestEvent mixedRequest(
            String subagentName, String firstTool, String secondTool) {
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
                                        SubagentSetup.CALLABLE_NAME_PREFIX + subagentName,
                                        "arguments",
                                        Map.of("prompt", "review the diff"))),
                        Map.of(
                                "id",
                                "call-2",
                                "type",
                                "function",
                                "function",
                                Map.of("name", firstTool, "arguments", Map.of("q", "x"))),
                        Map.of(
                                "id",
                                "call-3",
                                "type",
                                "function",
                                "function",
                                Map.of("name", secondTool, "arguments", Map.of("q", "y")))));
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

    /** Records the order in which submit and await happen, into a log shared across sub-agents. */
    private static class OrderRecordingSubagentSetup extends SubagentSetup {
        private final String label;
        private final List<String> ops;

        OrderRecordingSubagentSetup(String label, List<String> ops) {
            super("Orders a diff.");
            this.label = label;
            this.ops = ops;
        }

        @Override
        public SubagentFuture submit(RunnerContext ctx, Object prompt) {
            ops.add("submit:" + label);
            return new OrderRecordingFuture(label, ops);
        }

        @Override
        public SubagentFuture submit(RunnerContext ctx, Object prompt, String sessionId) {
            return submit(ctx, prompt);
        }

        @Override
        public SubagentFuture submit(
                RunnerContext ctx, Object prompt, String sessionId, String callId) {
            return submit(ctx, prompt);
        }
    }

    /** Records its await into the shared log, then resolves to a fixed success. */
    private static class OrderRecordingFuture extends SubagentFuture {
        private final String label;
        private final List<String> ops;

        OrderRecordingFuture(String label, List<String> ops) {
            super("session-" + label, "call-" + label);
            this.label = label;
            this.ops = ops;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public SubagentResult await() {
            ops.add("await:" + label);
            return SubagentResult.ok(label + " done");
        }

        @Override
        public void cancel() {
            ops.add("cancel:" + label);
        }

        @Override
        public SubagentFutures combine(SubagentFuture... others) {
            throw new UnsupportedOperationException();
        }
    }

    /** Its await reports a cancellation, to drive the interruption path under parallel dispatch. */
    private static class InterruptingSubagentSetup extends SubagentSetup {
        private final String label;
        private final List<String> ops;

        InterruptingSubagentSetup(String label, List<String> ops) {
            super("Interrupts on await.");
            this.label = label;
            this.ops = ops;
        }

        @Override
        public SubagentFuture submit(RunnerContext ctx, Object prompt) {
            ops.add("submit:" + label);
            return new InterruptingFuture(label, ops);
        }

        @Override
        public SubagentFuture submit(RunnerContext ctx, Object prompt, String sessionId) {
            return submit(ctx, prompt);
        }

        @Override
        public SubagentFuture submit(
                RunnerContext ctx, Object prompt, String sessionId, String callId) {
            return submit(ctx, prompt);
        }
    }

    /** Records its await into the shared log, then reports a cancellation instead of resolving. */
    private static class InterruptingFuture extends SubagentFuture {
        private final String label;
        private final List<String> ops;

        InterruptingFuture(String label, List<String> ops) {
            super("session-" + label, "call-" + label);
            this.label = label;
            this.ops = ops;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public SubagentResult await() throws InterruptedException {
            ops.add("await:" + label);
            throw new InterruptedException("cancelled");
        }

        @Override
        public void cancel() {
            ops.add("cancel:" + label);
        }

        @Override
        public SubagentFutures combine(SubagentFuture... others) {
            throw new UnsupportedOperationException();
        }
    }

    /** Its await overflows the stack, to drive the {@link StackOverflowError} absorption path. */
    private static class OverflowingSubagentSetup extends SubagentSetup {
        OverflowingSubagentSetup() {
            super("Overflows on await.");
        }

        @Override
        public SubagentFuture submit(RunnerContext ctx, Object prompt) {
            return new OverflowingFuture();
        }

        @Override
        public SubagentFuture submit(RunnerContext ctx, Object prompt, String sessionId) {
            return submit(ctx, prompt);
        }

        @Override
        public SubagentFuture submit(
                RunnerContext ctx, Object prompt, String sessionId, String callId) {
            return submit(ctx, prompt);
        }
    }

    /**
     * Reports an overflow instead of resolving. A {@link StackOverflowError} is an {@link Error},
     * so {@code await} needs no throws clause to raise it, and it stands in for an overflow the
     * result walk reaches on a result nested deeper than the stack allows.
     */
    private static class OverflowingFuture extends SubagentFuture {
        OverflowingFuture() {
            super("session", "call");
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public SubagentResult await() {
            throw new StackOverflowError("result normalization recursed without end");
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

    private static class FakeRunnerContext implements RunnerContext, ExecutionReporter {
        private final List<Event> sentEvents = new ArrayList<>();
        private final List<ExecutionReport> reports = new ArrayList<>();
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

        /** Turns on the batched path: async tool calls with room to run more than one. */
        FakeRunnerContext withParallelToolCalls() {
            config.set(AgentExecutionOptions.TOOL_CALL_ASYNC, true);
            config.set(AgentExecutionOptions.TOOL_CALL_PARALLELISM, 2);
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
        public void reportExecutionCreated(
                String entityType, String entityName, Map<String, Object> entityMetadata) {
            reports.add(new ExecutionReport("created", entityType, entityName, null));
        }

        @Override
        public void reportExecutionStarted(
                String entityType, String entityName, Map<String, Object> entityMetadata) {
            reports.add(new ExecutionReport("started", entityType, entityName, null));
        }

        @Override
        public void reportExecutionStartedAt(
                String entityType,
                String entityName,
                Map<String, Object> entityMetadata,
                String timestamp) {
            reports.add(new ExecutionReport("started", entityType, entityName, timestamp));
        }

        @Override
        public void reportExecutionSucceeded(
                String entityType, String entityName, Map<String, Object> entityMetadata) {
            reports.add(new ExecutionReport("succeeded", entityType, entityName, null));
        }

        @Override
        public void reportExecutionSucceededAt(
                String entityType,
                String entityName,
                Map<String, Object> entityMetadata,
                String timestamp) {
            reports.add(new ExecutionReport("succeeded", entityType, entityName, timestamp));
        }

        @Override
        public void reportExecutionFailed(
                String entityType,
                String entityName,
                Map<String, Object> entityMetadata,
                Throwable error,
                String problemCategory) {
            reports.add(new ExecutionReport("failed", entityType, entityName, null));
        }

        @Override
        public void reportExecutionFailedAt(
                String entityType,
                String entityName,
                Map<String, Object> entityMetadata,
                Throwable error,
                String problemCategory,
                String timestamp) {
            reports.add(new ExecutionReport("failed", entityType, entityName, timestamp));
        }

        @Override
        public void close() {}
    }

    /** One lifecycle report captured by {@link FakeRunnerContext}, for scope assertions. */
    private static final class ExecutionReport {
        private final String phase;
        private final String entityType;
        private final String entityName;
        private final String timestamp;

        private ExecutionReport(
                String phase, String entityType, String entityName, String timestamp) {
            this.phase = phase;
            this.entityType = entityType;
            this.entityName = entityName;
            this.timestamp = timestamp;
        }
    }
}
