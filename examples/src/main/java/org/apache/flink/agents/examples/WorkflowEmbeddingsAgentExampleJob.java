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
package org.apache.flink.agents.examples;

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.examples.agents.EmbeddingsAgent;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.source.SourceFunction;

/**
 * Example workflow demonstrating how to use the WorkflowEmbeddingsAgentExample to generate
 * embeddings for streaming text data.
 *
 * <p>This example shows: 1. Setting up a Flink streaming job with the embedding agent 2. Processing
 * text documents to generate vector embeddings 3. Handling both structured and unstructured text
 * input 4. Monitoring embedding generation results
 *
 * <p>Prerequisites: - Ollama server running on localhost:11434 - nomic-embed-text model available:
 * `ollama pull nomic-embed-text`
 */
public class WorkflowEmbeddingsAgentExampleJob {

    /** Sample text documents for embedding generation. */
    private static final String[] SAMPLE_DOCUMENTS = {
        "Apache Flink is a framework and distributed processing engine for stateful computations over unbounded and bounded data streams.",
        "Machine learning algorithms can learn patterns from data and make predictions on new, unseen data.",
        "Vector embeddings capture semantic meaning of text in high-dimensional numerical representations.",
        "Natural language processing enables computers to understand, interpret, and generate human language.",
        "Deep learning uses neural networks with multiple layers to model and understand complex patterns.",
        "Retrieval-Augmented Generation combines information retrieval with text generation for better AI responses.",
        "Semantic search uses vector similarity to find relevant documents based on meaning rather than keywords.",
        "Large language models are trained on vast amounts of text data to understand and generate human-like text.",
        "Data streaming allows real-time processing of continuous data flows in distributed systems.",
        "Artificial intelligence systems can process and analyze large volumes of data to extract insights."
    };

    /** Custom source function that generates sample text documents. */
    public static class SampleTextSource implements SourceFunction<String> {
        private volatile boolean running = true;
        private int documentIndex = 0;

        @Override
        public void run(SourceContext<String> ctx) throws Exception {
            while (running) {
                // Send structured JSON documents
                if (documentIndex < SAMPLE_DOCUMENTS.length) {
                    String document = SAMPLE_DOCUMENTS[documentIndex];
                    String jsonDoc =
                            String.format(
                                    "{\"id\": \"doc_%d\", \"text\": \"%s\", \"category\": \"tech\", \"timestamp\": %d}",
                                    documentIndex + 1,
                                    document.replace("\"", "\\\""),
                                    System.currentTimeMillis());
                    ctx.collect(jsonDoc);
                    documentIndex++;
                } else {
                    // Send some plain text documents
                    String plainText =
                            "This is a plain text document number "
                                    + (documentIndex - SAMPLE_DOCUMENTS.length + 1)
                                    + " for embedding generation testing.";
                    ctx.collect(plainText);
                    documentIndex++;

                    if (documentIndex > SAMPLE_DOCUMENTS.length + 5) {
                        running = false; // Stop after processing all documents
                    }
                }

                // Wait 2 seconds between documents
                Thread.sleep(2000);
            }
        }

        @Override
        public void cancel() {
            running = false;
        }
    }

    public static void main(String[] args) throws Exception {
        // Set up Flink execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1); // Use single parallelism for deterministic processing

        // Set up Agents execution environment
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // Create data stream of text documents
        DataStream<String> textStream =
                env.addSource(new SampleTextSource()).name("Sample Text Source");

        // Process with embedding agent using the correct pattern
        DataStream<Object> embeddingResults =
                agentsEnv
                        .fromDataStream(textStream)
                        .apply(new EmbeddingsAgent())
                        .toDataStream();

        // Print results with detailed information
        embeddingResults
                .map(
                        event -> {
                            if (event instanceof org.apache.flink.agents.api.OutputEvent) {
                                org.apache.flink.agents.api.OutputEvent outputEvent =
                                        (org.apache.flink.agents.api.OutputEvent) event;
                                Object payload = outputEvent.getOutput();

                                if (payload instanceof java.util.Map) {
                                    @SuppressWarnings("unchecked")
                                    java.util.Map<String, Object> result =
                                            (java.util.Map<String, Object>) payload;

                                    if (result.containsKey("error")) {
                                        return String.format("ERROR: %s", result.get("error"));
                                    } else {
                                        return String.format(
                                                "EMBEDDING GENERATED - ID: %s, Dimension: %s, Norm: %.4f, Text: '%.100s...'",
                                                result.get("id"),
                                                result.get("dimension"),
                                                result.get("norm"),
                                                result.get("text"));
                                    }
                                }
                            }
                            return "Processed: " + event.toString();
                        })
                .print()
                .name("Print Results");

        // Execute the Flink job
        env.execute("Workflow Embeddings Agent Example");
    }
}
