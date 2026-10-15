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

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.context.RunnerContextImpl;
import org.apache.flink.agents.runtime.subgraph.SubgraphDefinition;
import org.apache.flink.agents.runtime.subgraph.SubgraphEvent;
import org.apache.flink.agents.runtime.subgraph.SubgraphInvocation;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A runtime-only adapter for an arbitrary fan-out graph, without ChatAction or a subagent setup.
 */
@EnabledForJreRange(min = JRE.JAVA_21)
@Timeout(30)
public class SubgraphCallTest {
    public static void caller(Event event, RunnerContext ctx) throws Exception {
        RunnerContextImpl runner = (RunnerContextImpl) ctx;
        Map<String, Action> actions = runner.currentPlan().getActions();
        SubgraphDefinition<Integer> definition =
                new SubgraphDefinition<>() {
                    @Override
                    public List<Action> matchActions(Event input) {
                        if ("start".equals(input.getType())) {
                            return List.of(actions.get("first"), actions.get("second"));
                        }
                        if ("next".equals(input.getType())) {
                            return List.of(actions.get("third"));
                        }
                        return List.of();
                    }

                    @Override
                    public boolean isOutput(Event output) {
                        return "result".equals(output.getType());
                    }

                    @Override
                    public Integer finish(List<Event> outputs) {
                        return outputs.size();
                    }
                };
        ctx.getShortTermMemory().set("shared", "caller");
        SubgraphInvocation<Integer> call =
                new SubgraphInvocation<>(
                        "custom",
                        "key",
                        definition,
                        runner.getMemoryContext(),
                        runner.getResourceScope());
        runner.getSubgraphCalls().register(call);
        ctx.sendEvent(new SubgraphEvent(call.getId(), new Event("start"), true));
        int results =
                ctx.durableExecuteAsync(
                                new DurableCallable<Integer>() {
                                    @Override
                                    public String getId() {
                                        return "custom-result";
                                    }

                                    @Override
                                    public Class<Integer> getResultClass() {
                                        return Integer.class;
                                    }

                                    @Override
                                    public Integer call() throws Exception {
                                        return call.awaitResult();
                                    }
                                })
                        .await();
        ctx.sendEvent(new OutputEvent(results));
    }

    public static void first(Event event, RunnerContext ctx) throws Exception {
        assertThat(event.getType()).isEqualTo("start");
        assertThat(ctx.getShortTermMemory().get("shared").getValue()).isEqualTo("caller");
        ctx.sendEvent(new Event("result"));
    }

    public static void second(Event event, RunnerContext ctx) {
        ctx.sendEvent(new Event("next"));
    }

    public static void third(Event event, RunnerContext ctx) {
        ctx.sendEvent(new Event("result"));
    }

    public static void leaked(Event event, RunnerContext ctx) {
        throw new AssertionError("Internal graph output reached outer listeners");
    }

    @Test
    void routesArbitraryGraphPrivatelyAndWaitsForAllBranches() throws Exception {
        Agent agent = new Agent();
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                SubgraphCallTest.class.getMethod("caller", Event.class, RunnerContext.class));
        for (String name : List.of("first", "second", "third")) {
            agent.addAction(
                    new String[] {"unused"},
                    SubgraphCallTest.class.getMethod(name, Event.class, RunnerContext.class));
        }
        agent.addAction(
                new String[] {"start", "next", "result"},
                SubgraphCallTest.class.getMethod("leaked", Event.class, RunnerContext.class));
        try (KeyedOneInputStreamOperatorTestHarness<String, String, Object> harness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(new AgentPlan(agent), true),
                        (KeySelector<String, String>) value -> value,
                        TypeInformation.of(String.class))) {
            harness.open();
            harness.processElement(new StreamRecord<>("key"));
            ((ActionExecutionOperator<?, ?>) harness.getOperator()).waitInFlightEventsFinished();
            assertThat(harness.getRecordOutput()).hasSize(1);
            assertThat(harness.getRecordOutput().iterator().next().getValue()).isEqualTo(2);
        }
    }
}
