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
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentResult;
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
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Functional coverage for an agent registered directly as an {@code AGENT} resource: the operator
 * must dispatch the call event into the child scope, run the child's actions, accumulate their
 * output and complete the caller's wait.
 *
 * <p>Requires JDK 21+. A Java caller's wait must release the mailbox so the operator can dispatch
 * the child's actions, which only the continuation executor can do; on JDK 11 the synchronous
 * fallback blocks the mailbox and the call cannot complete.
 */
public class InternalSubagentCallTest {

    private static final List<String> CHILD_INVOCATIONS = new ArrayList<>();

    @BeforeEach
    void requireContinuationsAndReset() {
        assumeTrue(
                Runtime.version().feature() >= 21,
                "a Java caller's sub-agent wait needs continuations (JDK 21+)");
        CHILD_INVOCATIONS.clear();
    }

    // --- child agents ---

    /** Echoes the prompt back as one output event. */
    public static class EchoChildAgent extends Agent {
        public EchoChildAgent() throws Exception {
            addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    EchoChildAgent.class.getMethod("echo", Event.class, RunnerContext.class));
        }

        @SuppressWarnings("unused")
        public static void echo(Event event, RunnerContext ctx) {
            Object prompt = InputEvent.fromEvent(event).getInput();
            CHILD_INVOCATIONS.add(String.valueOf(prompt));
            ctx.sendEvent(new OutputEvent("echo:" + prompt));
        }
    }

    /** Fails instead of producing output. */
    public static class FailingChildAgent extends Agent {
        public FailingChildAgent() throws Exception {
            addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    FailingChildAgent.class.getMethod("fail", Event.class, RunnerContext.class));
        }

        @SuppressWarnings("unused")
        public static void fail(Event event, RunnerContext ctx) {
            throw new IllegalStateException(
                    "child refused " + InputEvent.fromEvent(event).getInput());
        }
    }

    /** Calls a grandchild of its own, so the call path is exercised two levels deep. */
    public static class NestingChildAgent extends Agent {
        public NestingChildAgent() throws Exception {
            addResource("grandchild", ResourceType.AGENT, new EchoChildAgent());
            addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    NestingChildAgent.class.getMethod("relay", Event.class, RunnerContext.class));
        }

        @SuppressWarnings("unused")
        public static void relay(Event event, RunnerContext ctx) throws Exception {
            Object prompt = InputEvent.fromEvent(event).getInput();
            SubagentSetup grandchild =
                    (SubagentSetup) ctx.getResource("grandchild", ResourceType.AGENT);
            SubagentResult result = grandchild.submit(ctx, "deep:" + prompt).await();
            ctx.sendEvent(new OutputEvent("relayed:" + firstOutput(result)));
        }
    }

    // --- caller actions ---

    @SuppressWarnings("unused")
    public static void callOnce(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup child = (SubagentSetup) ctx.getResource("child", ResourceType.AGENT);
        SubagentResult result = child.submit(ctx, InputEvent.fromEvent(event).getInput()).await();
        ctx.sendEvent(new OutputEvent(result.isSuccess() ? firstOutput(result) : "error"));
    }

    @SuppressWarnings("unused")
    public static void callFailing(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup child = (SubagentSetup) ctx.getResource("child", ResourceType.AGENT);
        SubagentResult result = child.submit(ctx, "please").await();
        ctx.sendEvent(
                new OutputEvent(
                        result.isSuccess()
                                ? "unexpected-success"
                                : "failed:" + result.getErrorMessage()));
    }

    @SuppressWarnings("unused")
    public static void callTwice(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup child = (SubagentSetup) ctx.getResource("child", ResourceType.AGENT);
        SubagentFuture first = child.submit(ctx, "a");
        SubagentFuture second = child.submit(ctx, "b");
        ctx.sendEvent(
                new OutputEvent(
                        firstOutput(first.await())
                                + "|"
                                + firstOutput(second.await())
                                + "|"
                                + first.getCallId()
                                + "!="
                                + second.getCallId()));
    }

    // --- tests ---

    @Test
    @Timeout(60)
    void callerReceivesTheChildsAccumulatedOutput() throws Exception {
        List<Object> output = run(plan("callOnce", new EchoChildAgent()), 7L);

        assertThat(CHILD_INVOCATIONS).containsExactly("7");
        assertThat(output).containsExactly("echo:7");
    }

    @Test
    @Timeout(60)
    void childFailureSurfacesThroughTheResult() throws Exception {
        List<Object> output = run(plan("callFailing", new FailingChildAgent()), 7L);

        assertThat(output).hasSize(1);
        assertThat((String) output.get(0)).startsWith("failed:").contains("child refused please");
    }

    @Test
    @Timeout(60)
    void successiveCallsRunIndependentlyUnderDistinctIdentities() throws Exception {
        List<Object> output = run(plan("callTwice", new EchoChildAgent()), 7L);

        assertThat(CHILD_INVOCATIONS).containsExactly("a", "b");
        assertThat(output).hasSize(1);
        String joined = (String) output.get(0);
        assertThat(joined).startsWith("echo:a|echo:b|");
        String[] ids = joined.substring(joined.indexOf("b|") + 2).split("!=");
        assertThat(ids[0]).isNotEqualTo(ids[1]);
    }

    @Test
    @Timeout(60)
    void nestedCallReachesTheGrandchild() throws Exception {
        List<Object> output = run(plan("callOnce", new NestingChildAgent()), 7L);

        assertThat(CHILD_INVOCATIONS).containsExactly("deep:7");
        assertThat(output).containsExactly("relayed:echo:deep:7");
    }

    // --- helpers ---

    private static String firstOutput(SubagentResult result) {
        return String.valueOf(((List<?>) result.getResult()).get(0));
    }

    private static AgentPlan plan(String callerAction, Agent child) throws Exception {
        Agent agent = new Agent();
        agent.addResource("child", ResourceType.AGENT, child);
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                InternalSubagentCallTest.class.getMethod(
                        callerAction, Event.class, RunnerContext.class));
        return new AgentPlan(agent);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> run(AgentPlan plan, long input) throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(plan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            harness.open();
            harness.processElement(new StreamRecord<>(input));
            ((ActionExecutionOperator<Long, Object>) harness.getOperator())
                    .waitInFlightEventsFinished();
            return ((List<StreamRecord<Object>>) harness.getRecordOutput())
                    .stream().map(StreamRecord::getValue).collect(Collectors.toList());
        }
    }
}
