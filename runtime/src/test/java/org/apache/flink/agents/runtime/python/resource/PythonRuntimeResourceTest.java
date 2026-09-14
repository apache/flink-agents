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

package org.apache.flink.agents.runtime.python.resource;

import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.Test;
import pemja.core.object.PyObject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Tests for {@link PythonRuntimeResource}. */
public class PythonRuntimeResourceTest {

    private final PyObject pythonResource = mock(PyObject.class);
    private final PythonRuntimeResource handle =
            new PythonRuntimeResource(ResourceType.CHAT_MODEL, pythonResource);

    @Test
    public void testHandleReportsTheTypeItWasMaterializedFor() {
        assertThat(handle.getResourceType()).isEqualTo(ResourceType.CHAT_MODEL);
    }

    @Test
    public void testHandleExposesThePythonObjectItStandsFor() {
        assertThat(handle.getPythonResource()).isSameAs(pythonResource);
    }

    // The Python runtime opened the resource and will close it, so a handle that opened or closed
    // it again would break the invariants its owner already established.
    @Test
    public void testOpenAndCloseLeaveThePythonOwnedResourceUntouched() throws Exception {
        handle.open();
        handle.close();

        verifyNoInteractions(pythonResource);
    }
}
