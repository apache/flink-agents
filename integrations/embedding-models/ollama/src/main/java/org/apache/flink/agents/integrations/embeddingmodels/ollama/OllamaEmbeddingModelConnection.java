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

import io.github.ollama4j.Ollama;
import io.github.ollama4j.exceptions.OllamaException;
import io.github.ollama4j.models.embed.OllamaEmbedRequest;
import io.github.ollama4j.models.embed.OllamaEmbedResult;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelConnection;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;

import java.util.*;
import java.util.function.BiFunction;

/** An embedding model integration for Ollama powered by the ollama4j client. */
public class OllamaEmbeddingModelConnection extends BaseEmbeddingModelConnection {

    private final Ollama ollamaAPI;
    private final String defaultModel;

    public OllamaEmbeddingModelConnection(
            ResourceDescriptor descriptor, BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
        String host =
                descriptor.getArgument("host") != null
                        ? descriptor.getArgument("host")
                        : "http://localhost:11434";
        this.defaultModel =
                descriptor.getArgument("model") != null
                        ? descriptor.getArgument("model")
                        : "nomic-embed-text";

        this.ollamaAPI = new Ollama(host);
    }

    @Override
    public float[] embed(String text, Map<String, Object> parameters) {
        String model = (String) parameters.getOrDefault("model", defaultModel);

        try {
            // Pull the model if needed
            ollamaAPI.pullModel(model);

            // Create embedding request with the input text
            OllamaEmbedRequest requestModel =
                    new OllamaEmbedRequest(model, Collections.singletonList(text));

            // Get embeddings from Ollama
            OllamaEmbedResult response = ollamaAPI.embed(requestModel);

            // Extract the first (and only) embedding from the response
            List<List<Double>> embeddings = response.getEmbeddings();
            if (embeddings == null || embeddings.isEmpty()) {
                throw new RuntimeException("No embeddings returned from Ollama for text: " + text);
            }

            List<Double> embedding = embeddings.get(0);

            // Convert to float array
            float[] result = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                result[i] = embedding.get(i).floatValue();
            }

            return result;

        } catch (OllamaException e) {
            throw new RuntimeException("Error generating embeddings for text: " + text, e);
        }
    }

    @Override
    public List<float[]> embed(List<String> texts, Map<String, Object> parameters) {
        String model = (String) parameters.getOrDefault("model", defaultModel);

        try {
            // Pull the model if needed
            ollamaAPI.pullModel(model);

            // Create embedding request with all input texts
            OllamaEmbedRequest requestModel = new OllamaEmbedRequest(model, texts);

            // Get embeddings from Ollama
            OllamaEmbedResult response = ollamaAPI.embed(requestModel);

            // Extract embeddings from the response
            List<List<Double>> embeddings = response.getEmbeddings();
            if (embeddings == null || embeddings.size() != texts.size()) {
                throw new RuntimeException("Mismatch between input texts and returned embeddings");
            }

            // Convert to float arrays
            List<float[]> results = new ArrayList<>();
            for (List<Double> embedding : embeddings) {
                float[] result = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    result[i] = embedding.get(i).floatValue();
                }
                results.add(result);
            }

            return results;

        } catch (OllamaException e) {
            throw new RuntimeException("Error generating embeddings for texts", e);
        }
    }
}
