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
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.Action;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.WorkflowPlan;
import org.apache.flink.agents.runtime.message.EventMessage;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.flink.util.OutputTag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for {@link ActionExecutionOperator}. */
public class ActionExecutionOperatorTest {

    private static final TypeInformation<EventMessage<Long>> TEST_EVENT_MESSAGE_TYPE_INFO =
            TypeInformation.of(new TypeHint<EventMessage<Long>>() {});

    private static final OutputTag<EventMessage<Long>> TEST_OUTPUT_TAG =
            new OutputTag<>("test-feedback-output", TEST_EVENT_MESSAGE_TYPE_INFO);

    @Test
    void testExecuteWorkflow() throws Exception {
        ActionExecutionOperator<Long> operator =
                new ActionExecutionOperator<>(
                        TEST_OUTPUT_TAG,
                        TestWorkflow.getWorkflowPlan(),
                        TEST_EVENT_MESSAGE_TYPE_INFO);
        try (KeyedOneInputStreamOperatorTestHarness<Long, EventMessage<Long>, EventMessage<Long>>
                testHarness =
                        new KeyedOneInputStreamOperatorTestHarness<>(
                                operator,
                                new EventMessage.EventMessageKeySelector<Long>(),
                                TypeInformation.of(Long.class))) {
            testHarness.open();

            // 1. Process an InputEvent with value 0
            //    Expected results:
            //    - A MiddleEvent with value 1 should be emitted to the recordOutput
            //    - No OutputEvent should be emitted to the sideOutput
            testHarness.processElement(
                    new StreamRecord<>(new EventMessage<>(0L, new InputEvent(0L))));
            List<StreamRecord<EventMessage<Long>>> recordOutput =
                    (List<StreamRecord<EventMessage<Long>>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(1);
            EventMessage<Long> recordOutputEventMessage = recordOutput.get(0).getValue();
            assertThat(recordOutputEventMessage.getKey()).isEqualTo(0L);
            assertThat(recordOutputEventMessage.getEvent())
                    .isInstanceOf(TestWorkflow.MiddleEvent.class);
            assertThat(recordOutputEventMessage.getEventType())
                    .isEqualTo(TestWorkflow.MiddleEvent.class.getCanonicalName());
            assertThat(((TestWorkflow.MiddleEvent) recordOutputEventMessage.getEvent()).getNum())
                    .isEqualTo(1);
            assertThat(testHarness.getSideOutput(TEST_OUTPUT_TAG)).isNullOrEmpty();
            recordOutput.clear();

            // 2. Process the previously generated MiddleEvent (value 1)
            //    Expected results:
            //    - An OutputEvent with value 2 should be emitted to the sideOutput
            //    - No output should be emitted to the recordOutput
            testHarness.processElement(new StreamRecord<>(recordOutputEventMessage));
            ConcurrentLinkedQueue<StreamRecord<EventMessage<Long>>> sideOutput =
                    testHarness.getSideOutput(TEST_OUTPUT_TAG);
            assertThat(sideOutput.size()).isEqualTo(1);
            EventMessage<Long> sideOutputEventMessage = sideOutput.poll().getValue();
            assertThat(sideOutputEventMessage.getEvent()).isInstanceOf(OutputEvent.class);
            assertThat(((OutputEvent) sideOutputEventMessage.getEvent()).getOutput()).isEqualTo(2L);
            assertThat(testHarness.getRecordOutput()).hasSize(1);
        }
    }

    @Test
    void testInputEventBlockingOnDuplicateKey() throws Exception {
        ActionExecutionOperator<Long> operator =
                new ActionExecutionOperator<>(
                        TEST_OUTPUT_TAG,
                        TestWorkflow.getWorkflowPlan(),
                        TEST_EVENT_MESSAGE_TYPE_INFO);
        try (KeyedOneInputStreamOperatorTestHarness<Long, EventMessage<Long>, EventMessage<Long>>
                testHarness =
                        new KeyedOneInputStreamOperatorTestHarness<>(
                                operator,
                                new EventMessage.EventMessageKeySelector<Long>(),
                                TypeInformation.of(Long.class))) {
            testHarness.open();

            // 1. Process the first InputEvent with key "0"
            //    Expected behavior:
            //    - A MiddleEvent will be generated to recordOutput
            EventMessage<Long> inputEvent1 = new EventMessage<>(0L, new InputEvent(0L));
            testHarness.processElement(new StreamRecord<>(inputEvent1));
            assertThat(testHarness.getRecordOutput()).hasSize(1);
            // the MiddleEvent produced by the InputEvent1
            EventMessage<Long> middleEvent1 =
                    testHarness.getRecordOutput().stream().findFirst().get().getValue();

            // 2. Process a second InputEvent with the same key "0"
            //    Expected behavior:
            //    - The second InputEvent will be blocked due to duplicate key handling.
            //    - It remains in a pending state until the first event's processing is complete.
            //    - No output should be emitted to the recordOutput.
            EventMessage<Long> inputEvent2 = new EventMessage<>(0L, new InputEvent(0L));
            testHarness.processElement(new StreamRecord<>(inputEvent2));
            assertThat(testHarness.getRecordOutput()).hasSize(1);

            // 3. Process the previously generated MiddleEvent1 (from step 1)
            //    Expected behavior:
            //    - An OutputEvent (OutputEvent1) will be emitted to the sideOutput.
            //    - After the MiddleEvent1 is processed, the blocked InputEvent2 will be process.
            //    - A new MiddleEvent will be generated and emitted to the recordOutput.
            testHarness.processElement(new StreamRecord<>(middleEvent1));
            assertThat(testHarness.getSideOutput(TEST_OUTPUT_TAG).size()).isEqualTo(1);
            assertThat(testHarness.getRecordOutput().size()).isEqualTo(2);
        }
    }

    @Test
    void testInputEventNotBlockingOnDifferentKey() throws Exception {
        ActionExecutionOperator<Long> operator =
                new ActionExecutionOperator<>(
                        TEST_OUTPUT_TAG,
                        TestWorkflow.getWorkflowPlan(),
                        TEST_EVENT_MESSAGE_TYPE_INFO);
        try (KeyedOneInputStreamOperatorTestHarness<Long, EventMessage<Long>, EventMessage<Long>>
                testHarness =
                        new KeyedOneInputStreamOperatorTestHarness<>(
                                operator,
                                new EventMessage.EventMessageKeySelector<Long>(),
                                TypeInformation.of(Long.class))) {
            testHarness.open();

            // 1. Process InputEvent1 with key "0"
            //    Expected behavior:
            //    - A MiddleEvent will be generated and emitted to recordOutput
            EventMessage<Long> inputEvent1 = new EventMessage<>(0L, new InputEvent(0L));
            testHarness.processElement(new StreamRecord<>(inputEvent1));
            assertThat(testHarness.getRecordOutput()).hasSize(1);

            // 2. Process InputEvent2 with key "1"
            //    Expected behavior:
            //    - The InputEvent2 will not be blocked due to a unique key.
            //    - A new MiddleEvent will be generated and emitted to the recordOutput.
            EventMessage<Long> inputEvent2 = new EventMessage<>(1L, new InputEvent(0L));
            testHarness.processElement(new StreamRecord<>(inputEvent2));
            assertThat(testHarness.getRecordOutput()).hasSize(2);
        }
    }

    public static class TestWorkflow {

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

        public static void processInputEvent(InputEvent event, RunnerContext context) {
            Long inputData = (Long) event.getInput();
            context.sendEvent(new MiddleEvent(inputData + 1));
        }

        public static void processMiddleEvent(MiddleEvent event, RunnerContext context) {
            context.sendEvent(new OutputEvent(event.getNum() * 2));
        }

        public static WorkflowPlan getWorkflowPlan() throws Exception {
            Map<Class<? extends Event>, List<Action>> eventTriggerActions = new HashMap<>();
            Action action1 =
                    new Action(
                            "processInputEvent",
                            new JavaFunction(
                                    TestWorkflow.class,
                                    "processInputEvent",
                                    new Class<?>[] {InputEvent.class, RunnerContext.class}),
                            Collections.singletonList(InputEvent.class));
            Action action2 =
                    new Action(
                            "processMiddleEvent",
                            new JavaFunction(
                                    TestWorkflow.class,
                                    "processMiddleEvent",
                                    new Class<?>[] {MiddleEvent.class, RunnerContext.class}),
                            Collections.singletonList(MiddleEvent.class));
            eventTriggerActions.put(InputEvent.class, Collections.singletonList(action1));
            eventTriggerActions.put(MiddleEvent.class, Collections.singletonList(action2));
            Map<String, Action> actions = new HashMap<>();
            actions.put(action1.getName(), action1);
            actions.put(action2.getName(), action2);
            return new WorkflowPlan(actions, eventTriggerActions);
        }
    }
}
