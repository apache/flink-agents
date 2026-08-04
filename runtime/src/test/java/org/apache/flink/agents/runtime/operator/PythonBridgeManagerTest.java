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
        setPrivateField(bridge, "longTermMemory", longTermMemory);
        setPrivateField(bridge, "pythonActionExecutor", actionExecutor);
        setPrivateField(bridge, "pythonResourceAdapter", resourceAdapter);
        setPrivateField(bridge, "pythonInterpreter", interpreter);
        setPrivateField(bridge, "pythonEnvironmentManager", environmentManager);

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

    private static void setPrivateField(Object target, String fieldName, Object value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
