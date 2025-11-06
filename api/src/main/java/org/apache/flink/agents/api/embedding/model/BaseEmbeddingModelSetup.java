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

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Base class for embedding model setup configurations.
 *
 * <p>This class provides common setup functionality for embedding models, including connection
 * management and model configuration.
 */
public abstract class BaseEmbeddingModelSetup extends Resource {
    protected final String connection;
    protected String model;

    public BaseEmbeddingModelSetup(
            ResourceDescriptor descriptor, BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
        this.connection = descriptor.getArgument("connection");
        this.model = descriptor.getArgument("model");
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
    public BaseEmbeddingModelConnection getConnection() {
        return (BaseEmbeddingModelConnection)
                getResource.apply(connection, ResourceType.EMBEDDING_MODEL_CONNECTION);
    }

    /**
     * Get the model name.
     *
     * @return The model name
     */
    public String getModel() {
        return model;
    }

    /**
     * Generate embeddings for the given text.
     *
     * @param text The input text to generate embeddings for
     * @return An array of floating-point values representing the text embeddings
     */
    public float[] embed(String text) {
        return this.embed(text, Collections.emptyMap());
    }

    public float[] embed(String text, Map<String, Object> parameters) {
        BaseEmbeddingModelConnection connection = getConnection();

        Map<String, Object> params = this.getParameters();
        params.putAll(parameters);

        // params are propagated to the connection
        return connection.embed(text, params);
    }

    /**
     * Generate embeddings for multiple texts.
     *
     * @param texts The list of input texts to generate embeddings for
     * @return A list of arrays, each containing floating-point values representing the text
     *     embeddings
     */
    public List<float[]> embed(List<String> texts) {
        return this.embed(texts, Collections.emptyMap());
    }

    public List<float[]> embed(List<String> texts, Map<String, Object> parameters) {
        BaseEmbeddingModelConnection connection = getConnection();

        Map<String, Object> params = this.getParameters();
        params.putAll(parameters);

        // params are propagated to the connection
        return connection.embed(texts, params);
    }
}
