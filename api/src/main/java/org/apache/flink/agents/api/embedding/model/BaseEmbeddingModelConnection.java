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
 * Unless required by applicable law or agreed in writing, software
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
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Abstraction of embedding model connection.
 *
 * <p>Responsible for managing embedding model service connection configurations, such as Service
 * address, API key, Connection timeout, Model name, Authentication information, etc.
 *
 * <p>This class follows the parameter pattern where additional configuration options can be passed
 * through a Map&lt;String, Object&gt; parameters argument. Common parameters include:
 *
 * <ul>
 *   <li>model - The model name to use for embeddings
 *   <li>encoding_format - The format for encoding (e.g., "float", "base64")
 *   <li>timeout - Request timeout in milliseconds
 *   <li>batch_size - Maximum number of texts to process in a single request
 * </ul>
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
     * @param parameters Additional parameters to configure the embedding request. Common parameters
     *     include: - "model" (String): The specific model variant to use - "encoding_format"
     *     (String): The format for encoding (e.g., "float", "base64") - "timeout" (Integer):
     *     Request timeout in milliseconds
     * @return An array of floating-point values representing the text embeddings. The length of the
     *     array is determined by the model itself.
     */
    public abstract float[] embed(String text, Map<String, Object> parameters);

    /**
     * Generate embeddings for multiple text inputs.
     *
     * @param texts The list of input texts to generate embeddings for
     * @param parameters Additional parameters to configure the embedding request. Common parameters
     *     include: - "model" (String): The specific model variant to use - "encoding_format"
     *     (String): The format for encoding (e.g., "float", "base64") - "batch_size" (Integer):
     *     Maximum number of texts to process in a single request - "timeout" (Integer): Request
     *     timeout in milliseconds
     * @return A list of arrays, each containing floating-point values representing the text
     *     embeddings. The length of each array is determined by the model itself.
     */
    public abstract List<float[]> embed(List<String> texts, Map<String, Object> parameters);
}
