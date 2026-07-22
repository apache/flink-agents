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
import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.AgentRunBeginEvent;
import org.apache.flink.agents.api.listener.EventListener;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.JavaFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.python.utils.PythonActionExecutor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.flink.types.Row;
import org.apache.flink.util.ExceptionUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Tests for AgentRunBeginEvent emission by {@link ActionExecutionOperator}. */
public class AgentRunBeginEventEmissionTest {

    /** Collects every processed event through the EVENT_LISTENERS seam. */
    public static class CollectingEventListener implements EventListener {
        public static final List<Event> EVENTS = new CopyOnWriteArrayList<>();

        @Override
        public void onEventProcessed(EventContext context, Event event) {
            EVENTS.add(event);
        }
    }

    private static final AtomicBoolean RUN_BEGIN_ACTION_EXECUTED = new AtomicBoolean(false);

    @BeforeEach
    void reset() {
        CollectingEventListener.EVENTS.clear();
        RUN_BEGIN_ACTION_EXECUTED.set(false);
    }

    /** Input-triggered action: writes one STM value. */
    public static void writeTierAction(Event event, RunnerContext context) {
        try {
            context.getShortTermMemory().set("user.tier", "gold");
        } catch (Exception e) {
            ExceptionUtils.rethrow(e);
        }
    }

    /** Run-begin-triggered action: writes an STM flag and records execution. */
    public static void onRunBeginAction(Event event, RunnerContext context) {
        try {
            context.getShortTermMemory().set("run.begin.seen", true);
            context.sendEvent(new OutputEvent("run-begin"));
            RUN_BEGIN_ACTION_EXECUTED.set(true);
        } catch (Exception e) {
            ExceptionUtils.rethrow(e);
        }
    }

    private static AgentPlan buildPlan(AgentConfiguration config, boolean subscribeRunBegin) {
        try {
            Map<String, List<Action>> actionsByEvent = new HashMap<>();
            Map<String, Action> actions = new HashMap<>();
            Action inputAction =
                    new Action(
                            "writeTierAction",
                            new JavaFunction(
                                    AgentRunBeginEventEmissionTest.class,
                                    "writeTierAction",
                                    new Class<?>[] {Event.class, RunnerContext.class}),
                            Collections.singletonList(InputEvent.EVENT_TYPE));
            actionsByEvent.put(InputEvent.EVENT_TYPE, Collections.singletonList(inputAction));
            actions.put(inputAction.getName(), inputAction);

            if (subscribeRunBegin) {
                Action runBeginAction =
                        new Action(
                                "onRunBeginAction",
                                new JavaFunction(
                                        AgentRunBeginEventEmissionTest.class,
                                        "onRunBeginAction",
                                        new Class<?>[] {Event.class, RunnerContext.class}),
                                Collections.singletonList(EventType.AgentRunBeginEvent));
                actionsByEvent.put(
                        EventType.AgentRunBeginEvent, Collections.singletonList(runBeginAction));
                actions.put(runBeginAction.getName(), runBeginAction);
            }
            return new AgentPlan(actions, actionsByEvent, new HashMap<>(), config);
        } catch (Exception e) {
            ExceptionUtils.rethrow(e);
        }
        return null;
    }

    private static AgentConfiguration listenerConfig() {
        AgentConfiguration config = new AgentConfiguration();
        config.set(
                AgentConfigOptions.EVENT_LISTENERS,
                List.of(CollectingEventListener.class.getName()));
        return config;
    }

    private static AgentConfiguration listenerConfigWithRunBeginEvent() {
        AgentConfiguration config = listenerConfig();
        config.set(AgentExecutionOptions.AGENT_RUN_BEGIN_EVENT, true);
        return config;
    }

    private static KeyedOneInputStreamOperatorTestHarness<String, Long, Object> createHarness(
            AgentPlan agentPlan) throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(
                new ActionExecutionOperatorFactory<>(agentPlan, true),
                (KeySelector<Long, String>) String::valueOf,
                TypeInformation.of(String.class));
    }

    private static List<AgentRunBeginEvent> collectedRunBeginEvents() {
        return CollectingEventListener.EVENTS.stream()
                .filter(e -> AgentRunBeginEvent.EVENT_TYPE.equals(e.getType()))
                .map(AgentRunBeginEvent::fromEvent)
                .collect(Collectors.toList());
    }

    @Test
    void testRunBeginEmittedOncePerInputWithPreviousRunStm() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<String, Long, Object> testHarness =
                createHarness(buildPlan(listenerConfigWithRunBeginEvent(), false))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(42L));
            operator.waitInFlightEventsFinished();

            List<AgentRunBeginEvent> runBegins = collectedRunBeginEvents();
            assertThat(runBegins).hasSize(1);
            assertThat(runBegins.get(0).getKey()).isEqualTo("42");
            assertThat(runBegins.get(0).getValue()).isEmpty();
            assertThat(runBegins.get(0).getId()).isNotNull();

            testHarness.processElement(new StreamRecord<>(42L));
            operator.waitInFlightEventsFinished();

            runBegins = collectedRunBeginEvents();
            assertThat(runBegins).hasSize(2);
            assertThat(runBegins.get(1).getKey()).isEqualTo("42");
            assertThat(runBegins.get(1).getValue()).containsEntry("user.tier", "gold");
            assertThat(runBegins.get(1).getId()).isNotEqualTo(runBegins.get(0).getId());
        }
    }

    @Test
    void testRunBeginCopiesInputSourceTimestamp() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<String, Long, Object> testHarness =
                createHarness(buildPlan(listenerConfigWithRunBeginEvent(), true))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(42L, 1234L));
            operator.waitInFlightEventsFinished();

            assertThat(collectedRunBeginEvents())
                    .singleElement()
                    .satisfies(
                            event -> {
                                assertThat(event.hasSourceTimestamp()).isTrue();
                                assertThat(event.getSourceTimestamp()).isEqualTo(1234L);
                            });
            List<StreamRecord<Object>> output =
                    (List<StreamRecord<Object>>) testHarness.getRecordOutput();
            assertThat(output)
                    .singleElement()
                    .satisfies(
                            record -> {
                                assertThat(record.hasTimestamp()).isTrue();
                                assertThat(record.getTimestamp()).isEqualTo(1234L);
                            });
        }
    }

    @Test
    void testRunBeginSnapshotIsolatedPerKey() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<String, Long, Object> testHarness =
                createHarness(buildPlan(listenerConfigWithRunBeginEvent(), false))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            // key 1 writes user.tier; key 2's own run-begin must not see key 1's STM.
            testHarness.processElement(new StreamRecord<>(1L));
            operator.waitInFlightEventsFinished();
            testHarness.processElement(new StreamRecord<>(2L));
            operator.waitInFlightEventsFinished();
            testHarness.processElement(new StreamRecord<>(1L));
            operator.waitInFlightEventsFinished();

            List<AgentRunBeginEvent> runBegins = collectedRunBeginEvents();
            assertThat(runBegins).hasSize(3);
            // key 2's first run-begin sees only its own (empty) STM, no leak from key 1.
            assertThat(runBegins.get(1).getKey()).isEqualTo("2");
            assertThat(runBegins.get(1).getValue()).isEmpty();
            // key 1's second run-begin carries key 1's own prior write.
            assertThat(runBegins.get(2).getKey()).isEqualTo("1");
            assertThat(runBegins.get(2).getValue()).containsEntry("user.tier", "gold");
        }
    }

    @Test
    void testRunBeginDisabledByDefault() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<String, Long, Object> testHarness =
                createHarness(buildPlan(listenerConfig(), false))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(42L));
            operator.waitInFlightEventsFinished();

            assertThat(collectedRunBeginEvents()).isEmpty();
        }
    }

    @Test
    void testRunBeginDisabledByConfig() throws Exception {
        AgentConfiguration config = listenerConfig();
        config.set(AgentExecutionOptions.AGENT_RUN_BEGIN_EVENT, false);
        try (KeyedOneInputStreamOperatorTestHarness<String, Long, Object> testHarness =
                createHarness(buildPlan(config, false))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(42L));
            operator.waitInFlightEventsFinished();

            assertThat(collectedRunBeginEvents()).isEmpty();
        }
    }

    @Test
    void testRunBeginEventTriggersSubscribedAction() throws Exception {
        try (KeyedOneInputStreamOperatorTestHarness<String, Long, Object> testHarness =
                createHarness(buildPlan(listenerConfigWithRunBeginEvent(), true))) {
            testHarness.open();
            ActionExecutionOperator<Long, Object> operator =
                    (ActionExecutionOperator<Long, Object>) testHarness.getOperator();

            testHarness.processElement(new StreamRecord<>(7L));
            operator.waitInFlightEventsFinished();

            // Proves routing through getActionsTriggeredBy, not just logging.
            assertThat(RUN_BEGIN_ACTION_EXECUTED).isTrue();

            // The subscribed action's STM write shows up in the NEXT run's begin snapshot.
            testHarness.processElement(new StreamRecord<>(7L));
            operator.waitInFlightEventsFinished();

            List<AgentRunBeginEvent> runBegins = collectedRunBeginEvents();
            assertThat(runBegins).hasSize(2);
            assertThat(runBegins.get(1).getValue())
                    .containsEntry("run.begin.seen", true)
                    .containsEntry("user.tier", "gold");
        }
    }

    @Test
    void javaRowKeyIsNotDecodedAsPyFlinkKey() {
        PythonActionExecutor pythonActionExecutor = mock(PythonActionExecutor.class);

        assertThat(
                        ActionExecutionOperator.resolveEventKeyText(
                                Row.of(new byte[] {1, 2, 3}), true, pythonActionExecutor))
                .isNull();
        verifyNoInteractions(pythonActionExecutor);
    }
}
