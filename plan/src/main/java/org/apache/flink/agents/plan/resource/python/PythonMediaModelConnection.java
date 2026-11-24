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

import org.apache.flink.agents.api.media.BaseMediaModelConnection;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import pemja.core.object.PyObject;

import java.util.Map;

/**
 * Python-based implementation of {@link BaseMediaModelConnection} that wraps a Python media model
 * connection object, managing its lifecycle and metric group across Pemja.
 */
public final class PythonMediaModelConnection extends BaseMediaModelConnection
        implements PythonResourceWrapper {

    private final PythonResourceAdapter adapter;
    private final PyObject resource;
    private volatile boolean closed;

    public PythonMediaModelConnection(
            PythonResourceAdapter adapter,
            PyObject resource,
            ResourceDescriptor descriptor,
            ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.adapter = adapter;
        this.resource = resource;
    }

    @Override
    public void open() {
        adapter.callMethod(resource, "open", Map.of());
    }

    @Override
    public Object getPythonResource() {
        return resource;
    }

    @Override
    public PythonResourceAdapter getPythonResourceAdapter() {
        return adapter;
    }

    @Override
    public void setMetricGroup(FlinkAgentsMetricGroup metricGroup) {
        super.setMetricGroup(metricGroup);
        setPythonResourceMetricGroup(metricGroup);
    }

    @Override
    public void close() throws Exception {
        if (closed || resource == null) {
            return;
        }
        closed = true;
        try (resource) {
            adapter.callMethod(resource, "close", Map.of());
        }
    }
}
