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

package org.apache.flink.agents.api.vectorstores;

import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelSetup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Base abstract class for vector store. Provides vector store functionality that integrates
 * embedding models for text-based semantic search. Handles both connection management and embedding
 * generation internally.
 *
 * <h2>Filter DSL</h2>
 *
 * Every vector store operation that takes {@code filters} accepts the same unified dialect. The
 * dialect intentionally covers only the subset that every supported backend can honour — equality
 * matching — so callers writing to {@code BaseVectorStore} never have to know which native
 * operators a given store supports.
 *
 * <p>Grammar (values must be JSON-compatible scalars):
 *
 * <pre>{@code
 * // Equality — "field equals value":
 * Map.of("field", value)
 *
 * // Multiple top-level keys are implicitly AND-ed:
 * Map.of("user_id", "u1", "run_id", "r1")
 * }</pre>
 *
 * {@code null} means "no filter". Richer operators (ranges, set membership, OR, NOT, etc.) are
 * deliberately out of scope here because most vector-store backends do not support them uniformly.
 * Callers who need backend-specific filters should pass them through {@code extraArgs} (e.g.
 * ChromaDB's {@code where} dict); implementations that receive an unsupported operator via {@code
 * filters} should throw {@link UnsupportedOperationException}.
 */
public abstract class BaseVectorStore extends Resource {

    /**
     * Name of the embedding model resource to use. May be {@code null} when callers always provide
     * pre-computed vectors.
     */
    protected final @Nullable String embeddingModelName;

    /**
     * The embedding model resolved from {@link #embeddingModelName} during {@link #open()}, or
     * {@code null} when no embedding model is configured.
     */
    protected @Nullable BaseEmbeddingModelSetup embeddingModel;

    /**
     * Default collection this store operates on when a caller does not specify one. {@code null}
     * means the backend's own fallback applies.
     */
    protected final @Nullable String collection;

    public BaseVectorStore(
            ResourceDescriptor descriptor, BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
        this.embeddingModelName = descriptor.getArgument("embedding_model");
        this.collection = descriptor.getArgument("collection");
    }

    /**
     * Trigger construction for resource objects.
     *
     * <p>Currently, in cross-language invocation scenarios, constructing resource object within an
     * async thread may encounter issues. We resolved this issue by moving the construction of the
     * resources object out of the method to be async executed and invoking it in the main thread.
     */
    @Override
    public void open() {
        if (this.embeddingModelName != null) {
            this.embeddingModel =
                    (BaseEmbeddingModelSetup)
                            this.getResource.apply(
                                    this.embeddingModelName, ResourceType.EMBEDDING_MODEL);
        }
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.VECTOR_STORE;
    }

    /**
     * Returns vector store setup settings passed to connection. These parameters are merged with
     * query-specific parameters when performing vector search operations.
     *
     * @return A map containing the store configuration parameters
     */
    public abstract Map<String, Object> getStoreKwargs();

    /**
     * Add documents to vector store.
     *
     * <p>Auto-embeds any document whose {@code embedding} field is {@code null} via the configured
     * embedding model. Implementations may generate IDs for documents that don't have one.
     *
     * @param documents The documents to be added.
     * @param collection The name of the collection to add to. If is null, will add documents to the
     *     default collection.
     * @param extraArgs The vector store specific arguments.
     * @return The IDs of the documents added.
     */
    public List<String> add(
            List<Document> documents, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException {
        ensureEmbeddings(documents);

        final Map<String, Object> storeKwargs = this.getStoreKwargs();
        storeKwargs.putAll(extraArgs);

        return this.addEmbedding(documents, collection, storeKwargs);
    }

    /**
     * Update existing documents in the vector store.
     *
     * <p>Mirrors {@link #add}'s shape — identity lives on the document itself ({@link
     * Document#getId()}). Each document must already have its {@code id} set; unlike {@link #add},
     * update does not generate ids.
     *
     * @param documents Document(s) carrying {@code id} + the new content / metadata / embedding.
     * @param collection Target collection. If null, use the default collection.
     * @param extraArgs Vector store specific parameters.
     */
    public void update(
            List<Document> documents, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException {
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("`documents` must be non-empty.");
        }
        for (Document doc : documents) {
            if (doc.getId() == null) {
                throw new IllegalArgumentException(
                        "Every document passed to `update` must have `id` set.");
            }
        }
        ensureEmbeddings(documents);

        final Map<String, Object> storeKwargs = this.getStoreKwargs();
        storeKwargs.putAll(extraArgs);

        this.updateEmbedding(documents, collection, storeKwargs);
    }

    /**
     * Performs vector search using structured query object. Converts text query to embeddings and
     * returns structured query result.
     *
     * @param query VectorStoreQuery object containing query parameters
     * @return VectorStoreQueryResult containing the retrieved documents
     */
    public VectorStoreQueryResult query(VectorStoreQuery query) {
        final float[] queryEmbedding = getEmbeddingModel().embed(query.getQueryText());

        final Map<String, Object> storeKwargs = this.getStoreKwargs();
        storeKwargs.putAll(query.getExtraArgs());

        final List<Document> documents =
                this.queryEmbedding(
                        queryEmbedding,
                        query.getLimit(),
                        query.getCollection(),
                        query.getFilters(),
                        storeKwargs);

        return new VectorStoreQueryResult(documents);
    }

    /**
     * Retrieve documents from the vector store.
     *
     * <p>When {@code ids} is provided, the {@code ids} list itself bounds the result size and
     * {@code limit} is effectively ignored. Without {@code ids}, up to {@code limit} documents
     * matching {@code filters} (or all, when no filter is set) are returned — callers who genuinely
     * need the full collection should pass {@code limit=null} explicitly; the bounded default
     * exists to avoid silently loading a whole collection into memory.
     *
     * @param ids The ids of the documents. If is null, retrieve documents matching {@code filters}.
     * @param collection The name of the collection to be retrieved. If is null, retrieve the
     *     default collection.
     * @param filters Metadata filter in the unified DSL (see class Javadoc); {@code null} = no
     *     filter.
     * @param limit Maximum number of documents to return. Defaults to 100; pass {@code null} for
     *     unbounded.
     * @param extraArgs Additional arguments.
     * @return List of documents retrieved.
     */
    public abstract List<Document> get(
            @Nullable List<String> ids,
            @Nullable String collection,
            @Nullable Map<String, Object> filters,
            @Nullable Integer limit,
            Map<String, Object> extraArgs)
            throws IOException;

    /**
     * Convenience overload: get by ids from the default collection.
     *
     * <p>Named {@code getByIds} rather than overloading {@code get} so that the cross-language
     * Python bridge sees a single {@code get} method on the Java side: Pemja's multi-overload
     * matcher cannot dispatch to a method when any argument is Python {@code None}.
     */
    public final List<Document> getByIds(List<String> ids) throws IOException {
        return get(ids, null, null, null, Map.of());
    }

    /** Convenience overload: get by ids from a specific collection. */
    public final List<Document> getByIds(List<String> ids, String collection) throws IOException {
        return get(ids, collection, null, null, Map.of());
    }

    /**
     * Convenience overload: get by filters from the default collection. Pass {@code limit=null} for
     * unbounded; otherwise the backend default (100) applies.
     */
    public final List<Document> getByFilters(Map<String, Object> filters) throws IOException {
        return get(null, null, filters, null, Map.of());
    }

    /** Convenience overload: get by filters with an explicit row limit. */
    public final List<Document> getByFilters(Map<String, Object> filters, int limit)
            throws IOException {
        return get(null, null, filters, limit, Map.of());
    }

    /**
     * Delete documents in the vector store.
     *
     * @param ids The ids of the documents. If is null, delete documents matching {@code filters}.
     * @param collection The name of the collection the documents belong to. If is null, use the
     *     default collection.
     * @param filters Metadata filter in the unified DSL (see class Javadoc); {@code null} = no
     *     filter.
     * @param extraArgs Additional arguments.
     */
    public abstract void delete(
            @Nullable List<String> ids,
            @Nullable String collection,
            @Nullable Map<String, Object> filters,
            Map<String, Object> extraArgs)
            throws IOException;

    /**
     * Convenience overload: delete by ids from the default collection.
     *
     * <p>Named {@code deleteByIds} rather than overloading {@code delete} so that the
     * cross-language Python bridge sees a single {@code delete} method on the Java side: Pemja's
     * multi-overload matcher cannot dispatch to a method when any argument is Python {@code None}.
     */
    public final void deleteByIds(List<String> ids) throws IOException {
        delete(ids, null, null, Map.of());
    }

    /** Convenience overload: delete by ids from a specific collection. */
    public final void deleteByIds(List<String> ids, String collection) throws IOException {
        delete(ids, collection, null, Map.of());
    }

    /** Convenience overload: delete by filters from the default collection. */
    public final void deleteByFilters(Map<String, Object> filters) throws IOException {
        delete(null, null, filters, Map.of());
    }

    /**
     * Performs vector search using a pre-computed embedding.
     *
     * @param embedding The embedding vector to search with
     * @param limit Maximum number of results to return
     * @param collection The collection to query to. If is null, query the default collection.
     * @param filters Metadata filter in the unified DSL (see class Javadoc); {@code null} = no
     *     filter.
     * @param args Additional arguments for the vector search
     * @return List of documents matching the query embedding
     */
    public abstract List<Document> queryEmbedding(
            float[] embedding,
            int limit,
            @Nullable String collection,
            @Nullable Map<String, Object> filters,
            Map<String, Object> args);

    /**
     * Add documents with pre-computed embedding to vector store.
     *
     * @param documents The documents to be added.
     * @param collection The name of the collection to add to. If is null, add to the default
     *     collection.
     * @param extraArgs Additional arguments.
     * @return IDs of the added documents.
     */
    public abstract List<String> addEmbedding(
            List<Document> documents, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException;

    /**
     * Update documents with pre-computed embeddings. Identity is read from {@link
     * Document#getId()}.
     *
     * @param documents Documents carrying id + new content/metadata/embedding.
     * @param collection Target collection. If null, use the default collection.
     * @param extraArgs Additional arguments.
     */
    public abstract void updateEmbedding(
            List<Document> documents, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException;

    /** Auto-embed any documents whose {@code embedding} field is {@code null}. */
    protected void ensureEmbeddings(List<Document> documents) {
        for (Document doc : documents) {
            if (doc.getEmbedding() == null) {
                doc.setEmbedding(getEmbeddingModel().embed(doc.getContent()));
            }
        }
    }

    private BaseEmbeddingModelSetup getEmbeddingModel() {
        if (embeddingModel == null) {
            throw new IllegalStateException(
                    "No embedding model configured on this vector store. "
                            + "Pass `embedding_model=` at construction or supply pre-computed vectors "
                            + "in the Documents / VectorStoreQuery before calling this method.");
        }
        return embeddingModel;
    }
}
