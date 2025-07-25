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
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.Action;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.flink.util.ExceptionUtils;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link ActionExecutionOperator}. */
public class ActionExecutionOperatorTest {

    @Test
    void testExecuteAgent() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(TestAgent.getAgentPlan(false), true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            testHarness.processElement(new StreamRecord<>(0L));
            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo(2L);

            testHarness.processElement(new StreamRecord<>(1L));
            recordOutput = (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(2);
            assertThat(recordOutput.get(1).getValue()).isEqualTo(4L);
        }
    }

    @Test
    void testMemoryAccessProhibitedOutsideMailboxThread() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(TestAgent.getAgentPlan(true), true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();

            assertThatThrownBy(() -> testHarness.processElement(new StreamRecord<>(0L)))
                    .rootCause()
                    .hasMessageContaining("Expected to be running on the task mailbox thread");
        }
    }

    public static class TestAgent {

        public static class MiddleEvent extends Event {
            public Long num;

            public MiddleEvent(Long num) {
                super();
                this.num = num;
            }

            public Long getNum() {
                return num;
            }
        }

        public static void action1(InputEvent event, RunnerContext context) {
            Long inputData = (Long) event.getInput();
            try {
                MemoryObject mem = context.getShortTermMemory();
                mem.set("tmp", inputData + 1);
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
            context.sendEvent(new MiddleEvent(inputData + 1));
        }

        public static void action2(MiddleEvent event, RunnerContext context) {
            try {
                MemoryObject mem = context.getShortTermMemory();
                Long tmp = (Long) mem.get("tmp").getValue();
                context.sendEvent(new OutputEvent(tmp * 2));
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
        }

        public static void action3(MiddleEvent event, RunnerContext context) {
            // To test disallows memory access from non-mailbox threads.
            try {
                ExecutorService executor = Executors.newSingleThreadExecutor();
                Future<Long> future =
                        executor.submit(
                                () -> (Long) context.getShortTermMemory().get("tmp").getValue());
                Long tmp = future.get();
                context.sendEvent(new OutputEvent(tmp * 2));
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
        }

        public static AgentPlan getAgentPlan(boolean testMemoryAccessOutOfMailbox) {
            try {
                Map<String, List<Action>> actionsByEvent = new HashMap<>();
                Action action1 =
                        new Action(
                                "action1",
                                new JavaFunction(
                                        TestAgent.class,
                                        "action1",
                                        new Class<?>[] {InputEvent.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.class.getName()));
                Action action2 =
                        new Action(
                                "action2",
                                new JavaFunction(
                                        TestAgent.class,
                                        "action2",
                                        new Class<?>[] {MiddleEvent.class, RunnerContext.class}),
                                Collections.singletonList(MiddleEvent.class.getName()));
                actionsByEvent.put(InputEvent.class.getName(), Collections.singletonList(action1));
                actionsByEvent.put(MiddleEvent.class.getName(), Collections.singletonList(action2));
                Map<String, Action> actions = new HashMap<>();
                actions.put(action1.getName(), action1);
                actions.put(action2.getName(), action2);

                if (testMemoryAccessOutOfMailbox) {
                    Action action3 =
                            new Action(
                                    "action3",
                                    new JavaFunction(
                                            TestAgent.class,
                                            "action3",
                                            new Class<?>[] {
                                                MiddleEvent.class, RunnerContext.class
                                            }),
                                    Collections.singletonList(MiddleEvent.class.getName()));
                    actionsByEvent.put(
                            MiddleEvent.class.getName(), Collections.singletonList(action3));
                    actions.put(action3.getName(), action3);
                }

                return new AgentPlan(actions, actionsByEvent);
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
            return null;
        }
    }
}
