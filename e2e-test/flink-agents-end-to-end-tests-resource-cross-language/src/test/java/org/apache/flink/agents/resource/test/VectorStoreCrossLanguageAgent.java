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

import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.EmbeddingModelConnection;
import org.apache.flink.agents.api.annotation.EmbeddingModelSetup;
import org.apache.flink.agents.api.annotation.VectorStore;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ContextRetrievalRequestEvent;
import org.apache.flink.agents.api.event.ContextRetrievalResponseEvent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.vectorstores.CollectionManageableVectorStore;
import org.apache.flink.agents.api.vectorstores.Document;
import org.apache.flink.agents.api.vectorstores.python.PythonCollectionManageableVectorStore;
import org.junit.jupiter.api.Assertions;
import pemja.core.PythonException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Integration test agent for verifying vector store functionality with Python vector store
 * implementation.
 *
 * <p>This test agent validates: - Python vector store resource registration and creation - Python
 * embedding model integration with vector store - Collection management operations (create, get,
 * delete) - Document CRUD operations (add, get, delete, size) - Context retrieval and similarity
 * search - Cross-language resource dependency (vector store depends on embedding model)
 *
 * <p>Used for e2e testing of the vector store subsystem with cross-language support.
 */
public class VectorStoreCrossLanguageAgent extends Agent {
    public static final String OLLAMA_MODEL = "nomic-embed-text";
    public static final String TEST_COLLECTION = "test_collection";

    @EmbeddingModelConnection
    public static ResourceDescriptor embeddingConnection() {
        if (System.getProperty("EMBEDDING_TYPE", "PYTHON").equals("PYTHON")) {
            return ResourceDescriptor.Builder.newBuilder(
                            ResourceName.EmbeddingModel.PYTHON_WRAPPER_CONNECTION)
                    .addInitialArgument(
                            "pythonClazz", ResourceName.EmbeddingModel.Python.OLLAMA_CONNECTION)
                    .build();
        } else {
            return ResourceDescriptor.Builder.newBuilder(
                            ResourceName.EmbeddingModel.OLLAMA_CONNECTION)
                    .addInitialArgument("host", "http://localhost:11434")
                    .addInitialArgument("timeout", 60)
                    .build();
        }
    }

    @EmbeddingModelSetup
    public static ResourceDescriptor embeddingModel() {
        if (System.getProperty("EMBEDDING_TYPE", "PYTHON").equals("PYTHON")) {
            return ResourceDescriptor.Builder.newBuilder(
                            ResourceName.EmbeddingModel.PYTHON_WRAPPER_SETUP)
                    .addInitialArgument(
                            "pythonClazz", ResourceName.EmbeddingModel.Python.OLLAMA_SETUP)
                    .addInitialArgument("connection", "embeddingConnection")
                    .addInitialArgument("model", OLLAMA_MODEL)
                    .build();
        } else {
            return ResourceDescriptor.Builder.newBuilder(ResourceName.EmbeddingModel.OLLAMA_SETUP)
                    .addInitialArgument("connection", "embeddingConnection")
                    .addInitialArgument("model", OLLAMA_MODEL)
                    .build();
        }
    }

    @VectorStore
    public static ResourceDescriptor vectorStore() {
        return ResourceDescriptor.Builder.newBuilder(
                        ResourceName.VectorStore.PYTHON_WRAPPER_COLLECTION_MANAGEABLE_VECTOR_STORE)
                .addInitialArgument(
                        "pythonClazz", ResourceName.VectorStore.Python.CHROMA_VECTOR_STORE)
                .addInitialArgument("embedding_model", "embeddingModel")
                .build();
    }

    @Action(listenEvents = InputEvent.class)
    public static void inputEvent(InputEvent event, RunnerContext ctx) throws Exception {
        final String input = (String) event.getInput();

        MemoryObject isInitialized = ctx.getShortTermMemory().get("is_initialized");
        if (isInitialized == null) {
            PythonCollectionManageableVectorStore vectorStore =
                    (PythonCollectionManageableVectorStore)
                            ctx.getResource("vectorStore", ResourceType.VECTOR_STORE);

            // Initialize vector store
            vectorStore.getOrCreateCollection(
                    TEST_COLLECTION, Map.of("key1", "value1", "key2", "value2"));

            CollectionManageableVectorStore.Collection collection =
                    vectorStore.getCollection(TEST_COLLECTION);
            Assertions.assertNotEquals(collection, null, "Vector store collection is null");
            Assertions.assertEquals(
                    TEST_COLLECTION,
                    collection.getName(),
                    "Vector store collection name is not test_collection");
            Assertions.assertEquals(
                    Map.of("key1", "value1", "key2", "value2"),
                    collection.getMetadata(),
                    "Vector store collection metadata is not correct");

            System.out.println("[TEST] Vector store Collection Management PASSED");

            vectorStore.deleteCollection(TEST_COLLECTION);
            Assertions.assertThrows(
                    PythonException.class, () -> vectorStore.getCollection(TEST_COLLECTION));

            // Initialize collection
            vectorStore.add(
                    List.of(
                            new Document(
                                    "Apache Flink Agents is an Agentic AI framework based on Apache Flink.",
                                    Map.of("category", "ai-agent", "source", "test"),
                                    "doc1"),
                            new Document(
                                    "ChromaDB is a vector database for AI applications",
                                    Map.of("category", "database", "source", "test"),
                                    "doc2"),
                            new Document(
                                    "This is a test document used to verify the delete functionality.",
                                    Map.of("category", "utility", "source", "test"),
                                    "doc3")),
                    null,
                    Map.of());

            // Test size
            Assertions.assertEquals(3, vectorStore.size(null), "Vector store size is not 3");

            // Test delete
            vectorStore.delete(List.of("doc3"), null, Map.of());
            Assertions.assertEquals(
                    2, vectorStore.size(null), "Vector store size is not 2, doc3 was not deleted");

            // Test get
            Document doc = vectorStore.get(List.of("doc2"), null, Map.of()).get(0);
            Assertions.assertEquals(
                    "ChromaDB is a vector database for AI applications", doc.getContent());
            Assertions.assertEquals(
                    Map.of("category", "database", "source", "test"), doc.getMetadata());

            System.out.println("[TEST] Vector store Document Management PASSED");

            ctx.getShortTermMemory().set("is_initialized", true);
        }

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
