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

import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;

/**
 * Wrapper interface for Python resource objects. This interface provides a unified way to access
 * the underlying Python resource from Java objects that encapsulate Python functionality.
 */
public interface PythonResourceWrapper {

    /**
     * Retrieves the underlying Python resource object.
     *
     * @return the wrapped Python resource object
     */
    Object getPythonResource();

    /**
     * Returns the adapter that owns the wrapped Python resource.
     *
     * @return the Python resource adapter, or null if metric forwarding is unsupported
     */
    default PythonResourceAdapter getPythonResourceAdapter() {
        return null;
    }

    /**
     * Binds the current Java metric group to the wrapped Python resource.
     *
     * @param metricGroup the metric group to bind
     */
    default void setPythonResourceMetricGroup(FlinkAgentsMetricGroup metricGroup) {
        PythonResourceAdapter adapter = getPythonResourceAdapter();
        Object pythonResource = getPythonResource();
        if (adapter != null && pythonResource != null) {
            adapter.setMetricGroup(pythonResource, metricGroup);
        }
    }
}
