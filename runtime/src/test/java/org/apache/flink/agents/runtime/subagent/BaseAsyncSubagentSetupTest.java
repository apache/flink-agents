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
import org.apache.flink.agents.api.subagent.Result;
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperator;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperatorFactory;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.typeutils.TypeExtractor;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The async-job base in pub/sub mode, exercised through the example {@link MockAsyncSubagentSetup}:
 * unit-style assertions of the durable POST and its crash-window reconciler, the await and fetch
 * compositions, failover replay equivalence, cancellation, and pipeline flows proving that the pub
 * POST lands immediately and the handle subscribes to the run.
 */
public class BaseAsyncSubagentSetupTest {

    private static final String RESOURCE_NAME = "ext-agent";

    // ------------------------------------------------------------------------------------------
    // The pub: one durable POST, issued immediately
    // ------------------------------------------------------------------------------------------

    @Test
    void submitRequestPostsImmediatelyWithoutQuerying() throws Exception {
        MockAsyncSubagentSetup setup = new MockAsyncSubagentSetup(2, false);

        DurableCallable<Void> callable = setup.submitRequestForTest("ping", "sid-1", "call-1");

        assertThat(callable.getId()).isEqualTo("sid-1#call-1");
        callable.call();

        assertThat(setup.postCount()).isEqualTo(1);
        assertThat(setup.statusQueryCount()).isZero();
        assertThat(setup.fetchCount()).isZero();
    }

    @Test
    void postFailureFailsTheSubmit() throws Exception {
        MockAsyncSubagentSetup setup = new MockAsyncSubagentSetup(2, true);

        DurableCallable<Void> callable = setup.submitRequestForTest("ping", "sid-1", "call-1");

        assertThatThrownBy(callable::call)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("post failed");
        assertThat(setup.statusQueryCount()).isZero();
        assertThat(setup.fetchCount()).isZero();
    }

    // ------------------------------------------------------------------------------------------
    // The crash-window reconciler of the POST
    // ------------------------------------------------------------------------------------------

    @Test
    void reconcilerRepostsWhenTheRunIsNotOnRecord() throws Exception {
        MockAsyncSubagentSetup setup = new MockAsyncSubagentSetup(1, false);

        setup.submitRequestForTest("ping", "sid-1", "call-1").reconciler().call();

        // Probe reported NOT_STARTED, so the missing POST was issued exactly once.
        assertThat(setup.postCount()).isEqualTo(1);
        assertThat(setup.statusQueryCount()).isEqualTo(1);
    }

    @Test
    void reconcilerDoesNotRepostARunningRun() throws Exception {
        MockAsyncSubagentSetup setup = new MockAsyncSubagentSetup(1, false);
        setup.seedRun("sid-1", "call-1", "done:ping", null, 1);

        setup.submitRequestForTest("ping", "sid-1", "call-1").reconciler().call();

        assertThat(setup.postCount()).isZero();
        assertThat(setup.statusQueryCount()).isEqualTo(1);
    }

    @Test
    void reconcilerDoesNotRepostATerminalRun() throws Exception {
        MockAsyncSubagentSetup setup = new MockAsyncSubagentSetup(0, false);
        setup.seedRun("sid-1", "call-1", "done:ping", null, 0);

        setup.submitRequestForTest("ping", "sid-1", "call-1").reconciler().call();

        assertThat(setup.postCount()).isZero();
        assertThat(setup.statusQueryCount()).isEqualTo(1);
    }

    @Test
    void reconcilerTreatsAFailedRunAsLanded() throws Exception {
        MockAsyncSubagentSetup setup = new MockAsyncSubagentSetup(0, false);
        setup.seedRun("sid-1", "call-1", null, "run exploded", 0);

        setup.submitRequestForTest("ping", "sid-1", "call-1").reconciler().call();

        assertThat(setup.postCount()).isZero();
        assertThat(setup.statusQueryCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------
    // The sub: await composition and direct fetch
    // ------------------------------------------------------------------------------------------

    @Test
    void awaitPollsUntilTerminalThenFetches() throws Exception {
        MockAsyncSubagentSetup setup = new MockAsyncSubagentSetup(2, false);
        setup.seedRun("sid-1", "call-1", "done:ping", null, 2);

        DurableCallable<Result> await = setup.awaitResultForTest("sid-1", "call-1");
        Result result = await.call();

        assertThat(await.getId()).isEqualTo("sid-1#call-1#await");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).isEqualTo("done:ping");
        // Two RUNNING probes, the terminal one, then the separate fetch.
        assertThat(setup.statusQueryCount()).isEqualTo(3);
        assertThat(setup.fetchCount()).isEqualTo(1);
    }

    @Test
    void awaitSurfacesAFailedRunWithoutFetching() throws Exception {
        MockAsyncSubagentSetup setup = new MockAsyncSubagentSetup(0, false);
        setup.seedRun("sid-1", "call-1", null, "run exploded", 0);

        Result result = setup.awaitResultForTest("sid-1", "call-1").call();

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("run exploded");
        assertThat(setup.fetchCount()).isZero();
    }

    @Test
    void fetchResultRecordsTheOutcomeUnderItsOwnId() throws Exception {
        MockAsyncSubagentSetup setup = new MockAsyncSubagentSetup(0, false);
        setup.seedRun("sid-1", "call-1", "done:ping", null, 0);

        DurableCallable<Result> fetch = setup.fetchResultForTest("sid-1", "call-1");
        Result result = fetch.call();

        assertThat(fetch.getId()).isEqualTo("sid-1#call-1#fetch");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).isEqualTo("done:ping");
        assertThat(setup.fetchCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------
    // Failover replay: fresh probes may take a different path to the same result
    // ------------------------------------------------------------------------------------------

    @Test
    void replayAfterTheRunCompletedTakesFewerProbes() throws Exception {
        // Original execution: the run completes only after two RUNNING probes.
        MockAsyncSubagentSetup original = new MockAsyncSubagentSetup(2, false);
        original.seedRun("sid-1", "call-1", "done:ping", null, 2);
        Result before = original.awaitResultForTest("sid-1", "call-1").call();
        assertThat(original.statusQueryCount()).isEqualTo(3);

        // Replay: the run has already reached a terminal state, so the same await takes a
        // shorter path — fewer probes — to the same result.
        MockAsyncSubagentSetup replay = new MockAsyncSubagentSetup(2, false);
        replay.seedRun("sid-1", "call-1", "done:ping", null, 0);
        Result after = replay.awaitResultForTest("sid-1", "call-1").call();

        assertThat(after.isSuccess()).isTrue();
        assertThat(after.getResult()).isEqualTo(before.getResult());
        assertThat(replay.statusQueryCount()).isEqualTo(1);
    }

    @Test
    void divergentReplayPathsProduceTheSameResult() throws Exception {
        MockAsyncSubagentSetup setup = new MockAsyncSubagentSetup(0, false);
        setup.seedRun("sid-1", "call-1", "done:ping", null, 0);

        // One replay waits through the await composition, another fetches the result directly:
        // different durable records, the same outcome.
        DurableCallable<Result> await = setup.awaitResultForTest("sid-1", "call-1");
        DurableCallable<Result> fetch = setup.fetchResultForTest("sid-1", "call-1");
        Result awaited = await.call();
        Result fetched = fetch.call();

        assertThat(await.getId()).isNotEqualTo(fetch.getId());
        assertThat(awaited.isSuccess()).isTrue();
        assertThat(fetched.isSuccess()).isTrue();
        assertThat(fetched.getResult()).isEqualTo(awaited.getResult());
    }

    // ------------------------------------------------------------------------------------------
    // Pipeline flows: pub lands immediately, the handle subscribes to the run
    // ------------------------------------------------------------------------------------------

    /** Submits through the short form and resolves directly, without probing isDone first. */
    @SuppressWarnings("unused")
    public static void delegated(Event event, RunnerContext ctx) throws Exception {
        MockAsyncSubagentSetup setup =
                (MockAsyncSubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        SubagentFuture future = setup.submit(ctx, "ping");
        Result result = future.await();
        ctx.sendEvent(new OutputEvent(result.getResult()));
    }

    @Test
    void pipelineSubmitsThenResolvesDirectly() throws Exception {
        MockAsyncSubagentSetup setup = new MockAsyncSubagentSetup(2, false);
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(plan("delegated", setup))) {
            harness.open();
            run(harness, 1L);

            assertThat(setup.postCount()).isEqualTo(1);
            assertThat(setup.fetchCount()).isEqualTo(1);
            assertThat(harness.getRecordOutput())
                    .singleElement()
                    .extracting(StreamRecord::getValue)
                    .isEqualTo("done:ping");
        }
    }

    /** Probes isDone until the run turns terminal, then resolves. */
    @SuppressWarnings("unused")
    public static void pollsThenResolves(Event event, RunnerContext ctx) throws Exception {
        MockAsyncSubagentSetup setup =
                (MockAsyncSubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        SubagentFuture future = setup.submit(ctx, "ping");
        int pendingProbes = 0;
        while (!future.isDone()) {
            pendingProbes++;
        }
        Result result = future.await();
        ctx.sendEvent(new OutputEvent("seen:" + pendingProbes + "|" + result.getResult()));
    }

    @Test
    void pipelineProbesStatusDirectlyUntilTerminal() throws Exception {
        MockAsyncSubagentSetup setup = new MockAsyncSubagentSetup(2, false);
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(plan("pollsThenResolves", setup))) {
            harness.open();
            run(harness, 1L);

            // Two RUNNING probes from isDone, the terminal probe and fetch from await.
            assertThat(setup.statusQueryCount()).isEqualTo(4);
            assertThat(setup.fetchCount()).isEqualTo(1);
            assertThat(harness.getRecordOutput())
                    .singleElement()
                    .extracting(StreamRecord::getValue)
                    .isEqualTo("seen:2|done:ping");
        }
    }

    /** Submits and fails when the POST endpoint rejects the run. */
    @SuppressWarnings("unused")
    public static void postFails(Event event, RunnerContext ctx) throws Exception {
        MockAsyncSubagentSetup setup =
                (MockAsyncSubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        setup.submit(ctx, "ping");
        ctx.sendEvent(new OutputEvent("unreachable"));
    }

    @Test
    void postFailureFailsTheAction() throws Exception {
        MockAsyncSubagentSetup setup = new MockAsyncSubagentSetup(2, true);
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(plan("postFails", setup))) {
            harness.open();

            assertThatThrownBy(() -> run(harness, 1L))
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("post failed");
            assertThat(harness.getRecordOutput()).isEmpty();
        }
    }

    /** Cancels after the pub and resolves: the default disposition is a CancellationException. */
    @SuppressWarnings("unused")
    public static void cancelsThenResolves(Event event, RunnerContext ctx) throws Exception {
        MockAsyncSubagentSetup setup =
                (MockAsyncSubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        SubagentFuture future = setup.submit(ctx, "ping");
        future.cancel();
        try {
            future.await();
            throw new IllegalStateException("cancelled handle resolved");
        } catch (CancellationException expected) {
            // The cancel hook returned nothing, so the resolve fails as cancelled.
        }
        ctx.sendEvent(new OutputEvent("cancelled"));
    }

    @Test
    void cancelBeforeResolveThrowsCancellationByDefault() throws Exception {
        MockAsyncSubagentSetup setup = new MockAsyncSubagentSetup(2, false);
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(plan("cancelsThenResolves", setup))) {
            harness.open();
            run(harness, 1L);

            // The pub landed, but the cancelled resolve never awaited nor fetched.
            assertThat(setup.postCount()).isEqualTo(1);
            assertThat(setup.cancelCount()).isEqualTo(1);
            assertThat(setup.statusQueryCount()).isZero();
            assertThat(setup.fetchCount()).isZero();
            assertThat(harness.getRecordOutput()).hasSize(1);
        }
    }

    /** Cancels twice: a repeated cancel on the same handle is a local no-op. */
    @SuppressWarnings("unused")
    public static void cancelsTwice(Event event, RunnerContext ctx) throws Exception {
        MockAsyncSubagentSetup setup =
                (MockAsyncSubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        SubagentFuture future = setup.submit(ctx, "ping");
        future.cancel();
        future.cancel();
        try {
            future.await();
            throw new IllegalStateException("cancelled handle resolved");
        } catch (CancellationException expected) {
            // Repeated cancellations are harmless; remote cancels are idempotent.
        }
        ctx.sendEvent(new OutputEvent("cancelled"));
    }

    @Test
    void repeatedCancelIsALocalNoOp() throws Exception {
        MockAsyncSubagentSetup setup = new MockAsyncSubagentSetup(2, false);
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(plan("cancelsTwice", setup))) {
            harness.open();
            run(harness, 1L);

            // The second cancel on the same handle does not propagate again; a failover
            // replay creates a fresh handle and may propagate again (idempotent remotely).
            assertThat(setup.cancelCount()).isEqualTo(1);
            assertThat(setup.fetchCount()).isZero();
            assertThat(harness.getRecordOutput()).hasSize(1);
        }
    }

    /** Continues a session: the second invocation reuses the first handle's session id. */
    @SuppressWarnings("unused")
    public static void continuesASession(Event event, RunnerContext ctx) throws Exception {
        MockAsyncSubagentSetup setup =
                (MockAsyncSubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        SubagentFuture first = setup.submit(ctx, "turn-1");
        Result firstOutcome = first.await();
        SubagentFuture second = setup.submit(ctx, "turn-2", first.getSessionId());
        Result secondOutcome = second.await();
        ctx.sendEvent(
                new OutputEvent(
                        first.getSessionId().equals(second.getSessionId())
                                + "|"
                                + !first.getCallId().equals(second.getCallId())
                                + "|"
                                + firstOutcome.getResult()
                                + "|"
                                + secondOutcome.getResult()));
    }

    @Test
    void multiTurnContinuationReusesTheSessionFromTheHandle() throws Exception {
        MockAsyncSubagentSetup setup = new MockAsyncSubagentSetup(0, false);
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(plan("continuesASession", setup))) {
            harness.open();
            run(harness, 1L);

            assertThat(setup.postCount()).isEqualTo(2);
            assertThat(harness.getRecordOutput())
                    .singleElement()
                    .extracting(StreamRecord::getValue)
                    .isEqualTo("true|true|done:turn-1|done:turn-2");
        }
    }

    /** Batches two open handles and resolves them together. */
    @SuppressWarnings("unused")
    public static void batchesOpenHandles(Event event, RunnerContext ctx) throws Exception {
        MockAsyncSubagentSetup setup =
                (MockAsyncSubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        SubagentFuture first = setup.submit(ctx, "a");
        SubagentFuture second = setup.submit(ctx, "b");
        List<Result> results = first.combine(second).awaitAll();
        ctx.sendEvent(
                new OutputEvent(results.get(0).getResult() + "|" + results.get(1).getResult()));
    }

    @Test
    void combineResolvesEveryOpenHandle() throws Exception {
        MockAsyncSubagentSetup setup = new MockAsyncSubagentSetup(0, false);
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(plan("batchesOpenHandles", setup))) {
            harness.open();
            run(harness, 1L);

            assertThat(setup.postCount()).isEqualTo(2);
            assertThat(setup.fetchCount()).isEqualTo(2);
            assertThat(harness.getRecordOutput())
                    .singleElement()
                    .extracting(StreamRecord::getValue)
                    .isEqualTo("done:a|done:b");
        }
    }

    /** Submits a handle and never resolves it. */
    @SuppressWarnings("unused")
    public static void dropsHandle(Event event, RunnerContext ctx) throws Exception {
        MockAsyncSubagentSetup setup =
                (MockAsyncSubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        setup.submit(ctx, "dropped");
        ctx.sendEvent(new OutputEvent("done"));
    }

    @Test
    void droppingAnOpenHandleFailsTheAction() throws Exception {
        MockAsyncSubagentSetup setup = new MockAsyncSubagentSetup(2, false);
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(plan("dropsHandle", setup))) {
            harness.open();

            // The pub landed at submit, but the open handle was dropped without collecting
            // its outcome: the base's per-task registry fails the action over it.
            assertThatThrownBy(() -> run(harness, 1L))
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("without resolving the sub-agent calls it submitted");
            assertThat(setup.postCount()).isEqualTo(1);
            assertThat(setup.fetchCount()).isZero();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Harness plumbing
    // ------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void run(
            KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness, long value)
            throws Exception {
        harness.processElement(new StreamRecord<>(value));
        ((ActionExecutionOperator<Long, Object>) harness.getOperator())
                .waitInFlightEventsFinished();
    }

    private static AgentPlan plan(String actionMethod, MockAsyncSubagentSetup setup)
            throws Exception {
        Agent agent = new Agent();
        agent.addResource(RESOURCE_NAME, ResourceType.AGENT, setup);
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                BaseAsyncSubagentSetupTest.class.getMethod(
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
