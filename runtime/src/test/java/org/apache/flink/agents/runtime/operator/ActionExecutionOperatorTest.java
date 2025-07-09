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
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.TestProcessingTimeService;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.flink.util.ExceptionUtils;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for {@link ActionExecutionOperator}. */
public class ActionExecutionOperatorTest {

    @Test
    void testExecuteAgent() throws Exception {
        ActionExecutionOperator<Long, Object> operator =
                new ActionExecutionOperator<>(
                        TestAgent.getAgentPlan(), true, new TestProcessingTimeService());
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        operator,
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

        public static void processInputEvent(InputEvent event, RunnerContext context) {
            Long inputData = (Long) event.getInput();
            context.sendEvent(new MiddleEvent(inputData + 1));
        }

        public static void processMiddleEvent(MiddleEvent event, RunnerContext context) {
            context.sendEvent(new OutputEvent(event.getNum() * 2));
        }

        public static AgentPlan getAgentPlan() {
            try {
                Map<String, List<Action>> actionsByEvent = new HashMap<>();
                Action action1 =
                        new Action(
                                "processInputEvent",
                                new JavaFunction(
                                        TestAgent.class,
                                        "processInputEvent",
                                        new Class<?>[] {InputEvent.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.class.getName()));
                Action action2 =
                        new Action(
                                "processMiddleEvent",
                                new JavaFunction(
                                        TestAgent.class,
                                        "processMiddleEvent",
                                        new Class<?>[] {MiddleEvent.class, RunnerContext.class}),
                                Collections.singletonList(MiddleEvent.class.getName()));
                actionsByEvent.put(InputEvent.class.getName(), Collections.singletonList(action1));
                actionsByEvent.put(MiddleEvent.class.getName(), Collections.singletonList(action2));
                Map<String, Action> actions = new HashMap<>();
                actions.put(action1.getName(), action1);
                actions.put(action2.getName(), action2);
                return new AgentPlan(actions, actionsByEvent);
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
            return null;
        }
    }
}
