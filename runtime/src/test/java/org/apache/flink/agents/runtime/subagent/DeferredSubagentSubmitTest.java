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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperator;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperatorFactory;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.typeutils.TypeExtractor;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The deferred execution mode of {@link BaseDeferredSubagentSetup}: submit only registers the
 * invocation, the request is issued when the handle is resolved, several handles can be resolved
 * together, and a dropped handle fails the action through the base's per-task registry. Ids are
 * supplied explicitly by the caller actions.
 */
public class DeferredSubagentSubmitTest {

    private static final String RESOURCE_NAME = "capturing";

    private final RunnerContext ctx = null;

    @BeforeEach
    public void resetCaptures() {
        MockDeferredSubagentSetup.reset();
    }

    // The short forms inherit the base's deterministic assignment, which needs a prepared task.

    @Test
    void shortFormsRequireAPreparedTask() throws Exception {
        BaseDeferredSubagentSetup setup = new MockDeferredSubagentSetup();

        assertThrows(IllegalStateException.class, () -> setup.submit(ctx, "ping", "sid-1"));
        assertThrows(IllegalStateException.class, () -> setup.submit(ctx, "ping"));
    }

    // Batched resolve: every pending deferred handle is prepared up front, then the prepared
    // calls are executed one by one in submission order.

    @SuppressWarnings("unused")
    public static void batched(Event event, RunnerContext ctx) throws Exception {
        BaseDeferredSubagentSetup setup =
                (BaseDeferredSubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        SubagentFuture first = setup.submit(ctx, "batch-a", "session", "call-1");
        SubagentFuture second = setup.submit(ctx, "batch-b", "session", "call-2");
        // Nothing has been issued yet: submit only created the deferred handles.
        if (!MockDeferredSubagentSetup.captures().isEmpty()) {
            throw new IllegalStateException(
                    "deferred submit issued the request too early: "
                            + MockDeferredSubagentSetup.captures());
        }
        List<SubagentResult> results = first.combine(second).awaitAll();
        ctx.sendEvent(
                new OutputEvent(results.get(0).getResult() + "|" + results.get(1).getResult()));
    }

    @Test
    void batchedResolveResolvesEveryHandleInSubmissionOrder() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(plan("batched", new MockDeferredSubagentSetup()))) {
            harness.open();
            run(harness, 1L);

            assertThat(MockDeferredSubagentSetup.captures()).hasSize(2);
            assertThat(MockDeferredSubagentSetup.executionCount()).isEqualTo(2);
            assertThat(harness.getRecordOutput()).hasSize(1);
        }
    }

    // A dropped handle fails the action instead of silently skipping the call: the base records
    // every handle in its per-task registry and checks it when the task finishes.

    @SuppressWarnings("unused")
    public static void dropsHandle(Event event, RunnerContext ctx) throws Exception {
        BaseDeferredSubagentSetup setup =
                (BaseDeferredSubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        setup.submit(ctx, "dropped", "session", "call-1");
        ctx.sendEvent(new OutputEvent("done"));
    }

    @Test
    void droppingADeferredHandleFailsTheAction() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(plan("dropsHandle", new MockDeferredSubagentSetup()))) {
            harness.open();

            assertThatThrownBy(() -> run(harness, 1L))
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("without resolving the sub-agent calls it submitted");
            assertThat(MockDeferredSubagentSetup.executionCount()).isZero();
        }
    }

    // An already-resolved handle simply contributes its value to the batch.

    @SuppressWarnings("unused")
    public static void batchesResolvedHandle(Event event, RunnerContext ctx) throws Exception {
        BaseDeferredSubagentSetup setup =
                (BaseDeferredSubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        SubagentFuture resolved = new CompletedSubagentFuture("s", "c", SubagentResult.ok("x"));
        SubagentFuture pending = setup.submit(ctx, "pending", "session", "call-1");
        List<SubagentResult> results = resolved.combine(pending).awaitAll();
        ctx.sendEvent(
                new OutputEvent(results.get(0).getResult() + "|" + results.get(1).getResult()));
    }

    @Test
    void batchingAnAlreadyResolvedHandleJoinsTheBatch() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(plan("batchesResolvedHandle", new MockDeferredSubagentSetup()))) {
            harness.open();
            run(harness, 1L);

            // Only the pending handle issued a request; the resolved one contributed its value.
            assertThat(MockDeferredSubagentSetup.captures()).hasSize(1);
            assertThat(MockDeferredSubagentSetup.executionCount()).isEqualTo(1);
            assertThat(harness.getRecordOutput()).hasSize(1);
        }
    }

    // Cancellation: the request was never issued, so cancelling discards it and resolving the
    // handle fails with a CancellationException.

    @SuppressWarnings("unused")
    public static void cancelsBeforeResolve(Event event, RunnerContext ctx) throws Exception {
        BaseDeferredSubagentSetup setup =
                (BaseDeferredSubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        SubagentFuture handle = setup.submit(ctx, "cancelled", "session", "call-1");
        handle.cancel();
        if (!MockDeferredSubagentSetup.captures().isEmpty()) {
            throw new IllegalStateException("cancelled handle created its callable");
        }
        try {
            handle.await();
            throw new IllegalStateException("cancelled handle resolved");
        } catch (CancellationException expected) {
            // The request was never issued; cancellation fails the resolve.
        }
        ctx.sendEvent(new OutputEvent("cancelled"));
    }

    @Test
    void cancellingBeforeResolveDiscardsTheRequest() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(plan("cancelsBeforeResolve", new MockDeferredSubagentSetup()))) {
            harness.open();
            run(harness, 1L);

            assertThat(MockDeferredSubagentSetup.captures()).isEmpty();
            assertThat(MockDeferredSubagentSetup.executionCount()).isZero();
            assertThat(harness.getRecordOutput()).hasSize(1);
        }
    }

    @SuppressWarnings("unused")
    public static void cancelsThroughTheGroup(Event event, RunnerContext ctx) throws Exception {
        BaseDeferredSubagentSetup setup =
                (BaseDeferredSubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        SubagentFuture first = setup.submit(ctx, "batch-a", "session", "call-1");
        SubagentFuture second = setup.submit(ctx, "batch-b", "session", "call-2");
        first.combine(second).cancel();
        try {
            first.combine(second).awaitAll();
            throw new IllegalStateException("cancelled batch resolved");
        } catch (CancellationException expected) {
            // Every handle in the batch received the cancellation.
        }
        ctx.sendEvent(new OutputEvent("cancelled"));
    }

    @Test
    void cancellingThroughTheGroupPropagatesToEveryHandle() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(plan("cancelsThroughTheGroup", new MockDeferredSubagentSetup()))) {
            harness.open();
            run(harness, 1L);

            assertThat(MockDeferredSubagentSetup.captures()).isEmpty();
            assertThat(MockDeferredSubagentSetup.executionCount()).isZero();
            assertThat(harness.getRecordOutput()).hasSize(1);
        }
    }

    // A cancelled handle unregisters from the base's per-task registry, so the built-in check
    // does not fail the action over it.

    @SuppressWarnings("unused")
    public static void cancelsTrackedHandle(Event event, RunnerContext ctx) throws Exception {
        BaseDeferredSubagentSetup setup =
                (BaseDeferredSubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        setup.submit(ctx, "cancelled", "session", "call-1").cancel();
        ctx.sendEvent(new OutputEvent("done"));
    }

    @Test
    void cancellingATrackedHandleDoesNotFailTheAction() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(plan("cancelsTrackedHandle", new MockDeferredSubagentSetup()))) {
            harness.open();
            run(harness, 1L);

            assertThat(MockDeferredSubagentSetup.executionCount()).isZero();
            assertThat(harness.getRecordOutput()).hasSize(1);
        }
    }

    // A system-level failure escaping the prepared callable propagates and fails the action
    // instead of being folded into an error result.

    @SuppressWarnings("unused")
    public static void awaitsSystemFailingHandle(Event event, RunnerContext ctx) throws Exception {
        BaseDeferredSubagentSetup setup =
                (BaseDeferredSubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        setup.submit(ctx, "boom", "session", "call-1").await();
        ctx.sendEvent(new OutputEvent("done"));
    }

    @Test
    void systemLevelFailurePropagatesInsteadOfFolding() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(plan("awaitsSystemFailingHandle", new ThrowingDeferredSubagentSetup()))) {
            harness.open();

            assertThatThrownBy(() -> run(harness, 1L))
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("durable execution crashed");
            assertThat(harness.getRecordOutput()).isEmpty();
        }
    }

    /** Deferred setup whose prepared callable throws a system-level failure instead of folding. */
    private static final class ThrowingDeferredSubagentSetup extends BaseDeferredSubagentSetup {
        @Override
        protected DurableCallable<SubagentResult> prepare(
                RunnerContext ctx, Object prompt, String sessionId, String callId) {
            return new DurableCallable<SubagentResult>() {
                @Override
                public String getId() {
                    return sessionId + "#" + callId;
                }

                @Override
                public Class<SubagentResult> getResultClass() {
                    return SubagentResult.class;
                }

                @Override
                public SubagentResult call() {
                    throw new IllegalStateException("durable execution crashed");
                }
            };
        }
    }

    @SuppressWarnings("unchecked")
    private static void run(
            KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness, long value)
            throws Exception {
        harness.processElement(new StreamRecord<>(value));
        ((ActionExecutionOperator<Long, Object>) harness.getOperator())
                .waitInFlightEventsFinished();
    }

    private static AgentPlan plan(String actionMethod, BaseDeferredSubagentSetup setup)
            throws Exception {
        Agent agent = new Agent();
        agent.addResource(RESOURCE_NAME, ResourceType.AGENT, setup);
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                DeferredSubagentSubmitTest.class.getMethod(
                        actionMethod, Event.class, RunnerContext.class));
        return new AgentPlan(agent, new AgentConfiguration());
    }

    private static KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness(
            AgentPlan plan) throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(
                new ActionExecutionOperatorFactory<>(plan, true),
                (KeySelector<Long, Long>) value -> value,
                TypeExtractor.getForClass(Long.class));
    }
}
