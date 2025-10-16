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

import java.util.List;
import java.util.function.BiFunction;

/**
 * Abstraction of embedding model connection.
 *
 * <p>Responsible for managing embedding model service connection configurations, such as Service
 * address, API key, Connection timeout, Model name, Authentication information, etc.
 */
public abstract class BaseEmbeddingModelConnection extends Resource {

    public BaseEmbeddingModelConnection(
            ResourceDescriptor descriptor, BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.EMBEDDING_MODEL_CONNECTION;
    }

    /**
     * Generate embeddings for a single text input.
     *
     * @param text The input text to generate embeddings for
     * @return An array of floating-point values representing the text embeddings
     */
    public abstract float[] embed(String text);

    /**
     * Generate embeddings for multiple text inputs.
     *
     * @param texts The list of input texts to generate embeddings for
     * @return A list of arrays, each containing floating-point values representing the text
     *     embeddings
     */
    public abstract List<float[]> embed(List<String> texts);

    /**
     * Generate embeddings for a single text input using a specific model.
     *
     * @param text The input text to generate embeddings for
     * @param model The model to use for generating embeddings
     * @return An array of floating-point values representing the text embeddings
     */
    public abstract float[] embed(String text, String model);

    /**
     * Generate embeddings for multiple text inputs using a specific model.
     *
     * @param texts The list of input texts to generate embeddings for
     * @param model The model to use for generating embeddings
     * @return A list of arrays, each containing floating-point values representing the text embeddings
     */
    public abstract java.util.List<float[]> embed(java.util.List<String> texts, String model);

    /**
     * Get the dimension of the embeddings produced by this model.
     *
     * @return The embedding dimension
     */
    public abstract int getEmbeddingDimension();
}
