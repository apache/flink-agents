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
package org.apache.flink.agents.integrations.vectorstores.pgvector;

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

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link PgVectorVectorStore}. The live tests need a PostgreSQL server with the pgvector
 * extension and are enabled by {@code PGVECTOR_URI} (a JDBC URL); {@code PGVECTOR_USERNAME} and
 * {@code PGVECTOR_PASSWORD} default to {@code postgres}. {@code tools/docker/pgvector} starts one.
 */
public class PgVectorVectorStoreTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);
    private static final String LIVE = "PGVECTOR_URI";

    @Test
    void testConstructorAndStoreKwargs() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(PgVectorVectorStore.class.getName())
                        .addInitialArgument("embedding_model", "embeddingModel")
                        .addInitialArgument("uri", "jdbc:postgresql://db:5432/agents")
                        .addInitialArgument("username", "user")
                        .addInitialArgument("password", "secret")
                        .addInitialArgument("schema", "rag")
                        .addInitialArgument("collection", "test_collection")
                        .addInitialArgument("dims", 5)
                        .addInitialArgument("metric_type", "ip")
                        .addInitialArgument("index_type", "ivfflat")
                        .addInitialArgument("index_params", Map.of("lists", 100))
                        .addInitialArgument("create_extension", false)
                        .addInitialArgument("iterative_scan", "strict_order")
                        .build();
        PgVectorVectorStore store = new PgVectorVectorStore(desc, NOOP);
        Map<String, Object> kwargs = store.getStoreKwargs();
        assertThat(store).isInstanceOf(BaseVectorStore.class);
        assertThat(store).isInstanceOf(CollectionManageableVectorStore.class);
        assertThat(kwargs)
                .containsEntry("uri", "jdbc:postgresql://db:5432/agents")
                .containsEntry("schema", "rag")
                .containsEntry("collection", "test_collection")
                .containsEntry("index", "test_collection")
                .containsEntry("dims", 5)
                .containsEntry("metric_type", "IP")
                .containsEntry("index_type", "IVFFLAT")
                .containsEntry("index_params", Map.of("lists", 100L))
                .containsEntry("create_extension", false)
                .containsEntry("iterative_scan", "STRICT_ORDER");
        // Credentials never travel with the store kwargs.
        assertThat(kwargs).doesNotContainKeys("username", "password");
        store.close();
        // A closed store does not quietly reopen a connection.
        assertThatThrownBy(() -> store.get(List.of("x"), null, null, null, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void testDefaultsAndAliases() {
        PgVectorVectorStore store =
                new PgVectorVectorStore(
                        ResourceDescriptor.Builder.newBuilder(PgVectorVectorStore.class.getName())
                                .addInitialArgument("index", "aliased")
                                .build(),
                        NOOP);
        assertThat(store.getStoreKwargs())
                .containsEntry("uri", PgVectorVectorStore.DEFAULT_URI)
                .containsEntry("schema", PgVectorVectorStore.DEFAULT_SCHEMA)
                .containsEntry("collection", "aliased")
                .containsEntry("dims", PgVectorVectorStore.DEFAULT_DIMENSION)
                .containsEntry("metric_type", "COSINE")
                .containsEntry("index_type", "HNSW")
                .containsEntry("index_params", Map.of())
                .containsEntry("create_extension", true)
                .containsEntry("iterative_scan", "RELAXED_ORDER");

        PgVectorVectorStore hostStore =
                new PgVectorVectorStore(
                        ResourceDescriptor.Builder.newBuilder(PgVectorVectorStore.class.getName())
                                .addInitialArgument("host", "db.internal")
                                .addInitialArgument("port", 6543)
                                .addInitialArgument("database", "agents")
                                .build(),
                        NOOP);
        assertThat(hostStore.getStoreKwargs())
                .containsEntry("uri", "jdbc:postgresql://db.internal:6543/agents")
                .containsEntry("collection", PgVectorVectorStore.DEFAULT_COLLECTION);

        // port/database are honoured without an explicit host.
        PgVectorVectorStore portStore =
                new PgVectorVectorStore(
                        ResourceDescriptor.Builder.newBuilder(PgVectorVectorStore.class.getName())
                                .addInitialArgument("port", 6543)
                                .addInitialArgument("database", "agents")
                                .build(),
                        NOOP);
        assertThat(portStore.getStoreKwargs())
                .containsEntry("uri", "jdbc:postgresql://localhost:6543/agents");

        // Credentials embedded in the JDBC URL never surface through the store kwargs.
        PgVectorVectorStore credentialStore =
                newStore(
                        "uri",
                        "jdbc:postgresql://db/agents?user=svc&password=s3cret&ssl=true"
                                + "&sslkey=k.pk8&sslpassword=hunter2");
        assertThat(credentialStore.getStoreKwargs())
                .containsEntry("uri", "jdbc:postgresql://db/agents?ssl=true&sslkey=k.pk8")
                .doesNotContainKeys("username", "password");
        assertThat(credentialStore.getStoreKwargs().toString())
                .doesNotContain("s3cret")
                .doesNotContain("hunter2");
        assertThat(PgVectorVectorStore.stripCredentials("jdbc:postgresql://db/agents?password=x"))
                .isEqualTo("jdbc:postgresql://db/agents");
    }

    @Test
    void testArgumentValidation() {
        assertThatThrownBy(() -> newStore("collection", "docs; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collection");
        assertThatThrownBy(() -> newStore("vector_field", "embedding\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vector_field");
        assertThatThrownBy(() -> newStore("dims", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dims");
        assertThatThrownBy(() -> newStore("dims", "abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dims");
        assertThatThrownBy(() -> newStore("dims", 768.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dims");
        assertThatThrownBy(() -> newStore("dims", 3_000_000_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dims");
        assertThat(newStore("dims", 768.0).getStoreKwargs()).containsEntry("dims", 768);
        assertThatThrownBy(() -> newStore("metric_type", "DOT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metric_type");
        assertThatThrownBy(() -> newStore("index_type", "FLAT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index_type");
        assertThatThrownBy(() -> newStore("create_extension", "yes"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("create_extension");
        assertThat(newStore("create_extension", " FALSE ").getStoreKwargs())
                .containsEntry("create_extension", false);
        assertThatThrownBy(() -> newStore("index_params", Map.of("m", "16.5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index_params");
        assertThatThrownBy(() -> newStore("index_params", Map.of("m", "16); DROP TABLE x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index_params");
        assertThatThrownBy(() -> newStore("index_params", "m=16"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index_params");
        assertThatThrownBy(() -> newStore("uri", "postgresql://svc:S3cret@localhost/postgres"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jdbc:postgresql:")
                .hasMessageContaining("//***@localhost")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("S3cret"));
        assertThat(PgVectorVectorStore.redactUri("jdbc:postgresql://db/x?password=p&ssl=true"))
                .isEqualTo("jdbc:postgresql://db/x?ssl=true");
        // pgjdbc's short and socket forms are accepted as given.
        assertThat(newStore("uri", "jdbc:postgresql:agents").getStoreKwargs())
                .containsEntry("uri", "jdbc:postgresql:agents");
        assertThat(
                        newStore("uri", "jdbc:postgresql:///agents?host=/var/run/postgresql")
                                .getStoreKwargs())
                .containsEntry("uri", "jdbc:postgresql:///agents?host=/var/run/postgresql");
        assertThatThrownBy(() -> newStore("collection", "t".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("63 characters");
        // Wide vectors are only rejected when a table with an index would actually be created
        // (checked live below); the constructor and the schema check accept them.
        assertThat(newStore("dims", 3072).getStoreKwargs()).containsEntry("dims", 3072);
        assertThatThrownBy(() -> newStore("connect_timeout_s", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connect_timeout_s");
        assertThatThrownBy(() -> newStore("socket_timeout_s", "soon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("socket_timeout_s");
        assertThatThrownBy(() -> newStore("index_params", Map.of("m", 16.5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be an integer");
        assertThatThrownBy(() -> newStore("iterative_scan", "fast"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("iterative_scan");
        assertThat(PgVectorVectorStore.isAtLeast("0.8.6", new int[] {0, 8})).isTrue();
        assertThat(PgVectorVectorStore.isAtLeast("v0.8.1", new int[] {0, 8})).isTrue();
        assertThat(PgVectorVectorStore.isAtLeast("0.8", new int[] {0, 8})).isTrue();
        assertThat(PgVectorVectorStore.isAtLeast("1.0.0", new int[] {0, 8})).isTrue();
        assertThat(PgVectorVectorStore.isAtLeast("0.7.4", new int[] {0, 8})).isFalse();
        assertThat(PgVectorVectorStore.isAtLeast(null, new int[] {0, 8})).isFalse();
        assertThatThrownBy(
                        () ->
                                new PgVectorVectorStore(
                                        ResourceDescriptor.Builder.newBuilder(
                                                        PgVectorVectorStore.class.getName())
                                                .addInitialArgument("uri", "jdbc:postgresql://a/db")
                                                .addInitialArgument("host", "b")
                                                .build(),
                                        NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uri and host");
        assertThatThrownBy(() -> newStore("port", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
        // Indexed columns must leave room for a unique index name on any table.
        int maxColumn = PgVectorVectorStore.MAX_INDEXED_COLUMN_LENGTH;
        assertThatThrownBy(() -> newStore("vector_field", "v".repeat(maxColumn + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index name");
        assertThat(
                        PgVectorVectorStore.indexName(
                                        "t".repeat(63),
                                        newStore("vector_field", "v".repeat(maxColumn))
                                                .getStoreKwargs()
                                                .get("vector_field")
                                                .toString())
                                .length())
                .isLessThanOrEqualTo(63);
        // Wide vectors are fine without an index; integral floats normalize to integers.
        // Timeouts are connection settings and stay out of the store kwargs.
        PgVectorVectorStore wide =
                new PgVectorVectorStore(
                        ResourceDescriptor.Builder.newBuilder(PgVectorVectorStore.class.getName())
                                .addInitialArgument("dims", 3072)
                                .addInitialArgument("index_type", "NONE")
                                .addInitialArgument("index_params", Map.of("m", 16.0))
                                .addInitialArgument("connect_timeout_s", 3)
                                .addInitialArgument("socket_timeout_s", 30)
                                .build(),
                        NOOP);
        assertThat(wide.getStoreKwargs())
                .containsEntry("index_params", Map.of("m", 16L))
                .doesNotContainKeys("connect_timeout_s", "socket_timeout_s");
    }

    @Test
    void testIndexNamesStayUniqueWithinIdentifierLimit() {
        assertThat(PgVectorVectorStore.indexName("docs", "embedding"))
                .isEqualTo("docs_embedding_idx");
        String longTable = "fa_pgvector_" + "x".repeat(50);
        String vectorIndex = PgVectorVectorStore.indexName(longTable, "embedding");
        String metadataIndex = PgVectorVectorStore.indexName(longTable, "metadata");
        assertThat(vectorIndex.length()).isLessThanOrEqualTo(63);
        assertThat(metadataIndex.length()).isLessThanOrEqualTo(63);
        assertThat(vectorIndex).endsWith("_embedding_idx").isNotEqualTo(metadataIndex);
        // Distinct long tables never share an index name.
        assertThat(PgVectorVectorStore.indexName(longTable + "a", "embedding"))
                .isNotEqualTo(PgVectorVectorStore.indexName(longTable + "b", "embedding"));
    }

    @Test
    void testFiltersCompileToContainmentJson() {
        PgVectorVectorStore store = newStore("collection", "docs");
        assertThat(store.filtersToJson(null)).isNull();
        assertThat(store.filtersToJson(Map.of())).isNull();
        assertThat(store.filtersToJson(Map.of("category", "docs")))
                .isEqualTo("{\"category\":\"docs\"}");
        assertThat(store.filtersToJson(Map.of("count", 3))).isEqualTo("{\"count\":3}");
        assertThatThrownBy(() -> store.filtersToJson(Map.of("nested", Map.of("a", 1))))
                .isInstanceOf(UnsupportedOperationException.class);
        // A list would turn containment into a subset match, which is not equality.
        assertThatThrownBy(() -> store.filtersToJson(Map.of("tags", List.of("a"))))
                .isInstanceOf(UnsupportedOperationException.class);
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("category", null);
        assertThatThrownBy(() -> store.filtersToJson(withNull))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testVectorLiteralAndScores() {
        assertThat(PgVectorVectorStore.vectorLiteral(new float[] {1.0f, -0.5f, 0.25f}))
                .isEqualTo("[1.0,-0.5,0.25]");
        assertThat(PgVectorVectorStore.vectorLiteral(new float[0])).isEqualTo("[]");
        assertThatThrownBy(() -> PgVectorVectorStore.vectorLiteral(new float[] {Float.NaN}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
        // Cosine distance 0.25 -> similarity 0.75; L2 keeps the distance; IP negates <#>.
        assertThat(PgVectorVectorStore.MetricType.COSINE.toScore(0.25))
                .isCloseTo(0.75f, within(1e-6f));
        assertThat(PgVectorVectorStore.MetricType.L2.toScore(2.5)).isCloseTo(2.5f, within(1e-6f));
        assertThat(PgVectorVectorStore.MetricType.IP.toScore(-3.0)).isCloseTo(3.0f, within(1e-6f));
        assertThat(PgVectorVectorStore.MetricType.COSINE.getOperator()).isEqualTo("<=>");
        assertThat(PgVectorVectorStore.MetricType.L2.getOperatorClass()).isEqualTo("vector_l2_ops");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = LIVE, matches = ".+")
    void testCollectionManagement() throws Exception {
        String collection = collectionName("collection_management");
        PgVectorVectorStore store = openStore(collection);
        try {
            store.createCollectionIfNotExists(collection, Map.of());
            // Creating again is a no-op, with or without options.
            store.createCollectionIfNotExists(collection, Map.of());
            store.createCollectionIfNotExists(collection, null);
            assertThat(store.schemaMismatches(collection, null)).isEmpty();
            Assertions.assertTrue(
                    store.get(null, collection, null, 10, Collections.emptyMap()).isEmpty());
            assertThat(indexes(collection))
                    .containsEntry(
                            PgVectorVectorStore.indexName(collection, "embedding"),
                            "hnsw/vector_cosine_ops")
                    .containsEntry(
                            PgVectorVectorStore.indexName(collection, "metadata"), "gin/jsonb_ops");
            store.deleteCollection(collection);
            Assertions.assertThrows(
                    Exception.class,
                    () -> store.get(null, collection, null, 10, Collections.emptyMap()));
        } finally {
            dropCollectionQuietly(store, collection);
            store.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = LIVE, matches = ".+")
    void testExistingTableMismatchesAreReported() throws Exception {
        String collection = collectionName("mismatch");
        PgVectorVectorStore store = openStore(collection);
        try {
            store.createCollectionIfNotExists(collection, Map.of("metric_type", "L2"));
            assertThat(store.schemaMismatches(collection, Map.of("metric_type", "L2"))).isEmpty();
            // IF NOT EXISTS keeps the L2 table; a COSINE store on it has no usable index and a
            // different width, and both facts are reported (and logged by create).
            store.createCollectionIfNotExists(collection, Map.of("dims", 7));
            // ... and nothing was added to it: still one vector index, the L2 one.
            assertThat(indexes(collection).values()).containsOnlyOnce("hnsw/vector_l2_ops");
            assertThat(indexes(collection).values()).doesNotContain("hnsw/vector_cosine_ops");
            List<String> mismatches = store.schemaMismatches(collection, Map.of("dims", 7));
            assertThat(mismatches).hasSize(2);
            assertThat(mismatches.get(0)).contains("vector(5)").contains("vector(7)");
            assertThat(mismatches.get(1)).contains("vector_cosine_ops").contains("vector_l2_ops");
            // A store configured for wider vectors than an index allows can still describe and
            // "ensure" the existing table; only creating a new table enforces the index limit.
            PgVectorVectorStore wide = openStore(collection);
            try {
                wide.createCollectionIfNotExists(collection, Map.of("dims", 3072));
                assertThat(wide.schemaMismatches(collection, Map.of("dims", 3072)))
                        .isNotEmpty()
                        .noneMatch(m -> m.contains("2000-dimension"));
                assertThatThrownBy(
                                () ->
                                        wide.createCollectionIfNotExists(
                                                collectionName("wide"), Map.of("dims", 3072)))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("2000-dimension limit");
            } finally {
                wide.close();
            }
            // Without an index requirement only the column is checked.
            assertThat(store.schemaMismatches(collection, Map.of("index_type", "NONE"))).isEmpty();
            // A non-table relation squatting on the name is an error, not "create it".
            String viewName = collectionName("view");
            try (Connection admin = adminConnection();
                    Statement statement = admin.createStatement()) {
                statement.execute("CREATE VIEW public.\"" + viewName + "\" AS SELECT 1 AS id");
            }
            try {
                assertThatThrownBy(() -> store.createCollectionIfNotExists(viewName, Map.of()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("not a table");
            } finally {
                try (Connection admin = adminConnection();
                        Statement statement = admin.createStatement()) {
                    statement.execute("DROP VIEW IF EXISTS public.\"" + viewName + "\"");
                }
            }
            // Unknown collections are reported rather than assumed fine.
            assertThat(store.schemaMismatches(collectionName("absent"), Map.of()))
                    .singleElement()
                    .asString()
                    .contains("does not exist");
        } finally {
            dropCollectionQuietly(store, collection);
            store.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = LIVE, matches = ".+")
    void testCreateCollectionHonoursIndexArguments() throws Exception {
        String collection = collectionName("index_args");
        PgVectorVectorStore store = openStore(collection);
        try {
            store.createCollectionIfNotExists(
                    collection,
                    Map.of(
                            "metric_type", "L2",
                            "index_type", "IVFFLAT",
                            "index_params", Map.of("lists", 4)));
            assertThat(indexes(collection))
                    .containsEntry(
                            PgVectorVectorStore.indexName(collection, "embedding"),
                            "ivfflat/vector_l2_ops");
            try (Connection connection = adminConnection();
                    Statement statement = connection.createStatement();
                    ResultSet rows =
                            statement.executeQuery(
                                    "SELECT indexdef FROM pg_indexes WHERE indexname = '"
                                            + PgVectorVectorStore.indexName(collection, "embedding")
                                            + "'")) {
                Assertions.assertTrue(rows.next(), "vector index should exist");
                assertThat(rows.getString(1)).contains("lists='4'");
            }
        } finally {
            dropCollectionQuietly(store, collection);
            store.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = LIVE, matches = ".+")
    void testDocumentManagement() throws Exception {
        String collection = collectionName("document_management");
        PgVectorVectorStore store = openStore(collection);
        try {
            store.createCollectionIfNotExists(collection, Map.of());
            store.add(
                    List.of(
                            new Document(
                                    "pgvector is a PostgreSQL extension",
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
                    "pgvector is a PostgreSQL extension",
                    Map.of("category", "database", "source", "test"));
            assertDocument(
                    documentById(all, "doc2"),
                    "doc2",
                    "Apache Flink Agents is an AI framework",
                    Map.of("category", "ai-agent", "source", "test"));

            List<Document> byId =
                    store.get(List.of("doc1"), collection, null, null, Collections.emptyMap());
            Assertions.assertEquals(1, byId.size());
            Assertions.assertEquals("doc1", byId.get(0).getId());
            // An empty id list returns nothing instead of scanning the table.
            Assertions.assertTrue(
                    store.get(List.of(), collection, null, null, Collections.emptyMap()).isEmpty());

            Assertions.assertEquals(
                    1, store.get(null, collection, null, 1, Collections.emptyMap()).size());

            // An empty id list is a no-op, not a request to delete everything.
            store.delete(List.of(), collection, null, Collections.emptyMap());
            Assertions.assertEquals(
                    2, store.get(null, collection, null, 10, Collections.emptyMap()).size());

            store.delete(List.of("doc1"), collection, null, Collections.emptyMap());
            List<Document> remaining =
                    store.get(null, collection, null, 10, Collections.emptyMap());
            Assertions.assertEquals(1, remaining.size());
            Assertions.assertEquals("doc2", remaining.get(0).getId());

            store.delete(null, collection, null, Collections.emptyMap());
            Assertions.assertTrue(
                    store.get(null, collection, null, 10, Collections.emptyMap()).isEmpty());
        } finally {
            dropCollectionQuietly(store, collection);
            store.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = LIVE, matches = ".+")
    void testFiltersDsl() throws Exception {
        String collection = collectionName("filters_dsl");
        PgVectorVectorStore store = openStore(collection);
        try {
            store.createCollectionIfNotExists(collection, Map.of());
            store.add(
                    List.of(
                            new Document(
                                    "pgvector is a PostgreSQL extension",
                                    Map.of("category", "database", "user_id", "alice", "rank", 1),
                                    "doc_alice"),
                            new Document(
                                    "Apache Flink Agents is an AI framework",
                                    Map.of("category", "ai-agent", "user_id", "bob", "rank", 2),
                                    "doc_bob")),
                    collection,
                    Collections.emptyMap());

            List<Document> aliceOnly =
                    store.get(null, collection, Map.of("user_id", "alice"), 10, Map.of());
            Assertions.assertEquals(1, aliceOnly.size());
            Assertions.assertEquals("doc_alice", aliceOnly.get(0).getId());

            // Numeric equality and two-key conjunction.
            Assertions.assertEquals(
                    "doc_bob",
                    store.get(null, collection, Map.of("rank", 2), 10, Map.of()).get(0).getId());
            Assertions.assertTrue(
                    store.get(
                                    null,
                                    collection,
                                    Map.of("user_id", "alice", "category", "ai-agent"),
                                    10,
                                    Map.of())
                            .isEmpty());

            // A filter applies to similarity search too: the closest vector belongs to bob, but
            // only alice's document may be returned.
            List<Document> aliceQueried =
                    store.queryEmbedding(
                            new float[] {0.0f, 1.0f, 0.0f, 0.0f, 0.0f},
                            5,
                            collection,
                            Map.of("user_id", "alice"),
                            Collections.emptyMap());
            Assertions.assertEquals(1, aliceQueried.size());
            Assertions.assertEquals("doc_alice", aliceQueried.get(0).getId());

            store.delete(null, collection, Map.of("user_id", "alice"), Collections.emptyMap());
            List<Document> remaining = store.get(null, collection, null, 10, Map.of());
            Assertions.assertEquals(1, remaining.size());
            Assertions.assertEquals("doc_bob", remaining.get(0).getId());
        } finally {
            dropCollectionQuietly(store, collection);
            store.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = LIVE, matches = ".+")
    void testAddGeneratesIdsAndUpdateOverwrites() throws Exception {
        String collection = collectionName("add_update");
        PgVectorVectorStore store = openStore(collection);
        try {
            store.createCollectionIfNotExists(collection, Map.of());
            // add treats "" as "generate an id"; update must not upsert a row keyed "".
            assertThatThrownBy(
                            () ->
                                    store.update(
                                            List.of(new Document("x", Map.of(), "")),
                                            collection,
                                            Collections.emptyMap()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("id");
            List<String> ids =
                    store.add(
                            List.of(
                                    new Document(
                                            "pgvector is a PostgreSQL extension",
                                            Map.of("category", "database"),
                                            null)),
                            collection,
                            Collections.emptyMap());
            Assertions.assertEquals(1, ids.size());
            Assertions.assertFalse(ids.get(0).isEmpty());
            List<Document> stored =
                    store.get(List.of(ids.get(0)), collection, null, null, Collections.emptyMap());
            Assertions.assertEquals(1, stored.size());

            Document original =
                    new Document(
                            "pgvector is a PostgreSQL extension",
                            Map.of("category", "database"),
                            "doc1");
            store.add(List.of(original), collection, Collections.emptyMap());
            // Adding again with an existing id replaces the row instead of failing, so a replayed
            // batch is idempotent; the new document in the same batch is stored as well.
            Document fresh =
                    new Document(
                            "Apache Flink Agents is an AI framework",
                            Map.of("category", "ai"),
                            "fresh");
            Document replayed =
                    new Document(
                            "pgvector is a PostgreSQL extension",
                            Map.of("category", "database", "replayed", true),
                            "doc1");
            Assertions.assertEquals(
                    List.of("fresh", "doc1"),
                    store.add(List.of(fresh, replayed), collection, Collections.emptyMap()));
            // The generated-id document from above, doc1 (replaced in place) and fresh.
            Assertions.assertEquals(
                    3, store.get(null, collection, null, null, Collections.emptyMap()).size());
            Assertions.assertEquals(
                    true,
                    store.get(List.of("doc1"), collection, null, null, Collections.emptyMap())
                            .get(0)
                            .getMetadata()
                            .get("replayed"));
            // The same id twice in one batch stores one row, the later document winning.
            store.add(
                    List.of(
                            new Document("first", Map.of(), "twice", new float[] {0, 0, 0, 1, 0}),
                            new Document("second", Map.of(), "twice", new float[] {0, 0, 0, 0, 1})),
                    collection,
                    Collections.emptyMap());
            List<Document> twice =
                    store.get(List.of("twice"), collection, null, null, Collections.emptyMap());
            Assertions.assertEquals(1, twice.size());
            Assertions.assertEquals("second", twice.get(0).getContent());
            // A batch is still atomic: an unserialisable document fails the whole batch.
            Map<String, Object> unserialisable = new HashMap<>();
            unserialisable.put("self", new Object());
            assertThatThrownBy(
                            () ->
                                    store.add(
                                            List.of(
                                                    new Document(
                                                            "a",
                                                            Map.of(),
                                                            "atomic_a",
                                                            new float[] {1, 0, 0, 0, 0}),
                                                    new Document(
                                                            "b",
                                                            unserialisable,
                                                            "atomic_b",
                                                            new float[] {0, 1, 0, 0, 0})),
                                            collection,
                                            Collections.emptyMap()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("add");
            Assertions.assertTrue(
                    store.get(List.of("atomic_a"), collection, null, null, Collections.emptyMap())
                            .isEmpty());

            Document rewritten =
                    new Document(
                            "pgvector stores dense vectors", Map.of("category", "updated"), "doc1");
            store.update(List.of(rewritten), collection, Collections.emptyMap());
            List<Document> after =
                    store.get(List.of("doc1"), collection, null, null, Collections.emptyMap());
            Assertions.assertEquals(1, after.size());
            Assertions.assertEquals("pgvector stores dense vectors", after.get(0).getContent());
            Assertions.assertEquals("updated", after.get(0).getMetadata().get("category"));
            // The embedding was replaced too: the rewritten text maps to the second axis.
            List<Document> hits =
                    store.queryEmbedding(
                            new float[] {0.0f, 1.0f, 0.0f, 0.0f, 0.0f},
                            1,
                            collection,
                            null,
                            Collections.emptyMap());
            Assertions.assertEquals("doc1", hits.get(0).getId());
            assertThat(hits.get(0).getScore()).isCloseTo(1.0f, within(1e-4f));
        } finally {
            dropCollectionQuietly(store, collection);
            store.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = LIVE, matches = ".+")
    void testQueryOrdersByMetricAndPopulatesScore() throws Exception {
        String collection = collectionName("scores");
        PgVectorVectorStore store = openStore(collection);
        try {
            store.createCollectionIfNotExists(collection, Map.of());
            store.add(
                    List.of(
                            new Document(
                                    "pgvector is a PostgreSQL extension", Map.of("src", "t"), "x"),
                            new Document(
                                    "Apache Flink Agents is an AI framework",
                                    Map.of("src", "t"),
                                    "y")),
                    collection,
                    Collections.emptyMap());

            // Query text embeds to the first axis, so "x" is nearest under every metric.
            VectorStoreQuery query =
                    new VectorStoreQuery(
                            "pgvector is a PostgreSQL extension",
                            2,
                            collection,
                            Collections.emptyMap());
            List<Document> cosine = store.query(query).getDocuments();
            Assertions.assertEquals(List.of("x", "y"), ids(cosine));
            assertThat(cosine.get(0).getScore()).isCloseTo(1.0f, within(1e-4f));
            assertThat(cosine.get(1).getScore()).isCloseTo(0.0f, within(1e-4f));

            float[] q = new float[] {1.0f, 0.0f, 0.0f, 0.0f, 0.0f};
            List<Document> l2 =
                    store.queryEmbedding(q, 2, collection, null, Map.of("metric_type", "L2"));
            Assertions.assertEquals(List.of("x", "y"), ids(l2));
            assertThat(l2.get(0).getScore()).isCloseTo(0.0f, within(1e-4f));
            assertThat(l2.get(1).getScore()).isCloseTo((float) Math.sqrt(2.0), within(1e-4f));

            List<Document> ip =
                    store.queryEmbedding(q, 2, collection, null, Map.of("metric_type", "IP"));
            Assertions.assertEquals(List.of("x", "y"), ids(ip));
            assertThat(ip.get(0).getScore()).isCloseTo(1.0f, within(1e-4f));
            assertThat(ip.get(1).getScore()).isCloseTo(0.0f, within(1e-4f));

            // Reads that are not searches carry no score.
            List<Document> byId =
                    store.get(List.of("x"), collection, null, null, Collections.emptyMap());
            Assertions.assertNull(byId.get(0).getScore());

            // An externally inserted row without a vector is skipped by an HNSW index scan; a
            // sequential scan sorts it last. Either way it must never be reported as a match
            // with a (perfect) score.
            try (Connection admin = adminConnection();
                    Statement statement = admin.createStatement()) {
                statement.execute(
                        "INSERT INTO public.\""
                                + collection
                                + "\" (id, content, metadata) VALUES ('z', 'no vector', '{}')");
                // A metadata cell that is not a JSON object (written by another tool) is
                // returned under "value" instead of failing the read.
                statement.execute(
                        "INSERT INTO public.\""
                                + collection
                                + "\" (id, content, metadata, embedding) VALUES"
                                + " ('arr', 'array metadata', '[\"a\",\"b\"]', '[0,0,0,1,0]')");
            }
            Document arr =
                    store.get(List.of("arr"), collection, null, null, Collections.emptyMap())
                            .get(0);
            assertThat(arr.getMetadata()).containsEntry("value", List.of("a", "b"));
            try (Connection admin = adminConnection();
                    Statement statement = admin.createStatement()) {
                statement.execute("DELETE FROM public.\"" + collection + "\" WHERE id = 'arr'");
            }
            List<Document> withNull =
                    store.queryEmbedding(q, 3, collection, null, Collections.emptyMap());
            assertThat(ids(withNull)).startsWith("x", "y");
            for (Document document : withNull) {
                if ("z".equals(document.getId())) {
                    Assertions.assertNull(document.getScore());
                } else {
                    Assertions.assertNotNull(document.getScore());
                }
            }
        } finally {
            dropCollectionQuietly(store, collection);
            store.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = LIVE, matches = ".+")
    void testFilteredSearchOnIndexedTableUsesIterativeScan() throws Exception {
        String collection = collectionName("iterscan");
        PgVectorVectorStore store = openStore(collection);
        try {
            store.createCollectionIfNotExists(collection, Map.of());
            // The bundled pgvector (0.8+) supports iterative scans.
            assertThat(store.supportsIterativeScan()).isTrue();

            // Many "other" rows near the query and three "rare" rows far away: without an
            // iterative scan an index scan may post-filter its first candidates down to nothing.
            List<Document> documents = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                float[] vector = new float[] {0.01f * i, 0.02f * (i % 7), 0.03f * (i % 5), 0f, 1f};
                documents.add(new Document("other " + i, Map.of("k", "other"), "r" + i, vector));
            }
            documents.add(
                    new Document("rare 1", Map.of("k", "rare"), "t1", new float[] {1, 1, 1, 1, 0}));
            documents.add(
                    new Document("rare 2", Map.of("k", "rare"), "t2", new float[] {1, 1, 1, 0, 0}));
            documents.add(
                    new Document("rare 3", Map.of("k", "rare"), "t3", new float[] {1, 1, 0, 0, 0}));
            store.addEmbedding(documents, collection, Collections.emptyMap());

            float[] query = new float[] {0, 0, 0, 0, 1};
            // On a table this small the planner would scan sequentially, so force the HNSW index
            // path through pgjdbc's "options" startup parameter: this is the post-filtering case
            // iterative scans exist for.
            String forcedUri =
                    System.getenv(LIVE)
                            + (System.getenv(LIVE).contains("?") ? "&" : "?")
                            + "options=-c%20enable_seqscan%3Doff%20-c%20enable_bitmapscan%3Doff";
            PgVectorVectorStore forced =
                    new PgVectorVectorStore(
                            ResourceDescriptor.Builder.newBuilder(
                                            PgVectorVectorStore.class.getName())
                                    .addInitialArgument("uri", forcedUri)
                                    .addInitialArgument(
                                            "username", envOr("PGVECTOR_USERNAME", "postgres"))
                                    .addInitialArgument(
                                            "password", envOr("PGVECTOR_PASSWORD", "postgres"))
                                    .addInitialArgument("collection", collection)
                                    .addInitialArgument("dims", 5)
                                    .build(),
                            NOOP);
            try {
                // Plain HNSW scan: only the ef_search nearest candidates are filtered, and the
                // rare rows are far away, so the search comes up short.
                List<Document> plain =
                        forced.queryEmbedding(
                                query,
                                3,
                                collection,
                                Map.of("k", "rare"),
                                Map.of("iterative_scan", "off"));
                assertThat(plain).as("iterative_scan=off").hasSizeLessThan(3);
                // The store's default (relaxed_order, SET LOCAL per search) and a strict
                // override find them all.
                assertThat(
                                ids(
                                        forced.queryEmbedding(
                                                query,
                                                3,
                                                collection,
                                                Map.of("k", "rare"),
                                                Collections.emptyMap())))
                        .as("default")
                        .containsExactlyInAnyOrder("t1", "t2", "t3");
                assertThat(
                                ids(
                                        forced.queryEmbedding(
                                                query,
                                                3,
                                                collection,
                                                Map.of("k", "rare"),
                                                Map.of("iterative_scan", "strict_order"))))
                        .as("strict_order")
                        .containsExactlyInAnyOrder("t1", "t2", "t3");
                // Unfiltered searches run with the setting too (a plain HNSW scan is capped at
                // hnsw.ef_search rows) and are unaffected by it here.
                assertThat(
                                forced.queryEmbedding(
                                        query, 3, collection, null, Collections.emptyMap()))
                        .hasSize(3);
                // A failed search must not leave the connection in an aborted transaction
                // block: the next search on the same store works.
                assertThatThrownBy(
                                () ->
                                        forced.queryEmbedding(
                                                new float[] {1, 0, 0},
                                                3,
                                                collection,
                                                null,
                                                Collections.emptyMap()))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(
                                forced.queryEmbedding(
                                        query, 3, collection, null, Collections.emptyMap()))
                        .hasSize(3);
                // The connection is back in autocommit mode: a plain write through it must be
                // visible at once from another connection.
                forced.delete(List.of("t3"), collection, null, Collections.emptyMap());
            } finally {
                forced.close();
            }
            Assertions.assertEquals(
                    2,
                    store.get(null, collection, Map.of("k", "rare"), null, Collections.emptyMap())
                            .size());
            // limit=null returns every matching row, not a bounded default.
            Assertions.assertEquals(
                    302, store.get(null, collection, null, null, Collections.emptyMap()).size());
            Assertions.assertEquals(
                    5, store.get(null, collection, null, 5, Collections.emptyMap()).size());
        } finally {
            dropCollectionQuietly(store, collection);
            store.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = LIVE, matches = ".+")
    void testReconnectsAfterServerClosedTheConnection() throws Exception {
        String collection = collectionName("reconnect");
        PgVectorVectorStore store = openStore(collection);
        try {
            store.createCollectionIfNotExists(collection, Map.of());
            store.add(
                    List.of(new Document("pgvector is a PostgreSQL extension", Map.of(), "x")),
                    collection,
                    Collections.emptyMap());
            // Kill the store's backend from the admin session (every other backend of this
            // database but our own; the tests run sequentially and close their stores).
            try (Connection admin = adminConnection();
                    Statement statement = admin.createStatement()) {
                statement.execute(
                        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity"
                                + " WHERE pid <> pg_backend_pid() AND datname = current_database()"
                                + " AND backend_type = 'client backend'");
            }
            // The first call finds the dead socket and fails with a connection-level error ...
            assertThatThrownBy(
                            () ->
                                    store.get(
                                            List.of("x"),
                                            collection,
                                            null,
                                            null,
                                            Collections.emptyMap()))
                    .isInstanceOf(IOException.class);
            // ... and the next one runs on a fresh connection.
            Assertions.assertEquals(
                    1,
                    store.get(List.of("x"), collection, null, null, Collections.emptyMap()).size());
            assertThat(
                            store.queryEmbedding(
                                    new float[] {1, 0, 0, 0, 0},
                                    1,
                                    collection,
                                    null,
                                    Collections.emptyMap()))
                    .hasSize(1);
        } finally {
            dropCollectionQuietly(store, collection);
            store.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = LIVE, matches = ".+")
    void testExtraArgsCollectionDoesNotOverrideTargetCollection() throws Exception {
        String collection = collectionName("target_collection");
        String ignoredCollection = collectionName("ignored_collection");
        PgVectorVectorStore store = openStore(collection);
        try {
            store.createCollectionIfNotExists(collection, Map.of());
            List<String> ids =
                    store.add(
                            List.of(
                                    new Document(
                                            "pgvector is a PostgreSQL extension",
                                            Map.of("category", "database"),
                                            "doc1")),
                            null,
                            Map.of("collection", ignoredCollection));
            Assertions.assertEquals(List.of("doc1"), ids);
            Assertions.assertEquals(
                    1,
                    store.get(List.of("doc1"), collection, null, null, Collections.emptyMap())
                            .size());
        } finally {
            dropCollectionQuietly(store, collection);
            dropCollectionQuietly(store, ignoredCollection);
            store.close();
        }
    }

    // ---- helpers

    private static PgVectorVectorStore newStore(String key, Object value) {
        return new PgVectorVectorStore(
                ResourceDescriptor.Builder.newBuilder(PgVectorVectorStore.class.getName())
                        .addInitialArgument(key, value)
                        .build(),
                NOOP);
    }

    private static ResourceDescriptor descriptor(String collection) {
        return ResourceDescriptor.Builder.newBuilder(PgVectorVectorStore.class.getName())
                .addInitialArgument("embedding_model", "embeddingModel")
                .addInitialArgument("uri", System.getenv(LIVE))
                .addInitialArgument("username", envOr("PGVECTOR_USERNAME", "postgres"))
                .addInitialArgument("password", envOr("PGVECTOR_PASSWORD", "postgres"))
                .addInitialArgument("collection", collection)
                .addInitialArgument("dims", 5)
                .build();
    }

    private static PgVectorVectorStore openStore(String collection) throws Exception {
        PgVectorVectorStore store =
                new PgVectorVectorStore(
                        descriptor(collection),
                        ResourceContext.fromGetResource(PgVectorVectorStoreTest::getResource));
        store.open();
        return store;
    }

    /**
     * Maps each index of the table to {@code <access method>/<operator class>} from the catalog.
     */
    private static Map<String, String> indexes(String table) throws Exception {
        Map<String, String> result = new HashMap<>();
        try (Connection connection = adminConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT ic.relname, am.amname, oc.opcname FROM pg_index i"
                                        + " JOIN pg_class c ON c.oid = i.indrelid"
                                        + " JOIN pg_class ic ON ic.oid = i.indexrelid"
                                        + " JOIN pg_am am ON am.oid = ic.relam"
                                        + " JOIN pg_opclass oc ON oc.oid = i.indclass[0]"
                                        + " WHERE c.relname = ?")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.put(rows.getString(1), rows.getString(2) + "/" + rows.getString(3));
                }
            }
        }
        return result;
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(
                System.getenv(LIVE),
                envOr("PGVECTOR_USERNAME", "postgres"),
                envOr("PGVECTOR_PASSWORD", "postgres"));
    }

    /** Environment variable first, then system property, then the default (as the e2e tests). */
    private static String envOr(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isEmpty()) {
            value = System.getProperty(key);
        }
        return value == null || value.isEmpty() ? defaultValue : value;
    }

    private static String collectionName(String prefix) {
        // Keep well under PostgreSQL's 63-character identifier limit.
        return "fa_pgvector_"
                + prefix
                + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static List<String> ids(List<Document> documents) {
        return documents.stream()
                .map(Document::getId)
                .collect(java.util.stream.Collectors.toList());
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

    private static void dropCollectionQuietly(PgVectorVectorStore store, String collection) {
        try {
            store.deleteCollection(collection);
        } catch (Exception ignored) {
            // Best-effort cleanup.
        }
    }

    private static Resource getResource(String name, ResourceType type) {
        BaseEmbeddingModelSetup embeddingModel = Mockito.mock(BaseEmbeddingModelSetup.class);
        Mockito.when(embeddingModel.embed("pgvector is a PostgreSQL extension"))
                .thenReturn(new float[] {1.0f, 0.0f, 0.0f, 0.0f, 0.0f});
        Mockito.when(embeddingModel.embed("pgvector stores dense vectors"))
                .thenReturn(new float[] {0.0f, 1.0f, 0.0f, 0.0f, 0.0f});
        Mockito.when(embeddingModel.embed("Apache Flink Agents is an AI framework"))
                .thenReturn(new float[] {0.0f, 1.0f, 0.0f, 0.0f, 0.0f});
        return embeddingModel;
    }
}
