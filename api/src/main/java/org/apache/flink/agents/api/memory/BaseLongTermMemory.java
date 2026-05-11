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
package org.apache.flink.agents.api.memory;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Base interface for long-term memory management. Provides operations to create, retrieve, delete,
 * and search memory sets, which are named collections of memory items.
 */
public interface BaseLongTermMemory extends AutoCloseable {

    /**
     * Gets the memory set by name. If it does not exist, the backend creates it.
     *
     * @param name the name of the memory set
     * @return the memory set
     */
    MemorySet getMemorySet(String name) throws Exception;

    /**
     * Deletes the memory set.
     *
     * @param name the name of the memory set to delete
     * @return true if the memory set was successfully deleted
     */
    boolean deleteMemorySet(String name) throws Exception;

    /**
     * Adds items to the memory set. The backend may auto-generate IDs.
     *
     * @param memorySet the memory set to add items to
     * @param memoryItems the items to add
     * @param metadatas optional list of metadata maps, one per item
     * @return list of IDs of the added items
     */
    List<String> add(
            MemorySet memorySet,
            List<String> memoryItems,
            @Nullable List<Map<String, Object>> metadatas)
            throws Exception;

    /**
     * Retrieves memory items. When {@code ids} is provided, {@code filters} and {@code limit} are
     * ignored.
     *
     * @param memorySet the memory set to retrieve from
     * @param ids optional list of item IDs to retrieve
     * @param filters optional metadata filters
     * @param limit maximum number of items to return; defaults to 100 when {@code null}
     * @return list of matching memory items
     */
    List<MemorySetItem> get(
            MemorySet memorySet,
            @Nullable List<String> ids,
            @Nullable Map<String, Object> filters,
            @Nullable Integer limit)
            throws Exception;

    /**
     * Deletes memory items. If {@code ids} is null, all items in the set are deleted.
     *
     * @param memorySet the memory set to delete items from
     * @param ids optional list of item IDs to delete
     */
    void delete(MemorySet memorySet, @Nullable List<String> ids) throws Exception;

    /**
     * Performs semantic search on the memory set.
     *
     * @param memorySet the memory set to search in
     * @param query the query string for semantic search
     * @param limit maximum number of items to return
     * @param filters optional metadata filters
     * @param extraArgs backend-specific extra arguments forwarded as keyword arguments to the
     *     underlying search call (mirrors Python's {@code **kwargs})
     * @return list of memory items most relevant to the query, ordered by relevance
     */
    List<MemorySetItem> search(
            MemorySet memorySet,
            String query,
            int limit,
            @Nullable Map<String, Object> filters,
            Map<String, Object> extraArgs)
            throws Exception;
}
