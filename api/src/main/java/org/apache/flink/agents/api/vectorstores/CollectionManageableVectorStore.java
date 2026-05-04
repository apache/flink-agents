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

import java.util.Map;

/** Base interface for vector store which supports collection management. */
public interface CollectionManageableVectorStore {

    /**
     * Create the collection if it doesn't already exist; no-op otherwise.
     *
     * @param name Name of the collection.
     * @param kwargs Backend-specific options applied only when the collection is created (e.g.
     *     Chroma's {@code metadata} dict, Pinecone's {@code dimension} / {@code metric}).
     *     Implementations should document which keys they recognize; unknown keys are ignored.
     */
    void createCollectionIfNotExists(String name, Map<String, Object> kwargs) throws Exception;

    /**
     * Delete a collection.
     *
     * @param name Name of the collection.
     */
    void deleteCollection(String name) throws Exception;
}
