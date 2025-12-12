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

package org.apache.flink.agents.api.event;

import org.apache.flink.agents.api.Event;

/** Event representing a request for context retrieval. */
public class ContextRetrievalRequestEvent extends Event {

    private static final int DEFAULT_MAX_RESULTS = 3;

    private final String query;
    private final String vectorStore;
    private final int maxResults;

    public ContextRetrievalRequestEvent(String query, String vectorStore) {
        this.query = query;
        this.vectorStore = vectorStore;
        this.maxResults = DEFAULT_MAX_RESULTS;
    }

    public ContextRetrievalRequestEvent(String query, String vectorStore, int maxResults) {
        this.query = query;
        this.vectorStore = vectorStore;
        this.maxResults = maxResults;
    }

    public String getQuery() {
        return query;
    }

    public String getVectorStore() {
        return vectorStore;
    }

    public int getMaxResults() {
        return maxResults;
    }

    @Override
    public String toString() {
        return "ContextRetrievalRequestEvent{"
                + "query='"
                + query
                + '\''
                + ", vectorStore='"
                + vectorStore
                + '\''
                + ", maxResults="
                + maxResults
                + '}';
    }
}
