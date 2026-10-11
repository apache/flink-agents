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
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.resource.PythonResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.skills.Skills;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.resourceprovider.PythonResourceProvider;
import org.apache.flink.agents.plan.resourceprovider.ResourceProvider;
import org.apache.flink.agents.plan.subagent.InternalSubagentProvider;
import org.apache.flink.agents.runtime.env.PythonEnvironmentManager;
import org.apache.flink.agents.runtime.memory.Mem0LongTermMemory;
import org.apache.flink.agents.runtime.python.utils.PythonActionExecutor;
import org.apache.flink.agents.runtime.python.utils.PythonInterpreterManager;
import org.apache.flink.agents.runtime.python.utils.PythonResourceAdapterImpl;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.JobID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;

/** Contract tests for {@link PythonBridgeManager}. */
class PythonBridgeManagerTest {

    @Test
    void packageSourcesRequirePythonEvenWithOnlyJavaProviders() throws Exception {
        Agent agent = new Agent();
        agent.addResource("skills", ResourceType.SKILLS, Skills.fromPackage("pkg", "skills"));
        AgentPlan plan = new AgentPlan(agent);
        assertThat(
                        plan.getResourceProviders().values().stream()
                                .flatMap(providers -> providers.values().stream())
                                .anyMatch(
                                        org.apache.flink.agents.plan.resourceprovider
                                                        .ResourceProvider
                                                ::isPythonOwned))
                .isFalse();
        assertThat(PythonBridgeManager.hasPackageSkills(plan)).isTrue();
        Agent nativeAgent = new Agent();
        nativeAgent.addResource("skills", ResourceType.SKILLS, Skills.fromClasspath("skills"));
        assertThat(PythonBridgeManager.hasPackageSkills(new AgentPlan(nativeAgent))).isFalse();
    }

    @Test
    void closeAttemptsAllResourcesAndSuppressesLaterFailures() throws Exception {
        PythonBridgeManager bridge = new PythonBridgeManager();
        Mem0LongTermMemory longTermMemory = mock(Mem0LongTermMemory.class);
        PythonActionExecutor actionExecutor = mock(PythonActionExecutor.class);
        PythonResourceAdapterImpl resourceAdapter = mock(PythonResourceAdapterImpl.class);
        PythonInterpreterManager interpreterManager = mock(PythonInterpreterManager.class);
        PythonEnvironmentManager environmentManager = mock(PythonEnvironmentManager.class);
        RuntimeException actionExecutorFailure =
                new RuntimeException("action executor close failed");
        RuntimeException interpreterFailure = new RuntimeException("interpreter close failed");
        RuntimeException environmentFailure = new RuntimeException("environment close failed");

        doThrow(actionExecutorFailure).when(actionExecutor).close();
        RuntimeException resourceAdapterFailure =
                new RuntimeException("resource adapter close failed");
        doThrow(resourceAdapterFailure).when(resourceAdapter).close();
        doThrow(interpreterFailure).when(interpreterManager).close();
        doThrow(environmentFailure).when(environmentManager).close();
        setField(bridge, "longTermMemory", longTermMemory);
        setField(bridge, "pythonActionExecutor", actionExecutor);
        setField(bridge, "pythonResourceAdapter", resourceAdapter);
        setField(bridge, "pythonInterpreterManager", interpreterManager);
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
                        interpreterManager,
                        environmentManager);
        closeOrder.verify(longTermMemory).close();
        closeOrder.verify(actionExecutor).close();
        closeOrder.verify(resourceAdapter).close();
        closeOrder.verify(interpreterManager).close();
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
     * {@code open()} decides whether to start the shared Python runtime by inspecting plans, and it
     * runs before sub-agent setups exist, so the inspection must walk the whole plan tree. An
     * internal sub-agent's child plan is reachable only through {@link
     * InternalSubagentProvider#getChildPlan()}; a Java root with a Python-bearing descendant still
     * has to initialize Python, or the descendant's eager materialization fails its "no Python
     * runtime" guard. Pins the recursion through two nested layers and both detection paths.
     */
    @Test
    void planTreeRecursesThroughNestedInternalSubagentChildPlans() throws Exception {
        // Deepest layer: a Python action and a Python-owned resource, neither visible from the
        // root.
        Action pythonAction =
                new Action(
                        "pyAction",
                        new PythonFunction("test_module", "py_action"),
                        List.of(InputEvent.EVENT_TYPE));
        PythonResourceProvider pythonModel =
                new PythonResourceProvider(
                        "pyModel",
                        ResourceType.CHAT_MODEL,
                        new PythonResourceDescriptor("test.module", "PyModel", Map.of()));
        Map<ResourceType, Map<String, ResourceProvider>> grandchildProviders = new HashMap<>();
        grandchildProviders.put(
                ResourceType.CHAT_MODEL, new HashMap<>(Map.of("pyModel", pythonModel)));
        AgentPlan grandchild =
                new AgentPlan(Map.of(pythonAction.getName(), pythonAction), grandchildProviders);

        // Middle layer: Java-only, wraps the grandchild as an internal sub-agent.
        InternalSubagentProvider grandchildProvider =
                new InternalSubagentProvider("grandchild", grandchild);
        Map<ResourceType, Map<String, ResourceProvider>> childProviders = new HashMap<>();
        childProviders.put(
                ResourceType.AGENT, new HashMap<>(Map.of("grandchild", grandchildProvider)));
        AgentPlan child = new AgentPlan(Map.of(), childProviders);

        // Root: one Java action, wraps the child as an internal sub-agent.
        Action javaAction = TestActions.noopAction();
        InternalSubagentProvider childProvider = new InternalSubagentProvider("child", child);
        Map<ResourceType, Map<String, ResourceProvider>> rootProviders = new HashMap<>();
        rootProviders.put(ResourceType.AGENT, new HashMap<>(Map.of("child", childProvider)));
        AgentPlan root = new AgentPlan(Map.of(javaAction.getName(), javaAction), rootProviders);

        // Depth-first pre-order: the root, then each descendant through its sub-agent provider.
        assertThat(PythonBridgeManager.planTree(root)).containsExactly(root, child, grandchild);
        // The root alone is Java-only; both Python signals live two layers down and must surface.
        assertThat(PythonBridgeManager.treeContainsPythonResource(root)).isTrue();
        assertThat(PythonBridgeManager.treeContainsPythonAction(root)).isTrue();
    }

    /**
     * Complements {@link #planTreeRecursesThroughNestedInternalSubagentChildPlans}: that test pins
     * the detection helpers in isolation, this one pins that {@code open()} actually consumes their
     * whole-tree result. The root is Java-only and the sole Python signal sits in an internal
     * sub-agent's child plan, so a root-only scan would classify the root as Python-free, take the
     * no-op branch, and never build the Python environment. Stub the environment manager to
     * short-circuit at the bootstrap instead of launching a real Pemja runtime; constructing it at
     * all proves {@code open()} entered the initialization branch on the strength of the child
     * plan.
     */
    @Test
    void openStartsPythonWhenOnlyASubagentChildPlanBearsPython() throws Exception {
        // Child plan carries the only Python signal: a Python-owned resource.
        PythonResourceProvider pythonModel =
                new PythonResourceProvider(
                        "pyModel",
                        ResourceType.CHAT_MODEL,
                        new PythonResourceDescriptor("test.module", "PyModel", Map.of()));
        Map<ResourceType, Map<String, ResourceProvider>> childProviders = new HashMap<>();
        childProviders.put(ResourceType.CHAT_MODEL, new HashMap<>(Map.of("pyModel", pythonModel)));
        AgentPlan child = new AgentPlan(Map.of(), childProviders);

        // Root is Java-only and reaches that child solely through an internal sub-agent provider.
        Action javaAction = TestActions.noopAction();
        InternalSubagentProvider childProvider = new InternalSubagentProvider("child", child);
        Map<ResourceType, Map<String, ResourceProvider>> rootProviders = new HashMap<>();
        rootProviders.put(ResourceType.AGENT, new HashMap<>(Map.of("child", childProvider)));
        AgentPlan root = new AgentPlan(Map.of(javaAction.getName(), javaAction), rootProviders);

        try (MockedConstruction<PythonEnvironmentManager> envManagers =
                mockConstruction(
                        PythonEnvironmentManager.class,
                        (mock, context) ->
                                doThrow(new IllegalStateException("python-bootstrap-reached"))
                                        .when(mock)
                                        .open())) {
            try (PythonBridgeManager bridge = new PythonBridgeManager()) {
                assertThatThrownBy(
                                () ->
                                        bridge.open(
                                                root,
                                                /* resourceCache */ null,
                                                new ExecutionConfig(),
                                                /* distributedCache */ null,
                                                /* tmpDirs */
                                                new String[] {System.getProperty("java.io.tmpdir")},
                                                /* jobId */ new JobID(),
                                                /* metricGroup */ null,
                                                /* mailboxThreadChecker */ () -> {},
                                                /* jobIdentifier */ "job-1",
                                                /* userCodeClassLoader */
                                                Thread.currentThread().getContextClassLoader()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("python-bootstrap-reached");
            }
            // A root-only scan sees a Java-only root, takes the no-op branch, and never constructs
            // the environment manager; the whole-tree scan finds the child's Python resource.
            assertThat(envManagers.constructed()).hasSize(1);
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
        PythonInterpreterManager interpreterManager = mock(PythonInterpreterManager.class);
        PythonEnvironmentManager environmentManager = mock(PythonEnvironmentManager.class);
        doThrow(new IllegalStateException("action executor close failed"))
                .when(actionExecutor)
                .close();

        setField(bridge, "pythonActionExecutor", actionExecutor);
        setField(bridge, "pythonInterpreterManager", interpreterManager);
        setField(bridge, "pythonEnvironmentManager", environmentManager);

        assertThatThrownBy(bridge::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("action executor close failed")
                // Contract 3: a lone failure arrives with nothing attached to it.
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).isEmpty());

        InOrder inOrder = inOrder(actionExecutor, interpreterManager, environmentManager);
        inOrder.verify(actionExecutor).close();
        inOrder.verify(interpreterManager).close();
        inOrder.verify(environmentManager).close();
    }

    /** The first failure is rethrown and any later one is attached as suppressed, never dropped. */
    @Test
    void closeReportsFirstFailureWithLaterOnesSuppressed() throws Exception {
        PythonBridgeManager bridge = new PythonBridgeManager();
        PythonActionExecutor actionExecutor = mock(PythonActionExecutor.class);
        PythonInterpreterManager interpreterManager = mock(PythonInterpreterManager.class);
        PythonEnvironmentManager environmentManager = mock(PythonEnvironmentManager.class);
        doThrow(new IllegalStateException("action executor close failed"))
                .when(actionExecutor)
                .close();
        doThrow(new IllegalStateException("environment manager close failed"))
                .when(environmentManager)
                .close();

        setField(bridge, "pythonActionExecutor", actionExecutor);
        setField(bridge, "pythonInterpreterManager", interpreterManager);
        setField(bridge, "pythonEnvironmentManager", environmentManager);

        assertThatThrownBy(bridge::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("action executor close failed")
                .satisfies(
                        thrown ->
                                assertThat(thrown.getSuppressed())
                                        .extracting(Throwable::getMessage)
                                        .containsExactly("environment manager close failed"));

        verify(interpreterManager).close();
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
        PythonInterpreterManager interpreterManager = mock(PythonInterpreterManager.class);
        PythonEnvironmentManager environmentManager = mock(PythonEnvironmentManager.class);
        OutOfMemoryError failure = new OutOfMemoryError("action executor close failed");
        doThrow(failure).when(actionExecutor).close();

        setField(bridge, "pythonActionExecutor", actionExecutor);
        setField(bridge, "pythonInterpreterManager", interpreterManager);
        setField(bridge, "pythonEnvironmentManager", environmentManager);

        // The Error reaches the caller unchanged rather than wrapped in an Exception, and with
        // nothing attached to it.
        assertThatThrownBy(bridge::close)
                .isSameAs(failure)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).isEmpty());

        verify(interpreterManager).close();
        verify(environmentManager).close();
    }

    private static void setField(PythonBridgeManager bridge, String name, Object value)
            throws Exception {
        Field field = PythonBridgeManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(bridge, value);
    }
}
