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
package org.apache.flink.agents.examples.agents;

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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * An agent that generates embeddings for each row of data using Ollama embedding models.
 *
 * <p>This agent receives text data, processes it to generate high-dimensional vector embeddings,
 * and outputs the results with metadata. It demonstrates how to integrate embedding models into
 * Flink Agents workflows for vector-based processing and similarity search applications.
 *
 * <p>The agent supports various embedding models available in Ollama such as: - nomic-embed-text
 * (768 dimensions) - mxbai-embed-large (1024 dimensions) - all-minilm (384 dimensions)
 */
public class EmbeddingsAgent extends Agent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @EmbeddingModelConnection
    public static ResourceDescriptor ollamaEmbeddingConnection() {
        return ResourceDescriptor.Builder.newBuilder(OllamaEmbeddingModelConnection.class.getName())
                .addInitialArgument("host", "http://localhost:11434")
                .addInitialArgument("timeout", 60)
                .addInitialArgument("model", "nomic-embed-text")
                .build();
    }

    @EmbeddingModelSetup
    public static ResourceDescriptor embeddingModel() {
        return ResourceDescriptor.Builder.newBuilder(OllamaEmbeddingModelSetup.class.getName())
                .addInitialArgument("connection", "ollamaEmbeddingConnection")
                .addInitialArgument("model", "nomic-embed-text")
                .build();
    }

    /**
     * Tool for storing embeddings in a vector database.
     *
     * @param id The unique identifier for the text data
     * @param text The original text content
     * @param embedding The generated embedding vector
     * @param dimension The dimension of the embedding vector
     */
    @Tool(
            description =
                    "Store embeddings in a vector database for similarity search and retrieval.")
    public static void storeEmbedding(
            @ToolParam(name = "id") String id,
            @ToolParam(name = "text") String text,
            @ToolParam(name = "embedding") float[] embedding,
            @ToolParam(name = "dimension") int dimension) {

        // In a real implementation, this would store in a vector database like Pinecone, Weaviate,
        // etc.
        System.out.printf(
                "Storing embedding: ID=%s, Text='%s...', Dimension=%d%n",
                id, text.substring(0, Math.min(50, text.length())), dimension);
        System.out.printf(
                "Embedding preview: [%.4f, %.4f, %.4f, ...]%n",
                embedding[0], embedding[1], embedding[2]);
    }

    /**
     * Tool for calculating similarity between embeddings.
     *
     * @param embedding1 First embedding vector
     * @param embedding2 Second embedding vector
     * @return Cosine similarity score between -1 and 1
     */
    @Tool(description = "Calculate cosine similarity between two embedding vectors.")
    public static float calculateSimilarity(
            @ToolParam(name = "embedding1") float[] embedding1,
            @ToolParam(name = "embedding2") float[] embedding2) {

        if (embedding1.length != embedding2.length) {
            throw new IllegalArgumentException("Embedding dimensions must match");
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
        System.out.printf("Calculated similarity: %.4f%n", similarity);
        return similarity;
    }

    /** Process input event and generate embeddings for the text data. */
    @Action(listenEvents = {InputEvent.class})
    public static void processInput(InputEvent event, RunnerContext ctx) throws Exception {
        String input = (String) event.getInput();
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Parse input as a text document with optional metadata
        Map<String, Object> inputData;
        try {
            inputData = MAPPER.readValue(input, Map.class);
        } catch (Exception e) {
            // If not JSON, treat as plain text
            inputData = new HashMap<>();
            inputData.put("text", input);
            inputData.put("id", "doc_" + System.currentTimeMillis());
        }

        String text = (String) inputData.get("text");
        String id = (String) inputData.getOrDefault("id", "doc_" + System.currentTimeMillis());

        if (text == null || text.trim().isEmpty()) {
            ctx.sendEvent(
                    new OutputEvent(
                            Map.of("error", "No text content found in input", "input", input)));
            return;
        }

        // Store data in short-term memory for later use
        ctx.getShortTermMemory().set("id", id);
        ctx.getShortTermMemory().set("text", text);
        ctx.getShortTermMemory().set("originalInput", inputData);

        try {
            // Use the actual Ollama embedding model directly
            float[] embedding = generateRealEmbedding(text);
            int dimension = embedding.length;

            // Store the embedding using the tool
            storeEmbedding(id, text, embedding, dimension);

            // Create output with embedding results
            Map<String, Object> result = new HashMap<>();
            result.put("id", id);
            result.put("text", text);
            result.put("embedding", embedding);
            result.put("dimension", dimension);
            result.put("embeddingPreview", Arrays.copyOf(embedding, Math.min(5, embedding.length)));
            result.put("metadata", inputData);
            result.put("timestamp", System.currentTimeMillis());

            // Calculate some statistics
            float norm = 0.0f;
            for (float value : embedding) {
                norm += value * value;
            }
            result.put("norm", Math.sqrt(norm));

            ctx.sendEvent(new OutputEvent(result));

            System.out.printf(
                    "Generated embedding for text: '%s' (ID: %s, Dimension: %d)%n",
                    text.substring(0, Math.min(100, text.length())), id, dimension);

        } catch (Exception e) {
            System.err.printf(
                    "Error generating embedding for text '%s': %s%n", text, e.getMessage());
            ctx.sendEvent(
                    new OutputEvent(
                            Map.of(
                                    "error", "Failed to generate embedding: " + e.getMessage(),
                                    "id", id,
                                    "text", text)));
        }
    }

    /**
     * Generate real embeddings using Ollama embedding model directly. This bypasses the framework
     * resource system and creates a direct connection.
     */
    private static float[] generateRealEmbedding(String text) {
        try {
            // Create Ollama connection directly using the same configuration as the resource
            // descriptor
            OllamaEmbeddingModelConnection connection =
                    new OllamaEmbeddingModelConnection(ollamaEmbeddingConnection(), null);

            // Generate the embedding using the real Ollama model
            float[] embedding = connection.embed(text);

            System.out.printf("Generated Ollama embedding with dimension: %d%n", embedding.length);
            return embedding;

        } catch (Exception e) {
            System.err.printf(
                    "FAILED to generate real embedding for text '%s': %s%n",
                    text.substring(0, Math.min(50, text.length())), e.getMessage());
            e.printStackTrace();
            // Re-throw the exception instead of falling back to mock
            throw new RuntimeException("Ollama embedding generation failed", e);
        }
    }

    /** Data class for structured text input with metadata. */
    public static class TextDocument {
        private String id;
        private String text;
        private String category;
        private Map<String, Object> metadata;

        // Constructors
        public TextDocument() {}

        public TextDocument(String id, String text, String category, Map<String, Object> metadata) {
            this.id = id;
            this.text = text;
            this.category = category;
            this.metadata = metadata;
        }

        // Getters and setters
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
        }
    }

    /** Data class for embedding results. */
    public static class EmbeddingResult {
        private String id;
        private String text;
        private float[] embedding;
        private int dimension;
        private double norm;
        private long timestamp;
        private Map<String, Object> metadata;

        // Constructors
        public EmbeddingResult() {}

        public EmbeddingResult(
                String id,
                String text,
                float[] embedding,
                int dimension,
                double norm,
                long timestamp,
                Map<String, Object> metadata) {
            this.id = id;
            this.text = text;
            this.embedding = embedding;
            this.dimension = dimension;
            this.norm = norm;
            this.timestamp = timestamp;
            this.metadata = metadata;
        }

        // Getters and setters
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public float[] getEmbedding() {
            return embedding;
        }

        public void setEmbedding(float[] embedding) {
            this.embedding = embedding;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }

        public double getNorm() {
            return norm;
        }

        public void setNorm(double norm) {
            this.norm = norm;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
        }
    }
}
