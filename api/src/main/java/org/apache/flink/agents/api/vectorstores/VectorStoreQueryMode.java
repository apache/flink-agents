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

/**
 * Query execution modes for vector stores.
 *
 * <ul>
 *   <li>{@link #SEMANTIC}: Use dense vector embeddings and similarity search.
 * </ul>
 */
public enum VectorStoreQueryMode {
    /** Semantic similarity search using embeddings. */
    SEMANTIC("semantic");
    /** Keyword/lexical search (store dependent). TODO: term-based retrieval */
    //    KEYWORD("keyword"),
    /** Hybrid search combining semantic and keyword results. TODO: semantic + keyword retrieval */
    //    HYBRID("hybrid");

    private final String value;

    VectorStoreQueryMode(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * Get VectorStoreQueryMode from string value.
     *
     * @param value the string value
     * @return the corresponding ResourceType
     * @throws IllegalArgumentException if no matching ResourceType is found
     */
    public static VectorStoreQueryMode fromValue(String value) {
        for (VectorStoreQueryMode type : VectorStoreQueryMode.values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown VectorStoreQueryMode value: " + value);
    }
}
