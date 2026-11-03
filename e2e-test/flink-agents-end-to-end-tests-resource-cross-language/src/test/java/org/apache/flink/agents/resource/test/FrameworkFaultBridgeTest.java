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

package org.apache.flink.agents.resource.test;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.apache.flink.agents.api.event.ToolResponseEvent;
import org.apache.flink.agents.api.function.PythonFunction;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.agents.plan.exceptions.UncapturableException;
import org.apache.flink.agents.plan.resource.python.PythonObjectScope;
import org.apache.flink.agents.runtime.CompileUtils;
import org.apache.flink.agents.runtime.python.utils.PythonInterpreterManager;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.apache.flink.util.ExceptionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pemja.core.PythonInterpreter;
import pemja.core.PythonInterpreterConfig;
import pemja.core.object.PyObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Uses real Pemja conversions, including resumed coroutines and separate callback workers. */
@Timeout(60)
public class FrameworkFaultBridgeTest {
    private static final String FIXTURES = "flink_agents.runtime.tests.framework_fault_bridge";
    private static final Object[] ORDINARY_VALUE = {false, null, "Type", "message", "trace"};
    private PythonInterpreterManager manager;
    private PythonInterpreter interpreter;
    private Path projectRoot;

    @BeforeEach
    void open() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null
                && !Files.exists(root.resolve("python/flink_agents/plan/exceptions.py"))) {
            root = root.getParent();
        }
        if (root == null) throw new IllegalStateException("Cannot locate Python fixtures");
        projectRoot = root;
        PythonInterpreterConfig config =
                PythonInterpreterConfig.newBuilder()
                        .setExcType(PythonInterpreterConfig.ExecType.MULTI_THREAD)
                        .setPythonExec(System.getenv().getOrDefault("PYTHON_EXECUTABLE", "python3"))
                        .addPythonPaths(root.resolve("python").toString())
                        .build();
        interpreter = new PythonInterpreter(config);
        manager = new PythonInterpreterManager(interpreter, () -> new PythonInterpreter(config));
    }

    @AfterEach
    void close() throws Exception {
        if (manager != null) manager.close();
    }

    private Object call(String name, Object... args) {
        return manager.invoke("function.call_python_function", FIXTURES, name, args);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @EnabledForJreRange(min = JRE.JAVA_21)
    void pythonChildFailureKeepsItsClassificationThroughTheOperator(boolean framework)
            throws Exception {
        Configuration config = new Configuration();
        config.setString("restart-strategy.type", "disable");
        config.setString(
                "python.executable", System.getenv().getOrDefault("PYTHON_EXECUTABLE", "python3"));
        config.setString(
                "python.pythonpath",
                projectRoot.resolve("python")
                        + File.pathSeparator
                        + System.getenv().getOrDefault("PYTHONPATH", ""));
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(config);
        env.setParallelism(1);
        Agent child =
                new Agent()
                        .addAction(
                                "child",
                                new String[] {InputEvent.EVENT_TYPE},
                                PythonFunction.of(FIXTURES, "child_action"),
                                null);
        Agent parent = new Agent();
        parent.addResource("child", ResourceType.AGENT, child);
        parent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                FrameworkFaultBridgeTest.class.getMethod(
                        "requestChild", Event.class, RunnerContext.class));
        parent.addAction(
                new String[] {ToolResponseEvent.EVENT_TYPE},
                FrameworkFaultBridgeTest.class.getMethod(
                        "receiveChild", Event.class, RunnerContext.class));
        AgentConfiguration agentConfig = new AgentConfiguration();
        AgentPlan compiled = new AgentPlan(parent, agentConfig);
        Map<String, Action> actions = new LinkedHashMap<>(compiled.getActions());
        // This test drives tool requests directly, without an LLM's chat continuation.
        actions.remove("chat_model_action");
        AgentPlan plan = new AgentPlan(actions, compiled.getResourceProviders(), agentConfig);
        List<Object> outputs = new ArrayList<>();
        try (CloseableIterator<Object> results =
                CompileUtils.connectToAgent(env.fromData(framework), value -> 0L, plan)
                        .collectAsync()) {
            if (framework) {
                Throwable failure = catchThrowable(env::execute);
                assertThat(failure)
                        .hasStackTraceContaining("Python child framework fault")
                        .hasStackTraceContaining("child state store unavailable");
                assertThat(UncapturableException.isUncapturable(failure)).isTrue();
            } else {
                env.execute();
                results.forEachRemaining(outputs::add);
                // receiveChild checks the failed result; diagnostic text is not a public contract.
                assertThat(outputs).singleElement().asString().isNotBlank();
            }
        }
    }

    public static void requestChild(Event event, RunnerContext ctx) {
        ctx.sendEvent(
                new ToolRequestEvent(
                        "model",
                        List.of(
                                new ToolCallBlock(
                                        "child-call",
                                        "_subagent_child",
                                        Map.of(
                                                "framework",
                                                InputEvent.fromEvent(event).getInput())))));
    }

    public static void receiveChild(Event event, RunnerContext ctx) {
        ToolResponseEvent response = ToolResponseEvent.fromEvent(event);
        if (!Boolean.FALSE.equals(response.getSuccess().get("child-call"))) {
            throw new IllegalStateException("Expected an ordinary child failure");
        }
        ctx.sendEvent(new OutputEvent(response.getError().get("child-call")));
    }

    @Test
    void interleavedRawAndGuardedCallsKeepTheDispatcherAlive() {
        for (int i = 0; i < 3; i++) {
            assertThat(call("ordinary_value")).isEqualTo(ORDINARY_VALUE);
            interpreter.invoke(
                    "function.call_python_function", FIXTURES, "ordinary_value", new Object[0]);
            assertThat(call("ordinary_value")).isEqualTo(ORDINARY_VALUE);
        }
    }

    @Test
    void javaFaultKeepsItsOriginalIdentityAndCause() {
        FaultSource source = new FaultSource("state write failed");
        for (int i = 0; i < 3; i++) {
            Throwable failure = catchThrowable(() -> call("raise_java", source));
            assertThat(failure).isSameAs(source.fault);
            assertThat(failure.getCause()).isSameAs(source.cause);
        }
    }

    @Test
    void pythonWrapperKeepsTheJavaCauseAndPythonContext() {
        FaultSource source = new FaultSource("storage unavailable");
        Throwable failure = catchThrowable(() -> call("wrap_java", source));
        assertThat(failure)
                .isInstanceOf(UncapturableException.class)
                .hasStackTraceContaining("python wrapper");
        assertThat(ExceptionUtils.findThrowable(failure, IOException.class).orElse(null))
                .isSameAs(source.cause);
    }

    @Test
    void coroutineFaultSurvivesYieldAndTemporaryHandleClose() {
        FaultSource source = new FaultSource("failure after resume");
        try (PythonObjectScope scope = new PythonObjectScope()) {
            PyObject created = scope.own((PyObject) call("async_java_fault", source));
            manager.set("fault_coroutine", created);
        }
        try {
            try (PythonObjectScope scope = new PythonObjectScope()) {
                PyObject resumed = scope.own((PyObject) manager.get("fault_coroutine"));
                Object[] step =
                        (Object[])
                                scope.own(
                                        manager.invoke("function.call_python_awaitable", resumed));
                assertThat(step[0]).isEqualTo(false);
            }
            try (PythonObjectScope scope = new PythonObjectScope()) {
                PyObject resumed = scope.own((PyObject) manager.get("fault_coroutine"));
                assertThat(
                                catchThrowable(
                                        () ->
                                                manager.invoke(
                                                        "function.call_python_awaitable", resumed)))
                        .isSameAs(source.fault);
            }
        } finally {
            manager.exec("del fault_coroutine");
        }
    }

    @Test
    void concurrentCallsKeepTheirOwnFaults() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                FaultSource source = new FaultSource("call-" + i);
                calls.add(
                        workers.submit(
                                () -> {
                                    Throwable failure =
                                            catchThrowable(() -> call("raise_java", source));
                                    assertThat(failure).isSameAs(source.fault);
                                    assertThat(call("ordinary_value")).isEqualTo(ORDINARY_VALUE);
                                }));
            }
            for (Future<?> call : calls) call.get(30, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    public static final class FaultSource {
        private final IOException cause;
        private final UncapturableException fault;

        public FaultSource(String message) {
            cause = new IOException(message);
            fault = UncapturableException.wrap(cause);
        }

        public void fail() {
            throw fault;
        }
    }
}
