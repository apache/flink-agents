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
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelSetup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.vectorstores.BaseVectorStore;
import org.apache.flink.agents.api.vectorstores.CollectionManageableVectorStore;
import org.apache.flink.agents.api.vectorstores.Document;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.*;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiFunction;

/**
 * OpenSearch vector store supporting both OpenSearch Serverless (AOSS) and
 * OpenSearch Service domains, with IAM (SigV4) or basic auth.
 *
 * <p>Implements {@link CollectionManageableVectorStore} for Long-Term Memory support.
 * Collections map to OpenSearch indices. Collection metadata is stored in a
 * dedicated {@code _collection_metadata} index.
 *
 * <p>Parameters:
 * <ul>
 *   <li><b>embedding_model</b> (required): name of the embedding model resource</li>
 *   <li><b>endpoint</b> (required): OpenSearch endpoint URL</li>
 *   <li><b>index</b> (required): default index name</li>
 *   <li><b>service_type</b> (optional): "serverless" (default) or "domain"</li>
 *   <li><b>auth</b> (optional): "iam" (default) or "basic"</li>
 *   <li><b>username</b> (required if auth=basic): basic auth username</li>
 *   <li><b>password</b> (required if auth=basic): basic auth password</li>
 *   <li><b>vector_field</b> (optional): vector field name (default: "embedding")</li>
 *   <li><b>content_field</b> (optional): content field name (default: "content")</li>
 *   <li><b>region</b> (optional): AWS region (default: us-east-1)</li>
 *   <li><b>dims</b> (optional): vector dimensions for index creation (default: 1024)</li>
 * </ul>
 */
public class OpenSearchVectorStore extends BaseVectorStore
        implements CollectionManageableVectorStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String METADATA_INDEX = "flink_agents_collection_metadata";

    private final String endpoint;
    private final String index;
    private final String vectorField;
    private final String contentField;
    private final int dims;
    private final Region region;
    private final boolean serverless;
    private final boolean useIamAuth;
    private final String basicAuthHeader;

    private final SdkHttpClient httpClient;
    private final Aws4Signer signer;

    public OpenSearchVectorStore(
            ResourceDescriptor descriptor,
            BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);

        this.endpoint = descriptor.getArgument("endpoint");
        this.index = descriptor.getArgument("index");
        this.vectorField = Objects.requireNonNullElse(descriptor.getArgument("vector_field"), "embedding");
        this.contentField = Objects.requireNonNullElse(descriptor.getArgument("content_field"), "content");
        Integer dimsArg = descriptor.getArgument("dims");
        this.dims = dimsArg != null ? dimsArg : 1024;

        String regionStr = descriptor.getArgument("region");
        this.region = Region.of(regionStr != null ? regionStr : "us-east-1");

        String serviceType = Objects.requireNonNullElse(descriptor.getArgument("service_type"), "serverless");
        this.serverless = serviceType.equalsIgnoreCase("serverless");

        String auth = Objects.requireNonNullElse(descriptor.getArgument("auth"), "iam");
        this.useIamAuth = auth.equalsIgnoreCase("iam");

        if (!useIamAuth) {
            String username = descriptor.getArgument("username");
            String password = descriptor.getArgument("password");
            if (username == null || password == null) {
                throw new IllegalArgumentException("username and password required for basic auth");
            }
            this.basicAuthHeader = "Basic " + Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        } else {
            this.basicAuthHeader = null;
        }

        this.httpClient = ApacheHttpClient.create();
        this.signer = Aws4Signer.create();

        Integer bulkMb = descriptor.getArgument("max_bulk_mb");
        this.maxBulkBytes = (bulkMb != null ? bulkMb : 5) * 1024 * 1024;
    }

    /** Batch-embeds all documents in a single call, then delegates to addEmbedding. */
    @Override
    public List<String> add(List<Document> documents, @Nullable String collection,
                            Map<String, Object> extraArgs) throws IOException {
        BaseEmbeddingModelSetup emb = (BaseEmbeddingModelSetup)
                this.getResource.apply(this.embeddingModel, ResourceType.EMBEDDING_MODEL);
        List<String> texts = new ArrayList<>();
        List<Integer> needsEmbedding = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            if (documents.get(i).getEmbedding() == null) {
                texts.add(documents.get(i).getContent());
                needsEmbedding.add(i);
            }
        }
        if (!texts.isEmpty()) {
            List<float[]> embeddings = emb.embed(texts);
            for (int j = 0; j < needsEmbedding.size(); j++) {
                documents.get(needsEmbedding.get(j)).setEmbedding(embeddings.get(j));
            }
        }
        return this.addEmbedding(documents, collection, extraArgs);
    }

    // ---- CollectionManageableVectorStore ----

    @Override
    public Collection getOrCreateCollection(String name, Map<String, Object> metadata)
            throws Exception {
        String idx = sanitizeIndexName(name);
        if (!indexExists(idx)) {
            createKnnIndex(idx);
        }
        // Store metadata
        ensureMetadataIndex();
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("collection_name", name);
        doc.set("metadata", MAPPER.valueToTree(metadata));
        executeRequest("PUT", "/" + METADATA_INDEX + "/_doc/" + idx, doc.toString());
        executeRequest("POST", "/" + METADATA_INDEX + "/_refresh", null);
        return new Collection(name, metadata != null ? metadata : Collections.emptyMap());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Collection getCollection(String name) throws Exception {
        String idx = sanitizeIndexName(name);
        if (!indexExists(idx)) {
            throw new RuntimeException("Collection " + name + " not found");
        }
        try {
            ensureMetadataIndex();
            JsonNode resp = executeRequest("GET", "/" + METADATA_INDEX + "/_doc/" + idx, null);
            if (resp.has("found") && resp.get("found").asBoolean()) {
                Map<String, Object> meta = MAPPER.convertValue(
                        resp.path("_source").path("metadata"), Map.class);
                return new Collection(name, meta != null ? meta : Collections.emptyMap());
            }
        } catch (Exception ignored) {
            // metadata index may not exist yet
        }
        return new Collection(name, Collections.emptyMap());
    }

    @Override
    public Collection deleteCollection(String name) throws Exception {
        String idx = sanitizeIndexName(name);
        Collection col = getCollection(name);
        executeRequest("DELETE", "/" + idx, null);
        try {
            executeRequest("DELETE", "/" + METADATA_INDEX + "/_doc/" + idx, null);
        } catch (Exception ignored) {
        }
        return col;
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
        String body = String.format(
                "{\"settings\":{\"index\":{\"knn\":true}},"
                + "\"mappings\":{\"properties\":{\"%s\":{\"type\":\"knn_vector\",\"dimension\":%d},"
                + "\"%s\":{\"type\":\"text\"},\"metadata\":{\"type\":\"object\"}}}}",
                vectorField, dims, contentField);
        try {
            executeRequest("PUT", "/" + idx, body);
        } catch (RuntimeException e) {
            if (!e.getMessage().contains("resource_already_exists_exception")) {
                throw e;
            }
        }
    }

    private void ensureMetadataIndex() {
        if (!indexExists(METADATA_INDEX)) {
            try {
                executeRequest("PUT", "/" + METADATA_INDEX,
                        "{\"mappings\":{\"properties\":{\"collection_name\":{\"type\":\"keyword\"},"
                        + "\"metadata\":{\"type\":\"object\"}}}}");
            } catch (RuntimeException e) {
                if (!e.getMessage().contains("resource_already_exists_exception")) {
                    throw e;
                }
            }
        }
    }

    /** Sanitize collection name to valid OpenSearch index name (lowercase, no special chars). */
    private String sanitizeIndexName(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\-_]", "-")
                .replaceAll("^[^a-z]+", "a-");  // must start with letter
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
    public long size(@Nullable String collection) throws Exception {
        String idx = collection != null ? sanitizeIndexName(collection) : this.index;
        JsonNode response = executeRequest("GET", "/" + idx + "/_count", null);
        return response.get("count").asLong();
    }

    @Override
    public List<Document> get(@Nullable List<String> ids, @Nullable String collection,
                              Map<String, Object> extraArgs) throws IOException {
        String idx = collection != null ? sanitizeIndexName(collection) : this.index;
        if (ids != null && !ids.isEmpty()) {
            ObjectNode body = MAPPER.createObjectNode();
            ArrayNode idsArray = body.putObject("query").putObject("ids").putArray("values");
            ids.forEach(idsArray::add);
            body.put("size", ids.size());
            return parseHits(executeRequest("POST", "/" + idx + "/_search", body.toString()));
        }
        return parseHits(executeRequest("POST", "/" + idx + "/_search",
                "{\"query\":{\"match_all\":{}},\"size\":10000}"));
    }

    @Override
    public void delete(@Nullable List<String> ids, @Nullable String collection,
                       Map<String, Object> extraArgs) throws IOException {
        String idx = collection != null ? sanitizeIndexName(collection) : this.index;
        if (ids != null && !ids.isEmpty()) {
            ObjectNode body = MAPPER.createObjectNode();
            ArrayNode idsArray = body.putObject("query").putObject("ids").putArray("values");
            ids.forEach(idsArray::add);
            executeRequest("POST", "/" + idx + "/_delete_by_query", body.toString());
        } else {
            executeRequest("POST", "/" + idx + "/_delete_by_query",
                    "{\"query\":{\"match_all\":{}}}");
        }
        executeRequest("POST", "/" + idx + "/_refresh", null);
    }

    @Override
    protected List<Document> queryEmbedding(float[] embedding, int limit,
                                            @Nullable String collection, Map<String, Object> args) {
        try {
            String idx = collection != null ? sanitizeIndexName(collection) : this.index;
            int k = (int) args.getOrDefault("k", Math.max(1, limit));

            ObjectNode body = MAPPER.createObjectNode();
            body.put("size", k);
            ObjectNode knnQuery = body.putObject("query").putObject("knn");
            ObjectNode fieldQuery = knnQuery.putObject(vectorField);
            ArrayNode vectorArray = fieldQuery.putArray("vector");
            for (float v : embedding) vectorArray.add(v);
            fieldQuery.put("k", k);
            if (args.containsKey("min_score")) {
                fieldQuery.put("min_score", ((Number) args.get("min_score")).floatValue());
            }
            if (args.containsKey("ef_search")) {
                fieldQuery.putObject("method_parameters")
                        .put("ef_search", ((Number) args.get("ef_search")).intValue());
            }
            if (args.containsKey("filter_query")) {
                fieldQuery.set("filter", MAPPER.readTree((String) args.get("filter_query")));
            }

            return parseHits(executeRequest("POST", "/" + idx + "/_search", body.toString()));
        } catch (Exception e) {
            throw new RuntimeException("OpenSearch KNN search failed.", e);
        }
    }

    /** Max bulk payload size in bytes (default 5MB, configurable via constructor). */
    private final int maxBulkBytes;

    @Override
    protected List<String> addEmbedding(List<Document> documents, @Nullable String collection,
                                        Map<String, Object> extraArgs) throws IOException {
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
                for (float v : doc.getEmbedding()) vec.add(v);
            }
            if (doc.getMetadata() != null) {
                source.set("metadata", MAPPER.valueToTree(doc.getMetadata()));
            }
            String sourceLine = source.toString() + "\n";

            int entryBytes = actionLine.length() + sourceLine.length();

            // Flush if adding this entry would exceed the bulk size limit
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
            docs.add(new Document(content, metadata, id));
        }
        return docs;
    }

    @SuppressWarnings("unchecked")
    private JsonNode executeRequest(String method, String path, @Nullable String body) {
        try {
            URI uri = URI.create(endpoint + path);
            SdkHttpFullRequest.Builder reqBuilder = SdkHttpFullRequest.builder()
                    .uri(uri)
                    .method(SdkHttpMethod.valueOf(method))
                    .putHeader("Content-Type", "application/json");

            if (body != null) {
                reqBuilder.contentStreamProvider(() ->
                        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            }

            SdkHttpFullRequest request;
            if (useIamAuth) {
                Aws4SignerParams signerParams = Aws4SignerParams.builder()
                        .awsCredentials(DefaultCredentialsProvider.create().resolveCredentials())
                        .signingName(serverless ? "aoss" : "es")
                        .signingRegion(region)
                        .build();
                request = signer.sign(reqBuilder.build(), signerParams);
            } else {
                request = reqBuilder
                        .putHeader("Authorization", basicAuthHeader)
                        .build();
            }

            HttpExecuteRequest.Builder execBuilder = HttpExecuteRequest.builder().request(request);
            if (request.contentStreamProvider().isPresent()) {
                execBuilder.contentStreamProvider(request.contentStreamProvider().get());
            }

            HttpExecuteResponse response = httpClient.prepareRequest(execBuilder.build()).call();
            int statusCode = response.httpResponse().statusCode();

            if ("HEAD".equals(method)) {
                if (statusCode >= 400) {
                    throw new RuntimeException("OpenSearch HEAD request failed (" + statusCode + ")");
                }
                return MAPPER.createObjectNode().put("status", statusCode);
            }

            String responseBody = new String(
                    response.responseBody().orElseThrow().readAllBytes());

            if (response.httpResponse().statusCode() >= 400) {
                throw new RuntimeException("OpenSearch request failed (" +
                        response.httpResponse().statusCode() + "): " + responseBody);
            }
            return MAPPER.readTree(responseBody);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("OpenSearch request failed.", e);
        }
    }
}
