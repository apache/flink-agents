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

package org.apache.flink.agents.api.resource;

import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;

import java.util.function.BiFunction;

/**
 * Base interface for all kinds of resources, including chat models, tools, prompts and so on.
 *
 * <p>Resources are components that can be used by agents during action execution.
 */
public abstract class Resource {
    protected BiFunction<String, ResourceType, Resource> getResource;

    /** The metric group bound to this resource, injected by RunnerContext.getResource(). */
    private transient FlinkAgentsMetricGroup metricGroup;

    protected Resource(
            ResourceDescriptor descriptor, BiFunction<String, ResourceType, Resource> getResource) {
        this.getResource = getResource;
    }

    protected Resource() {}

    /**
     * Get the type of the resource.
     *
     * @return the resource type
     */
    public abstract ResourceType getResourceType();

    /**
     * Set the metric group for this resource.
     *
     * @param metricGroup the metric group to bind
     */
    public void setMetricGroup(FlinkAgentsMetricGroup metricGroup) {
        this.metricGroup = metricGroup;
    }

    /**
     * Get the bound metric group.
     *
     * @return the bound metric group, or null if not set
     */
    protected FlinkAgentsMetricGroup getMetricGroup() {
        return metricGroup;
    }
}
