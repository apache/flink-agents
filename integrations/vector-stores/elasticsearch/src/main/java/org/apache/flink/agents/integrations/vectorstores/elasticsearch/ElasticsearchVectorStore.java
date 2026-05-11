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
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.MgetRequest;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.get.GetResult;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.vectorstores.BaseVectorStore;
import org.apache.flink.agents.api.vectorstores.CollectionManageableVectorStore;
import org.apache.flink.agents.api.vectorstores.Document;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;

import javax.annotation.Nullable;

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
public class ElasticsearchVectorStore extends BaseVectorStore
        implements CollectionManageableVectorStore {

    /** Default vector dimensionality used when {@code dims} is not provided. */
    public static final int DEFAULT_DIMENSION = 768;
    /** The maximum number of documents that can be retrieved in get. */
    public static final int MAX_RESULT_WINDOW = 10000;

    public static final String DEFAULT_METADATA_FIELD = "_metadata";
    public static final String DEFAULT_CONTENT_FIELD = "_content";
    public static final String DEFAULT_VECTOR_FIELD = "_vector";

    /** Low-level Elasticsearch client used to execute search requests. */
    private final ElasticsearchClient client;

    /** Default index name. */
    private final String index;
    /** Name of the content field to store the document content. */
    private final String contentField;
    /** Name of the metadata field to store additional metadatas. */
    private final String metadataField;
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

    /** Whether the content of document is stored in content field. */
    private final boolean storeInContentField;

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

        this.storeInContentField =
                Objects.requireNonNullElse(descriptor.getArgument("store_in_content_field"), true);

        // Required query-related arguments
        this.index = descriptor.getArgument("index");
        this.vectorField =
                Objects.requireNonNullElse(
                        descriptor.getArgument("vector_field"), DEFAULT_VECTOR_FIELD);
        this.contentField =
                Objects.requireNonNullElse(
                        descriptor.getArgument("content_field"), DEFAULT_CONTENT_FIELD);
        this.metadataField =
                Objects.requireNonNullElse(
                        descriptor.getArgument("metadata_field"), DEFAULT_METADATA_FIELD);
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

    @Override
    public void close() throws Exception {
        this.client.close();
    }

    /**
     * Creates the Elasticsearch index for the given collection if it does not already exist.
     *
     * <p>Elasticsearch does not natively support attaching arbitrary metadata to an index, so any
     * {@code metadata} key in {@code kwargs} is ignored. Callers needing to associate metadata with
     * content should store it on the documents themselves.
     */
    @Override
    public void createCollectionIfNotExists(String name, Map<String, Object> kwargs)
            throws Exception {
        ExistsRequest existsRequest = ExistsRequest.of(e -> e.index(name));
        boolean exists = this.client.indices().exists(existsRequest).value();
        if (!exists) {
            createIndex(name);
        }
    }

    /**
     * Creates an Elasticsearch index with vector field mapping.
     *
     * @param indexName The name of the index to create
     * @throws IOException if the index creation fails
     */
    private void createIndex(String indexName) throws IOException {

        // Build mappings with vector field
        Map<String, Property> properties = new HashMap<>();

        // Add vector field mapping
        Property vectorProperty =
                Property.of(p -> p.denseVector(dv -> dv.dims(this.dims).index(true)));
        properties.put(this.vectorField, vectorProperty);

        // Add text field for content
        Property textProperty = Property.of(p -> p.text(t -> t));
        properties.put(this.contentField, textProperty);

        // Add metadata field mapping
        Property metadataProperty = Property.of(p -> p.object(o -> o));
        properties.put(this.metadataField, metadataProperty);

        CreateIndexRequest createRequest =
                CreateIndexRequest.of(
                        c ->
                                c.index(indexName)
                                        .mappings(
                                                m ->
                                                        m.properties(properties)
                                                                .dynamic(DynamicMapping.True)));

        this.client.indices().create(createRequest);
    }

    @Override
    public void deleteCollection(String name) throws Exception {
        DeleteIndexRequest deleteIndexRequest = DeleteIndexRequest.of(d -> d.index(name));
        this.client.indices().delete(deleteIndexRequest);
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
     * Retrieve documents from the vector store.
     *
     * <p>If ids is not provided, this method will retrieve documents according to {@code limit},
     * {@code offset}, and {@code filter_query} in additional arguments. If {@code limit} is null,
     * up to {@link ElasticsearchVectorStore#MAX_RESULT_WINDOW} documents are returned (an
     * Elasticsearch ceiling).
     *
     * <p>The unified {@code filters} DSL parameter is not yet translated to Elasticsearch's native
     * query DSL — callers needing structured filtering should pass a raw {@code filter_query} via
     * {@code extraArgs}. TODO: implement equality-DSL translation parallel to the Python Chroma
     * implementation.
     *
     * @param ids The ids of the documents.
     * @param collection The name of the collection to be retrieved. If is null, retrieve the
     *     default collection.
     * @param filters Unified filter DSL. Currently ignored — see method Javadoc.
     * @param limit Maximum number of documents to return; falls back to {@link
     *     ElasticsearchVectorStore#MAX_RESULT_WINDOW} when null.
     * @param extraArgs Additional arguments. (offset, filter_query, etc.)
     * @return List of documents retrieved.
     */
    @Override
    public List<Document> get(
            @Nullable List<String> ids,
            @Nullable String collection,
            @Nullable Map<String, Object> filters,
            @Nullable Integer limit,
            Map<String, Object> extraArgs)
            throws IOException {
        String index = collection == null ? this.index : collection;

        if (ids != null && !ids.isEmpty()) {
            // Get specific documents by IDs
            return getDocumentsByIds(index, ids);
        } else {
            // Get all documents with optional filters, limit or offset.
            Map<String, Object> mergedArgs = new HashMap<>(extraArgs);
            if (limit != null) {
                mergedArgs.put("limit", limit);
            }
            return getDocuments(index, filters, mergedArgs);
        }
    }

    /**
     * Delete documents in the vector store.
     *
     * <p>If ids is not provided, this method will delete documents matched the {@code filter_query}
     * in additional arguments. If neither {@code filter_query} nor {@code filters} is provided,
     * this method will delete all the documents.
     *
     * <p>The unified {@code filters} DSL parameter is not yet translated to Elasticsearch's native
     * query DSL — callers needing structured filtering should pass a raw {@code filter_query} via
     * {@code extraArgs}. TODO: implement equality-DSL translation parallel to the Python Chroma
     * implementation.
     *
     * @param ids The ids of the documents.
     * @param collection The name of the collection the documents belong to. If is null, use the
     *     default collection.
     * @param filters Unified filter DSL. Currently ignored — see method Javadoc.
     * @param extraArgs Additional arguments. (filter_query, etc.)
     */
    @Override
    public void delete(
            @Nullable List<String> ids,
            @Nullable String collection,
            @Nullable Map<String, Object> filters,
            Map<String, Object> extraArgs)
            throws IOException {
        String index = collection == null ? this.index : collection;

        if (ids != null && !ids.isEmpty()) {
            // Delete specific documents by IDs
            deleteDocumentsByIds(index, ids);
        } else {
            // Delete all documents with optional filters
            deleteDocuments(index, filters, extraArgs);
        }
    }

    /**
     * Retrieves documents by their IDs using Elasticsearch multi-get API.
     *
     * @param index The index to query
     * @param ids List of document IDs to retrieve
     * @return List of Documents
     * @throws IOException if the request fails
     * @throws JsonProcessingException if JSON processing fails
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Document> getDocumentsByIds(String index, List<String> ids)
            throws IOException, JsonProcessingException {
        MgetRequest mgetRequest = MgetRequest.of(m -> m.index(index).ids(ids));

        MgetResponse<Map<String, Object>> mgetResponse =
                (MgetResponse) this.client.mget(mgetRequest, Map.class);

        List<Document> documents = new ArrayList<>();
        for (MultiGetResponseItem<Map<String, Object>> item : mgetResponse.docs()) {
            if (item.isResult()) {
                GetResult<Map<String, Object>> getResult = item.result();
                Map<String, Object> source = getResult.source();
                String id = getResult.id();
                // mget has no relevance score; leave Document.score null.
                Document document = getDocument(id, source, null);
                documents.add(document);
            }
        }
        return documents;
    }

    /**
     * Retrieves documents using Elasticsearch search API with optional filters.
     *
     * @param index The index to query
     * @param extraArgs Additional arguments (limit, offset, filter_query, etc.)
     * @return List of Documents
     * @throws IOException if the request fails
     * @throws JsonProcessingException if JSON processing fails
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Document> getDocuments(
            String index, @Nullable Map<String, Object> filters, Map<String, Object> extraArgs)
            throws IOException, JsonProcessingException {
        SearchRequest.Builder builder = new SearchRequest.Builder().index(index);

        // Handle limit (size)
        Integer limit = (Integer) extraArgs.get("limit");
        builder.size(Objects.requireNonNullElse(limit, MAX_RESULT_WINDOW));

        // Handle offset (from)
        Integer offset = (Integer) extraArgs.get("offset");
        if (offset != null) {
            builder.from(offset);
        }

        // Combine the unified filters DSL and the raw filter_query into a single ES query.
        String combined =
                combineQueryJson(
                        (String) extraArgs.get("filter_query"), filtersToBoolMustJson(filters));
        if (combined != null) {
            builder.query(q -> q.withJson(new StringReader(combined)));
        }

        // Execute search
        SearchResponse<Map<String, Object>> searchResponse =
                (SearchResponse) this.client.search(builder.build(), Map.class);

        final long total = searchResponse.hits().total().value();
        if (total == 0) {
            return Collections.emptyList();
        }

        return getDocuments((int) total, searchResponse);
    }

    /**
     * Deletes documents by their IDs using Elasticsearch bulk delete API.
     *
     * @param index The index to delete from
     * @param ids List of document IDs to delete
     * @throws IOException if the request fails
     */
    private void deleteDocumentsByIds(String index, List<String> ids) throws IOException {
        // Prepare bulk delete operations
        List<BulkOperation> bulkOperations = new ArrayList<>();
        for (String id : ids) {
            bulkOperations.add(BulkOperation.of(bo -> bo.delete(d -> d.index(index).id(id))));
        }

        // Execute bulk delete request
        BulkRequest bulkRequest = BulkRequest.of(br -> br.operations(bulkOperations));
        BulkResponse bulkResponse = this.client.bulk(bulkRequest);

        // Check for errors
        if (bulkResponse.errors()) {
            StringBuilder errorMsg = new StringBuilder("Some documents failed to delete: ");
            bulkResponse.items().stream()
                    .filter(item -> item.error() != null)
                    .forEach(
                            item ->
                                    errorMsg.append(
                                            String.format(
                                                    "id=%s, error=%s; ",
                                                    item.id(), item.error().reason())));
            throw new RuntimeException(errorMsg.toString());
        }
    }

    /**
     * Deletes documents using Elasticsearch delete by query API.
     *
     * @param index The index to delete from
     * @param extraArgs Additional arguments (filter_query, etc.)
     * @throws IOException if the request fails
     */
    private void deleteDocuments(
            String index, @Nullable Map<String, Object> filters, Map<String, Object> extraArgs)
            throws IOException {
        DeleteByQueryRequest.Builder builder = new DeleteByQueryRequest.Builder().index(index);

        String combined;
        try {
            combined =
                    combineQueryJson(
                            (String) extraArgs.get("filter_query"), filtersToBoolMustJson(filters));
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to build delete-by-query filter", e);
        }
        if (combined != null) {
            builder.query(q -> q.withJson(new StringReader(combined)));
        } else {
            // No filter at all → delete every document (match_all).
            builder.query(q -> q.matchAll(ma -> ma));
        }

        // Execute delete by query
        DeleteByQueryResponse response = this.client.deleteByQuery(builder.build());

        // Check for failures
        if (response.failures() != null && !response.failures().isEmpty()) {
            StringBuilder errorMsg = new StringBuilder("Some documents failed to delete: ");
            response.failures()
                    .forEach(
                            failure ->
                                    errorMsg.append(
                                            String.format(
                                                    "id=%s, error=%s; ",
                                                    failure.id(), failure.cause().reason())));
            throw new RuntimeException(errorMsg.toString());
        }
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
     * @param collection The index to query search. If is null, search the default index.
     * @param args Additional arguments. Supported keys: {@code k}, {@code num_candidates}, {@code
     *     filter_query}
     * @return A list of matching documents, possibly empty
     * @throws RuntimeException if the search request fails
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public List<Document> queryEmbedding(
            float[] embedding,
            int limit,
            @Nullable String collection,
            @Nullable Map<String, Object> filters,
            Map<String, Object> args) {
        try {
            String index = collection == null ? this.index : collection;
            int k = (int) args.getOrDefault("k", Math.max(1, limit));

            int numCandidates = (int) args.getOrDefault("num_candidates", Math.max(100, k * 2));
            String combined =
                    combineQueryJson(
                            (String) args.get("filter_query"), filtersToBoolMustJson(filters));

            List<Float> queryVector = new ArrayList<>(embedding.length);
            for (float v : embedding) queryVector.add(v);

            SearchRequest.Builder builder =
                    new SearchRequest.Builder()
                            .index(index)
                            .knn(
                                    kb ->
                                            kb.field(this.vectorField)
                                                    .queryVector(queryVector)
                                                    .k(k)
                                                    .numCandidates(numCandidates));

            if (combined != null) {
                final String finalCombined = combined;
                builder = builder.postFilter(f -> f.withJson(new StringReader(finalCombined)));
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
     * Add documents with pre-computed embedding to vector store.
     *
     * <p>ElasticSearch will set the content of the document to content field.
     */
    @Override
    public List<String> addEmbedding(
            List<Document> documents, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException {
        String index = collection == null ? this.index : collection;
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }

        // Prepare bulk operations
        List<BulkOperation> bulkOperations = new ArrayList<>();
        List<String> documentIds = new ArrayList<>();

        for (Document doc : documents) {
            // Generate ID if not provided
            String id = doc.getId();
            if (id == null || id.isEmpty()) {
                id = UUID.randomUUID().toString();
            }
            final String docId = id;
            documentIds.add(docId);

            Map<String, Object> source = new HashMap<>();

            // Add embedding vector if available
            float[] embedding = doc.getEmbedding();
            if (embedding != null && embedding.length > 0) {
                List<Float> embeddingList = new ArrayList<>(embedding.length);
                for (float v : embedding) {
                    embeddingList.add(v);
                }
                source.put(this.vectorField, embeddingList);
            }

            source.put(this.contentField, doc.getContent());

            // Add metadata
            Map<String, Object> metadata = doc.getMetadata();
            if (metadata != null && !metadata.isEmpty()) {
                source.put(this.metadataField, doc.getMetadata());
            }

            // Create index operation for bulk request
            bulkOperations.add(
                    BulkOperation.of(
                            bo -> bo.index(io -> io.index(index).id(docId).document(source))));
        }

        // Execute bulk request
        BulkRequest bulkRequest =
                BulkRequest.of(br -> br.operations(bulkOperations).refresh(Refresh.WaitFor));
        BulkResponse bulkResponse = this.client.bulk(bulkRequest);

        // Check for errors
        if (bulkResponse.errors()) {
            StringBuilder errorMsg = new StringBuilder("Some documents failed to index: ");
            bulkResponse.items().stream()
                    .filter(item -> item.error() != null)
                    .forEach(
                            item ->
                                    errorMsg.append(
                                            String.format(
                                                    "id=%s, error=%s; ",
                                                    item.id(), item.error().reason())));
            throw new RuntimeException(errorMsg.toString());
        }

        return documentIds;
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
            // hit.score() is a Double — null for plain get-all responses, populated for
            // KNN / scored search; mirror that null-ness on Document.score.
            Double score = hit.score();
            documents.add(
                    getDocument(hit.id(), hit.source(), score == null ? null : score.floatValue()));
        }
        return documents;
    }

    @SuppressWarnings("unchecked")
    private Document getDocument(String id, Map<String, Object> source, @Nullable Float score)
            throws JsonProcessingException {
        Map<String, Object> metadata = new HashMap<>();
        String content = "";
        if (source != null) {
            Map<String, Object> extra = (Map<String, Object>) source.remove(this.metadataField);
            if (extra != null) {
                metadata.putAll(extra);
            }

            // Elasticsearch supports store document as mappings. If storeInContentField is
            // true,
            // we get the content from the specific field, otherwise, we get the content from
            // all
            // the fields.
            if (this.storeInContentField) {
                content = (String) source.get(this.contentField);
            } else {
                source.remove(this.vectorField);
                content = mapper.writeValueAsString(source);
            }
        }
        return new Document(content, metadata, id, null, score);
    }

    @Override
    public void updateEmbedding(
            List<Document> documents, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException {
        // ES's bulk "index" operation is upsert by id, so the same path that addEmbedding uses
        // works as an update too. The public BaseVectorStore.update() already enforces that every
        // document carries an id, so addEmbedding will not generate new ones here.
        addEmbedding(documents, collection, extraArgs);
    }

    /**
     * Translate the unified equality-only filter DSL into an Elasticsearch {@code bool/must} of
     * {@code term} clauses against {@code <metadataField>.<key>.keyword}, since metadata is stored
     * inside the {@link #metadataField} object and ES dynamic mapping exposes string fields as
     * {@code <field>.keyword} for exact matching. Returns {@code null} when there is nothing to
     * filter on, so callers can fall back to "no query" semantics.
     */
    @Nullable
    private String filtersToBoolMustJson(@Nullable Map<String, Object> filters)
            throws JsonProcessingException {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> must = new ArrayList<>();
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            String field = this.metadataField + "." + entry.getKey() + ".keyword";
            must.add(Map.of("term", Map.of(field, entry.getValue())));
        }
        return mapper.writeValueAsString(Map.of("bool", Map.of("must", must)));
    }

    /**
     * AND together a raw {@code filter_query} JSON (passed through {@code extraArgs}) and the
     * translated unified-DSL JSON. When only one is present it is returned as-is; when both are
     * present they are wrapped inside an outer {@code bool/must}.
     */
    @Nullable
    @SuppressWarnings({"rawtypes", "unchecked"})
    private String combineQueryJson(@Nullable String raw, @Nullable String dsl)
            throws JsonProcessingException {
        if (raw == null) {
            return dsl;
        }
        if (dsl == null) {
            return raw;
        }
        Map rawObj = mapper.readValue(raw, Map.class);
        Map dslObj = mapper.readValue(dsl, Map.class);
        return mapper.writeValueAsString(Map.of("bool", Map.of("must", List.of(rawObj, dslObj))));
    }
}
