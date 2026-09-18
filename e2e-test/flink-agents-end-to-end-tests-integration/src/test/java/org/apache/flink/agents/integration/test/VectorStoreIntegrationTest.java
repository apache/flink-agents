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

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.vectorstores.Document;
import org.apache.flink.agents.integrations.vectorstores.pgvector.PgVectorVectorStore;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.apache.flink.agents.integration.test.OllamaPreparationUtils.pullModel;
import static org.apache.flink.agents.integration.test.VectorStoreIntegrationAgent.OLLAMA_MODEL;
import static org.apache.flink.agents.integration.test.VectorStoreIntegrationAgent.envOr;

/**
 * Parameterized integration test for Vector Stores. Validates the Elasticsearch and pgvector
 * backends.
 *
 * <p>Environment variables required to run against a real Elasticsearch cluster:
 *
 * <ul>
 *   <li>ES_HOST (e.g., http://localhost:9200)
 *   <li>ES_INDEX (e.g., my_documents)
 *   <li>ES_VECTOR_FIELD (e.g., content_vector)
 *   <li>ES_DIMS (optional; defaults to 768)
 * </ul>
 *
 * <p>Environment variables (or system properties) required to run against pgvector:
 *
 * <ul>
 *   <li>PGVECTOR_URI (e.g., jdbc:postgresql://localhost:5432/postgres)
 *   <li>PGVECTOR_USERNAME, PGVECTOR_PASSWORD (optional; default postgres/postgres)
 * </ul>
 *
 * The pgvector table starts empty and is seeded here, before the job runs, through the same store
 * descriptor the agent uses (with fixed vectors); it is dropped afterwards. Each run pins the
 * agent's VECTOR_STORE_PROVIDER to its own backend and restores the previous value.
 *
 * <p>If these are not provided, the test will be skipped.
 */
public class VectorStoreIntegrationTest {

    /**
     * Seed rows with fixed unit vectors of the agent's dimensionality: the agent only checks that
     * retrieval returns non-empty documents, so no embedding model is needed to write them.
     */
    private static List<Document> pgVectorSeed(int dims) {
        return List.of(
                new Document(
                        "Apache Flink is a framework for stateful computations over"
                                + " unbounded and bounded data streams.",
                        Map.of("topic", "flink"),
                        "flink",
                        unitVector(dims, 0)),
                new Document(
                        "pgvector adds vector similarity search to PostgreSQL.",
                        Map.of("topic", "database"),
                        "pgvector",
                        unitVector(dims, 1)));
    }

    private static float[] unitVector(int dims, int axis) {
        float[] vector = new float[dims];
        vector[axis] = 1.0f;
        return vector;
    }

    @ParameterizedTest
    @ValueSource(strings = {"ELASTICSEARCH", "PGVECTOR"})
    public void testVectorStoreSemanticQuery(String backend) throws Exception {
        if (!"PGVECTOR".equals(backend)) {
            Assumptions.assumeTrue(!envOr("ES_HOST", "").isEmpty(), "ES_HOST is not set");
            Assumptions.assumeTrue(!envOr("ES_INDEX", "").isEmpty(), "ES_INDEX is not set");
            Assumptions.assumeTrue(
                    !envOr("ES_VECTOR_FIELD", "").isEmpty(), "ES_VECTOR_FIELD is not set");
            String previousProvider = System.getProperty("VECTOR_STORE_PROVIDER");
            try {
                System.setProperty("VECTOR_STORE_PROVIDER", "ELASTICSEARCH");
                runAndCheck();
            } finally {
                restoreProperty("VECTOR_STORE_PROVIDER", previousProvider);
            }
            return;
        }
        Assumptions.assumeTrue(!envOr("PGVECTOR_URI", "").isEmpty(), "PGVECTOR_URI is not set");
        Assumptions.assumeTrue(pullOllamaModel(), "Ollama model " + OLLAMA_MODEL + " unavailable");
        // The pgvector run points the agent at a throwaway table through system properties and
        // restores whatever the caller had set; seeding happens inside the try so a failure still
        // drops the table.
        String previousProvider = System.getProperty("VECTOR_STORE_PROVIDER");
        String previousCollection = System.getProperty("PGVECTOR_COLLECTION");
        String table = "fa_e2e_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        // The same store descriptor the agent declares seeds the table before the job (with
        // pre-computed vectors, so no embedding model is involved here) and drops it afterwards.
        // Everything from the first setProperty on happens inside the try.
        PgVectorVectorStore store = null;
        try {
            System.setProperty("VECTOR_STORE_PROVIDER", "PGVECTOR");
            System.setProperty("PGVECTOR_COLLECTION", table);
            store =
                    new PgVectorVectorStore(
                            VectorStoreIntegrationAgent.vectorStore(),
                            ResourceContext.fromGetResource((name, type) -> null));
            store.createCollectionIfNotExists(table, Map.of());
            store.addEmbedding(
                    pgVectorSeed(Integer.parseInt(envOr("PGVECTOR_DIMS", "768"))), table, Map.of());
            runAndCheck();
        } finally {
            if (store != null) {
                try {
                    store.deleteCollection(table);
                } catch (Exception ex) {
                    System.err.printf("[TEST] Could not drop pgvector table %s: %s%n", table, ex);
                }
                store.close();
            }
            restoreProperty("VECTOR_STORE_PROVIDER", previousProvider);
            restoreProperty("PGVECTOR_COLLECTION", previousCollection);
        }
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    private static boolean pullOllamaModel() {
        try {
            return pullModel(OLLAMA_MODEL);
        } catch (IOException e) {
            return false;
        }
    }

    private void runAndCheck() throws Exception {

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        final DataStreamSource<String> inputStream = env.fromData("What is Apache Flink");

        final AgentsExecutionEnvironment agentEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        final DataStream<Object> outputStream =
                agentEnv.fromDataStream(inputStream, (KeySelector<String, String>) value -> value)
                        .apply(new VectorStoreIntegrationAgent())
                        .toDataStream();

        final CloseableIterator<Object> results = outputStream.collectAsync();

        agentEnv.execute();

        checkResult(results);
    }

    @SuppressWarnings("unchecked")
    private void checkResult(CloseableIterator<Object> results) {
        Assertions.assertTrue(
                results.hasNext(), "No output received from VectorStoreIntegrationAgent");

        Object obj = results.next();
        Assertions.assertInstanceOf(Map.class, obj, "Output must be a Map");

        java.util.Map<String, Object> res = (java.util.Map<String, Object>) obj;
        Assertions.assertEquals("PASSED", res.get("test_status"));

        Object count = res.get("retrieved_count");
        Assertions.assertNotNull(count, "retrieved_count must exist");
        if (count instanceof Number) {
            Assertions.assertTrue(((Number) count).intValue() >= 1, "retrieved_count must be >= 1");
        }

        Object preview = res.get("first_doc_preview");
        Assertions.assertTrue(
                preview instanceof String && !((String) preview).trim().isEmpty(),
                "first_doc_preview must be a non-empty string");

        Object firstId = res.get("first_doc_id");
        if (firstId != null) {
            Assertions.assertTrue(
                    firstId instanceof String && !((String) firstId).trim().isEmpty(),
                    "first_doc_id when present must be a non-empty string");
        }
    }
}
