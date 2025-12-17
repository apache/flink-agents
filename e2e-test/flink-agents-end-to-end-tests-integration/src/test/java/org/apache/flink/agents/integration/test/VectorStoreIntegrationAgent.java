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

import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.EmbeddingModelConnection;
import org.apache.flink.agents.api.annotation.EmbeddingModelSetup;
import org.apache.flink.agents.api.annotation.VectorStore;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ContextRetrievalRequestEvent;
import org.apache.flink.agents.api.event.ContextRetrievalResponseEvent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.vectorstores.Document;
import org.apache.flink.agents.integrations.embeddingmodels.ollama.OllamaEmbeddingModelConnection;
import org.apache.flink.agents.integrations.embeddingmodels.ollama.OllamaEmbeddingModelSetup;
import org.apache.flink.agents.integrations.vectorstores.elasticsearch.ElasticsearchVectorStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VectorStoreIntegrationAgent extends Agent {
    public static final String OLLAMA_MODEL = "nomic-embed-text";

    @EmbeddingModelConnection
    public static ResourceDescriptor embeddingConnection() {
        final String provider = System.getProperty("MODEL_PROVIDER", "OLLAMA");
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

    @VectorStore
    public static ResourceDescriptor vectorStore() {
        final String provider = System.getProperty("VECTOR_STORE_PROVIDER", "ELASTICSEARCH");
        if (provider.equals("ELASTICSEARCH")) {
            final ResourceDescriptor.Builder builder =
                    ResourceDescriptor.Builder.newBuilder(ElasticsearchVectorStore.class.getName())
                            .addInitialArgument("embedding_model", "embeddingModel")
                            .addInitialArgument("host", System.getenv("ES_HOST"))
                            .addInitialArgument("index", System.getenv("ES_INDEX"))
                            .addInitialArgument("dims", Integer.parseInt(System.getenv("ES_DIMS")))
                            .addInitialArgument("vector_field", System.getenv("ES_VECTOR_FIELD"));

            final String username = System.getenv("ES_USERNAME");
            final String password = System.getenv("ES_PASSWORD");
            if (username != null && password != null) {
                builder.addInitialArgument("username", username)
                        .addInitialArgument("password", password);
            }

            return builder.build();
        } else {
            throw new RuntimeException(String.format("Unknown vector store provider %s", provider));
        }
    }

    @Action(listenEvents = InputEvent.class)
    public static void inputEvent(InputEvent event, RunnerContext ctx) {
        final String input = (String) event.getInput();

        ctx.sendEvent(new ContextRetrievalRequestEvent(input, "vectorStore"));
    }

    @Action(listenEvents = ContextRetrievalResponseEvent.class)
    public static void contextRetrievalResponseEvent(
            ContextRetrievalResponseEvent event, RunnerContext ctx) {
        final List<Document> documents = event.getDocuments();

        Map<String, Object> result = new HashMap<>();
        try {
            // Basic validations similar in spirit to EmbeddingIntegrationAgent
            if (documents == null) {
                throw new AssertionError("Vector store returned null documents list");
            }

            if (documents.isEmpty()) {
                throw new AssertionError("Vector store returned empty documents list");
            }

            int idx = 0;
            for (Document doc : documents) {
                if (doc == null) {
                    throw new AssertionError("Document entry is null");
                }

                final String content = doc.getContent();
                if (content == null || content.trim().isEmpty()) {
                    throw new AssertionError(String.format("Document[%d] content is empty", idx));
                }

                // ID can be optional, but when present it must be non-empty
                if (doc.getId() != null && doc.getId().trim().isEmpty()) {
                    throw new AssertionError(String.format("Document[%d] id is empty string", idx));
                }
                idx++;
            }

            result.put("test_status", "PASSED");
            result.put("retrieved_count", documents.size());
            // Include preview of first doc
            Document first = documents.get(0);
            result.put("first_doc_id", first.getId());
            result.put(
                    "first_doc_preview",
                    first.getContent().substring(0, Math.min(50, first.getContent().length())));

            ctx.sendEvent(new OutputEvent(result));
            System.out.printf("[TEST] Vector store retrieval PASSED, count=%d%n", documents.size());
        } catch (Exception e) {
            result.put("test_status", "FAILED");
            result.put("error", e.getMessage());
            ctx.sendEvent(new OutputEvent(result));
            System.err.printf("[TEST] Vector store retrieval FAILED: %s%n", e.getMessage());
            throw e;
        }
    }
}
