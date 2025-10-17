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

import io.github.ollama4j.OllamaAPI;
import io.github.ollama4j.exceptions.OllamaBaseException;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelConnection;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        this.defaultModel =
                descriptor.getArgument("model") != null
                        ? descriptor.getArgument("model")
                        : "nomic-embed-text";

        this.ollamaAPI = new OllamaAPI(host);
    }

    @Override
    public float[] embed(String text, Map<String, Object> parameters) {
        String model = (String) parameters.getOrDefault("model", defaultModel);
        return embedSingle(text, model);
    }

    @Override
    public List<float[]> embed(List<String> texts, Map<String, Object> parameters) {
        String model = (String) parameters.getOrDefault("model", defaultModel);
        return embedBatch(texts, model);
    }

    private float[] embedSingle(String text, String model) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Text cannot be null or empty");
        }

        try {
            List<Double> embeddings = ollamaAPI.generateEmbeddings(model, text);

            if (embeddings == null || embeddings.isEmpty()) {
                throw new RuntimeException(
                        "Received empty embeddings from Ollama for model: " + model);
            }

            float[] result = new float[embeddings.size()];
            for (int i = 0; i < embeddings.size(); i++) {
                result[i] = embeddings.get(i).floatValue();
            }

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

    private List<float[]> embedBatch(List<String> texts, String model) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("Texts list cannot be null or empty");
        }

        List<float[]> results = new ArrayList<>();
        for (String text : texts) {
            if (text != null && !text.trim().isEmpty()) {
                results.add(embedSingle(text, model));
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

        try {
            Map<String, Object> testParams = new HashMap<>();
            testParams.put("model", defaultModel);
            float[] testEmbedding = embed("test", testParams);
            cachedDimension = testEmbedding.length;
            return cachedDimension;
        } catch (Exception e) {
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

    /** Check if the specified model is available on the Ollama server. */
    public boolean isModelAvailable(String model) {
        try {
            return ollamaAPI.listModels().stream()
                    .anyMatch(modelInfo -> modelInfo.getName().equals(model));
        } catch (Exception e) {
            try {
                Map<String, Object> testParams = new HashMap<>();
                testParams.put("model", model);
                embed("test", testParams);
                return true;
            } catch (Exception testException) {
                return false;
            }
        }
    }

    /** Get the default embedding model name. */
    public String getDefaultModel() {
        return defaultModel;
    }
}
