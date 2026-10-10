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
 * Ephemeral memory store backing one sub-agent call's isolated memory view. An internal sub-agent
 * does not share memory with its caller: reads and writes stay within this store, so the caller's
 * persisted memory is never visible to the child and the child's writes never reach durable state.
 * {@link #persistCache()} is a no-op that keeps the call's writes in memory for the whole call, so
 * every action of that call reads a consistent view until the owning call status releases it.
 */
public class IsolatedCachedMemoryStore extends CachedMemoryStore {

    private final Map<String, MemoryObjectImpl.MemoryItem> ownCache = new HashMap<>();

    public IsolatedCachedMemoryStore() {
        super(null);
    }

    @Override
    public MemoryObjectImpl.MemoryItem get(String key) throws Exception {
        // No read-through: an internal sub-agent sees only what its own call has written.
        return ownCache.get(key);
    }

    @Override
    public void put(String key, MemoryObjectImpl.MemoryItem value) throws Exception {
        ownCache.put(key, value);
    }

    @Override
    public boolean contains(String key) throws Exception {
        return ownCache.containsKey(key);
    }

    /**
     * Retains the call's writes for the lifetime of the sub-agent call. The isolated view belongs
     * to the call, not to any single action, and has no durable state behind it, so there is
     * nothing to flush; clearing here would hide one action's writes from the next action of the
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
