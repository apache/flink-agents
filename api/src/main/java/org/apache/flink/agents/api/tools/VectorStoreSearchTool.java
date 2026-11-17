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

package org.apache.flink.agents.api.tools;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.vectorstores.BaseVectorStore;
import org.apache.flink.agents.api.vectorstores.Document;
import org.apache.flink.agents.api.vectorstores.VectorStoreQuery;

import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Search a vector store declared in the same agent.
 *
 * <p>The model passes only {@code query}. The tool returns a JSON list of {@code {"content": ...,
 * "metadata": ...}} entries, or {@code "No relevant documents found."}. Errors from the vector
 * store are thrown, not returned.
 *
 * <p>Register the tool under the same name as its {@code name}:
 *
 * <pre>{@code
 * agentsEnv.addResource(
 *         "search_flink_docs",
 *         ResourceType.TOOL,
 *         VectorStoreSearchTool.builder()
 *                 .name("search_flink_docs")
 *                 .description("Search the Flink documentation.")
 *                 .vectorStore("knowledge_base")
 *                 .maxResults(3)
 *                 .build());
 * }</pre>
 */
@JsonIgnoreProperties({"resourceContext", "toolType"})
public class VectorStoreSearchTool extends Tool {

    static final String NO_RESULTS_MESSAGE = "No relevant documents found.";
    static final int DEFAULT_MAX_RESULTS = 3;
    static final String QUERY_PARAMETER = "query";

    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\","
                    + "\"properties\":{"
                    + "\"query\":{\"type\":\"string\",\"description\":"
                    + "\"Natural-language query describing the information to find.\"}"
                    + "},"
                    + "\"required\":[\"query\"]}";

    /**
     * Writes search results. Besides Jackson's defaults (NaN and infinities as strings, byte arrays
     * as Base64), it writes {@code java.time} values and {@code Date} as ISO-8601 strings and
     * property-less objects as {@code {}}, so that one unusual metadata value cannot fail the whole
     * response.
     */
    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .registerModule(
                            new SimpleModule()
                                    .addSerializer(
                                            TemporalAccessor.class, ToStringSerializer.instance))
                    .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final String vectorStore;
    private final int maxResults;

    /** Resolved in {@link #open()}; calls may run concurrently in async workers. */
    @JsonIgnore private transient volatile BaseVectorStore resolvedStore;

    @JsonCreator
    private VectorStoreSearchTool(
            @JsonProperty("metadata") ToolMetadata metadata,
            @JsonProperty("vectorStore") String vectorStore,
            @JsonProperty("maxResults") Integer maxResults) {
        super(metadata);
        this.vectorStore =
                Objects.requireNonNull(vectorStore, "VectorStoreSearchTool requires vectorStore.");
        int limit = maxResults == null ? DEFAULT_MAX_RESULTS : maxResults;
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "VectorStoreSearchTool requires a positive maxResults, got " + limit + ".");
        }
        this.maxResults = limit;
    }

    /** Create a builder for {@link VectorStoreSearchTool}. */
    public static Builder builder() {
        return new Builder();
    }

    /** Name of the vector store resource to search. */
    public String getVectorStore() {
        return vectorStore;
    }

    /** Maximum number of documents returned per call. */
    public int getMaxResults() {
        return maxResults;
    }

    @Override
    public ToolType getToolType() {
        return ToolType.FUNCTION;
    }

    @Override
    public void open() throws Exception {
        if (resourceContext == null) {
            throw new IllegalStateException("VectorStoreSearchTool requires a ResourceContext.");
        }
        Resource store = resourceContext.getResource(vectorStore, ResourceType.VECTOR_STORE);
        if (!(store instanceof BaseVectorStore)) {
            throw new IllegalArgumentException(
                    "Resource '" + vectorStore + "' must be a vector store.");
        }
        resolvedStore = (BaseVectorStore) store;
    }

    @Override
    public ToolResponse call(ToolParameters parameters) {
        Object query = parameters.getParameter(QUERY_PARAMETER);
        if (!(query instanceof String)) {
            throw new IllegalArgumentException(
                    "VectorStoreSearchTool requires a string 'query' argument.");
        }
        BaseVectorStore store = resolvedStore;
        if (store == null) {
            throw new IllegalStateException(
                    "Vector store '"
                            + vectorStore
                            + "' has not been resolved. "
                            + "Ensure open() is called before calling the tool.");
        }
        List<Document> documents =
                store.query(new VectorStoreQuery((String) query, maxResults)).getDocuments();
        if (documents == null || documents.isEmpty()) {
            return ToolResponse.text(NO_RESULTS_MESSAGE);
        }
        List<Map<String, Object>> results = new ArrayList<>(documents.size());
        for (Document document : documents) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("content", document.getContent());
            entry.put("metadata", document.getMetadata());
            results.add(entry);
        }
        try {
            return ToolResponse.text(MAPPER.writeValueAsString(results));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize search results.", e);
        }
    }

    /** Builder for {@link VectorStoreSearchTool}. */
    public static final class Builder {
        private String name;
        private String description;
        private String vectorStore;
        private int maxResults = DEFAULT_MAX_RESULTS;

        private Builder() {}

        /** Tool name shown to the model; must match the name the tool is registered under. */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /** Tool description shown to the model; it decides when the model searches. */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /** Name of the vector store resource to search. */
        public Builder vectorStore(String vectorStore) {
            this.vectorStore = vectorStore;
            return this;
        }

        /** Maximum number of documents returned per call. Defaults to 3; must be positive. */
        public Builder maxResults(int maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        public VectorStoreSearchTool build() {
            Objects.requireNonNull(name, "VectorStoreSearchTool requires name.");
            Objects.requireNonNull(description, "VectorStoreSearchTool requires description.");
            return new VectorStoreSearchTool(
                    new ToolMetadata(name, description, INPUT_SCHEMA), vectorStore, maxResults);
        }
    }
}
