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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Child memory store that reads through to the parent's unpersisted cache but keeps writes to
 * itself. {@link #persistCache()} discards child writes with a warning — cross-scope persist
 * behavior is not yet designed.
 */
public class IsolatedCachedMemoryStore extends CachedMemoryStore {

    private static final Logger LOG = LoggerFactory.getLogger(IsolatedCachedMemoryStore.class);

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
        return parent.get(key);
    }

    @Override
    public void put(String key, MemoryObjectImpl.MemoryItem value) throws Exception {
        ownCache.put(key, value);
    }

    @Override
    public boolean contains(String key) throws Exception {
        return ownCache.containsKey(key) || parent.contains(key);
    }

    // TODO: design cross-scope memory persist behavior
    @Override
    public void persistCache() throws Exception {
        if (!ownCache.isEmpty()) {
            LOG.warn(
                    "Subagent memory persist not yet supported; discarding {} cached entries.",
                    ownCache.size());
            ownCache.clear();
        }
    }

    @Override
    public void clear() throws Exception {
        ownCache.clear();
    }
}
