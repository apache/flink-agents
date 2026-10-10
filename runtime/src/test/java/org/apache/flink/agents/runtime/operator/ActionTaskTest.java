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
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.python.context.PythonRunnerContextImpl;
import org.apache.flink.agents.runtime.python.operator.PythonActionTask;
import org.apache.flink.agents.runtime.python.operator.PythonGeneratorActionTask;
import org.apache.flink.agents.runtime.python.utils.PythonActionExecutor;
import org.apache.flink.agents.runtime.subagent.InternalSubagentCallEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests for Action observation identity and output validation. */
class ActionTaskTest {

    @Test
    void distinctActionExecutionsHaveDistinctObservationIds() {
        Event triggeringEvent = new InputEvent(1L);
        Action action = TestActions.noopAction();

        ActionTask first = new JavaActionTask("key", triggeringEvent, action, 1L);
        ActionTask second = new JavaActionTask("key", triggeringEvent, action, 1L);

        assertThat(first.getObservationId()).isNotEqualTo(second.getObservationId());
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void taskRestoredWithoutObservationIdGetsStableFallback() {
        ActionTask task =
                new JavaActionTask("key", new InputEvent(1L), TestActions.noopAction(), 1L);
        task.observationId = null;

        String restoredObservationId = task.getObservationId();

        assertThat(restoredObservationId).isNotBlank();
        assertThat(task.getObservationId()).isEqualTo(restoredObservationId);
    }

    @Test
    void pythonContinuationKeepsObservationId() throws Exception {
        Action action =
                new Action(
                        "python-action",
                        new PythonFunction("test_module", "test_action"),
                        List.of(InputEvent.EVENT_TYPE));
        PythonActionTask task = new PythonActionTask("key", new InputEvent(1L), action, 1L);
        PythonRunnerContextImpl context = mock(PythonRunnerContextImpl.class);
        PythonActionExecutor executor = mock(PythonActionExecutor.class);
        task.setRunnerContext(context);
        when(context.getPythonAwaitableRef()).thenReturn("awaitable");
        when(executor.executePythonFunction((PythonFunction) action.getExec(), task.event))
                .thenReturn("awaitable");
        when(executor.callPythonAwaitable("awaitable")).thenReturn(false);

        ActionTask generated =
                task.invoke(getClass().getClassLoader(), executor)
                        .getGeneratedActionTask()
                        .orElseThrow();

        assertThat(generated).isInstanceOf(PythonGeneratorActionTask.class);
        assertThat(generated.getObservationId()).isEqualTo(task.getObservationId());
    }

    @Test
    void pythonContinuationsDispatchBootstrapOnceAndRetainOrdinaryEventsUntilCompletion()
            throws Exception {
        PythonFunction function = new PythonFunction("test_module", "test_action");
        Action action = new Action("python-action", function, List.of(InputEvent.EVENT_TYPE));
        Event triggeringEvent = new InputEvent(1L);
        triggeringEvent.setSourceTimestamp(123L);
        PythonActionTask task = new PythonActionTask("key", triggeringEvent, action, 1L);
        PythonRunnerContextImpl context =
                new PythonRunnerContextImpl(
                        null, () -> {}, new AgentPlan(Map.of()), null, "test-job");
        PythonActionExecutor executor = mock(PythonActionExecutor.class);
        task.setRunnerContext(context);
        Event firstEvent = new Event("first-event");
        Event secondEvent = new Event("second-event");
        InternalSubagentCallEvent bootstrap =
                InternalSubagentCallEvent.bootstrap(
                        new InputEvent("prompt"), "child", "call", "session");
        when(executor.executePythonFunction(function, triggeringEvent))
                .thenAnswer(
                        invocation -> {
                            context.sendEvent(firstEvent);
                            return "awaitable";
                        });
        when(executor.callPythonAwaitable("awaitable"))
                .thenAnswer(
                        invocation -> {
                            context.sendEvent(bootstrap);
                            return false;
                        })
                .thenAnswer(
                        invocation -> {
                            context.sendEvent(secondEvent);
                            return false;
                        })
                .thenReturn(false, true);

        ActionTask.ActionTaskResult first = task.invoke(getClass().getClassLoader(), executor);

        assertThat(first.isFinished()).isFalse();
        assertThat(first.getOutputEvents()).containsExactly(bootstrap);
        assertThat(bootstrap.getSourceTimestamp()).isEqualTo(123L);
        assertThat(context.getPendingEvents()).containsExactly(firstEvent);
        ActionTask continuation = first.getGeneratedActionTask().orElseThrow();
        assertThat(continuation).isInstanceOf(PythonGeneratorActionTask.class);
        assertThat(continuation.getRunnerContext()).isSameAs(context);
        assertThat(continuation.getObservationId()).isEqualTo(task.getObservationId());

        for (int i = 0; i < 2; i++) {
            ActionTask.ActionTaskResult suspended =
                    continuation.invoke(getClass().getClassLoader(), executor);

            assertThat(suspended.isFinished()).isFalse();
            assertThat(suspended.getOutputEvents()).isEmpty();
            assertThat(suspended.getGeneratedActionTask()).containsSame(continuation);
            assertThat(context.getPendingEvents()).containsExactly(firstEvent, secondEvent);
        }

        ActionTask.ActionTaskResult completed =
                continuation.invoke(getClass().getClassLoader(), executor);

        assertThat(completed.isFinished()).isTrue();
        assertThat(completed.getGeneratedActionTask()).isEmpty();
        assertThat(completed.getOutputEvents()).containsExactly(firstEvent, secondEvent);
        assertThat(context.getPendingEvents()).isEmpty();
        verify(executor).executePythonFunction(function, triggeringEvent);
        verify(executor, times(4)).callPythonAwaitable("awaitable");
    }

    @Test
    void pythonRecoveredContinuationKeepsObservationId() throws Exception {
        Action action =
                new Action(
                        "python-action",
                        new PythonFunction("test_module", "test_action"),
                        List.of(InputEvent.EVENT_TYPE));
        PythonGeneratorActionTask task =
                new PythonGeneratorActionTask(
                        "key", new InputEvent(1L), action, 1L, "observation-id");
        PythonRunnerContextImpl context = mock(PythonRunnerContextImpl.class);
        PythonActionExecutor executor = mock(PythonActionExecutor.class);
        task.setRunnerContext(context);
        when(context.getPythonAwaitableRef()).thenReturn(null, "restarted-awaitable");
        when(executor.executePythonFunction((PythonFunction) action.getExec(), task.event))
                .thenReturn("restarted-awaitable");
        when(executor.callPythonAwaitable("restarted-awaitable")).thenReturn(false);

        ActionTask generated =
                task.invoke(getClass().getClassLoader(), executor)
                        .getGeneratedActionTask()
                        .orElseThrow();

        assertThat(generated).isInstanceOf(PythonGeneratorActionTask.class);
        assertThat(generated.getObservationId()).isEqualTo("observation-id");
    }

    @Test
    void resultAcceptsDistinctOutputEvent() {
        Event triggeringEvent = new InputEvent(1L);
        Action action = TestActions.noopAction();
        ActionTask task = new JavaActionTask("key", triggeringEvent, action, 1L);
        Event outputEvent = new Event("result");

        ActionTask.ActionTaskResult result =
                task.new ActionTaskResult(true, List.of(outputEvent), null);

        assertThat(result.getOutputEvents()).containsExactly(outputEvent);
    }

    @Test
    void resultRejectsSelfLoop() {
        Event triggeringEvent = new InputEvent(1L);
        Action action = TestActions.noopAction();
        ActionTask task = new JavaActionTask("key", triggeringEvent, action, 1L);
        Event validOutput = new Event("result");

        assertThatThrownBy(
                        () ->
                                task
                                .new ActionTaskResult(
                                        true, List.of(validOutput, triggeringEvent), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(action.getName())
                .hasMessageContaining(triggeringEvent.getId().toString());
    }
}
