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

import org.apache.flink.agents.runtime.python.event.PythonEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import pemja.core.PythonInterpreter;

import java.lang.reflect.Field;

import static org.apache.flink.agents.runtime.python.utils.PythonActionExecutor.PYTHON_EVENT_TO_STRING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link PythonActionExecutor}. */
class PythonActionExecutorTest {

    @Mock private PythonInterpreter mockInterpreter;

    private PythonActionExecutor executor;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        // Create executor with null environment manager (we won't call open())
        executor = new PythonActionExecutor(null, "{}");

        // Inject mock interpreter via reflection
        Field interpreterField = PythonActionExecutor.class.getDeclaredField("interpreter");
        interpreterField.setAccessible(true);
        interpreterField.set(executor, mockInterpreter);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    void testPythonEventToString() {
        // Given
        byte[] eventBytes = new byte[] {1, 2, 3, 4, 5};
        String expectedString = "InputEvent(input='test data')";
        PythonEvent pythonEvent =
                new PythonEvent(eventBytes, "flink_agents.api.events.event.InputEvent");

        when(mockInterpreter.invoke(eq(PYTHON_EVENT_TO_STRING), eq((Object) eventBytes)))
                .thenReturn(expectedString);

        // When
        String result = executor.pythonEventToString(pythonEvent);

        // Then
        assertThat(result).isEqualTo(expectedString);
        verify(mockInterpreter).invoke(eq(PYTHON_EVENT_TO_STRING), eq((Object) eventBytes));
    }

    @Test
    void testPythonEventToStringWithComplexEvent() {
        // Given
        byte[] eventBytes = "serialized_python_object".getBytes();
        String expectedString = "OutputEvent(output={'key': 'value', 'nested': {'a': 1}})";
        PythonEvent pythonEvent =
                new PythonEvent(eventBytes, "flink_agents.api.events.event.OutputEvent");

        when(mockInterpreter.invoke(eq(PYTHON_EVENT_TO_STRING), eq((Object) eventBytes)))
                .thenReturn(expectedString);

        // When
        String result = executor.pythonEventToString(pythonEvent);

        // Then
        assertThat(result).isEqualTo(expectedString);
    }

    @Test
    void testPythonEventToStringWithEmptyEvent() {
        // Given
        byte[] eventBytes = new byte[0];
        String expectedString = "Event()";
        PythonEvent pythonEvent =
                new PythonEvent(eventBytes, "flink_agents.api.events.event.Event");

        when(mockInterpreter.invoke(eq(PYTHON_EVENT_TO_STRING), eq((Object) eventBytes)))
                .thenReturn(expectedString);

        // When
        String result = executor.pythonEventToString(pythonEvent);

        // Then
        assertThat(result).isEqualTo(expectedString);
    }

    @Test
    void testPythonEventToStringReturnsNullableString() {
        // Given - Python might return None which becomes null
        byte[] eventBytes = new byte[] {1, 2, 3};
        PythonEvent pythonEvent =
                new PythonEvent(eventBytes, "flink_agents.api.events.event.InputEvent");

        when(mockInterpreter.invoke(eq(PYTHON_EVENT_TO_STRING), eq((Object) eventBytes)))
                .thenReturn(null);

        // When
        String result = executor.pythonEventToString(pythonEvent);

        // Then
        assertThat(result).isNull();
    }

    @Test
    void testPythonEventToStringPassesCorrectEventBytes() {
        // Given - verify the exact bytes are passed to the interpreter
        byte[] expectedBytes = new byte[] {10, 20, 30, 40, 50};
        PythonEvent pythonEvent =
                new PythonEvent(expectedBytes, "flink_agents.api.events.event.CustomEvent");

        when(mockInterpreter.invoke(eq(PYTHON_EVENT_TO_STRING), eq((Object) expectedBytes)))
                .thenReturn("CustomEvent()");

        // When
        executor.pythonEventToString(pythonEvent);

        // Then - verify the exact bytes were passed
        verify(mockInterpreter).invoke(eq(PYTHON_EVENT_TO_STRING), eq((Object) expectedBytes));
    }
}
