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
package org.apache.flink.agents.integration.test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.EmbeddingModelConnection;
import org.apache.flink.agents.api.annotation.EmbeddingModelSetup;
import org.apache.flink.agents.api.annotation.Tool;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.integrations.embeddingmodels.ollama.OllamaEmbeddingModelConnection;
import org.apache.flink.agents.integrations.embeddingmodels.ollama.OllamaEmbeddingModelSetup;

import java.util.HashMap;
import java.util.Map;

/**
 * Integration test agent for verifying embedding functionality with Ollama models.
 *
 * <p>This test agent validates: - Ollama embedding model integration - Vector generation and
 * processing - Embedding dimension consistency - Tool integration for embedding operations - Error
 * handling in embedding generation
 *
 * <p>Used for e2e testing of the embedding model subsystem.
 */
public class EmbeddingIntegrationAgent extends Agent {
    public static final String OLLAMA_MODEL = "nomic-embed-text";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @EmbeddingModelConnection
    public static ResourceDescriptor embeddingConnection() {
        String provider = System.getProperty("MODEL_PROVIDER", "OLLAMA");
        if (provider.equals("OLLAMA")) {
            return ResourceDescriptor.Builder.newBuilder(
                            OllamaEmbeddingModelConnection.class.getName())
                    .addInitialArgument("host", "http://localhost:11434")
                    .addInitialArgument("timeout", 60)
                    .build();
        } else {
            throw new RuntimeException(String.format("Unknown model provider %s", provider));
        }
    }

    @EmbeddingModelSetup
    public static ResourceDescriptor embeddingModel() {
        String provider = System.getProperty("MODEL_PROVIDER", "OLLAMA");
        if (provider.equals("OLLAMA")) {
            return ResourceDescriptor.Builder.newBuilder(OllamaEmbeddingModelSetup.class.getName())
                    .addInitialArgument("connection", "embeddingConnection")
                    .addInitialArgument("model", OLLAMA_MODEL)
                    .build();
        } else {
            throw new RuntimeException(String.format("Unknown model provider %s", provider));
        }
    }

    /** Test tool for validating embedding storage operations. */
    @Tool(description = "Validate embedding storage for integration testing")
    public static void validateEmbeddingStorage(
            @ToolParam(name = "id") String id,
            @ToolParam(name = "text") String text,
            @ToolParam(name = "embedding") float[] embedding) {

        // Validation assertions for testing
        if (embedding == null || embedding.length == 0) {
            throw new AssertionError("Embedding cannot be null or empty");
        }

        System.out.printf(
                "[TEST] Validated embedding: ID=%s, Dimension=%d, Text='%s...'%n",
                id, embedding.length, text.substring(0, Math.min(30, text.length())));
    }

    /** Test tool for validating similarity calculations. */
    @Tool(description = "Validate similarity calculation for integration testing")
    public static float validateSimilarityCalculation(
            @ToolParam(name = "embedding1") float[] embedding1,
            @ToolParam(name = "embedding2") float[] embedding2) {

        if (embedding1.length != embedding2.length) {
            throw new AssertionError("Embedding dimensions must match for similarity calculation");
        }

        float dotProduct = 0.0f;
        float normA = 0.0f;
        float normB = 0.0f;

        for (int i = 0; i < embedding1.length; i++) {
            dotProduct += embedding1[i] * embedding2[i];
            normA += embedding1[i] * embedding1[i];
            normB += embedding2[i] * embedding2[i];
        }

        if (normA == 0.0f || normB == 0.0f) {
            return 0.0f;
        }

        float similarity = (float) (dotProduct / (Math.sqrt(normA) * Math.sqrt(normB)));

        // Validate similarity is in expected range
        if (similarity < -1.0f || similarity > 1.0f) {
            throw new AssertionError(
                    String.format("Similarity score out of range [-1,1]: %.4f", similarity));
        }

        System.out.printf("[TEST] Validated similarity calculation: %.4f%n", similarity);
        return similarity;
    }

    /** Main test action that processes input and validates embedding generation. */
    @Action(listenEvents = {InputEvent.class})
    public static void testEmbeddingGeneration(InputEvent event, RunnerContext ctx)
            throws Exception {
        String input = (String) event.getInput();
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Parse test input
        Map<String, Object> inputData;
        try {
            inputData = MAPPER.readValue(input, Map.class);
        } catch (Exception e) {
            inputData = new HashMap<>();
            inputData.put("text", input);
            inputData.put("id", "test_doc_" + System.currentTimeMillis());
        }

        String text = (String) inputData.get("text");
        String id = (String) inputData.getOrDefault("id", "test_doc_" + System.currentTimeMillis());

        if (text == null || text.trim().isEmpty()) {
            throw new AssertionError("Test input must contain valid text");
        }

        // Store test data in memory
        ctx.getShortTermMemory().set("test_id", id);
        ctx.getShortTermMemory().set("test_text", text);

        try {
            // Generate embedding using Ollama
            float[] embedding = generateEmbeddingForTest(text, ctx);

            // Test the validation tool
            validateEmbeddingStorage(id, text, embedding);

            // Test similarity calculation with itself (should be 1.0)
            float selfSimilarity = validateSimilarityCalculation(embedding, embedding);
            if (Math.abs(selfSimilarity - 1.0f) > 0.001f) {
                throw new AssertionError(
                        String.format("Self-similarity should be 1.0, got %.4f", selfSimilarity));
            }

            // Create a minimal test result to avoid serialization issues
            Map<String, Object> testResult = new HashMap<>();
            testResult.put("test_status", "PASSED");
            testResult.put("id", id);
            testResult.put("dimension", Integer.valueOf(embedding.length));
            testResult.put("self_similarity", Float.valueOf(selfSimilarity));

            ctx.sendEvent(new OutputEvent(testResult));

            System.out.printf(
                    "[TEST] Embedding generation test PASSED for: '%s'%n",
                    text.substring(0, Math.min(50, text.length())));

        } catch (Exception e) {
            // Create minimal error result
            Map<String, Object> testResult = new HashMap<>();
            testResult.put("test_status", "FAILED");
            testResult.put("error", e.getMessage());
            testResult.put("id", id);

            ctx.sendEvent(new OutputEvent(testResult));

            System.err.printf("[TEST] Embedding generation test FAILED: %s%n", e.getMessage());
            throw e; // Re-throw for test failure reporting
        }
    }

    /** Generate embedding using the framework's resource system for testing. */
    private static float[] generateEmbeddingForTest(String text, RunnerContext ctx) {
        try {
            OllamaEmbeddingModelSetup embeddingModel =
                    (OllamaEmbeddingModelSetup)
                            ctx.getResource(
                                    "embeddingModel",
                                    org.apache.flink.agents.api.resource.ResourceType
                                            .EMBEDDING_MODEL);

            float[] embedding = embeddingModel.embed(text);
            System.out.printf("[TEST] Generated embedding with dimension: %d%n", embedding.length);
            return embedding;

        } catch (Exception e) {
            System.err.printf("[TEST] Failed to generate embedding: %s%n", e.getMessage());
            throw new RuntimeException("Embedding generation test failed", e);
        }
    }

    /** Calculate the L2 norm of an embedding vector. */
    private static float calculateNorm(float[] embedding) {
        float norm = 0.0f;
        for (float value : embedding) {
            norm += value * value;
        }
        return (float) Math.sqrt(norm);
    }
}
