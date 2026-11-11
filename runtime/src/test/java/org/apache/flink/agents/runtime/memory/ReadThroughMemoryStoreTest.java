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

import org.apache.flink.agents.api.context.MemoryObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReadThroughMemoryStoreTest {
    @Test
    void readsPersistedAndUncommittedValuesWithoutFlushingOrChangingParent() throws Exception {
        ForTestMemoryMapState<MemoryObjectImpl.MemoryItem> state = new ForTestMemoryMapState<>();
        CachedMemoryStore parentStore = new CachedMemoryStore(state);
        MemoryObject parent = memory(parentStore);
        parent.set("counter", 0);
        parentStore.persistCache();
        parent.set("counter", 1);
        parent.set("request.tenant", "tenant");

        ReadThroughMemoryStore childStore = new ReadThroughMemoryStore(parentStore);
        MemoryObject child = memory(childStore);
        assertThat(child.get("counter").getValue()).isEqualTo(1);
        assertThat(child.get("request.tenant").getValue()).isEqualTo("tenant");
        child.set("counter", 9);
        child.set("request.internal", true);
        childStore.persistCache();

        assertThat(child.get("counter").getValue()).isEqualTo(9);
        assertThat(parent.get("counter").getValue()).isEqualTo(1);
        assertThat(parent.get("request").getFields()).containsOnlyKeys("tenant");
        assertThat(state.get("counter").getValue()).isEqualTo(0);
        assertThat(state.contains("request.tenant")).isFalse();

        ReadThroughMemoryStore sibling = new ReadThroughMemoryStore(parentStore);
        assertThat(memory(sibling).get("counter").getValue()).isEqualTo(1);
        assertThat(memory(sibling).isExist("request.internal")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void isolatesMutableValuesBeforeTheyCanBeChangedWithoutSet() throws Exception {
        CachedMemoryStore parentStore = new CachedMemoryStore(new ForTestMemoryMapState<>());
        MemoryObject parent = memory(parentStore);
        Map<String, Object> payload = new HashMap<>();
        payload.put("items", new ArrayList<>(List.of("parent")));
        payload.put("bytes", new byte[] {1});
        parent.set("payload", payload);
        ReadThroughMemoryStore childStore = new ReadThroughMemoryStore(parentStore);
        MemoryObject child = memory(childStore);

        Map<String, Object> copy = (Map<String, Object>) child.get("payload").getValue();
        ((List<String>) copy.get("items")).add("child");
        ((byte[]) copy.get("bytes"))[0] = 2;
        childStore.persistCache();
        assertThat((List<String>) payload.get("items")).containsExactly("parent");
        assertThat((byte[]) payload.get("bytes")).containsExactly((byte) 1);
        assertThat(child.get("payload").getValue()).isSameAs(copy);
    }

    @Test
    void clearHidesParentAndRetainsNewLocalWrites() throws Exception {
        CachedMemoryStore parentStore = new CachedMemoryStore(new ForTestMemoryMapState<>());
        MemoryObject parent = memory(parentStore);
        parent.set("parent", 1);
        ReadThroughMemoryStore childStore = new ReadThroughMemoryStore(parentStore);
        MemoryObject child = memory(childStore);
        assertThat(child.get("parent").getValue()).isEqualTo(1);
        childStore.clear();
        child = memory(childStore);
        assertThat(child.isExist("parent")).isFalse();
        assertThat(child.get("parent")).isNull();
        child.set("local", 2);
        childStore.persistCache();
        assertThat(child.getFields()).containsOnlyKeys("local");
        assertThat(parent.getFields()).containsOnlyKeys("parent");
    }

    private static MemoryObject memory(MemoryStore store) throws Exception {
        return new MemoryObjectImpl(
                MemoryObject.MemoryType.SHORT_TERM,
                store,
                MemoryObjectImpl.ROOT_KEY,
                new ArrayList<>());
    }
}
