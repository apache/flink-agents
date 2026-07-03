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

    private static PythonActionExecutor newExecutor(PythonInterpreter interpreter)
            throws Exception {
        return new PythonActionExecutor(interpreter, null, null, null, "test-job");
    }
}
