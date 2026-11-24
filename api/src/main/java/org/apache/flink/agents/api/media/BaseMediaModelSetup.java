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

package org.apache.flink.agents.api.media;

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.utils.StringValidationUtils;

import javax.annotation.Nullable;

import java.util.Objects;

/**
 * Abstract base class for {@link ResourceType#MEDIA_MODEL} capability setups.
 *
 * <p>Concrete task setups extend one of the four task-specific subclasses ({@link
 * BaseSpeechSynthesisModelSetup}, {@link BaseTranscriptionModelSetup}, {@link
 * BaseImageGenerationModelSetup}, or {@link BaseVideoGenerationModelSetup}) and optionally
 * implement {@link AsyncJobModel} when backed by resumable provider jobs. Each setup references a
 * {@link ResourceType#MEDIA_MODEL_CONNECTION} via {@link ResourceDescriptor} and resolves it when
 * opened. Closing a setup detaches its connection reference without closing the shared connection.
 */
public abstract class BaseMediaModelSetup extends Resource {
    protected final ResourceDescriptor descriptor;
    private final String connectionName;
    private final String model;
    @Nullable protected BaseMediaModelConnection connection;

    /**
     * Construct a media model setup from a resource descriptor.
     *
     * <p>Requires non-blank {@code connection} and {@code model} arguments.
     */
    protected BaseMediaModelSetup(
            ResourceDescriptor descriptor, @Nullable ResourceContext resourceContext) {
        super(Objects.requireNonNull(descriptor, "descriptor"), resourceContext);
        this.descriptor = descriptor;
        this.connectionName =
                StringValidationUtils.requireNonBlank(
                        descriptor.getArgument("connection"), "connection");
        this.model =
                StringValidationUtils.requireNonBlank(descriptor.getArgument("model"), "model");
    }

    public String getConnectionName() {
        return connectionName;
    }

    public String getModel() {
        return model;
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.MEDIA_MODEL;
    }

    @Override
    public void open() throws Exception {
        super.open();
        if (connection != null) {
            return;
        }
        if (resourceContext == null) {
            throw new IllegalStateException(
                    "ResourceContext is required to resolve media model connection '"
                            + connectionName
                            + "'.");
        }
        Resource resource =
                resourceContext.getResource(connectionName, ResourceType.MEDIA_MODEL_CONNECTION);
        if (!(resource instanceof BaseMediaModelConnection)) {
            throw new IllegalArgumentException(
                    "Resource '"
                            + connectionName
                            + "' of type MEDIA_MODEL_CONNECTION is not a BaseMediaModelConnection: "
                            + (resource == null ? "null" : resource.getClass().getName()));
        }
        connection = (BaseMediaModelConnection) resource;
    }

    @Override
    public void close() throws Exception {
        connection = null;
        super.close();
    }

    /** Return the opened {@link BaseMediaModelConnection} for this setup. */
    protected BaseMediaModelConnection getConnection() {
        if (connection == null) {
            throw new IllegalStateException(
                    "Media model setup is not open. Call open() before using it.");
        }
        return connection;
    }
}
