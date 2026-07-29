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
import org.apache.flink.agents.api.RetryExecutor;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelConnection;
import org.apache.flink.agents.api.embedding.model.EmbeddingResult;
import org.apache.flink.agents.api.embedding.model.EmbeddingTokenUsage;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
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

    private final BedrockRuntimeClient client;
    private final String defaultModel;
    private final ExecutorService embedPool;
    private final RetryExecutor retryExecutor;

    public BedrockEmbeddingModelConnection(
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.client = createClient(resolveRegion(descriptor));
        this.defaultModel = resolveDefaultModel(descriptor);
        this.embedPool = Executors.newFixedThreadPool(resolveEmbedConcurrency(descriptor));
        this.retryExecutor = createRetryExecutor(descriptor);
    }

    BedrockEmbeddingModelConnection(
            ResourceDescriptor descriptor,
            ResourceContext resourceContext,
            BedrockRuntimeClient client,
            ExecutorService embedPool,
            RetryExecutor retryExecutor,
            String defaultModel) {
        super(descriptor, resourceContext);
        this.client = client;
        this.defaultModel = defaultModel;
        this.embedPool = embedPool;
        this.retryExecutor = retryExecutor;
    }

    private static BedrockRuntimeClient createClient(String region) {
        return BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    private static String resolveRegion(ResourceDescriptor descriptor) {
        String region = descriptor.getArgument("region");
        if (region == null || region.isBlank()) {
            region = "us-east-1";
        }
        return region;
    }

    private static String resolveDefaultModel(ResourceDescriptor descriptor) {
        String model = descriptor.getArgument("model");
        return (model != null && !model.isBlank()) ? model : DEFAULT_MODEL;
    }

    private static int resolveEmbedConcurrency(ResourceDescriptor descriptor) {
        Integer concurrency = descriptor.getArgument("embed_concurrency");
        return concurrency != null ? concurrency : 4;
    }

    private static RetryExecutor createRetryExecutor(ResourceDescriptor descriptor) {
        Integer retries = descriptor.getArgument("max_retries");
        return RetryExecutor.builder()
                .maxRetries(retries != null ? retries : 5)
                .initialBackoffMs(200)
                .retryablePredicate(BedrockEmbeddingModelConnection::isRetryable)
                .build();
    }

    @Override
    public float[] embed(String text, Map<String, Object> parameters) {
        return embedWithUsage(text, parameters).getEmbeddings();
    }

    @Override
    public EmbeddingResult<float[]> embedWithUsage(String text, Map<String, Object> parameters) {
        String model = (String) parameters.getOrDefault("model", defaultModel);
        Integer dimensions = (Integer) parameters.get("dimensions");

        ObjectNode body = MAPPER.createObjectNode();
        body.put("inputText", text);
        if (dimensions != null) {
            body.put("dimensions", dimensions);
        }
        body.put("normalize", true);

        InvokeModelResponse response =
                retryExecutor.execute(
                        () ->
                                client.invokeModel(
                                        InvokeModelRequest.builder()
                                                .modelId(model)
                                                .contentType("application/json")
                                                .body(SdkBytes.fromUtf8String(body.toString()))
                                                .build()),
                        "BedrockEmbed");

        try {
            JsonNode result = MAPPER.readTree(response.body().asUtf8String());
            JsonNode embeddingNode = result.get("embedding");
            float[] embedding = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                embedding[i] = (float) embeddingNode.get(i).asDouble();
            }
            return new EmbeddingResult<>(embedding, extractTokenUsage(result));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Bedrock embedding response.", e);
        }
    }

    private static EmbeddingTokenUsage extractTokenUsage(JsonNode result) {
        JsonNode inputTokenCount = result.get("inputTextTokenCount");
        if (inputTokenCount == null || !inputTokenCount.isNumber()) {
            return null;
        }
        long tokens = inputTokenCount.asLong();
        return new EmbeddingTokenUsage(tokens, tokens);
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
        return embedWithUsage(texts, parameters).getEmbeddings();
    }

    @Override
    public EmbeddingResult<List<float[]>> embedWithUsage(
            List<String> texts, Map<String, Object> parameters) {
        if (texts.size() <= 1) {
            List<float[]> results = new ArrayList<>(texts.size());
            EmbeddingTokenUsage totalUsage = null;
            for (String text : texts) {
                EmbeddingResult<float[]> result = embedWithUsage(text, parameters);
                results.add(result.getEmbeddings());
                totalUsage = mergeUsage(totalUsage, result.getTokenUsage());
            }
            return new EmbeddingResult<>(results, totalUsage);
        }
        @SuppressWarnings("unchecked")
        CompletableFuture<EmbeddingResult<float[]>>[] futures =
                texts.stream()
                        .map(
                                text ->
                                        CompletableFuture.supplyAsync(
                                                () -> embedWithUsage(text, parameters), embedPool))
                        .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).join();
        List<float[]> results = new ArrayList<>(texts.size());
        EmbeddingTokenUsage totalUsage = null;
        for (CompletableFuture<EmbeddingResult<float[]>> f : futures) {
            EmbeddingResult<float[]> result = f.join();
            results.add(result.getEmbeddings());
            totalUsage = mergeUsage(totalUsage, result.getTokenUsage());
        }
        return new EmbeddingResult<>(results, totalUsage);
    }

    private static EmbeddingTokenUsage mergeUsage(
            EmbeddingTokenUsage left, EmbeddingTokenUsage right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return new EmbeddingTokenUsage(
                left.getPromptTokens() + right.getPromptTokens(),
                left.getTotalTokens() + right.getTotalTokens());
    }

    @Override
    public void close() throws Exception {
        this.embedPool.shutdown();
        this.client.close();
    }
}
