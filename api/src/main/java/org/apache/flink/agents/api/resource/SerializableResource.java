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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.annotation.Internal;

import java.io.Serializable;

/**
 * Abstract class for resources that are serializable.
 *
 * <p>This class ensures that extending resources can be properly serialized.
 */
public abstract class SerializableResource extends Resource implements Serializable {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Bind the {@link ResourceContext} to this resource. A serializable resource is registered as
     * an instance rather than created from a {@link ResourceDescriptor}, so it does not receive the
     * context in its constructor; the framework calls this before {@link #open()}. User code should
     * not call it.
     *
     * @param resourceContext the resource context to bind
     */
    @Internal
    public void setResourceContext(ResourceContext resourceContext) {
        this.resourceContext = resourceContext;
    }

    /**
     * Validates that the resource is JSON serializable.
     *
     * @return a validated instance of {@link SerializableResource}
     * @throws IllegalArgumentException if the resource is not serializable
     */
    public SerializableResource validateSerializable() {
        try {
            objectMapper.writeValueAsString(this);
            return this;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Resource is not serializable: " + e.getMessage(), e);
        }
    }
}
