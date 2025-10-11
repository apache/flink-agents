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

package org.apache.flink.agents.integrations.embeddingmodels.ollama;

import io.github.ollama4j.OllamaAPI;
import io.github.ollama4j.exceptions.OllamaBaseException;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelConnection;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * An embedding model integration for Ollama powered by the ollama4j client.
 *
 * <p>This implementation adapts the generic Flink Agents embedding model interface to Ollama's
 * embedding API. It supports various embedding models available in Ollama such as:
 *
 * <ul>
 *   <li>nomic-embed-text
 *   <li>mxbai-embed-large
 *   <li>all-minilm
 *   <li>And other embedding models supported by Ollama
 * </ul>
 *
 * <p>See also {@link BaseEmbeddingModelConnection} for the common resource abstractions and
 * lifecycle.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyAgent extends Agent {
 *   // Register the embedding model connection via @EmbeddingModelConnection metadata.
 *   @EmbeddingModelConnection
 *   public static ResourceDescriptor ollama() {
 *     return ResourceDescriptor.Builder.newBuilder(OllamaEmbeddingModelConnection.class.getName())
 *                 .addInitialArgument("host", "http://localhost:11434") // the Ollama server URL
 *                 .addInitialArgument("timeout", 60) // optional timeout in seconds
 *                 .addInitialArgument("model", "nomic-embed-text") // the embedding model name
 *                 .build();
 *   }
 * }
 * }</pre>
 */
public class OllamaEmbeddingModelConnection extends BaseEmbeddingModelConnection {

    private final OllamaAPI ollamaAPI;
    private final String host;
    private final String defaultModel;
    private Integer cachedDimension;

    public OllamaEmbeddingModelConnection(
            ResourceDescriptor descriptor, BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
        this.host =
                descriptor.getArgument("host") != null
                        ? descriptor.getArgument("host")
                        : "http://localhost:11434";
        long timeout =
                descriptor.getArgument("timeout") != null
                        ? Long.parseLong(descriptor.getArgument("timeout").toString())
                        : 60L;
        this.defaultModel =
                descriptor.getArgument("model") != null
                        ? descriptor.getArgument("model")
                        : "nomic-embed-text";

        this.ollamaAPI = new OllamaAPI(host);
        // Note: OllamaAPI timeout configuration may vary by version
        // For ollama4j 1.1.0, timeout is typically configured at request level
    }

    @Override
    public float[] embed(String text) {
        return embed(text, defaultModel);
    }

    /**
     * Generate embeddings for a single text with a specific model.
     *
     * @param text The input text to generate embeddings for
     * @param model The embedding model to use
     * @return An array of floating-point values representing the text embeddings
     */
    public float[] embed(String text, String model) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Text cannot be null or empty");
        }

        try {
            // Use the modern API for generating embeddings
            List<Double> embeddings = ollamaAPI.generateEmbeddings(model, text);

            if (embeddings == null || embeddings.isEmpty()) {
                throw new RuntimeException(
                        "Received empty embeddings from Ollama for model: " + model);
            }

            // Convert List<Double> to float[]
            float[] result = new float[embeddings.size()];
            for (int i = 0; i < embeddings.size(); i++) {
                result[i] = embeddings.get(i).floatValue();
            }

            // Cache the dimension for future reference
            if (cachedDimension == null) {
                cachedDimension = result.length;
            }

            return result;
        } catch (OllamaBaseException e) {
            throw new RuntimeException(
                    "Ollama API error while generating embeddings for text with model '"
                            + model
                            + "': "
                            + e.getMessage(),
                    e);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(
                    "Communication error with Ollama server at "
                            + host
                            + " while generating embeddings: "
                            + e.getMessage(),
                    e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Unexpected error while generating embeddings for text with model '"
                            + model
                            + "': "
                            + e.getMessage(),
                    e);
        }
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return embed(texts, defaultModel);
    }

    /**
     * Generate embeddings for multiple texts with a specific model. This method processes texts
     * sequentially to avoid overwhelming the Ollama server.
     *
     * @param texts The list of input texts to generate embeddings for
     * @param model The embedding model to use
     * @return A list of arrays, each containing floating-point values representing the text
     *     embeddings
     */
    public List<float[]> embed(List<String> texts, String model) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("Texts list cannot be null or empty");
        }

        List<float[]> results = new ArrayList<>();
        for (String text : texts) {
            if (text != null && !text.trim().isEmpty()) {
                results.add(embed(text, model));
            } else {
                throw new IllegalArgumentException("Text in list cannot be null or empty");
            }
        }
        return results;
    }

    @Override
    public int getEmbeddingDimension() {
        if (cachedDimension != null) {
            return cachedDimension;
        }

        // If not cached, generate a test embedding to determine dimension
        try {
            float[] testEmbedding = embed("test", defaultModel);
            cachedDimension = testEmbedding.length;
            return cachedDimension;
        } catch (Exception e) {
            // Return default dimension for common models if test fails
            switch (defaultModel.toLowerCase()) {
                case "nomic-embed-text":
                    return 768;
                case "mxbai-embed-large":
                    return 1024;
                case "all-minilm":
                    return 384;
                default:
                    throw new RuntimeException(
                            "Could not determine embedding dimension for model: "
                                    + defaultModel
                                    + ". Cause: "
                                    + e.getMessage(),
                            e);
            }
        }
    }

    /**
     * Check if the specified model is available on the Ollama server.
     *
     * @param model The model name to check
     * @return true if the model is available, false otherwise
     */
    public boolean isModelAvailable(String model) {
        try {
            // Try to list models and check if our model is available
            return ollamaAPI.listModels().stream()
                    .anyMatch(modelInfo -> modelInfo.getName().equals(model));
        } catch (Exception e) {
            // If we can't list models, try a test embedding
            try {
                embed("test", model);
                return true;
            } catch (Exception testException) {
                return false;
            }
        }
    }

    /**
     * Get the default embedding model name.
     *
     * @return The default model name
     */
    public String getDefaultModel() {
        return defaultModel;
    }

    /**
     * Get the Ollama server host URL.
     *
     * @return The host URL
     */
    public String getHost() {
        return host;
    }
}
