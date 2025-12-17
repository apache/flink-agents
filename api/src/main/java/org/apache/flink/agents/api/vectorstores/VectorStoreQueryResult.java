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

import java.util.List;

/**
 * Result of a vector store query.
 *
 * <p>Wraps the list of {@link Document} instances returned by a {@link BaseVectorStore} query in a
 * simple value object.
 */
public class VectorStoreQueryResult {
    /** The documents matched by the query, ordered by relevance. */
    private final List<Document> documents;

    /**
     * Creates a new query result.
     *
     * @param documents the matched documents
     */
    public VectorStoreQueryResult(List<Document> documents) {
        this.documents = documents;
    }

    /** Returns the matched documents. */
    public List<Document> getDocuments() {
        return documents;
    }
}
