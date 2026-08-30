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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.MemoryRef;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ShortTermWriteEvent;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.listener.EventListener;
import org.apache.flink.agents.api.logger.EventLogger;
import org.apache.flink.agents.api.logger.EventLoggerConfig;
import org.apache.flink.agents.api.logger.EventLoggerFactory;
import org.apache.flink.agents.api.logger.EventLoggerOpenParams;
import org.apache.flink.agents.api.logger.LoggerType;
import org.apache.flink.agents.api.memory.MemorySet;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.trace.ExecutionLifecycleEvents;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.ExecutionTraceContext;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.actions.ToolCallAction;
import org.apache.flink.agents.plan.resourceprovider.JavaSerializableResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.apache.flink.agents.plan.tools.FunctionTool;
import org.apache.flink.agents.runtime.ResourceCache;
import org.apache.flink.agents.runtime.actionstate.ActionState;
import org.apache.flink.agents.runtime.actionstate.ActionStateSerde;
import org.apache.flink.agents.runtime.actionstate.ActionStateUtil;
import org.apache.flink.agents.runtime.actionstate.CallResult;
import org.apache.flink.agents.runtime.actionstate.InMemoryActionStateStore;
import org.apache.flink.agents.runtime.eventlog.EventLogWriter;
import org.apache.flink.agents.runtime.eventlog.FileEventLogger;
import org.apache.flink.agents.runtime.eventlog.Slf4jEventLogger;
import org.apache.flink.agents.runtime.memory.Mem0LongTermMemory;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.runtime.state.KeyGroupRangeAssignment;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.StreamOperatorStateHandler;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.mailbox.TaskMailbox;
import org.apache.flink.streaming.util.AbstractStreamOperatorTestHarness;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.flink.util.ExceptionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

/** Tests for {@link ActionExecutionOperator}. */
public class ActionExecutionOperatorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @BeforeEach
    void resetReconcilableFixtures() {
        TestAgent.resetReconcilableRecoveryFixture();
        TestAgent.resetMixedRecoveryFixture();
        TestAgent.FOLLOWING_ACTION_EXECUTED.set(false);
        RecordingEventLogger.reset();
        EventLoggerFactory.registerFactory(LoggerType.SLF4J, config -> new RecordingEventLogger());
    }

    @AfterEach
    void restoreEventLoggerFactory() {
        EventLoggerFactory.registerFactory(LoggerType.SLF4J, Slf4jEventLogger::new);
    }

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
    void testJavaEventAttachmentsAreOffloadedAndResolvedBetweenActions() throws Exception {
        InMemoryActionStateStore actionStateStore = new InMemoryActionStateStore(false);
        AgentPlan agentPlan = TestAgent.getEventAttachmentAgentPlan();
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            List<Event> eventsAtSendBoundary = new ArrayList<>();
            operator.getEventRouter()
                    .addEventListener(
                            (context, event) -> {
                                if (TestAgent.ATTACHMENT_EVENT_TYPE.equals(event.getType())) {
                                    eventsAtSendBoundary.add(event);
                                }
                            });

            testHarness.processElement(new StreamRecord<>(1L));
            operator.waitInFlightEventsFinished();

            assertThat(eventsAtSendBoundary).hasSize(1);
            Event runtimeEvent = eventsAtSendBoundary.get(0);
            Object reference = runtimeEvent.getAttachment(TestAgent.ATTACHMENT_KEY);
            assertThat(reference).isInstanceOf(MemoryRef.class);
            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput).hasSize(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo(Map.of("value", 1L));

            ActionState actionState =
                    actionStateStore.get(
                            1L,
                            0L,
                            agentPlan.getActions().get("receiveEventAttachment"),
                            runtimeEvent);
            assertThat(actionState).isNotNull();
            assertThat(actionState.getTaskEvent().getAttachment(TestAgent.ATTACHMENT_KEY))
                    .isSameAs(reference);
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
    void testRestoreOnlyResumesKeysOwnedByCurrentSubtask() throws Exception {
        final int maxParallelism = 4;
        final int oldParallelism = 1;
        final int newParallelism = 2;
        final long key = 1L;

        OperatorSubtaskState snapshot;
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(TestAgent.getAgentPlan(false), true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class),
                        maxParallelism,
                        oldParallelism,
                        0)) {
            testHarness.open();
            testHarness.processElement(new StreamRecord<>(key));
            assertThat(testHarness.getTaskMailbox().size()).isEqualTo(1);
            snapshot = testHarness.snapshot(1L, 1L);
        }

        int ownerSubtask =
                KeyGroupRangeAssignment.computeOperatorIndexForKeyGroup(
                        maxParallelism,
                        newParallelism,
                        KeyGroupRangeAssignment.assignToKeyGroup(key, maxParallelism));
        int nonOwnerSubtask = 1 - ownerSubtask;

        OperatorSubtaskState ownerState =
                AbstractStreamOperatorTestHarness.repartitionOperatorState(
                        snapshot, maxParallelism, oldParallelism, newParallelism, ownerSubtask);
        OperatorSubtaskState nonOwnerState =
                AbstractStreamOperatorTestHarness.repartitionOperatorState(
                        snapshot, maxParallelism, oldParallelism, newParallelism, nonOwnerSubtask);

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> ownerHarness =
                        new KeyedOneInputStreamOperatorTestHarness<>(
                                new ActionExecutionOperatorFactory(
                                        TestAgent.getAgentPlan(false), true),
                                (KeySelector<Long, Long>) value -> value,
                                TypeInformation.of(Long.class),
                                maxParallelism,
                                newParallelism,
                                ownerSubtask);
                KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> nonOwnerHarness =
                        new KeyedOneInputStreamOperatorTestHarness<>(
                                new ActionExecutionOperatorFactory(
                                        TestAgent.getAgentPlan(false), true),
                                (KeySelector<Long, Long>) value -> value,
                                TypeInformation.of(Long.class),
                                maxParallelism,
                                newParallelism,
                                nonOwnerSubtask)) {
            ownerHarness.initializeState(ownerState);
            nonOwnerHarness.initializeState(nonOwnerState);

            ownerHarness.open();
            nonOwnerHarness.open();

            assertThat(ownerHarness.getTaskMailbox().size()).isEqualTo(1);
            assertThat(nonOwnerHarness.getTaskMailbox().size()).isZero();
            assertThat(
                            ((ActionExecutionOperator<Long, Object>) ownerHarness.getOperator())
                                    .getOperatorStateManager()
                                    .getProcessingKeys())
                    .containsExactly(key);
            assertThat(
                            ((ActionExecutionOperator<Long, Object>) nonOwnerHarness.getOperator())
                                    .getOperatorStateManager()
                                    .getProcessingKeys())
                    .isEmpty();

            OperatorSubtaskState secondCheckpoint =
                    AbstractStreamOperatorTestHarness.repackageState(
                            ownerHarness.snapshot(2L, 2L), nonOwnerHarness.snapshot(2L, 2L));
            OperatorSubtaskState secondRestoreOwnerState =
                    AbstractStreamOperatorTestHarness.repartitionOperatorState(
                            secondCheckpoint,
                            maxParallelism,
                            newParallelism,
                            newParallelism,
                            ownerSubtask);

            try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> restoredOwnerHarness =
                    new KeyedOneInputStreamOperatorTestHarness<>(
                            new ActionExecutionOperatorFactory(TestAgent.getAgentPlan(false), true),
                            (KeySelector<Long, Long>) value -> value,
                            TypeInformation.of(Long.class),
                            maxParallelism,
                            newParallelism,
                            ownerSubtask)) {
                restoredOwnerHarness.initializeState(secondRestoreOwnerState);
                restoredOwnerHarness.open();

                assertThat(restoredOwnerHarness.getTaskMailbox().size()).isEqualTo(1);
            }
        }
    }

    @Test
    void pendingActionConfigSurvivesRestore() throws Exception {
        final long key = 7L;
        Action configuredAction =
                new Action(
                        "configuredAction",
                        new JavaFunction(
                                TestAgent.class,
                                "action1",
                                new Class<?>[] {Event.class, RunnerContext.class}),
                        Collections.singletonList(InputEvent.EVENT_TYPE),
                        Collections.singletonMap("mode", "strict"));
        AgentPlan agentPlan =
                new AgentPlan(
                        Collections.singletonMap(configuredAction.getName(), configuredAction));

        OperatorSubtaskState snapshot;
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(agentPlan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            testHarness.processElement(new StreamRecord<>(key));
            assertThat(testHarness.getTaskMailbox().size()).isEqualTo(1);
            snapshot = testHarness.snapshot(1L, 1L);
        }

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> restoredHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(agentPlan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            restoredHarness.initializeState(snapshot);
            restoredHarness.open();

            ActionExecutionOperator<Long, Object> restoredOperator =
                    (ActionExecutionOperator<Long, Object>) restoredHarness.getOperator();
            restoredOperator.setCurrentKey(key);
            ActionTask restoredTask =
                    restoredOperator.getOperatorStateManager().pollNextActionTask();

            assertThat(restoredTask).isNotNull();
            assertThat(restoredTask.action.getConfig()).containsEntry("mode", "strict");
        }
    }

    @Test
    void testRestoredActionUsesSameTextualContextKeyForLtmWriteAndCleanup() throws Exception {
        AgentPlan agentPlan = TestAgent.getFailedActionAfterLtmAgentPlan();
        OperatorSubtaskState snapshot;
        long key = 1L << 32;
        String expectedContextKey = "4294967296";

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(agentPlan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            testHarness.processElement(new StreamRecord<>(key));
            assertThat(testHarness.getTaskMailbox().size()).isEqualTo(1);
            snapshot = testHarness.snapshot(1L, 1L);
        }

        RecordingMem0LongTermMemory ltm = new RecordingMem0LongTermMemory();
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> restoredHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(agentPlan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            restoredHarness.initializeState(snapshot);
            restoredHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) restoredHarness.getOperator();
            replaceOperatorLtm(operator, ltm);

            assertThatThrownBy(operator::waitInFlightEventsFinished)
                    .hasCauseInstanceOf(ActionExecutionOperator.ActionTaskExecutionException.class)
                    .rootCause()
                    .hasMessageContaining("first action failed after LTM");

            assertThat(ltm.recordedKeys()).containsExactly(expectedContextKey);
            assertThat(ltm.drainedObservationKeys()).containsExactly(expectedContextKey);
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
    void testMailboxSubmittedActionTaskPropagatesErrorAndClosesActionLifecycle() throws Exception {
        AgentPlan basePlan = TestAgent.getLinkageErrorAgentPlan();
        AgentPlan agentPlan =
                new AgentPlan(
                        basePlan.getActions(),
                        basePlan.getResourceProviders(),
                        traceEnabledConfig(),
                        basePlan.getAgentName());

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(agentPlan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(0L));
            assertThatThrownBy(() -> operator.waitInFlightEventsFinished())
                    .hasCauseInstanceOf(ActionExecutionOperator.ActionTaskExecutionException.class)
                    .rootCause()
                    .isInstanceOf(NoClassDefFoundError.class)
                    .hasMessageContaining("synthetic missing runtime dependency");
        }

        RecordedEvent started =
                findRecordedLifecycleEvent(
                        ExecutionLifecycleEvents.EXECUTION_STARTED_EVENT_TYPE,
                        "linkageErrorAction",
                        ExecutionLifecycleEvents.STATUS_STARTED);
        RecordedEvent failed =
                findRecordedLifecycleEvent(
                        ExecutionLifecycleEvents.EXECUTION_FAILED_EVENT_TYPE,
                        "linkageErrorAction",
                        ExecutionLifecycleEvents.STATUS_FAILED);
        assertThat(failed.traceContext().getExecutionId())
                .isEqualTo(started.traceContext().getExecutionId());
        assertThat(failed.event.getAttr("errorType"))
                .isEqualTo(NoClassDefFoundError.class.getName());
    }

    @Test
    void testToolLinkageErrorEmitsFailedLifecycleBeforeActionFailure() throws Exception {
        AgentPlan basePlan = TestAgent.getLinkageErrorToolAgentPlan();
        AgentPlan agentPlan =
                new AgentPlan(
                        basePlan.getActions(),
                        basePlan.getResourceProviders(),
                        traceEnabledConfig(),
                        basePlan.getAgentName());

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(agentPlan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(0L));
            assertThatThrownBy(() -> operator.waitInFlightEventsFinished())
                    .hasCauseInstanceOf(ActionExecutionOperator.ActionTaskExecutionException.class)
                    .rootCause()
                    .isInstanceOf(NoClassDefFoundError.class)
                    .hasMessageContaining("synthetic missing runtime dependency");
        }

        RecordedEvent started =
                findRecordedLifecycleEvent(
                        ExecutionLifecycleEvents.EXECUTION_STARTED_EVENT_TYPE,
                        "linkageErrorTool",
                        ExecutionLifecycleEvents.STATUS_STARTED);
        RecordedEvent failed =
                findRecordedLifecycleEvent(
                        ExecutionLifecycleEvents.EXECUTION_FAILED_EVENT_TYPE,
                        "linkageErrorTool",
                        ExecutionLifecycleEvents.STATUS_FAILED);
        assertThat(started.traceContext().getEntityType())
                .isEqualTo(ExecutionReporter.EntityTypes.TOOL);
        assertThat(failed.traceContext().getExecutionId())
                .isEqualTo(started.traceContext().getExecutionId());
        assertThat(failed.event.getAttr("errorType"))
                .isEqualTo(NoClassDefFoundError.class.getName());
    }

    @Test
    void testUnsupportedObservationValueIsSkippedWithoutChangingActionSuccess() throws Exception {
        InMemoryActionStateStore actionStateStore = new InMemoryActionStateStore(false);
        AgentPlan agentPlan = TestAgent.getBestEffortMemoryObservationPlan();
        try (KeyedOneInputStreamOperatorTestHarness<String, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        (KeySelector<Long, String>) String::valueOf,
                        TypeInformation.of(String.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(7L));
            operator.waitInFlightEventsFinished();

            List<StreamRecord<Object>> output =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(output).singleElement().extracting(StreamRecord::getValue).isEqualTo(7L);

            ActionState actionState =
                    actionStateStore.get(
                            "7",
                            0L,
                            agentPlan.getActions().get("bestEffortMemoryObservationAction"),
                            new InputEvent(7L));
            assertThat(actionState).isNotNull();
            assertThat(actionState.getOutputEvents())
                    .filteredOn(ShortTermWriteEvent.class::isInstance)
                    .singleElement()
                    .extracting(event -> ((ShortTermWriteEvent) event).getValue())
                    .isEqualTo(Map.of("valid", 7));
        }
    }

    @Test
    void testFailedActionAfterLtmDiscardsCurrentKeyBeforeRethrowing() throws Exception {
        RecordingMem0LongTermMemory ltm = new RecordingMem0LongTermMemory();
        try (KeyedOneInputStreamOperatorTestHarness<String, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(
                                TestAgent.getFailedActionAfterLtmAgentPlan(), true),
                        (KeySelector<Long, String>) String::valueOf,
                        TypeInformation.of(String.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();
            replaceOperatorLtm(operator, ltm);

            testHarness.processElement(new StreamRecord<>(0L));

            assertThatThrownBy(() -> operator.waitInFlightEventsFinished())
                    .hasCauseInstanceOf(ActionExecutionOperator.ActionTaskExecutionException.class)
                    .rootCause()
                    .hasMessageContaining("first action failed after LTM");
            // The public LTM operation ran before the action failed.
            assertThat(ltm.recordedKeys()).containsExactly("0");
            // The common failure boundary explicitly drains this key before rethrowing. Check the
            // same buffer directly rather than duplicating the Python LTM record schema here.
            assertThat(ltm.pendingObservationKeys()).isEmpty();
            assertThat(ltm.drainedObservationKeys()).containsExactly("0");
            assertThat(ltm.drainCallCount()).isEqualTo(1);
            // The failed mailbox task cannot continue to the following action in this operator.
            assertThat(TestAgent.FOLLOWING_ACTION_EXECUTED).isFalse();
        }
    }

    @Test
    void testDiscardFailureDoesNotReplaceActionFailure() throws Exception {
        RecordingMem0LongTermMemory ltm = new RecordingMem0LongTermMemory();
        RuntimeException discardFailure = new RuntimeException("discard failed");
        ltm.failDrainWith(discardFailure);

        try (KeyedOneInputStreamOperatorTestHarness<String, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(
                                TestAgent.getFailedActionAfterLtmAgentPlan(), true),
                        (KeySelector<Long, String>) String::valueOf,
                        TypeInformation.of(String.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();
            replaceOperatorLtm(operator, ltm);

            testHarness.processElement(new StreamRecord<>(0L));
            Throwable thrown = catchThrowable(operator::waitInFlightEventsFinished);

            assertThat(thrown)
                    .hasCauseInstanceOf(ActionExecutionOperator.ActionTaskExecutionException.class)
                    .rootCause()
                    .hasMessageContaining("first action failed after LTM");
            assertThat(findSuppressedFailure(thrown, discardFailure)).isSameAs(discardFailure);
            assertThat(ltm.recordedKeys()).containsExactly("0");
            assertThat(ltm.drainCallCount()).isEqualTo(1);
            assertThat(TestAgent.FOLLOWING_ACTION_EXECUTED).isFalse();
        }
    }

    private static Throwable findSuppressedFailure(Throwable failure, Throwable expected) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            for (Throwable suppressed : current.getSuppressed()) {
                if (suppressed == expected) {
                    return suppressed;
                }
            }
        }
        return null;
    }

    private static void replaceOperatorLtm(
            ActionExecutionOperator<?, ?> operator, Mem0LongTermMemory ltm) throws Exception {
        Field ltmField = ActionExecutionOperator.class.getDeclaredField("ltm");
        ltmField.setAccessible(true);
        ltmField.set(operator, ltm);
    }

    private static void replaceOperatorField(
            ActionExecutionOperator<?, ?> operator, String name, Object value) throws Exception {
        Field field = ActionExecutionOperator.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(operator, value);
    }

    /**
     * Swaps the state handler {@link AbstractStreamOperator} inherits, returning the previous one.
     *
     * <p>{@code super.close()} compiles to {@code stateHandler.dispose()} and binds statically, so
     * a subclass cannot intercept the call. Replacing the inherited handler is what makes the super
     * call observable, and what lets it be made to fail.
     */
    private static StreamOperatorStateHandler replaceStateHandler(
            ActionExecutionOperator<?, ?> operator, StreamOperatorStateHandler handler)
            throws Exception {
        Field field = AbstractStreamOperator.class.getDeclaredField("stateHandler");
        field.setAccessible(true);
        StreamOperatorStateHandler previous = (StreamOperatorStateHandler) field.get(operator);
        field.set(operator, handler);
        return previous;
    }

    private static KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> openCloseTestHarness()
            throws Exception {
        KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(TestAgent.getAgentPlan(false), true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class));
        testHarness.open();
        return testHarness;
    }

    /** The operator's five closeable components, stubbed so each close is observable. */
    private static final class CloseComponents {
        private final ResourceCache resourceCache = mock(ResourceCache.class);
        private final ActionTaskContextManager contextManager =
                mock(ActionTaskContextManager.class);
        private final PythonBridgeManager pythonBridge = mock(PythonBridgeManager.class);
        private final EventLogWriter eventLogWriter = mock(EventLogWriter.class);
        private final DurableExecutionManager durableExecManager =
                mock(DurableExecutionManager.class);

        private void installInto(ActionExecutionOperator<?, ?> operator) throws Exception {
            replaceOperatorField(operator, "resourceCache", resourceCache);
            replaceOperatorField(operator, "contextManager", contextManager);
            replaceOperatorField(operator, "pythonBridge", pythonBridge);
            replaceOperatorField(operator, "eventLogWriter", eventLogWriter);
            replaceOperatorField(operator, "durableExecManager", durableExecManager);
        }

        /** Detaches the mocks so the harness teardown does not re-trigger the failure. */
        private void detachFrom(ActionExecutionOperator<?, ?> operator) throws Exception {
            replaceOperatorField(operator, "resourceCache", null);
            replaceOperatorField(operator, "contextManager", null);
            replaceOperatorField(operator, "pythonBridge", null);
            replaceOperatorField(operator, "eventLogWriter", null);
            replaceOperatorField(operator, "durableExecManager", null);
        }

        /**
         * Verifies every component was released, in the documented order, with {@code
         * super.close()} last.
         *
         * <p>Order is load-bearing rather than incidental: {@code resourceCache} must close before
         * {@code pythonBridge} because cached resources may hold Python references, and {@code
         * super.close()} disposes the state backends the components run against.
         */
        private void verifyClosedInOrder(StreamOperatorStateHandler stateHandler) throws Exception {
            InOrder inOrder =
                    inOrder(
                            resourceCache,
                            contextManager,
                            pythonBridge,
                            eventLogWriter,
                            durableExecManager,
                            stateHandler);
            inOrder.verify(resourceCache).close();
            inOrder.verify(contextManager).close();
            inOrder.verify(pythonBridge).close();
            inOrder.verify(eventLogWriter).close();
            inOrder.verify(durableExecManager).close();
            inOrder.verify(stateHandler).dispose();
        }
    }

    /**
     * A failing component must not strand the ones behind it. This matters most for {@code
     * resourceCache}, which closes first and aggregates its own failures, and for {@code
     * pythonBridge}, which releases the embedded Python interpreter.
     *
     * <p>Also pins that {@code super.close()} still runs. {@link AbstractStreamOperator#close()}
     * disposes the state handler, so skipping it strands the state backends.
     */
    @Test
    void closeClosesEveryComponentWhenAnEarlierCloseFails() throws Exception {
        KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                openCloseTestHarness();
        ActionExecutionOperator<Long, Object> operator =
                (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

        CloseComponents components = new CloseComponents();
        doThrow(new IllegalStateException("resource cache close failed"))
                .when(components.resourceCache)
                .close();
        components.installInto(operator);
        StreamOperatorStateHandler stateHandler = mock(StreamOperatorStateHandler.class);
        StreamOperatorStateHandler realStateHandler = replaceStateHandler(operator, stateHandler);

        try {
            assertThatThrownBy(operator::close)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("resource cache close failed")
                    // Contract 3: with super.close() healthy, nothing is attached to the failure.
                    .satisfies(thrown -> assertThat(thrown.getSuppressed()).isEmpty());

            components.verifyClosedInOrder(stateHandler);
        } finally {
            components.detachFrom(operator);
            replaceStateHandler(operator, realStateHandler);
            testHarness.close();
        }
    }

    /**
     * A {@code super.close()} failure must aggregate with the component failures rather than
     * replace them: the earlier component failure still reaches the caller, with the super failure
     * attached to it as suppressed.
     */
    @Test
    void closeAggregatesSuperCloseFailureWithComponentFailure() throws Exception {
        KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                openCloseTestHarness();
        ActionExecutionOperator<Long, Object> operator =
                (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

        CloseComponents components = new CloseComponents();
        doThrow(new IllegalStateException("resource cache close failed"))
                .when(components.resourceCache)
                .close();
        components.installInto(operator);
        StreamOperatorStateHandler stateHandler = mock(StreamOperatorStateHandler.class);
        doThrow(new IllegalStateException("state handler dispose failed"))
                .when(stateHandler)
                .dispose();
        StreamOperatorStateHandler realStateHandler = replaceStateHandler(operator, stateHandler);

        try {
            assertThatThrownBy(operator::close)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("resource cache close failed")
                    .satisfies(
                            thrown ->
                                    assertThat(thrown.getSuppressed())
                                            .extracting(Throwable::getMessage)
                                            .containsExactly("state handler dispose failed"));

            components.verifyClosedInOrder(stateHandler);
        } finally {
            components.detachFrom(operator);
            replaceStateHandler(operator, realStateHandler);
            testHarness.close();
        }
    }

    /** Java-side stand-in for the Python-backed LTM wrapper used to observe the failure path. */
    private static final class RecordingMem0LongTermMemory extends Mem0LongTermMemory {
        private final List<String> recordedKeys = new ArrayList<>();
        private final List<String> pendingObservationKeys = new ArrayList<>();
        private final List<String> pendingObservationIds = new ArrayList<>();
        private final List<String> drainedObservationKeys = new ArrayList<>();
        private String currentKey;
        private String currentObservationId;
        private boolean updateObservationConfigured = true;
        private boolean observationSuppressed;
        private int drainCallCount;
        private RuntimeException drainFailure;

        private RecordingMem0LongTermMemory() {
            super(null, null);
        }

        @Override
        public void configureObservation(
                boolean updateObservationEnabled,
                boolean getObservationEnabled,
                boolean searchObservationEnabled) {
            updateObservationConfigured = updateObservationEnabled;
        }

        @Override
        public void switchContext(
                String partitionKey, String observationId, boolean observationSuppressed) {
            currentKey = partitionKey;
            currentObservationId = observationId;
            this.observationSuppressed = observationSuppressed;
        }

        @Override
        public MemorySet getMemorySet(String name) {
            MemorySet memorySet = new MemorySet(name);
            memorySet.setLtm(this);
            return memorySet;
        }

        @Override
        public List<String> add(
                MemorySet memorySet,
                List<String> memoryItems,
                @javax.annotation.Nullable List<Map<String, Object>> metadatas) {
            recordedKeys.add(currentKey);
            if (!updateObservationConfigured || observationSuppressed) {
                return List.of("memory-id");
            }
            pendingObservationKeys.add(currentKey);
            pendingObservationIds.add(currentObservationId);
            return List.of("memory-id");
        }

        @Override
        public String drainObservationRecordsJson(String partitionKey, String observationId) {
            drainCallCount++;
            if (drainFailure != null) {
                throw drainFailure;
            }
            for (int index = pendingObservationKeys.size() - 1; index >= 0; index--) {
                if (pendingObservationKeys.get(index).equals(partitionKey)
                        && pendingObservationIds.get(index).equals(observationId)) {
                    drainedObservationKeys.add(pendingObservationKeys.remove(index));
                    pendingObservationIds.remove(index);
                }
            }
            return "[]";
        }

        @Override
        public void close() {}

        private List<String> recordedKeys() {
            return recordedKeys;
        }

        private int drainCallCount() {
            return drainCallCount;
        }

        private List<String> pendingObservationKeys() {
            return pendingObservationKeys;
        }

        private List<String> drainedObservationKeys() {
            return drainedObservationKeys;
        }

        private void failDrainWith(RuntimeException failure) {
            drainFailure = failure;
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

    /** A EventListener for unit test */
    public static class TestEventListener implements EventListener {
        public boolean called = false;
        public final List<String> eventTypes = new ArrayList<>();

        @Override
        public void onEventProcessed(EventContext context, Event event) {
            this.called = true;
            this.eventTypes.add(event.getType());
        }
    }

    private static final class EventSnapshot {
        private final UUID id;
        private final UUID upstreamEventId;
        private final String upstreamActionName;

        private EventSnapshot(Event event) {
            this.id = event.getId();
            this.upstreamEventId = event.getUpstreamEventId();
            this.upstreamActionName = event.getUpstreamActionName();
        }
    }

    private static Map<String, EventSnapshot> recordEventSnapshotsByType(
            ActionExecutionOperator<Long, Object> operator) {
        Map<String, EventSnapshot> snapshotsByType = new HashMap<>();
        operator.getEventRouter()
                .addEventListener(
                        (context, event) ->
                                snapshotsByType.put(event.getType(), new EventSnapshot(event)));
        return snapshotsByType;
    }

    /** Fails while processing an Action's output Event. */
    public static class FailingMiddleEventListener implements EventListener {
        @Override
        public void onEventProcessed(EventContext context, Event event) {
            if (TestAgent.MiddleEvent.EVENT_TYPE.equals(event.getType())) {
                throw new IllegalStateException("Failed to process Action output Event");
            }
        }
    }

    /** Records events appended to Event Log for assertions. */
    public static class RecordingEventLogger implements EventLogger {
        private static final List<RecordedEvent> EVENTS = new ArrayList<>();
        private static int createdCount;
        private static int openCount;
        private static int flushCount;
        private static int closeCount;

        public RecordingEventLogger() {
            createdCount++;
        }

        static void reset() {
            EVENTS.clear();
            createdCount = 0;
            openCount = 0;
            flushCount = 0;
            closeCount = 0;
        }

        static List<RecordedEvent> events() {
            return List.copyOf(EVENTS);
        }

        static int createdCount() {
            return createdCount;
        }

        static int openCount() {
            return openCount;
        }

        static int flushCount() {
            return flushCount;
        }

        static int closeCount() {
            return closeCount;
        }

        @Override
        public void open(EventLoggerOpenParams params) {
            openCount++;
        }

        @Override
        public void append(EventContext eventContext, Event event) {
            append(eventContext, event, null);
        }

        @Override
        public void append(
                EventContext eventContext, Event event, ExecutionTraceContext traceContext) {
            EVENTS.add(new RecordedEvent(event, traceContext));
        }

        @Override
        public void flush() {
            flushCount++;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    private static class RecordedEvent {
        private final Event event;
        private final ExecutionTraceContext traceContext;

        private RecordedEvent(Event event, ExecutionTraceContext traceContext) {
            this.event = event;
            this.traceContext = traceContext;
        }

        private ExecutionTraceContext traceContext() {
            if (traceContext == null) {
                throw new AssertionError("Missing ExecutionTraceContext");
            }
            return traceContext;
        }

        private String status() {
            return (String) event.getAttr(ExecutionLifecycleEvents.STATUS_ATTRIBUTE);
        }

        private String problemCategory() {
            return (String) event.getAttr(ExecutionLifecycleEvents.PROBLEM_CATEGORY_ATTRIBUTE);
        }
    }

    private static RecordedEvent findRecordedLifecycleEvent(
            String eventType, String entityName, String status) {
        return RecordingEventLogger.events().stream()
                .filter(record -> eventType.equals(record.event.getType()))
                .filter(record -> entityName.equals(record.traceContext().getEntityName()))
                .filter(record -> status.equals(record.status()))
                .findFirst()
                .orElseThrow(
                        () ->
                                new AssertionError(
                                        String.format(
                                                "Missing lifecycle event type=%s entity=%s status=%s in %s",
                                                eventType,
                                                entityName,
                                                status,
                                                RecordingEventLogger.events().stream()
                                                        .map(
                                                                record ->
                                                                        record.event.getType()
                                                                                + "/"
                                                                                + record.traceContext()
                                                                                        .getEntityName()
                                                                                + "/"
                                                                                + record.status())
                                                        .collect(Collectors.toList()))));
    }

    private static AgentConfiguration traceEnabledConfig() {
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentConfigOptions.EVENT_LOG_TRACE_ENABLED, true);
        return config;
    }

    @Test
    void testEventListenersFromAgentConfig() throws Exception {
        final AgentConfiguration config = traceEnabledConfig();
        config.set(AgentConfigOptions.EVENT_LISTENERS, List.of(TestEventListener.class.getName()));
        final AgentPlan agentPlan = TestAgent.getAgentPlanWithConfig(config);

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(agentPlan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            final ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();
            final Field eventListenersField = EventRouter.class.getDeclaredField("eventListeners");
            eventListenersField.setAccessible(true);
            final Object obj = eventListenersField.get(operator.getEventRouter());
            assertThat(obj).isNotNull();
            assertThat(obj).isInstanceOf(List.class);

            final List eventListeners = (List) obj;
            assertThat(eventListeners.size()).isEqualTo(1);

            final Object listener = eventListeners.get(0);
            assertThat(listener).isInstanceOf(TestEventListener.class);

            // listener should not have been triggered yet
            boolean called = ((TestEventListener) listener).called;
            assertThat(called).isFalse();

            // process a some element to trigger the operator logic
            testHarness.processElement(new StreamRecord<>(1L));
            operator.waitInFlightEventsFinished();

            // listener should have been invoked after element processing
            TestEventListener testEventListener = (TestEventListener) listener;
            called = testEventListener.called;
            assertThat(called).isTrue();
            assertThat(testEventListener.eventTypes)
                    .noneMatch(ExecutionLifecycleEvents::isExecutionLifecycleEvent);
            assertThat(RecordingEventLogger.events())
                    .anyMatch(
                            record ->
                                    ExecutionLifecycleEvents.isExecutionLifecycleEvent(
                                            record.event.getType()));
        }
    }

    @Test
    void testActionLifecycleEventsCarryExecutionContext() throws Exception {
        final AgentConfiguration config = traceEnabledConfig();
        final AgentPlan agentPlan = TestAgent.getAgentPlanWithConfig(config);

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(agentPlan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(1L));
            operator.waitInFlightEventsFinished();
        }

        RecordedEvent action1Started =
                findRecordedLifecycleEvent(
                        ExecutionLifecycleEvents.EXECUTION_STARTED_EVENT_TYPE,
                        "action1",
                        ExecutionLifecycleEvents.STATUS_STARTED);
        RecordedEvent action1Finished =
                findRecordedLifecycleEvent(
                        ExecutionLifecycleEvents.EXECUTION_FINISHED_EVENT_TYPE,
                        "action1",
                        ExecutionLifecycleEvents.STATUS_SUCCESS);
        RecordedEvent action2Started =
                findRecordedLifecycleEvent(
                        ExecutionLifecycleEvents.EXECUTION_STARTED_EVENT_TYPE,
                        "action2",
                        ExecutionLifecycleEvents.STATUS_STARTED);
        RecordedEvent action2Finished =
                findRecordedLifecycleEvent(
                        ExecutionLifecycleEvents.EXECUTION_FINISHED_EVENT_TYPE,
                        "action2",
                        ExecutionLifecycleEvents.STATUS_SUCCESS);

        assertThat(action1Started.traceContext().getInputRunId()).isNotBlank();
        assertThat(action1Started.traceContext().getBusinessKey()).isEqualTo("1");
        assertThat(action1Started.traceContext().getEntityType()).isEqualTo("action");
        assertThat(action1Started.status()).isEqualTo(ExecutionLifecycleEvents.STATUS_STARTED);
        assertThat(action1Finished.traceContext().getExecutionId())
                .isEqualTo(action1Started.traceContext().getExecutionId());
        assertThat(action1Finished.status()).isEqualTo(ExecutionLifecycleEvents.STATUS_SUCCESS);

        assertThat(action2Started.traceContext().getInputRunId())
                .isEqualTo(action1Started.traceContext().getInputRunId());
        assertThat(action2Started.traceContext().getParentExecutionId()).isNull();
        assertThat(action2Finished.traceContext().getExecutionId())
                .isEqualTo(action2Started.traceContext().getExecutionId());

        RecordedEvent middleEvent =
                RecordingEventLogger.events().stream()
                        .filter(
                                record ->
                                        TestAgent.MiddleEvent.EVENT_TYPE.equals(
                                                record.event.getType()))
                        .findFirst()
                        .orElseThrow();
        assertThat(middleEvent.traceContext().getExecutionId())
                .isEqualTo(action1Started.traceContext().getExecutionId());
        assertThat(middleEvent.traceContext().getEntityName()).isEqualTo("action1");

        RecordedEvent outputEvent =
                RecordingEventLogger.events().stream()
                        .filter(record -> OutputEvent.EVENT_TYPE.equals(record.event.getType()))
                        .findFirst()
                        .orElseThrow();
        assertThat(outputEvent.traceContext().getExecutionId())
                .isEqualTo(action2Started.traceContext().getExecutionId());
        assertThat(outputEvent.traceContext().getEntityName()).isEqualTo("action2");
    }

    @Test
    void testActionFailureLifecycleEventCarriesProblemCategory() throws Exception {
        final AgentConfiguration config = traceEnabledConfig();
        AgentPlan basePlan = TestAgent.getDurableExceptionUncaughtAgentPlan();
        AgentPlan agentPlan =
                new AgentPlan(
                        basePlan.getActions(),
                        basePlan.getResourceProviders(),
                        config,
                        basePlan.getAgentName());

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(agentPlan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(1L));
            assertThatThrownBy(operator::waitInFlightEventsFinished)
                    .hasCauseInstanceOf(ActionExecutionOperator.ActionTaskExecutionException.class);
        }

        RecordedEvent failed =
                findRecordedLifecycleEvent(
                        ExecutionLifecycleEvents.EXECUTION_FAILED_EVENT_TYPE,
                        "durableExceptionUncaughtAction",
                        ExecutionLifecycleEvents.STATUS_FAILED);
        assertThat(failed.problemCategory())
                .isEqualTo(ExecutionReporter.ProblemCategories.ACTION_EXECUTION_FAILED);
        assertThat(failed.event.getAttr("errorType"))
                .isEqualTo(IllegalStateException.class.getName());
        assertThat(String.valueOf(failed.event.getAttr("errorMessage")))
                .contains("Simulated LLM failure");
    }

    @Test
    void testActionFinishesBeforeItsOutputEventIsProcessed() throws Exception {
        AgentConfiguration config = traceEnabledConfig();
        config.set(
                AgentConfigOptions.EVENT_LISTENERS,
                List.of(FailingMiddleEventListener.class.getName()));
        AgentPlan agentPlan = TestAgent.getAgentPlanWithConfig(config);

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(agentPlan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(1L));
            assertThatThrownBy(operator::waitInFlightEventsFinished)
                    .hasCauseInstanceOf(ActionExecutionOperator.ActionTaskExecutionException.class)
                    .rootCause()
                    .hasMessage("Failed to process Action output Event");
        }

        RecordedEvent started =
                findRecordedLifecycleEvent(
                        ExecutionLifecycleEvents.EXECUTION_STARTED_EVENT_TYPE,
                        "action1",
                        ExecutionLifecycleEvents.STATUS_STARTED);
        RecordedEvent finished =
                findRecordedLifecycleEvent(
                        ExecutionLifecycleEvents.EXECUTION_FINISHED_EVENT_TYPE,
                        "action1",
                        ExecutionLifecycleEvents.STATUS_SUCCESS);
        assertThat(finished.traceContext().getExecutionId())
                .isEqualTo(started.traceContext().getExecutionId());
        assertThat(RecordingEventLogger.events())
                .noneMatch(
                        record ->
                                ExecutionLifecycleEvents.EXECUTION_FAILED_EVENT_TYPE.equals(
                                                record.event.getType())
                                        && "action1".equals(record.traceContext().getEntityName()));
    }

    @Test
    void testActionContinuationEmitsStartedLifecycleEventOnce() throws Exception {
        final AgentConfiguration config = traceEnabledConfig();
        AgentPlan basePlan = TestAgent.getAsyncAgentPlan(false);
        AgentPlan agentPlan =
                new AgentPlan(
                        basePlan.getActions(),
                        basePlan.getResourceProviders(),
                        config,
                        basePlan.getAgentName());

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(agentPlan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(5L));
            operator.waitInFlightEventsFinished();
        }

        assertThat(RecordingEventLogger.events())
                .filteredOn(
                        record ->
                                ExecutionLifecycleEvents.EXECUTION_STARTED_EVENT_TYPE.equals(
                                                record.event.getType())
                                        && "asyncAction1"
                                                .equals(record.traceContext().getEntityName()))
                .hasSize(1);
    }

    @Test
    void testDoesNotPruneBeforeCheckpointComplete() throws Exception {
        AgentPlan agentPlanWithStateStore = TestAgent.getAgentPlan(false);
        RecordingActionStateStore actionStateStore = new RecordingActionStateStore();

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(
                                agentPlanWithStateStore, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(5L));
            operator.waitInFlightEventsFinished();
            assertThat(actionStateStore.getPrunedSeqNums()).isEmpty();

            testHarness.snapshot(1L, 1L);
            assertThat(actionStateStore.getPrunedSeqNums()).isEmpty();
            testHarness.notifyOfCompletedCheckpoint(1L);

            assertThat(actionStateStore.getPrunedSeqNums()).containsExactly(0L);
        }
    }

    @Test
    void testDoesNotPruneSeqsInFlight() throws Exception {
        AgentPlan agentPlanWithStateStore = TestAgent.getAgentPlan(false);
        RecordingActionStateStore actionStateStore = new RecordingActionStateStore();

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(
                                agentPlanWithStateStore, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(5L));
            operator.waitInFlightEventsFinished();
            actionStateStore.clearPruneCalls();

            testHarness.processElement(new StreamRecord<>(5L));
            assertThat(testHarness.getTaskMailbox().size()).isEqualTo(1);

            testHarness.snapshot(1L, 1L);
            testHarness.notifyOfCompletedCheckpoint(1L);

            assertThat(actionStateStore.getPrunedSeqNums()).containsExactly(0L);
        }
    }

    @Test
    void testBusinessAndExecutionEventsShareSingleEventLogger() throws Exception {
        AgentPlan agentPlan = TestAgent.getAgentPlanWithConfig(traceEnabledConfig());
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(agentPlan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(1L));
            operator.waitInFlightEventsFinished();

            assertThat(RecordingEventLogger.createdCount()).isEqualTo(1);
            assertThat(RecordingEventLogger.openCount()).isEqualTo(1);
            assertThat(RecordingEventLogger.flushCount())
                    .isEqualTo(RecordingEventLogger.events().size());
            assertThat(RecordingEventLogger.events())
                    .anySatisfy(
                            record ->
                                    assertThat(record.event.getType())
                                            .isEqualTo(InputEvent.EVENT_TYPE));
            assertThat(RecordingEventLogger.events())
                    .anySatisfy(
                            record ->
                                    assertThat(record.event.getType())
                                            .isEqualTo(
                                                    ExecutionLifecycleEvents
                                                            .EXECUTION_STARTED_EVENT_TYPE));
        }

        assertThat(RecordingEventLogger.closeCount()).isEqualTo(1);
    }

    @Test
    void testTraceRecordingIsDisabledByDefault() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(TestAgent.getAgentPlan(false), true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(1L));
            operator.waitInFlightEventsFinished();

            assertThat(RecordingEventLogger.events()).isNotEmpty();
            assertThat(RecordingEventLogger.events())
                    .allSatisfy(
                            record -> {
                                assertThat(record.traceContext).isNull();
                                assertThat(
                                                ExecutionLifecycleEvents.isExecutionLifecycleEvent(
                                                        record.event.getType()))
                                        .isFalse();
                            });
        }
    }

    @Test
    void testEventLogBaseDirFromAgentConfig() throws Exception {
        String baseLogDir = "/tmp/flink-agents-test";
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentConfigOptions.EVENT_LOGGER_TYPE, LoggerType.FILE);
        config.set(AgentConfigOptions.BASE_LOG_DIR, baseLogDir);
        config.set(AgentConfigOptions.PRETTY_PRINT, true);
        AgentPlan agentPlan = TestAgent.getAgentPlanWithConfig(config);

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory(agentPlan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();
            Object eventLogger = operator.getEventRouter().getEventLogger();
            assertThat(eventLogger).isInstanceOf(FileEventLogger.class);

            Field configField = FileEventLogger.class.getDeclaredField("config");
            configField.setAccessible(true);
            Object loggerConfig = configField.get(eventLogger);
            Field propertiesField = loggerConfig.getClass().getDeclaredField("properties");
            propertiesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> properties =
                    (Map<String, Object>) propertiesField.get(loggerConfig);
            @SuppressWarnings("unchecked")
            Map<String, Object> agentConfig =
                    (Map<String, Object>)
                            properties.get(EventLoggerConfig.AGENT_CONFIG_PROPERTY_KEY);
            assertThat(agentConfig.get(AgentConfigOptions.BASE_LOG_DIR.getKey()))
                    .isEqualTo(baseLogDir);
            assertThat(agentConfig.get(AgentConfigOptions.PRETTY_PRINT.getKey())).isEqualTo(true);
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
    void testCompletedActionStatePersistsOutputEventLineage() throws Exception {
        AgentPlan agentPlan = TestAgent.getAgentPlan(false);
        SerializingActionStateStore actionStateStore = new SerializingActionStateStore();

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(3L));
            operator.waitInFlightEventsFinished();
        }

        assertThat(actionStateStore.getCompletedStateBytes()).hasSize(2);
        for (Map.Entry<String, byte[]> entry :
                actionStateStore.getCompletedStateBytes().entrySet()) {
            JsonNode state = OBJECT_MAPPER.readTree(entry.getValue());
            JsonNode outputEvent = state.path("outputEvents").get(0);

            assertThat(outputEvent.path("upstreamEventId").asText())
                    .isEqualTo(state.path("taskEvent").path("id").asText());
            assertThat(outputEvent.path("upstreamActionName").asText()).isEqualTo(entry.getKey());
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
    void testActionStateStoreCleanupAfterCheckpointComplete() throws Exception {
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

            InMemoryActionStateStore actionStateStore =
                    (InMemoryActionStateStore)
                            operator.getDurableExecutionManager().getActionStateStore();
            assertThat(actionStateStore.getKeyedActionStates()).isNotEmpty();

            testHarness.snapshot(1L, 1L);
            testHarness.notifyOfCompletedCheckpoint(1L);

            assertThat(actionStateStore.getKeyedActionStates()).isEmpty();
        }
    }

    @Test
    void testEarlierCheckpointReplayKeepsDurableState() throws Exception {
        AgentPlan agentPlan = TestAgent.getDurableSyncAgentPlan();
        InMemoryActionStateStore actionStateStore = new InMemoryActionStateStore(true);
        OperatorSubtaskState snapshot;

        TestAgent.DURABLE_CALL_COUNTER.set(0);

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            // Simulate failure recovery from a checkpoint taken before this input was processed.
            snapshot = testHarness.snapshot(1L, 1L);

            testHarness.processElement(new StreamRecord<>(7L));
            operator.waitInFlightEventsFinished();

            assertThat(TestAgent.DURABLE_CALL_COUNTER.get()).isEqualTo(1);
            assertThat(actionStateStore.getKeyedActionStates()).isNotEmpty();
        }

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.initializeState(snapshot);
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            // Replay the same input after restoring from the earlier checkpoint.
            testHarness.processElement(new StreamRecord<>(7L));
            operator.waitInFlightEventsFinished();

            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput).hasSize(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo(21L);
            assertThat(TestAgent.DURABLE_CALL_COUNTER.get())
                    .as("Durable supplier should not be re-executed during replay")
                    .isEqualTo(1);
        }
    }

    @Test
    void testReplaySkipsCompletedActions() throws Exception {
        AgentPlan agentPlan = TestAgent.getAgentPlan(false);
        long inputValue = 7L;
        InMemoryActionStateStore actionStateStore = new InMemoryActionStateStore(false);
        TestAgent.ACTION1_CALL_COUNTER.set(0);
        TestAgent.ACTION2_CALL_COUNTER.set(0);

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(inputValue));
            operator.waitInFlightEventsFinished();

            assertThat(TestAgent.ACTION1_CALL_COUNTER.get()).isEqualTo(1);
            assertThat(TestAgent.ACTION2_CALL_COUNTER.get()).isEqualTo(1);
        }

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(inputValue));
            operator.waitInFlightEventsFinished();

            List<StreamRecord<Object>> outputRecords =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(outputRecords).hasSize(1);
            assertThat(outputRecords.get(0).getValue()).isEqualTo((inputValue + 1) * 2);
            assertThat(TestAgent.ACTION1_CALL_COUNTER.get())
                    .as("Completed action1 must not be re-executed during replay")
                    .isEqualTo(1);
            assertThat(TestAgent.ACTION2_CALL_COUNTER.get())
                    .as("Completed action2 must not be re-executed during replay")
                    .isEqualTo(1);
        }
    }

    @Test
    void testActionStateStoreReplayRecordsReusedExecutions() throws Exception {
        AgentConfiguration config = traceEnabledConfig();
        AgentPlan basePlan = TestAgent.getAgentPlan(false);
        AgentPlan agentPlanWithStateStore =
                new AgentPlan(
                        basePlan.getActions(),
                        basePlan.getResourceProviders(),
                        config,
                        basePlan.getAgentName());
        InMemoryActionStateStore actionStateStore;
        String originalInputRunId;
        UUID originalMiddleEventId;
        UUID originalOutputEventId;

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(
                                agentPlanWithStateStore, true, new InMemoryActionStateStore(false)),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();
            actionStateStore =
                    (InMemoryActionStateStore)
                            operator.getDurableExecutionManager().getActionStateStore();

            long inputValue = 7L;
            testHarness.processElement(new StreamRecord<>(inputValue));
            operator.waitInFlightEventsFinished();

            RecordedEvent action1Started =
                    findRecordedLifecycleEvent(
                            ExecutionLifecycleEvents.EXECUTION_STARTED_EVENT_TYPE,
                            "action1",
                            ExecutionLifecycleEvents.STATUS_STARTED);
            originalInputRunId = action1Started.traceContext().getInputRunId();
            originalMiddleEventId =
                    RecordingEventLogger.events().stream()
                            .filter(
                                    record ->
                                            TestAgent.MiddleEvent.EVENT_TYPE.equals(
                                                    record.event.getType()))
                            .findFirst()
                            .orElseThrow()
                            .event
                            .getId();
            originalOutputEventId =
                    RecordingEventLogger.events().stream()
                            .filter(record -> OutputEvent.EVENT_TYPE.equals(record.event.getType()))
                            .findFirst()
                            .orElseThrow()
                            .event
                            .getId();
        }

        RecordingEventLogger.reset();

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(
                                agentPlanWithStateStore, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            long inputValue = 7L;
            testHarness.processElement(new StreamRecord<>(inputValue));
            operator.waitInFlightEventsFinished();

            List<StreamRecord<Object>> outputRecords =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(outputRecords).hasSize(1);
            assertThat(outputRecords.get(0).getValue()).isEqualTo((inputValue + 1) * 2);
            assertThat(actionStateStore.getKeyedActionStates().get(String.valueOf(inputValue)))
                    .hasSize(2);

            List<RecordedEvent> replayEvents = RecordingEventLogger.events();
            assertThat(replayEvents)
                    .filteredOn(
                            record ->
                                    ExecutionLifecycleEvents.EXECUTION_REUSED_EVENT_TYPE.equals(
                                            record.event.getType()))
                    .extracting(record -> record.traceContext().getEntityName())
                    .containsExactlyInAnyOrder("action1", "action2");
            assertThat(replayEvents)
                    .noneMatch(
                            record ->
                                    ExecutionLifecycleEvents.EXECUTION_FINISHED_EVENT_TYPE.equals(
                                                    record.event.getType())
                                            && ExecutionLifecycleEvents.STATUS_SUCCESS.equals(
                                                    record.status())
                                            && List.of("action1", "action2")
                                                    .contains(
                                                            record.traceContext().getEntityName()));

            RecordedEvent action1Reused =
                    findRecordedLifecycleEvent(
                            ExecutionLifecycleEvents.EXECUTION_REUSED_EVENT_TYPE,
                            "action1",
                            ExecutionLifecycleEvents.STATUS_REUSED);
            RecordedEvent action2Reused =
                    findRecordedLifecycleEvent(
                            ExecutionLifecycleEvents.EXECUTION_REUSED_EVENT_TYPE,
                            "action2",
                            ExecutionLifecycleEvents.STATUS_REUSED);
            RecordedEvent replayedMiddleEvent =
                    replayEvents.stream()
                            .filter(
                                    record ->
                                            TestAgent.MiddleEvent.EVENT_TYPE.equals(
                                                    record.event.getType()))
                            .findFirst()
                            .orElseThrow();
            RecordedEvent replayedOutputEvent =
                    replayEvents.stream()
                            .filter(record -> OutputEvent.EVENT_TYPE.equals(record.event.getType()))
                            .findFirst()
                            .orElseThrow();

            assertThat(action1Reused.traceContext().getInputRunId())
                    .isNotEqualTo(originalInputRunId);
            assertThat(action2Reused.traceContext().getInputRunId())
                    .isEqualTo(action1Reused.traceContext().getInputRunId());
            assertThat(replayedMiddleEvent.event.getId()).isEqualTo(originalMiddleEventId);
            assertThat(replayedMiddleEvent.traceContext().getExecutionId())
                    .isEqualTo(action1Reused.traceContext().getExecutionId());
            assertThat(replayedOutputEvent.event.getId()).isEqualTo(originalOutputEventId);
            assertThat(replayedOutputEvent.traceContext().getExecutionId())
                    .isEqualTo(action2Reused.traceContext().getExecutionId());
        }
    }

    @Test
    void testReplayReappliesNewObjectMemoryUpdatesIntoEmptyState() throws Exception {
        AgentPlan agentPlan = TestAgent.getNestedMemoryAgentPlan();
        InMemoryActionStateStore actionStateStore = new InMemoryActionStateStore(false);
        TestAgent.NESTED_MEMORY_ACTION_CALL_COUNTER.set(0);

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

            assertThat(TestAgent.NESTED_MEMORY_ACTION_CALL_COUNTER.get()).isEqualTo(1);
        }

        // Simulate recovery from a checkpoint taken before the input was processed: the keyed
        // memory state is empty, but the completed ActionState survives in the store, so the
        // action is skipped and its memory updates are replayed. The newObject update must be
        // re-applied as an object creation — replaying it as set("user", null) would create a
        // null value leaf and the subsequent set("user.score", ...) replay would fail.
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
            assertThat(recordOutput).hasSize(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo(8L);
            assertThat(TestAgent.NESTED_MEMORY_ACTION_CALL_COUNTER.get())
                    .as("Completed action must not be re-executed during replay")
                    .isEqualTo(1);
        }
    }

    @Test
    void testReplayReappliesNewObjectMemoryUpdatesOverRestoredState() throws Exception {
        AgentPlan agentPlan = TestAgent.getNestedMemoryAgentPlan();
        InMemoryActionStateStore actionStateStore = new InMemoryActionStateStore(false);
        TestAgent.NESTED_MEMORY_ACTION_CALL_COUNTER.set(0);
        OperatorSubtaskState snapshot;

        // Both inputs must share one Flink key so the second input's replay runs over the state
        // the first input left behind.
        KeySelector<Long, Long> constantKey = value -> 0L;

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        constantKey,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            // First input for this key: the action creates the "user" object, and the checkpoint
            // taken afterwards persists it in the keyed memory state.
            testHarness.processElement(new StreamRecord<>(7L));
            operator.waitInFlightEventsFinished();
            snapshot = testHarness.snapshot(1L, 1L);

            // Second input for the same key: the action completes (its ActionState survives in
            // the store), but the job "fails" before the next checkpoint.
            testHarness.processElement(new StreamRecord<>(9L));
            operator.waitInFlightEventsFinished();

            assertThat(TestAgent.NESTED_MEMORY_ACTION_CALL_COUNTER.get()).isEqualTo(2);
        }

        // Recovery: the restored memory state already contains "user" as a nested object (from
        // the first input), and the second input is re-delivered. Its action is completed in the
        // ActionState store, so its memory updates are replayed against the restored state. The
        // newObject update must tolerate the already existing object — replaying it as
        // set("user", null) would throw "Cannot overwrite object with value" and crash-loop
        // recovery.
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        constantKey,
                        TypeInformation.of(Long.class))) {
            testHarness.initializeState(snapshot);
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(9L));
            operator.waitInFlightEventsFinished();

            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput).hasSize(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo(10L);
            assertThat(TestAgent.NESTED_MEMORY_ACTION_CALL_COUNTER.get())
                    .as("Completed action must not be re-executed during replay")
                    .isEqualTo(2);
        }
    }

    @Test
    void testReplayRebindsOutputLineage() throws Exception {
        AgentPlan agentPlan = TestAgent.getAgentPlan(false);
        long inputValue = 7L;
        InMemoryActionStateStore actionStateStore = new InMemoryActionStateStore(false);
        Map<String, EventSnapshot> firstSnapshots;
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            firstSnapshots = recordEventSnapshotsByType(operator);

            // Execute the actions and persist their completed states.
            testHarness.processElement(new StreamRecord<>(inputValue));
            operator.waitInFlightEventsFinished();
        }

        Map<String, EventSnapshot> replaySnapshots;
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();
            replaySnapshots = recordEventSnapshotsByType(operator);

            // Replay the same input and reuse the completed action states.
            testHarness.processElement(new StreamRecord<>(inputValue));
            operator.waitInFlightEventsFinished();
        }

        EventSnapshot firstInput = firstSnapshots.get(InputEvent.EVENT_TYPE);
        EventSnapshot replayInput = replaySnapshots.get(InputEvent.EVENT_TYPE);
        EventSnapshot firstMiddle = firstSnapshots.get(TestAgent.MiddleEvent.EVENT_TYPE);
        EventSnapshot replayMiddle = replaySnapshots.get(TestAgent.MiddleEvent.EVENT_TYPE);
        EventSnapshot firstOutput = firstSnapshots.get(OutputEvent.EVENT_TYPE);
        EventSnapshot replayOutput = replaySnapshots.get(OutputEvent.EVENT_TYPE);

        assertThat(firstInput.id).isNotEqualTo(replayInput.id);

        assertThat(replayMiddle.id).isEqualTo(firstMiddle.id);
        assertThat(firstMiddle.upstreamEventId).isEqualTo(firstInput.id);
        assertThat(replayMiddle.upstreamEventId).isEqualTo(replayInput.id);
        assertThat(firstMiddle.upstreamEventId).isNotEqualTo(replayMiddle.upstreamEventId);
        assertThat(firstMiddle.upstreamActionName).isEqualTo("action1");
        assertThat(replayMiddle.upstreamActionName).isEqualTo("action1");

        assertThat(replayOutput.id).isEqualTo(firstOutput.id);
        assertThat(firstOutput.upstreamEventId).isEqualTo(firstMiddle.id);
        assertThat(replayOutput.upstreamEventId).isEqualTo(replayMiddle.id);
        assertThat(firstOutput.upstreamActionName).isEqualTo("action2");
        assertThat(replayOutput.upstreamActionName).isEqualTo("action2");
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

    /**
     * Regression test: durable-store lookups must use the original typed key, never its string
     * form. The key-group segment embedded in every action-state record key is derived from the
     * typed key's hash, so a stringified lookup computes a different key-group at maxParallelism
     * greater than 1 and every recovery read misses, silently re-executing completed durable calls.
     * Harness maxParallelism of 1 masks this (all keys collapse to key-group 0), hence the
     * realistic maxParallelism here.
     */
    @Test
    void testDurableRecoveryHitsCacheWithTypedKeyAtRealisticMaxParallelism() throws Exception {
        final int maxParallelism = 128;
        final long key = 1L;
        // Fixture guard: the regression only manifests when the typed key and its string form
        // hash to different key-groups.
        assertThat(KeyGroupRangeAssignment.assignToKeyGroup(key, maxParallelism))
                .isNotEqualTo(
                        KeyGroupRangeAssignment.assignToKeyGroup(
                                String.valueOf(key), maxParallelism));

        AgentPlan agentPlan = TestAgent.getDurableSyncAgentPlan();
        InMemoryActionStateStore actionStateStore = new InMemoryActionStateStore(false);
        TestAgent.DURABLE_CALL_COUNTER.set(0);

        for (int run = 0; run < 2; run++) {
            try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                    new KeyedOneInputStreamOperatorTestHarness<>(
                            new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                            (KeySelector<Long, Long>) value -> value,
                            TypeInformation.of(Long.class),
                            maxParallelism,
                            1,
                            0)) {
                testHarness.open();
                ActionExecutionOperator<Long, Object> operator =
                        (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

                testHarness.processElement(new StreamRecord<>(key));
                operator.waitInFlightEventsFinished();

                List<StreamRecord<Object>> recordOutput =
                        (List<StreamRecord<Object>>) testHarness.getRecordOutput();
                assertThat(recordOutput).hasSize(1);
                assertThat(recordOutput.get(0).getValue()).isEqualTo(key * 3);
            }
        }

        assertThat(TestAgent.DURABLE_CALL_COUNTER.get())
                .as("Second run must recover from the durable store instead of re-executing")
                .isEqualTo(1);
    }

    /**
     * Regression test for the recovery ownership check: the key-group embedded in a persisted
     * action-state record key is derived from the original typed key, and after rescaling it must
     * be accepted by exactly the subtask that Flink assigns that key to. Under the old scheme —
     * ownership recomputed by hashing the string form of the business key — the true owner (subtask
     * of Long(1)'s key-group) would have dropped its own record while a foreign subtask retained
     * it, re-executing completed actions and leaking orphan state.
     */
    @Test
    void testOwnershipFilterAcceptsTypedKeyGroupOnlyOnOwnerSubtask() throws Exception {
        final int maxParallelism = 128;
        final int parallelism = 2;
        final long key = 1L;
        AgentPlan agentPlan = TestAgent.getDurableSyncAgentPlan();

        // Phase 1: run with the typed key so the store holds records whose embedded key-group was
        // computed from Long(1), not from "1".
        InMemoryActionStateStore writerStore = new InMemoryActionStateStore(false);
        TestAgent.DURABLE_CALL_COUNTER.set(0);
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> writerHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, writerStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class),
                        maxParallelism,
                        1,
                        0)) {
            writerHarness.open();
            writerHarness.processElement(new StreamRecord<>(key));
            ((ActionExecutionOperator<Long, Object>) writerHarness.getOperator())
                    .waitInFlightEventsFinished();
        }

        List<String> persistedKeys =
                writerStore.getKeyedActionStates().values().stream()
                        .flatMap(states -> states.keySet().stream())
                        .collect(Collectors.toList());
        assertThat(persistedKeys).isNotEmpty();
        int embeddedKeyGroup = ActionStateUtil.parseKeyGroup(persistedKeys.get(0));
        assertThat(embeddedKeyGroup)
                .isEqualTo(KeyGroupRangeAssignment.assignToKeyGroup(key, maxParallelism));

        int ownerSubtask =
                KeyGroupRangeAssignment.computeOperatorIndexForKeyGroup(
                        maxParallelism, parallelism, embeddedKeyGroup);
        int stringDerivedKeyGroup =
                KeyGroupRangeAssignment.assignToKeyGroup(String.valueOf(key), maxParallelism);
        // Fixture guard: the string-derived key-group must land on the other subtask, mirroring
        // the original ownership bug.
        assertThat(
                        KeyGroupRangeAssignment.computeOperatorIndexForKeyGroup(
                                maxParallelism, parallelism, stringDerivedKeyGroup))
                .isNotEqualTo(ownerSubtask);

        // Phase 2: restart at parallelism 2 and capture the ownership filter each subtask
        // installs on its store during recovery.
        FilterCapturingActionStateStore ownerStore = new FilterCapturingActionStateStore();
        FilterCapturingActionStateStore nonOwnerStore = new FilterCapturingActionStateStore();
        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> ownerHarness =
                        new KeyedOneInputStreamOperatorTestHarness<>(
                                new ActionExecutionOperatorFactory<>(agentPlan, true, ownerStore),
                                (KeySelector<Long, Long>) value -> value,
                                TypeInformation.of(Long.class),
                                maxParallelism,
                                parallelism,
                                ownerSubtask);
                KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> nonOwnerHarness =
                        new KeyedOneInputStreamOperatorTestHarness<>(
                                new ActionExecutionOperatorFactory<>(
                                        agentPlan, true, nonOwnerStore),
                                (KeySelector<Long, Long>) value -> value,
                                TypeInformation.of(Long.class),
                                maxParallelism,
                                parallelism,
                                1 - ownerSubtask)) {
            ownerHarness.open();
            nonOwnerHarness.open();

            assertThat(ownerStore.capturedOwnershipFilter).isNotNull();
            assertThat(nonOwnerStore.capturedOwnershipFilter).isNotNull();

            assertThat(ownerStore.capturedOwnershipFilter.test(embeddedKeyGroup))
                    .as("The subtask owning the typed key's key-group must retain the record")
                    .isTrue();
            assertThat(nonOwnerStore.capturedOwnershipFilter.test(embeddedKeyGroup))
                    .as("Every other subtask must drop the record")
                    .isFalse();
            assertThat(ownerStore.capturedOwnershipFilter.test(stringDerivedKeyGroup))
                    .as(
                            "String-derived key-group must not be owned by the typed key's owner;"
                                    + " otherwise the original string-hash ownership bug would be"
                                    + " undetectable")
                    .isFalse();
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

    @Test
    void testDurableExecuteReconcilableRecoverySuccess() throws Exception {
        AgentPlan agentPlan = TestAgent.getDurableReconcilableAgentPlan();
        InMemoryActionStateStore actionStateStore = new InMemoryActionStateStore(false, 1);
        long key = 1L;
        long input = 1L;
        TestAgent.RECONCILABLE_RECOVERY_BEHAVIOR = TestAgent.ReconcileBehavior.SUCCESS;
        TestAgent.RECONCILABLE_RECOVERY_RESULT = 42L;

        seedActionState(
                actionStateStore,
                key,
                input,
                agentPlan,
                "durableReconcilableAction",
                actionStateWithCallResults(CallResult.pending("reconcilable-call", "")));

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(input));
            operator.waitInFlightEventsFinished();

            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput).hasSize(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo(42L);
        }

        assertThat(TestAgent.RECONCILABLE_CALL_COUNTER.get()).isZero();
        assertThat(TestAgent.RECONCILABLE_RECONCILE_COUNTER.get()).isEqualTo(1);

        ActionState persistedState =
                getStoredActionState(
                        actionStateStore, key, input, agentPlan, "durableReconcilableAction");
        assertThat(persistedState.isCompleted()).isTrue();
        assertThat(persistedState.getCallResults()).isEmpty();
    }

    @Test
    void testDurableExecuteReconcilableRecoveryException() throws Exception {
        AgentPlan agentPlan = TestAgent.getDurableReconcilableAgentPlan();
        InMemoryActionStateStore actionStateStore = new InMemoryActionStateStore(false, 1);
        long key = 2L;
        long input = 2L;
        TestAgent.RECONCILABLE_RECOVERY_BEHAVIOR = TestAgent.ReconcileBehavior.EXCEPTION;
        TestAgent.RECONCILABLE_EXCEPTION_MESSAGE = "reconcile unavailable";

        seedActionState(
                actionStateStore,
                key,
                input,
                agentPlan,
                "durableReconcilableAction",
                actionStateWithCallResults(CallResult.pending("reconcilable-call", "")));

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(input));
            operator.waitInFlightEventsFinished();

            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput).hasSize(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo("ERROR:reconcile unavailable");
        }

        assertThat(TestAgent.RECONCILABLE_CALL_COUNTER.get()).isZero();
        assertThat(TestAgent.RECONCILABLE_RECONCILE_COUNTER.get()).isEqualTo(1);

        ActionState persistedState =
                getStoredActionState(
                        actionStateStore, key, input, agentPlan, "durableReconcilableAction");
        assertThat(persistedState.isCompleted()).isTrue();
        assertThat(persistedState.getCallResults()).isEmpty();
    }

    @Test
    void testDurableExecuteReconcilableRecoveryMismatchStartsNewCall() throws Exception {
        AgentPlan agentPlan = TestAgent.getDurableReconcilableAgentPlan();
        InMemoryActionStateStore actionStateStore = new InMemoryActionStateStore(false);
        long key = 4L;
        long input = 4L;

        seedActionState(
                actionStateStore,
                key,
                input,
                agentPlan,
                "durableReconcilableAction",
                actionStateWithCallResults(CallResult.pending("stale-call", "")));

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(input));
            operator.waitInFlightEventsFinished();

            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput).hasSize(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo(20L);
        }

        assertThat(TestAgent.RECONCILABLE_CALL_COUNTER.get()).isEqualTo(1);
        assertThat(TestAgent.RECONCILABLE_RECONCILE_COUNTER.get()).isZero();

        ActionState persistedState =
                getStoredActionState(
                        actionStateStore, key, input, agentPlan, "durableReconcilableAction");
        assertThat(persistedState.isCompleted()).isTrue();
        assertThat(persistedState.getCallResults()).isEmpty();
    }

    @Test
    void testDurableExecuteRecoveryMixedCompletionOnlyAndReconcilableCalls() throws Exception {
        AgentPlan agentPlan = TestAgent.getDurableMixedRecoveryAgentPlan();
        InMemoryActionStateStore actionStateStore = new InMemoryActionStateStore(false, 1);
        long key = 1L;
        long input = 1L;
        TestAgent.MIXED_RECONCILE_BEHAVIOR = TestAgent.ReconcileBehavior.SUCCESS;
        TestAgent.MIXED_RECONCILE_RESULT = 50L;

        seedActionState(
                actionStateStore,
                key,
                input,
                agentPlan,
                "durableMixedRecoveryAction",
                actionStateWithCallResults(
                        new CallResult(
                                "mixed-legacy-call", "", OBJECT_MAPPER.writeValueAsBytes(11L)),
                        CallResult.pending("mixed-reconcilable-call", "")));

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true, actionStateStore),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(input));
            operator.waitInFlightEventsFinished();

            List<StreamRecord<Object>> recordOutput =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(recordOutput).hasSize(1);
            assertThat(recordOutput.get(0).getValue()).isEqualTo(61L);
        }

        assertThat(TestAgent.MIXED_LEGACY_CALL_COUNTER.get()).isZero();
        assertThat(TestAgent.MIXED_RECONCILABLE_CALL_COUNTER.get()).isZero();
        assertThat(TestAgent.MIXED_RECONCILE_COUNTER.get()).isEqualTo(1);

        ActionState persistedState =
                getStoredActionState(
                        actionStateStore, key, input, agentPlan, "durableMixedRecoveryAction");
        assertThat(persistedState.isCompleted()).isTrue();
        assertThat(persistedState.getCallResults()).isEmpty();
    }

    public static class TestAgent {

        private static final String ATTACHMENT_EVENT_TYPE = "AttachmentEvent";
        private static final String ATTACHMENT_KEY = "payload";

        /** Counter to track how many times the durable supplier is executed. */
        public static final java.util.concurrent.atomic.AtomicInteger DURABLE_CALL_COUNTER =
                new java.util.concurrent.atomic.AtomicInteger(0);

        /** Counters used to verify that completed Actions are not re-executed during replay. */
        public static final java.util.concurrent.atomic.AtomicInteger ACTION1_CALL_COUNTER =
                new java.util.concurrent.atomic.AtomicInteger(0);

        public static final java.util.concurrent.atomic.AtomicInteger ACTION2_CALL_COUNTER =
                new java.util.concurrent.atomic.AtomicInteger(0);

        public static final java.util.concurrent.atomic.AtomicBoolean FOLLOWING_ACTION_EXECUTED =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        public static final java.util.concurrent.atomic.AtomicInteger
                NESTED_MEMORY_ACTION_CALL_COUNTER =
                        new java.util.concurrent.atomic.AtomicInteger(0);

        public static class MiddleEvent extends Event {
            public static final String EVENT_TYPE = "MiddleEvent";

            public Long num;

            public MiddleEvent(Long num) {
                super(EVENT_TYPE);
                this.num = num;
            }

            public Long getNum() {
                return num;
            }
        }

        public static void action1(Event event, RunnerContext context) {
            ACTION1_CALL_COUNTER.incrementAndGet();
            Long inputData = (Long) InputEvent.fromEvent(event).getInput();
            try {
                MemoryObject mem = context.getShortTermMemory();
                mem.set("tmp", inputData + 1);
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
            context.sendEvent(new MiddleEvent(inputData + 1));
        }

        public static void sendEventAttachment(Event event, RunnerContext context) {
            Event attachmentEvent = new Event(ATTACHMENT_EVENT_TYPE);
            attachmentEvent.setAttachment(
                    ATTACHMENT_KEY, Map.of("value", InputEvent.fromEvent(event).getInput()));
            context.sendEvent(attachmentEvent);
        }

        public static void receiveEventAttachment(Event event, RunnerContext context) {
            Object attachment = event.getAttachment(ATTACHMENT_KEY);
            event.setAttachment(ATTACHMENT_KEY, "mutated-by-action");
            context.sendEvent(new OutputEvent(attachment));
        }

        public static void action2(MiddleEvent event, RunnerContext context) {
            ACTION2_CALL_COUNTER.incrementAndGet();
            try {
                MemoryObject mem = context.getShortTermMemory();
                Long tmp = (Long) mem.get("tmp").getValue();
                context.sendEvent(new OutputEvent(tmp * 2));
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
        }

        public static void nestedMemoryAction(Event event, RunnerContext context) {
            NESTED_MEMORY_ACTION_CALL_COUNTER.incrementAndGet();
            Long inputData = (Long) InputEvent.fromEvent(event).getInput();
            try {
                MemoryObject mem = context.getShortTermMemory();
                mem.newObject("user");
                mem.set("user.score", inputData + 1);
                context.sendEvent(new OutputEvent(inputData + 1));
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

        public static void bestEffortMemoryObservationAction(Event event, RunnerContext context) {
            Long input = (Long) InputEvent.fromEvent(event).getInput();
            try {
                context.getShortTermMemory().set("valid", input);
                context.getShortTermMemory().set("kryo-only", new KryoOnlyObservationValue());
                context.getShortTermMemory().set("non-finite", Double.NaN);
                context.sendEvent(new OutputEvent(input));
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
        }

        private static final class KryoOnlyObservationValue implements Serializable {
            private static final long serialVersionUID = 1L;
            private final Object value = new Object();
        }

        private static <T> DurableCallable<T> durableCallable(
                String id, Class<T> resultClass, Callable<T> callSupplier) {
            return new DurableCallable<T>() {
                @Override
                public String getId() {
                    return id;
                }

                @Override
                public Class<T> getResultClass() {
                    return resultClass;
                }

                @Override
                public T call() throws Exception {
                    return callSupplier.call();
                }
            };
        }

        private static <T> DurableCallable<T> reconcilableDurableCallable(
                String id,
                Class<T> resultClass,
                Callable<T> callSupplier,
                Callable<T> reconcileSupplier) {
            return new DurableCallable<T>() {
                @Override
                public String getId() {
                    return id;
                }

                @Override
                public Class<T> getResultClass() {
                    return resultClass;
                }

                @Override
                public T call() throws Exception {
                    return callSupplier.call();
                }

                @Override
                public Callable<T> reconciler() {
                    return reconcileSupplier;
                }
            };
        }

        public static void asyncAction1(Event event, RunnerContext context) {
            Long inputData = (Long) InputEvent.fromEvent(event).getInput();
            try {
                Long result =
                        context.durableExecuteAsync(
                                durableCallable(
                                        "async-multiply",
                                        Long.class,
                                        () -> {
                                            try {
                                                Thread.sleep(50);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                            return inputData * 10;
                                        }));

                MemoryObject mem = context.getShortTermMemory();
                mem.set("tmp", result);
                context.sendEvent(new MiddleEvent(result));
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
        }

        public static void multiAsyncAction(Event event, RunnerContext context) {
            Long inputData = (Long) InputEvent.fromEvent(event).getInput();
            try {
                Long result1 =
                        context.durableExecuteAsync(
                                durableCallable(
                                        "async-add",
                                        Long.class,
                                        () -> {
                                            try {
                                                Thread.sleep(30);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                            return inputData + 100;
                                        }));

                Long result2 =
                        context.durableExecuteAsync(
                                durableCallable(
                                        "async-multiply",
                                        Long.class,
                                        () -> {
                                            try {
                                                Thread.sleep(30);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                            return result1 * 2;
                                        }));

                MemoryObject mem = context.getShortTermMemory();
                mem.set("multiAsyncResult", result2);
                context.sendEvent(new OutputEvent(result2));
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
        }

        public static void durableSyncAction(Event event, RunnerContext context) {
            Long inputData = (Long) InputEvent.fromEvent(event).getInput();
            try {
                Long result =
                        context.durableExecute(
                                durableCallable(
                                        "sync-compute",
                                        Long.class,
                                        () -> {
                                            DURABLE_CALL_COUNTER.incrementAndGet();
                                            return inputData * 3;
                                        }));

                context.sendEvent(new OutputEvent(result));
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
        }

        public static final java.util.concurrent.atomic.AtomicInteger EXCEPTION_CALL_COUNTER =
                new java.util.concurrent.atomic.AtomicInteger(0);

        public enum ReconcileBehavior {
            SUCCESS,
            EXCEPTION
        }

        public static final java.util.concurrent.atomic.AtomicInteger RECONCILABLE_CALL_COUNTER =
                new java.util.concurrent.atomic.AtomicInteger(0);
        public static final java.util.concurrent.atomic.AtomicInteger
                RECONCILABLE_RECONCILE_COUNTER = new java.util.concurrent.atomic.AtomicInteger(0);
        public static volatile ReconcileBehavior RECONCILABLE_RECOVERY_BEHAVIOR =
                ReconcileBehavior.SUCCESS;
        public static volatile long RECONCILABLE_RECOVERY_RESULT = 42L;
        public static volatile String RECONCILABLE_EXCEPTION_MESSAGE = "reconcile unavailable";

        public static final java.util.concurrent.atomic.AtomicInteger MIXED_LEGACY_CALL_COUNTER =
                new java.util.concurrent.atomic.AtomicInteger(0);
        public static final java.util.concurrent.atomic.AtomicInteger
                MIXED_RECONCILABLE_CALL_COUNTER = new java.util.concurrent.atomic.AtomicInteger(0);
        public static final java.util.concurrent.atomic.AtomicInteger MIXED_RECONCILE_COUNTER =
                new java.util.concurrent.atomic.AtomicInteger(0);
        public static volatile ReconcileBehavior MIXED_RECONCILE_BEHAVIOR =
                ReconcileBehavior.SUCCESS;
        public static volatile long MIXED_RECONCILE_RESULT = 50L;

        public static void durableExceptionAction(Event event, RunnerContext context) {
            try {
                context.durableExecute(
                        durableCallable(
                                "exception-action",
                                String.class,
                                () -> {
                                    EXCEPTION_CALL_COUNTER.incrementAndGet();
                                    throw new RuntimeException(
                                            "Test exception from durableExecute");
                                }));
            } catch (Exception e) {
                context.sendEvent(new OutputEvent("ERROR:" + e.getMessage()));
            }
        }

        public static void durableReconcilableAction(Event event, RunnerContext context) {
            Long inputData = (Long) InputEvent.fromEvent(event).getInput();
            try {
                Long result =
                        context.durableExecute(
                                reconcilableDurableCallable(
                                        "reconcilable-call",
                                        Long.class,
                                        () -> {
                                            RECONCILABLE_CALL_COUNTER.incrementAndGet();
                                            return inputData * 5;
                                        },
                                        () -> {
                                            RECONCILABLE_RECONCILE_COUNTER.incrementAndGet();
                                            switch (RECONCILABLE_RECOVERY_BEHAVIOR) {
                                                case SUCCESS:
                                                    return RECONCILABLE_RECOVERY_RESULT;
                                                case EXCEPTION:
                                                    throw new IllegalStateException(
                                                            RECONCILABLE_EXCEPTION_MESSAGE);
                                            }
                                            throw new IllegalStateException(
                                                    "Unsupported reconcile behavior");
                                        }));
                context.sendEvent(new OutputEvent(result));
            } catch (Exception e) {
                context.sendEvent(new OutputEvent("ERROR:" + e.getMessage()));
            }
        }

        public static void durableMixedRecoveryAction(Event event, RunnerContext context) {
            Long inputData = (Long) InputEvent.fromEvent(event).getInput();
            try {
                Long firstResult =
                        context.durableExecute(
                                durableCallable(
                                        "mixed-legacy-call",
                                        Long.class,
                                        () -> {
                                            MIXED_LEGACY_CALL_COUNTER.incrementAndGet();
                                            return inputData + 10;
                                        }));
                Long secondResult =
                        context.durableExecute(
                                reconcilableDurableCallable(
                                        "mixed-reconcilable-call",
                                        Long.class,
                                        () -> {
                                            MIXED_RECONCILABLE_CALL_COUNTER.incrementAndGet();
                                            return firstResult * 2;
                                        },
                                        () -> {
                                            MIXED_RECONCILE_COUNTER.incrementAndGet();
                                            switch (MIXED_RECONCILE_BEHAVIOR) {
                                                case SUCCESS:
                                                    return MIXED_RECONCILE_RESULT;
                                                case EXCEPTION:
                                                    throw new IllegalStateException(
                                                            "mixed reconcile failed");
                                            }
                                            throw new IllegalStateException(
                                                    "Unsupported reconcile behavior");
                                        }));
                context.sendEvent(new OutputEvent(firstResult + secondResult));
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
        }

        public static class LinkageErrorAction {
            static {
                if (shouldThrowLinkageError()) {
                    throw new NoClassDefFoundError("synthetic missing runtime dependency");
                }
            }

            private static boolean shouldThrowLinkageError() {
                return true;
            }

            public static void action(Event event, RunnerContext context) {}
        }

        public static void failingActionAfterLtm(Event event, RunnerContext context) {
            try {
                context.getLongTermMemory()
                        .getMemorySet("notes")
                        .add(List.of("previous action"), null);
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
            throw new IllegalStateException("first action failed after LTM");
        }

        public static void followingAction(Event event, RunnerContext context) {
            FOLLOWING_ACTION_EXECUTED.set(true);
        }

        public static void resetReconcilableRecoveryFixture() {
            RECONCILABLE_CALL_COUNTER.set(0);
            RECONCILABLE_RECONCILE_COUNTER.set(0);
            RECONCILABLE_RECOVERY_BEHAVIOR = ReconcileBehavior.SUCCESS;
            RECONCILABLE_RECOVERY_RESULT = 42L;
            RECONCILABLE_EXCEPTION_MESSAGE = "reconcile unavailable";
        }

        public static void resetMixedRecoveryFixture() {
            MIXED_LEGACY_CALL_COUNTER.set(0);
            MIXED_RECONCILABLE_CALL_COUNTER.set(0);
            MIXED_RECONCILE_COUNTER.set(0);
            MIXED_RECONCILE_BEHAVIOR = ReconcileBehavior.SUCCESS;
            MIXED_RECONCILE_RESULT = 50L;
        }

        public static AgentPlan getAgentPlan(boolean testMemoryAccessOutOfMailbox) {
            return getAgentPlanWithConfig(new AgentConfiguration(), testMemoryAccessOutOfMailbox);
        }

        public static AgentPlan getEventAttachmentAgentPlan() {
            try {
                Action sendAction =
                        new Action(
                                "sendEventAttachment",
                                new JavaFunction(
                                        TestAgent.class,
                                        "sendEventAttachment",
                                        new Class<?>[] {Event.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.EVENT_TYPE));
                Action receiveAction =
                        new Action(
                                "receiveEventAttachment",
                                new JavaFunction(
                                        TestAgent.class,
                                        "receiveEventAttachment",
                                        new Class<?>[] {Event.class, RunnerContext.class}),
                                Collections.singletonList(ATTACHMENT_EVENT_TYPE));
                return new AgentPlan(
                        Map.of(
                                sendAction.getName(), sendAction,
                                receiveAction.getName(), receiveAction));
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
            return null;
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
                                        new Class<?>[] {Event.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.EVENT_TYPE));
                Action action2 =
                        new Action(
                                "action2",
                                new JavaFunction(
                                        TestAgent.class,
                                        "action2",
                                        new Class<?>[] {MiddleEvent.class, RunnerContext.class}),
                                Collections.singletonList(MiddleEvent.EVENT_TYPE));
                actionsByEvent.put(InputEvent.EVENT_TYPE, Collections.singletonList(action1));
                actionsByEvent.put(MiddleEvent.EVENT_TYPE, Collections.singletonList(action2));
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
                                    Collections.singletonList(MiddleEvent.EVENT_TYPE));
                    actionsByEvent.put(MiddleEvent.EVENT_TYPE, Collections.singletonList(action3));
                    actions.put(action3.getName(), action3);
                }

                return new AgentPlan(actions, new HashMap<>(), config);
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
            return null;
        }

        /** Creates an AgentPlan with a single action that creates a nested memory object. */
        public static AgentPlan getNestedMemoryAgentPlan() {
            try {
                Action nestedMemoryAction =
                        new Action(
                                "nestedMemoryAction",
                                new JavaFunction(
                                        TestAgent.class,
                                        "nestedMemoryAction",
                                        new Class<?>[] {Event.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.EVENT_TYPE));
                Map<String, List<Action>> actionsByEvent = new HashMap<>();
                actionsByEvent.put(
                        InputEvent.EVENT_TYPE, Collections.singletonList(nestedMemoryAction));
                Map<String, Action> actions = new HashMap<>();
                actions.put(nestedMemoryAction.getName(), nestedMemoryAction);
                return new AgentPlan(actions, new HashMap<>(), new AgentConfiguration());
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
                                            new Class<?>[] {Event.class, RunnerContext.class}),
                                    Collections.singletonList(InputEvent.EVENT_TYPE));
                    actionsByEvent.put(
                            InputEvent.EVENT_TYPE, Collections.singletonList(multiAsyncAction));
                    actions.put(multiAsyncAction.getName(), multiAsyncAction);
                } else {
                    // Use asyncAction1 -> action2 chain
                    Action asyncAction1 =
                            new Action(
                                    "asyncAction1",
                                    new JavaFunction(
                                            TestAgent.class,
                                            "asyncAction1",
                                            new Class<?>[] {Event.class, RunnerContext.class}),
                                    Collections.singletonList(InputEvent.EVENT_TYPE));
                    Action action2 =
                            new Action(
                                    "action2",
                                    new JavaFunction(
                                            TestAgent.class,
                                            "action2",
                                            new Class<?>[] {
                                                MiddleEvent.class, RunnerContext.class
                                            }),
                                    Collections.singletonList(MiddleEvent.EVENT_TYPE));
                    actionsByEvent.put(
                            InputEvent.EVENT_TYPE, Collections.singletonList(asyncAction1));
                    actionsByEvent.put(MiddleEvent.EVENT_TYPE, Collections.singletonList(action2));
                    actions.put(asyncAction1.getName(), asyncAction1);
                    actions.put(action2.getName(), action2);
                }

                return new AgentPlan(actions, new HashMap<>());
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
                                        new Class<?>[] {Event.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.EVENT_TYPE));
                actionsByEvent.put(
                        InputEvent.EVENT_TYPE, Collections.singletonList(durableSyncAction));
                actions.put(durableSyncAction.getName(), durableSyncAction);

                return new AgentPlan(actions, new HashMap<>());
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
            return null;
        }

        public static AgentPlan getDurableReconcilableAgentPlan() {
            try {
                Map<String, List<Action>> actionsByEvent = new HashMap<>();
                Map<String, Action> actions = new HashMap<>();

                Action reconcilableAction =
                        new Action(
                                "durableReconcilableAction",
                                new JavaFunction(
                                        TestAgent.class,
                                        "durableReconcilableAction",
                                        new Class<?>[] {Event.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.EVENT_TYPE));
                actionsByEvent.put(
                        InputEvent.EVENT_TYPE, Collections.singletonList(reconcilableAction));
                actions.put(reconcilableAction.getName(), reconcilableAction);

                return new AgentPlan(actions, new HashMap<>());
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
            return null;
        }

        public static AgentPlan getDurableMixedRecoveryAgentPlan() {
            try {
                Map<String, List<Action>> actionsByEvent = new HashMap<>();
                Map<String, Action> actions = new HashMap<>();

                Action mixedRecoveryAction =
                        new Action(
                                "durableMixedRecoveryAction",
                                new JavaFunction(
                                        TestAgent.class,
                                        "durableMixedRecoveryAction",
                                        new Class<?>[] {Event.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.EVENT_TYPE));
                actionsByEvent.put(
                        InputEvent.EVENT_TYPE, Collections.singletonList(mixedRecoveryAction));
                actions.put(mixedRecoveryAction.getName(), mixedRecoveryAction);

                return new AgentPlan(actions, new HashMap<>());
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
                                        new Class<?>[] {Event.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.EVENT_TYPE));
                actionsByEvent.put(
                        InputEvent.EVENT_TYPE, Collections.singletonList(exceptionAction));
                actions.put(exceptionAction.getName(), exceptionAction);

                return new AgentPlan(actions, new HashMap<>());
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
            return null;
        }

        public static void requestLinkageErrorTool(Event event, RunnerContext context) {
            Map<String, Object> function = new HashMap<>();
            function.put("name", "linkageErrorTool");
            function.put("arguments", new HashMap<String, Object>());
            Map<String, Object> toolCall = new HashMap<>();
            toolCall.put("id", "call-1");
            toolCall.put("function", function);
            context.sendEvent(new ToolRequestEvent("unused-model", List.of(toolCall)));
        }

        public static String linkageErrorTool() {
            throw new NoClassDefFoundError("synthetic missing runtime dependency");
        }

        public static AgentPlan getLinkageErrorAgentPlan() {
            try {
                Map<String, Action> actions = new HashMap<>();

                Action errorAction =
                        new Action(
                                "linkageErrorAction",
                                new JavaFunction(
                                        LinkageErrorAction.class,
                                        "action",
                                        new Class<?>[] {Event.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.EVENT_TYPE));
                actions.put(errorAction.getName(), errorAction);

                return new AgentPlan(actions);
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
            return null;
        }

        public static AgentPlan getLinkageErrorToolAgentPlan() {
            try {
                Action requestAction =
                        new Action(
                                "requestLinkageErrorTool",
                                new JavaFunction(
                                        TestAgent.class,
                                        "requestLinkageErrorTool",
                                        new Class<?>[] {Event.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.EVENT_TYPE));
                Action toolCallAction = ToolCallAction.getToolCallAction();
                Map<String, Action> actions = new LinkedHashMap<>();
                actions.put(requestAction.getName(), requestAction);
                actions.put(toolCallAction.getName(), toolCallAction);

                FunctionTool tool =
                        FunctionTool.fromStaticMethod(
                                "Throws a linkage error.",
                                TestAgent.class.getMethod("linkageErrorTool"));
                Map<String, ResourceProvider> tools = new HashMap<>();
                tools.put(
                        "linkageErrorTool",
                        JavaSerializableResourceProvider.createResourceProvider(
                                "linkageErrorTool", ResourceType.TOOL, tool));
                Map<ResourceType, Map<String, ResourceProvider>> resourceProviders =
                        new HashMap<>();
                resourceProviders.put(ResourceType.TOOL, tools);

                return new AgentPlan(actions, resourceProviders);
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
            return null;
        }

        public static AgentPlan getBestEffortMemoryObservationPlan() {
            try {
                Action action =
                        new Action(
                                "bestEffortMemoryObservationAction",
                                new JavaFunction(
                                        TestAgent.class,
                                        "bestEffortMemoryObservationAction",
                                        new Class<?>[] {Event.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.EVENT_TYPE));
                return new AgentPlan(Map.of(action.getName(), action));
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
            return null;
        }

        public static AgentPlan getFailedActionAfterLtmAgentPlan() {
            try {
                Map<String, Action> actions = new LinkedHashMap<>();
                Action failingAction =
                        new Action(
                                "failingActionAfterLtm",
                                new JavaFunction(
                                        TestAgent.class,
                                        "failingActionAfterLtm",
                                        new Class<?>[] {Event.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.EVENT_TYPE));
                Action followingAction =
                        new Action(
                                "followingAction",
                                new JavaFunction(
                                        TestAgent.class,
                                        "followingAction",
                                        new Class<?>[] {Event.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.EVENT_TYPE));
                actions.put(failingAction.getName(), failingAction);
                actions.put(followingAction.getName(), followingAction);

                return new AgentPlan(actions);
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
        public static void durableExceptionUncaughtAction(Event event, RunnerContext context) {
            try {
                context.durableExecute(
                        durableCallable(
                                "uncaught-exception-action",
                                String.class,
                                () -> {
                                    UNCAUGHT_EXCEPTION_CALL_COUNTER.incrementAndGet();
                                    throw new IllegalStateException(
                                            "Simulated LLM failure: Connection timeout");
                                }));
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
        public static void durableAsyncExceptionAction(Event event, RunnerContext context) {
            try {
                context.durableExecuteAsync(
                        durableCallable(
                                "async-exception-action",
                                String.class,
                                () -> {
                                    ASYNC_EXCEPTION_CALL_COUNTER.incrementAndGet();
                                    throw new RuntimeException("Async operation failed: API error");
                                }));
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
                                        new Class<?>[] {Event.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.EVENT_TYPE));
                actionsByEvent.put(
                        InputEvent.EVENT_TYPE, Collections.singletonList(exceptionAction));
                actions.put(exceptionAction.getName(), exceptionAction);

                return new AgentPlan(actions, new HashMap<>());
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
                                        new Class<?>[] {Event.class, RunnerContext.class}),
                                Collections.singletonList(InputEvent.EVENT_TYPE));
                actionsByEvent.put(
                        InputEvent.EVENT_TYPE, Collections.singletonList(exceptionAction));
                actions.put(exceptionAction.getName(), exceptionAction);

                return new AgentPlan(actions, new HashMap<>());
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
            return null;
        }
    }

    private static ActionState actionStateWithCallResults(CallResult... callResults) {
        ActionState actionState = new ActionState(null);
        for (CallResult callResult : callResults) {
            actionState.addCallResult(callResult);
        }
        return actionState;
    }

    private static void seedActionState(
            InMemoryActionStateStore actionStateStore,
            long key,
            long input,
            AgentPlan agentPlan,
            String actionName,
            ActionState actionState)
            throws Exception {
        InputEvent event = new InputEvent(input);
        Action action = agentPlan.getActions().get(actionName);
        actionStateStore.put(key, 0L, action, event, actionState);
    }

    private static ActionState getStoredActionState(
            InMemoryActionStateStore actionStateStore,
            long key,
            long input,
            AgentPlan agentPlan,
            String actionName)
            throws Exception {
        InputEvent event = new InputEvent(input);
        Action action = agentPlan.getActions().get(actionName);
        return actionStateStore.get(key, 0L, action, event);
    }

    /**
     * Records the ownership filter that {@code DurableExecutionManager.handleRecovery} installs on
     * the store during operator recovery, so tests can assert which key-groups a given subtask
     * would retain.
     */
    private static class FilterCapturingActionStateStore extends InMemoryActionStateStore {
        private volatile IntPredicate capturedOwnershipFilter;

        private FilterCapturingActionStateStore() {
            super(false);
        }

        @Override
        public void setOwnershipFilter(IntPredicate ownershipFilter) {
            this.capturedOwnershipFilter = ownershipFilter;
        }
    }

    private static class RecordingActionStateStore extends InMemoryActionStateStore {
        private final List<Long> prunedSeqNums = new java.util.ArrayList<>();

        private RecordingActionStateStore() {
            super(false);
        }

        @Override
        public void pruneState(Object key, long seqNum) {
            prunedSeqNums.add(seqNum);
        }

        private void clearPruneCalls() {
            prunedSeqNums.clear();
        }

        private List<Long> getPrunedSeqNums() {
            return prunedSeqNums;
        }
    }

    private static class SerializingActionStateStore extends InMemoryActionStateStore {
        private final Map<String, byte[]> completedStateBytes = new HashMap<>();

        private SerializingActionStateStore() {
            super(false);
        }

        @Override
        public void put(Object key, long seqNum, Action action, Event event, ActionState state)
                throws IOException {
            if (state.isCompleted()) {
                completedStateBytes.put(action.getName(), ActionStateSerde.serialize(state));
            }
            super.put(key, seqNum, action, event, state);
        }

        private Map<String, byte[]> getCompletedStateBytes() {
            return completedStateBytes;
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
