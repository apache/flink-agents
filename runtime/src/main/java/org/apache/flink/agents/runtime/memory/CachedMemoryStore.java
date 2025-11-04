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

import org.apache.flink.api.common.state.MapState;

import java.util.HashMap;
import java.util.Map;

public class CachedMemoryStore implements MemoryStore {

    private final Map<String, MemoryObjectImpl.MemoryItem> cache;
    private final MapState<String, MemoryObjectImpl.MemoryItem> store;

    public CachedMemoryStore(MapState<String, MemoryObjectImpl.MemoryItem> store) {
        this.store = store;
        this.cache = new HashMap<>();
    }

    @Override
    public MemoryObjectImpl.MemoryItem get(String key) throws Exception {
        if (cache.containsKey(key)) {
            return cache.get(key);
        }

        return store.get(key);
    }

    @Override
    public void put(String key, MemoryObjectImpl.MemoryItem value) throws Exception {
        cache.put(key, value);
    }

    @Override
    public boolean contains(String key) throws Exception {
        return cache.containsKey(key) || store.contains(key);
    }

    public void persistCache() throws Exception {
        for (Map.Entry<String, MemoryObjectImpl.MemoryItem> entry : cache.entrySet()) {
            store.put(entry.getKey(), entry.getValue());
        }
        cache.clear();
    }

    public void clear() throws Exception {
        cache.clear();
        store.clear();
    }
}
