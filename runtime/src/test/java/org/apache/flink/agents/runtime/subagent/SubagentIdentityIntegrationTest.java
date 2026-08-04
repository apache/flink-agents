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
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperator;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperatorFactory;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Operator-harness tests for deterministic sub-agent identity assignment: same-round determinism
 * and uniqueness, sibling-task separation, and fan-out ordering. {@link CapturingSubagentSetup}
 * records the {@code (sessionId, callId)} pair handed to every produced callable. The
 * failover-replay counterpart lives in {@code SubagentIdentityRecoveryTest}.
 */
public class SubagentIdentityIntegrationTest {

    private static final String RESOURCE_NAME = "agent";
    private static final String EXPLICIT_SESSION_ID = "explicit-session-checkout-1";

    @BeforeEach
    void resetCaptures() {
        CapturingSubagentSetup.reset();
    }

    // Same-round determinism and uniqueness.

    @SuppressWarnings("unused")
    public static void mixedCalls(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup setup = (SubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        setup.call(ctx, "p1");
        setup.call(ctx, "p2");
        setup.call(ctx, "p3a", EXPLICIT_SESSION_ID);
        setup.call(ctx, "p3b", EXPLICIT_SESSION_ID);
        ctx.sendEvent(new OutputEvent("done"));
    }

    @Test
    void it1MixedCallsProduceUniqueDeterministicIds() throws Exception {
        List<CapturingSubagentSetup.Capture> captures = runMixedCallsScenario(1L);
        assertThat(captures).hasSize(4);

        String autoSession1 = captures.get(0).sessionId;
        String autoSession2 = captures.get(1).sessionId;
        String explicitSessionA = captures.get(2).sessionId;
        String explicitSessionB = captures.get(3).sessionId;

        // The two auto-assigned sessions follow the runtime's session id shape (a fixed-length
        // name-UUID namespace plus the session ordinal) and are distinct.
        assertThat(autoSession1)
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-0");
        assertThat(autoSession2)
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-1");

        // An explicit session id is used verbatim, not reshaped into the auto-assigned form.
        assertThat(explicitSessionA).isEqualTo(EXPLICIT_SESSION_ID);
        assertThat(explicitSessionB).isEqualTo(EXPLICIT_SESSION_ID);

        // All four call ids are globally distinct.
        List<String> callIds = captures.stream().map(c -> c.callId).collect(Collectors.toList());
        assertThat(callIds).doesNotHaveDuplicates();

        // A call id is its session id plus the per-session ordinal (1, then 2 for the two
        // explicit-session calls; 1 for each auto session's single call).
        assertThat(captures.get(0).callId).isEqualTo(autoSession1 + "-1");
        assertThat(captures.get(1).callId).isEqualTo(autoSession2 + "-1");
        assertThat(captures.get(2).callId).isEqualTo(EXPLICIT_SESSION_ID + "-1");
        assertThat(captures.get(3).callId).isEqualTo(EXPLICIT_SESSION_ID + "-2");
    }

    @Test
    void it1RerunningIdenticalInputReproducesIdenticalCaptureSequence() throws Exception {
        List<CapturingSubagentSetup.Capture> firstRun = runMixedCallsScenario(2L);
        List<CapturingSubagentSetup.Capture> secondRun = runMixedCallsScenario(2L);

        assertThat(toIdPairs(secondRun)).isEqualTo(toIdPairs(firstRun));
    }

    private static List<List<String>> toIdPairs(List<CapturingSubagentSetup.Capture> captures) {
        return captures.stream()
                .map(c -> List.of(c.sessionId, c.callId))
                .collect(Collectors.toList());
    }

    /** Builds a fresh plan/harness, runs {@link #mixedCalls}, and returns its capture sequence. */
    private static List<CapturingSubagentSetup.Capture> runMixedCallsScenario(long inputValue)
            throws Exception {
        CapturingSubagentSetup.reset();
        Agent agent = new Agent();
        agent.addResource(RESOURCE_NAME, ResourceType.AGENT, new CapturingSubagentSetup());
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                SubagentIdentityIntegrationTest.class.getMethod(
                        "mixedCalls", Event.class, RunnerContext.class));
        AgentPlan plan = new AgentPlan(agent);

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(plan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(inputValue));
            operator.waitInFlightEventsFinished();
        }
        return CapturingSubagentSetup.captures();
    }

    // Sibling tasks fired from the same record must not collide.

    /** Trigger event whose attributes vary per branch, used to separate sibling task namespaces. */
    public static class SiblingTriggerEvent extends Event {
        public static final String EVENT_TYPE = "SiblingTriggerEvent";

        public SiblingTriggerEvent(String branch) {
            super(EVENT_TYPE);
            setAttr("branch", branch);
        }

        public String getBranch() {
            return (String) getAttr("branch");
        }
    }

    @SuppressWarnings("unused")
    public static void siblingProducer(Event event, RunnerContext ctx) {
        // Same key, same sequenceNumber, same downstream action name for both -- only the
        // triggering event differs between the two siblings.
        ctx.sendEvent(new SiblingTriggerEvent("branch-a"));
        ctx.sendEvent(new SiblingTriggerEvent("branch-b"));
    }

    @SuppressWarnings("unused")
    public static void siblingConsumer(SiblingTriggerEvent event, RunnerContext ctx)
            throws Exception {
        SubagentSetup setup = (SubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        setup.call(ctx, "prompt-" + event.getBranch());
        ctx.sendEvent(new OutputEvent(event.getBranch()));
    }

    @Test
    void it3SiblingTasksFromSameRecordGetSeparateIdentityNamespaces() throws Exception {
        Agent agent = new Agent();
        agent.addResource(RESOURCE_NAME, ResourceType.AGENT, new CapturingSubagentSetup());
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                SubagentIdentityIntegrationTest.class.getMethod(
                        "siblingProducer", Event.class, RunnerContext.class));
        agent.addAction(
                new String[] {SiblingTriggerEvent.EVENT_TYPE},
                SubagentIdentityIntegrationTest.class.getMethod(
                        "siblingConsumer", SiblingTriggerEvent.class, RunnerContext.class));
        AgentPlan plan = new AgentPlan(agent);

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(plan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(9L));
            operator.waitInFlightEventsFinished();

            @SuppressWarnings("unchecked")
            List<StreamRecord<Object>> output =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(output).hasSize(2);
        }

        List<CapturingSubagentSetup.Capture> captures = CapturingSubagentSetup.captures();
        assertThat(captures).hasSize(2);

        // Both siblings share (key, sequenceNumber, actionName); only the triggering event's
        // digest differs. Their identities must not collide.
        assertThat(captures.get(0).sessionId).isNotEqualTo(captures.get(1).sessionId);
        assertThat(captures.get(0).callId).isNotEqualTo(captures.get(1).callId);
    }

    // Async fan-out assigns distinct ids in creation order.

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
    void it4FanOutCallablesGetDistinctIdsInCreationOrder() throws Exception {
        Agent agent = new Agent();
        agent.addResource(RESOURCE_NAME, ResourceType.AGENT, new CapturingSubagentSetup());
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                SubagentIdentityIntegrationTest.class.getMethod(
                        "fanOut", Event.class, RunnerContext.class));
        AgentPlan plan = new AgentPlan(agent);

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(plan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(4L));
            operator.waitInFlightEventsFinished();
        }

        List<CapturingSubagentSetup.Capture> captures = CapturingSubagentSetup.captures();
        assertThat(captures).hasSize(2);
        assertThat(captures.get(0).prompt).isEqualTo("fan-a");
        assertThat(captures.get(1).prompt).isEqualTo("fan-b");
        assertThat(captures.get(0).sessionId).isNotEqualTo(captures.get(1).sessionId);
        assertThat(captures.get(0).callId).isNotEqualTo(captures.get(1).callId);
    }
}
