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
package org.apache.flink.agents.api.resource.python;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import pemja.core.object.PyObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PythonObjectScopeTest {

    @Test
    void closesNestedReferencesOnceInReverseAcquisitionOrder() throws Exception {
        PyObject first = mock(PyObject.class);
        PyObject second = mock(PyObject.class);

        PythonObjectScope scope = new PythonObjectScope();
        scope.own(Map.of("values", List.of(first, second, first)));
        scope.close();
        scope.close();

        InOrder closeOrder = inOrder(first, second);
        closeOrder.verify(second).close();
        closeOrder.verify(first).close();
        verify(first, times(1)).close();
        verify(second, times(1)).close();
    }

    @Test
    void leavesTransferredReferencesOpen() throws Exception {
        PyObject retained = mock(PyObject.class);
        PyObject temporary = mock(PyObject.class);

        try (PythonObjectScope scope = new PythonObjectScope()) {
            scope.own(List.of(retained, temporary));
            scope.release(retained);
        }

        verify(temporary).close();
        verify(retained, times(0)).close();
    }

    @Test
    void closesNativeReferenceWhenLogicalResourceCloseFails() throws Exception {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PyObject resource = mock(PyObject.class);
        RuntimeException failure = new RuntimeException("logical close failed");
        when(adapter.callMethod(resource, "close", Map.of())).thenThrow(failure);

        PythonObjectScope scope = new PythonObjectScope();
        scope.own(resource);

        assertThatThrownBy(() -> scope.closeResource(adapter, resource)).isSameAs(failure);
        verify(resource).close();

        scope.closeResource(adapter, resource);
        verify(adapter, times(1)).callMethod(resource, "close", Map.of());
        verify(resource, times(1)).close();
    }

    @Test
    void closesLogicalCloseResultBeforeNativeResourceReference() throws Exception {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PyObject resource = mock(PyObject.class);
        PyObject closeResult = mock(PyObject.class);
        when(adapter.callMethod(resource, "close", Map.of())).thenReturn(closeResult);

        PythonObjectScope scope = new PythonObjectScope();
        scope.own(resource);
        scope.closeResource(adapter, resource);

        InOrder closeOrder = inOrder(closeResult, resource);
        closeOrder.verify(closeResult).close();
        closeOrder.verify(resource).close();
    }

    @Test
    void doesNotCloseResourceTwiceWhenLogicalCloseReturnsItself() throws Exception {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PyObject resource = mock(PyObject.class);
        when(adapter.callMethod(resource, "close", Map.of())).thenReturn(resource);

        PythonObjectScope scope = new PythonObjectScope();
        scope.own(resource);
        scope.closeResource(adapter, resource);

        verify(resource, times(1)).close();
    }

    @Test
    void rejectsReferencesAddedAfterClose() {
        PythonObjectScope scope = new PythonObjectScope();
        scope.close();

        assertThatThrownBy(() -> scope.own(mock(PyObject.class)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void handlesSelfReferentialContainers() throws Exception {
        PyObject reference = mock(PyObject.class);
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(reference);
        cyclic.add(cyclic);

        try (PythonObjectScope scope = new PythonObjectScope()) {
            scope.own(cyclic);
        }

        verify(reference).close();
    }
}
