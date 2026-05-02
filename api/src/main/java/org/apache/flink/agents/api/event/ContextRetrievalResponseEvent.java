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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.vectorstores.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Event representing retrieved context results. */
public class ContextRetrievalResponseEvent extends Event {

    public static final String EVENT_TYPE = "_context_retrieval_response_event";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ContextRetrievalResponseEvent(UUID requestId, String query, List<Document> documents) {
        super(EVENT_TYPE);
        setAttr("request_id", requestId);
        setAttr("query", query);
        setAttr("documents", documents);
    }

    /**
     * Reconstructs a typed ContextRetrievalResponseEvent from a base Event, deserializing nested
     * types.
     *
     * @param event the base event containing context retrieval response data in attributes
     * @return a typed ContextRetrievalResponseEvent
     */
    @SuppressWarnings("unchecked")
    public static ContextRetrievalResponseEvent fromEvent(Event event) {
        Object rawId = event.getAttr("request_id");
        UUID requestId = rawId instanceof String ? UUID.fromString((String) rawId) : (UUID) rawId;
        String query = (String) event.getAttr("query");

        List<?> rawDocs = (List<?>) event.getAttr("documents");
        List<Document> documents = new ArrayList<>();
        if (rawDocs != null) {
            for (Object d : rawDocs) {
                if (d instanceof Document) {
                    documents.add((Document) d);
                } else if (d instanceof Map) {
                    documents.add(MAPPER.convertValue(d, Document.class));
                }
            }
        }
        return new ContextRetrievalResponseEvent(requestId, query, documents);
    }

    @JsonIgnore
    public UUID getRequestId() {
        Object val = getAttr("request_id");
        if (val instanceof String) {
            return UUID.fromString((String) val);
        }
        return (UUID) val;
    }

    @JsonIgnore
    public String getQuery() {
        return (String) getAttr("query");
    }

    @JsonIgnore
    @SuppressWarnings("unchecked")
    public List<Document> getDocuments() {
        return (List<Document>) getAttr("documents");
    }

    @Override
    public String toString() {
        return "ContextRetrievalResponseEvent{"
                + "requestId="
                + getRequestId()
                + ", query='"
                + getQuery()
                + '\''
                + ", documents="
                + getDocuments()
                + '}';
    }
}
