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

import org.apache.flink.agents.runtime.memory.MemoryObjectImpl.MemoryItem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CachedMemoryStoreTest {

    @Test
    void testPutAndGet() throws Exception {
        ForTestMemoryMapState<MemoryItem> store = new ForTestMemoryMapState<>();
        CachedMemoryStore cachedStore = new CachedMemoryStore(store);

        MemoryItem v1 = new MemoryItem();
        MemoryItem v2 = new MemoryItem(10);
        cachedStore.put("k1", v1);
        cachedStore.put("k2", v2);

        assertThat(cachedStore.get("k1")).isEqualTo(v1);
        assertThat(cachedStore.get("k2")).isEqualTo(v2);
    }

    @Test
    void testPutToCache() throws Exception {
        ForTestMemoryMapState<MemoryItem> store = new ForTestMemoryMapState<>();
        MemoryItem v1 = new MemoryItem();
        MemoryItem v2 = new MemoryItem();
        store.put("k1", v1);
        store.put("k2", v2);

        CachedMemoryStore cachedStore = new CachedMemoryStore(store);

        MemoryItem v11 = new MemoryItem();
        cachedStore.put("k1", v11);

        assertThat(cachedStore.get("k1")).isEqualTo(v11);
        assertThat(cachedStore.get("k2")).isEqualTo(v2);
    }

    @Test
    void testContains() throws Exception {
        ForTestMemoryMapState<MemoryItem> store = new ForTestMemoryMapState<>();
        MemoryItem v1 = new MemoryItem();
        store.put("k1", v1);

        CachedMemoryStore cachedStore = new CachedMemoryStore(store);
        assertThat(cachedStore.contains("k1")).isTrue();
        assertThat(cachedStore.contains("k2")).isFalse();

        MemoryItem v2 = new MemoryItem();
        cachedStore.put("k2", v2);
        assertThat(cachedStore.contains("k2")).isTrue();
    }

    @Test
    void testPersistCache() throws Exception {
        ForTestMemoryMapState<MemoryItem> store = new ForTestMemoryMapState<>();
        MemoryItem v1 = new MemoryItem();
        MemoryItem v2 = new MemoryItem(10);
        store.put("k1", v1);
        store.put("k2", v2);

        CachedMemoryStore cachedStore = new CachedMemoryStore(store);
        MemoryItem v11 = new MemoryItem();
        cachedStore.put("k1", v11);

        cachedStore.persistCache();

        assertThat(store.get("k1")).isEqualTo(v11);
        assertThat(store.get("k2")).isEqualTo(v2);
    }
}
