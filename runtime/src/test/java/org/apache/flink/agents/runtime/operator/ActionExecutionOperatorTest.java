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
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.InMemoryActionStateStore;
import org.apache.flink.agents.runtime.eventlog.FileEventLogger;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.watermark.Watermark;
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
import java.util.stream.Collectors;

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
        AgentPlan agentPlanWithStateStore = TestAgent.getAgentPlan(false);

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(
                                agentPlanWithStateStore, true, new InMemoryActionStateStore(false)),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            InMemoryActionStateStore actionStateStore =
                    (InMemoryActionStateStore)
                            operator.getDurableExecutionManager().getActionStateStore();

            assertThat(actionStateStore).isNotNull();
            assertThat(actionStateStore.getKeyedActionStates()).isEmpty();

            // Process an element and verify action state is created and managed
            testHarness.processElement(new StreamRecord<>(5L));
            operator.waitInFlightEventsFinished();

            // Verify that action states were created during processing
            Map<String, Map<String, ActionState>> actionStates =
                    actionStateStore.getKeyedActionStates();
            assertThat(actionStates).isNotEmpty();

            // Verify the content of stored action states
            assertThat(actionStates.size()).isEqualTo(1);

            // Verify each action state contains expected information
            for (Map.Entry<String, Map<String, ActionState>> outerEntry : actionStates.entrySet()) {
                for (Map.Entry<String, ActionState> entry : outerEntry.getValue().entrySet()) {
                    ActionState state = entry.getValue();
                    assertThat(state).isNotNull();
                    assertThat(state.getTaskEvent()).isNotNull();

                    // Check that output events were captured
                    assertThat(state.getOutputEvents()).isNotEmpty();
                }
            }

            // Verify output
            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo(12L);

            // Test checkpoint complete triggers cleanup
            testHarness.notifyOfCompletedCheckpoint(1L);
        }
    }

    @Test
    void testEventLogBaseDirFromAgentConfig() throws Exception {
        String baseLogDir = "/tmp/flink-agents-test";
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentConfigOptions.BASE_LOG_DIR, baseLogDir);
        AgentPlan agentPlan = TestAgent.getAgentPlanWithConfig(config);

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(agentPlan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();
            EventRouter eventRouterObj = operator.getEventRouter();
            Field eventLoggerField = EventRouter.class.getDeclaredField("eventLogger");
            eventLoggerField.setAccessible(true);
            Object eventLogger = eventLoggerField.get(eventRouterObj);
            assertThat(eventLogger).isInstanceOf(FileEventLogger.class);

            Field configField = FileEventLogger.class.getDeclaredField("config");
            configField.setAccessible(true);
            Object loggerConfig = configField.get(eventLogger);
            Field propertiesField = loggerConfig.getClass().getDeclaredField("properties");
            propertiesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> properties =
                    (Map<String, Object>) propertiesField.get(loggerConfig);
            assertThat(properties.get(FileEventLogger.BASE_LOG_DIR_PROPERTY_KEY))
                    .isEqualTo(baseLogDir);
        }
    }

    @Test
    void testActionStateStoreContentVerification() throws Exception {
        AgentPlan agentPlanWithStateStore = TestAgent.getAgentPlan(false);

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(
                                agentPlanWithStateStore, true, new InMemoryActionStateStore(false)),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            InMemoryActionStateStore actionStateStore =
                    (InMemoryActionStateStore)
                            operator.getDurableExecutionManager().getActionStateStore();

            Long inputValue = 3L;
            testHarness.processElement(new StreamRecord<>(inputValue));
            operator.waitInFlightEventsFinished();

            Map<String, Map<String, ActionState>> actionStates =
                    actionStateStore.getKeyedActionStates();
            assertThat(actionStates).hasSize(1);

            // Verify specific action states by examining the keys
            for (Map.Entry<String, Map<String, ActionState>> outerEntry : actionStates.entrySet()) {
                for (Map.Entry<String, ActionState> entry : outerEntry.getValue().entrySet()) {
                    String stateKey = entry.getKey();
                    ActionState state = entry.getValue();

                    // Verify the state key contains the expected key and action information
                    assertThat(stateKey).contains(inputValue.toString());

                    // Verify task event is properly stored
                    Event taskEvent = state.getTaskEvent();
                    assertThat(taskEvent).isNotNull();

                    // Verify memory updates contain expected data
                    if (!state.getShortTermMemoryUpdates().isEmpty()) {
                        // For action1, memory should contain input + 1
                        assertThat(state.getShortTermMemoryUpdates().get(0).getPath())
                                .isEqualTo("tmp");
                        assertThat(state.getShortTermMemoryUpdates().get(0).getValue())
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
                        assertThat(finalOutput.getOutput()).isEqualTo((inputValue + 1) * 2);
                    }
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
        AgentPlan agentPlanWithStateStore = TestAgent.getAgentPlan(false);

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(
                                agentPlanWithStateStore, true, new InMemoryActionStateStore(false)),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            // Access the action state store
            InMemoryActionStateStore actionStateStore =
                    (InMemoryActionStateStore)
                            operator.getDurableExecutionManager().getActionStateStore();

            // Process multiple elements with same key to test state persistence
            testHarness.processElement(new StreamRecord<>(1L));
            operator.waitInFlightEventsFinished();

            // Verify initial state creation
            Map<String, Map<String, ActionState>> actionStates =
                    actionStateStore.getKeyedActionStates();
            assertThat(actionStates).isNotEmpty();
            int initialStateCount = actionStates.size();

            testHarness.processElement(new StreamRecord<>(1L));
            operator.waitInFlightEventsFinished();

            // Verify state persists and grows for same key processing
            actionStates = actionStateStore.getKeyedActionStates();
            assertThat(actionStates.size()).isGreaterThanOrEqualTo(initialStateCount);

            // Process element with different key
            testHarness.processElement(new StreamRecord<>(2L));
            operator.waitInFlightEventsFinished();

            // Verify new states created for different key
            actionStates = actionStateStore.getKeyedActionStates();
            assertThat(actionStates.size()).isGreaterThan(initialStateCount);

            // Verify outputs
            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(3);
        }
    }

    @Test
    void testActionStateStoreCleanupAfterOutputEvent() throws Exception {
        AgentPlan agentPlanWithStateStore = TestAgent.getAgentPlan(false);

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(
                                agentPlanWithStateStore, true, new InMemoryActionStateStore(true)),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            // Process multiple elements with same key to test state persistence
            testHarness.processElement(new StreamRecord<>(1L));
            operator.waitInFlightEventsFinished();

            testHarness.processElement(new StreamRecord<>(2L));
            operator.waitInFlightEventsFinished();

            // Process element with different key
            testHarness.processElement(new StreamRecord<>(3L));
            operator.waitInFlightEventsFinished();

            // Verify outputs
            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(3);

            // Access the action state store
            InMemoryActionStateStore actionStateStore =
                    (InMemoryActionStateStore)
                            operator.getDurableExecutionManager().getActionStateStore();
            assertThat(actionStateStore.getKeyedActionStates()).isEmpty();
        }
    }

    @Test
    void testActionStateStoreReplayIncurNoFunctionCall() throws Exception {
        AgentPlan agentPlanWithStateStore = TestAgent.getAgentPlan(false);
        InMemoryActionStateStore actionStateStore;
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(
                                agentPlanWithStateStore, true, new InMemoryActionStateStore(false)),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            // Access the action state store
            actionStateStore =
                    (InMemoryActionStateStore)
                            operator.getDurableExecutionManager().getActionStateStore();

            Long inputValue = 7L;

            // First processing - this will execute the actual functions and store state
            testHarness.processElement(new StreamRecord<>(inputValue));
            operator.waitInFlightEventsFinished();
        }
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(
                                agentPlanWithStateStore, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            Long inputValue = 7L;

            // First processing - this will execute the actual functions and store state
            testHarness.processElement(new StreamRecord<>(inputValue));
            operator.waitInFlightEventsFinished();
            // Verify first output is correct
            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo((inputValue + 1) * 2);

            // The action state store should only have one entry
            assertThat(actionStateStore.getKeyedActionStates().get(String.valueOf(inputValue)))
                    .hasSize(2);
        }
    }

    @Test
    void testWatermark() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(TestAgent.getAgentPlan(false), true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {

            final long initialTime = 0L;

            testHarness.open();

            // Process input data 1 with key 0
            testHarness.processWatermark(new Watermark(initialTime + 1));
            testHarness.processElement(new StreamRecord<>(0L, initialTime + 2));
            testHarness.processElement(new StreamRecord<>(0L, initialTime + 3));
            testHarness.processElement(new StreamRecord<>(1L, initialTime + 4));
            testHarness.processWatermark(new Watermark(initialTime + 5));
            testHarness.processElement(new StreamRecord<>(1L, initialTime + 6));
            testHarness.processElement(new StreamRecord<>(0L, initialTime + 7));
            testHarness.processElement(new StreamRecord<>(1L, initialTime + 8));
            testHarness.processWatermark(new Watermark(initialTime + 9));

            testHarness.endInput();
            testHarness.close();

            Object[] jobOutputQueue = testHarness.getOutput().toArray();
            assertThat(jobOutputQueue.length).isEqualTo(9);

            long lastWatermark = Long.MIN_VALUE;

            for (Object obj : jobOutputQueue) {
                if (obj instanceof StreamRecord) {
                    StreamRecord<?> streamRecord = (StreamRecord<?>) obj;
                    assertThat(streamRecord.getTimestamp()).isGreaterThan(lastWatermark);
                } else if (obj instanceof Watermark) {
                    Watermark watermark = (Watermark) obj;
                    assertThat(watermark.getTimestamp()).isGreaterThan(lastWatermark);
                    lastWatermark = watermark.getTimestamp();
                }
            }
        }
    }

    /** Tests that executeAsync works correctly. */
    @Test
    void testExecuteAsyncJavaAction() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(
                                TestAgent.getAsyncAgentPlan(false), true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            // Input value 5: asyncAction1 computes 5 * 10 = 50, action2 computes 50 * 2 = 100
            testHarness.processElement(new StreamRecord<>(5L));
            operator.waitInFlightEventsFinished();

            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo(100L);
        }
    }

    /**
     * Tests that multiple executeAsync calls can be chained together. Each async operation should
     * complete before the next one starts (serial execution).
     */
    @Test
    void testMultipleExecuteAsyncCalls() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(TestAgent.getAsyncAgentPlan(true), true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            // Input value 7:
            // First async: 7 + 100 = 107
            // Second async: 107 * 2 = 214
            testHarness.processElement(new StreamRecord<>(7L));
            operator.waitInFlightEventsFinished();

            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo(214L);
        }
    }

    /**
     * Tests that executeAsync works correctly with multiple keys processed concurrently. Each key
     * should complete its async operations independently.
     */
    @Test
    void testExecuteAsyncWithMultipleKeys() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(
                                TestAgent.getAsyncAgentPlan(false), true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            // Process two elements with different keys
            // Key 3: asyncAction1 computes 3 * 10 = 30, action2 computes 30 * 2 = 60
            // Key 4: asyncAction1 computes 4 * 10 = 40, action2 computes 40 * 2 = 80
            testHarness.processElement(new StreamRecord<>(3L));
            testHarness.processElement(new StreamRecord<>(4L));
            operator.waitInFlightEventsFinished();

            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(2);

            // Check both outputs exist (order may vary due to concurrent processing)
            List<Object> outputValues =
                    recordOutput.stream().map(StreamRecord::getValue).collect(Collectors.toList());
            assertThat(outputValues).containsExactlyInAnyOrder(60L, 80L);
        }
    }

    /** Tests that durableExecute (sync) works correctly. */
    @Test
    void testDurableExecuteSyncAction() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(
                                TestAgent.getDurableSyncAgentPlan(), true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            // Input value 5: durableSyncAction computes 5 * 3 = 15
            testHarness.processElement(new StreamRecord<>(5L));
            operator.waitInFlightEventsFinished();

            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo(15L);
        }
    }

    /**
     * Tests that durableExecute with ActionStateStore can recover from cached results. This
     * verifies that on recovery, the durable execution returns cached results without re-executing
     * the supplier.
     */
    @Test
    void testDurableExecuteRecoveryFromCachedResult() throws Exception {
        AgentPlan agentPlan = TestAgent.getDurableSyncAgentPlan();
        InMemoryActionStateStore actionStateStore = new InMemoryActionStateStore(false);

        // Reset the counter before the test
        TestAgent.DURABLE_CALL_COUNTER.set(0);

        // First execution - will execute the supplier and store the result
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(7L));
            operator.waitInFlightEventsFinished();

            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(1);
            // 7 * 3 = 21
            assertThat(recordOutput.get(0).getValue()).isEqualTo(21L);

            // Verify action state was stored
            assertThat(actionStateStore.getKeyedActionStates()).isNotEmpty();

            // Verify supplier was called exactly once during first execution
            assertThat(TestAgent.DURABLE_CALL_COUNTER.get()).isEqualTo(1);
        }

        // Second execution with same action state store - should recover from cached result
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            // Process the same key - should recover from cached state
            testHarness.processElement(new StreamRecord<>(7L));
            operator.waitInFlightEventsFinished();

            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(1);
            // Should get the same result (21) from recovery
            assertThat(recordOutput.get(0).getValue()).isEqualTo(21L);

            // CRITICAL: Verify supplier was NOT called during recovery - counter should still be 1
            assertThat(TestAgent.DURABLE_CALL_COUNTER.get())
                    .as("Supplier should NOT be called during recovery")
                    .isEqualTo(1);
        }
    }

    /** Tests that durableExecute properly handles exceptions thrown by the supplier. */
    @Test
    void testDurableExecuteExceptionHandling() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(
                                TestAgent.getDurableExceptionAgentPlan(), true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            // Reset counter
            TestAgent.EXCEPTION_CALL_COUNTER.set(0);

            testHarness.processElement(new StreamRecord<>(1L));
            operator.waitInFlightEventsFinished();

            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput.size()).isEqualTo(1);
            // Verify the error was caught and handled
            assertThat(recordOutput.get(0).getValue().toString()).contains("ERROR:");

            // Verify the supplier was called
            assertThat(TestAgent.EXCEPTION_CALL_COUNTER.get()).isEqualTo(1);
        }
    }

    /**
     * Tests that exception recovery works correctly - on recovery, the cached exception should be
     * re-thrown without calling the supplier again.
     */
    @Test
    void testDurableExecuteExceptionRecovery() throws Exception {
        AgentPlan agentPlan = TestAgent.getDurableExceptionAgentPlan();
        InMemoryActionStateStore actionStateStore = new InMemoryActionStateStore(false);

        // Reset counter
        TestAgent.EXCEPTION_CALL_COUNTER.set(0);

        // First execution - will execute the supplier, throw exception, and store it
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(2L));
            operator.waitInFlightEventsFinished();

            // Verify supplier was called once
            assertThat(TestAgent.EXCEPTION_CALL_COUNTER.get()).isEqualTo(1);

            // Verify action state was stored
            assertThat(actionStateStore.getKeyedActionStates()).isNotEmpty();
        }

        // Second execution - should recover cached exception without calling supplier
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(2L));
            operator.waitInFlightEventsFinished();

            // CRITICAL: Verify supplier was NOT called during recovery
            assertThat(TestAgent.EXCEPTION_CALL_COUNTER.get())
                    .as("Supplier should NOT be called during exception recovery")
                    .isEqualTo(1);
        }
    }

    /**
     * Tests that durableExecute exception can be serialized and recovered correctly when the action
     * does NOT catch the exception (simulates built-in action behavior like ChatModelAction).
     *
     * <p>This test verifies that:
     *
     * <ul>
     *   <li>DurableExecutionException can be properly serialized by Jackson
     *   <li>On recovery, the cached exception is re-thrown without re-executing the supplier
     *   <li>The exception content (class name and message) is preserved
     * </ul>
     */
    @Test
    void testDurableExecuteExceptionRecoveryWithUncaughtException() throws Exception {
        AgentPlan agentPlan = TestAgent.getDurableExceptionUncaughtAgentPlan();
        InMemoryActionStateStore actionStateStore = new InMemoryActionStateStore(false);

        // Reset counter
        TestAgent.UNCAUGHT_EXCEPTION_CALL_COUNTER.set(0);

        String firstExecutionExceptionChain = null;

        // First execution - will execute the supplier, throw exception, and store it
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(1L));

            // This should throw because the exception is not caught in the action
            try {
                operator.waitInFlightEventsFinished();
            } catch (Exception e) {
                // Collect all exception messages in the chain
                firstExecutionExceptionChain = ExceptionUtils.stringifyException(e);
            }
        }

        // Verify supplier was called once
        assertThat(TestAgent.UNCAUGHT_EXCEPTION_CALL_COUNTER.get()).isEqualTo(1);

        // Verify exception was thrown and contains correct info somewhere in the chain
        assertThat(firstExecutionExceptionChain).isNotNull();
        assertThat(firstExecutionExceptionChain)
                .as("Exception chain should contain original class name")
                .contains("IllegalStateException");
        assertThat(firstExecutionExceptionChain)
                .as("Exception chain should contain original message")
                .contains("Simulated LLM failure");

        // Verify action state was stored with call result
        assertThat(actionStateStore.getKeyedActionStates()).isNotEmpty();

        String recoveryExceptionChain = null;

        // Second execution - should recover cached exception without calling supplier
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(1L));

            try {
                operator.waitInFlightEventsFinished();
            } catch (Exception e) {
                // Collect all exception messages in the chain
                recoveryExceptionChain = ExceptionUtils.stringifyException(e);
            }
        }

        // CRITICAL: Verify supplier was NOT called during recovery
        assertThat(TestAgent.UNCAUGHT_EXCEPTION_CALL_COUNTER.get())
                .as("Supplier should NOT be called during exception recovery")
                .isEqualTo(1);

        // Verify recovered exception contains correct information in the chain
        assertThat(recoveryExceptionChain).isNotNull();
        assertThat(recoveryExceptionChain)
                .as("Recovered exception chain should contain original class name")
                .contains("IllegalStateException");
        assertThat(recoveryExceptionChain)
                .as("Recovered exception chain should contain original message")
                .contains("Simulated LLM failure");
    }

    /**
     * Tests that durableExecuteAsync exception can be serialized and recovered correctly.
     *
     * <p>This test verifies async exception handling works the same way as sync.
     */
    @Test
    void testDurableExecuteAsyncExceptionRecovery() throws Exception {
        AgentPlan agentPlan = TestAgent.getDurableAsyncExceptionAgentPlan();
        InMemoryActionStateStore actionStateStore = new InMemoryActionStateStore(false);

        // Reset counter
        TestAgent.ASYNC_EXCEPTION_CALL_COUNTER.set(0);

        String firstExecutionExceptionChain = null;

        // First execution - will execute the async supplier, throw exception, and store it
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(1L));

            try {
                operator.waitInFlightEventsFinished();
            } catch (Exception e) {
                firstExecutionExceptionChain = ExceptionUtils.stringifyException(e);
            }
        }

        // Verify supplier was called once
        assertThat(TestAgent.ASYNC_EXCEPTION_CALL_COUNTER.get()).isEqualTo(1);

        // Verify exception was thrown
        assertThat(firstExecutionExceptionChain).isNotNull();
        assertThat(firstExecutionExceptionChain)
                .as("Exception chain should contain original message")
                .contains("Async operation failed");

        // Verify action state was stored
        assertThat(actionStateStore.getKeyedActionStates()).isNotEmpty();

        String recoveryExceptionChain = null;

        // Second execution - should recover cached exception without calling supplier
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(1L));

            try {
                operator.waitInFlightEventsFinished();
            } catch (Exception e) {
                recoveryExceptionChain = ExceptionUtils.stringifyException(e);
            }
        }

        // CRITICAL: Verify supplier was NOT called during recovery
        assertThat(TestAgent.ASYNC_EXCEPTION_CALL_COUNTER.get())
                .as("Supplier should NOT be called during async exception recovery")
                .isEqualTo(1);

        // Verify recovered exception contains correct information
        assertThat(recoveryExceptionChain).isNotNull();
        assertThat(recoveryExceptionChain)
                .as("Recovered exception chain should contain original message")
                .contains("Async operation failed");
    }

    public static class TestAgent {

        /** Counter to track how many times the durable supplier is executed. */
        public static final java.util.concurrent.atomic.AtomicInteger DURABLE_CALL_COUNTER =
                new java.util.concurrent.atomic.AtomicInteger(0);

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

        public static void asyncAction1(InputEvent event, RunnerContext context) {
            Long inputData = (Long) event.getInput();
            try {
                Long result =
                        context.durableExecuteAsync(
                                new DurableCallable<Long>() {
                                    @Override
                                    public String getId() {
                                        return "async-multiply";
                                    }

                                    @Override
                                    public Class<Long> getResultClass() {
                                        return Long.class;
                                    }

                                    @Override
                                    public Long call() {
                                        try {
                                            Thread.sleep(50);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                        return inputData * 10;
                                    }
                                });

                MemoryObject mem = context.getShortTermMemory();
                mem.set("tmp", result);
                context.sendEvent(new MiddleEvent(result));
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
        }

        public static void multiAsyncAction(InputEvent event, RunnerContext context) {
            Long inputData = (Long) event.getInput();
            try {
                Long result1 =
                        context.durableExecuteAsync(
                                new DurableCallable<Long>() {
                                    @Override
                                    public String getId() {
                                        return "async-add";
                                    }

                                    @Override
                                    public Class<Long> getResultClass() {
                                        return Long.class;
                                    }

                                    @Override
                                    public Long call() {
                                        try {
                                            Thread.sleep(30);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                        return inputData + 100;
                                    }
                                });

                Long result2 =
                        context.durableExecuteAsync(
                                new DurableCallable<Long>() {
                                    @Override
                                    public String getId() {
                                        return "async-multiply";
                                    }

                                    @Override
                                    public Class<Long> getResultClass() {
                                        return Long.class;
                                    }

                                    @Override
                                    public Long call() {
                                        try {
                                            Thread.sleep(30);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                        return result1 * 2;
                                    }
                                });

                MemoryObject mem = context.getShortTermMemory();
                mem.set("multiAsyncResult", result2);
                context.sendEvent(new OutputEvent(result2));
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
        }

        public static void durableSyncAction(InputEvent event, RunnerContext context) {
            Long inputData = (Long) event.getInput();
            try {
                Long result =
                        context.durableExecute(
                                new DurableCallable<Long>() {
                                    @Override
                                    public String getId() {
                                        return "sync-compute";
                                    }

                                    @Override
                                    public Class<Long> getResultClass() {
                                        return Long.class;
                                    }

                                    @Override
                                    public Long call() {
                                        DURABLE_CALL_COUNTER.incrementAndGet();
                                        return inputData * 3;
                                    }
                                });

                context.sendEvent(new OutputEvent(result));
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
        }

        public static final java.util.concurrent.atomic.AtomicInteger EXCEPTION_CALL_COUNTER =
                new java.util.concurrent.atomic.AtomicInteger(0);

        public static void durableExceptionAction(InputEvent event, RunnerContext context) {
            try {
                context.durableExecute(
                        new DurableCallable<String>() {
                            @Override
                            public String getId() {
                                return "exception-action";
                            }

                            @Override
                            public Class<String> getResultClass() {
                                return String.class;
                            }

                            @Override
                            public String call() {
                                EXCEPTION_CALL_COUNTER.incrementAndGet();
                                throw new RuntimeException("Test exception from durableExecute");
                            }
                        });
            } catch (Exception e) {
                context.sendEvent(new OutputEvent("ERROR:" + e.getMessage()));
            }
        }

        public static AgentPlan getAgentPlan(boolean testMemoryAccessOutOfMailbox) {
            return getAgentPlanWithConfig(new AgentConfiguration(), testMemoryAccessOutOfMailbox);
        }

        public static AgentPlan getAgentPlanWithConfig(AgentConfiguration config) {
            return getAgentPlanWithConfig(config, false);
        }

        private static AgentPlan getAgentPlanWithConfig(
                AgentConfiguration config, boolean testMemoryAccessOutOfMailbox) {
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

        /**
         * Creates an AgentPlan for testing async execution.
         *
         * @param useMultiAsync if true, uses multiAsyncAction which chains multiple async calls
         * @return AgentPlan configured with async actions
         */
        public static AgentPlan getAsyncAgentPlan(boolean useMultiAsync) {
            try {
                Map<String, List<Action>> actionsByEvent = new HashMap<>();
                Map<String, Action> actions = new HashMap<>();

                if (useMultiAsync) {
                    // Use multiAsyncAction that chains multiple executeAsync calls
                    Action multiAsyncAction =
                            new Action(
                                    "multiAsyncAction",
                                    new JavaFunction(
                                            TestAgent.class,
                                            "multiAsyncAction",
                                            new Class<?>[] {InputEvent.class, RunnerContext.class}),
                                    Collections.singletonList(InputEvent.class.getName()));
                    actionsByEvent.put(
                            InputEvent.class.getName(),
                            Collections.singletonList(multiAsyncAction));
                    actions.put(multiAsyncAction.getName(), multiAsyncAction);
                } else {
                    // Use asyncAction1 -> action2 chain
                    Action asyncAction1 =
                            new Action(
                                    "asyncAction1",
                                    new JavaFunction(
                                            TestAgent.class,
                                            "asyncAction1",
                                            new Class<?>[] {InputEvent.class, RunnerContext.class}),
                                    Collections.singletonList(InputEvent.class.getName()));
                    Action action2 =
                            new Action(
                                    "action2",
                                    new JavaFunction(
                                            TestAgent.class,
                                            "action2",
                                            new Class<?>[] {
                                                MiddleEvent.class, RunnerContext.class
                                            }),
                                    Collections.singletonList(MiddleEvent.class.getName()));
                    actionsByEvent.put(
                            InputEvent.class.getName(), Collections.singletonList(asyncAction1));
                    actionsByEvent.put(
                            MiddleEvent.class.getName(), Collections.singletonList(action2));
                    actions.put(asyncAction1.getName(), asyncAction1);
                    actions.put(action2.getName(), action2);
                }

                return new AgentPlan(actions, actionsByEvent, new HashMap<>());
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
            return null;
        }

        public static AgentPlan getDurableSyncAgentPlan() {
            try {
                Map<String, List<Action>> actionsByEvent = new HashMap<>();
                Map<String, Action> actions = new HashMap<>();

                Action durableSyncAction =
                        new Action(
                                "durableSyncAction",
                                new JavaFunction(
                                        TestAgent.class,
                                        "durableSyncAction",
                                        new Class<?>[] {InputEvent.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.class.getName()));
                actionsByEvent.put(
                        InputEvent.class.getName(), Collections.singletonList(durableSyncAction));
                actions.put(durableSyncAction.getName(), durableSyncAction);

                return new AgentPlan(actions, actionsByEvent, new HashMap<>());
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
            return null;
        }

        public static AgentPlan getDurableExceptionAgentPlan() {
            try {
                Map<String, List<Action>> actionsByEvent = new HashMap<>();
                Map<String, Action> actions = new HashMap<>();

                Action exceptionAction =
                        new Action(
                                "durableExceptionAction",
                                new JavaFunction(
                                        TestAgent.class,
                                        "durableExceptionAction",
                                        new Class<?>[] {InputEvent.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.class.getName()));
                actionsByEvent.put(
                        InputEvent.class.getName(), Collections.singletonList(exceptionAction));
                actions.put(exceptionAction.getName(), exceptionAction);

                return new AgentPlan(actions, actionsByEvent, new HashMap<>());
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
            return null;
        }

        // ==================== Actions for Exception Recovery Tests ====================

        /**
         * Counter to track how many times the uncaught exception supplier is executed. Used to
         * verify that on recovery, the supplier is not re-executed.
         */
        public static final java.util.concurrent.atomic.AtomicInteger
                UNCAUGHT_EXCEPTION_CALL_COUNTER = new java.util.concurrent.atomic.AtomicInteger(0);

        /**
         * Action that uses durableExecute and does NOT catch the exception. This simulates the
         * behavior of built-in actions like ChatModelAction.
         */
        public static void durableExceptionUncaughtAction(InputEvent event, RunnerContext context) {
            try {
                context.durableExecute(
                        new DurableCallable<String>() {
                            @Override
                            public String getId() {
                                return "uncaught-exception-action";
                            }

                            @Override
                            public Class<String> getResultClass() {
                                return String.class;
                            }

                            @Override
                            public String call() {
                                UNCAUGHT_EXCEPTION_CALL_COUNTER.incrementAndGet();
                                throw new IllegalStateException(
                                        "Simulated LLM failure: Connection timeout");
                            }
                        });
            } catch (Exception e) {
                // Re-throw without wrapping - simulates built-in action behavior
                ExceptionUtils.rethrow(e);
            }
        }

        /**
         * Counter to track how many times the async exception supplier is executed. Used to verify
         * that on recovery, the supplier is not re-executed.
         */
        public static final java.util.concurrent.atomic.AtomicInteger ASYNC_EXCEPTION_CALL_COUNTER =
                new java.util.concurrent.atomic.AtomicInteger(0);

        /**
         * Action that uses durableExecuteAsync and does NOT catch the exception. This simulates
         * async operations that fail.
         */
        public static void durableAsyncExceptionAction(InputEvent event, RunnerContext context) {
            try {
                context.durableExecuteAsync(
                        new DurableCallable<String>() {
                            @Override
                            public String getId() {
                                return "async-exception-action";
                            }

                            @Override
                            public Class<String> getResultClass() {
                                return String.class;
                            }

                            @Override
                            public String call() {
                                ASYNC_EXCEPTION_CALL_COUNTER.incrementAndGet();
                                throw new RuntimeException("Async operation failed: API error");
                            }
                        });
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
        }

        public static AgentPlan getDurableExceptionUncaughtAgentPlan() {
            try {
                Map<String, List<Action>> actionsByEvent = new HashMap<>();
                Map<String, Action> actions = new HashMap<>();

                Action exceptionAction =
                        new Action(
                                "durableExceptionUncaughtAction",
                                new JavaFunction(
                                        TestAgent.class,
                                        "durableExceptionUncaughtAction",
                                        new Class<?>[] {InputEvent.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.class.getName()));
                actionsByEvent.put(
                        InputEvent.class.getName(), Collections.singletonList(exceptionAction));
                actions.put(exceptionAction.getName(), exceptionAction);

                return new AgentPlan(actions, actionsByEvent, new HashMap<>());
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
            return null;
        }

        public static AgentPlan getDurableAsyncExceptionAgentPlan() {
            try {
                Map<String, List<Action>> actionsByEvent = new HashMap<>();
                Map<String, Action> actions = new HashMap<>();

                Action exceptionAction =
                        new Action(
                                "durableAsyncExceptionAction",
                                new JavaFunction(
                                        TestAgent.class,
                                        "durableAsyncExceptionAction",
                                        new Class<?>[] {InputEvent.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.class.getName()));
                actionsByEvent.put(
                        InputEvent.class.getName(), Collections.singletonList(exceptionAction));
                actions.put(exceptionAction.getName(), exceptionAction);

                return new AgentPlan(actions, actionsByEvent, new HashMap<>());
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
