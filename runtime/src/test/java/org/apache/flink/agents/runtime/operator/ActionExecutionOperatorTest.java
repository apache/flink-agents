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
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.Action;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.InMemoryActionStateStore;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.mailbox.TaskMailbox;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.flink.util.ExceptionUtils;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
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
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(0L));
            operator.waitInFlightEventsFinished();
            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo(2L);

            testHarness.processElement(new StreamRecord<>(1L));
            operator.waitInFlightEventsFinished();
            recordOutput = (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(2);
            assertThat(recordOutput.get(1).getValue()).isEqualTo(4L);
        }
    }

    @Test
    void testSameKeyDataAreProcessedInOrder() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(TestAgent.getAgentPlan(false), true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            // Process input data 1 with key 0
            testHarness.processElement(new StreamRecord<>(0L));
            // Process input data 2, which has the same key (0)
            testHarness.processElement(new StreamRecord<>(0L));
            // Since both pieces of data share the same key, we should consolidate them and process
            // only input data 1.
            // This means we need one mail to execute the action1 action for input data 1.
            assertMailboxSizeAndRun(testHarness.getTaskMailbox(), 1);
            // After executing this mail, we will have another mail to execute the action2 action
            // for input data 1.
            assertMailboxSizeAndRun(testHarness.getTaskMailbox(), 1);
            // Once the above mails are executed, we should get a single output result from input
            // data 1.
            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo(2L);

            // After the processing of input data 1 is finished, we can proceed to process input
            // data 2 and obtain its result.
            operator.waitInFlightEventsFinished();
            recordOutput = (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(2);
            assertThat(recordOutput.get(1).getValue()).isEqualTo(2L);
        }
    }

    @Test
    void testDifferentKeyDataCanRunConcurrently() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(TestAgent.getAgentPlan(false), true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();

            // Process input data 1 with key 0
            testHarness.processElement(new StreamRecord<>(0L));
            // Process input data 2, which has the different key (1)
            testHarness.processElement(new StreamRecord<>(1L));
            // Since the two input data items have different keys, they can be processed in
            // parallel.
            // As a result, we should have two separate mails to execute the action1 for each of
            // them.
            assertMailboxSizeAndRun(testHarness.getTaskMailbox(), 2);
            // After these two mails are executed, there should be another two mails — one for each
            // input data item — to execute the corresponding action2.
            assertMailboxSizeAndRun(testHarness.getTaskMailbox(), 2);
            // Once both action2 operations are completed, we should receive two output data items,
            // each corresponding to one of the original inputs.
            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(2);
            assertThat(recordOutput.get(0).getValue()).isEqualTo(2L);
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
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(0L));
            assertThatThrownBy(() -> operator.waitInFlightEventsFinished())
                    .hasCauseInstanceOf(ActionExecutionOperator.ActionTaskExecutionException.class)
                    .rootCause()
                    .hasMessageContaining("Expected to be running on the task mailbox thread");
        }
    }

    @Test
    void testInMemoryActionStateStoreIntegration() throws Exception {
        // Create agent plan with inmemory action state store configuration
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentConfigOptions.ACTION_STATE_STORE_BACKEND, "inmemory");
        AgentPlan agentPlanWithStateStore = TestAgent.getAgentPlanWithConfig(false, config);

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(
                                agentPlanWithStateStore, true, new InMemoryActionStateStore()),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            // Use reflection to access the action state store for validation
            Field actionStateStoreField =
                    ActionExecutionOperator.class.getDeclaredField("actionStateStore");
            actionStateStoreField.setAccessible(true);
            InMemoryActionStateStore actionStateStore =
                    (InMemoryActionStateStore) actionStateStoreField.get(operator);

            assertThat(actionStateStore).isNotNull();
            assertThat(actionStateStore.getActionStates()).isEmpty();

            // Process an element and verify action state is created and managed
            testHarness.processElement(new StreamRecord<>(5L));
            operator.waitInFlightEventsFinished();

            // Verify that action states were created during processing
            Map<String, ActionState> actionStates = actionStateStore.getActionStates();
            assertThat(actionStates).isNotEmpty();

            // Verify the content of stored action states
            assertThat(actionStates.size()).isEqualTo(2);

            // Verify each action state contains expected information
            for (Map.Entry<String, ActionState> entry : actionStates.entrySet()) {
                ActionState state = entry.getValue();
                assertThat(state).isNotNull();
                assertThat(state.getTaskEvent()).isNotNull();

                // Check that output events were captured
                assertThat(state.getOutputEvents()).isNotEmpty();

                // Verify the generated action task is empty (action completed)
                assertThat(state.getGeneratedActionTask()).isEmpty();
            }

            // Verify output
            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo(12L);

            // Test checkpoint complete triggers cleanup
            testHarness.notifyOfCompletedCheckpoint(1L);
            // After cleanup, the action states should be cleared
            assertThat(actionStateStore.getActionStates()).isEmpty();
        }
    }

    @Test
    void testActionStateStoreContentVerification() throws Exception {
        // Create agent plan with inmemory action state store configuration
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentConfigOptions.ACTION_STATE_STORE_BACKEND, "inmemory");
        AgentPlan agentPlanWithStateStore = TestAgent.getAgentPlanWithConfig(false, config);

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(
                                agentPlanWithStateStore, true, new InMemoryActionStateStore()),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            // Use reflection to access the action state store for validation
            Field actionStateStoreField =
                    ActionExecutionOperator.class.getDeclaredField("actionStateStore");
            actionStateStoreField.setAccessible(true);
            InMemoryActionStateStore actionStateStore =
                    (InMemoryActionStateStore) actionStateStoreField.get(operator);

            Long inputValue = 3L;
            testHarness.processElement(new StreamRecord<>(inputValue));
            operator.waitInFlightEventsFinished();

            Map<String, ActionState> actionStates = actionStateStore.getActionStates();
            assertThat(actionStates).hasSize(2);

            // Verify specific action states by examining the keys
            for (Map.Entry<String, ActionState> entry : actionStates.entrySet()) {
                String stateKey = entry.getKey();
                ActionState state = entry.getValue();

                // Verify the state key contains the expected key and action information
                assertThat(stateKey).contains(inputValue.toString());

                // Verify task event is properly stored
                Event taskEvent = state.getTaskEvent();
                assertThat(taskEvent).isNotNull();

                // Verify memory updates contain expected data
                if (!state.getMemoryUpdates().isEmpty()) {
                    // For action1, memory should contain input + 1
                    assertThat(state.getMemoryUpdates().get(0).getPath()).isEqualTo("tmp");
                    assertThat(state.getMemoryUpdates().get(0).getValue())
                            .isEqualTo(inputValue + 1);
                }

                // Verify output events are captured
                assertThat(state.getOutputEvents()).isNotEmpty();

                // Check the type of events in the output
                Event outputEvent = state.getOutputEvents().get(0);
                assertThat(outputEvent).isNotNull();
                if (outputEvent instanceof TestAgent.MiddleEvent) {
                    TestAgent.MiddleEvent middleEvent = (TestAgent.MiddleEvent) outputEvent;
                    assertThat(middleEvent.getNum()).isEqualTo(inputValue + 1);
                } else if (outputEvent instanceof OutputEvent) {
                    OutputEvent finalOutput = (OutputEvent) outputEvent;
                    assertThat(finalOutput.getOutput())
                            .isEqualTo((inputValue + 1) * 2); // (3+1)*2 = 8
                }
            }

            // Verify final output
            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo((inputValue + 1) * 2);
        }
    }

    @Test
    void testActionStateStoreStateManagement() throws Exception {
        // Create agent plan with stateful actions
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentConfigOptions.ACTION_STATE_STORE_BACKEND, "inmemory");
        AgentPlan agentPlanWithStateStore = TestAgent.getAgentPlanWithConfig(false, config);

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(
                                agentPlanWithStateStore, true, new InMemoryActionStateStore()),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            // Access the action state store
            java.lang.reflect.Field actionStateStoreField =
                    ActionExecutionOperator.class.getDeclaredField("actionStateStore");
            actionStateStoreField.setAccessible(true);
            InMemoryActionStateStore actionStateStore =
                    (InMemoryActionStateStore) actionStateStoreField.get(operator);

            // Process multiple elements with same key to test state persistence
            testHarness.processElement(new StreamRecord<>(1L));
            operator.waitInFlightEventsFinished();

            // Verify initial state creation
            Map<String, ActionState> actionStates = actionStateStore.getActionStates();
            assertThat(actionStates).isNotEmpty();
            int initialStateCount = actionStates.size();

            testHarness.processElement(new StreamRecord<>(1L));
            operator.waitInFlightEventsFinished();

            // Verify state persists and grows for same key processing
            actionStates = actionStateStore.getActionStates();
            assertThat(actionStates.size()).isGreaterThanOrEqualTo(initialStateCount);

            // Process element with different key
            testHarness.processElement(new StreamRecord<>(2L));
            operator.waitInFlightEventsFinished();

            // Verify new states created for different key
            actionStates = actionStateStore.getActionStates();
            assertThat(actionStates.size()).isGreaterThan(initialStateCount);

            // Verify outputs
            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(3);
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
            return getAgentPlanWithConfig(testMemoryAccessOutOfMailbox, new AgentConfiguration());
        }

        private static AgentPlan getAgentPlanWithConfig(
                boolean testMemoryAccessOutOfMailbox, AgentConfiguration config) {
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

                return new AgentPlan(actions, actionsByEvent, new HashMap<>(), config);
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
            return null;
        }
    }

    private static void assertMailboxSizeAndRun(TaskMailbox mailbox, int expectedSize)
            throws Exception {
        assertThat(mailbox.size()).isEqualTo(expectedSize);
        for (int i = 0; i < expectedSize; i++) {
            mailbox.take(TaskMailbox.MIN_PRIORITY).run();
        }
    }
}
