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
package org.apache.flink.agents.plan.resourceprovider;

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import pemja.core.object.PyObject;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PythonResourceProviderTest {

    @ParameterizedTest
    @EnumSource(
            value = ResourceType.class,
            names = {
                "CHAT_MODEL", "CHAT_MODEL_CONNECTION", "EMBEDDING_MODEL",
                "EMBEDDING_MODEL_CONNECTION", "VECTOR_STORE", "MCP_SERVER"
            })
    void providedResourceClosesHandleOnceWhenLogicalCloseFails(ResourceType type) throws Exception {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PyObject pythonResource = mock(PyObject.class);
        ResourceDescriptor descriptor =
                new ResourceDescriptor("example.module", "ExampleModel", Map.of());
        PythonResourceProvider provider = new PythonResourceProvider("model", type, descriptor);
        provider.setPythonResourceAdapter(adapter);
        when(adapter.initPythonResource(anyString(), anyString(), anyMap()))
                .thenReturn(pythonResource);
        RuntimeException failure = new RuntimeException("logical close failed");
        when(adapter.callMethod(pythonResource, "close", Map.of())).thenThrow(failure);

        Resource resource = provider.provide(mock(ResourceContext.class));

        verify(pythonResource, never()).close();
        assertThatThrownBy(resource::close).isSameAs(failure);
        resource.close();
        verify(adapter).callMethod(pythonResource, "close", Map.of());
        verify(pythonResource).close();
    }

    @Test
    void closesPythonResourceWhenWrapperConstructionFails() throws Exception {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PyObject pythonResource = mock(PyObject.class);
        Map<String, Object> arguments = Map.of("structured_output_strategy", "invalid");
        ResourceDescriptor descriptor =
                new ResourceDescriptor("example.module", "ExampleModel", arguments);
        PythonResourceProvider provider =
                new PythonResourceProvider("model", ResourceType.CHAT_MODEL, descriptor);
        provider.setPythonResourceAdapter(adapter);
        when(adapter.initPythonResource("example.module", "ExampleModel", arguments))
                .thenReturn(pythonResource);

        assertThatThrownBy(() -> provider.provide(mock(ResourceContext.class)))
                .hasCauseInstanceOf(IllegalArgumentException.class);

        verify(adapter).callMethod(pythonResource, "close", Map.of());
        verify(pythonResource).close();
    }
}
