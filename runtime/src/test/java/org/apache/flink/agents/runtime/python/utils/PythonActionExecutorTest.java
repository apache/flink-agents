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

import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.plan.PythonFunction;
import org.apache.flink.agents.runtime.python.context.PythonRunnerContextImpl;
import org.apache.flink.types.Row;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import pemja.core.PythonInterpreter;
import pemja.core.object.PyObject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PythonActionExecutorTest {

    private static final String CONVERT_JSON_TO_PYTHON_EVENT =
            "python_java_utils.convert_json_to_python_event";
    private static final String CALL_PYTHON_AWAITABLE = "function.call_python_awaitable";

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
    void closesPythonEventAfterSynchronousAction() throws Exception {
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        PythonRunnerContextImpl runnerContext = mock(PythonRunnerContextImpl.class);
        PythonActionExecutor executor = newExecutor(interpreter, runnerContext);
        PythonFunction function = mock(PythonFunction.class);
        PyObject pythonEvent = mock(PyObject.class);
        when(interpreter.invoke(same(CONVERT_JSON_TO_PYTHON_EVENT), anyString()))
                .thenReturn(pythonEvent);
        when(function.call(same(pythonEvent), isNull())).thenReturn(null);

        assertThat(executor.executePythonFunction(function, new InputEvent(1L))).isNull();

        verify(function).setInterpreter(interpreter);
        verify(pythonEvent).close();
    }

    @Test
    void closesTemporaryWrappersAfterStoringAwaitable() throws Exception {
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        PythonRunnerContextImpl runnerContext = mock(PythonRunnerContextImpl.class);
        PythonActionExecutor executor = newExecutor(interpreter, runnerContext);
        PythonFunction function = mock(PythonFunction.class);
        PyObject pythonEvent = mock(PyObject.class);
        PyObject pythonAwaitable = mock(PyObject.class);
        when(interpreter.invoke(same(CONVERT_JSON_TO_PYTHON_EVENT), anyString()))
                .thenReturn(pythonEvent);
        when(function.call(same(pythonEvent), isNull())).thenReturn(pythonAwaitable);

        String pythonAwaitableRef = executor.executePythonFunction(function, new InputEvent(1L));

        ArgumentCaptor<String> refCaptor = ArgumentCaptor.forClass(String.class);
        verify(interpreter).set(refCaptor.capture(), same(pythonAwaitable));
        assertThat(pythonAwaitableRef)
                .isEqualTo(refCaptor.getValue())
                .startsWith("python_awaitable_");
        InOrder closeOrder = inOrder(interpreter, pythonAwaitable, pythonEvent);
        closeOrder.verify(interpreter).set(pythonAwaitableRef, pythonAwaitable);
        closeOrder.verify(pythonAwaitable).close();
        closeOrder.verify(pythonEvent).close();
    }

    @Test
    void closesPythonEventWhenActionFails() throws Exception {
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        PythonRunnerContextImpl runnerContext = mock(PythonRunnerContextImpl.class);
        PythonActionExecutor executor = newExecutor(interpreter, runnerContext);
        PythonFunction function = mock(PythonFunction.class);
        PyObject pythonEvent = mock(PyObject.class);
        RuntimeException failure = new RuntimeException("action failed");
        when(interpreter.invoke(same(CONVERT_JSON_TO_PYTHON_EVENT), anyString()))
                .thenReturn(pythonEvent);
        when(function.call(same(pythonEvent), isNull())).thenThrow(failure);

        assertThatThrownBy(() -> executor.executePythonFunction(function, new InputEvent(1L)))
                .isInstanceOf(PythonActionExecutor.PythonActionExecutionException.class)
                .hasCause(failure);
        verify(pythonEvent).close();
        verify(runnerContext).drainEvents(null);
    }

    @Test
    void closesAwaitableAndEventWhenStoringAwaitableFails() throws Exception {
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        PythonRunnerContextImpl runnerContext = mock(PythonRunnerContextImpl.class);
        PythonActionExecutor executor = newExecutor(interpreter, runnerContext);
        PythonFunction function = mock(PythonFunction.class);
        PyObject pythonEvent = mock(PyObject.class);
        PyObject pythonAwaitable = mock(PyObject.class);
        RuntimeException failure = new RuntimeException("set failed");
        when(interpreter.invoke(same(CONVERT_JSON_TO_PYTHON_EVENT), anyString()))
                .thenReturn(pythonEvent);
        when(function.call(same(pythonEvent), isNull())).thenReturn(pythonAwaitable);
        doThrow(failure).when(interpreter).set(anyString(), same(pythonAwaitable));

        assertThatThrownBy(() -> executor.executePythonFunction(function, new InputEvent(1L)))
                .isInstanceOf(PythonActionExecutor.PythonActionExecutionException.class)
                .hasCause(failure);
        verify(pythonAwaitable).close();
        verify(pythonEvent).close();
    }

    @Test
    void closesRetrievedAwaitableWhileItIsPending() throws Exception {
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        PythonActionExecutor executor = newExecutor(interpreter);
        PyObject pythonAwaitable = mock(PyObject.class);
        String pythonAwaitableRef = "python_awaitable_1";
        when(interpreter.get(pythonAwaitableRef)).thenReturn(pythonAwaitable);
        when(interpreter.invoke(CALL_PYTHON_AWAITABLE, pythonAwaitable)).thenReturn(false);

        assertThat(executor.callPythonAwaitable(pythonAwaitableRef)).isFalse();

        verify(pythonAwaitable).close();
        verify(interpreter, never()).exec(anyString());
    }

    @Test
    void deletesCompletedAwaitableAndClosesRetrievedWrapper() throws Exception {
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        PythonActionExecutor executor = newExecutor(interpreter);
        PyObject pythonAwaitable = mock(PyObject.class);
        String pythonAwaitableRef = "python_awaitable_1";
        when(interpreter.get(pythonAwaitableRef)).thenReturn(pythonAwaitable);
        when(interpreter.invoke(CALL_PYTHON_AWAITABLE, pythonAwaitable)).thenReturn(true);

        assertThat(executor.callPythonAwaitable(pythonAwaitableRef)).isTrue();

        InOrder closeOrder = inOrder(interpreter, pythonAwaitable);
        closeOrder.verify(interpreter).invoke(CALL_PYTHON_AWAITABLE, pythonAwaitable);
        closeOrder.verify(interpreter).exec("del " + pythonAwaitableRef);
        closeOrder.verify(pythonAwaitable).close();
    }

    @Test
    void closesRetrievedAwaitableWhenPollingFails() throws Exception {
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        PythonActionExecutor executor = newExecutor(interpreter);
        PyObject pythonAwaitable = mock(PyObject.class);
        String pythonAwaitableRef = "python_awaitable_1";
        RuntimeException failure = new RuntimeException("poll failed");
        when(interpreter.get(pythonAwaitableRef)).thenReturn(pythonAwaitable);
        when(interpreter.invoke(CALL_PYTHON_AWAITABLE, pythonAwaitable)).thenThrow(failure);

        assertThatThrownBy(() -> executor.callPythonAwaitable(pythonAwaitableRef))
                .isSameAs(failure);
        verify(pythonAwaitable).close();
        verify(interpreter, never()).exec(anyString());
    }

    private static PythonActionExecutor newExecutor(PythonInterpreter interpreter)
            throws Exception {
        return newExecutor(interpreter, null);
    }

    private static PythonActionExecutor newExecutor(
            PythonInterpreter interpreter, PythonRunnerContextImpl runnerContext) throws Exception {
        return new PythonActionExecutor(interpreter, null, null, runnerContext, "test-job");
    }
}
