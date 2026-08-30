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

import org.apache.flink.types.Row;
import org.junit.jupiter.api.Test;
import pemja.core.PythonInterpreter;
import pemja.core.object.PyObject;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PythonActionExecutorTest {

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

    /**
     * A failing thread-pool shutdown must not skip the runner-context cleanup. That cleanup
     * releases the Python context's long-term memory and resource cache, and {@code
     * PythonBridgeManager} closes the interpreter immediately behind this call, so a skipped
     * cleanup never runs at all.
     */
    @Test
    void closeCleansRunnerContextWhenThreadPoolShutdownFails() throws Exception {
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        PythonActionExecutor executor = newExecutor(interpreter);
        PyObject threadPool = mock(PyObject.class);
        PyObject runnerContext = mock(PyObject.class);
        setField(executor, "pythonAsyncThreadPool", threadPool);
        setField(executor, "pythonRunnerContext", runnerContext);
        when(interpreter.invoke("flink_runner_context.close_async_thread_pool", threadPool))
                .thenThrow(new RuntimeException("thread pool shutdown failed"));

        assertThatThrownBy(executor::close)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("thread pool shutdown failed")
                // A lone failure arrives with nothing attached to it.
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).isEmpty());

        verify(interpreter)
                .invoke("flink_runner_context.close_flink_runner_context", runnerContext);
        // The handle is released even on the failing path, so a repeated close cannot double-free.
        assertThat(executor.getPythonRunnerContext()).isNull();
    }

    /** The first failure is rethrown and the later one is attached as suppressed, never dropped. */
    @Test
    void closeReportsFirstFailureWithLaterOneSuppressed() throws Exception {
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        PythonActionExecutor executor = newExecutor(interpreter);
        PyObject threadPool = mock(PyObject.class);
        PyObject runnerContext = mock(PyObject.class);
        setField(executor, "pythonAsyncThreadPool", threadPool);
        setField(executor, "pythonRunnerContext", runnerContext);
        when(interpreter.invoke("flink_runner_context.close_async_thread_pool", threadPool))
                .thenThrow(new RuntimeException("thread pool shutdown failed"));
        when(interpreter.invoke("flink_runner_context.close_flink_runner_context", runnerContext))
                .thenThrow(new RuntimeException("runner context cleanup failed"));

        assertThatThrownBy(executor::close)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("thread pool shutdown failed")
                .satisfies(
                        thrown ->
                                assertThat(thrown.getSuppressed())
                                        .extracting(Throwable::getMessage)
                                        .containsExactly("runner context cleanup failed"));
    }

    /**
     * Pins the handler to {@code Throwable}: a non-{@code Exception} failure from the thread-pool
     * shutdown must still release the runner context, and must reach the caller unwrapped.
     */
    @Test
    void closeCleansRunnerContextWhenThreadPoolShutdownThrowsError() throws Exception {
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        PythonActionExecutor executor = newExecutor(interpreter);
        PyObject threadPool = mock(PyObject.class);
        PyObject runnerContext = mock(PyObject.class);
        setField(executor, "pythonAsyncThreadPool", threadPool);
        setField(executor, "pythonRunnerContext", runnerContext);
        OutOfMemoryError failure = new OutOfMemoryError("thread pool shutdown failed");
        when(interpreter.invoke("flink_runner_context.close_async_thread_pool", threadPool))
                .thenThrow(failure);

        assertThatThrownBy(executor::close).isSameAs(failure);

        verify(interpreter)
                .invoke("flink_runner_context.close_flink_runner_context", runnerContext);
    }

    private static void setField(PythonActionExecutor executor, String name, Object value)
            throws Exception {
        Field field = PythonActionExecutor.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(executor, value);
    }

    private static PythonActionExecutor newExecutor(PythonInterpreter interpreter)
            throws Exception {
        return new PythonActionExecutor(interpreter, null, null, null, "test-job");
    }
}
