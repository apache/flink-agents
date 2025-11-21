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
 *   <li>{@link #KEYWORD}: Use keyword or lexical search when supported by the store.
 *   <li>{@link #HYBRID}: Combine semantic and keyword search strategies.
 * </ul>
 */
public enum VectorStoreQueryMode {
    /** Semantic similarity search using embeddings. */
    SEMANTIC,
    /** Keyword/lexical search (store dependent). */
    KEYWORD,
    /** Hybrid search combining semantic and keyword results. */
    HYBRID;
}
