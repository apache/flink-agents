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
package org.apache.flink.agents.runtime.memory;

import org.apache.flink.agents.api.memory.MemorySet;
import org.apache.flink.agents.api.memory.MemorySetItem;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import pemja.core.object.PyObject;

import javax.annotation.Nullable;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Java-side thin wrapper around the Python {@code Mem0LongTermMemory} instance. All public methods
 * forward to Python via {@link PythonResourceAdapter#callMethod}; Python-only return types ({@code
 * MemorySetItem}) are converted into Java POJOs by the {@code mem0_items_to_java} helper in {@code
 * python_java_utils.py}.
 */
public class Mem0LongTermMemory implements InteranlBaseLongTermMemory {

    private static final String TO_PYTHON_MEMORY_SET = "python_java_utils.to_python_memory_set";
    private static final String MEM0_ITEMS_TO_JAVA = "python_java_utils.mem0_items_to_java";

    private final PythonResourceAdapter adapter;
    private final PyObject pyMem0;

    public Mem0LongTermMemory(PythonResourceAdapter adapter, PyObject pyMem0) {
        this.adapter = adapter;
        this.pyMem0 = pyMem0;
    }

    @Override
    public MemorySet getMemorySet(String name) {
        // Mirrors Python's `Mem0LongTermMemory.get_memory_set`: a pure factory that
        // returns a new MemorySet bound to this ltm; no Python call is needed.
        MemorySet ms = new MemorySet(name);
        ms.setLtm(this);
        return ms;
    }

    @Override
    public boolean deleteMemorySet(String name) {
        return (Boolean) adapter.callMethod(pyMem0, "delete_memory_set", Map.of("name", name));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> add(
            MemorySet memorySet,
            List<String> memoryItems,
            @Nullable List<Map<String, Object>> metadatas) {
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("memory_set", buildPyMemorySet(memorySet));
        kwargs.put("memory_items", memoryItems);
        if (metadatas != null) {
            kwargs.put("metadatas", metadatas);
        }
        return (List<String>) adapter.callMethod(pyMem0, "add", kwargs);
    }

    @Override
    public List<MemorySetItem> get(
            MemorySet memorySet,
            @Nullable List<String> ids,
            @Nullable Map<String, Object> filters,
            @Nullable Integer limit) {
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("memory_set", buildPyMemorySet(memorySet));
        if (ids != null) {
            kwargs.put("ids", ids);
        }
        if (filters != null) {
            kwargs.put("filters", filters);
        }
        if (limit != null) {
            kwargs.put("limit", limit);
        }
        Object pyItems = adapter.callMethod(pyMem0, "get", kwargs);
        return convertItems(pyItems);
    }

    @Override
    public void delete(MemorySet memorySet, @Nullable List<String> ids) {
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("memory_set", buildPyMemorySet(memorySet));
        if (ids != null) {
            kwargs.put("ids", ids);
        }
        adapter.callMethod(pyMem0, "delete", kwargs);
    }

    @Override
    public List<MemorySetItem> search(
            MemorySet memorySet,
            String query,
            int limit,
            @Nullable Map<String, Object> filters,
            Map<String, Object> extraArgs) {
        Map<String, Object> kwargs = new HashMap<>(extraArgs);
        kwargs.put("memory_set", buildPyMemorySet(memorySet));
        kwargs.put("query", query);
        kwargs.put("limit", limit);
        if (filters != null) {
            kwargs.put("filters", filters);
        }
        Object pyItems = adapter.callMethod(pyMem0, "search", kwargs);
        return convertItems(pyItems);
    }

    @Override
    public void switchContext(String key) {
        adapter.callMethod(pyMem0, "switch_context", Map.of("key", key));
    }

    @Override
    public void close() {
        adapter.callMethod(pyMem0, "close", Map.of());
    }

    private Object buildPyMemorySet(MemorySet memorySet) {
        return adapter.invoke(TO_PYTHON_MEMORY_SET, memorySet.getName());
    }

    @SuppressWarnings("unchecked")
    private List<MemorySetItem> convertItems(Object pyItems) {
        Object converted = adapter.invoke(MEM0_ITEMS_TO_JAVA, pyItems);
        List<Map<String, Object>> dicts =
                converted == null ? List.of() : (List<Map<String, Object>>) converted;
        List<MemorySetItem> items = new ArrayList<>(dicts.size());
        for (Map<String, Object> dict : dicts) {
            items.add(dictToItem(dict));
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private MemorySetItem dictToItem(Map<String, Object> dict) {
        return new MemorySetItem(
                (String) dict.get("memory_set_name"),
                (String) dict.get("id"),
                (String) dict.get("value"),
                parseTimestamp(dict.get("created_at")),
                parseTimestamp(dict.get("updated_at")),
                (Map<String, Object>) dict.get("additional_metadata"));
    }

    private static @Nullable LocalDateTime parseTimestamp(@Nullable Object iso) {
        if (iso == null) {
            return null;
        }
        String s = (String) iso;
        try {
            // mem0's ISO 8601 timestamps include a UTC offset (e.g. "...+00:00").
            return OffsetDateTime.parse(s).toLocalDateTime();
        } catch (DateTimeParseException e) {
            // Fall back to parsing offset-less timestamps for backends that omit them.
            return LocalDateTime.parse(s);
        }
    }
}
