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

package org.apache.flink.agents.api.chat.model.routing;

import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;

/**
 * Convenience base class for {@link RoutingStrategy} implementations that are instantiated from a
 * {@link ResourceDescriptor}.
 *
 * <p>{@link ChatModelRouter} instantiates the configured strategy reflectively, requiring a public
 * constructor with the signature {@code (ResourceDescriptor, ResourceContext)} — the same
 * convention used by {@link org.apache.flink.agents.api.resource.Resource}. Extending this class
 * gives custom strategies that constructor for free and exposes the descriptor/context to
 * subclasses.
 */
public abstract class AbstractRoutingStrategy implements RoutingStrategy {

    protected final ResourceDescriptor descriptor;
    protected final ResourceContext resourceContext;

    protected AbstractRoutingStrategy(
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        this.descriptor = descriptor;
        this.resourceContext = resourceContext;
    }

    /** Read a strategy configuration argument from the backing descriptor. */
    protected <T> T arg(String name) {
        return descriptor != null ? descriptor.getArgument(name) : null;
    }

    /** Read a strategy configuration argument, falling back to {@code defaultValue} when absent. */
    protected <T> T arg(String name, T defaultValue) {
        T value = arg(name);
        return value != null ? value : defaultValue;
    }
}
