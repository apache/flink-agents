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

import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.runtime.env.PythonEnvironmentManager;
import org.apache.flink.agents.runtime.memory.Mem0LongTermMemory;
import org.apache.flink.agents.runtime.python.utils.PythonActionExecutor;
import org.apache.flink.agents.runtime.python.utils.PythonResourceAdapterImpl;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.JobID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import pemja.core.PythonInterpreter;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Contract tests for {@link PythonBridgeManager}. */
class PythonBridgeManagerTest {

    @Test
    void closeAttemptsAllResourcesAndSuppressesLaterFailures() throws Exception {
        PythonBridgeManager bridge = new PythonBridgeManager();
        Mem0LongTermMemory longTermMemory = mock(Mem0LongTermMemory.class);
        PythonActionExecutor actionExecutor = mock(PythonActionExecutor.class);
        PythonResourceAdapterImpl resourceAdapter = mock(PythonResourceAdapterImpl.class);
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        PythonEnvironmentManager environmentManager = mock(PythonEnvironmentManager.class);
        RuntimeException actionExecutorFailure =
                new RuntimeException("action executor close failed");
        RuntimeException interpreterFailure = new RuntimeException("interpreter close failed");
        RuntimeException environmentFailure = new RuntimeException("environment close failed");

        doThrow(actionExecutorFailure).when(actionExecutor).close();
        RuntimeException resourceAdapterFailure =
                new RuntimeException("resource adapter close failed");
        doThrow(resourceAdapterFailure).when(resourceAdapter).close();
        doThrow(interpreterFailure).when(interpreter).close();
        doThrow(environmentFailure).when(environmentManager).close();
        setField(bridge, "longTermMemory", longTermMemory);
        setField(bridge, "pythonActionExecutor", actionExecutor);
        setField(bridge, "pythonResourceAdapter", resourceAdapter);
        setField(bridge, "pythonInterpreter", interpreter);
        setField(bridge, "pythonEnvironmentManager", environmentManager);

        assertThatThrownBy(bridge::close)
                .isSameAs(actionExecutorFailure)
                .hasSuppressedException(resourceAdapterFailure)
                .hasSuppressedException(interpreterFailure)
                .hasSuppressedException(environmentFailure);
        InOrder closeOrder =
                inOrder(
                        longTermMemory,
                        actionExecutor,
                        resourceAdapter,
                        interpreter,
                        environmentManager);
        closeOrder.verify(longTermMemory).close();
        closeOrder.verify(actionExecutor).close();
        closeOrder.verify(resourceAdapter).close();
        closeOrder.verify(interpreter).close();
        closeOrder.verify(environmentManager).close();
    }

    @Test
    void openIsNoOpWhenPlanHasNeitherPythonActionsNorResources() throws Exception {
        // Java-only plan: one Java action, no resources.
        Action javaAction = TestActions.noopAction();
        Map<String, Action> actions = Map.of(javaAction.getName(), javaAction);
        Map<String, List<Action>> byEvent = Map.of(InputEvent.EVENT_TYPE, List.of(javaAction));
        AgentPlan plan = new AgentPlan(actions);

        try (PythonBridgeManager bridge = new PythonBridgeManager()) {
            bridge.open(
                    plan,
                    /* resourceCache */ null,
                    new ExecutionConfig(),
                    /* distributedCache */ null,
                    /* tmpDirs */ new String[] {System.getProperty("java.io.tmpdir")},
                    /* jobId */ new JobID(),
                    /* metricGroup */ null,
                    /* mailboxThreadChecker */ () -> {},
                    /* jobIdentifier */ "job-1",
                    /* userCodeClassLoader */ Thread.currentThread().getContextClassLoader());

            // No-op contract: nothing initialized, no Pemja interpreter created.
            assertThat(bridge.isInitialized()).isFalse();
            assertThat(bridge.getPythonActionExecutor()).isNull();
            assertThat(bridge.getPythonRunnerContext()).isNull();
        }
    }

    /**
     * A failing action executor must not strand the interpreter or the environment manager: both
     * hold native Python state that leaks for the lifetime of the TaskManager if never closed.
     *
     * <p>Also pins the close order documented on the class, which is load-bearing rather than
     * incidental: {@link PythonActionExecutor#close()} calls back into the interpreter, so it has
     * to run before the interpreter is closed.
     */
    @Test
    void closeReleasesInterpreterAndEnvironmentWhenActionExecutorFails() throws Exception {
        PythonBridgeManager bridge = new PythonBridgeManager();
        PythonActionExecutor actionExecutor = mock(PythonActionExecutor.class);
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        PythonEnvironmentManager environmentManager = mock(PythonEnvironmentManager.class);
        doThrow(new IllegalStateException("action executor close failed"))
                .when(actionExecutor)
                .close();

        setField(bridge, "pythonActionExecutor", actionExecutor);
        setField(bridge, "pythonInterpreter", interpreter);
        setField(bridge, "pythonEnvironmentManager", environmentManager);

        assertThatThrownBy(bridge::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("action executor close failed")
                // Contract 3: a lone failure arrives with nothing attached to it.
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).isEmpty());

        InOrder inOrder = inOrder(actionExecutor, interpreter, environmentManager);
        inOrder.verify(actionExecutor).close();
        inOrder.verify(interpreter).close();
        inOrder.verify(environmentManager).close();
    }

    /** The first failure is rethrown and any later one is attached as suppressed, never dropped. */
    @Test
    void closeReportsFirstFailureWithLaterOnesSuppressed() throws Exception {
        PythonBridgeManager bridge = new PythonBridgeManager();
        PythonActionExecutor actionExecutor = mock(PythonActionExecutor.class);
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        PythonEnvironmentManager environmentManager = mock(PythonEnvironmentManager.class);
        doThrow(new IllegalStateException("action executor close failed"))
                .when(actionExecutor)
                .close();
        doThrow(new IllegalStateException("environment manager close failed"))
                .when(environmentManager)
                .close();

        setField(bridge, "pythonActionExecutor", actionExecutor);
        setField(bridge, "pythonInterpreter", interpreter);
        setField(bridge, "pythonEnvironmentManager", environmentManager);

        assertThatThrownBy(bridge::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("action executor close failed")
                .satisfies(
                        thrown ->
                                assertThat(thrown.getSuppressed())
                                        .extracting(Throwable::getMessage)
                                        .containsExactly("environment manager close failed"));

        verify(interpreter).close();
    }

    /**
     * Pins the handler to {@code Throwable}. A non-{@code Exception} failure from the action
     * executor must still release the native Python state; a {@code catch (Exception)} ladder or
     * {@code IOUtils.closeAll} would stop here and leak it.
     */
    @Test
    void closeReleasesInterpreterAndEnvironmentWhenActionExecutorThrowsError() throws Exception {
        PythonBridgeManager bridge = new PythonBridgeManager();
        PythonActionExecutor actionExecutor = mock(PythonActionExecutor.class);
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        PythonEnvironmentManager environmentManager = mock(PythonEnvironmentManager.class);
        OutOfMemoryError failure = new OutOfMemoryError("action executor close failed");
        doThrow(failure).when(actionExecutor).close();

        setField(bridge, "pythonActionExecutor", actionExecutor);
        setField(bridge, "pythonInterpreter", interpreter);
        setField(bridge, "pythonEnvironmentManager", environmentManager);

        // The Error reaches the caller unchanged rather than wrapped in an Exception, and with
        // nothing attached to it.
        assertThatThrownBy(bridge::close)
                .isSameAs(failure)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).isEmpty());

        verify(interpreter).close();
        verify(environmentManager).close();
    }

    private static void setField(PythonBridgeManager bridge, String name, Object value)
            throws Exception {
        Field field = PythonBridgeManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(bridge, value);
    }
}
