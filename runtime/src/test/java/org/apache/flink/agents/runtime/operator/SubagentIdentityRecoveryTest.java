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
package org.apache.flink.agents.runtime.operator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.Result;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.CallResult;
import org.apache.flink.agents.runtime.actionstate.InMemoryActionStateStore;
import org.apache.flink.agents.runtime.subagent.CapturingSubagentSetup;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failover-replay tests for deterministic sub-agent identity: replaying a record must recompute
 * identical ids from the namespace alone, and a persisted durable {@link CallResult} must turn the
 * replayed call into a cache hit instead of a re-execution.
 *
 * <p>The crash window under test (durable call persisted, action not yet complete) cannot be
 * produced by interrupting the synchronous harness, because a finished single-hop action is
 * immediately marked completed and replays without re-entering its body. Each test therefore learns
 * the assigned ids from a real stage-1 run, then hand-seeds a fresh store with a not-yet-completed
 * {@link ActionState} carrying the learned result (the same technique as {@code
 * ActionExecutionOperatorTest}) and re-processes the identical record; the identity context is
 * heap-only, so stage 2 recomputes ordinals from scratch.
 */
public class SubagentIdentityRecoveryTest {

    private static final String RESOURCE_NAME = "agent";

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void resetCaptures() {
        CapturingSubagentSetup.reset();
    }

    // Failover replay reproduces identical ids without duplicating side effects.

    @SuppressWarnings("unused")
    public static void singleCall(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup setup = (SubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        setup.call(ctx, "recover-me");
        ctx.sendEvent(new OutputEvent("done"));
    }

    @Test
    void it2FailoverReplayReproducesIdenticalIdsWithoutDuplicateSideEffects() throws Exception {
        long key = 11L;

        // Stage 1: learn the deterministically-assigned (sessionId, callId) from a real run
        // against a fresh, independent store.
        runToCompletion(buildSingleCallPlan(), new InMemoryActionStateStore(false), key);

        assertThat(CapturingSubagentSetup.captures()).hasSize(1);
        assertThat(CapturingSubagentSetup.executionCount()).isEqualTo(1);
        CapturingSubagentSetup.Capture firstRun = CapturingSubagentSetup.captures().get(0);

        // Stage 2: seed a *different*, fresh store to simulate a crash right after the durable
        // sub-agent call persisted its result but before the action as a whole completed. See
        // the class-level javadoc for why this must be constructed by hand.
        AgentPlan plan2 = buildSingleCallPlan();
        InMemoryActionStateStore store2 = new InMemoryActionStateStore(false);
        seedCompletedCallResults(
                store2,
                key,
                plan2,
                "singleCall",
                key,
                new CallResult(
                        firstRun.sessionId + "#" + firstRun.callId,
                        "",
                        OBJECT_MAPPER.writeValueAsBytes(Result.ok("cached"))));

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness2 =
                newHarness(plan2, store2)) {
            harness2.open();
            ActionExecutionOperator<Long, Object> operator2 =
                    (ActionExecutionOperator<Long, Object>) harness2.getOperator();

            harness2.processElement(new StreamRecord<>(key));
            operator2.waitInFlightEventsFinished();

            List<CapturingSubagentSetup.Capture> captures = CapturingSubagentSetup.captures();
            assertThat(captures).hasSize(2);
            assertThat(captures.get(1).sessionId).isEqualTo(captures.get(0).sessionId);
            assertThat(captures.get(1).callId).isEqualTo(captures.get(0).callId);

            assertThat(CapturingSubagentSetup.executionCount())
                    .as("Durable call must not be re-executed once its CallResult is persisted")
                    .isEqualTo(1);

            @SuppressWarnings("unchecked")
            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) harness2.getRecordOutput();
            assertThat(recordOutput).hasSize(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo("done");
        }
    }

    // Fan-out replay: callables replay in creation order without duplicating side effects.

    @SuppressWarnings("unused")
    public static void fanOut(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup setup = (SubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        DurableCallable<Result> c1 = setup.asAsyncCallable(ctx, "fan-a");
        DurableCallable<Result> c2 = setup.asAsyncCallable(ctx, "fan-b");
        ctx.durableExecuteAsync(c1);
        ctx.durableExecuteAsync(c2);
        ctx.sendEvent(new OutputEvent("done"));
    }

    @Test
    void it4FanOutReplayReproducesIdenticalIdsInOrderWithoutDuplicateSideEffects()
            throws Exception {
        long key = 6L;

        // Stage 1: learn both callables' deterministically-assigned ids from a real run.
        runToCompletion(buildFanOutPlan(), new InMemoryActionStateStore(false), key);

        assertThat(CapturingSubagentSetup.captures()).hasSize(2);
        assertThat(CapturingSubagentSetup.executionCount()).isEqualTo(2);
        CapturingSubagentSetup.Capture firstRunC1 = CapturingSubagentSetup.captures().get(0);
        CapturingSubagentSetup.Capture firstRunC2 = CapturingSubagentSetup.captures().get(1);

        // Stage 2: seed both callables' CallResults as already-persisted successes, simulating a
        // crash after both durable calls completed but before the action as a whole finished.
        AgentPlan plan2 = buildFanOutPlan();
        InMemoryActionStateStore store2 = new InMemoryActionStateStore(false);
        seedCompletedCallResults(
                store2,
                key,
                plan2,
                "fanOut",
                key,
                new CallResult(
                        firstRunC1.sessionId + "#" + firstRunC1.callId,
                        "",
                        OBJECT_MAPPER.writeValueAsBytes(Result.ok("cached-a"))),
                new CallResult(
                        firstRunC2.sessionId + "#" + firstRunC2.callId,
                        "",
                        OBJECT_MAPPER.writeValueAsBytes(Result.ok("cached-b"))));

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness2 =
                newHarness(plan2, store2)) {
            harness2.open();
            ActionExecutionOperator<Long, Object> operator2 =
                    (ActionExecutionOperator<Long, Object>) harness2.getOperator();

            harness2.processElement(new StreamRecord<>(key));
            operator2.waitInFlightEventsFinished();

            List<CapturingSubagentSetup.Capture> captures = CapturingSubagentSetup.captures();
            assertThat(captures).hasSize(4);

            // The replayed pair must reproduce the same two ids, in the same creation order, as
            // the first run.
            assertThat(captures.get(2).prompt).isEqualTo("fan-a");
            assertThat(captures.get(3).prompt).isEqualTo("fan-b");
            assertThat(captures.get(2).sessionId).isEqualTo(captures.get(0).sessionId);
            assertThat(captures.get(2).callId).isEqualTo(captures.get(0).callId);
            assertThat(captures.get(3).sessionId).isEqualTo(captures.get(1).sessionId);
            assertThat(captures.get(3).callId).isEqualTo(captures.get(1).callId);

            assertThat(CapturingSubagentSetup.executionCount())
                    .as(
                            "Fan-out durable calls must not be re-executed once their CallResults"
                                    + " are persisted")
                    .isEqualTo(2);

            @SuppressWarnings("unchecked")
            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) harness2.getRecordOutput();
            assertThat(recordOutput).hasSize(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo("done");
        }
    }

    // Helpers

    private static AgentPlan buildSingleCallPlan() throws Exception {
        Agent agent = new Agent();
        agent.addResource(RESOURCE_NAME, ResourceType.AGENT, new CapturingSubagentSetup());
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                SubagentIdentityRecoveryTest.class.getMethod(
                        "singleCall", Event.class, RunnerContext.class));
        return new AgentPlan(agent);
    }

    private static AgentPlan buildFanOutPlan() throws Exception {
        Agent agent = new Agent();
        agent.addResource(RESOURCE_NAME, ResourceType.AGENT, new CapturingSubagentSetup());
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                SubagentIdentityRecoveryTest.class.getMethod(
                        "fanOut", Event.class, RunnerContext.class));
        return new AgentPlan(agent);
    }

    private static KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> newHarness(
            AgentPlan plan, InMemoryActionStateStore actionStateStore) throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(
                new ActionExecutionOperatorFactory<>(plan, true, actionStateStore),
                (KeySelector<Long, Long>) value -> value,
                TypeInformation.of(Long.class));
    }

    /** Runs a single record through a fresh harness to completion, then closes the harness. */
    private static void runToCompletion(
            AgentPlan plan, InMemoryActionStateStore actionStateStore, long input)
            throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                newHarness(plan, actionStateStore)) {
            harness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) harness.getOperator();
            harness.processElement(new StreamRecord<>(input));
            operator.waitInFlightEventsFinished();
        }
    }

    /**
     * Seeds {@code actionStateStore} with an {@code ActionState} that is not yet completed but
     * already carries the given {@link CallResult}s, in the order the action creates its durable
     * callables. Mirrors the private helpers in {@code ActionExecutionOperatorTest}.
     */
    private static void seedCompletedCallResults(
            InMemoryActionStateStore actionStateStore,
            long key,
            AgentPlan agentPlan,
            String actionName,
            long input,
            CallResult... callResults)
            throws Exception {
        InputEvent event = new InputEvent(input);
        Action action = agentPlan.getActions().get(actionName);
        ActionState actionState = new ActionState(null);
        for (CallResult callResult : callResults) {
            actionState.addCallResult(callResult);
        }
        actionStateStore.put(key, 0L, action, event, actionState);
    }
}
