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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.agents.api.RetryExecutor;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.vectorstores.BaseVectorStore;
import org.apache.flink.agents.api.vectorstores.CollectionManageableVectorStore;
import org.apache.flink.agents.api.vectorstores.Document;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;

import javax.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * OpenSearch vector store supporting both OpenSearch Serverless (AOSS) and OpenSearch Service
 * domains, with IAM (SigV4) or basic auth.
 *
 * <p>Implements {@link CollectionManageableVectorStore}: collections map to OpenSearch indices.
 * OpenSearch does not natively support attaching arbitrary metadata to an index, so this
 * implementation does not persist any collection-level metadata — callers needing per-document
 * attributes should put them on the documents themselves.
 *
 * <p>Supported parameters:
 *
 * <ul>
 *   <li><b>embedding_model</b> (required): name of the embedding model resource
 *   <li><b>endpoint</b> (required): OpenSearch endpoint URL
 *   <li><b>index</b> (required): default index name
 *   <li><b>service_type</b> (optional): "serverless" (default) or "domain"
 *   <li><b>auth</b> (optional): "iam" (default) or "basic"
 *   <li><b>username</b> (required if auth=basic): basic auth username
 *   <li><b>password</b> (required if auth=basic): basic auth password
 *   <li><b>vector_field</b> (optional): vector field name (default: "embedding")
 *   <li><b>content_field</b> (optional): content field name (default: "content")
 *   <li><b>region</b> (optional): AWS region (default: us-east-1)
 *   <li><b>dims</b> (optional): vector dimensions for index creation (default: 1024)
 *   <li><b>max_bulk_mb</b> (optional): max bulk payload size in MB (default: 5)
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * @VectorStore
 * public static ResourceDescriptor opensearchStore() {
 *     return ResourceDescriptor.Builder.newBuilder(OpenSearchVectorStore.class.getName())
 *             .addInitialArgument("embedding_model", "bedrockEmbeddingSetup")
 *             .addInitialArgument("endpoint", "https://my-domain.us-east-1.es.amazonaws.com")
 *             .addInitialArgument("index", "my-vectors")
 *             .addInitialArgument("service_type", "domain")
 *             .addInitialArgument("auth", "iam")
 *             .addInitialArgument("dims", 1024)
 *             .build();
 * }
 * }</pre>
 */
public class OpenSearchVectorStore extends BaseVectorStore
        implements CollectionManageableVectorStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int DEFAULT_GET_LIMIT = 10000;

    private final String endpoint;
    private final String index;
    private final String vectorField;
    private final String contentField;
    private final int dims;
    private final Region region;
    private final boolean serverless;
    private final boolean useIamAuth;
    private final String basicAuthHeader;
    private final int maxBulkBytes;

    private final SdkHttpClient httpClient;
    // TODO: Aws4Signer is legacy; migrate to AwsV4HttpSigner from http-auth-aws in a follow-up.
    private final Aws4Signer signer;
    private final DefaultCredentialsProvider credentialsProvider;
    private final RetryExecutor retryExecutor;

    public OpenSearchVectorStore(
            ResourceDescriptor descriptor, BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);

        this.endpoint = descriptor.getArgument("endpoint");
        if (this.endpoint == null || this.endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint is required for OpenSearchVectorStore");
        }

        this.index = descriptor.getArgument("index");

        this.vectorField =
                Objects.requireNonNullElse(descriptor.getArgument("vector_field"), "embedding");
        this.contentField =
                Objects.requireNonNullElse(descriptor.getArgument("content_field"), "content");
        Integer dimsArg = descriptor.getArgument("dims");
        this.dims = dimsArg != null ? dimsArg : 1024;

        String regionStr = descriptor.getArgument("region");
        this.region = Region.of(regionStr != null ? regionStr : "us-east-1");

        String serviceType =
                Objects.requireNonNullElse(descriptor.getArgument("service_type"), "serverless");
        this.serverless = serviceType.equalsIgnoreCase("serverless");

        String auth = Objects.requireNonNullElse(descriptor.getArgument("auth"), "iam");
        this.useIamAuth = auth.equalsIgnoreCase("iam");

        if (!useIamAuth) {
            String username = descriptor.getArgument("username");
            String password = descriptor.getArgument("password");
            if (username == null || password == null) {
                throw new IllegalArgumentException("username and password required for basic auth");
            }
            this.basicAuthHeader =
                    "Basic "
                            + Base64.getEncoder()
                                    .encodeToString(
                                            (username + ":" + password)
                                                    .getBytes(StandardCharsets.UTF_8));
        } else {
            this.basicAuthHeader = null;
        }

        this.httpClient = ApacheHttpClient.create();
        this.signer = Aws4Signer.create();
        this.credentialsProvider = DefaultCredentialsProvider.builder().build();

        Integer bulkMb = descriptor.getArgument("max_bulk_mb");
        this.maxBulkBytes = (bulkMb != null ? bulkMb : 5) * 1024 * 1024;

        this.retryExecutor =
                RetryExecutor.builder()
                        .maxRetries(5)
                        .initialBackoffMs(200)
                        .retryablePredicate(OpenSearchVectorStore::isRetryableStatus)
                        .build();
    }

    @Override
    public void close() throws Exception {
        this.httpClient.close();
        this.credentialsProvider.close();
    }

    /**
     * Batch-embeds all documents in a single call, then delegates to addEmbedding.
     *
     * <p>TODO: This batch embedding logic is duplicated in S3VectorsVectorStore. Consider
     * extracting to BaseVectorStore in a follow-up (would also benefit ElasticsearchVectorStore).
     */
    @Override
    public List<String> add(
            List<Document> documents, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException {
        List<String> texts = new ArrayList<>();
        List<Integer> needsEmbedding = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            if (documents.get(i).getEmbedding() == null) {
                texts.add(documents.get(i).getContent());
                needsEmbedding.add(i);
            }
        }
        if (!texts.isEmpty()) {
            List<float[]> embeddings = this.embeddingModel.embed(texts);
            for (int j = 0; j < needsEmbedding.size(); j++) {
                documents.get(needsEmbedding.get(j)).setEmbedding(embeddings.get(j));
            }
        }
        return this.addEmbedding(documents, collection, extraArgs);
    }

    // ---- CollectionManageableVectorStore ----

    /**
     * Creates the OpenSearch index for the given collection if it does not already exist.
     *
     * <p>OpenSearch does not natively support attaching arbitrary metadata to an index, so any
     * {@code metadata} key in {@code kwargs} is ignored.
     */
    @Override
    public void createCollectionIfNotExists(String name, Map<String, Object> kwargs)
            throws Exception {
        String idx = sanitizeIndexName(name);
        if (!indexExists(idx)) {
            createKnnIndex(idx);
        }
    }

    @Override
    public void deleteCollection(String name) throws Exception {
        String idx = sanitizeIndexName(name);
        executeRequest("DELETE", "/" + idx, null);
    }

    private boolean indexExists(String idx) {
        try {
            executeRequest("HEAD", "/" + idx, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void createKnnIndex(String idx) {
        String body =
                String.format(
                        "{\"settings\":{\"index\":{\"knn\":true}},"
                                + "\"mappings\":{\"properties\":{\"%s\":{\"type\":\"knn_vector\","
                                + "\"dimension\":%d},\"%s\":{\"type\":\"text\"},"
                                + "\"metadata\":{\"type\":\"object\"}}}}",
                        vectorField, dims, contentField);
        try {
            executeRequest("PUT", "/" + idx, body);
        } catch (RuntimeException e) {
            if (!e.getMessage().contains("resource_already_exists_exception")) {
                throw e;
            }
        }
    }

    /** Sanitize collection name to valid OpenSearch index name (lowercase, no special chars). */
    private String sanitizeIndexName(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\-_]", "-")
                .replaceAll("^[^a-z]+", "a-");
    }

    // ---- BaseVectorStore ----

    @Override
    public Map<String, Object> getStoreKwargs() {
        Map<String, Object> m = new HashMap<>();
        m.put("index", index);
        m.put("vector_field", vectorField);
        return m;
    }

    @Override
    public List<Document> get(
            @Nullable List<String> ids,
            @Nullable String collection,
            @Nullable Map<String, Object> filters,
            @Nullable Integer limit,
            Map<String, Object> extraArgs)
            throws IOException {
        String idx = collection != null ? sanitizeIndexName(collection) : this.index;
        ObjectNode body = MAPPER.createObjectNode();
        if (ids != null && !ids.isEmpty()) {
            ArrayNode idsArray = body.putObject("query").putObject("ids").putArray("values");
            ids.forEach(idsArray::add);
            body.put("size", ids.size());
        } else {
            int effectiveLimit = limit != null ? limit : DEFAULT_GET_LIMIT;
            body.put("size", effectiveLimit);
            JsonNode filterQuery = filtersToBoolQuery(filters);
            if (filterQuery != null) {
                body.set("query", filterQuery);
            } else {
                body.putObject("query").putObject("match_all");
            }
        }
        return parseHits(executeRequest("POST", "/" + idx + "/_search", body.toString()));
    }

    @Override
    public void delete(
            @Nullable List<String> ids,
            @Nullable String collection,
            @Nullable Map<String, Object> filters,
            Map<String, Object> extraArgs)
            throws IOException {
        String idx = collection != null ? sanitizeIndexName(collection) : this.index;
        ObjectNode body = MAPPER.createObjectNode();
        if (ids != null && !ids.isEmpty()) {
            ArrayNode idsArray = body.putObject("query").putObject("ids").putArray("values");
            ids.forEach(idsArray::add);
        } else {
            JsonNode filterQuery = filtersToBoolQuery(filters);
            if (filterQuery != null) {
                body.set("query", filterQuery);
            } else {
                body.putObject("query").putObject("match_all");
            }
        }
        executeRequest("POST", "/" + idx + "/_delete_by_query", body.toString());
        executeRequest("POST", "/" + idx + "/_refresh", null);
    }

    @Override
    public List<Document> queryEmbedding(
            float[] embedding,
            int limit,
            @Nullable String collection,
            @Nullable Map<String, Object> filters,
            Map<String, Object> args) {
        try {
            String idx = collection != null ? sanitizeIndexName(collection) : this.index;
            int k = (int) args.getOrDefault("k", Math.max(1, limit));

            ObjectNode body = MAPPER.createObjectNode();
            body.put("size", k);
            ObjectNode knnQuery = body.putObject("query").putObject("knn");
            ObjectNode fieldQuery = knnQuery.putObject(vectorField);
            ArrayNode vectorArray = fieldQuery.putArray("vector");
            for (float v : embedding) {
                vectorArray.add(v);
            }
            fieldQuery.put("k", k);
            if (args.containsKey("min_score")) {
                fieldQuery.put("min_score", ((Number) args.get("min_score")).floatValue());
            }
            if (args.containsKey("ef_search")) {
                fieldQuery
                        .putObject("method_parameters")
                        .put("ef_search", ((Number) args.get("ef_search")).intValue());
            }
            JsonNode rawFilter =
                    args.containsKey("filter_query")
                            ? MAPPER.readTree((String) args.get("filter_query"))
                            : null;
            JsonNode dslFilter = filtersToBoolQuery(filters);
            JsonNode combined = combineQueries(rawFilter, dslFilter);
            if (combined != null) {
                fieldQuery.set("filter", combined);
            }

            return parseHits(executeRequest("POST", "/" + idx + "/_search", body.toString()));
        } catch (Exception e) {
            throw new RuntimeException("OpenSearch KNN search failed.", e);
        }
    }

    @Override
    public void updateEmbedding(
            List<Document> documents, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException {
        // OpenSearch's bulk index operation is upsert-by-id, so addEmbedding doubles as update.
        // BaseVectorStore.update() already enforces that every document carries an id, so
        // addEmbedding will not generate new ones here.
        addEmbedding(documents, collection, extraArgs);
    }

    @Override
    public List<String> addEmbedding(
            List<Document> documents, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException {
        String idx = collection != null ? sanitizeIndexName(collection) : this.index;
        if (!indexExists(idx)) {
            createKnnIndex(idx);
        }
        List<String> allIds = new ArrayList<>();
        StringBuilder bulk = new StringBuilder();
        int bulkBytes = 0;

        for (Document doc : documents) {
            String id = doc.getId() != null ? doc.getId() : UUID.randomUUID().toString();
            allIds.add(id);

            ObjectNode action = MAPPER.createObjectNode();
            action.putObject("index").put("_index", idx).put("_id", id);
            String actionLine = action.toString() + "\n";

            ObjectNode source = MAPPER.createObjectNode();
            source.put(contentField, doc.getContent());
            if (doc.getEmbedding() != null) {
                ArrayNode vec = source.putArray(vectorField);
                for (float v : doc.getEmbedding()) {
                    vec.add(v);
                }
            }
            if (doc.getMetadata() != null) {
                source.set("metadata", MAPPER.valueToTree(doc.getMetadata()));
            }
            String sourceLine = source.toString() + "\n";

            int entryBytes = actionLine.length() + sourceLine.length();

            if (bulkBytes > 0 && bulkBytes + entryBytes > maxBulkBytes) {
                executeRequest("POST", "/_bulk", bulk.toString());
                bulk.setLength(0);
                bulkBytes = 0;
            }

            bulk.append(actionLine).append(sourceLine);
            bulkBytes += entryBytes;
        }

        if (bulkBytes > 0) {
            executeRequest("POST", "/_bulk", bulk.toString());
        }
        executeRequest("POST", "/" + idx + "/_refresh", null);
        return allIds;
    }

    @SuppressWarnings("unchecked")
    private List<Document> parseHits(JsonNode response) {
        List<Document> docs = new ArrayList<>();
        JsonNode hits = response.path("hits").path("hits");
        for (JsonNode hit : hits) {
            String id = hit.get("_id").asText();
            JsonNode source = hit.get("_source");
            String content = source.has(contentField) ? source.get(contentField).asText() : "";
            Map<String, Object> metadata = new HashMap<>();
            if (source.has("metadata")) {
                metadata = MAPPER.convertValue(source.get("metadata"), Map.class);
            }
            JsonNode scoreNode = hit.get("_score");
            Float score =
                    (scoreNode == null || scoreNode.isNull()) ? null : (float) scoreNode.asDouble();
            docs.add(new Document(content, metadata, id, null, score));
        }
        return docs;
    }

    /**
     * Translate the unified equality-only filter DSL into an OpenSearch {@code bool/must} of {@code
     * term} clauses against {@code metadata.<key>.keyword}, since metadata is stored under the
     * {@code metadata} object and OpenSearch dynamic mapping exposes string fields as {@code
     * <field>.keyword} for exact matching. Returns {@code null} when there is nothing to filter on.
     */
    @Nullable
    private JsonNode filtersToBoolQuery(@Nullable Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode must = root.putObject("bool").putArray("must");
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            ObjectNode termWrap = MAPPER.createObjectNode();
            termWrap.putObject("term")
                    .putPOJO("metadata." + entry.getKey() + ".keyword", entry.getValue());
            must.add(termWrap);
        }
        return root;
    }

    /**
     * AND together a raw filter (passed in via {@code extraArgs.filter_query}) and the translated
     * unified-DSL filter under an outer {@code bool/must}. When only one is present it is returned
     * as-is.
     */
    @Nullable
    private JsonNode combineQueries(@Nullable JsonNode raw, @Nullable JsonNode dsl) {
        if (raw == null) {
            return dsl;
        }
        if (dsl == null) {
            return raw;
        }
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode must = root.putObject("bool").putArray("must");
        must.add(raw);
        must.add(dsl);
        return root;
    }

    private JsonNode executeRequest(String method, String path, @Nullable String body) {
        return retryExecutor.execute(
                () -> doExecuteRequest(method, path, body), "OpenSearchRequest");
    }

    private static boolean isRetryableStatus(Exception e) {
        String msg = e.getMessage();
        return msg != null
                && (msg.contains("(429)") || msg.contains("(503)") || msg.contains("(502)"));
    }

    private JsonNode doExecuteRequest(String method, String path, @Nullable String body) {
        try {
            URI uri = URI.create(endpoint + path);
            SdkHttpFullRequest.Builder reqBuilder =
                    SdkHttpFullRequest.builder()
                            .uri(uri)
                            .method(SdkHttpMethod.valueOf(method))
                            .putHeader("Content-Type", "application/json");

            if (body != null) {
                reqBuilder.contentStreamProvider(
                        () -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            }

            SdkHttpFullRequest request;
            if (useIamAuth) {
                AwsCredentials credentials = credentialsProvider.resolveCredentials();
                Aws4SignerParams signerParams =
                        Aws4SignerParams.builder()
                                .awsCredentials(credentials)
                                .signingName(serverless ? "aoss" : "es")
                                .signingRegion(region)
                                .build();
                request = signer.sign(reqBuilder.build(), signerParams);
            } else {
                request = reqBuilder.putHeader("Authorization", basicAuthHeader).build();
            }

            HttpExecuteRequest.Builder execBuilder = HttpExecuteRequest.builder().request(request);
            if (request.contentStreamProvider().isPresent()) {
                execBuilder.contentStreamProvider(request.contentStreamProvider().get());
            }

            HttpExecuteResponse response = httpClient.prepareRequest(execBuilder.build()).call();
            int statusCode = response.httpResponse().statusCode();

            if ("HEAD".equals(method)) {
                if (statusCode >= 400) {
                    throw new RuntimeException(
                            "OpenSearch HEAD request failed (" + statusCode + ")");
                }
                return MAPPER.createObjectNode().put("status", statusCode);
            }

            String responseBody = new String(response.responseBody().orElseThrow().readAllBytes());

            if (statusCode >= 400) {
                throw new RuntimeException(
                        "OpenSearch request failed (" + statusCode + "): " + responseBody);
            }
            return MAPPER.readTree(responseBody);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("OpenSearch request failed.", e);
        }
    }
}
