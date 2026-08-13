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

package org.apache.flink.agents.integrations.vectorstores.elasticsearch;

import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelSetup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.vectorstores.BaseVectorStore;
import org.apache.flink.agents.api.vectorstores.CollectionManageableVectorStore;
import org.apache.flink.agents.api.vectorstores.Document;
import org.apache.flink.agents.api.vectorstores.VectorStoreQuery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Test for {@link ElasticsearchVectorStore}
 *
 * <p>Need setup Elasticsearch server to run this test. Look <a
 * href="https://www.elastic.co/docs/deploy-manage/deploy/self-managed/install-elasticsearch-docker-basic">Start
 * a single-node cluster in Docker</a> for details.
 *
 * <p>For {@link ElasticsearchVectorStore} doesn't support security check yet, when start the
 * container, should add "-e xpack.security.enabled=false" option.
 */
@EnabledIfEnvironmentVariable(named = "ES_HOST", matches = ".+")
public class ElasticsearchVectorStoreTest {
    public static BaseVectorStore store;

    public static Resource getResource(String name, ResourceType type) {
        BaseEmbeddingModelSetup embeddingModel = Mockito.mock(BaseEmbeddingModelSetup.class);
        Mockito.when(embeddingModel.embed("Elasticsearch is a search engine"))
                .thenReturn(new float[] {0.2f, 0.3f, 0.4f, 0.5f, 0.6f});
        Mockito.when(
                        embeddingModel.embed(
                                "Apache Flink Agents is an Agentic AI framework based on Apache Flink."))
                .thenReturn(new float[] {0.1f, 0.2f, 0.3f, 0.4f, 0.5f});
        return embeddingModel;
    }

    @BeforeAll
    public static void initialize() throws Exception {
        final ResourceDescriptor.Builder builder =
                ResourceDescriptor.Builder.newBuilder(ElasticsearchVectorStore.class.getName())
                        .addInitialArgument("embedding_model", "embeddingModel")
                        .addInitialArgument("host", "localhost:9200")
                        .addInitialArgument("dims", 5)
                        .addInitialArgument("username", "elastic")
                        .addInitialArgument("password", System.getenv("ES_PASSWORD"));
        store =
                new ElasticsearchVectorStore(
                        builder.build(),
                        ResourceContext.fromGetResource(ElasticsearchVectorStoreTest::getResource));
        store.open();
    }

    @Test
    public void testCollectionManagement() throws Exception {
        CollectionManageableVectorStore vectorStore = (CollectionManageableVectorStore) store;
        String name = "collection_management";
        // ES backend ignores any metadata in kwargs (no native index-metadata support).
        vectorStore.createCollectionIfNotExists(name, Map.of());

        // The collection (index) is created and empty.
        List<Document> empty = store.get(null, name, null, null, Collections.emptyMap());
        Assertions.assertTrue(empty.isEmpty());

        vectorStore.deleteCollection(name);

        // Querying a deleted collection should fail.
        Assertions.assertThrows(
                Exception.class, () -> store.get(null, name, null, null, Collections.emptyMap()));
    }

    @Test
    public void testDocumentManagement() throws Exception {
        String name = "document_management";
        ((CollectionManageableVectorStore) store).createCollectionIfNotExists(name, Map.of());

        List<Document> documents = new ArrayList<>();
        documents.add(
                new Document(
                        "Elasticsearch is a search engine",
                        Map.of("category", "database", "source", "test"),
                        "doc1"));
        documents.add(
                new Document(
                        "Apache Flink Agents is an Agentic AI framework based on Apache Flink.",
                        Map.of("category", "ai-agent", "source", "test"),
                        "doc2"));
        store.add(documents, name, Collections.emptyMap());
        // wait for the documents to become visible in the elasticsearch server.
        Thread.sleep(1000);
        for (Document doc : documents) {
            doc.setEmbedding(null);
        }

        // test get all documents
        List<Document> all = store.get(null, name, null, null, Collections.emptyMap());
        Assertions.assertEquals(2, all.size());
        Assertions.assertEquals(documents.get(0).getId(), all.get(0).getId());
        Assertions.assertEquals(documents.get(0).getContent(), all.get(0).getContent());
        Assertions.assertEquals(documents.get(0).getMetadata(), all.get(0).getMetadata());
        Assertions.assertEquals(documents.get(1).getId(), all.get(1).getId());
        Assertions.assertEquals(documents.get(1).getContent(), all.get(1).getContent());
        Assertions.assertEquals(documents.get(1).getMetadata(), all.get(1).getMetadata());

        // test get specific document
        List<Document> specific =
                store.get(
                        Collections.singletonList("doc1"),
                        name,
                        null,
                        null,
                        Collections.emptyMap());
        Assertions.assertEquals(1, specific.size());
        Assertions.assertEquals(documents.get(0).getId(), specific.get(0).getId());
        Assertions.assertEquals(documents.get(0).getContent(), specific.get(0).getContent());
        Assertions.assertEquals(documents.get(0).getMetadata(), specific.get(0).getMetadata());

        // test delete specific document
        store.delete(Collections.singletonList("doc1"), name, null, Collections.emptyMap());
        Thread.sleep(1000);
        List<Document> remain = store.get(null, name, null, null, Collections.emptyMap());
        Assertions.assertEquals(1, remain.size());
        Assertions.assertEquals(documents.get(1).getId(), remain.get(0).getId());
        Assertions.assertEquals(documents.get(1).getContent(), remain.get(0).getContent());
        Assertions.assertEquals(documents.get(1).getMetadata(), remain.get(0).getMetadata());

        // test delete all documents
        store.delete(null, name, null, Collections.emptyMap());
        Thread.sleep(1000);
        List<Document> empty = store.get(null, name, null, null, Collections.emptyMap());
        Assertions.assertTrue(empty.isEmpty());

        ((CollectionManageableVectorStore) store).deleteCollection(name);
    }

    @Test
    public void testFiltersDsl() throws Exception {
        // Verify the unified equality-only filters DSL gets translated into ES bool/must term
        // clauses against metadata.<key>.keyword. Two docs go in with different metadata; get
        // and queryEmbedding with filters must each return only the matching doc.
        String name = "filters_dsl";
        ((CollectionManageableVectorStore) store).createCollectionIfNotExists(name, Map.of());

        List<Document> docs = new ArrayList<>();
        docs.add(
                new Document(
                        "Elasticsearch is a search engine",
                        Map.of("category", "database", "user_id", "alice"),
                        "doc_alice"));
        docs.add(
                new Document(
                        "Apache Flink Agents is an Agentic AI framework based on Apache Flink.",
                        Map.of("category", "ai-agent", "user_id", "bob"),
                        "doc_bob"));
        store.add(docs, name, Collections.emptyMap());
        Thread.sleep(1000);

        List<Document> aliceOnly =
                store.get(null, name, Map.of("user_id", "alice"), null, Collections.emptyMap());
        Assertions.assertEquals(1, aliceOnly.size());
        Assertions.assertEquals("doc_alice", aliceOnly.get(0).getId());

        // queryEmbedding with the same filter should also restrict to alice.
        List<Document> aliceQueried =
                store.queryEmbedding(
                        new float[] {0.2f, 0.3f, 0.4f, 0.5f, 0.6f},
                        5,
                        name,
                        Map.of("user_id", "alice"),
                        Collections.emptyMap());
        Assertions.assertFalse(aliceQueried.isEmpty());
        Assertions.assertTrue(aliceQueried.stream().allMatch(d -> "doc_alice".equals(d.getId())));

        ((CollectionManageableVectorStore) store).deleteCollection(name);
    }

    @Test
    public void testFilteredKnnReturnsMatchingDocumentsOutsideUnfilteredTopK() throws Exception {
        String name = "filtered_knn_top_k";
        ((CollectionManageableVectorStore) store).createCollectionIfNotExists(name, Map.of());

        float[] queryVector = new float[] {1.0f, 0.0f, 0.0f, 0.0f, 0.0f};
        store.add(
                List.of(
                        new Document(
                                "near bob",
                                Map.of("user_id", "bob"),
                                "doc_bob",
                                new float[] {1.0f, 0.0f, 0.0f, 0.0f, 0.0f}),
                        new Document(
                                "near alice",
                                Map.of("user_id", "alice", "tenant", "allowed"),
                                "doc_alice",
                                new float[] {0.9f, 0.1f, 0.0f, 0.0f, 0.0f}),
                        new Document(
                                "far alice",
                                Map.of("user_id", "alice", "tenant", "blocked"),
                                "doc_alice_far",
                                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f})),
                name,
                Collections.emptyMap());

        List<Document> filteredByDsl =
                store.queryEmbedding(
                        queryVector, 1, name, Map.of("user_id", "alice"), Collections.emptyMap());
        Assertions.assertEquals(1, filteredByDsl.size());
        Assertions.assertEquals("doc_alice", filteredByDsl.get(0).getId());

        List<Document> filteredByRawQuery =
                store.queryEmbedding(
                        queryVector,
                        1,
                        name,
                        null,
                        Map.of(
                                "filter_query",
                                "{\"term\":{\"_metadata.user_id.keyword\":\"alice\"}}"));
        Assertions.assertEquals(1, filteredByRawQuery.size());
        Assertions.assertEquals("doc_alice", filteredByRawQuery.get(0).getId());

        List<Document> filteredByBoth =
                store.queryEmbedding(
                        queryVector,
                        1,
                        name,
                        Map.of("user_id", "alice"),
                        Map.of(
                                "filter_query",
                                "{\"term\":{\"_metadata.tenant.keyword\":\"allowed\"}}"));
        Assertions.assertEquals(1, filteredByBoth.size());
        Assertions.assertEquals("doc_alice", filteredByBoth.get(0).getId());

        ((CollectionManageableVectorStore) store).deleteCollection(name);
    }

    @Test
    public void testUpdateOverwritesExistingDocument() throws Exception {
        // ES bulk index is upsert by id — update should rewrite the doc in place.
        String name = "update_overwrite";
        ((CollectionManageableVectorStore) store).createCollectionIfNotExists(name, Map.of());

        Document original =
                new Document(
                        "Elasticsearch is a search engine", Map.of("category", "database"), "doc1");
        store.add(List.of(original), name, Collections.emptyMap());
        Thread.sleep(1000);

        Document rewritten =
                new Document(
                        "Apache Flink Agents is an Agentic AI framework based on Apache Flink.",
                        Map.of("category", "ai-agent"),
                        "doc1");
        store.update(List.of(rewritten), name, Collections.emptyMap());
        Thread.sleep(1000);

        List<Document> after = store.get(List.of("doc1"), name, null, null, Collections.emptyMap());
        Assertions.assertEquals(1, after.size());
        Assertions.assertEquals(
                "Apache Flink Agents is an Agentic AI framework based on Apache Flink.",
                after.get(0).getContent());
        Assertions.assertEquals("ai-agent", after.get(0).getMetadata().get("category"));

        ((CollectionManageableVectorStore) store).deleteCollection(name);
    }

    @Test
    public void testQueryEmbeddingPopulatesScore() throws Exception {
        // KNN hits must come back with a non-null Document.score so callers (e.g. mem0) can
        // surface the relevance score in their output.
        String name = "score_populated";
        ((CollectionManageableVectorStore) store).createCollectionIfNotExists(name, Map.of());

        store.add(
                List.of(
                        new Document(
                                "Elasticsearch is a search engine", Map.of("src", "test"), "doc1"),
                        new Document(
                                "Apache Flink Agents is an Agentic AI framework based on Apache Flink.",
                                Map.of("src", "test"),
                                "doc2")),
                name,
                Collections.emptyMap());
        Thread.sleep(1000);

        VectorStoreQuery q =
                new VectorStoreQuery(
                        "Elasticsearch is a search engine", 5, name, Collections.emptyMap());
        List<Document> hits = store.query(q).getDocuments();
        Assertions.assertFalse(hits.isEmpty());
        Assertions.assertTrue(
                hits.stream().allMatch(d -> d.getScore() != null),
                "Every KNN hit should carry an Elasticsearch _score");

        // mget (get-by-ids) has no relevance score — Document.score must stay null.
        List<Document> byId = store.get(List.of("doc1"), name, null, null, Collections.emptyMap());
        Assertions.assertEquals(1, byId.size());
        Assertions.assertNull(byId.get(0).getScore());

        ((CollectionManageableVectorStore) store).deleteCollection(name);
    }
}
