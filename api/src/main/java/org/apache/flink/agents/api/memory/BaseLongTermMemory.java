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

import org.apache.flink.agents.api.memory.compaction.CompactionConfig;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Base interface for long-term memory management. It provides operations to create, retrieve,
 * delete, and search memory sets, which are collections of memory items. A memory set can store
 * items of a specific type (e.g., String or ChatMessage) and has a capacity limit. When the
 * capacity is exceeded, compaction will be applied to manage the memory set size.
 */
public interface BaseLongTermMemory extends AutoCloseable {

    /**
     * Gets an existing memory set or creates a new one if it doesn't exist.
     *
     * @param name the name of the memory set
     * @param itemType the type of items stored in the memory set
     * @param capacity the maximum number of items the memory set can hold
     * @param compactionConfig the compaction config to use when the capacity is exceeded
     * @return the existing or newly created memory set
     * @throws Exception if the memory set cannot be created or retrieved
     */
    MemorySet getOrCreateMemorySet(
            String name, Class<?> itemType, int capacity, CompactionConfig compactionConfig)
            throws Exception;

    /**
     * Gets an existing memory set by name.
     *
     * @param name the name of the memory set to retrieve
     * @return the memory set with the given name
     * @throws Exception if the memory set does not exist or cannot be retrieved
     */
    MemorySet getMemorySet(String name) throws Exception;

    /**
     * Deletes a memory set by name.
     *
     * @param name the name of the memory set to delete
     * @return true if the memory set was successfully deleted, false if it didn't exist
     * @throws Exception if the deletion operation fails
     */
    boolean deleteMemorySet(String name) throws Exception;

    /**
     * Gets the number of items in the memory set.
     *
     * @param memorySet the memory set to count items in
     * @return the number of items in the memory set
     * @throws Exception if the size cannot be determined
     */
    long size(MemorySet memorySet) throws Exception;

    /**
     * Adds items to the memory set. If IDs are not provided, they will be automatically generated.
     * This method may trigger compaction if the memory set capacity is exceeded.
     *
     * @param memorySet the memory set to add items to
     * @param memoryItems the items to be added to the memory set
     * @param ids optional list of IDs for the items. If null or shorter than memoryItems, IDs will
     *     be auto-generated for missing items
     * @param metadatas optional list of metadata maps for the items. Each metadata map corresponds
     *     to an item at the same index
     * @return list of IDs of the added items
     * @throws Exception if items cannot be added to the memory set
     */
    List<String> add(
            MemorySet memorySet,
            List<?> memoryItems,
            @Nullable List<String> ids,
            @Nullable List<Map<String, Object>> metadatas)
            throws Exception;

    /**
     * Retrieves memory items from the memory set. If no IDs are provided, all items in the memory
     * set are returned.
     *
     * @param memorySet the memory set to retrieve items from
     * @param ids optional list of item IDs to retrieve. If null, all items are returned
     * @return list of memory set items. If ids is provided, returns items matching those IDs. If
     *     ids is null, returns all items in the memory set
     * @throws Exception if items cannot be retrieved from the memory set
     */
    List<MemorySetItem> get(MemorySet memorySet, @Nullable List<String> ids) throws Exception;

    /**
     * Deletes memory items from the memory set. If no IDs are provided, all items in the memory set
     * are deleted.
     *
     * @param memorySet the memory set to delete items from
     * @param ids optional list of item IDs to delete. If null, all items in the memory set are
     *     deleted
     * @throws Exception if items cannot be deleted from the memory set
     */
    void delete(MemorySet memorySet, @Nullable List<String> ids) throws Exception;

    /**
     * Performs semantic search on the memory set to find items related to the query string.
     *
     * @param memorySet the memory set to search in
     * @param query the query string for semantic search
     * @param limit the maximum number of items to return
     * @param extraArgs additional arguments for the search operation (e.g., filters, distance
     *     metrics)
     * @return list of memory set items that are most relevant to the query, ordered by relevance
     * @throws Exception if the search operation fails
     */
    List<MemorySetItem> search(
            MemorySet memorySet, String query, int limit, Map<String, Object> extraArgs)
            throws Exception;
}
