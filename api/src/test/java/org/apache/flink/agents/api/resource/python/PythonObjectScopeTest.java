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
import pemja.core.object.PyObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PythonObjectScopeTest {

    @Test
    void closesNestedReferencesOnce() throws Exception {
        PyObject first = mock(PyObject.class);
        PyObject second = mock(PyObject.class);

        PythonObjectScope scope = new PythonObjectScope();
        scope.own(Map.of("values", List.of(first, second, first)));
        scope.close();
        scope.close();

        verify(first, times(1)).close();
        verify(second, times(1)).close();
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
