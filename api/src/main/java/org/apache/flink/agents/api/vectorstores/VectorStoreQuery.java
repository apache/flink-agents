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

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Parameters for querying a {@link BaseVectorStore}.
 *
 * <p>A query consists of a textual prompt that will be embedded (for semantic search) or used as a
 * keyword string, an optional {@code limit} for the number of results, a {@link
 * VectorStoreQueryMode}, and an optional map of store-specific arguments.
 */
public class VectorStoreQuery {

    /** How the query should be executed (semantic, keyword, or hybrid). */
    private final VectorStoreQueryMode mode;
    /** The user-provided query text. */
    private final String queryText;
    /** Maximum number of documents to return. */
    private final Integer limit;
    /** The name of the collection to query to. */
    private final @Nullable String collection;
    /** Additional store-specific parameters. */
    private final Map<String, Object> extraArgs;

    public VectorStoreQuery(String queryText, Integer limit) {
        this(VectorStoreQueryMode.SEMANTIC, queryText, limit, null, new HashMap<>());
    }

    /**
     * Creates a semantic-search query with default mode {@link VectorStoreQueryMode#SEMANTIC}.
     *
     * @param queryText the text to embed and search for
     * @param limit maximum number of results to return
     * @param collection the collection to query to
     * @param extraArgs store-specific additional parameters
     */
    public VectorStoreQuery(
            String queryText, Integer limit, String collection, Map<String, Object> extraArgs) {
        this(VectorStoreQueryMode.SEMANTIC, queryText, limit, collection, extraArgs);
    }

    /**
     * Creates a query with explicit mode and extra arguments.
     *
     * @param mode the query mode
     * @param queryText the text to search for
     * @param limit maximum number of results to return
     * @param collection the collection to query to
     * @param extraArgs store-specific additional parameters
     */
    public VectorStoreQuery(
            VectorStoreQueryMode mode,
            String queryText,
            Integer limit,
            @Nullable String collection,
            Map<String, Object> extraArgs) {
        this.mode = mode;
        this.queryText = queryText;
        this.limit = limit;
        this.collection = collection;
        this.extraArgs = extraArgs;
    }

    /** Returns the query mode. */
    public VectorStoreQueryMode getMode() {
        return mode;
    }

    /** Returns the query text. */
    public String getQueryText() {
        return queryText;
    }

    /** Returns the requested result limit. */
    public Integer getLimit() {
        return limit;
    }

    /** Returns extra store-specific arguments. */
    public Map<String, Object> getExtraArgs() {
        return extraArgs;
    }

    @Nullable
    public String getCollection() {
        return collection;
    }
}
