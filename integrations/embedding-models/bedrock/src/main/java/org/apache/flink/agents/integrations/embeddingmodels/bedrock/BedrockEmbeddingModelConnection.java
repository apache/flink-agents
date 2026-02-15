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

package org.apache.flink.agents.integrations.embeddingmodels.bedrock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelConnection;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;

/**
 * Bedrock embedding model connection using Amazon Titan Text Embeddings V2.
 *
 * <p>Uses the InvokeModel API to generate embeddings. Supports configurable dimensions (256, 512,
 * or 1024) and normalization. Since Titan V2 processes one text per API call, batch embedding is
 * parallelized via a configurable thread pool.
 *
 * <p>Supported connection parameters:
 *
 * <ul>
 *   <li><b>region</b> (optional): AWS region, defaults to us-east-1
 *   <li><b>model</b> (optional): default model ID, defaults to amazon.titan-embed-text-v2:0
 *   <li><b>embed_concurrency</b> (optional): thread pool size for parallel embedding (default: 4)
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * @EmbeddingModelConnection
 * public static ResourceDescriptor bedrockEmbedding() {
 *     return ResourceDescriptor.Builder.newBuilder(BedrockEmbeddingModelConnection.class.getName())
 *             .addInitialArgument("region", "us-east-1")
 *             .addInitialArgument("model", "amazon.titan-embed-text-v2:0")
 *             .addInitialArgument("embed_concurrency", 8)
 *             .build();
 * }
 * }</pre>
 */
public class BedrockEmbeddingModelConnection extends BaseEmbeddingModelConnection {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_MODEL = "amazon.titan-embed-text-v2:0";
    private static final int MAX_RETRIES = 5;

    private final BedrockRuntimeClient client;
    private final String defaultModel;
    private final ExecutorService embedPool;

    public BedrockEmbeddingModelConnection(
            ResourceDescriptor descriptor, BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);

        String region = descriptor.getArgument("region");
        if (region == null || region.isBlank()) {
            region = "us-east-1";
        }

        this.client =
                BedrockRuntimeClient.builder()
                        .region(Region.of(region))
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .build();

        String model = descriptor.getArgument("model");
        this.defaultModel = (model != null && !model.isBlank()) ? model : DEFAULT_MODEL;

        Integer concurrency = descriptor.getArgument("embed_concurrency");
        int threads = concurrency != null ? concurrency : 4;
        this.embedPool = Executors.newFixedThreadPool(threads);
    }

    @Override
    public float[] embed(String text, Map<String, Object> parameters) {
        String model = (String) parameters.getOrDefault("model", defaultModel);
        Integer dimensions = (Integer) parameters.get("dimensions");

        ObjectNode body = MAPPER.createObjectNode();
        body.put("inputText", text);
        if (dimensions != null) {
            body.put("dimensions", dimensions);
        }
        body.put("normalize", true);

        for (int attempt = 0; ; attempt++) {
            try {
                InvokeModelResponse response =
                        client.invokeModel(
                                InvokeModelRequest.builder()
                                        .modelId(model)
                                        .contentType("application/json")
                                        .body(SdkBytes.fromUtf8String(body.toString()))
                                        .build());

                JsonNode result = MAPPER.readTree(response.body().asUtf8String());
                JsonNode embeddingNode = result.get("embedding");
                float[] embedding = new float[embeddingNode.size()];
                for (int i = 0; i < embeddingNode.size(); i++) {
                    embedding[i] = (float) embeddingNode.get(i).asDouble();
                }
                return embedding;
            } catch (Exception e) {
                if (attempt < MAX_RETRIES && isRetryable(e)) {
                    try {
                        long delay =
                                (long) (Math.pow(2, attempt) * 200 * (0.5 + Math.random() * 0.5));
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    throw new RuntimeException("Failed to generate Bedrock embedding.", e);
                }
            }
        }
    }

    private static boolean isRetryable(Exception e) {
        String msg = e.toString();
        return msg.contains("ThrottlingException")
                || msg.contains("ModelErrorException")
                || msg.contains("429")
                || msg.contains("424")
                || msg.contains("503");
    }

    @Override
    public List<float[]> embed(List<String> texts, Map<String, Object> parameters) {
        if (texts.size() <= 1) {
            List<float[]> results = new ArrayList<>(texts.size());
            for (String text : texts) {
                results.add(embed(text, parameters));
            }
            return results;
        }
        @SuppressWarnings("unchecked")
        CompletableFuture<float[]>[] futures =
                texts.stream()
                        .map(
                                text ->
                                        CompletableFuture.supplyAsync(
                                                () -> embed(text, parameters), embedPool))
                        .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).join();
        List<float[]> results = new ArrayList<>(texts.size());
        for (CompletableFuture<float[]> f : futures) {
            results.add(f.join());
        }
        return results;
    }

    @Override
    public void close() throws Exception {
        this.embedPool.shutdown();
        this.client.close();
    }
}
