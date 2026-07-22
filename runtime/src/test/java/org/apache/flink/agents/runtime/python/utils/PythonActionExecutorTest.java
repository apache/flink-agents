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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PythonActionExecutorTest {

    @Test
    void resolvesPickledStringFromPyFlinkKeyRow() throws Exception {
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        PythonActionExecutor executor = newExecutor(interpreter);
        byte[] pickledKey = new byte[] {1, 2, 3};
        when(interpreter.invoke("python_java_utils.convert_to_python_object", pickledKey))
                .thenReturn("user-7");

        assertThat(executor.resolveStringKey(Row.of(pickledKey))).isEqualTo("user-7");
        verify(interpreter).invoke("python_java_utils.convert_to_python_object", pickledKey);
    }

    @Test
    void rejectsNonStringAndMalformedPyFlinkKeys() throws Exception {
        PythonInterpreter interpreter = mock(PythonInterpreter.class);
        PythonActionExecutor executor = newExecutor(interpreter);
        byte[] nonStringKey = new byte[] {1};
        byte[] malformedKey = new byte[] {2};
        when(interpreter.invoke("python_java_utils.convert_to_python_object", nonStringKey))
                .thenReturn(7);
        when(interpreter.invoke("python_java_utils.convert_to_python_object", malformedKey))
                .thenThrow(new RuntimeException("bad pickle"));

        assertThat(executor.resolveStringKey(Row.of(nonStringKey))).isNull();
        assertThat(executor.resolveStringKey(Row.of(malformedKey))).isNull();
        assertThat(executor.resolveStringKey(Row.of("too", "many"))).isNull();
        assertThat(executor.resolveStringKey(7)).isNull();
    }

    private static PythonActionExecutor newExecutor(PythonInterpreter interpreter)
            throws Exception {
        return new PythonActionExecutor(interpreter, null, null, null, "test-job");
    }
}
