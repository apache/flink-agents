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

import java.util.HashMap;
import java.util.Map;

/**
 * Child memory store that reads through to the parent's unpersisted cache but keeps writes to
 * itself. The store backs one sub-agent call's isolated memory view: {@link #persistCache()} keeps
 * the child's writes in memory for the whole call so every action of that call reads a consistent
 * view, and never flushes them into the parent's durable state.
 */
public class IsolatedCachedMemoryStore extends CachedMemoryStore {

    private final CachedMemoryStore parent;
    private final Map<String, MemoryObjectImpl.MemoryItem> ownCache = new HashMap<>();

    public IsolatedCachedMemoryStore(CachedMemoryStore parent) {
        super(null);
        this.parent = parent;
    }

    @Override
    public MemoryObjectImpl.MemoryItem get(String key) throws Exception {
        if (ownCache.containsKey(key)) {
            return ownCache.get(key);
        }
        MemoryObjectImpl.MemoryItem parentItem = parent.get(key);
        // Copy on read so this scope owns the item it exposes: MemoryObjectImpl.set() applies its
        // field-list updates to the resolved item in place, and the copy confines those updates to
        // the child scope, leaving the parent's item unchanged.
        return parentItem == null ? null : new MemoryObjectImpl.MemoryItem(parentItem);
    }

    @Override
    public void put(String key, MemoryObjectImpl.MemoryItem value) throws Exception {
        ownCache.put(key, value);
    }

    @Override
    public boolean contains(String key) throws Exception {
        return ownCache.containsKey(key) || parent.contains(key);
    }

    /**
     * Retains the child's writes for the lifetime of the sub-agent call. The isolated view belongs
     * to the call, not to any single action: flushing it into the parent would leak child writes
     * into the caller, and clearing it would hide one action's writes from the next action of the
     * same call. The owning call status releases the view when the record finishes.
     */
    @Override
    public void persistCache() throws Exception {
        // No-op: the call's isolated writes stay in ownCache until the call status is dropped.
    }

    @Override
    public void clear() throws Exception {
        ownCache.clear();
    }
}
