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

import org.apache.flink.agents.api.decision.BaseDecisionModelConnection;
import org.apache.flink.agents.api.decision.DecisionRequest;
import org.apache.flink.agents.api.decision.DecisionResult;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.plan.actions.Utils;
import pemja.core.object.PyObject;

import java.util.Map;
import java.util.Objects;

/** Java access to a Python decision model connection, managed by the resource lifecycle. */
public final class PythonDecisionModelConnection extends BaseDecisionModelConnection
        implements PythonResourceWrapper {
    private final PythonDecisionModelBridge bridge;

    public PythonDecisionModelConnection(
            PythonResourceAdapter adapter,
            PyObject resource,
            ResourceDescriptor descriptor,
            ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.bridge = new PythonDecisionModelBridge(adapter, resource);
    }

    @Override
    public void open() {
        bridge.open();
    }

    @Override
    public boolean supportsAsyncExecution() {
        return Utils.supportAsync();
    }

    @Override
    public DecisionResult decide(
            DecisionRequest request, String model, Map<String, Object> requestParams) {
        return bridge.decide(request, Objects.requireNonNull(model, "model"), requestParams);
    }

    @Override
    public Object getPythonResource() {
        return bridge.resource;
    }

    @Override
    public PythonResourceAdapter getPythonResourceAdapter() {
        return bridge.adapter;
    }

    @Override
    public void setMetricGroup(FlinkAgentsMetricGroup metricGroup) {
        super.setMetricGroup(metricGroup);
        setPythonResourceMetricGroup(metricGroup);
    }

    @Override
    public void close() throws Exception {
        bridge.close();
    }
}
