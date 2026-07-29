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

import javax.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a long term memory set, a named collection of memory items. Acts as a thin proxy that
 * delegates all operations to the bound {@link BaseLongTermMemory}.
 */
public class MemorySet {
    private final String name;
    private @JsonIgnore BaseLongTermMemory ltm;

    @JsonCreator
    public MemorySet(@JsonProperty("name") String name) {
        this.name = name;
    }

    /**
     * Adds items to this memory set. The backend may auto-generate IDs.
     *
     * @param memoryItems the items to add
     * @param metadatas optional list of metadata maps, one per item
     * @return list of IDs of the added items
     */
    public List<String> add(List<String> memoryItems, @Nullable List<Map<String, Object>> metadatas)
            throws Exception {
        return this.ltm.add(this, memoryItems, metadatas);
    }

    /**
     * Retrieves memory items. When {@code ids} is provided, {@code filters} and {@code limit} are
     * ignored.
     *
     * @param ids optional list of item IDs to retrieve
     * @param filters optional metadata filters
     * @param limit maximum number of items to return
     * @return list of matching memory items
     */
    public List<MemorySetItem> get(
            @Nullable List<String> ids,
            @Nullable Map<String, Object> filters,
            @Nullable Integer limit)
            throws Exception {
        return this.ltm.get(this, ids, filters, limit);
    }

    /**
     * Performs semantic search on this memory set.
     *
     * @param query the query string for semantic search
     * @param limit maximum number of items to return
     * @param filters optional metadata filters
     * @param extraArgs backend-specific extra arguments; pass an empty map when none are needed
     * @return list of memory items most relevant to the query, ordered by relevance
     */
    public List<MemorySetItem> search(
            String query,
            int limit,
            @Nullable Map<String, Object> filters,
            Map<String, Object> extraArgs)
            throws Exception {
        return this.ltm.search(this, query, limit, filters, extraArgs);
    }

    /**
     * Deletes memory items. If {@code ids} is null, all items in the set are deleted.
     *
     * @param ids optional list of item IDs to delete
     */
    public void delete(@Nullable List<String> ids) throws Exception {
        this.ltm.delete(this, ids);
    }

    public void setLtm(BaseLongTermMemory ltm) {
        this.ltm = ltm;
    }

    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        MemorySet memorySet = (MemorySet) o;
        return Objects.equals(name, memorySet.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "MemorySet{name='" + name + "'}";
    }
}
