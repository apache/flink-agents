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

package org.apache.flink.agents.api.embedding.model;

import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.util.Preconditions;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for embedding model setup configurations.
 *
 * <p>This class provides common setup functionality for embedding models, including connection
 * management and model configuration.
 */
public abstract class BaseEmbeddingModelSetup extends Resource {
    protected final String connectionName;
    protected String model;

    @Nullable protected BaseEmbeddingModelConnection connection;

    public BaseEmbeddingModelSetup(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.connectionName = descriptor.getArgument("connection");
        this.model = descriptor.getArgument("model");
    }

    /**
     * Trigger construction for resource objects.
     *
     * <p>Currently, in cross-language invocation scenarios, constructing resource object within an
     * async thread may encounter issues. We resolved this issue by moving the construction of the
     * resources object out of the method to be async executed and invoking it in the main thread.
     */
    @Override
    public void open() throws Exception {
        this.connection =
                (BaseEmbeddingModelConnection)
                        resourceContext.getResource(
                                connectionName, ResourceType.EMBEDDING_MODEL_CONNECTION);
    }

    public abstract Map<String, Object> getParameters();

    @Override
    public ResourceType getResourceType() {
        return ResourceType.EMBEDDING_MODEL;
    }

    /**
     * Get the embedding model connection.
     *
     * @return The embedding model connection instance
     */
    @VisibleForTesting
    public BaseEmbeddingModelConnection getConnection() {
        Preconditions.checkNotNull(
                connection,
                "Connection is not initialized. Ensure open() is called before embed().");
        return connection;
    }

    /**
     * Get the model name.
     *
     * @return The model name
     */
    public String getModel() {
        return model;
    }

    /** Generate one vector for text, with optional provider token usage. */
    public EmbeddingResult<float[]> embed(String text) {
        return embed(text, Collections.emptyMap());
    }

    public EmbeddingResult<float[]> embed(String text, Map<String, Object> parameters) {
        return embed(new TextBlock(text), parameters);
    }

    /** Generate one vector for a supported data content block. */
    public EmbeddingResult<float[]> embed(DataContentBlock block) {
        return embed(block, Collections.emptyMap());
    }

    public EmbeddingResult<float[]> embed(DataContentBlock block, Map<String, Object> parameters) {
        return EmbeddingModelUtils.singleResult(embed(List.of(block), parameters));
    }

    /** Generate one vector per block in input order, with optional usage for the batch. */
    public EmbeddingResult<List<float[]>> embed(List<? extends DataContentBlock> blocks) {
        return embed(blocks, Collections.emptyMap());
    }

    public EmbeddingResult<List<float[]>> embed(
            List<? extends DataContentBlock> blocks, Map<String, Object> parameters) {
        Map<String, Object> merged = new HashMap<>(getParameters());
        merged.putAll(parameters);
        return getConnection().embed(blocks, merged);
    }
}
