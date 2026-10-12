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
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentFutures;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.plan.AgentConfiguration;
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
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the deadlock described in GitHub issue #1213: with {@code num-async-threads=1}, a
 * parent awaiting an internal sub-agent holds the single async-pool worker via a blocking {@code
 * responseFuture.get()}, while the child's own durable async call needs a worker from the same pool
 * to make progress. The result is a self-deadlock — neither side can advance.
 *
 * <p>The fix must let the parent's await yield its worker so the child can proceed, regardless of
 * pool size. This test times out under the broken behavior and completes promptly once fixed.
 */
@EnabledForJreRange(min = JRE.JAVA_21)
public class InternalSubagentDeadlockTest {

    private static final AtomicBoolean CHILD_DURABLE_EXECUTED = new AtomicBoolean(false);
    private static final AtomicInteger DURABLE_EXECUTIONS = new AtomicInteger();

    @BeforeEach
    void resetCounters() {
        CHILD_DURABLE_EXECUTED.set(false);
        DURABLE_EXECUTIONS.set(0);
    }

    /**
     * Child agent whose action performs a durable async call before emitting output. The call
     * itself is trivial (returns immediately), but it still requires an async-pool worker to
     * execute. Under the deadlock, the parent holds the only worker and this call never starts.
     */
    public static class DurableAsyncChildAgent extends Agent {
        public DurableAsyncChildAgent() throws Exception {
            addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    DurableAsyncChildAgent.class.getMethod(
                            "handle", Event.class, RunnerContext.class));
        }

        @SuppressWarnings("unused")
        public static void handle(Event event, RunnerContext ctx) throws Exception {
            String result = ctx.durableExecuteAsync(durableCall()).await();
            ctx.sendEvent(new OutputEvent(result));
        }
    }

    /**
     * Child that itself awaits a grandchild performing a durable call, so two waits are nested
     * while the grandchild's call still needs a worker from the same single-thread pool.
     */
    public static class NestingDurableChildAgent extends Agent {
        public NestingDurableChildAgent() throws Exception {
            addResource("grandchild", ResourceType.AGENT, new DurableAsyncChildAgent());
            addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    NestingDurableChildAgent.class.getMethod(
                            "relay", Event.class, RunnerContext.class));
        }

        @SuppressWarnings("unused")
        public static void relay(Event event, RunnerContext ctx) throws Exception {
            SubagentSetup grandchild =
                    (SubagentSetup) ctx.getResource("grandchild", ResourceType.AGENT);
            SubagentResult result =
                    grandchild.submit(ctx, InputEvent.fromEvent(event).getInput()).await();
            ctx.sendEvent(new OutputEvent("relayed:" + firstOutput(result)));
        }
    }

    private static DurableCallable<String> durableCall() {
        return new DurableCallable<String>() {
            @Override
            public String getId() {
                return "child-durable";
            }

            @Override
            public Class<String> getResultClass() {
                return String.class;
            }

            @Override
            public String call() {
                CHILD_DURABLE_EXECUTED.set(true);
                DURABLE_EXECUTIONS.incrementAndGet();
                return "durable-result";
            }
        };
    }

    private static String firstOutput(SubagentResult result) {
        return String.valueOf(((List<?>) result.getResult()).get(0));
    }

    @SuppressWarnings("unused")
    public static void callChild(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup child = (SubagentSetup) ctx.getResource("child", ResourceType.AGENT);
        SubagentResult result = child.submit(ctx, InputEvent.fromEvent(event).getInput()).await();
        ctx.sendEvent(new OutputEvent("parent:" + firstOutput(result)));
    }

    @SuppressWarnings("unused")
    public static void callChildTwiceCombined(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup child = (SubagentSetup) ctx.getResource("child", ResourceType.AGENT);
        SubagentFutures combined = child.submit(ctx, "a").combine(child.submit(ctx, "b"));
        List<SubagentResult> results = combined.awaitAll();
        ctx.sendEvent(
                new OutputEvent(
                        "parent:"
                                + firstOutput(results.get(0))
                                + ","
                                + firstOutput(results.get(1))));
    }

    /**
     * With {@code num-async-threads=1}, the parent's await must not hold the only worker. If it
     * does, the child's durable call can never execute and this test times out.
     */
    @Test
    @Timeout(30)
    void singleAsyncThreadDoesNotDeadlockWhenChildUsesDurableCall() throws Exception {
        List<Object> output = run(plan("callChild", new DurableAsyncChildAgent()));

        assertThat(CHILD_DURABLE_EXECUTED).isTrue();
        assertThat(output).containsExactly("parent:durable-result");
    }

    /**
     * Nesting multiplies the waits in flight: a child awaiting its own grandchild must not consume
     * a worker either, or one nested call deadlocks the single-thread pool.
     */
    @Test
    @Timeout(30)
    void singleAsyncThreadDoesNotDeadlockOnNestedCalls() throws Exception {
        List<Object> output = run(plan("callChild", new NestingDurableChildAgent()));

        assertThat(CHILD_DURABLE_EXECUTED).isTrue();
        assertThat(output).containsExactly("parent:relayed:durable-result");
    }

    /**
     * Concurrent calls combined into one wait: {@code awaitAll} prepares both calls (dispatching
     * both children) before awaiting either, so both waits are in flight at once and neither may
     * hold a worker.
     */
    @Test
    @Timeout(30)
    void singleAsyncThreadDoesNotDeadlockOnCombinedCalls() throws Exception {
        List<Object> output = run(plan("callChildTwiceCombined", new DurableAsyncChildAgent()));

        assertThat(DURABLE_EXECUTIONS.get()).isEqualTo(2);
        assertThat(output).containsExactly("parent:durable-result,durable-result");
    }

    // --- helpers ---

    /** Builds a plan whose single caller action is {@code callerAction}, with one async worker. */
    private static AgentPlan plan(String callerAction, Agent child) throws Exception {
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentExecutionOptions.NUM_ASYNC_THREADS, 1);

        Agent parent = new Agent();
        parent.addResource("child", ResourceType.AGENT, child);
        parent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                InternalSubagentDeadlockTest.class.getMethod(
                        callerAction, Event.class, RunnerContext.class));
        return new AgentPlan(parent, config);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> run(AgentPlan plan) throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(plan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            harness.open();
            harness.processElement(new StreamRecord<>(1L));
            ((ActionExecutionOperator<Long, Object>) harness.getOperator())
                    .waitInFlightEventsFinished();
            return ((List<StreamRecord<Object>>) harness.getRecordOutput())
                    .stream().map(StreamRecord::getValue).collect(Collectors.toList());
        }
    }
}
