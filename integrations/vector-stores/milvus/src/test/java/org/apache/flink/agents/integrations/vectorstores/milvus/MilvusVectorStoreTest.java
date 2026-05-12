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

package org.apache.flink.agents.integrations.vectorstores.milvus;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.GetLoadStateReq;
import io.milvus.v2.service.collection.request.ReleaseCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.request.DescribeIndexReq;
import io.milvus.v2.service.index.request.ListIndexesReq;
import io.milvus.v2.service.index.response.DescribeIndexResp;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for {@link MilvusVectorStore}. */
public class MilvusVectorStoreTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    @Test
    void testConstructorAndStoreKwargs() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(MilvusVectorStore.class.getName())
                        .addInitialArgument("embedding_model", "embeddingModel")
                        .addInitialArgument("uri", "http://localhost:19530")
                        .addInitialArgument("collection", "test_collection")
                        .addInitialArgument("dims", 5)
                        .addInitialArgument("num_shards", 3)
                        .addInitialArgument("metric_type", "IP")
                        .addInitialArgument("index_type", "IVF_FLAT")
                        .addInitialArgument("metadata_index_keys", List.of("source"))
                        .addInitialArgument("metadata_index_cast_types", Map.of("score", "DOUBLE"))
                        // Keep the read-after-write behavior deterministic in integration tests.
                        .addInitialArgument("consistency_level", "STRONG")
                        .addInitialArgument("load_timeout_ms", 12345L)
                        .build();

        MilvusVectorStore store = new MilvusVectorStore(desc, NOOP);
        Map<String, Object> kwargs = store.getStoreKwargs();
        assertThat(store).isInstanceOf(BaseVectorStore.class);
        assertThat(store).isInstanceOf(CollectionManageableVectorStore.class);
        assertThat(kwargs).containsEntry("collection", "test_collection");
        assertThat(kwargs).containsEntry("index", "test_collection");
        assertThat(kwargs).containsEntry("dims", 5);
        assertThat(kwargs).containsEntry("num_shards", 3);
        assertThat(kwargs).containsEntry("metric_type", "IP");
        assertThat(kwargs).containsEntry("index_type", "IVF_FLAT");
        assertThat((List<String>) kwargs.get("metadata_index_keys"))
                .containsExactly(
                        "user_id", "agent_id", "run_id", "actor_id", "category", "source", "score");
        assertThat((Map<String, String>) kwargs.get("metadata_index_cast_types"))
                .containsEntry("user_id", "VARCHAR")
                .containsEntry("agent_id", "VARCHAR")
                .containsEntry("run_id", "VARCHAR")
                .containsEntry("actor_id", "VARCHAR")
                .containsEntry("category", "VARCHAR")
                .containsEntry("score", "DOUBLE");
        assertThat(kwargs).containsEntry("consistency_level", "STRONG");
        assertThat(kwargs).containsEntry("load_timeout_ms", 12345L);
        assertThat(kwargs).doesNotContainKey("flush_on_write");
        assertThat(kwargs).doesNotContainKey("auto_create_collection");
        assertThat(kwargs).doesNotContainKey("metadata_index_enabled");
        store.close();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MILVUS_URI", matches = ".+")
    void testCreateCollectionUsesShardCount() throws Exception {
        String collection = collectionName("shard_count");
        MilvusVectorStore store = openStore(collection);
        MilvusClientV2 client =
                new MilvusClientV2(
                        ConnectConfig.builder().uri(System.getenv("MILVUS_URI")).build());
        try {
            store.createCollectionIfNotExists(collection, Map.of("num_shards", 2));

            DescribeCollectionResp resp =
                    client.describeCollection(
                            DescribeCollectionReq.builder().collectionName(collection).build());
            Assertions.assertEquals(2, resp.getShardsNum());
        } finally {
            store.deleteCollection(collection);
            store.close();
            client.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MILVUS_URI", matches = ".+")
    void testCreateCollectionUsesMetadataJsonIndex() throws Exception {
        String collection = collectionName("metadata_json_index");
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(MilvusVectorStore.class.getName())
                        .addInitialArgument("embedding_model", "embeddingModel")
                        .addInitialArgument("uri", System.getenv("MILVUS_URI"))
                        .addInitialArgument("collection", collection)
                        .addInitialArgument("dims", 5)
                        .addInitialArgument("index_type", "AUTOINDEX")
                        .addInitialArgument("metric_type", "COSINE")
                        .addInitialArgument("metadata_index_keys", List.of("source"))
                        .addInitialArgument("metadata_index_cast_types", Map.of("score", "DOUBLE"))
                        // Avoid timing-sensitive assertions after insert/update; production
                        // defaults
                        // to BOUNDED unless callers require immediate read-after-write visibility.
                        .addInitialArgument("consistency_level", "STRONG")
                        .build();
        MilvusVectorStore store =
                new MilvusVectorStore(
                        desc, ResourceContext.fromGetResource(MilvusVectorStoreTest::getResource));
        store.open();
        MilvusClientV2 client =
                new MilvusClientV2(
                        ConnectConfig.builder().uri(System.getenv("MILVUS_URI")).build());
        try {
            store.createCollectionIfNotExists(collection, Map.of());

            List<String> indexNames =
                    client.listIndexes(
                            ListIndexesReq.builder()
                                    .collectionName(collection)
                                    .fieldName(MilvusVectorStore.DEFAULT_METADATA_FIELD)
                                    .build());
            assertThat(indexNames).contains(MilvusVectorStore.DEFAULT_METADATA_INDEX_NAME);
            assertThat(indexNames)
                    .contains(
                            metadataPathIndexName("user_id"),
                            metadataPathIndexName("agent_id"),
                            metadataPathIndexName("run_id"),
                            metadataPathIndexName("actor_id"),
                            metadataPathIndexName("category"),
                            metadataPathIndexName("source"),
                            metadataPathIndexName("score"));

            DescribeIndexResp resp =
                    client.describeIndex(
                            DescribeIndexReq.builder()
                                    .collectionName(collection)
                                    .indexName(MilvusVectorStore.DEFAULT_METADATA_INDEX_NAME)
                                    .build());
            DescribeIndexResp.IndexDesc scoreIndex =
                    resp.getIndexDescByIndexName(MilvusVectorStore.DEFAULT_METADATA_INDEX_NAME);
            Assertions.assertEquals(
                    MilvusVectorStore.DEFAULT_METADATA_FIELD, scoreIndex.getFieldName());
            Assertions.assertEquals(IndexParam.IndexType.AUTOINDEX, scoreIndex.getIndexType());
            assertThat(scoreIndex.getExtraParams())
                    .containsEntry("json_path", MilvusVectorStore.DEFAULT_METADATA_FIELD)
                    .containsEntry("json_cast_type", "JSON");

            assertMetadataPathIndex(client, collection, "user_id", "VARCHAR");
            assertMetadataPathIndex(client, collection, "actor_id", "VARCHAR");
            assertMetadataPathIndex(client, collection, "category", "VARCHAR");
            assertMetadataPathIndex(client, collection, "source", "VARCHAR");
            assertMetadataPathIndex(client, collection, "score", "DOUBLE");
        } finally {
            store.deleteCollection(collection);
            store.close();
            client.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MILVUS_URI", matches = ".+")
    void testCollectionManagement() throws Exception {
        String collection = collectionName("collection_management");
        MilvusVectorStore store = openStore(collection);

        try {
            createCollection(store, collection);
            Assertions.assertTrue(
                    store.get(null, collection, null, 10, Collections.emptyMap()).isEmpty());

            store.deleteCollection(collection);

            Assertions.assertThrows(
                    Exception.class,
                    () -> store.get(null, collection, null, 10, Collections.emptyMap()));
        } finally {
            store.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MILVUS_URI", matches = ".+")
    void testCreateCollectionIfNotExistsLoadsReleasedCollection() throws Exception {
        String collection = collectionName("load_released");
        MilvusVectorStore store = openStore(collection);
        MilvusClientV2 client =
                new MilvusClientV2(
                        ConnectConfig.builder().uri(System.getenv("MILVUS_URI")).build());

        try {
            createCollection(store, collection);
            store.add(
                    List.of(
                            new Document(
                                    "Milvus is a vector database",
                                    Map.of("category", "database"),
                                    "doc1")),
                    collection,
                    Collections.emptyMap());

            client.releaseCollection(
                    ReleaseCollectionReq.builder()
                            .collectionName(collection)
                            .async(Boolean.FALSE)
                            .build());
            Assertions.assertFalse(
                    client.getLoadState(
                            GetLoadStateReq.builder().collectionName(collection).build()));

            createCollection(store, collection);
            Assertions.assertTrue(
                    client.getLoadState(
                            GetLoadStateReq.builder().collectionName(collection).build()));

            List<Document> loaded =
                    store.get(List.of("doc1"), collection, null, null, Collections.emptyMap());
            Assertions.assertEquals(1, loaded.size());
            assertDocument(
                    loaded.get(0),
                    "doc1",
                    "Milvus is a vector database",
                    Map.of("category", "database"));
        } finally {
            store.deleteCollection(collection);
            store.close();
            client.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MILVUS_URI", matches = ".+")
    void testDocumentManagement() throws Exception {
        String collection = collectionName("document_management");
        MilvusVectorStore store = openStore(collection);

        try {
            createCollection(store, collection);

            store.add(
                    List.of(
                            new Document(
                                    "Milvus is a vector database",
                                    Map.of("category", "database", "source", "test"),
                                    "doc1"),
                            new Document(
                                    "Apache Flink Agents is an AI framework",
                                    Map.of("category", "ai-agent", "source", "test"),
                                    "doc2")),
                    collection,
                    Collections.emptyMap());

            List<Document> all = store.get(null, collection, null, 10, Collections.emptyMap());
            Assertions.assertEquals(2, all.size());
            assertDocument(
                    documentById(all, "doc1"),
                    "doc1",
                    "Milvus is a vector database",
                    Map.of("category", "database", "source", "test"));
            assertDocument(
                    documentById(all, "doc2"),
                    "doc2",
                    "Apache Flink Agents is an AI framework",
                    Map.of("category", "ai-agent", "source", "test"));

            List<Document> byId =
                    store.get(List.of("doc1"), collection, null, null, Collections.emptyMap());
            Assertions.assertEquals(1, byId.size());
            assertDocument(
                    byId.get(0),
                    "doc1",
                    "Milvus is a vector database",
                    Map.of("category", "database", "source", "test"));

            store.delete(List.of("doc1"), collection, null, Collections.emptyMap());
            List<Document> remaining =
                    store.get(null, collection, null, 10, Collections.emptyMap());
            Assertions.assertEquals(1, remaining.size());
            assertDocument(
                    remaining.get(0),
                    "doc2",
                    "Apache Flink Agents is an AI framework",
                    Map.of("category", "ai-agent", "source", "test"));

            store.delete(null, collection, null, Collections.emptyMap());
            Assertions.assertTrue(
                    store.get(null, collection, null, 10, Collections.emptyMap()).isEmpty());
        } finally {
            store.deleteCollection(collection);
            store.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MILVUS_URI", matches = ".+")
    void testFiltersDsl() throws Exception {
        String collection = collectionName("filters_dsl");
        MilvusVectorStore store = openStore(collection);

        try {
            createCollection(store, collection);

            store.add(
                    List.of(
                            new Document(
                                    "Milvus is a vector database",
                                    Map.of("category", "database", "user_id", "alice"),
                                    "doc_alice"),
                            new Document(
                                    "Apache Flink Agents is an AI framework",
                                    Map.of("category", "ai-agent", "user_id", "bob"),
                                    "doc_bob")),
                    collection,
                    Collections.emptyMap());

            List<Document> aliceOnly =
                    store.get(
                            null,
                            collection,
                            Map.of("user_id", "alice"),
                            10,
                            Collections.emptyMap());
            Assertions.assertEquals(1, aliceOnly.size());
            Assertions.assertEquals("doc_alice", aliceOnly.get(0).getId());

            List<Document> aliceQueried =
                    store.queryEmbedding(
                            new float[] {1.0f, 0.0f, 0.0f, 0.0f, 0.0f},
                            5,
                            collection,
                            Map.of("user_id", "alice"),
                            Collections.emptyMap());
            Assertions.assertFalse(aliceQueried.isEmpty());
            Assertions.assertTrue(
                    aliceQueried.stream().allMatch(d -> "doc_alice".equals(d.getId())));
        } finally {
            store.deleteCollection(collection);
            store.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MILVUS_URI", matches = ".+")
    void testAddGeneratesIdsForDocumentsWithoutIds() throws Exception {
        String collection = collectionName("generated_ids");
        MilvusVectorStore store = openStore(collection);

        try {
            createCollection(store, collection);

            List<String> ids =
                    store.add(
                            List.of(
                                    new Document(
                                            "Milvus is a vector database",
                                            Map.of("category", "database"),
                                            null)),
                            collection,
                            Collections.emptyMap());

            Assertions.assertEquals(1, ids.size());
            Assertions.assertNotNull(ids.get(0));
            Assertions.assertFalse(ids.get(0).isEmpty());

            List<Document> stored =
                    store.get(List.of(ids.get(0)), collection, null, null, Collections.emptyMap());
            Assertions.assertEquals(1, stored.size());
            assertDocument(
                    stored.get(0),
                    ids.get(0),
                    "Milvus is a vector database",
                    Map.of("category", "database"));
        } finally {
            store.deleteCollection(collection);
            store.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MILVUS_URI", matches = ".+")
    void testAddPreservesCallerProvidedId() throws Exception {
        String collection = collectionName("add_with_id");
        MilvusVectorStore store = openStore(collection);

        try {
            createCollection(store, collection);

            Document document =
                    new Document(
                            "Milvus is a vector database", Map.of("category", "database"), "doc1");
            List<String> ids = store.add(List.of(document), collection, Collections.emptyMap());
            Assertions.assertEquals(List.of("doc1"), ids);

            List<Document> stored =
                    store.get(List.of("doc1"), collection, null, null, Collections.emptyMap());
            Assertions.assertEquals(1, stored.size());
            assertDocument(
                    stored.get(0),
                    "doc1",
                    "Milvus is a vector database",
                    Map.of("category", "database"));
        } finally {
            store.deleteCollection(collection);
            store.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MILVUS_URI", matches = ".+")
    void testExtraArgsCollectionDoesNotOverrideTargetCollection() throws Exception {
        String collection = collectionName("target_collection");
        String ignoredCollection = collectionName("ignored_collection");
        MilvusVectorStore store = openStore(collection);

        try {
            createCollection(store, collection);

            List<String> ids =
                    store.add(
                            List.of(
                                    new Document(
                                            "Milvus is a vector database",
                                            Map.of("category", "database"),
                                            "doc1")),
                            null,
                            Map.of("collection", ignoredCollection));
            Assertions.assertEquals(List.of("doc1"), ids);

            List<Document> stored =
                    store.get(List.of("doc1"), collection, null, null, Collections.emptyMap());
            Assertions.assertEquals(1, stored.size());
            assertDocument(
                    stored.get(0),
                    "doc1",
                    "Milvus is a vector database",
                    Map.of("category", "database"));
        } finally {
            dropCollectionQuietly(store, collection);
            dropCollectionQuietly(store, ignoredCollection);
            store.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MILVUS_URI", matches = ".+")
    void testUpdateOverwritesExistingDocument() throws Exception {
        String collection = collectionName("update_overwrite");
        MilvusVectorStore store = openStore(collection);

        try {
            createCollection(store, collection);

            Document original =
                    new Document(
                            "Milvus is a vector database", Map.of("category", "database"), "doc1");
            store.add(List.of(original), collection, Collections.emptyMap());

            Document rewritten =
                    new Document(
                            "Milvus stores dense vectors", Map.of("category", "updated"), "doc1");
            store.update(List.of(rewritten), collection, Collections.emptyMap());

            List<Document> after =
                    store.get(List.of("doc1"), collection, null, null, Collections.emptyMap());
            Assertions.assertEquals(1, after.size());
            Assertions.assertEquals("Milvus stores dense vectors", after.get(0).getContent());
            Assertions.assertEquals("updated", after.get(0).getMetadata().get("category"));
        } finally {
            store.deleteCollection(collection);
            store.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MILVUS_URI", matches = ".+")
    void testQueryEmbeddingPopulatesScore() throws Exception {
        String collection = collectionName("score_populated");
        MilvusVectorStore store = openStore(collection);

        try {
            createCollection(store, collection);

            store.add(
                    List.of(
                            new Document(
                                    "Milvus is a vector database", Map.of("src", "test"), "doc1"),
                            new Document(
                                    "Apache Flink Agents is an AI framework",
                                    Map.of("src", "test"),
                                    "doc2")),
                    collection,
                    Collections.emptyMap());

            VectorStoreQuery q =
                    new VectorStoreQuery(
                            "Milvus is a vector database", 5, collection, Collections.emptyMap());
            List<Document> hits = store.query(q).getDocuments();
            Assertions.assertFalse(hits.isEmpty());
            Assertions.assertTrue(
                    hits.stream().allMatch(d -> d.getScore() != null),
                    "Every Milvus search hit should carry a score");

            List<Document> byId =
                    store.get(List.of("doc1"), collection, null, null, Collections.emptyMap());
            Assertions.assertEquals(1, byId.size());
            Assertions.assertNull(byId.get(0).getScore());
        } finally {
            store.deleteCollection(collection);
            store.close();
        }
    }

    /**
     * Builds a descriptor for integration tests.
     *
     * <p>STRONG consistency is used here so that reads immediately see preceding writes within the
     * same test method. Production deployments should use the default BOUNDED level for better
     * throughput.
     */
    private static ResourceDescriptor descriptor(String collection) {
        return ResourceDescriptor.Builder.newBuilder(MilvusVectorStore.class.getName())
                .addInitialArgument("embedding_model", "embeddingModel")
                .addInitialArgument("uri", System.getenv("MILVUS_URI"))
                .addInitialArgument("collection", collection)
                .addInitialArgument("dims", 5)
                .addInitialArgument("index_type", "AUTOINDEX")
                .addInitialArgument("metric_type", "COSINE")
                // Avoid timing-sensitive assertions after insert/update; production defaults to
                // BOUNDED unless callers require immediate read-after-write visibility.
                .addInitialArgument("consistency_level", "STRONG")
                .build();
    }

    private static MilvusVectorStore openStore(String collection) throws Exception {
        MilvusVectorStore store =
                new MilvusVectorStore(
                        descriptor(collection),
                        ResourceContext.fromGetResource(MilvusVectorStoreTest::getResource));
        store.open();
        return store;
    }

    private static void createCollection(MilvusVectorStore store, String collection) {
        store.createCollectionIfNotExists(collection, Map.of());
    }

    private static String collectionName(String prefix) {
        return "fa_milvus_" + prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static Document documentById(List<Document> documents, String id) {
        return documents.stream()
                .filter(d -> id.equals(d.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing document " + id));
    }

    private static void assertDocument(
            Document document, String id, String content, Map<String, Object> metadata) {
        Assertions.assertEquals(id, document.getId());
        Assertions.assertEquals(content, document.getContent());
        Assertions.assertEquals(metadata, document.getMetadata());
        Assertions.assertNull(document.getScore());
    }

    private static void assertMetadataPathIndex(
            MilvusClientV2 client, String collection, String key, String castType) {
        DescribeIndexResp resp =
                client.describeIndex(
                        DescribeIndexReq.builder()
                                .collectionName(collection)
                                .indexName(metadataPathIndexName(key))
                                .build());
        DescribeIndexResp.IndexDesc index =
                resp.getIndexDescByIndexName(metadataPathIndexName(key));
        Assertions.assertEquals(MilvusVectorStore.DEFAULT_METADATA_FIELD, index.getFieldName());
        Assertions.assertEquals(IndexParam.IndexType.AUTOINDEX, index.getIndexType());
        assertThat(index.getExtraParams())
                .containsEntry(
                        "json_path", MilvusVectorStore.DEFAULT_METADATA_FIELD + "[\"" + key + "\"]")
                .containsEntry("json_cast_type", castType);
    }

    private static String metadataPathIndexName(String key) {
        return MilvusVectorStore.DEFAULT_METADATA_FIELD + "_" + key + "_json_index";
    }

    private static void dropCollectionQuietly(MilvusVectorStore store, String collection) {
        try {
            store.deleteCollection(collection);
        } catch (Exception ignored) {
            // Best-effort cleanup for negative-path assertions.
        }
    }

    private static Resource getResource(String name, ResourceType type) {
        BaseEmbeddingModelSetup embeddingModel = Mockito.mock(BaseEmbeddingModelSetup.class);
        Mockito.when(embeddingModel.embed("Milvus is a vector database"))
                .thenReturn(new float[] {1.0f, 0.0f, 0.0f, 0.0f, 0.0f});
        Mockito.when(embeddingModel.embed("Milvus stores dense vectors"))
                .thenReturn(new float[] {1.0f, 0.0f, 0.0f, 0.0f, 0.0f});
        Mockito.when(embeddingModel.embed("Apache Flink Agents is an AI framework"))
                .thenReturn(new float[] {0.0f, 1.0f, 0.0f, 0.0f, 0.0f});
        return embeddingModel;
    }
}
