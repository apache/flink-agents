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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.GetLoadStateReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.vectorstores.BaseVectorStore;
import org.apache.flink.agents.api.vectorstores.CollectionManageableVectorStore;
import org.apache.flink.agents.api.vectorstores.Document;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Milvus-backed implementation of a vector store.
 *
 * <p>This implementation executes dense-vector similarity search against a Milvus collection. It
 * integrates with an embedding model (configured via the {@code embedding_model} resource argument
 * inherited from {@link BaseVectorStore}) to convert query text into embeddings and then performs
 * vector search using Milvus' search API.
 *
 * <p>The store creates collections with a simple dense-vector schema:
 *
 * <ul>
 *   <li>{@code id}: VarChar primary key
 *   <li>{@code content}: VarChar document content
 *   <li>{@code metadata}: JSON metadata map
 *   <li>{@code embedding}: FloatVector
 * </ul>
 *
 * <p>Configuration is provided through {@link ResourceDescriptor} arguments. The most relevant ones
 * are:
 *
 * <ul>
 *   <li>{@code collection} or {@code index} (optional): Target collection name. If omitted,
 *       defaults to {@link #DEFAULT_COLLECTION}.
 *   <li>{@code dims} (optional): Vector dimensionality; defaults to {@link #DEFAULT_DIMENSION}.
 *   <li>{@code vector_field}, {@code content_field}, {@code metadata_field}, {@code id_field}
 *       (optional): Schema field names.
 *   <li>{@code metric_type} (optional): Milvus metric type; defaults to {@code COSINE}.
 *   <li>{@code index_type} and {@code index_params} (optional): Milvus vector index settings.
 *   <li>{@code metadata_index_keys} (optional): Additional top-level metadata keys to index as JSON
 *       path indexes. The default keys are {@code user_id}, {@code agent_id}, {@code run_id},
 *       {@code actor_id}, and {@code category}.
 *   <li>{@code metadata_index_cast_types} (optional): Map from metadata key to Milvus JSON index
 *       cast type. Defaults to {@code VARCHAR}; use values such as {@code DOUBLE} for numeric
 *       metadata keys.
 *   <li>{@code num_shards} (optional): Number of Milvus shards to create with the collection;
 *       defaults to {@link #DEFAULT_NUM_SHARDS}. As a rough capacity-planning rule, use about one
 *       shard per 100 million vectors, and increase it for heavier write throughput.
 *   <li>{@code consistency_level} (optional): Milvus consistency level for query and search;
 *       defaults to {@code BOUNDED}. Use {@code STRONG} when immediate read-after-write visibility
 *       is required.
 *   <li>{@code load_timeout_ms} (optional): Timeout used when loading a collection from {@link
 *       #createCollectionIfNotExists(String, Map)}; defaults to {@link #DEFAULT_LOAD_TIMEOUT_MS}.
 *   <li>{@code uri}, or {@code host}/{@code port} (optional): Milvus endpoint. If omitted, defaults
 *       to {@code http://localhost:19530}.
 *   <li>Authentication (optional): Either token auth via {@code token}, or basic auth via {@code
 *       username}/{@code password}.
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * ResourceDescriptor desc = ResourceDescriptor.Builder
 *     .newBuilder(MilvusVectorStore.class.getName())
 *     .addInitialArgument("embedding_model", "textEmbedder")
 *     .addInitialArgument("uri", "http://localhost:19530")
 *     .addInitialArgument("collection", "my_documents")
 *     .addInitialArgument("dims", 768)
 *     .addInitialArgument("metric_type", "COSINE")
 *     .addInitialArgument("index_type", "AUTOINDEX")
 *     .build();
 * }</pre>
 */
public class MilvusVectorStore extends BaseVectorStore implements CollectionManageableVectorStore {

    /**
     * Default collection name used when {@code collection}, {@code collection_name}, and {@code
     * index} are omitted.
     */
    public static final String DEFAULT_COLLECTION = "flink_agents_milvus_collection";
    /** Default primary key field name. */
    public static final String DEFAULT_ID_FIELD = "id";
    /** Default field name used to store document content. */
    public static final String DEFAULT_CONTENT_FIELD = "content";
    /** Default JSON field name used to store document metadata. */
    public static final String DEFAULT_METADATA_FIELD = "metadata";
    /** Default FloatVector field name on which Milvus search is executed. */
    public static final String DEFAULT_VECTOR_FIELD = "embedding";
    /** Default index name for the full metadata JSON index. */
    public static final String DEFAULT_METADATA_INDEX_NAME = "metadata_json_index";
    /** Metadata keys that are commonly used by Mem0 and vector-store filter callers. */
    public static final List<String> DEFAULT_METADATA_INDEX_KEYS =
            List.of("user_id", "agent_id", "run_id", "actor_id", "category");
    /** Default Milvus JSON cast type used for metadata path indexes. */
    public static final String DEFAULT_METADATA_INDEX_CAST_TYPE = "VARCHAR";
    /** Default vector dimensionality used when {@code dims} is not provided. */
    public static final int DEFAULT_DIMENSION = 768;
    /** The maximum number of documents that can be retrieved by get when limit is omitted. */
    public static final int DEFAULT_MAX_GET_LIMIT = 10000;
    /** Default maximum length for the VarChar primary key field. */
    public static final int DEFAULT_ID_MAX_LENGTH = 65535;
    /** Default maximum length for the VarChar content field. */
    public static final int DEFAULT_CONTENT_MAX_LENGTH = 65535;
    /** Default number of Milvus shards used when creating a collection. */
    public static final int DEFAULT_NUM_SHARDS = 1;
    /** Default timeout for synchronous collection load operations. */
    public static final long DEFAULT_LOAD_TIMEOUT_MS = 120000L;

    /** Milvus connection configuration built from the resource descriptor. */
    private final ConnectConfig connectConfig;
    /** Lazily-created Milvus client used to execute collection and vector requests. */
    private transient @Nullable MilvusClientV2 client;

    private final Gson gson = new Gson();

    /** Resolved Milvus endpoint URI. */
    private final String uri;
    /** Optional Milvus database name. */
    private final @Nullable String databaseName;
    /** Default collection name used when a per-call collection is not supplied. */
    private final String defaultCollection;
    /** Name of the primary key field. */
    private final String idField;
    /** Name of the content field to store the document content. */
    private final String contentField;
    /** Name of the JSON field to store document metadata. */
    private final String metadataField;
    /** Name of the FloatVector field on which vector search is executed. */
    private final String vectorField;
    /** Vector dimensionality of the {@link #vectorField}. */
    private final int dims;
    /** Default query limit used by get when no limit is provided. */
    private final int maxGetLimit;
    /** Maximum length for the VarChar primary key field. */
    private final int idMaxLength;
    /** Maximum length for the VarChar content field. */
    private final int contentMaxLength;
    /** Default Milvus metric type used for collection indexes and search. */
    private final IndexParam.MetricType metricType;
    /** Default Milvus index type used when creating collections. */
    private final IndexParam.IndexType indexType;
    /** Extra index parameters passed to Milvus collection creation. */
    private final Map<String, Object> indexParams;
    /** Metadata JSON keys indexed with path-specific indexes during collection creation. */
    private final List<String> metadataIndexKeys;
    /** Per-metadata-key JSON cast type overrides for path-specific indexes. */
    private final Map<String, String> metadataIndexCastTypes;
    /** Number of shards used when creating collections. */
    private final int numShards;
    /** Consistency level used for collection creation, query, and search requests. */
    private final ConsistencyLevel consistencyLevel;
    /** Timeout used when loading collections from create-collection paths. */
    private final long loadTimeoutMs;
    /**
     * Creates a new {@code MilvusVectorStore} from the provided descriptor and resource resolver.
     *
     * <p>The constructor reads connection, authentication, schema, index, and query defaults from
     * the descriptor and prepares a {@link ConnectConfig}. The Milvus client itself is created
     * lazily on first use.
     *
     * @param descriptor Resource descriptor containing configuration arguments
     * @param resourceContext Context used to resolve other resources by name and type
     */
    public MilvusVectorStore(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);

        this.uri = resolveUri(descriptor);
        this.databaseName = stringArg(descriptor, "db_name", null);
        this.defaultCollection =
                stringArg(
                        descriptor,
                        "collection",
                        stringArg(
                                descriptor,
                                "collection_name",
                                stringArg(descriptor, "index", DEFAULT_COLLECTION)));
        this.idField = stringArg(descriptor, "id_field", DEFAULT_ID_FIELD);
        this.contentField = stringArg(descriptor, "content_field", DEFAULT_CONTENT_FIELD);
        this.metadataField = stringArg(descriptor, "metadata_field", DEFAULT_METADATA_FIELD);
        this.vectorField = stringArg(descriptor, "vector_field", DEFAULT_VECTOR_FIELD);
        this.dims = intArg(descriptor, "dims", DEFAULT_DIMENSION);
        this.maxGetLimit = intArg(descriptor, "max_get_limit", DEFAULT_MAX_GET_LIMIT);
        this.idMaxLength = intArg(descriptor, "id_max_length", DEFAULT_ID_MAX_LENGTH);
        this.contentMaxLength =
                intArg(descriptor, "content_max_length", DEFAULT_CONTENT_MAX_LENGTH);
        this.metricType =
                enumArg(
                        IndexParam.MetricType.class,
                        stringArg(descriptor, "metric_type", IndexParam.MetricType.COSINE.name()));
        this.indexType =
                enumArg(
                        IndexParam.IndexType.class,
                        stringArg(descriptor, "index_type", IndexParam.IndexType.AUTOINDEX.name()));
        this.indexParams = mapArg(descriptor, "index_params");
        this.metadataIndexCastTypes = metadataIndexCastTypesArg(descriptor);
        this.metadataIndexKeys = metadataIndexKeysArg(descriptor, this.metadataIndexCastTypes);
        this.numShards = intArg(descriptor, "num_shards", DEFAULT_NUM_SHARDS);
        this.consistencyLevel =
                enumArg(
                        ConsistencyLevel.class,
                        stringArg(
                                descriptor, "consistency_level", ConsistencyLevel.BOUNDED.name()));
        this.loadTimeoutMs = longArg(descriptor, "load_timeout_ms", DEFAULT_LOAD_TIMEOUT_MS);

        ConnectConfig.ConnectConfigBuilder builder =
                ConnectConfig.builder()
                        .uri(this.uri)
                        .secure(this.uri.startsWith("https://"))
                        .enablePrecheck(booleanArg(descriptor, "enable_precheck", false));

        String token = stringArg(descriptor, "token", null);
        if (token != null && !token.isEmpty()) {
            builder.token(token);
        }
        String username = stringArg(descriptor, "username", null);
        String password = stringArg(descriptor, "password", null);
        if (username != null && password != null) {
            builder.username(username).password(password);
        }
        if (this.databaseName != null) {
            builder.dbName(this.databaseName);
        }

        this.connectConfig = builder.build();
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.close();
            this.client = null;
        }
    }

    /**
     * Returns default store-level arguments collected from the descriptor.
     *
     * <p>The returned map can be merged with per-query arguments to form the complete set of
     * parameters for Milvus collection creation, retrieval, and vector search operations.
     *
     * @return map of default store arguments such as {@code uri}, {@code collection}, {@code
     *     vector_field}, {@code dims}, {@code metric_type}, {@code index_type}, and {@code
     *     num_shards}.
     */
    @Override
    public Map<String, Object> getStoreKwargs() {
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("uri", this.uri);
        kwargs.put("collection", this.defaultCollection);
        kwargs.put("index", this.defaultCollection);
        kwargs.put("id_field", this.idField);
        kwargs.put("content_field", this.contentField);
        kwargs.put("metadata_field", this.metadataField);
        kwargs.put("vector_field", this.vectorField);
        kwargs.put("dims", this.dims);
        kwargs.put("metric_type", this.metricType.name());
        kwargs.put("index_type", this.indexType.name());
        kwargs.put("index_params", new HashMap<>(this.indexParams));
        kwargs.put("metadata_index_keys", new ArrayList<>(this.metadataIndexKeys));
        kwargs.put("metadata_index_cast_types", new HashMap<>(this.metadataIndexCastTypes));
        kwargs.put("num_shards", this.numShards);
        kwargs.put("consistency_level", this.consistencyLevel.name());
        kwargs.put("load_timeout_ms", this.loadTimeoutMs);
        if (this.databaseName != null) {
            kwargs.put("db_name", this.databaseName);
        }
        return kwargs;
    }

    /** Returns the lazily-created Milvus client. */
    private MilvusClientV2 client() {
        if (this.client == null) {
            this.client = new MilvusClientV2(this.connectConfig);
        }
        return this.client;
    }

    /**
     * Creates the Milvus collection for the given name if it does not already exist.
     *
     * <p>The created schema contains a VarChar primary key, a VarChar content field, a nullable
     * JSON metadata field, and one FloatVector field. Vector index settings can be supplied through
     * {@code kwargs}; otherwise descriptor defaults are used. The collection is loaded before this
     * method returns, including the case where it already existed.
     */
    @Override
    public void createCollectionIfNotExists(String name, Map<String, Object> kwargs) {
        if (hasCollection(name)) {
            ensureCollectionLoaded(name, kwargs);
            return;
        }

        int dimension = intFromMap(kwargs, "dims", this.dims);
        IndexParam.MetricType metric =
                enumFromMap(IndexParam.MetricType.class, kwargs, "metric_type", this.metricType);
        IndexParam.IndexType index =
                enumFromMap(IndexParam.IndexType.class, kwargs, "index_type", this.indexType);
        int numShards = intFromMap(kwargs, "num_shards", this.numShards);
        Map<String, Object> params =
                kwargs.containsKey("index_params")
                        ? objectToMap(kwargs.get("index_params"))
                        : this.indexParams;

        CreateCollectionReq.CollectionSchema schema = client().createSchema();
        schema.setEnableDynamicField(false);
        schema.addField(
                AddFieldReq.builder()
                        .fieldName(this.idField)
                        .dataType(DataType.VarChar)
                        .isPrimaryKey(Boolean.TRUE)
                        .autoID(Boolean.FALSE)
                        .maxLength(intFromMap(kwargs, "id_max_length", this.idMaxLength))
                        .build());
        schema.addField(
                AddFieldReq.builder()
                        .fieldName(this.contentField)
                        .dataType(DataType.VarChar)
                        .maxLength(intFromMap(kwargs, "content_max_length", this.contentMaxLength))
                        .build());
        schema.addField(
                AddFieldReq.builder()
                        .fieldName(this.metadataField)
                        .dataType(DataType.JSON)
                        .isNullable(Boolean.TRUE)
                        .build());
        schema.addField(
                AddFieldReq.builder()
                        .fieldName(this.vectorField)
                        .dataType(DataType.FloatVector)
                        .dimension(dimension)
                        .build());

        IndexParam vectorIndex =
                IndexParam.builder()
                        .fieldName(this.vectorField)
                        .indexType(index)
                        .metricType(metric)
                        .extraParams(params)
                        .build();
        List<IndexParam> indexParams = new ArrayList<>();
        indexParams.add(vectorIndex);
        indexParams.add(metadataJsonIndexParam());
        Map<String, String> metadataCastTypes = metadataIndexCastTypesFromArgs(kwargs);
        for (String key : metadataIndexKeysFromArgs(kwargs, metadataCastTypes)) {
            indexParams.add(
                    metadataJsonPathIndexParam(
                            key,
                            metadataCastTypes.getOrDefault(key, DEFAULT_METADATA_INDEX_CAST_TYPE)));
        }

        CreateCollectionReq.CreateCollectionReqBuilder builder =
                CreateCollectionReq.builder()
                        .collectionName(name)
                        .collectionSchema(schema)
                        .consistencyLevel(this.consistencyLevel)
                        .numShards(numShards)
                        .indexParams(indexParams);
        if (this.databaseName != null) {
            builder.databaseName(this.databaseName);
        }
        client().createCollection(builder.build());
        ensureCollectionLoaded(name, kwargs);
    }

    /** Deletes the Milvus collection with the given name. */
    @Override
    public void deleteCollection(String name) {
        DropCollectionReq.DropCollectionReqBuilder builder =
                DropCollectionReq.builder().collectionName(name).async(Boolean.FALSE);
        if (this.databaseName != null) {
            builder.databaseName(this.databaseName);
        }
        client().dropCollection(builder.build());
    }

    /**
     * Retrieve documents from the vector store.
     *
     * <p>If {@code ids} are provided, this method queries by primary key and ignores {@code
     * filters} and {@code limit} per the {@link BaseVectorStore} contract. Otherwise it queries
     * with either the unified equality-only {@code filters} DSL or an all-rows fallback expression.
     *
     * @param ids The ids of the documents.
     * @param collection The name of the collection to retrieve from. If null, retrieve from the
     *     default collection.
     * @param filters Unified equality-only filter DSL matched against metadata JSON fields.
     * @param limit Maximum number of documents to return; falls back to {@link
     *     #DEFAULT_MAX_GET_LIMIT} when null.
     * @param extraArgs Additional Milvus-specific arguments.
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
        String targetCollection = resolveCollection(collection);

        if (ids != null && !ids.isEmpty()) {
            // Get specific documents by IDs; filters and limit are ignored per
            // BaseVectorStore contract.
            return getDocumentsByIds(targetCollection, ids);
        }

        // Get all documents with optional filters and limit.
        return getDocuments(targetCollection, filtersToExpression(filters), limit);
    }

    /**
     * Retrieves documents by their IDs using Milvus query API.
     *
     * @param collection The collection to query
     * @param ids List of document IDs to retrieve
     * @return List of Documents
     */
    private List<Document> getDocumentsByIds(String collection, List<String> ids) {
        QueryReq.QueryReqBuilder builder =
                QueryReq.builder()
                        .collectionName(collection)
                        .ids(toObjectIds(ids))
                        .outputFields(outputFields())
                        .consistencyLevel(this.consistencyLevel);
        if (this.databaseName != null) {
            builder.databaseName(this.databaseName);
        }
        QueryResp resp = client().query(builder.build());
        return queryResultsToDocuments(resp.getQueryResults());
    }

    /**
     * Retrieves documents using Milvus query API with optional filters and limit.
     *
     * @param collection The collection to query
     * @param filter Optional Milvus boolean expression
     * @param limit Maximum number of documents to return
     * @return List of Documents
     */
    private List<Document> getDocuments(
            String collection, @Nullable String filter, @Nullable Integer limit) {
        QueryReq.QueryReqBuilder builder =
                QueryReq.builder()
                        .collectionName(collection)
                        .outputFields(outputFields())
                        .filter(filter == null ? allRowsFilter() : filter)
                        .consistencyLevel(this.consistencyLevel)
                        .limit(limit == null ? this.maxGetLimit : limit);
        if (this.databaseName != null) {
            builder.databaseName(this.databaseName);
        }
        QueryResp resp = client().query(builder.build());
        return queryResultsToDocuments(resp.getQueryResults());
    }

    /**
     * Delete documents in the vector store.
     *
     * <p>If ids are provided, this method deletes the corresponding primary keys. Otherwise it
     * deletes documents matched by the unified equality-only {@code filters} DSL. If no filter is
     * provided, it deletes all documents in the target collection.
     *
     * @param ids The ids of the documents.
     * @param collection The name of the collection the documents belong to. If null, use the
     *     default collection.
     * @param filters Unified equality-only filter DSL matched against metadata JSON fields.
     * @param extraArgs Additional Milvus-specific arguments.
     */
    @Override
    public void delete(
            @Nullable List<String> ids,
            @Nullable String collection,
            @Nullable Map<String, Object> filters,
            Map<String, Object> extraArgs)
            throws IOException {
        String targetCollection = resolveCollection(collection);
        if (ids != null && !ids.isEmpty()) {
            // Delete specific documents by IDs.
            deleteDocumentsByIds(targetCollection, ids);
        } else {
            // Delete all documents with optional filters.
            deleteDocuments(targetCollection, filters);
        }
    }

    /**
     * Deletes documents by their IDs using Milvus delete API.
     *
     * @param collection The collection to delete from
     * @param ids List of document IDs to delete
     */
    private void deleteDocumentsByIds(String collection, List<String> ids) {
        DeleteReq.DeleteReqBuilder builder =
                DeleteReq.builder().collectionName(collection).ids(toObjectIds(ids));
        if (this.databaseName != null) {
            builder.databaseName(this.databaseName);
        }
        client().delete(builder.build());
    }

    /**
     * Deletes documents using Milvus delete API with optional filters.
     *
     * @param collection The collection to delete from
     * @param filters Unified equality-only filter DSL matched against metadata JSON fields
     */
    private void deleteDocuments(String collection, @Nullable Map<String, Object> filters) {
        String filter = filtersToExpression(filters);
        DeleteReq.DeleteReqBuilder builder =
                DeleteReq.builder()
                        .collectionName(collection)
                        .filter(filter == null ? allRowsFilter() : filter);
        if (this.databaseName != null) {
            builder.databaseName(this.databaseName);
        }
        client().delete(builder.build());
    }

    /**
     * Executes a Milvus vector search using a pre-computed embedding.
     *
     * <p>The method searches the configured vector field and returns only document id, content, and
     * metadata as output fields. The returned {@link Document#getScore()} value is populated from
     * the Milvus search result score, not from an output field.
     *
     * @param embedding The embedding vector to search with
     * @param limit Maximum number of nearest neighbors to return
     * @param collection The collection to search. If null, search the default collection.
     * @param filters Unified equality-only filter DSL matched against metadata JSON fields.
     * @param args Additional arguments. Supported keys include {@code metric_type} and {@code
     *     search_params}.
     * @return A list of matching documents, possibly empty
     */
    @Override
    public List<Document> queryEmbedding(
            float[] embedding,
            int limit,
            @Nullable String collection,
            @Nullable Map<String, Object> filters,
            Map<String, Object> args) {
        String targetCollection = resolveCollection(collection);
        SearchReq.SearchReqBuilder builder =
                SearchReq.builder()
                        .collectionName(targetCollection)
                        .annsField(this.vectorField)
                        .metricType(
                                enumFromMap(
                                        IndexParam.MetricType.class,
                                        args,
                                        "metric_type",
                                        this.metricType))
                        .data(Collections.<BaseVector>singletonList(new FloatVec(embedding)))
                        .limit(limit)
                        .outputFields(outputFields())
                        .consistencyLevel(this.consistencyLevel);
        String filter = filtersToExpression(filters);
        if (filter != null) {
            builder.filter(filter);
        }
        Map<String, Object> searchParams = objectToMap(args.get("search_params"));
        if (!searchParams.isEmpty()) {
            builder.searchParams(searchParams);
        }
        if (this.databaseName != null) {
            builder.databaseName(this.databaseName);
        }

        SearchResp resp = client().search(builder.build());
        List<List<SearchResp.SearchResult>> groups = resp.getSearchResults();
        if (groups == null || groups.isEmpty()) {
            return Collections.emptyList();
        }
        return searchResultsToDocuments(groups.get(0));
    }

    /**
     * Add documents with pre-computed embeddings to the vector store.
     *
     * <p>Documents without ids get generated UUIDs. Add always uses Milvus insert; callers should
     * use {@link #updateEmbedding(List, String, Map)} when they need to replace existing documents.
     * Each document must already contain an embedding; the public {@link BaseVectorStore#add(List,
     * String, Map)} path handles auto-embedding before it reaches this method.
     *
     * @return List of document ids written to Milvus
     */
    @Override
    public List<String> addEmbedding(
            List<Document> documents, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }
        String targetCollection = resolveCollection(collection);

        List<JsonObject> rows = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Document doc : documents) {
            String id = doc.getId();
            if (id == null || id.isEmpty()) {
                id = UUID.randomUUID().toString();
            }
            ids.add(id);
            rows.add(toRow(id, doc));
        }
        insertRows(targetCollection, rows);
        return ids;
    }

    /**
     * Update documents with pre-computed embeddings.
     *
     * <p>Milvus upsert rewrites rows by primary key. The public {@link BaseVectorStore#update(List,
     * String, Map)} path already enforces that every document carries an id.
     */
    @Override
    public void updateEmbedding(
            List<Document> documents, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException {
        String targetCollection = resolveCollection(collection);

        List<JsonObject> rows = new ArrayList<>();
        for (Document doc : documents) {
            rows.add(toRow(Objects.requireNonNull(doc.getId()), doc));
        }
        upsertRows(targetCollection, rows);
    }

    /** Writes rows using Milvus insert. */
    private void insertRows(String targetCollection, List<JsonObject> rows) {
        InsertReq.InsertReqBuilder builder =
                InsertReq.builder().collectionName(targetCollection).data(rows);
        if (this.databaseName != null) {
            builder.databaseName(this.databaseName);
        }
        client().insert(builder.build());
    }

    /** Writes rows using Milvus upsert, so repeated ids replace the existing entity. */
    private void upsertRows(String targetCollection, List<JsonObject> rows) {
        UpsertReq.UpsertReqBuilder builder =
                UpsertReq.builder()
                        .collectionName(targetCollection)
                        .data(rows)
                        .partialUpdate(false);
        if (this.databaseName != null) {
            builder.databaseName(this.databaseName);
        }
        client().upsert(builder.build());
    }

    /** Converts a {@link Document} into the row object expected by Milvus insert/upsert APIs. */
    private JsonObject toRow(String id, Document doc) {
        if (doc.getEmbedding() == null) {
            throw new IllegalArgumentException("Document embedding must not be null.");
        }
        JsonObject row = new JsonObject();
        row.addProperty(this.idField, id);
        row.addProperty(this.contentField, doc.getContent());
        row.add(this.metadataField, this.gson.toJsonTree(doc.getMetadata()));
        row.add(this.vectorField, this.gson.toJsonTree(toFloatList(doc.getEmbedding())));
        return row;
    }

    /** Checks whether a collection exists in the configured Milvus database. */
    private boolean hasCollection(String collectionName) {
        HasCollectionReq.HasCollectionReqBuilder builder =
                HasCollectionReq.builder().collectionName(collectionName);
        if (this.databaseName != null) {
            builder.databaseName(this.databaseName);
        }
        return client().hasCollection(builder.build());
    }

    /** Loads the collection when Milvus reports that it is not loaded yet. */
    private void ensureCollectionLoaded(String collectionName, Map<String, Object> extraArgs) {
        GetLoadStateReq.GetLoadStateReqBuilder stateBuilder =
                GetLoadStateReq.builder().collectionName(collectionName);
        if (this.databaseName != null) {
            stateBuilder.databaseName(this.databaseName);
        }
        if (Boolean.TRUE.equals(client().getLoadState(stateBuilder.build()))) {
            return;
        }

        LoadCollectionReq.LoadCollectionReqBuilder loadBuilder =
                LoadCollectionReq.builder()
                        .collectionName(collectionName)
                        .sync(Boolean.TRUE)
                        .timeout(longFromMap(extraArgs, "load_timeout_ms", this.loadTimeoutMs));
        if (this.databaseName != null) {
            loadBuilder.databaseName(this.databaseName);
        }
        client().loadCollection(loadBuilder.build());
    }

    /**
     * Creates an index on the full metadata JSON object.
     *
     * <p>Unlike per-key JSON path indexes, this uses {@code json_path=metadata} and {@code
     * json_cast_type=JSON}. It is the closest Milvus equivalent to Elasticsearch's dynamic metadata
     * object mapping: upper layers still pass unified filters by logical metadata key, and {@link
     * #filtersToExpression(Map)} expands them to {@code metadata["key"]} predicates.
     */
    private IndexParam metadataJsonIndexParam() {
        return IndexParam.builder()
                .fieldName(this.metadataField)
                .indexName(DEFAULT_METADATA_INDEX_NAME)
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .extraParams(Map.of("json_path", this.metadataField, "json_cast_type", "JSON"))
                .build();
    }

    /**
     * Creates an index on a high-value metadata JSON key.
     *
     * <p>Upper layers pass filters as logical keys such as {@code user_id}; this method maps each
     * key to the Milvus path expression {@code metadata["user_id"]}. String-like keys use {@code
     * VARCHAR} by default, while callers can override cast types for numeric or boolean metadata
     * keys with {@code metadata_index_cast_types}.
     */
    private IndexParam metadataJsonPathIndexParam(String key, String castType) {
        return IndexParam.builder()
                .fieldName(this.metadataField)
                .indexName(metadataJsonPathIndexName(key))
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .extraParams(
                        Map.of(
                                "json_path",
                                this.metadataField + "[\"" + key + "\"]",
                                "json_cast_type",
                                castType))
                .build();
    }

    /** Returns the deterministic index name used for a metadata JSON path index. */
    private String metadataJsonPathIndexName(String key) {
        return this.metadataField + "_" + key + "_json_index";
    }

    /**
     * Resolves the effective collection name.
     *
     * <p>Precedence is: explicit method argument, then the descriptor default. The descriptor
     * accepts the {@code index} alias to stay compatible with Elasticsearch-style configuration,
     * but per-call target collection selection should use the dedicated method parameter.
     */
    private String resolveCollection(@Nullable String collectionName) {
        if (collectionName != null) {
            return collectionName;
        }
        return this.defaultCollection;
    }

    /**
     * Output fields requested from Milvus for retrieval/search results.
     *
     * <p>The vector field is intentionally omitted: upper layers only require id, content,
     * metadata, and the search score supplied separately by Milvus search results.
     */
    private List<String> outputFields() {
        return List.of(this.idField, this.contentField, this.metadataField);
    }

    /** Converts Milvus query/get results into Flink Agents documents. */
    private List<Document> queryResultsToDocuments(@Nullable List<QueryResp.QueryResult> results) {
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }
        List<Document> docs = new ArrayList<>();
        for (QueryResp.QueryResult result : results) {
            docs.add(entityToDocument(result.getEntity(), null, null));
        }
        return docs;
    }

    /** Converts Milvus search results into Flink Agents documents and preserves hit scores. */
    private List<Document> searchResultsToDocuments(List<SearchResp.SearchResult> results) {
        List<Document> docs = new ArrayList<>();
        for (SearchResp.SearchResult result : results) {
            docs.add(entityToDocument(result.getEntity(), result.getId(), result.getScore()));
        }
        return docs;
    }

    /**
     * Converts a Milvus entity map into a {@link Document}.
     *
     * <p>Embeddings are not reconstructed from result rows because the vector field is not
     * requested in {@link #outputFields()}. Search scores are carried separately from Milvus search
     * results.
     */
    private Document entityToDocument(
            Map<String, Object> entity, @Nullable Object resultId, @Nullable Float score) {
        Object idValue = resultId == null ? entity.get(this.idField) : resultId;
        String id = idValue == null ? null : String.valueOf(idValue);
        Object contentValue = entity.get(this.contentField);
        String content = contentValue == null ? "" : String.valueOf(contentValue);
        Map<String, Object> metadata = objectToMap(entity.get(this.metadataField));
        return new Document(content, metadata, id, null, score);
    }

    /** Converts known map-like values returned by the Milvus SDK into a Java map. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> objectToMap(@Nullable Object value) {
        if (value == null) {
            return Collections.emptyMap();
        }
        if (value instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) value);
        }
        if (value instanceof JsonObject) {
            return this.gson.fromJson((JsonObject) value, Map.class);
        }
        if (value instanceof JsonElement) {
            return this.gson.fromJson((JsonElement) value, Map.class);
        }
        return Collections.emptyMap();
    }

    /** Resolves metadata JSON path index cast types from descriptor defaults plus per-call args. */
    private Map<String, String> metadataIndexCastTypesFromArgs(Map<String, Object> args) {
        Map<String, String> castTypes = new LinkedHashMap<>(this.metadataIndexCastTypes);
        if (args.containsKey("metadata_index_cast_types")) {
            putMetadataIndexCastTypes(
                    castTypes, objectToMap(args.get("metadata_index_cast_types")));
        }
        return castTypes;
    }

    /** Resolves metadata keys to index from defaults plus descriptor and per-call args. */
    private List<String> metadataIndexKeysFromArgs(
            Map<String, Object> args, Map<String, String> castTypes) {
        LinkedHashMap<String, Boolean> keys = new LinkedHashMap<>();
        putMetadataIndexKeys(keys, this.metadataIndexKeys);
        putMetadataIndexKeys(keys, stringList(args.get("metadata_index_keys")));
        putMetadataIndexKeys(keys, castTypes.keySet());
        return new ArrayList<>(keys.keySet());
    }

    /**
     * Translates the unified equality-only filter DSL into a Milvus boolean expression.
     *
     * <p>Metadata is stored in a JSON field, so equality filters are translated into JSON subscript
     * predicates such as {@code metadata["user_id"] == "alice"}.
     */
    private String filtersToExpression(@Nullable Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        List<String> clauses = new ArrayList<>();
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            Object value = entry.getValue();
            if (value == null || value instanceof Map) {
                throw new UnsupportedOperationException(
                        "MilvusVectorStore filters support equality shorthand only.");
            }
            clauses.add(
                    this.metadataField
                            + "[\""
                            + escapeString(entry.getKey())
                            + "\"] == "
                            + literal(value));
        }
        return String.join(" and ", clauses);
    }

    /** Returns a Milvus expression that matches every row with a non-empty primary key. */
    private String allRowsFilter() {
        return this.idField + " != \"\"";
    }

    /** Formats a Java value as a Milvus expression literal. */
    private String literal(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "\"" + escapeString(String.valueOf(value)) + "\"";
    }

    /** Converts Java primitive float arrays into boxed lists accepted by the Milvus SDK. */
    private static List<Float> toFloatList(float[] embedding) {
        List<Float> vector = new ArrayList<>(embedding.length);
        for (float value : embedding) {
            vector.add(value);
        }
        return vector;
    }

    /** Converts string ids into the object list shape expected by Milvus get/delete requests. */
    private static List<Object> toObjectIds(List<String> ids) {
        return new ArrayList<>(ids);
    }

    /** Resolves the Milvus endpoint from {@code uri}, {@code host}, and {@code port}. */
    private static String resolveUri(ResourceDescriptor descriptor) {
        String uri = stringArg(descriptor, "uri", null);
        if (uri != null && !uri.isEmpty()) {
            return uri;
        }
        String host = stringArg(descriptor, "host", "localhost");
        int port = intArg(descriptor, "port", 19530);
        if (host.startsWith("http://") || host.startsWith("https://")) {
            return host;
        }
        if (host.contains(":")) {
            return "http://" + host;
        }
        return "http://" + host + ":" + port;
    }

    /** Reads a descriptor argument as a map, returning an empty map when absent. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapArg(ResourceDescriptor descriptor, String key) {
        Object value = descriptor.getArgument(key);
        if (value instanceof Map) {
            return new HashMap<>((Map<String, Object>) value);
        }
        return Collections.emptyMap();
    }

    /** Reads metadata JSON path index keys from the descriptor. */
    private static List<String> metadataIndexKeysArg(
            ResourceDescriptor descriptor, Map<String, String> castTypes) {
        LinkedHashMap<String, Boolean> keys = new LinkedHashMap<>();
        putMetadataIndexKeys(keys, DEFAULT_METADATA_INDEX_KEYS);
        putMetadataIndexKeys(keys, stringList(descriptor.getArgument("metadata_index_keys")));
        putMetadataIndexKeys(keys, castTypes.keySet());
        return new ArrayList<>(keys.keySet());
    }

    /** Reads metadata JSON path index cast types from the descriptor. */
    private static Map<String, String> metadataIndexCastTypesArg(ResourceDescriptor descriptor) {
        Map<String, String> castTypes = new LinkedHashMap<>();
        for (String key : DEFAULT_METADATA_INDEX_KEYS) {
            castTypes.put(key, DEFAULT_METADATA_INDEX_CAST_TYPE);
        }
        putMetadataIndexCastTypes(castTypes, mapArg(descriptor, "metadata_index_cast_types"));
        return castTypes;
    }

    /** Adds metadata index keys while preserving insertion order and removing duplicates. */
    private static void putMetadataIndexKeys(
            LinkedHashMap<String, Boolean> target, Iterable<String> keys) {
        for (String key : keys) {
            target.put(normalizeMetadataIndexKey(key), Boolean.TRUE);
        }
    }

    /** Adds or overrides metadata index cast types. */
    private static void putMetadataIndexCastTypes(
            Map<String, String> target, Map<String, Object> castTypes) {
        for (Map.Entry<String, Object> entry : castTypes.entrySet()) {
            target.put(
                    normalizeMetadataIndexKey(entry.getKey()),
                    normalizeMetadataIndexCastType(entry.getValue()));
        }
    }

    /** Parses a descriptor or per-call argument as a string list. */
    private static List<String> stringList(@Nullable Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof Iterable) {
            List<String> result = new ArrayList<>();
            for (Object item : (Iterable<?>) value) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        if (value instanceof Object[]) {
            List<String> result = new ArrayList<>();
            for (Object item : (Object[]) value) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * Validates a top-level JSON key used for a generated Milvus path index.
     *
     * <p>Milvus recommends JSON keys made from letters, digits, and underscores. Keeping the
     * configurable index list to that subset also gives deterministic, valid index names.
     */
    private static String normalizeMetadataIndexKey(String key) {
        String trimmed = key == null ? "" : key.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("metadata_index_keys cannot contain empty keys.");
        }
        if (!isIdentifierStart(trimmed.charAt(0))) {
            throw new IllegalArgumentException(
                    "metadata_index_keys must contain only JSON-safe identifiers: " + key);
        }
        for (int i = 1; i < trimmed.length(); i++) {
            if (!isIdentifierPart(trimmed.charAt(i))) {
                throw new IllegalArgumentException(
                        "metadata_index_keys must contain only JSON-safe identifiers: " + key);
            }
        }
        return trimmed;
    }

    /** Normalizes Milvus JSON index cast types. */
    private static String normalizeMetadataIndexCastType(@Nullable Object value) {
        String type =
                value == null
                        ? DEFAULT_METADATA_INDEX_CAST_TYPE
                        : String.valueOf(value).trim().toUpperCase(Locale.ROOT);
        switch (type) {
            case "BOOL":
            case "DOUBLE":
            case "VARCHAR":
            case "ARRAY_BOOL":
            case "ARRAY_DOUBLE":
            case "ARRAY_VARCHAR":
            case "JSON":
                return type;
            default:
                throw new IllegalArgumentException(
                        "Unsupported Milvus metadata JSON index cast type: " + value);
        }
    }

    private static boolean isIdentifierStart(char value) {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z') || value == '_';
    }

    private static boolean isIdentifierPart(char value) {
        return isIdentifierStart(value) || (value >= '0' && value <= '9');
    }

    /** Reads a descriptor argument as a string, returning the supplied default when absent. */
    private static String stringArg(
            ResourceDescriptor descriptor, String key, @Nullable String defaultValue) {
        Object value = descriptor.getArgument(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    /** Reads a descriptor argument as an integer, accepting both numeric and string values. */
    private static int intArg(ResourceDescriptor descriptor, String key, int defaultValue) {
        Object value = descriptor.getArgument(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            return Integer.parseInt(String.valueOf(value));
        }
        return defaultValue;
    }

    /** Reads a descriptor argument as a long, accepting both numeric and string values. */
    private static long longArg(ResourceDescriptor descriptor, String key, long defaultValue) {
        Object value = descriptor.getArgument(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null) {
            return Long.parseLong(String.valueOf(value));
        }
        return defaultValue;
    }

    /** Reads a per-call argument as an integer, accepting both numeric and string values. */
    private static int intFromMap(Map<String, Object> args, String key, int defaultValue) {
        Object value = args.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            return Integer.parseInt(String.valueOf(value));
        }
        return defaultValue;
    }

    /** Reads a per-call argument as a long, accepting both numeric and string values. */
    private static long longFromMap(Map<String, Object> args, String key, long defaultValue) {
        Object value = args.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null) {
            return Long.parseLong(String.valueOf(value));
        }
        return defaultValue;
    }

    /** Reads a descriptor argument as a boolean. */
    private static boolean booleanArg(
            ResourceDescriptor descriptor, String key, boolean defaultValue) {
        Object value = descriptor.getArgument(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value != null) {
            return Boolean.parseBoolean(String.valueOf(value));
        }
        return defaultValue;
    }

    /** Reads a per-call enum argument, returning the default when absent. */
    private static <E extends Enum<E>> E enumFromMap(
            Class<E> enumClass, Map<String, Object> args, String key, E defaultValue) {
        Object value = args.get(key);
        return value == null ? defaultValue : enumArg(enumClass, String.valueOf(value));
    }

    /** Parses enum names case-insensitively and accepts dash-separated names. */
    private static <E extends Enum<E>> E enumArg(Class<E> enumClass, String value) {
        return Enum.valueOf(enumClass, value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }

    /** Escapes string content embedded in Milvus expression literals. */
    private static String escapeString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
