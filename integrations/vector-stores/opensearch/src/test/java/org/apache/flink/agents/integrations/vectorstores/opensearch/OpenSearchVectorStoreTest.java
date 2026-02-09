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

package org.apache.flink.agents.integrations.vectorstores.opensearch;

import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelSetup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.vectorstores.BaseVectorStore;
import org.apache.flink.agents.api.vectorstores.CollectionManageableVectorStore;
import org.apache.flink.agents.api.vectorstores.Document;
import org.apache.flink.agents.api.vectorstores.VectorStoreQuery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link OpenSearchVectorStore}.
 *
 * <p>Integration tests require an OpenSearch Serverless collection or domain. Set
 * OPENSEARCH_ENDPOINT environment variable to run.
 */
public class OpenSearchVectorStoreTest {

    private static final BiFunction<String, ResourceType, Resource> NOOP = (a, b) -> null;

    @Test
    @DisplayName("Constructor creates store with IAM auth")
    void testConstructorIamAuth() {
        ResourceDescriptor desc = ResourceDescriptor.Builder
                .newBuilder(OpenSearchVectorStore.class.getName())
                .addInitialArgument("embedding_model", "emb")
                .addInitialArgument("endpoint", "https://example.aoss.us-east-1.amazonaws.com")
                .addInitialArgument("index", "test-index")
                .addInitialArgument("region", "us-east-1")
                .addInitialArgument("service_type", "serverless")
                .addInitialArgument("auth", "iam")
                .build();
        OpenSearchVectorStore store = new OpenSearchVectorStore(desc, NOOP);
        assertThat(store).isInstanceOf(BaseVectorStore.class);
        assertThat(store).isInstanceOf(CollectionManageableVectorStore.class);
    }

    @Test
    @DisplayName("Constructor creates store with basic auth")
    void testConstructorBasicAuth() {
        ResourceDescriptor desc = ResourceDescriptor.Builder
                .newBuilder(OpenSearchVectorStore.class.getName())
                .addInitialArgument("embedding_model", "emb")
                .addInitialArgument("endpoint", "https://my-domain.us-east-1.es.amazonaws.com")
                .addInitialArgument("index", "test-index")
                .addInitialArgument("region", "us-east-1")
                .addInitialArgument("service_type", "domain")
                .addInitialArgument("auth", "basic")
                .addInitialArgument("username", "admin")
                .addInitialArgument("password", "password")
                .build();
        OpenSearchVectorStore store = new OpenSearchVectorStore(desc, NOOP);
        assertThat(store).isInstanceOf(BaseVectorStore.class);
    }

    @Test
    @DisplayName("Constructor with custom max_bulk_mb")
    void testConstructorCustomBulkSize() {
        ResourceDescriptor desc = ResourceDescriptor.Builder
                .newBuilder(OpenSearchVectorStore.class.getName())
                .addInitialArgument("embedding_model", "emb")
                .addInitialArgument("endpoint", "https://example.aoss.us-east-1.amazonaws.com")
                .addInitialArgument("index", "test-index")
                .addInitialArgument("max_bulk_mb", 10)
                .build();
        OpenSearchVectorStore store = new OpenSearchVectorStore(desc, NOOP);
        assertThat(store.getStoreKwargs()).containsEntry("index", "test-index");
    }

    @Test
    @DisplayName("Basic auth requires username and password")
    void testBasicAuthRequiresCredentials() {
        ResourceDescriptor desc = ResourceDescriptor.Builder
                .newBuilder(OpenSearchVectorStore.class.getName())
                .addInitialArgument("embedding_model", "emb")
                .addInitialArgument("endpoint", "https://example.com")
                .addInitialArgument("index", "test")
                .addInitialArgument("auth", "basic")
                .build();
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new OpenSearchVectorStore(desc, NOOP));
    }

    // --- Integration tests (require real OpenSearch) ---

    private static BaseVectorStore store;

    private static Resource getResource(String name, ResourceType type) {
        BaseEmbeddingModelSetup emb = Mockito.mock(BaseEmbeddingModelSetup.class);
        Mockito.when(emb.embed("OpenSearch is a search engine"))
                .thenReturn(new float[]{0.2f, 0.3f, 0.4f, 0.5f, 0.6f});
        Mockito.when(emb.embed("Flink Agents is an AI framework"))
                .thenReturn(new float[]{0.1f, 0.2f, 0.3f, 0.4f, 0.5f});
        Mockito.when(emb.embed("search engine"))
                .thenReturn(new float[]{0.2f, 0.3f, 0.4f, 0.5f, 0.6f});
        Mockito.when(emb.embed(Mockito.anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            List<float[]> result = new ArrayList<>();
            for (String t : texts) {
                result.add(emb.embed(t));
            }
            return result;
        });
        return emb;
    }

    @BeforeAll
    static void initialize() {
        String endpoint = System.getenv("OPENSEARCH_ENDPOINT");
        if (endpoint == null) return;
        String auth = System.getenv().getOrDefault("OPENSEARCH_AUTH", "iam");
        ResourceDescriptor.Builder builder = ResourceDescriptor.Builder
                .newBuilder(OpenSearchVectorStore.class.getName())
                .addInitialArgument("embedding_model", "emb")
                .addInitialArgument("endpoint", endpoint)
                .addInitialArgument("index", "test-opensearch")
                .addInitialArgument("dims", 5)
                .addInitialArgument("region", System.getenv().getOrDefault("AWS_REGION", "us-east-1"))
                .addInitialArgument("service_type",
                        System.getenv().getOrDefault("OPENSEARCH_SERVICE_TYPE", "serverless"))
                .addInitialArgument("auth", auth);
        if ("basic".equals(auth)) {
            builder.addInitialArgument("username", System.getenv("OPENSEARCH_USERNAME"));
            builder.addInitialArgument("password", System.getenv("OPENSEARCH_PASSWORD"));
        }
        store = new OpenSearchVectorStore(builder.build(), OpenSearchVectorStoreTest::getResource);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENSEARCH_ENDPOINT", matches = ".+")
    @DisplayName("Collection management: create, get, delete")
    void testCollectionManagement() throws Exception {
        CollectionManageableVectorStore vs = (CollectionManageableVectorStore) store;
        String name = "test_collection";
        Map<String, Object> metadata = Map.of("key1", "value1");
        vs.getOrCreateCollection(name, metadata);

        CollectionManageableVectorStore.Collection col = vs.getCollection(name);
        Assertions.assertNotNull(col);
        Assertions.assertEquals(name, col.getName());

        vs.deleteCollection(name);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENSEARCH_ENDPOINT", matches = ".+")
    @DisplayName("Document management: add, get, delete")
    void testDocumentManagement() throws Exception {
        String name = "test_docs";
        ((CollectionManageableVectorStore) store).getOrCreateCollection(name, Map.of());

        List<Document> docs = new ArrayList<>();
        docs.add(new Document("OpenSearch is a search engine", Map.of("src", "test"), "doc1"));
        docs.add(new Document("Flink Agents is an AI framework", Map.of("src", "test"), "doc2"));
        store.add(docs, name, Collections.emptyMap());
        Thread.sleep(1000);

        List<Document> all = store.get(null, name, Collections.emptyMap());
        Assertions.assertEquals(2, all.size());

        store.delete(Collections.singletonList("doc1"), name, Collections.emptyMap());
        Thread.sleep(1000);
        List<Document> remaining = store.get(null, name, Collections.emptyMap());
        Assertions.assertEquals(1, remaining.size());

        ((CollectionManageableVectorStore) store).deleteCollection(name);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENSEARCH_ENDPOINT", matches = ".+")
    @DisplayName("Query with filter_query restricts results")
    void testQueryWithFilter() throws Exception {
        String name = "test_filter";
        ((CollectionManageableVectorStore) store).getOrCreateCollection(name, Map.of());

        List<Document> docs = new ArrayList<>();
        docs.add(new Document("OpenSearch is a search engine", Map.of("src", "web"), "f1"));
        docs.add(new Document("Flink Agents is an AI framework", Map.of("src", "code"), "f2"));
        store.add(docs, name, Collections.emptyMap());
        Thread.sleep(1000);

        // Query with filter: only src=web
        VectorStoreQuery q = new VectorStoreQuery(
                "search engine", 5, name,
                Map.of("filter_query", "{\"term\":{\"metadata.src.keyword\":\"web\"}}"));
        List<Document> results = store.query(q).getDocuments();
        Assertions.assertFalse(results.isEmpty());
        Assertions.assertTrue(results.stream().allMatch(
                d -> "web".equals(d.getMetadata().get("src"))));

        ((CollectionManageableVectorStore) store).deleteCollection(name);
    }
}
