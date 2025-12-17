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

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.vectorstores.BaseVectorStore;
import org.apache.flink.agents.api.vectorstores.Document;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiFunction;

/**
 * Elasticsearch-backed implementation of a vector store.
 *
 * <p>This implementation executes approximate nearest neighbor (ANN) KNN queries against an
 * Elasticsearch index that contains a dense vector field. It integrates with an embedding model
 * (configured via the {@code embedding_model} resource argument inherited from {@link
 * BaseVectorStore}) to convert query text into embeddings and then performs vector search using
 * Elasticsearch's KNN capabilities.
 *
 * <p>Configuration is provided through {@link
 * org.apache.flink.agents.api.resource.ResourceDescriptor} arguments. The most relevant ones are:
 *
 * <ul>
 *   <li>{@code index} (required): Target index name.
 *   <li>{@code vector_field} (required): Name of the dense vector field used for KNN.
 *   <li>{@code dims} (optional): Vector dimensionality; defaults to {@link #DEFAULT_DIMENSION}.
 *   <li>{@code k} (optional): Number of nearest neighbors to return; can be overridden per query.
 *   <li>{@code num_candidates} (optional): Candidate set size for ANN search; can be overridden per
 *       query.
 *   <li>{@code filter_query} (optional): A raw JSON Elasticsearch filter query (DSL) that is
 *       applied as a post-filter; can be overridden per query.
 *   <li>{@code host} or {@code hosts} (optional): Elasticsearch endpoint(s). If omitted, defaults
 *       to {@code localhost:9200}.
 *   <li>Authentication (optional): Either basic auth via {@code username}/{@code password}, or API
 *       key via {@code api_key_base64} or {@code api_key_id}/{@code api_key_secret}.
 * </ul>
 *
 * <p>Example usage (aligned with ElasticsearchRagExample):
 *
 * <pre>{@code
 * ResourceDescriptor desc = ResourceDescriptor.Builder
 *     .newBuilder(ElasticsearchVectorStore.class.getName())
 *     .addInitialArgument("embedding_model", "textEmbedder") // name of embedding resource
 *     .addInitialArgument("index", "my_documents")
 *     .addInitialArgument("vector_field", "content_vector")
 *     .addInitialArgument("dims", 768)
 *     .addInitialArgument("host", "http://localhost:9200")
 *     // Optional auth (API key or basic):
 *     // .addInitialArgument("api_key_base64", "<BASE64_ID_COLON_SECRET>")
 *     // .addInitialArgument("username", "elastic")
 *     // .addInitialArgument("password", "secret")
 *     .build();
 * }</pre>
 */
public class ElasticsearchVectorStore extends BaseVectorStore {

    /** Default vector dimensionality used when {@code dims} is not provided. */
    public static final int DEFAULT_DIMENSION = 768;

    /** Low-level Elasticsearch client used to execute search requests. */
    private final ElasticsearchClient client;

    /** Target index name. */
    private final String index;
    /** Name of the dense vector field on which KNN queries are executed. */
    private final String vectorField;
    /** Vector dimensionality of the {@link #vectorField}. */
    private final int dims;
    /** Default value for KNN result size (can be overridden per query). */
    private final Integer k;
    /** Default number of ANN candidates for KNN (can be overridden per query). */
    private final Integer numCandidates;
    /** Optional default filter query in Elasticsearch JSON DSL (can be overridden per query). */
    private final String filterQuery;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Creates a new {@code ElasticsearchVectorStore} from the provided descriptor and resource
     * resolver.
     *
     * <p>The constructor reads connection, authentication, and query defaults from the descriptor
     * and prepares an {@link ElasticsearchClient} instance. It also validates required arguments.
     *
     * @param descriptor Resource descriptor containing configuration arguments
     * @param getResource Function to resolve other resources by name and type
     * @throws IllegalArgumentException if required arguments are missing or invalid
     */
    public ElasticsearchVectorStore(
            ResourceDescriptor descriptor, BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);

        // Required query-related arguments
        this.index = descriptor.getArgument("index");
        this.vectorField = descriptor.getArgument("vector_field");
        final Integer dimsArg = descriptor.getArgument("dims");
        this.dims = (dimsArg != null) ? dimsArg : DEFAULT_DIMENSION;
        this.filterQuery = descriptor.getArgument("filter_query");

        this.k = descriptor.getArgument("k");
        this.numCandidates = descriptor.getArgument("num_candidates");

        if (this.k != null && this.numCandidates != null) {
            if (this.k < this.numCandidates) {
                throw new IllegalArgumentException(
                        "'k' should be greater or equals than 'num_candidates'");
            }
        }

        if (this.vectorField == null || this.vectorField.isEmpty()) {
            throw new IllegalArgumentException("'vector_field' should not be null or empty");
        }

        if (this.index == null || this.index.isEmpty()) {
            throw new IllegalArgumentException("'index' should not be null or empty");
        }

        // Resolve Elasticsearch HTTP hosts. Precedence: host -> hosts -> default localhost
        final String hostUrl = descriptor.getArgument("host");
        final String hostsCsv = descriptor.getArgument("hosts");
        final List<HttpHost> httpHosts = new ArrayList<>();

        if (hostUrl != null) {
            httpHosts.add(HttpHost.create(hostUrl));
        } else if (hostsCsv != null) {
            for (String host : hostsCsv.split(",")) {
                httpHosts.add(HttpHost.create(host.trim()));
            }
        } else {
            httpHosts.add(HttpHost.create("localhost:9200"));
        }

        final RestClientBuilder builder = RestClient.builder(httpHosts.toArray(new HttpHost[0]));

        // Authentication configuration: API key (preferred) or basic auth
        final String username = descriptor.getArgument("username");
        final String password = descriptor.getArgument("password");
        final String apiKeyBase64 = descriptor.getArgument("api_key_base64");
        final String apiKeyId = descriptor.getArgument("api_key_id");
        final String apiKeySecret = descriptor.getArgument("api_key_secret");

        if (apiKeyBase64 != null || (apiKeyId != null && apiKeySecret != null)) {
            // Construct base64 token if only id/secret is provided
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
            // Fall back to HTTP basic authentication
            final BasicCredentialsProvider creds = new BasicCredentialsProvider();
            creds.setCredentials(
                    AuthScope.ANY, new UsernamePasswordCredentials(username, password));
            builder.setHttpClientConfigCallback(hcb -> hcb.setDefaultCredentialsProvider(creds));
        }

        // Build the REST client and the transport layer used by the high-level client
        final RestClient restClient = builder.build();
        final ElasticsearchTransport transport =
                new RestClientTransport(restClient, new JacksonJsonpMapper());
        this.client = new ElasticsearchClient(transport);
    }

    /**
     * Returns default store-level arguments collected from the descriptor.
     *
     * <p>The returned map can be merged with per-query arguments to form the complete set of
     * parameters for a vector search operation.
     *
     * @return map of default store arguments such as {@code index}, {@code vector_field}, {@code
     *     dims}, and optionally {@code k}, {@code num_candidates}, {@code filter_query}.
     */
    @Override
    public Map<String, Object> getStoreKwargs() {
        final Map<String, Object> m = new HashMap<>();
        m.put("index", this.index);
        m.put("vector_field", this.vectorField);
        m.put("dims", this.dims);
        if (this.k != null) {
            // Expose as top_k (preferred name); keep legacy key for compatibility if referenced
            m.put("top_k", this.k);
        }
        if (this.numCandidates != null) {
            m.put("num_candidates", this.numCandidates);
        }
        if (this.filterQuery != null) {
            m.put("filter_query", this.filterQuery);
        }
        // Accept but not actively used at query time; included for consistency
        // with agreed parameter naming.
        // Users may pass: text_field, similarity, source_includes
        return m;
    }

    /**
     * Executes a KNN vector search using a pre-computed embedding.
     *
     * <p>The method prepares a KNN search request using the supplied {@code embedding} and merges
     * default arguments from the store with the provided {@code args}. Optional filter queries
     * (JSON DSL) are applied as a post filter.
     *
     * @param embedding The embedding vector to search with
     * @param limit Maximum number of items the caller is interested in; used as a fallback for
     *     {@code k} if not explicitly provided
     * @param args Additional arguments. Supported keys: {@code k}, {@code num_candidates}, {@code
     *     filter_query}
     * @return A list of matching documents, possibly empty
     * @throws RuntimeException if the search request fails
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public List<Document> queryEmbedding(float[] embedding, int limit, Map<String, Object> args) {
        try {
            int k = (int) args.getOrDefault("k", Math.max(1, limit));

            int numCandidates = (int) args.getOrDefault("num_candidates", Math.max(100, k * 2));
            String filter = (String) args.get("filter_query");

            List<Float> queryVector = new ArrayList<>(embedding.length);
            for (float v : embedding) queryVector.add(v);

            SearchRequest.Builder builder =
                    new SearchRequest.Builder()
                            .index(this.index)
                            .knn(
                                    kb ->
                                            kb.field(this.vectorField)
                                                    .queryVector(queryVector)
                                                    .k(k)
                                                    .numCandidates(numCandidates));

            if (filter != null) {
                builder = builder.postFilter(f -> f.withJson(new StringReader(filter)));
            }
            final SearchResponse<Map<String, Object>> searchResponse =
                    (SearchResponse) this.client.search(builder.build(), Map.class);

            final long total = searchResponse.hits().total().value();
            if (0 == total) {
                return Collections.emptyList();
            }

            return getDocuments((int) total, searchResponse);
        } catch (IOException e) {
            throw new RuntimeException("Error performing KNN search", e);
        }
    }

    /**
     * Converts Elasticsearch hits into {@link Document} instances with metadata.
     *
     * @param total total number of hits reported by Elasticsearch
     * @param searchResponse the search response containing hits
     * @return list of {@code Document} objects constructed from hits
     */
    private List<Document> getDocuments(
            int total, SearchResponse<Map<String, Object>> searchResponse)
            throws JsonProcessingException {
        final List<Document> documents = new ArrayList<>(total);
        for (Hit<Map<String, Object>> hit : searchResponse.hits().hits()) {
            final Map<String, Object> _source = hit.source();
            final String id = hit.id();
            final Double score = hit.score();
            final String index = hit.index();
            final Map<String, Object> metadata = Map.of("id", id, "score", score, "index", index);
            final String content = (_source == null) ? "" : mapper.writeValueAsString(_source);
            final Document document = new Document(content, metadata, id);
            documents.add(document);
        }
        return documents;
    }
}
