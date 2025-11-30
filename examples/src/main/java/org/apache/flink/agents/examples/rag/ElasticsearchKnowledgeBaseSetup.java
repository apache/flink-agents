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

package org.apache.flink.agents.examples.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.CreateOperation;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.integrations.embeddingmodels.ollama.OllamaEmbeddingModelConnection;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Helper for preparing the Elasticsearch knowledge base used by the RAG example.
 *
 * <p>Main responsibilities:
 *
 * <ul>
 *   <li>Create the target index with a simple mapping that includes a {@code dense_vector} field
 *       (if the index does not exist).
 *   <li>Load a small set of built-in sample documents.
 *   <li>Generate embeddings via an Ollama embedding model and bulk index documents along with their
 *       vectors.
 * </ul>
 *
 * <p>How it is used:
 *
 * <pre>{@code
 * ElasticsearchKnowledgeBaseSetup.populate(
 *     esHost,
 *     index,
 *     vectorField,
 *     dims,
 *     similarity,         // e.g., "cosine"
 *     ollamaEndpoint,     // e.g., "http://localhost:11434"
 *     embeddingModel      // e.g., "nomic-embed-text"
 * );
 * }</pre>
 *
 * <p>Authentication (System properties):
 *
 * <ul>
 *   <li>{@code ES_API_KEY_BASE64} — Base64 of {@code apiKeyId:apiKeySecret} (takes precedence)
 *   <li>{@code ES_API_KEY_ID} and {@code ES_API_KEY_SECRET} — will be combined and Base64-encoded
 *   <li>{@code ES_USERNAME} and {@code ES_PASSWORD} — basic authentication fallback
 * </ul>
 *
 * If none of the above are provided, no authentication headers are added.
 *
 * <p>Connection defaults:
 *
 * <ul>
 *   <li>Elasticsearch host defaults to {@code http://localhost:9200} when not specified.
 *   <li>Ollama endpoint is passed in via the {@code populate(...)} arguments.
 * </ul>
 *
 * <p>Index mapping expectation:
 *
 * <ul>
 *   <li>This helper will create the index if missing with the following fields:
 *       <ul>
 *         <li>{@code content} — {@code text}
 *         <li>{@code metadata} — {@code object} with {@code enabled=false}
 *         <li>{@code <vectorField>} — {@code dense_vector} with {@code dims} and optional {@code
 *             similarity}
 *       </ul>
 * </ul>
 *
 * <p>Batching behavior (not externally configurable):
 *
 * <ul>
 *   <li>Embedding batch size: {@value #DEFAULT_EMBED_BATCH}
 *   <li>Bulk indexing batch size: {@value #DEFAULT_BULK_SIZE}
 * </ul>
 *
 * These defaults are internal and intentionally not exposed via system properties to keep the
 * example simple and safe.
 *
 * <p>Documents source:
 *
 * <ul>
 *   <li>By default, a small built-in set of topical documents is used to make the example runnable
 *       out of the box.
 * </ul>
 */
public class ElasticsearchKnowledgeBaseSetup {

    public static class SampleDoc {
        public final String id;
        public final String content;
        public final Map<String, Object> metadata;

        public SampleDoc(String id, String content, Map<String, Object> metadata) {
            this.id = id;
            this.content = content;
            this.metadata = metadata;
        }
    }

    public static void populate(
            String esHost,
            String index,
            String vectorField,
            int dims,
            String similarity,
            String ollamaEndpoint,
            String embeddingModel)
            throws IOException {

        ElasticsearchClient client = buildEsClient(esHost);
        ensureIndex(client, index, vectorField, dims, similarity);

        // Prepare embedding connection
        OllamaEmbeddingModelConnection embeddingConn =
                buildEmbeddingConnection(ollamaEndpoint, embeddingModel);

        // Load documents using a simplified loader selection
        List<SampleDoc> docs = loadDocuments();

        if (docs.isEmpty()) {
            System.out.println("[KB Setup] No documents to index.");
            return;
        }

        int indexed = embedAndIndexAll(client, index, vectorField, embeddingConn, docs);

        System.out.printf("[KB Setup] Indexed %d sample documents into '%s'%n", indexed, index);
    }

    /** Builds an embedding connection for Ollama. */
    private static OllamaEmbeddingModelConnection buildEmbeddingConnection(
            String ollamaEndpoint, String embeddingModel) {
        ResourceDescriptor embeddingConnDesc =
                ResourceDescriptor.Builder.newBuilder(
                                OllamaEmbeddingModelConnection.class.getName())
                        .addInitialArgument("host", ollamaEndpoint)
                        .addInitialArgument("model", embeddingModel)
                        .build();
        return new OllamaEmbeddingModelConnection(embeddingConnDesc, (name, type) -> null);
    }

    /** Default internal batch sizes (not configurable via system properties). */
    private static final int DEFAULT_EMBED_BATCH = 32;

    private static final int DEFAULT_BULK_SIZE = 100;

    /** Embeds all documents in batches and performs bulk indexing. */
    private static int embedAndIndexAll(
            ElasticsearchClient client,
            String index,
            String vectorField,
            OllamaEmbeddingModelConnection embeddingConn,
            List<SampleDoc> docs)
            throws IOException {
        int indexed = 0;
        for (int start = 0; start < docs.size(); start += DEFAULT_EMBED_BATCH) {
            int end = Math.min(start + DEFAULT_EMBED_BATCH, docs.size());
            List<SampleDoc> batch = docs.subList(start, end);
            List<String> texts = new ArrayList<>(batch.size());
            for (SampleDoc d : batch) {
                texts.add(d.content);
            }

            List<float[]> embeddings = embeddingConn.embed(texts, Map.of());
            indexed += bulkIndexBatch(client, index, vectorField, batch, embeddings);
        }
        return indexed;
    }

    /** Converts float[] to List<Float> as expected by ES Java API. */
    private static List<Float> toFloatList(float[] vec) {
        List<Float> list = new ArrayList<>(vec.length);
        for (float v : vec) list.add(v);
        return list;
    }

    /** Prepares and executes bulk index requests for a batch. */
    private static int bulkIndexBatch(
            ElasticsearchClient client,
            String index,
            String vectorField,
            List<SampleDoc> batch,
            List<float[]> embeddings)
            throws IOException {
        int indexed = 0;
        List<BulkOperation> ops = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            SampleDoc d = batch.get(i);
            float[] vec = embeddings.get(i);
            Map<String, Object> sourceDoc = new HashMap<>();
            sourceDoc.put("content", d.content);
            sourceDoc.put("metadata", d.metadata);
            sourceDoc.put(vectorField, toFloatList(vec));

            CreateOperation<Map<String, Object>> createOp =
                    CreateOperation.<Map<String, Object>>of(
                            b -> b.index(index).id(d.id).document(sourceDoc));
            ops.add(BulkOperation.of(b -> b.create(createOp)));

            // flush if reached bulkSize
            if (ops.size() >= DEFAULT_BULK_SIZE) {
                indexed += executeBulk(client, ops);
                ops.clear();
            }
        }
        if (!ops.isEmpty()) {
            indexed += executeBulk(client, ops);
        }
        return indexed;
    }

    private static int executeBulk(ElasticsearchClient client, List<BulkOperation> ops)
            throws IOException {
        BulkResponse resp = client.bulk(BulkRequest.of(b -> b.operations(ops)));
        int success = 0;
        if (resp.errors()) {
            resp.items()
                    .forEach(
                            item -> {
                                if (item.error() == null) {
                                    // created
                                } else if (item.status() == 409) {
                                    // already exists due to create opType, treat as success for
                                    // idempotency
                                }
                            });
        }
        // Count successes as created or already-exists (409). The client doesn't give direct count;
        // approximate by total ops minus real errors != 409
        for (var item : resp.items()) {
            if (item.error() == null || item.status() == 409) {
                success++;
            }
        }
        return success;
    }

    private static List<SampleDoc> loadDocuments() {
        List<SampleDoc> all = new ArrayList<>();
        // Core topics
        all.add(
                new SampleDoc(
                        "flink-001",
                        "Apache Flink is a framework and distributed processing engine for stateful computations over unbounded and bounded data streams.",
                        Map.of("source", "official", "topic", "flink")));
        all.add(
                new SampleDoc(
                        "flink-002",
                        "Flink supports event-time processing with watermarks, enabling out-of-order stream handling.",
                        Map.of("source", "docs", "topic", "event-time")));
        all.add(
                new SampleDoc(
                        "flink-003",
                        "Checkpoints and savepoints provide fault tolerance in Flink, allowing exactly-once state consistency.",
                        Map.of("source", "docs", "topic", "fault-tolerance")));
        all.add(
                new SampleDoc(
                        "flink-004",
                        "The DataStream API offers low-level stream processing primitives with rich windowing and state APIs.",
                        Map.of("source", "docs", "topic", "datastream")));
        all.add(
                new SampleDoc(
                        "flink-005",
                        "The Table/SQL API offers relational processing on streams and batches, integrating with the optimizer.",
                        Map.of("source", "docs", "topic", "table")));
        all.add(
                new SampleDoc(
                        "flink-006",
                        "Flink can use RocksDB as a state backend for large-scale, disk-based keyed state.",
                        Map.of("source", "docs", "topic", "rocksdb")));
        all.add(
                new SampleDoc(
                        "flink-007",
                        "Kafka connector enables exactly-once end-to-end semantics with transactions in Flink.",
                        Map.of("source", "docs", "topic", "kafka")));
        all.add(
                new SampleDoc(
                        "flink-008",
                        "Async I/O in Flink helps hide latency of external requests by overlapping computation and IO.",
                        Map.of("source", "blog", "topic", "async-io")));
        all.add(
                new SampleDoc(
                        "flink-009",
                        "Flink SQL integrates relational operations with streaming semantics for continuous queries.",
                        Map.of("source", "docs", "topic", "flink-sql")));
        all.add(
                new SampleDoc(
                        "flink-010",
                        "Flink supports stateful functions and complex event processing for advanced stream applications.",
                        Map.of("source", "kb", "topic", "cep")));

        // Flink Agents & RAG
        all.add(
                new SampleDoc(
                        "agents-001",
                        "Flink Agents integrates LLM-powered agents with Flink to build RAG, tools, and multi-agent workflows on streaming data.",
                        Map.of("source", "docs", "topic", "flink-agents")));
        all.add(
                new SampleDoc(
                        "agents-002",
                        "Retrieval-Augmented Generation (RAG) retrieves context from a knowledge base to ground LLM responses.",
                        Map.of("source", "kb", "topic", "rag")));
        all.add(
                new SampleDoc(
                        "agents-003",
                        "Vector stores index embeddings to enable semantic search over large corpora.",
                        Map.of("source", "kb", "topic", "vector-store")));
        all.add(
                new SampleDoc(
                        "agents-004",
                        "Agents can call tools to interact with external systems, enabling action-based workflows.",
                        Map.of("source", "kb", "topic", "agents")));
        all.add(
                new SampleDoc(
                        "agents-005",
                        "In RAG, chunking and retrieval quality directly affect the final answer relevance.",
                        Map.of("source", "kb", "topic", "rag")));

        // Elasticsearch & vectors
        all.add(
                new SampleDoc(
                        "es-001",
                        "Elasticsearch supports dense_vector fields and KNN search for vector similarity queries.",
                        Map.of("source", "official", "topic", "elasticsearch")));
        all.add(
                new SampleDoc(
                        "es-002",
                        "Cosine similarity is commonly used for normalized embedding vectors in vector search.",
                        Map.of("source", "kb", "topic", "similarity")));
        all.add(
                new SampleDoc(
                        "es-003",
                        "Num candidates controls the trade-off between accuracy and speed in approximate KNN search.",
                        Map.of("source", "kb", "topic", "knn")));
        all.add(
                new SampleDoc(
                        "es-004",
                        "Use bulk indexing to efficiently insert many documents into Elasticsearch.",
                        Map.of("source", "kb", "topic", "bulk")));
        all.add(
                new SampleDoc(
                        "es-005",
                        "Filtering can be combined with vector search via post_filter to constrain results by metadata.",
                        Map.of("source", "kb", "topic", "filter")));

        // Languages & general CS
        all.add(
                new SampleDoc(
                        "python-001",
                        "Python is a high-level, interpreted programming language known for its readability and extensive ecosystem.",
                        Map.of("source", "wiki", "topic", "python")));
        all.add(
                new SampleDoc(
                        "java-001",
                        "Java is a class-based, object-oriented programming language designed to have as few implementation dependencies as possible.",
                        Map.of("source", "wiki", "topic", "java")));
        all.add(
                new SampleDoc(
                        "stream-001",
                        "Stream processing focuses on processing data continuously as it arrives, offering low latency.",
                        Map.of("source", "kb", "topic", "streaming")));
        all.add(
                new SampleDoc(
                        "batch-001",
                        "Batch processing handles finite datasets and is optimized for throughput over latency.",
                        Map.of("source", "kb", "topic", "batch")));
        all.add(
                new SampleDoc(
                        "ml-001",
                        "Embeddings map text into dense vectors where semantic similarity corresponds to geometric closeness.",
                        Map.of("source", "kb", "topic", "embeddings")));
        all.add(
                new SampleDoc(
                        "llm-001",
                        "Large language models can be grounded with retrieved context to reduce hallucinations.",
                        Map.of("source", "kb", "topic", "llm")));

        // CEP and patterns
        all.add(
                new SampleDoc(
                        "cep-001",
                        "Flink CEP library allows pattern detection on event streams with complex conditions and time windows.",
                        Map.of("source", "docs", "topic", "cep")));
        all.add(
                new SampleDoc(
                        "watermark-001",
                        "Watermarks are special markers in a stream that indicate progress of event time.",
                        Map.of("source", "docs", "topic", "watermark")));

        // Add a few more generic entries
        all.add(
                new SampleDoc(
                        "ops-001",
                        "Flink supports native Kubernetes deployments, offering reactive scaling and high availability.",
                        Map.of("source", "docs", "topic", "ops")));
        all.add(
                new SampleDoc(
                        "state-001",
                        "Keyed state in Flink enables per-key storage and computation, backed by efficient state backends.",
                        Map.of("source", "docs", "topic", "state")));
        all.add(
                new SampleDoc(
                        "windows-001",
                        "Flink supports tumbling, sliding, and session windows to aggregate events over time.",
                        Map.of("source", "docs", "topic", "window")));

        // Multilingual (Korean) short summaries
        all.add(
                new SampleDoc(
                        "ko-flink-001",
                        "Apache Flink은 무한/유한 스트림 데이터를 상태 기반으로 처리하는 분산 처리 엔진입니다.",
                        Map.of("source", "ko", "topic", "flink", "lang", "ko")));
        all.add(
                new SampleDoc(
                        "ko-rag-001",
                        "RAG는 지식 베이스에서 관련 문서를 검색하여 LLM 응답을 보강하는 방식입니다.",
                        Map.of("source", "ko", "topic", "rag", "lang", "ko")));
        all.add(
                new SampleDoc(
                        "ko-es-001",
                        "Elasticsearch의 dense_vector 필드는 임베딩 벡터를 저장하고 유사도 검색을 지원합니다.",
                        Map.of("source", "ko", "topic", "elasticsearch", "lang", "ko")));
        all.add(
                new SampleDoc(
                        "ko-python-001",
                        "파이썬은 가독성이 좋고 생태계가 풍부한 고수준 프로그래밍 언어입니다.",
                        Map.of("source", "ko", "topic", "python", "lang", "ko")));
        all.add(
                new SampleDoc(
                        "ko-stream-001",
                        "스트림 처리란 데이터가 도착하는 즉시 지속적으로 처리하는 방식으로 낮은 지연을 제공합니다.",
                        Map.of("source", "ko", "topic", "streaming", "lang", "ko")));

        return all;
    }

    private static List<SampleDoc> loadFromClasspath(String resourcePath) {
        if (resourcePath == null || resourcePath.isEmpty()) {
            System.err.println("[KB Setup] ES_POPULATE_PATH is required for classpath source.");
            return List.of();
        }
        try (java.io.InputStream in =
                Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                System.err.println("[KB Setup] Classpath resource not found: " + resourcePath);
                return List.of();
            }
            return parseJsonOrJsonl(new java.io.InputStreamReader(in));
        } catch (Exception e) {
            System.err.println("[KB Setup] Failed to read classpath resource: " + e.getMessage());
            return List.of();
        }
    }

    private static List<SampleDoc> loadFromFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            System.err.println("[KB Setup] ES_POPULATE_PATH is required for file source.");
            return List.of();
        }
        try (java.io.Reader reader =
                java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(filePath))) {
            return parseJsonOrJsonl(reader);
        } catch (Exception e) {
            System.err.println("[KB Setup] Failed to read file: " + e.getMessage());
            return List.of();
        }
    }

    private static List<SampleDoc> parseJsonOrJsonl(java.io.Reader reader) throws IOException {
        List<SampleDoc> docs = new ArrayList<>();
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        // Try JSON array first
        try {
            List<Map<String, Object>> arr = mapper.readValue(reader, List.class);
            for (Map<String, Object> m : arr) {
                String id = String.valueOf(m.getOrDefault("id", UUID.randomUUID().toString()));
                String content = String.valueOf(m.get("content"));
                if (content == null || content.isEmpty()) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata =
                        (Map<String, Object>) m.getOrDefault("metadata", Map.of());
                docs.add(new SampleDoc(id, content, metadata));
            }
            return docs;
        } catch (Exception ignore) {
            // Fallback to JSONL
        }

        // Re-open reader as we consumed it
        if (reader instanceof java.io.BufferedReader) {
            // no-op
        }
        try (java.io.BufferedReader br = new java.io.BufferedReader(reader)) {
            String line;
            com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                Map<String, Object> m = om.readValue(line, Map.class);
                String id = String.valueOf(m.getOrDefault("id", UUID.randomUUID().toString()));
                String content = String.valueOf(m.get("content"));
                if (content == null || content.isEmpty()) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata =
                        (Map<String, Object>) m.getOrDefault("metadata", Map.of());
                docs.add(new SampleDoc(id, content, metadata));
            }
        }
        return docs;
    }

    // Removed unused exists(...) helper to simplify the class

    private static void ensureIndex(
            ElasticsearchClient client,
            String index,
            String vectorField,
            int dims,
            String similarity)
            throws IOException {
        BooleanResponse exists = client.indices().exists(ExistsRequest.of(b -> b.index(index)));
        if (exists.value()) {
            return;
        }

        // Build simple mapping with text + metadata + dense_vector
        Map<String, Object> mapping = new HashMap<>();
        Map<String, Object> props = new HashMap<>();
        props.put("content", Map.of("type", "text"));
        props.put("metadata", Map.of("type", "object", "enabled", false));
        Map<String, Object> vector = new HashMap<>();
        vector.put("type", "dense_vector");
        vector.put("dims", dims);
        if (similarity != null && !similarity.isEmpty()) {
            vector.put("similarity", similarity);
        }
        props.put(vectorField, vector);
        mapping.put("properties", props);

        CreateIndexResponse createRes =
                client.indices()
                        .create(
                                CreateIndexRequest.of(
                                        b ->
                                                b.index(index)
                                                        .withJson(
                                                                new java.io.StringReader(
                                                                        toJson(
                                                                                Map.of(
                                                                                        "mappings",
                                                                                        mapping))))));

        if (!createRes.acknowledged()) {
            throw new IOException("Failed to create index: " + index);
        }
    }

    private static String toJson(Map<String, Object> map) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ElasticsearchClient buildEsClient(String hostUrl) {
        List<HttpHost> httpHosts = new ArrayList<>();
        if (hostUrl == null || hostUrl.isEmpty()) {
            hostUrl = "http://localhost:9200";
        }
        httpHosts.add(HttpHost.create(hostUrl));

        RestClientBuilder builder = RestClient.builder(httpHosts.toArray(new HttpHost[0]));

        // Optional auth via system properties
        String apiKeyBase64 = System.getProperty("ES_API_KEY_BASE64");
        String apiKeyId = System.getProperty("ES_API_KEY_ID");
        String apiKeySecret = System.getProperty("ES_API_KEY_SECRET");
        String username = System.getProperty("ES_USERNAME");
        String password = System.getProperty("ES_PASSWORD");

        if (apiKeyBase64 != null || (apiKeyId != null && apiKeySecret != null)) {
            String token = apiKeyBase64;
            if (token == null) {
                String idColonSecret = apiKeyId + ":" + apiKeySecret;
                token =
                        Base64.getEncoder()
                                .encodeToString(idColonSecret.getBytes(StandardCharsets.UTF_8));
            }
            final Header[] defaultHeaders =
                    new Header[] {new BasicHeader("Authorization", "ApiKey " + token)};
            builder.setDefaultHeaders(defaultHeaders);
        } else if (username != null && password != null) {
            final BasicCredentialsProvider creds = new BasicCredentialsProvider();
            creds.setCredentials(
                    AuthScope.ANY, new UsernamePasswordCredentials(username, password));
            builder.setHttpClientConfigCallback(hcb -> hcb.setDefaultCredentialsProvider(creds));
        }

        RestClient lowLevelClient = builder.build();
        ElasticsearchTransport transport =
                new RestClientTransport(lowLevelClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }
}
