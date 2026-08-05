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
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.Result;
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentFutures;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperator;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperatorFactory;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.typeutils.TypeExtractor;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The short {@code submit} forms on {@link BaseSubagentSetup}: the setup observes the task
 * lifecycle and assigns the missing ids deterministically from the executing task's caller-side
 * facts, then delegates to the full {@code submit} provided by the subclass.
 */
public class BaseSubagentSetupTest {

    private static final String RESOURCE_NAME = "allocating";

    @BeforeEach
    public void resetCaptures() {
        AllocatingCaptureSetup.reset();
    }

    /** Captures every assigned {@code (sessionId, callId)} pair like a collecting setup. */
    public static class AllocatingCaptureSetup extends BaseSubagentSetup {

        private static final List<String[]> CAPTURES =
                Collections.synchronizedList(new ArrayList<>());

        /** Clears all captures. Call before each independent scenario. */
        public static void reset() {
            CAPTURES.clear();
        }

        /** Snapshot of every assignment since the last {@link #reset()}, in creation order. */
        public static List<String[]> captures() {
            synchronized (CAPTURES) {
                return new ArrayList<>(CAPTURES);
            }
        }

        @Override
        public SubagentFuture submit(
                RunnerContext ctx, Object prompt, String sessionId, String callId) {
            CAPTURES.add(new String[] {sessionId, callId, String.valueOf(prompt)});
            return new SubagentFuture(sessionId, callId) {
                @Override
                public boolean isDone() {
                    return true;
                }

                @Override
                public Result await() {
                    return Result.ok(sessionId + "|" + callId + "|" + prompt);
                }

                @Override
                public SubagentFutures combine(SubagentFuture... others) {
                    throw new UnsupportedOperationException("batching is not under test");
                }
            };
        }
    }

    @SuppressWarnings("unused")
    public static void shortForms(Event event, RunnerContext ctx) throws Exception {
        BaseSubagentSetup setup =
                (BaseSubagentSetup) ctx.getResource(RESOURCE_NAME, ResourceType.AGENT);
        SubagentFuture noIds = setup.submit(ctx, "a");
        SubagentFuture sessionOnly = setup.submit(ctx, "b", "given-session");
        ctx.sendEvent(
                new OutputEvent(noIds.await().getResult() + "|" + sessionOnly.await().getResult()));
    }

    @SuppressWarnings("unused")
    public static void twoStreams(Event event, RunnerContext ctx) throws Exception {
        BaseSubagentSetup first =
                (BaseSubagentSetup) ctx.getResource("stream-a", ResourceType.AGENT);
        BaseSubagentSetup second =
                (BaseSubagentSetup) ctx.getResource("stream-b", ResourceType.AGENT);
        first.submit(ctx, "a");
        second.submit(ctx, "b");
        ctx.sendEvent(new OutputEvent("done"));
    }

    @Test
    void shortFormsAssignIdsFromTheExecutingTask() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness = harness(plan())) {
            harness.open();
            run(harness, 1L);
            run(harness, 2L);

            List<String[]> captures = AllocatingCaptureSetup.captures();
            assertThat(captures).hasSize(4);

            // The no-id form assigns a fresh session and the first call within it.
            String[] first = captures.get(0);
            assertThat(first[2]).isEqualTo("a");
            assertThat(first[0]).endsWith("-0");
            assertThat(first[1]).isEqualTo(first[0] + "-1");

            // The session-only form assigns the call id under the given session.
            String[] second = captures.get(1);
            assertThat(second[0]).isEqualTo("given-session");
            assertThat(second[1]).isEqualTo("given-session-1");

            // Another key runs under another namespace, so the assigned session differs.
            String[] third = captures.get(2);
            assertThat(third[0]).endsWith("-0");
            assertThat(third[0]).isNotEqualTo(first[0]);

            assertThat(harness.getRecordOutput()).hasSize(2);
        }
    }

    @Test
    void setupsSharingOneActionAllocateDisjointIds() throws Exception {
        Agent agent = new Agent();
        agent.addResource("stream-a", ResourceType.AGENT, new AllocatingCaptureSetup());
        agent.addResource("stream-b", ResourceType.AGENT, new AllocatingCaptureSetup());
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                BaseSubagentSetupTest.class.getMethod(
                        "twoStreams", Event.class, RunnerContext.class));
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                harness(new AgentPlan(agent, new AgentConfiguration()))) {
            harness.open();
            run(harness, 1L);

            // Both sub-agents count in the same action execution but carry distinct injected
            // agent names, so their first sessions must not collide.
            List<String[]> captures = AllocatingCaptureSetup.captures();
            assertThat(captures).hasSize(2);
            assertThat(captures.get(0)[0]).isNotEqualTo(captures.get(1)[0]);
        }
    }

    @Test
    void materializationInjectsQualifiedResourceNames() throws Exception {
        Agent rootAgent = new Agent();
        rootAgent.addResource("root-setup", ResourceType.AGENT, new AllocatingCaptureSetup());
        ResourceCache rootCache =
                new ResourceCache(new AgentPlan(rootAgent).getResourceProviders());
        BaseSubagentSetup rootSetup =
                (BaseSubagentSetup) rootCache.getResource("root-setup", ResourceType.AGENT);
        assertThat(rootSetup.getResourceName()).isEqualTo("root-setup");

        // A child cache qualifies the resource names of setups materialized from the child plan.
        Agent childAgent = new Agent();
        childAgent.addResource("nested", ResourceType.AGENT, new AllocatingCaptureSetup());
        ResourceCache childCache =
                new ResourceCache(
                        new AgentPlan(childAgent).getResourceProviders(),
                        Thread.currentThread().getContextClassLoader(),
                        rootCache,
                        "root-setup#");
        BaseSubagentSetup nestedSetup =
                (BaseSubagentSetup) childCache.getResource("nested", ResourceType.AGENT);
        assertThat(nestedSetup.getResourceName()).isEqualTo("root-setup#nested");
    }

    @SuppressWarnings("unchecked")
    private static void run(
            KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness, long value)
            throws Exception {
        harness.processElement(new StreamRecord<>(value));
        ((ActionExecutionOperator<Long, Object>) harness.getOperator())
                .waitInFlightEventsFinished();
    }

    private static AgentPlan plan() throws Exception {
        Agent agent = new Agent();
        agent.addResource(RESOURCE_NAME, ResourceType.AGENT, new AllocatingCaptureSetup());
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                BaseSubagentSetupTest.class.getMethod(
                        "shortForms", Event.class, RunnerContext.class));
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
