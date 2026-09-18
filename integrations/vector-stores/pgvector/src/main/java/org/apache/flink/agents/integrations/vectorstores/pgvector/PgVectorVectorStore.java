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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.vectorstores.BaseVectorStore;
import org.apache.flink.agents.api.vectorstores.CollectionManageableVectorStore;
import org.apache.flink.agents.api.vectorstores.Document;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * PostgreSQL <a href="https://github.com/pgvector/pgvector">pgvector</a> backed implementation of a
 * vector store.
 *
 * <p>Each collection is a table with a text primary key, the document content, the metadata as
 * {@code jsonb}, and the embedding as a {@code vector(dims)} column. Similarity search orders rows
 * by the pgvector distance operator of the configured metric; equality filters from the unified
 * filter DSL compile to a single {@code jsonb} containment predicate ({@code metadata @> '{...}'}),
 * which the default GIN index on the metadata column serves.
 *
 * <p>Configuration is provided through {@link ResourceDescriptor} arguments:
 *
 * <ul>
 *   <li>{@code uri} (optional): JDBC URL, default {@code
 *       jdbc:postgresql://localhost:5432/postgres}. Alternatively {@code host}, {@code port} and
 *       {@code database} build the URL.
 *   <li>{@code username}, {@code password} (optional): connection credentials.
 *   <li>{@code connect_timeout_s} (optional): seconds to wait for a TCP connection and login.
 *       {@code socket_timeout_s} (optional): seconds to wait for a reply to any statement before
 *       the connection is dropped. When omitted, the driver defaults (10 seconds to connect, no
 *       socket limit) or the {@code connectTimeout}/{@code loginTimeout}/{@code socketTimeout}
 *       parameters of {@code uri} apply. Set a socket timeout when the job must fail over rather
 *       than wait on a hung server; keep it above the longest index build you expect from {@code
 *       createCollectionIfNotExists}.
 *   <li>{@code collection} (optional): default table name, default {@link #DEFAULT_COLLECTION};
 *       {@code collection_name} and {@code index} are accepted as aliases. {@code schema}
 *       (optional) selects the PostgreSQL schema, default {@code public}.
 *   <li>{@code id_field}, {@code content_field}, {@code metadata_field}, {@code vector_field}
 *       (optional): column names.
 *   <li>{@code dims} (optional): vector dimensionality, default {@link #DEFAULT_DIMENSION}.
 *   <li>{@code metric_type} (optional): {@code COSINE} (default), {@code L2} or {@code IP}.
 *   <li>{@code index_type} (optional): {@code HNSW} (default), {@code IVFFLAT} or {@code NONE}.
 *   <li>{@code index_params} (optional): index storage parameters, for example {@code m} and {@code
 *       ef_construction} for HNSW or {@code lists} for IVFFlat.
 *   <li>{@code create_extension} (optional): run {@code CREATE EXTENSION IF NOT EXISTS vector}
 *       before creating a table, default {@code true}. The statement is a no-op once the extension
 *       is installed; set {@code false} to skip it entirely, for example where DDL is audited or
 *       the role may not issue it.
 *   <li>{@code iterative_scan} (optional): {@code RELAXED_ORDER} (default), {@code STRICT_ORDER} or
 *       {@code OFF}. On pgvector 0.8 or later every similarity search runs with this iterative scan
 *       mode ({@code SET LOCAL} in the search's own transaction block), so that an HNSW or IVFFlat
 *       index keeps searching until {@code limit} rows satisfy the filter instead of returning only
 *       the matches among its first {@code ef_search} candidates. {@code OFF} leaves the server's
 *       settings alone. A per-query {@code iterative_scan} argument overrides it for that query.
 * </ul>
 *
 * <p>Names are quoted as given, so they are matched case-sensitively; a table created without
 * quotes has a lower-case name in PostgreSQL and must be referenced in lower case. HNSW and IVFFlat
 * indexes support at most {@value #MAX_INDEXED_DIMENSIONS} dimensions, so {@code
 * createCollectionIfNotExists} rejects wider vectors unless {@code index_type} is {@code NONE};
 * existing tables of any width can be used. Each store instance holds one connection and serializes
 * its operations.
 *
 * <p>Score semantics: {@link Document#getScore()} holds the cosine similarity for {@code COSINE}
 * and the inner product for {@code IP} (higher is better), and the Euclidean distance for {@code
 * L2} (lower is better). Results are always returned nearest first, also under a relaxed iterative
 * scan: the hits are re-sorted before they are returned.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * ResourceDescriptor desc = ResourceDescriptor.Builder
 *     .newBuilder(PgVectorVectorStore.class.getName())
 *     .addInitialArgument("embedding_model", "textEmbedder")
 *     .addInitialArgument("uri", "jdbc:postgresql://localhost:5432/postgres")
 *     .addInitialArgument("username", "postgres")
 *     .addInitialArgument("password", "postgres")
 *     .addInitialArgument("collection", "my_documents")
 *     .addInitialArgument("dims", 768)
 *     .addInitialArgument("metric_type", "COSINE")
 *     .build();
 * }</pre>
 */
public class PgVectorVectorStore extends BaseVectorStore
        implements CollectionManageableVectorStore {

    /** Default table name used when {@code collection} and its aliases are omitted. */
    public static final String DEFAULT_COLLECTION = "flink_agents_pgvector_collection";
    /** Default PostgreSQL schema. */
    public static final String DEFAULT_SCHEMA = "public";
    /** Defaults for {@code host}, {@code port} and {@code database} when {@code uri} is omitted. */
    public static final String DEFAULT_HOST = "localhost";

    public static final int DEFAULT_PORT = 5432;
    public static final String DEFAULT_DATABASE = "postgres";
    /** Default JDBC URL, built from the three defaults above. */
    public static final String DEFAULT_URI = jdbcUrl(DEFAULT_HOST, DEFAULT_PORT, DEFAULT_DATABASE);
    /** Default primary key column name. */
    public static final String DEFAULT_ID_FIELD = "id";
    /** Default column name used to store document content. */
    public static final String DEFAULT_CONTENT_FIELD = "content";
    /** Default {@code jsonb} column name used to store document metadata. */
    public static final String DEFAULT_METADATA_FIELD = "metadata";
    /** Default {@code vector} column name on which similarity search is executed. */
    public static final String DEFAULT_VECTOR_FIELD = "embedding";
    /** Default vector dimensionality used when {@code dims} is not provided. */
    public static final int DEFAULT_DIMENSION = 768;
    /** First pgvector version with {@code hnsw.iterative_scan} / {@code ivfflat.iterative_scan}. */
    static final int[] ITERATIVE_SCAN_MIN_VERSION = {0, 8};

    private static final Logger LOG = LoggerFactory.getLogger(PgVectorVectorStore.class);

    /** Distance metrics supported by pgvector for {@code vector} columns. */
    public enum MetricType {
        /** Cosine distance operator {@code <=>}; the score is the cosine similarity. */
        COSINE("<=>", "vector_cosine_ops"),
        /** Euclidean distance operator {@code <->}; the score is the distance. */
        L2("<->", "vector_l2_ops"),
        /** Negative inner product operator {@code <#>}; the score is the inner product. */
        IP("<#>", "vector_ip_ops");

        private final String operator;
        private final String operatorClass;

        MetricType(String operator, String operatorClass) {
            this.operator = operator;
            this.operatorClass = operatorClass;
        }

        /** Returns the pgvector distance operator of this metric. */
        public String getOperator() {
            return operator;
        }

        /** Returns the pgvector operator class used when indexing for this metric. */
        public String getOperatorClass() {
            return operatorClass;
        }

        /** Converts the raw operator result into the documented score of this metric. */
        float toScore(double distance) {
            switch (this) {
                case COSINE:
                    return (float) (1.0 - distance);
                case IP:
                    return (float) -distance;
                default:
                    return (float) distance;
            }
        }
    }

    /** Vector index types supported for created tables. */
    public enum IndexType {
        HNSW,
        IVFFLAT,
        /** Do not create a vector index; searches scan the table. */
        NONE
    }

    /** Iterative index scan modes applied to similarity searches (pgvector 0.8+). */
    public enum IterativeScan {
        /** Do not change the server's scan settings. */
        OFF,
        /** Keep scanning until enough rows match; results may be slightly out of order. */
        RELAXED_ORDER,
        /** Keep scanning until enough rows match, in exact distance order (HNSW only). */
        STRICT_ORDER;

        String sqlValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** PostgreSQL silently truncates longer identifiers, which would make names collide. */
    static final int MAX_IDENTIFIER_LENGTH = 63;

    /**
     * Longest indexed column name for which {@link #indexName(String, String)} can still derive a
     * unique index name for any table: {@code 63 - 8 (hash) - 1 (separator) - 5 ("_" and "_idx") -
     * 1 (table prefix)}.
     */
    static final int MAX_INDEXED_COLUMN_LENGTH =
            MAX_IDENTIFIER_LENGTH - 8 - 1 - "_".length() - "_idx".length() - 1;

    /** Advisory lock key serialising {@code CREATE EXTENSION} across all tables. */
    private static final String EXTENSION_LOCK_KEY = "flink_agents_pgvector.extension";
    /** pgvector's HNSW and IVFFlat indexes reject {@code vector} columns wider than this. */
    static final int MAX_INDEXED_DIMENSIONS = 2000;

    private static final Pattern INDEX_PARAM_VALUE = Pattern.compile("[A-Za-z0-9_]+");

    private final ObjectMapper mapper = new ObjectMapper();

    /** JDBC URL. */
    private final String uri;
    /** JDBC URL with any {@code user}/{@code password} query parameters removed. */
    private final String publicUri;
    /** Credentials; never exposed through {@link #getStoreKwargs()}. */
    private final @Nullable String username;

    private final @Nullable String password;
    /** Explicit timeouts; {@code null} leaves the driver's (or the URL's) setting in place. */
    private final @Nullable Integer connectTimeoutSeconds;

    private final @Nullable Integer socketTimeoutSeconds;
    /** PostgreSQL schema that holds the tables. */
    private final String schema;
    /** Default table name used when a per-call collection is not supplied. */
    private final String defaultCollection;

    private final String idField;
    private final String contentField;
    private final String metadataField;
    private final String vectorField;
    private final int dims;
    private final MetricType metricType;
    private final IndexType indexType;
    private final Map<String, Object> indexParams;
    private final boolean createExtension;
    private final IterativeScan iterativeScan;

    /** Whether the connected server's pgvector supports iterative scans; resolved lazily. */
    private @Nullable Boolean iterativeScanSupported;

    /**
     * Data source used to open connections. It carries the URL, credentials and timeouts, and its
     * class initialiser loads {@code org.postgresql.Driver}, which registers the driver with {@code
     * DriverManager} before {@code getConnection} goes through it; that global registration is why
     * the docs recommend Flink's {@code lib/} for the dist jar on long-lived session clusters.
     */
    private @Nullable PGSimpleDataSource dataSource;
    /**
     * One cached connection per store instance, revalidated and reopened when it broke. Operations
     * are {@code synchronized}, so concurrent actions on the same instance are serialized on it
     * rather than paying a connection handshake per call; parallelism comes from Flink subtasks,
     * each of which owns its own store instance.
     */
    private @Nullable Connection connection;

    /** Set by {@link #close()}; a closed store refuses to reconnect instead of leaking one. */
    private boolean closed;

    /**
     * Tables this instance has already created or verified, keyed by table and the expected
     * width/metric/index, so a repeated "ensure" call with the same expectations is a single
     * existence lookup. {@link #deleteCollection} forgets the table again.
     */
    private final Set<String> verifiedTables = new HashSet<>();

    /**
     * Creates a new {@code PgVectorVectorStore} from the provided descriptor and resource resolver.
     * The JDBC connection is opened lazily on first use.
     */
    public PgVectorVectorStore(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);

        this.uri = resolveUri(descriptor);
        this.publicUri = stripCredentials(this.uri);
        this.username = stringArg(descriptor, "username", null);
        this.password = stringArg(descriptor, "password", null);
        this.connectTimeoutSeconds = optionalSeconds(descriptor, "connect_timeout_s");
        this.socketTimeoutSeconds = optionalSeconds(descriptor, "socket_timeout_s");
        this.schema = identifierArg(descriptor, "schema", DEFAULT_SCHEMA);
        this.defaultCollection =
                identifier(
                        "collection",
                        stringArg(
                                descriptor,
                                "collection",
                                stringArg(
                                        descriptor,
                                        "collection_name",
                                        stringArg(descriptor, "index", DEFAULT_COLLECTION))));
        this.idField = identifierArg(descriptor, "id_field", DEFAULT_ID_FIELD);
        this.contentField = identifierArg(descriptor, "content_field", DEFAULT_CONTENT_FIELD);
        this.metadataField =
                indexedColumn(
                        "metadata_field",
                        identifierArg(descriptor, "metadata_field", DEFAULT_METADATA_FIELD));
        this.vectorField =
                indexedColumn(
                        "vector_field",
                        identifierArg(descriptor, "vector_field", DEFAULT_VECTOR_FIELD));
        this.dims = positive("dims", intArg(descriptor, "dims", DEFAULT_DIMENSION));
        this.metricType =
                enumArg(
                        MetricType.class,
                        "metric_type",
                        stringArg(descriptor, "metric_type", MetricType.COSINE.name()));
        this.indexType =
                enumArg(
                        IndexType.class,
                        "index_type",
                        stringArg(descriptor, "index_type", IndexType.HNSW.name()));
        this.indexParams = indexParams(mapArg(descriptor, "index_params"));
        this.createExtension = booleanArg(descriptor, "create_extension", true);
        this.iterativeScan =
                enumArg(
                        IterativeScan.class,
                        "iterative_scan",
                        stringArg(
                                descriptor, "iterative_scan", IterativeScan.RELAXED_ORDER.name()));
    }

    /**
     * Closes the cached connection. Failures are logged, never thrown, so teardown cannot mask the
     * failure that caused it.
     */
    @Override
    public synchronized void close() {
        this.closed = true;
        discardConnection();
        this.dataSource = null;
    }

    /**
     * Returns default store-level arguments collected from the descriptor. Credentials are not
     * included.
     */
    @Override
    public Map<String, Object> getStoreKwargs() {
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("uri", this.publicUri);
        kwargs.put("schema", this.schema);
        kwargs.put("collection", this.defaultCollection);
        kwargs.put("index", this.defaultCollection);
        kwargs.put("id_field", this.idField);
        kwargs.put("content_field", this.contentField);
        kwargs.put("metadata_field", this.metadataField);
        kwargs.put("vector_field", this.vectorField);
        kwargs.put("dims", this.dims);
        kwargs.put("metric_type", this.metricType.name());
        kwargs.put("index_type", this.indexType.name());
        kwargs.put("index_params", new LinkedHashMap<>(this.indexParams));
        kwargs.put("create_extension", this.createExtension);
        kwargs.put("iterative_scan", this.iterativeScan.name());
        return kwargs;
    }

    /**
     * Creates the table for the given collection if it does not exist, together with the vector
     * index for the configured metric and a GIN index on the metadata column. {@code dims}, {@code
     * metric_type}, {@code index_type} and {@code index_params} in {@code kwargs} override the
     * descriptor defaults for a new table.
     */
    @Override
    public synchronized void createCollectionIfNotExists(
            String name, @Nullable Map<String, Object> options) throws Exception {
        String table = identifier("collection", name);
        TableSpec spec = tableSpec(options);
        int dimension = spec.dimension;
        MetricType metric = spec.metric;
        IndexType index = spec.index;
        Map<String, Object> params = spec.indexParams;

        // "Ensure" always looks at the catalog, so a table dropped behind our back is recreated.
        // The common case, an existing table, is that one autocommit lookup and no lock; the
        // schema inspection below runs once per table and expectation for this instance.
        String verificationKey = table + "|" + dimension + "|" + metric + "|" + index;
        boolean existed;
        try {
            existed = tableExists(connection(), table);
        } catch (SQLException e) {
            discardIfBroken(e);
            throw e;
        }
        if (existed && this.verifiedTables.contains(verificationKey)) {
            return;
        }
        if (!existed) {
            // Only a table about to be created must fit its index; existing tables of any width
            // can be used (with index_type NONE, or with whatever index they already carry).
            checkIndexedDimensions(dimension, index);
            // One transaction under advisory locks: parallel subtasks creating the extension or
            // the same table would otherwise race past IF NOT EXISTS and fail on the catalog's
            // unique constraints. The extension lock is always taken first, so lock order is
            // consistent.
            inTransaction(
                    connection -> {
                        try (Statement statement = connection.createStatement();
                                PreparedStatement lock =
                                        connection.prepareStatement(
                                                "SELECT pg_advisory_xact_lock(hashtext(?))")) {
                            if (this.createExtension) {
                                lock.setString(1, EXTENSION_LOCK_KEY);
                                lock.execute();
                                statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
                                // The extension (and hence the answer) may have just appeared.
                                this.iterativeScanSupported = null;
                            }
                            lock.setString(1, this.schema + "." + table);
                            lock.execute();
                            // Another subtask may have created it between the check and the
                            // lock; IF NOT EXISTS below handles that, and reporting "created" for
                            // it only means the schema check runs, which it does anyway.
                            createTableAndIndexes(
                                    statement, table, dimension, metric, index, params);
                        }
                        return null;
                    });
        }
        // IF NOT EXISTS only compares names: an existing table may have been created for another
        // width or metric, and CREATE INDEX IF NOT EXISTS is skipped when an unrelated relation
        // already carries the index name. Say so instead of silently serving unindexed queries.
        try {
            for (String mismatch :
                    schemaMismatches(connection(), table, dimension, metric, index)) {
                LOG.warn(
                        "Collection {} ({}): {}",
                        qualified(table),
                        existed ? "already existed" : "just created",
                        mismatch);
            }
            this.verifiedTables.add(verificationKey);
        } catch (SQLException e) {
            discardIfBroken(e);
            throw e;
        }
    }

    /**
     * Whether a plain or partitioned table exists by that name. A view, sequence or other relation
     * occupying the name is reported as an error rather than silently treated as absent, because
     * {@code CREATE TABLE IF NOT EXISTS} would skip it and the index creation would then fail.
     */
    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT c.relkind FROM pg_class c"
                                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " WHERE n.nspname = ? AND c.relname = ?")) {
            statement.setString(1, this.schema);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return false;
                }
                String kind = rows.getString(1);
                if ("r".equals(kind) || "p".equals(kind)) {
                    return true;
                }
                throw new IllegalStateException(
                        qualified(table)
                                + " exists but is not a table (relkind '"
                                + kind
                                + "'); choose another collection name.");
            }
        }
    }

    private void createTableAndIndexes(
            Statement statement,
            String table,
            int dimension,
            MetricType metric,
            IndexType index,
            Map<String, Object> params)
            throws SQLException {
        statement.execute(
                "CREATE TABLE IF NOT EXISTS "
                        + qualified(table)
                        + " ("
                        + quote(this.idField)
                        + " TEXT PRIMARY KEY, "
                        + quote(this.contentField)
                        + " TEXT NOT NULL, "
                        + quote(this.metadataField)
                        + " JSONB, "
                        + quote(this.vectorField)
                        + " VECTOR("
                        + dimension
                        + "))");
        statement.execute(
                "CREATE INDEX IF NOT EXISTS "
                        + quote(indexName(table, this.metadataField))
                        + " ON "
                        + qualified(table)
                        + " USING GIN ("
                        + quote(this.metadataField)
                        + ")");
        if (index != IndexType.NONE) {
            statement.execute(
                    "CREATE INDEX IF NOT EXISTS "
                            + quote(indexName(table, this.vectorField))
                            + " ON "
                            + qualified(table)
                            + " USING "
                            + index.name().toLowerCase(Locale.ROOT)
                            + " ("
                            + quote(this.vectorField)
                            + " "
                            + metric.getOperatorClass()
                            + ")"
                            + withClause(params));
        }
    }

    /**
     * Compares an existing collection's vector column and index with this store's expectations and
     * returns one message per mismatch (empty when everything matches), for callers that prefer to
     * fail fast over reading the warnings {@link #createCollectionIfNotExists} logs. {@code
     * options} are interpreted as in {@link #createCollectionIfNotExists}.
     */
    public synchronized List<String> schemaMismatches(
            String name, @Nullable Map<String, Object> options) throws IOException {
        String table = identifier("collection", name);
        TableSpec spec = tableSpec(options);
        try {
            return schemaMismatches(connection(), table, spec.dimension, spec.metric, spec.index);
        } catch (SQLException e) {
            discardIfBroken(e);
            throw new IOException("Failed to inspect " + qualified(table) + ".", e);
        }
    }

    /** What a collection's table should look like: descriptor defaults overridden per call. */
    private static final class TableSpec {
        final int dimension;
        final MetricType metric;
        final IndexType index;
        final Map<String, Object> indexParams;

        TableSpec(int dimension, MetricType metric, IndexType index, Map<String, Object> params) {
            this.dimension = dimension;
            this.metric = metric;
            this.index = index;
            this.indexParams = params;
        }
    }

    /**
     * Resolves {@code dims}, {@code metric_type}, {@code index_type} and {@code index_params} from
     * per-call options over the descriptor defaults; {@code null} options (or values) fall back.
     */
    private TableSpec tableSpec(@Nullable Map<String, Object> options) {
        Map<String, Object> kwargs = options == null ? Collections.emptyMap() : options;
        int dimension = positive("dims", intFromMap(kwargs, "dims", this.dims));
        MetricType metric = enumFromMap(MetricType.class, kwargs, "metric_type", this.metricType);
        IndexType index = enumFromMap(IndexType.class, kwargs, "index_type", this.indexType);
        Object params = kwargs.get("index_params");
        return new TableSpec(
                dimension,
                metric,
                index,
                params == null ? this.indexParams : indexParams(toMap("index_params", params)));
    }

    private List<String> schemaMismatches(
            Connection connection, String table, int dimension, MetricType metric, IndexType index)
            throws SQLException {
        List<String> mismatches = new ArrayList<>();
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT t.typname, a.atttypmod FROM pg_attribute a"
                                + " JOIN pg_class c ON c.oid = a.attrelid"
                                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " JOIN pg_type t ON t.oid = a.atttypid"
                                + " WHERE n.nspname = ? AND c.relname = ? AND a.attname = ?")) {
            statement.setString(1, this.schema);
            statement.setString(2, table);
            statement.setString(3, this.vectorField);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    mismatches.add(
                            "table or column " + quote(this.vectorField) + " does not exist");
                    return mismatches;
                } else if (!"vector".equals(rows.getString(1))) {
                    mismatches.add(
                            "column "
                                    + quote(this.vectorField)
                                    + " has type "
                                    + rows.getString(1)
                                    + ", expected vector");
                } else if (rows.getInt(2) != dimension) {
                    // pgvector stores the declared width as the type modifier (-1 = unbounded).
                    mismatches.add(
                            "column "
                                    + quote(this.vectorField)
                                    + " is vector("
                                    + (rows.getInt(2) < 0 ? "" : rows.getInt(2))
                                    + "), expected vector("
                                    + dimension
                                    + ")");
                }
            }
        }
        if (index == IndexType.NONE) {
            return mismatches;
        }
        List<String> vectorIndexes = new ArrayList<>();
        boolean usable = false;
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT am.amname, oc.opcname FROM pg_index i"
                                + " JOIN pg_class c ON c.oid = i.indrelid"
                                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " JOIN pg_class ic ON ic.oid = i.indexrelid"
                                + " JOIN pg_am am ON am.oid = ic.relam"
                                + " JOIN pg_opclass oc ON oc.oid = i.indclass[0]"
                                + " JOIN pg_attribute a ON a.attrelid = c.oid"
                                + " AND a.attnum = i.indkey[0]"
                                + " WHERE n.nspname = ? AND c.relname = ? AND a.attname = ?"
                                + " AND am.amname IN ('hnsw', 'ivfflat')")) {
            statement.setString(1, this.schema);
            statement.setString(2, table);
            statement.setString(3, this.vectorField);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    vectorIndexes.add(rows.getString(1) + " (" + rows.getString(2) + ")");
                    usable |= metric.getOperatorClass().equals(rows.getString(2));
                }
            }
        }
        if (!usable) {
            mismatches.add(
                    "no "
                            + metric
                            + " ("
                            + metric.getOperatorClass()
                            + ") index on "
                            + quote(this.vectorField)
                            + (vectorIndexes.isEmpty()
                                    ? ""
                                    : "; existing vector indexes: " + vectorIndexes)
                            + "; similarity searches with this metric will scan the table");
        }
        return mismatches;
    }

    /** Drops the table for the given collection if it exists. */
    @Override
    public synchronized void deleteCollection(String name) throws Exception {
        String table = identifier("collection", name);
        this.verifiedTables.removeIf(key -> key.startsWith(table + "|"));
        try (Statement statement = connection().createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + qualified(table));
        } catch (SQLException e) {
            discardIfBroken(e);
            throw e;
        }
    }

    /**
     * Retrieve documents from the vector store.
     *
     * <p>If {@code ids} are provided, this method selects by primary key and ignores {@code
     * filters} and {@code limit} per the {@link BaseVectorStore} contract; an empty id list returns
     * no documents rather than scanning the table. Otherwise it selects rows whose metadata
     * contains {@code filters}, up to {@code limit} rows (all matching rows when {@code limit} is
     * null), ordered by id for a stable result.
     */
    @Override
    public synchronized List<Document> get(
            @Nullable List<String> ids,
            @Nullable String collection,
            @Nullable Map<String, Object> filters,
            @Nullable Integer limit,
            Map<String, Object> extraArgs)
            throws IOException {
        String table = resolveCollection(collection);
        if (ids != null && ids.isEmpty()) {
            return Collections.emptyList();
        }
        String select =
                "SELECT "
                        + quote(this.idField)
                        + ", "
                        + quote(this.contentField)
                        + ", "
                        + quote(this.metadataField)
                        + " FROM "
                        + qualified(table);
        try {
            Connection connection = connection();
            if (ids != null) {
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                select
                                        + " WHERE "
                                        + quote(this.idField)
                                        + " = ANY(?) ORDER BY "
                                        + quote(this.idField))) {
                    statement.setArray(1, textArray(connection, ids));
                    return readDocuments(statement, false);
                }
            }
            String filterJson = filtersToJson(filters);
            String sql =
                    select
                            + (filterJson == null
                                    ? ""
                                    : " WHERE " + quote(this.metadataField) + " @> ?::jsonb")
                            + " ORDER BY "
                            + quote(this.idField)
                            + (limit == null ? "" : " LIMIT ?");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int parameter = 1;
                if (filterJson != null) {
                    statement.setString(parameter++, filterJson);
                }
                if (limit != null) {
                    statement.setInt(parameter, positive("limit", limit));
                }
                return readDocuments(statement, false);
            }
        } catch (SQLException e) {
            discardIfBroken(e);
            throw new IOException("Failed to read documents from " + qualified(table) + ".", e);
        }
    }

    /**
     * Delete documents in the vector store.
     *
     * <p>If ids are provided, the corresponding rows are deleted; an empty id list is a no-op
     * rather than a request to delete everything. Otherwise rows whose metadata contains {@code
     * filters} are deleted; without filters every row in the table is deleted.
     */
    @Override
    public synchronized void delete(
            @Nullable List<String> ids,
            @Nullable String collection,
            @Nullable Map<String, Object> filters,
            Map<String, Object> extraArgs)
            throws IOException {
        String table = resolveCollection(collection);
        if (ids != null && ids.isEmpty()) {
            return;
        }
        try {
            Connection connection = connection();
            if (ids != null) {
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM "
                                        + qualified(table)
                                        + " WHERE "
                                        + quote(this.idField)
                                        + " = ANY(?)")) {
                    statement.setArray(1, textArray(connection, ids));
                    statement.executeUpdate();
                }
                return;
            }
            String filterJson = filtersToJson(filters);
            if (filterJson == null) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("DELETE FROM " + qualified(table));
                }
                return;
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "DELETE FROM "
                                    + qualified(table)
                                    + " WHERE "
                                    + quote(this.metadataField)
                                    + " @> ?::jsonb")) {
                statement.setString(1, filterJson);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            discardIfBroken(e);
            throw new IOException("Failed to delete documents from " + qualified(table) + ".", e);
        }
    }

    /**
     * Executes a similarity search using a pre-computed embedding.
     *
     * <p>Rows are ordered by the distance operator of the metric (nearest first) and the metric's
     * score is set on each {@link Document}. {@code metric_type} in {@code args} overrides the
     * descriptor metric for this query; the vector index is only used when it matches. On pgvector
     * 0.8+ the search runs with the configured {@code iterative_scan} mode (overridable in {@code
     * args}) so that an index scan keeps going until {@code limit} rows match, filtered or not.
     */
    @Override
    public synchronized List<Document> queryEmbedding(
            float[] embedding,
            int limit,
            @Nullable String collection,
            @Nullable Map<String, Object> filters,
            Map<String, Object> args) {
        String table = resolveCollection(collection);
        MetricType metric = enumFromMap(MetricType.class, args, "metric_type", this.metricType);
        IterativeScan scan =
                enumFromMap(IterativeScan.class, args, "iterative_scan", this.iterativeScan);
        String filterJson = filtersToJson(filters);
        String distance = quote(this.vectorField) + " " + metric.getOperator() + " ?::vector";
        // The distance column is referenced by position: any alias could clash with a user
        // column. A relaxed iterative scan may emit hits slightly out of order, so the LIMIT runs
        // in a materialized CTE and the outer query re-sorts the few rows it kept.
        String sql =
                "WITH hits AS MATERIALIZED (SELECT "
                        + quote(this.idField)
                        + ", "
                        + quote(this.contentField)
                        + ", "
                        + quote(this.metadataField)
                        + ", "
                        + distance
                        + " FROM "
                        + qualified(table)
                        + (filterJson == null
                                ? ""
                                : " WHERE " + quote(this.metadataField) + " @> ?::jsonb")
                        + " ORDER BY 4 LIMIT ?) SELECT * FROM hits ORDER BY 4";
        String vector = vectorLiteral(embedding);
        int rows = positive("limit", limit);
        Connection connection = null;
        boolean explicitBlock = false;
        try {
            connection = connection();
            // One execute() carries BEGIN, the SET LOCALs, the search and COMMIT: the scan mode is
            // scoped to this search, the server sees a proper transaction block (no "SET LOCAL
            // can only be used in transaction blocks" warning), and it is still one round trip
            // with no session state. This also matters without a filter: a plain HNSW scan
            // returns at most hnsw.ef_search rows.
            explicitBlock = scan != IterativeScan.OFF && supportsIterativeScan(connection);
            String statementText =
                    explicitBlock
                            ? "BEGIN; " + iterativeScanSettings(scan) + "; " + sql + "; COMMIT"
                            : sql;
            List<Document> documents;
            try (PreparedStatement statement = connection.prepareStatement(statementText)) {
                int parameter = 1;
                statement.setString(parameter++, vector);
                if (filterJson != null) {
                    statement.setString(parameter++, filterJson);
                }
                statement.setInt(parameter, rows);
                documents = readDocuments(statement, true);
            }
            for (Document document : documents) {
                if (document.getScore() != null) {
                    document.setScore(metric.toScore(document.getScore()));
                }
            }
            return documents;
        } catch (SQLException | IOException e) {
            if (explicitBlock) {
                // The server skipped COMMIT after the error and is sitting in an aborted
                // transaction block; end it so the connection stays usable.
                rollbackBlockQuietly(connection, e);
            }
            discardIfBroken(e);
            throw new IllegalStateException(
                    "Failed to query embeddings in " + qualified(table) + ".", e);
        }
    }

    /** Ends an explicit transaction block left open by a failed multi-statement execute. */
    private void rollbackBlockQuietly(Connection connection, Exception cause) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ROLLBACK");
        } catch (SQLException e) {
            cause.addSuppressed(e);
            discardConnection();
        }
    }

    /**
     * Whether the connected server's pgvector supports {@code hnsw.iterative_scan}. Once the
     * extension is installed its version decides and the answer is cached until the connection is
     * replaced or this instance installs the extension; while the extension is absent nothing is
     * cached, so a later installation by another party is picked up.
     */
    synchronized boolean supportsIterativeScan() throws SQLException {
        return supportsIterativeScan(connection());
    }

    /** Both transaction-local scan settings; IVFFlat only offers relaxed ordering. */
    private static String iterativeScanSettings(IterativeScan scan) {
        return "SET LOCAL hnsw.iterative_scan = "
                + scan.sqlValue()
                + "; SET LOCAL ivfflat.iterative_scan = "
                + IterativeScan.RELAXED_ORDER.sqlValue();
    }

    /**
     * Probes {@code pg_extension}: once the extension is present its version decides and the answer
     * is cached until the connection is replaced or this store installs the extension; an absent
     * extension is not cached, so a later installation by another party is picked up.
     */
    private boolean supportsIterativeScan(Connection connection) throws SQLException {
        Boolean supported = this.iterativeScanSupported;
        if (supported != null) {
            return supported;
        }
        try (Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT extversion FROM pg_extension WHERE extname = 'vector'")) {
            if (!rows.next()) {
                // Not installed (yet): unsupported for now, and probed again next time. Searches
                // cannot succeed without the extension anyway, so this is not a hot path.
                return false;
            }
            supported = isAtLeast(rows.getString(1), ITERATIVE_SCAN_MIN_VERSION);
        }
        this.iterativeScanSupported = supported;
        return supported;
    }

    /** Compares a dotted version such as {@code 0.8.6} against a minimum {@code {major, minor}}. */
    static boolean isAtLeast(@Nullable String version, int[] minimum) {
        if (version == null) {
            return false;
        }
        String[] parts = version.trim().replaceFirst("^[^0-9]+", "").split("[^0-9]+");
        for (int i = 0; i < minimum.length; i++) {
            int part = i < parts.length && !parts[i].isEmpty() ? Integer.parseInt(parts[i]) : 0;
            if (part != minimum[i]) {
                return part > minimum[i];
            }
        }
        return true;
    }

    /**
     * Add documents with pre-computed embeddings. Documents without ids get generated UUIDs; a
     * document whose id already exists replaces the stored row, as in the other Java stores, so a
     * replayed batch (Flink's at-least-once recovery) is idempotent. The batch is one transaction:
     * either every document is stored or none is.
     */
    @Override
    public synchronized List<String> addEmbedding(
            List<Document> documents, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }
        String table = resolveCollection(collection);
        List<String> ids = new ArrayList<>(documents.size());
        executeBatch(
                table,
                "add",
                upsertSql(table),
                statement -> {
                    Map<String, Document> rows = new LinkedHashMap<>();
                    for (Document document : documents) {
                        String id = document.getId();
                        if (id == null || id.isEmpty()) {
                            id = UUID.randomUUID().toString();
                        }
                        ids.add(id);
                        rows.put(id, document);
                    }
                    bindRows(statement, rows);
                });
        return ids;
    }

    /**
     * Update documents with pre-computed embeddings by upserting on the primary key, in one
     * transaction. The public {@link BaseVectorStore#update(List, String, Map)} path already
     * enforces that every document carries an id.
     */
    @Override
    public synchronized void updateEmbedding(
            List<Document> documents, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        String table = resolveCollection(collection);
        executeBatch(
                table,
                "update",
                upsertSql(table),
                statement -> {
                    Map<String, Document> rows = new LinkedHashMap<>();
                    for (Document document : documents) {
                        if (document.getId() == null || document.getId().isEmpty()) {
                            // Same rule as add, which treats "" as "generate one".
                            throw new IllegalArgumentException(
                                    "Every document passed to `update` must have `id` set.");
                        }
                        rows.put(document.getId(), document);
                    }
                    bindRows(statement, rows);
                });
    }

    // ---- SQL helpers

    /** Insert-or-replace by primary key; used by both {@code add} and {@code update}. */
    private String upsertSql(String table) {
        return "INSERT INTO "
                + qualified(table)
                + " ("
                + quote(this.idField)
                + ", "
                + quote(this.contentField)
                + ", "
                + quote(this.metadataField)
                + ", "
                + quote(this.vectorField)
                + ") VALUES (?, ?, ?::jsonb, ?::vector) ON CONFLICT ("
                + quote(this.idField)
                + ") DO UPDATE SET "
                + quote(this.contentField)
                + " = EXCLUDED."
                + quote(this.contentField)
                + ", "
                + quote(this.metadataField)
                + " = EXCLUDED."
                + quote(this.metadataField)
                + ", "
                + quote(this.vectorField)
                + " = EXCLUDED."
                + quote(this.vectorField);
    }

    /**
     * Returns the cached connection, reopening it when it is missing or closed. No validation round
     * trip is made on the hot path: pgjdbc closes the connection on I/O failures, and {@link
     * #discardIfBroken(Exception)} drops it on connection-level SQL states, so the next call
     * reconnects.
     */
    private Connection connection() throws SQLException {
        if (this.closed) {
            throw new IllegalStateException("PgVectorVectorStore has been closed.");
        }
        Connection current = this.connection;
        if (current != null && !current.isClosed()) {
            return current;
        }
        discardConnection();
        PGSimpleDataSource source = this.dataSource;
        if (source == null) {
            source = new PGSimpleDataSource();
            source.setUrl(this.uri);
            if (this.username != null) {
                source.setUser(this.username);
            }
            if (this.password != null) {
                source.setPassword(this.password);
            }
            // Only explicit arguments override what the URL (or the driver default) says.
            if (this.connectTimeoutSeconds != null) {
                source.setConnectTimeout(this.connectTimeoutSeconds);
                source.setLoginTimeout(this.connectTimeoutSeconds);
            }
            if (this.socketTimeoutSeconds != null) {
                source.setSocketTimeout(this.socketTimeoutSeconds);
            }
            this.dataSource = source;
        }
        current = source.getConnection();
        this.connection = current;
        try {
            current.setAutoCommit(true);
        } catch (SQLException e) {
            discardConnection();
            throw e;
        }
        return current;
    }

    /**
     * Rolls back and restores auto-commit after a failed transaction. Follow-up failures are
     * attached to {@code cause} as suppressed exceptions so the root cause is never masked, and a
     * connection that turned out to be broken is discarded.
     */
    private void abortTransaction(Connection connection, Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
        discardIfBroken(cause);
    }

    /**
     * Drops the cached connection when {@code failure} (or one of its causes/suppressed errors)
     * reports a connection-level SQL state (class 08), an operator-initiated termination (class
     * 57P) or an aborted transaction block (25P02), so that the next operation reconnects instead
     * of failing again.
     */
    private void discardIfBroken(Throwable failure) {
        Connection current = this.connection;
        if (current == null) {
            return;
        }
        try {
            if (current.isClosed() || hasConnectionFailure(failure)) {
                discardConnection();
            }
        } catch (SQLException e) {
            discardConnection();
        }
    }

    private static boolean hasConnectionFailure(@Nullable Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException) {
                String state = ((SQLException) t).getSQLState();
                if (state != null
                        && (state.startsWith("08")
                                || state.startsWith("57P")
                                || state.equals("25P02"))) {
                    return true;
                }
            }
            for (Throwable suppressed : t.getSuppressed()) {
                if (hasConnectionFailure(suppressed)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Closes and forgets the cached connection; errors while closing are ignored. */
    private void discardConnection() {
        Connection current = this.connection;
        this.connection = null;
        this.iterativeScanSupported = null;
        if (current != null) {
            try {
                current.close();
            } catch (SQLException e) {
                LOG.warn("Failed to close PostgreSQL connection.", e);
            }
        }
    }

    private static void checkIndexedDimensions(int dims, IndexType index) {
        if (index != IndexType.NONE && dims > MAX_INDEXED_DIMENSIONS) {
            throw new IllegalArgumentException(
                    "dims "
                            + dims
                            + " exceeds the "
                            + MAX_INDEXED_DIMENSIONS
                            + "-dimension limit of pgvector "
                            + index
                            + " indexes on vector columns; use index_type NONE for wider vectors.");
        }
    }

    /** Work executed inside {@link #inTransaction}. */
    private interface TransactionBody<T> {
        T run(Connection connection) throws SQLException, IOException;
    }

    /**
     * Runs {@code body} as one transaction on the cached connection: auto-commit off, commit on
     * success, rollback and auto-commit restore on failure with follow-up errors attached as
     * suppressed exceptions. If the transaction cannot be settled either way, the connection is
     * discarded rather than kept in an unknown state.
     */
    private <T> T inTransaction(TransactionBody<T> body) throws SQLException, IOException {
        Connection connection = connection();
        boolean settled = false;
        try {
            connection.setAutoCommit(false);
            T result;
            try {
                result = body.run(connection);
                connection.commit();
            } catch (SQLException | IOException | RuntimeException | Error e) {
                abortTransaction(connection, e);
                settled = true;
                throw e;
            }
            settled = true;
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                // The work is committed and durable; only the connection is in doubt. Reporting a
                // failure here would make callers retry (and duplicate) committed rows.
                LOG.warn("Discarding PostgreSQL connection after a committed transaction.", e);
                discardConnection();
            }
            return result;
        } catch (SQLException e) {
            if (settled) {
                discardIfBroken(e);
            } else {
                discardConnection();
            }
            throw e;
        }
    }

    /** Binds rows for a batched write. */
    private interface RowBinder {
        void bind(PreparedStatement statement) throws SQLException, IOException;
    }

    /** Runs a batched write as one transaction, rolling back when any row fails. */
    private void executeBatch(String table, String operation, String sql, RowBinder binder)
            throws IOException {
        try {
            inTransaction(
                    connection -> {
                        try (PreparedStatement statement = connection.prepareStatement(sql)) {
                            binder.bind(statement);
                            statement.executeBatch();
                        }
                        return null;
                    });
        } catch (SQLException | IOException e) {
            throw new IOException(
                    "Failed to " + operation + " documents in " + qualified(table) + ".", e);
        }
    }

    /**
     * Adds one batch row per id, the last document for an id winning. A batch that names the same
     * id twice would otherwise fail under pgjdbc's {@code reWriteBatchedInserts}, which folds the
     * batch into one multi-row INSERT that ON CONFLICT may not touch twice.
     */
    private void bindRows(PreparedStatement statement, Map<String, Document> rows)
            throws SQLException, IOException {
        for (Map.Entry<String, Document> row : rows.entrySet()) {
            bindRow(statement, row.getKey(), row.getValue());
            statement.addBatch();
        }
    }

    private void bindRow(PreparedStatement statement, String id, Document document)
            throws SQLException, IOException {
        if (document.getEmbedding() == null) {
            throw new IllegalArgumentException("Document embedding must not be null.");
        }
        statement.setString(1, id);
        statement.setString(2, document.getContent() == null ? "" : document.getContent());
        statement.setString(3, toJson(document.getMetadata()));
        statement.setString(4, vectorLiteral(document.getEmbedding()));
    }

    private List<Document> readDocuments(PreparedStatement statement, boolean withDistance)
            throws SQLException, IOException {
        List<Document> documents = new ArrayList<>();
        // execute() rather than executeQuery(): the text may start with SET LOCAL statements.
        boolean resultSet = statement.execute();
        while (!resultSet) {
            if (statement.getUpdateCount() == -1) {
                throw new SQLException("The statement returned no result set.");
            }
            resultSet = statement.getMoreResults();
        }
        try (ResultSet rows = statement.getResultSet()) {
            while (rows.next()) {
                String id = rows.getString(1);
                String content = rows.getString(2);
                Map<String, Object> metadata = fromJson(rows.getString(3));
                Float score = null;
                if (withDistance) {
                    double distance = rows.getDouble(4);
                    // A NULL vector yields a NULL distance; never report it as a perfect match.
                    score = rows.wasNull() ? null : (float) distance;
                }
                documents.add(
                        new Document(content == null ? "" : content, metadata, id, null, score));
            }
        }
        return documents;
    }

    private static Array textArray(Connection connection, List<String> ids) throws SQLException {
        return connection.createArrayOf("text", ids.toArray());
    }

    /**
     * Compiles the unified equality-only filter DSL into the JSON object used as the right-hand
     * side of a {@code jsonb} containment predicate. Returns {@code null} when there is nothing to
     * filter on. Only scalar values are equality-comparable: nested maps, collections, arrays and
     * null values are rejected, because {@code jsonb} containment would match arrays as subsets.
     */
    @Nullable
    String filtersToJson(@Nullable Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        Map<String, Object> containment = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            Object value = entry.getValue();
            if (value == null
                    || value instanceof Map
                    || value instanceof Collection
                    || value.getClass().isArray()) {
                throw new UnsupportedOperationException(
                        "PgVectorVectorStore filters support scalar equality shorthand only.");
            }
            containment.put(entry.getKey(), value);
        }
        try {
            return toJson(containment);
        } catch (IOException e) {
            throw new IllegalArgumentException("Filters are not JSON-serializable.", e);
        }
    }

    private String toJson(@Nullable Map<String, Object> value) throws IOException {
        try {
            return this.mapper.writeValueAsString(value == null ? Collections.emptyMap() : value);
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to serialize metadata as JSON.", e);
        }
    }

    /**
     * Reads a metadata cell. Documents write JSON objects; a pre-existing table may hold arrays or
     * scalars, which are returned under the key {@code value} rather than failing the whole read.
     */
    private Map<String, Object> fromJson(@Nullable String json) throws IOException {
        if (json == null || json.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Object value = this.mapper.readValue(json, Object.class);
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            return new LinkedHashMap<>(map);
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("value", value);
        return wrapped;
    }

    /** Formats an embedding as the pgvector text literal {@code [x,y,z]}. */
    static String vectorLiteral(float[] embedding) {
        StringBuilder literal = new StringBuilder(embedding.length * 10 + 2).append('[');
        for (int i = 0; i < embedding.length; i++) {
            float value = embedding[i];
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw new IllegalArgumentException(
                        "Embedding values must be finite, got " + value + " at index " + i + ".");
            }
            if (i > 0) {
                literal.append(',');
            }
            literal.append(value);
        }
        return literal.append(']').toString();
    }

    private String resolveCollection(@Nullable String collectionName) {
        return collectionName == null
                ? this.defaultCollection
                : identifier("collection", collectionName);
    }

    private String qualified(String table) {
        return quote(this.schema) + "." + quote(table);
    }

    /**
     * Builds the name of the index on {@code column}: {@code <table>_<column>_idx}, shortened with
     * a hash of the table name when it would exceed {@link #MAX_IDENTIFIER_LENGTH}, so that the
     * names of a table's indexes never collide after PostgreSQL's truncation.
     */
    static String indexName(String table, String column) {
        String suffix = "_" + column + "_idx";
        String name = table + suffix;
        if (name.length() <= MAX_IDENTIFIER_LENGTH) {
            return name;
        }
        String hash = String.format("%08x", table.hashCode());
        int keep = MAX_IDENTIFIER_LENGTH - suffix.length() - hash.length() - 1;
        if (keep < 1) {
            throw new IllegalArgumentException(
                    "Column name too long to derive an index name: " + column);
        }
        return table.substring(0, keep) + "_" + hash + suffix;
    }

    /** Quotes an already validated identifier. */
    private static String quote(String identifier) {
        return "\"" + identifier + "\"";
    }

    /** Renders validated index storage parameters as a {@code WITH (...)} clause. */
    private static String withClause(Map<String, Object> params) {
        if (params.isEmpty()) {
            return "";
        }
        StringBuilder clause = new StringBuilder(" WITH (");
        boolean first = true;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (!first) {
                clause.append(", ");
            }
            first = false;
            clause.append(entry.getKey()).append(" = ").append(entry.getValue());
        }
        return clause.append(')').toString();
    }

    /**
     * Validates index storage parameters: keys are identifiers and values are integers (integral
     * floating-point numbers such as YAML {@code 16.0} are normalized) or simple word tokens, so
     * they can be rendered into DDL without quoting.
     */
    private static Map<String, Object> indexParams(Map<String, Object> raw) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = identifier("index_params key", entry.getKey());
            Object value = entry.getValue();
            if (value instanceof Number) {
                double number = ((Number) value).doubleValue();
                if (Double.isNaN(number)
                        || Double.isInfinite(number)
                        || number != Math.rint(number)) {
                    throw new IllegalArgumentException(
                            "index_params value for '"
                                    + key
                                    + "' must be an integer, got: "
                                    + value);
                }
                params.put(key, ((Number) value).longValue());
                continue;
            }
            String text = String.valueOf(value);
            if (value == null || !INDEX_PARAM_VALUE.matcher(text).matches()) {
                throw new IllegalArgumentException(
                        "index_params value for '"
                                + key
                                + "' must be an integer or a plain word, got: "
                                + text);
            }
            params.put(key, text);
        }
        return params;
    }

    // ---- argument parsing

    private static String resolveUri(ResourceDescriptor descriptor) {
        String uri = stringArg(descriptor, "uri", null);
        if (uri != null && !uri.isEmpty()) {
            if (!uri.startsWith("jdbc:postgresql:")) {
                throw new IllegalArgumentException(
                        "uri must be a PostgreSQL JDBC URL starting with jdbc:postgresql:, got: "
                                + redactUri(uri));
            }
            for (String conflicting : new String[] {"host", "port", "database"}) {
                if (descriptor.getArgument(conflicting) != null) {
                    throw new IllegalArgumentException(
                            "uri and " + conflicting + " must not both be set.");
                }
            }
            return uri;
        }
        return jdbcUrl(
                stringArg(descriptor, "host", DEFAULT_HOST),
                positive("port", intArg(descriptor, "port", DEFAULT_PORT)),
                stringArg(descriptor, "database", DEFAULT_DATABASE));
    }

    private static String jdbcUrl(String host, int port, String database) {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    /**
     * Removes {@code user}, {@code password} and any other {@code *password} query parameter (such
     * as pgjdbc's {@code sslpassword}) from a JDBC URL so that credentials embedded in {@code uri}
     * never leak through {@link #getStoreKwargs()}.
     */
    static String stripCredentials(String uri) {
        int query = uri.indexOf('?');
        if (query < 0) {
            return uri;
        }
        StringBuilder kept = new StringBuilder();
        for (String parameter : uri.substring(query + 1).split("&")) {
            int eq = parameter.indexOf('=');
            String key = (eq < 0 ? parameter : parameter.substring(0, eq)).toLowerCase(Locale.ROOT);
            if (key.equals("user") || key.endsWith("password")) {
                continue;
            }
            kept.append(kept.length() == 0 ? '?' : '&').append(parameter);
        }
        return uri.substring(0, query) + kept;
    }

    /** Masks {@code user:password@} and drops credential query parameters for messages. */
    static String redactUri(String uri) {
        return stripCredentials(uri).replaceAll("//[^/@]*@", "//***@");
    }

    /** Validates that an indexed column name leaves room for a unique index name. */
    private static String indexedColumn(String argumentName, String value) {
        if (value.length() > MAX_INDEXED_COLUMN_LENGTH) {
            throw new IllegalArgumentException(
                    argumentName
                            + " must be at most "
                            + MAX_INDEXED_COLUMN_LENGTH
                            + " characters so that its index name fits the PostgreSQL identifier"
                            + " limit, got "
                            + value.length()
                            + ": "
                            + value);
        }
        return value;
    }

    private static String identifierArg(
            ResourceDescriptor descriptor, String key, String defaultValue) {
        return identifier(key, stringArg(descriptor, key, defaultValue));
    }

    /** Validates a SQL identifier so it can be safely quoted into statements. */
    static String identifier(String argumentName, @Nullable String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    argumentName
                            + " must match [A-Za-z_][A-Za-z0-9_]* (letters, digits, underscores),"
                            + " got: "
                            + value);
        }
        if (value.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(
                    argumentName
                            + " must be at most "
                            + MAX_IDENTIFIER_LENGTH
                            + " characters (PostgreSQL identifier limit), got "
                            + value.length()
                            + ": "
                            + value);
        }
        return value;
    }

    private static @Nullable Integer optionalSeconds(ResourceDescriptor descriptor, String key) {
        if (descriptor.getArgument(key) == null) {
            return null;
        }
        int value = intArg(descriptor, key, 0);
        if (value < 0) {
            throw new IllegalArgumentException(key + " must be zero or positive, got: " + value);
        }
        return value;
    }

    private static int positive(String argumentName, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    argumentName + " must be a positive integer, got: " + value);
        }
        return value;
    }

    private static Map<String, Object> mapArg(ResourceDescriptor descriptor, String key) {
        return toMap(key, descriptor.getArgument(key));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(String key, @Nullable Object value) {
        if (value instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) value);
        }
        if (value != null) {
            throw new IllegalArgumentException(key + " must be a map.");
        }
        return Collections.emptyMap();
    }

    private static String stringArg(
            ResourceDescriptor descriptor, String key, @Nullable String defaultValue) {
        Object value = descriptor.getArgument(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static int intArg(ResourceDescriptor descriptor, String key, int defaultValue) {
        Object value = descriptor.getArgument(key);
        return intValue(key, value, defaultValue);
    }

    private static int intFromMap(Map<String, Object> args, String key, int defaultValue) {
        return intValue(key, args.get(key), defaultValue);
    }

    private static int intValue(String key, @Nullable Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (number != Math.rint(number)
                    || number < Integer.MIN_VALUE
                    || number > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(key + " must be an integer, got: " + value);
            }
            return (int) number;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer, got: " + value, e);
        }
    }

    private static boolean booleanArg(
            ResourceDescriptor descriptor, String key, boolean defaultValue) {
        Object value = descriptor.getArgument(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (text.equals("true") || text.equals("false")) {
            return Boolean.parseBoolean(text);
        }
        throw new IllegalArgumentException(key + " must be true or false, got: " + value);
    }

    private static <E extends Enum<E>> E enumFromMap(
            Class<E> enumClass, Map<String, Object> args, String key, E defaultValue) {
        Object value = args.get(key);
        return value == null ? defaultValue : enumArg(enumClass, key, String.valueOf(value));
    }

    private static <E extends Enum<E>> E enumArg(Class<E> enumClass, String key, String value) {
        try {
            return Enum.valueOf(enumClass, value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    key
                            + " must be one of "
                            + List.of(enumClass.getEnumConstants())
                            + ", got: "
                            + value,
                    e);
        }
    }
}
