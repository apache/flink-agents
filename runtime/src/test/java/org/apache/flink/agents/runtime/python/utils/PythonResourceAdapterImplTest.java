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

import org.apache.flink.agents.api.chat.model.python.PythonChatModelSetup;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.python.PythonResourceWrapper;
import org.apache.flink.agents.api.tools.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import pemja.core.PythonInterpreter;
import pemja.core.object.PyObject;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class PythonResourceAdapterImplTest {
    @Mock private PythonInterpreter mockInterpreter;

    @Mock private BiFunction<String, ResourceType, Resource> getResource;

    private PythonResourceAdapterImpl pythonResourceAdapter;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        pythonResourceAdapter = new PythonResourceAdapterImpl(getResource, mockInterpreter);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    void testInitPythonResource() {
        String module = "test_module";
        String clazz = "TestClass";
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("param1", "value1");
        kwargs.put("param2", 42);

        PyObject expectedResult = mock(PyObject.class);
        when(mockInterpreter.invoke(
                        PythonResourceAdapterImpl.CREATE_RESOURCE, module, clazz, kwargs))
                .thenReturn(expectedResult);

        PyObject result = pythonResourceAdapter.initPythonResource(module, clazz, kwargs);

        assertThat(result).isEqualTo(expectedResult);
        assertThat(kwargs).containsKey(PythonResourceAdapterImpl.GET_RESOURCE_KEY);
        verify(mockInterpreter)
                .invoke(PythonResourceAdapterImpl.CREATE_RESOURCE, module, clazz, kwargs);
    }

    @Test
    void testOpen() {

        pythonResourceAdapter.open();

        verify(mockInterpreter).exec(PythonResourceAdapterImpl.PYTHON_IMPORTS);
        verify(mockInterpreter)
                .invoke(PythonResourceAdapterImpl.GET_RESOURCE_FUNCTION, pythonResourceAdapter);
    }

    @Test
    void testGetResourceWithPythonResourceWrapper() {
        String resourceName = "test_resource";
        String resourceType = "chat_model";
        PythonResourceWrapper mockPythonChatModelSetup = mock(PythonChatModelSetup.class);
        Object expectedPythonResource = new Object();

        when(getResource.apply(resourceName, ResourceType.CHAT_MODEL))
                .thenReturn((Resource) mockPythonChatModelSetup);
        when(mockPythonChatModelSetup.getPythonResource()).thenReturn(expectedPythonResource);

        Object result = pythonResourceAdapter.getResource(resourceName, resourceType);

        assertThat(result).isEqualTo(expectedPythonResource);
        verify(getResource).apply(resourceName, ResourceType.CHAT_MODEL);
        verify(mockPythonChatModelSetup).getPythonResource();
    }

    @Test
    void testGetResourceWithTool() {
        String resourceName = "test_tool";
        String resourceType = "tool";
        Tool mockTool = mock(Tool.class);
        Object expectedPythonTool = new Object();

        when(getResource.apply(resourceName, ResourceType.TOOL)).thenReturn(mockTool);
        when(mockInterpreter.invoke(PythonResourceAdapterImpl.FROM_JAVA_TOOL, mockTool))
                .thenReturn(expectedPythonTool);

        Object result = pythonResourceAdapter.getResource(resourceName, resourceType);

        assertThat(result).isEqualTo(expectedPythonTool);
        verify(getResource).apply(resourceName, ResourceType.TOOL);
        verify(mockInterpreter).invoke(PythonResourceAdapterImpl.FROM_JAVA_TOOL, mockTool);
    }

    @Test
    void testGetResourceWithPrompt() {
        String resourceName = "test_prompt";
        String resourceType = "prompt";
        Prompt mockPrompt = mock(Prompt.class);
        Object expectedPythonPrompt = new Object();

        when(getResource.apply(resourceName, ResourceType.PROMPT)).thenReturn(mockPrompt);
        when(mockInterpreter.invoke(PythonResourceAdapterImpl.FROM_JAVA_PROMPT, mockPrompt))
                .thenReturn(expectedPythonPrompt);

        Object result = pythonResourceAdapter.getResource(resourceName, resourceType);

        assertThat(result).isEqualTo(expectedPythonPrompt);
        verify(getResource).apply(resourceName, ResourceType.PROMPT);
        verify(mockInterpreter).invoke(PythonResourceAdapterImpl.FROM_JAVA_PROMPT, mockPrompt);
    }

    @Test
    void testGetResourceWithRegularResource() {
        String resourceName = "test_resource";
        String resourceType = "chat_model";
        Resource mockResource = mock(Resource.class);

        when(getResource.apply(resourceName, ResourceType.CHAT_MODEL)).thenReturn(mockResource);

        Object result = pythonResourceAdapter.getResource(resourceName, resourceType);

        assertThat(result).isEqualTo(mockResource);
        verify(getResource).apply(resourceName, ResourceType.CHAT_MODEL);
    }

    @Test
    void testCallMethod() {
        // Arrange
        Object obj = new Object();
        String methodName = "test_method";
        Map<String, Object> kwargs = Map.of("param", "value");
        Object expectedResult = "method_result";

        when(mockInterpreter.invoke(PythonResourceAdapterImpl.CALL_METHOD, obj, methodName, kwargs))
                .thenReturn(expectedResult);

        Object result = pythonResourceAdapter.callMethod(obj, methodName, kwargs);

        assertThat(result).isEqualTo(expectedResult);
        verify(mockInterpreter)
                .invoke(PythonResourceAdapterImpl.CALL_METHOD, obj, methodName, kwargs);
    }

    @Test
    void testInvoke() {
        String name = "test_function";
        Object[] args = {"arg1", 42, true};
        Object expectedResult = "invoke_result";

        when(mockInterpreter.invoke(name, args)).thenReturn(expectedResult);

        Object result = pythonResourceAdapter.invoke(name, args);

        assertThat(result).isEqualTo(expectedResult);
        verify(mockInterpreter).invoke(name, args);
    }
}
