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
package org.apache.flink.agents.runtime.python.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.python.context.PythonRunnerContextImpl;
import org.apache.flink.types.Row;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import pemja.core.PythonInterpreter;
import pemja.core.object.PyObject;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Tests for {@link PythonActionExecutor}. */
class PythonActionExecutorTest {

    private static final String CREATE_ASYNC_THREAD_POOL =
            "flink_runner_context.create_async_thread_pool";
    private static final String CLOSE_ASYNC_THREAD_POOL =
            "flink_runner_context.close_async_thread_pool";
    private static final String CREATE_FLINK_RUNNER_CONTEXT =
            "flink_runner_context.create_flink_runner_context";
    private static final String CLOSE_FLINK_RUNNER_CONTEXT =
            "flink_runner_context.close_flink_runner_context";

    @Test
    void resolvesPickledPythonKeyTextFromPyFlinkKeyRow() throws Exception {
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        PythonActionExecutor executor = newExecutor(interpreter);
        byte[] pickledKey = new byte[] {1, 2, 3};
        when(interpreter.invoke(
                        "python_java_utils.convert_to_python_key_text", pickledKey, "pickled"))
                .thenReturn("7");

        assertThat(executor.resolveKeyText(Row.of(pickledKey), true)).isEqualTo("7");
        verify(interpreter)
                .invoke("python_java_utils.convert_to_python_key_text", pickledKey, "pickled");
    }

    @Test
    void resolvesExplicitPyFlinkKeyTypesWithStringValueOf() throws Exception {
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        PythonActionExecutor executor = newExecutor(interpreter);

        assertThat(executor.resolveKeyText(Row.of(7L), false)).isEqualTo("7");
        assertThat(executor.resolveKeyText(Row.of(42), false)).isEqualTo("42");
    }

    @Test
    void resolvesExplicitByteArrayWithoutUnpickling() throws Exception {
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        PythonActionExecutor executor = newExecutor(interpreter);
        byte[] firstKey = new byte[] {'N', '.'};
        byte[] secondKey = new byte[] {(byte) 0x80, 0x04, 'N', '.'};
        when(interpreter.invoke(
                        "python_java_utils.convert_to_python_key_text", firstKey, "explicit"))
                .thenReturn("b'N.'");
        when(interpreter.invoke(
                        "python_java_utils.convert_to_python_key_text", secondKey, "explicit"))
                .thenReturn("b'\\x80\\x04N.'");

        assertThat(executor.resolveKeyText(Row.of(firstKey), false)).isEqualTo("b'N.'");
        assertThat(executor.resolveKeyText(Row.of(secondKey), false)).isEqualTo("b'\\x80\\x04N.'");
        verify(interpreter)
                .invoke("python_java_utils.convert_to_python_key_text", firstKey, "explicit");
        verify(interpreter)
                .invoke("python_java_utils.convert_to_python_key_text", secondKey, "explicit");
    }

    @Test
    void propagatesDecodeFailure() throws Exception {
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        PythonActionExecutor executor = newExecutor(interpreter);
        byte[] malformedKey = new byte[] {2};
        when(interpreter.invoke(
                        "python_java_utils.convert_to_python_key_text", malformedKey, "pickled"))
                .thenThrow(new RuntimeException("bad pickle"));

        assertThatThrownBy(() -> executor.resolveKeyText(Row.of(malformedKey), true))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("bad pickle");
    }

    @Test
    void releasesPythonObjectsAfterLogicalCleanup() throws Exception {
        TestFixture fixture = createOpenedExecutor();
        clearInvocations(fixture.interpreter, fixture.asyncThreadPool, fixture.runnerContextObject);

        fixture.executor.close();

        InOrder closeOrder =
                inOrder(fixture.interpreter, fixture.asyncThreadPool, fixture.runnerContextObject);
        closeOrder
                .verify(fixture.interpreter)
                .invoke(CLOSE_ASYNC_THREAD_POOL, fixture.asyncThreadPool);
        closeOrder.verify(fixture.asyncThreadPool).close();
        closeOrder
                .verify(fixture.interpreter)
                .invoke(CLOSE_FLINK_RUNNER_CONTEXT, fixture.runnerContextObject);
        closeOrder.verify(fixture.runnerContextObject).close();
    }

    @Test
    void releasesBothPythonObjectsWhenLogicalCleanupFails() throws Exception {
        TestFixture fixture = createOpenedExecutor();
        RuntimeException asyncFailure = new RuntimeException("async cleanup failed");
        RuntimeException contextFailure = new RuntimeException("context cleanup failed");
        doThrow(asyncFailure)
                .when(fixture.interpreter)
                .invoke(CLOSE_ASYNC_THREAD_POOL, fixture.asyncThreadPool);
        doThrow(contextFailure)
                .when(fixture.interpreter)
                .invoke(CLOSE_FLINK_RUNNER_CONTEXT, fixture.runnerContextObject);

        assertThatThrownBy(fixture.executor::close)
                .isSameAs(asyncFailure)
                .hasSuppressedException(contextFailure);
        InOrder closeOrder =
                inOrder(fixture.interpreter, fixture.asyncThreadPool, fixture.runnerContextObject);
        closeOrder
                .verify(fixture.interpreter)
                .invoke(CLOSE_ASYNC_THREAD_POOL, fixture.asyncThreadPool);
        closeOrder.verify(fixture.asyncThreadPool).close();
        closeOrder
                .verify(fixture.interpreter)
                .invoke(CLOSE_FLINK_RUNNER_CONTEXT, fixture.runnerContextObject);
        closeOrder.verify(fixture.runnerContextObject).close();

        clearInvocations(fixture.interpreter, fixture.asyncThreadPool, fixture.runnerContextObject);
        fixture.executor.close();
        verifyNoInteractions(
                fixture.interpreter, fixture.asyncThreadPool, fixture.runnerContextObject);
    }

    private static PythonActionExecutor newExecutor(PythonInterpreter interpreter)
            throws Exception {
        return new PythonActionExecutor(interpreter, null, null, null, "test-job");
    }

    private static TestFixture createOpenedExecutor() throws Exception {
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        PythonRunnerContextImpl runnerContext = mock(PythonRunnerContextImpl.class);
        JavaResourceAdapter resourceAdapter = mock(JavaResourceAdapter.class);
        PyObject asyncThreadPool = mock(PyObject.class);
        PyObject runnerContextObject = mock(PyObject.class);
        AgentPlan plan = new AgentPlan(new HashMap<>(), new HashMap<>());
        String planJson = new ObjectMapper().writeValueAsString(plan);
        String jobIdentifier = "job-1";

        when(interpreter.invoke(
                        CREATE_ASYNC_THREAD_POOL,
                        plan.getConfig().get(AgentExecutionOptions.NUM_ASYNC_THREADS)))
                .thenReturn(asyncThreadPool);
        when(interpreter.invoke(
                        CREATE_FLINK_RUNNER_CONTEXT,
                        runnerContext,
                        planJson,
                        asyncThreadPool,
                        resourceAdapter,
                        jobIdentifier))
                .thenReturn(runnerContextObject);

        PythonActionExecutor executor =
                new PythonActionExecutor(
                        interpreter, plan, resourceAdapter, runnerContext, jobIdentifier);
        executor.open();
        return new TestFixture(interpreter, asyncThreadPool, runnerContextObject, executor);
    }

    private static final class TestFixture {
        private final PythonInterpreter interpreter;
        private final PyObject asyncThreadPool;
        private final PyObject runnerContextObject;
        private final PythonActionExecutor executor;

        private TestFixture(
                PythonInterpreter interpreter,
                PyObject asyncThreadPool,
                PyObject runnerContextObject,
                PythonActionExecutor executor) {
            this.interpreter = interpreter;
            this.asyncThreadPool = asyncThreadPool;
            this.runnerContextObject = runnerContextObject;
            this.executor = executor;
        }
    }
}
