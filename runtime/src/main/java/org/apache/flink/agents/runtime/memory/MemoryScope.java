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

/**
 * The sensory/short-term store pair one scoped run reads and writes through.
 *
 * <p>Carrying the stores rather than a whole memory context keeps the per-action update and
 * observation journal out of the scope: the journal belongs to whichever action is executing, while
 * the stores are what a scope shares across its actions.
 */
public final class MemoryScope {
    private final CachedMemoryStore sensoryMemStore;
    private final CachedMemoryStore shortTermMemStore;

    public MemoryScope(CachedMemoryStore sensoryMemStore, CachedMemoryStore shortTermMemStore) {
        this.sensoryMemStore = sensoryMemStore;
        this.shortTermMemStore = shortTermMemStore;
    }

    /**
     * Self-contained stores: nothing is seeded from a parent view and writes never reach durable
     * state. This is the internal sub-agent's policy.
     */
    public static MemoryScope isolated() {
        return new MemoryScope(new IsolatedCachedMemoryStore(), new IsolatedCachedMemoryStore());
    }

    public CachedMemoryStore getSensoryMemStore() {
        return sensoryMemStore;
    }

    public CachedMemoryStore getShortTermMemStore() {
        return shortTermMemStore;
    }
}
