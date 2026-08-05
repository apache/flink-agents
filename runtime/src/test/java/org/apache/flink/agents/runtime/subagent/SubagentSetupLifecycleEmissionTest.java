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
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.SubagentFuture;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperator;
import org.apache.flink.agents.runtime.operator.ActionExecutionOperatorFactory;
import org.apache.flink.agents.runtime.operator.ActionTask;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that a {@link SubagentSetup} registered as an AGENT resource is discovered as a {@link
 * TaskLifecycleListener} by the {@link ActionExecutionOperator} and receives the task lifecycle
 * events in the expected order.
 */
public class SubagentSetupLifecycleEmissionTest {

    @BeforeEach
    void resetRecording() {
        RecordingLifecycleListener.EVENTS.clear();
    }

    /** AGENT setup that records every lifecycle notification it receives. */
    public static class RecordingLifecycleListener extends BaseSubagentSetup {

        static final List<String> EVENTS = new CopyOnWriteArrayList<>();

        @Override
        public SubagentFuture submit(
                RunnerContext ctx, Object prompt, String sessionId, String callId) {
            throw new UnsupportedOperationException("recording listener never submits");
        }

        @Override
        public void onRecordStart(Object key) {
            EVENTS.add("recordStart:" + key);
        }

        @Override
        public void onTaskPrepared(ActionTask task) {
            EVENTS.add("prepared:" + task.getAction().getName());
        }

        @Override
        public void onTaskTransferred(ActionTask from, ActionTask to) {
            EVENTS.add(
                    "transferred:" + from.getAction().getName() + "->" + to.getAction().getName());
        }

        @Override
        public void onTaskFinished(ActionTask task) {
            EVENTS.add("finished:" + task.getAction().getName());
        }

        @Override
        public void onRecordFinished(Object key) {
            EVENTS.add("recordFinished:" + key);
        }
    }

    /** Agent with a plain synchronous action and a recording AGENT resource. */
    public static class SyncListenerAgent extends Agent {

        @org.apache.flink.agents.api.annotation.Action(EventType.InputEvent)
        public static void handleInput(Event event, RunnerContext context) {
            Long input = (Long) InputEvent.fromEvent(event).getInput();
            context.sendEvent(new OutputEvent(input * 2));
        }
    }

    /** Agent whose input action suspends on a durable async call, forcing a task transfer. */
    public static class AsyncListenerAgent extends Agent {

        @org.apache.flink.agents.api.annotation.Action(EventType.InputEvent)
        public static void handleInput(Event event, RunnerContext context) throws Exception {
            Long input = (Long) InputEvent.fromEvent(event).getInput();
            Long result =
                    context.durableExecuteAsync(
                            new DurableCallable<Long>() {
                                @Override
                                public String getId() {
                                    return "listener-double";
                                }

                                @Override
                                public Class<Long> getResultClass() {
                                    return Long.class;
                                }

                                @Override
                                public Long call() {
                                    try {
                                        // Force the action to yield before the call completes,
                                        // so the task is suspended and transferred.
                                        Thread.sleep(50);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return input * 2;
                                }
                            });
            context.sendEvent(new OutputEvent(result));
        }
    }

    private static Agent agentWithRecorder(Agent agent) {
        agent.addResource("recorder", ResourceType.AGENT, new RecordingLifecycleListener());
        return agent;
    }

    @Test
    void recordStartAndFinishedPairAroundSyncTasks() throws Exception {
        AgentPlan plan = new AgentPlan(agentWithRecorder(new SyncListenerAgent()));
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(plan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(7L));
            operator.waitInFlightEventsFinished();

            assertThat(RecordingLifecycleListener.EVENTS)
                    .containsExactly(
                            "recordStart:7",
                            "prepared:handleInput",
                            "finished:handleInput",
                            "recordFinished:7");

            // A second record on the same key starts and finishes its own round.
            testHarness.processElement(new StreamRecord<>(7L));
            operator.waitInFlightEventsFinished();

            assertThat(RecordingLifecycleListener.EVENTS)
                    .containsExactly(
                            "recordStart:7",
                            "prepared:handleInput",
                            "finished:handleInput",
                            "recordFinished:7",
                            "recordStart:7",
                            "prepared:handleInput",
                            "finished:handleInput",
                            "recordFinished:7");
        }
    }

    @Test
    void suspendedTaskEmitsTransferBeforeItsSuccessorIsPrepared() throws Exception {
        AgentPlan plan = new AgentPlan(agentWithRecorder(new AsyncListenerAgent()));
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(plan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(5L));
            operator.waitInFlightEventsFinished();

            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo(10L);

            if (ContinuationActionExecutor.isContinuationSupported()) {
                // JDK 21+: the action suspends on the durable async call. The operator re-polls
                // the suspended task on every mailbox turn until the async future completes (that
                // round-robin is what lets concurrent work — sub-agent envelopes — progress while
                // the caller waits), so each poll that still finds the future pending emits
                // another transferred->prepared pair. Assert the invariant the test is named
                // after: every suspension transfers the task before its successor is prepared,
                // bounded by recordStart first and finished last.
                assertThat(RecordingLifecycleListener.EVENTS)
                        .startsWith("recordStart:5", "prepared:handleInput")
                        .endsWith("finished:handleInput", "recordFinished:5");
                List<String> middle =
                        RecordingLifecycleListener.EVENTS.subList(
                                2, RecordingLifecycleListener.EVENTS.size() - 2);
                assertThat(middle)
                        .as("suspension rounds alternate transferred -> prepared")
                        .isNotEmpty();
                assertThat(middle.size() % 2).isZero();
                for (int i = 0; i < middle.size(); i += 2) {
                    assertThat(middle.get(i)).isEqualTo("transferred:handleInput->handleInput");
                    assertThat(middle.get(i + 1)).isEqualTo("prepared:handleInput");
                }
            } else {
                // JDK 11 fallback runs the action synchronously: no suspension, no transfer.
                assertThat(RecordingLifecycleListener.EVENTS)
                        .containsExactly(
                                "recordStart:5",
                                "prepared:handleInput",
                                "finished:handleInput",
                                "recordFinished:5");
            }
        }
    }
}
