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
import org.junit.jupiter.api.AfterAll;
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
@EnabledIfEnvironmentVariable(named = "ES_HOST", matches = "https?://.+")
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
        String esHost = System.getenv().getOrDefault("ES_HOST", "http://localhost:9200");
        final ResourceDescriptor.Builder builder =
                ResourceDescriptor.Builder.newBuilder(ElasticsearchVectorStore.class.getName())
                        .addInitialArgument("embedding_model", "embeddingModel")
                        .addInitialArgument("host", esHost)
                        .addInitialArgument("dims", 5)
                        .addInitialArgument("username", "elastic")
                        .addInitialArgument("password", System.getenv("ES_PASSWORD"));
        store =
                new ElasticsearchVectorStore(
                        builder.build(),
                        ResourceContext.fromGetResource(ElasticsearchVectorStoreTest::getResource));
        store.open();
    }

    @AfterAll
    public static void cleanup() throws Exception {
        if (store != null) {
            store.close();
        }
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
        all.forEach(document -> document.setScore(null));
        Assertions.assertEquals(documents, all);

        // test get specific document
        List<Document> specific =
                store.get(
                        Collections.singletonList("doc1"),
                        name,
                        null,
                        null,
                        Collections.emptyMap());
        Assertions.assertEquals(1, specific.size());
        Assertions.assertEquals(documents.get(0), specific.get(0));

        // test delete specific document
        store.delete(Collections.singletonList("doc1"), name, null, Collections.emptyMap());
        Thread.sleep(1000);
        List<Document> remain = store.get(null, name, null, null, Collections.emptyMap());
        remain.forEach(document -> document.setScore(null));
        Assertions.assertEquals(1, remain.size());
        Assertions.assertEquals(documents.get(1), remain.get(0));

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
    public void testQueryEmbeddingFiltersBeforeSelectingNeighbors() throws Exception {
        // Contract: filters restrict the candidate set of the KNN search itself, so a matching
        // document is returned even when it is not among the k nearest vectors overall. Applying
        // the filter after the KNN phase instead would return nothing here, because the k nearest
        // vectors all belong to the other user.
        String name = "knn_prefilter";
        ((CollectionManageableVectorStore) store).createCollectionIfNotExists(name, Map.of());

        List<Document> docs = new ArrayList<>();
        // Six documents pointing the same way as the query vector, none of them alice's.
        for (int i = 0; i < 6; i++) {
            Document bob =
                    new Document("bob document " + i, Map.of("user_id", "bob"), "doc_bob_" + i);
            bob.setEmbedding(new float[] {1.0f, 0.0f, 0.0f, 0.0f, 0.0f});
            docs.add(bob);
        }
        // Three alice documents pointing orthogonally, so they never make the unfiltered top k.
        for (int i = 0; i < 3; i++) {
            Document alice =
                    new Document(
                            "alice document " + i, Map.of("user_id", "alice"), "doc_alice_" + i);
            alice.setEmbedding(new float[] {0.0f, 0.0f, 0.0f, 0.0f, 1.0f});
            docs.add(alice);
        }
        store.addEmbedding(docs, name, Collections.emptyMap());
        Thread.sleep(1000);

        List<Document> alice =
                store.queryEmbedding(
                        new float[] {1.0f, 0.0f, 0.0f, 0.0f, 0.0f},
                        5,
                        name,
                        Map.of("user_id", "alice"),
                        Collections.emptyMap());

        Assertions.assertEquals(3, alice.size());
        Assertions.assertTrue(alice.stream().allMatch(d -> d.getId().startsWith("doc_alice_")));

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
