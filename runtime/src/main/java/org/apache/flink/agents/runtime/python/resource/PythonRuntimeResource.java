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

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;

import pemja.core.object.PyObject;

/**
 * Java-side handle to a resource that lives in the Python runtime, letting Java code reach a
 * resource it cannot construct itself.
 *
 * <p>The Python runtime owns the resource: it constructs it, keeps it in its own cache and closes
 * it. This handle is therefore non-owning — {@link #open()} and {@link #close()} deliberately do
 * nothing, because opening or closing the same Python resource a second time from Java would break
 * the invariants its owner already established.
 *
 * <p>The handle carries no behaviour of its own, because what a Python resource can do is
 * expressed in Python: a caller that needs more than the resource's type drives the Python object
 * from {@link #getPythonResource()} over the bridge.
 */
public final class PythonRuntimeResource extends Resource {

    private final ResourceType type;
    private final PyObject pythonResource;

    public PythonRuntimeResource(ResourceType type, PyObject pythonResource) {
        this.type = type;
        this.pythonResource = pythonResource;
    }

    @Override
    public ResourceType getResourceType() {
        return type;
    }

    /** Returns the Python object this handle stands for. */
    public PyObject getPythonResource() {
        return pythonResource;
    }
}
