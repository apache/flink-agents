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

package org.apache.flink.agents.integrations.embeddingmodels.ollama;

import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelSetup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;

import java.util.List;
import java.util.function.BiFunction;

/**
 * An embedding model setup for Ollama powered by the ollama4j client.
 *
 * <p>This implementation adapts the generic Flink Agents embedding model interface to Ollama's
 * embedding API. It supports various embedding models available in Ollama such as: -
 * nomic-embed-text (768 dimensions) - mxbai-embed-large (1024 dimensions) - all-minilm (384
 * dimensions) - And other embedding models supported by Ollama
 *
 * <p>See also {@link BaseEmbeddingModelSetup} for the common resource abstractions and lifecycle.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyAgent extends Agent {
 *   // Register the embedding model setup via @EmbeddingModelSetup metadata.
 *   @EmbeddingModelSetup
 *   public static ResourceDesc ollama() {
 *     return ResourceDescriptor.Builder.newBuilder(OllamaEmbeddingModelSetup.class.getName())
 *                 .addInitialArgument("connection", "myConnection") // the name of OllamaEmbeddingModelConnection
 *                 .addInitialArgument("model", "nomic-embed-text") // the model name
 *                 .build();
 *   }
 * }
 * }</pre>
 */
public class OllamaEmbeddingModelSetup extends BaseEmbeddingModelSetup {

    public OllamaEmbeddingModelSetup(
            ResourceDescriptor descriptor, BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
    }

    @Override
    public OllamaEmbeddingModelConnection getConnection() {
        return (OllamaEmbeddingModelConnection) super.getConnection();
    }

    /**
     * Generate embeddings for the given text using the configured Ollama model.
     *
     * @param text The input text to generate embeddings for
     * @return An array of floating-point values representing the text embeddings
     */
    @Override
    public float[] embed(String text) {
        return getConnection().embed(text);
    }

    /**
     * Generate embeddings for the given text using a specific model.
     *
     * @param text The input text to generate embeddings for
     * @param model The specific embedding model to use
     * @return An array of floating-point values representing the text embeddings
     */
    public float[] embed(String text, String model) {
        return getConnection().embed(text, model);
    }

    /**
     * Generate embeddings for multiple texts using the configured model.
     *
     * @param texts The list of input texts to generate embeddings for
     * @return A list of arrays, each containing floating-point values representing the text
     *     embeddings
     */
    public List<float[]> embed(List<String> texts) {
        return getConnection().embed(texts);
    }

    /**
     * Generate embeddings for multiple texts using a specific model.
     *
     * @param texts The list of input texts to generate embeddings for
     * @param model The specific embedding model to use
     * @return A list of arrays, each containing floating-point values representing the text
     *     embeddings
     */
    public List<float[]> embed(List<String> texts, String model) {
        return getConnection().embed(texts, model);
    }

    /**
     * Get the dimension of the embeddings produced by the configured Ollama model.
     *
     * @return The embedding dimension
     */
    @Override
    public int getEmbeddingDimension() {
        return getConnection().getEmbeddingDimension();
    }

    /**
     * Check if the specified model is available on the Ollama server.
     *
     * @param model The model name to check
     * @return true if the model is available, false otherwise
     */
    public boolean isModelAvailable(String model) {
        return getConnection().isModelAvailable(model);
    }

    /**
     * Get the default embedding model name configured for this setup.
     *
     * @return The default model name
     */
    public String getDefaultModel() {
        return getConnection().getDefaultModel();
    }
}
