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
package org.apache.flink.agents.resource.test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.EmbeddingModelConnection;
import org.apache.flink.agents.api.annotation.EmbeddingModelSetup;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelSetup;
import org.apache.flink.agents.api.embedding.model.python.PythonEmbeddingModelConnection;
import org.apache.flink.agents.api.embedding.model.python.PythonEmbeddingModelSetup;
import org.apache.flink.agents.api.resource.ResourceDescriptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Integration test agent for verifying embedding functionality with Python embedding model.
 *
 * <p>This test agent validates: - Python embedding model integration - Vector generation and
 * processing - Embedding dimension consistency - Tool integration for embedding operations - Error
 * handling in embedding generation
 *
 * <p>Used for e2e testing of the embedding model subsystem.
 */
public class EmbeddingCrossLanguageAgent extends Agent {
    public static final String OLLAMA_MODEL = "nomic-embed-text";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @EmbeddingModelConnection
    public static ResourceDescriptor embeddingConnection() {
        return ResourceDescriptor.Builder.newBuilder(PythonEmbeddingModelConnection.class.getName())
                .addInitialArgument(
                        "module",
                        "flink_agents.integrations.embedding_models.local.ollama_embedding_model")
                .addInitialArgument("clazz", "OllamaEmbeddingModelConnection")
                .build();
    }

    @EmbeddingModelSetup
    public static ResourceDescriptor embeddingModel() {
        return ResourceDescriptor.Builder.newBuilder(PythonEmbeddingModelSetup.class.getName())
                .addInitialArgument(
                        "module",
                        "flink_agents.integrations.embedding_models.local.ollama_embedding_model")
                .addInitialArgument("clazz", "OllamaEmbeddingModelSetup")
                .addInitialArgument("connection", "embeddingConnection")
                .addInitialArgument("model", OLLAMA_MODEL)
                .build();
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
            BaseEmbeddingModelSetup embeddingModel =
                    (BaseEmbeddingModelSetup)
                            ctx.getResource(
                                    "embeddingModel",
                                    org.apache.flink.agents.api.resource.ResourceType
                                            .EMBEDDING_MODEL);

            float[] embedding = embeddingModel.embed(text);
            System.out.printf("[TEST] Generated embedding with dimension: %d%n", embedding.length);
            validateEmbeddingResult(id, text, embedding);

            List<float[]> embeddings = embeddingModel.embed(List.of(text));
            validateEmbeddingResults(id, List.of(text), embeddings);

            // Create a minimal test result to avoid serialization issues
            Map<String, Object> testResult = new HashMap<>();
            testResult.put("test_status", "PASSED");
            testResult.put("id", id);

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

    /** Validate embedding result. */
    public static void validateEmbeddingResult(String id, String text, float[] embedding) {

        // Validation assertions for testing
        if (embedding == null || embedding.length == 0) {
            throw new AssertionError("Embedding cannot be null or empty");
        }

        System.out.printf(
                "[TEST] Validated embedding: ID=%s, Dimension=%d, Text='%s...'%n",
                id, embedding.length, text.substring(0, Math.min(30, text.length())));
    }

    /** Validate embedding results. */
    public static void validateEmbeddingResults(
            String id, List<String> texts, List<float[]> embeddings) {

        // Validation assertions for testing
        if (embeddings == null || embeddings.isEmpty()) {
            throw new AssertionError("Embedding cannot be null or empty");
        }

        if (texts.size() != embeddings.size()) {
            throw new AssertionError("Text and embedding lists must have the same size");
        }

        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            float[] embedding = embeddings.get(i);
            validateEmbeddingResult(id, text, embedding);
        }
    }
}
