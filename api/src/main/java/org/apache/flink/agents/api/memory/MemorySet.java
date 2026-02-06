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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.flink.agents.api.memory.compaction.CompactionConfig;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MemorySet {
    private final String name;
    private final Class<?> itemType;
    private final int capacity;
    private final CompactionConfig compactionConfig;
    private @JsonIgnore BaseLongTermMemory ltm;

    @JsonCreator
    public MemorySet(
            @JsonProperty("name") String name,
            @JsonProperty("itemType") Class<?> itemType,
            @JsonProperty("capacity") int capacity,
            @JsonProperty("compactionConfig") CompactionConfig compactionConfig) {
        this.name = name;
        this.itemType = itemType;
        this.capacity = capacity;
        this.compactionConfig = compactionConfig;
    }

    /**
     * Gets the number of items in this memory set.
     *
     * @return the number of items in the memory set
     * @throws Exception if the size cannot be determined
     */
    public long size() throws Exception {
        return this.ltm.size(this);
    }

    /**
     * Adds items to this memory set. If IDs are not provided, they will be automatically generated.
     * This method may trigger compaction if the memory set capacity is exceeded.
     *
     * @param memoryItems the items to be added to the memory set
     * @param ids optional list of IDs for the items. If null or shorter than memoryItems, IDs will
     *     be auto-generated for missing items
     * @param metadatas optional list of metadata maps for the items. Each metadata map corresponds
     *     to an item at the same index
     * @return list of IDs of the added items
     * @throws Exception if items cannot be added to the memory set
     */
    public List<String> add(
            List<?> memoryItems,
            @Nullable List<String> ids,
            @Nullable List<Map<String, Object>> metadatas)
            throws Exception {
        return this.ltm.add(this, memoryItems, ids, metadatas);
    }

    /**
     * Retrieves memory items from this memory set. If no IDs are provided, all items in the memory
     * set are returned.
     *
     * @param ids optional list of item IDs to retrieve. If null, all items are returned
     * @return list of memory set items. If ids is provided, returns items matching those IDs. If
     *     ids is null, returns all items in the memory set
     * @throws Exception if items cannot be retrieved from the memory set
     */
    public List<MemorySetItem> get(@Nullable List<String> ids) throws Exception {
        return this.ltm.get(this, ids);
    }

    /**
     * Performs semantic search on this memory set to find items related to the query string.
     *
     * @param query the query string for semantic search
     * @param limit the maximum number of items to return
     * @param extraArgs optional additional arguments for the search operation (e.g., filters,
     *     distance metrics). If null, an empty map is used
     * @return list of memory set items that are most relevant to the query, ordered by relevance
     * @throws Exception if the search operation fails
     */
    public List<MemorySetItem> search(
            String query, int limit, @Nullable Map<String, Object> extraArgs) throws Exception {
        return this.ltm.search(
                this, query, limit, extraArgs == null ? Collections.emptyMap() : extraArgs);
    }

    public void setLtm(BaseLongTermMemory ltm) {
        this.ltm = ltm;
    }

    public String getName() {
        return name;
    }

    public Class<?> getItemType() {
        return itemType;
    }

    public int getCapacity() {
        return capacity;
    }

    public CompactionConfig getCompactionConfig() {
        return compactionConfig;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        MemorySet memorySet = (MemorySet) o;
        return capacity == memorySet.capacity
                && Objects.equals(name, memorySet.name)
                && Objects.equals(itemType, memorySet.itemType)
                && Objects.equals(compactionConfig, memorySet.compactionConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, itemType, capacity, compactionConfig);
    }

    @Override
    public String toString() {
        return "MemorySet{"
                + "name='"
                + name
                + '\''
                + ", itemType="
                + itemType
                + ", capacity="
                + capacity
                + ", compactionConfig="
                + compactionConfig
                + '}';
    }
}
