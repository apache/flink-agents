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
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.chat.ChatCallDeadlockTest;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises identical triggering events and multiple emission batches without a durable store. */
@EnabledForJreRange(min = JRE.JAVA_21)
public class RuntimeIdentityIntegrationTest {
    public static void fanout(Event event, RunnerContext ctx) {
        ctx.sendEvent(new Event("work"));
        ctx.sendEvent(new Event("work"));
    }

    public static void chat(Event event, RunnerContext ctx) throws Exception {
        ctx.sendEvent(new Event("marker"));
        for (int i = 0; i < 2; i++) {
            assertThat(ctx.chat("target", List.of(ChatMessage.user("same"))).await().getText())
                    .isEqualTo("answer:same");
            ctx.sendEvent(new Event("marker"));
        }
    }

    public static void subagent(Event event, RunnerContext ctx) throws Exception {
        ctx.sendEvent(new Event("marker"));
        for (int i = 0; i < 2; i++) {
            ((SubagentSetup) ctx.getResource("target", ResourceType.AGENT))
                    .submit(ctx, "same", "shared-session")
                    .await();
            ctx.sendEvent(new Event("marker"));
        }
    }

    public static void echo(Event event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent("same"));
    }

    public static void record(Event event, RunnerContext ctx) {
        ctx.sendEvent(new OutputEvent(event.getOccurrenceId()));
    }

    @ParameterizedTest
    @CsvSource({"false,chat", "true,chat", "false,subagent", "true,subagent"})
    @Timeout(30)
    void repeatedInvocationsAndYieldsHaveStableDistinctIdentities(boolean parallel, String caller)
            throws Exception {
        List<Object> first = run(parallel, caller);
        assertThat(first).hasSize(6).doesNotHaveDuplicates();
        assertThat(run(parallel, caller)).containsExactlyInAnyOrderElementsOf(first);
    }

    private static List<Object> run(boolean parallel, String caller) throws Exception {
        Agent agent = new Agent();
        if (caller.equals("chat")) {
            agent.addResource(
                    "target",
                    ResourceType.CHAT_MODEL,
                    ResourceDescriptor.Builder.newBuilder(
                                    ChatCallDeadlockTest.Model.class.getName())
                            .build());
        } else {
            Agent child = new Agent();
            child.addAction(
                    new String[] {InputEvent.EVENT_TYPE},
                    RuntimeIdentityIntegrationTest.class.getMethod(
                            "echo", Event.class, RunnerContext.class));
            agent.addResource("target", ResourceType.AGENT, child);
        }
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                RuntimeIdentityIntegrationTest.class.getMethod(
                        "fanout", Event.class, RunnerContext.class));
        agent.addAction(
                new String[] {"work"},
                RuntimeIdentityIntegrationTest.class.getMethod(
                        caller, Event.class, RunnerContext.class));
        agent.addAction(
                new String[] {"marker"},
                RuntimeIdentityIntegrationTest.class.getMethod(
                        "record", Event.class, RunnerContext.class));
        AgentPlan plan = new AgentPlan(agent);
        plan.getConfig().set(AgentExecutionOptions.PARALLEL_EXECUTION_ENABLED, parallel);
        try (KeyedOneInputStreamOperatorTestHarness<String, String, Object> harness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(plan, true),
                        (KeySelector<String, String>) value -> value,
                        TypeInformation.of(String.class))) {
            harness.open();
            harness.processElement(new StreamRecord<>("key"));
            ((ActionExecutionOperator<?, ?>) harness.getOperator()).waitInFlightEventsFinished();
            List<Object> result = new ArrayList<>();
            for (StreamRecord<Object> record : harness.getRecordOutput()) {
                result.add(record.getValue());
            }
            return result;
        }
    }
}
