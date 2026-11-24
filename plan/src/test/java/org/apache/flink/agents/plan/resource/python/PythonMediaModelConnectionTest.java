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

package org.apache.flink.agents.plan.resource.python;

import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.Test;
import pemja.core.object.PyObject;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** Unit tests for {@link PythonMediaModelConnection} lifecycle and wrapper delegation. */
class PythonMediaModelConnectionTest {

    @Test
    void openCloseAndResourceAccessDelegateToPythonConnection() throws Exception {
        PythonResourceAdapter adapter = mock(PythonResourceAdapter.class);
        PyObject pyResource = mock(PyObject.class);
        PythonMediaModelConnection connection =
                new PythonMediaModelConnection(
                        adapter, pyResource, new ResourceDescriptor("unused", Map.of()), null);

        assertThat(connection.getResourceType()).isEqualTo(ResourceType.MEDIA_MODEL_CONNECTION);
        assertThat(connection.getPythonResource()).isSameAs(pyResource);
        assertThat(connection.getPythonResourceAdapter()).isSameAs(adapter);

        connection.open();
        verify(adapter).callMethod(pyResource, "open", Map.of());

        FlinkAgentsMetricGroup metricGroup = mock(FlinkAgentsMetricGroup.class);
        connection.setMetricGroup(metricGroup);
        verify(adapter).setMetricGroup(pyResource, metricGroup);

        connection.close();
        connection.close();
        verify(adapter, times(1)).callMethod(pyResource, "close", Map.of());
        verify(pyResource, times(1)).close();
        verifyNoMoreInteractions(adapter);
    }
}
