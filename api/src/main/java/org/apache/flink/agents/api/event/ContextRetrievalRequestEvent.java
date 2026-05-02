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

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.flink.agents.api.Event;

/** Event representing a request for context retrieval. */
public class ContextRetrievalRequestEvent extends Event {

    public static final String EVENT_TYPE = "_context_retrieval_request_event";

    private static final int DEFAULT_MAX_RESULTS = 3;

    public ContextRetrievalRequestEvent(String query, String vectorStore) {
        this(query, vectorStore, DEFAULT_MAX_RESULTS);
    }

    public ContextRetrievalRequestEvent(String query, String vectorStore, int maxResults) {
        super(EVENT_TYPE);
        setAttr("query", query);
        setAttr("vector_store", vectorStore);
        setAttr("max_results", maxResults);
    }

    /**
     * Reconstructs a typed ContextRetrievalRequestEvent from a base Event.
     *
     * @param event the base event containing context retrieval request data in attributes
     * @return a typed ContextRetrievalRequestEvent
     */
    public static ContextRetrievalRequestEvent fromEvent(Event event) {
        String query = (String) event.getAttr("query");
        String vectorStore = (String) event.getAttr("vector_store");
        int maxResults = ((Number) event.getAttr("max_results")).intValue();
        return new ContextRetrievalRequestEvent(query, vectorStore, maxResults);
    }

    @JsonIgnore
    public String getQuery() {
        return (String) getAttr("query");
    }

    @JsonIgnore
    public String getVectorStore() {
        return (String) getAttr("vector_store");
    }

    @JsonIgnore
    public int getMaxResults() {
        return ((Number) getAttr("max_results")).intValue();
    }

    @Override
    public String toString() {
        return "ContextRetrievalRequestEvent{"
                + "query='"
                + getQuery()
                + '\''
                + ", vectorStore='"
                + getVectorStore()
                + '\''
                + ", maxResults="
                + getMaxResults()
                + '}';
    }
}
