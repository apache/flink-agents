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
import org.apache.flink.agents.api.context.MemoryUpdate;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link IsolatedCachedMemoryStore}: an internal sub-agent's memory is self-contained and
 * ephemeral. It neither reads through to a caller's memory nor flushes its writes to durable state;
 * the end-to-end no-sharing guarantee is locked by {@code
 * InternalSubagentCallTest#subAgentDoesNotReadCallersPersistedMemory}.
 */
public class IsolatedCachedMemoryStoreTest {

    private static final MemoryObject.MemoryType TYPE = MemoryObject.MemoryType.SHORT_TERM;

    private static MemoryObjectImpl newMemoryObject(MemoryStore store, List<MemoryUpdate> updates)
            throws Exception {
        return new MemoryObjectImpl(TYPE, store, MemoryObjectImpl.ROOT_KEY, updates);
    }

    /** The store reads back only what its own call wrote; a key it never wrote is absent. */
    @Test
    void readsAndWritesStayWithinTheStore() throws Exception {
        IsolatedCachedMemoryStore store = new IsolatedCachedMemoryStore();
        MemoryObjectImpl child = newMemoryObject(store, new LinkedList<>());
        child.set("c", 2);

        assertThat(child.isExist("c")).isTrue();
        assertThat(child.get("c").getValue()).isEqualTo(2);
        assertThat(child.isExist("never-written")).isFalse();
    }

    /**
     * {@link IsolatedCachedMemoryStore#persistCache()} is a no-op: the call's writes stay readable
     * for the whole call and are never flushed to durable state.
     */
    @Test
    void persistCacheRetainsWritesForTheWholeCall() throws Exception {
        IsolatedCachedMemoryStore store = new IsolatedCachedMemoryStore();
        MemoryObjectImpl child = newMemoryObject(store, new LinkedList<>());
        child.set("c", 2);

        store.persistCache();

        assertThat(store.contains("c")).isTrue();
        assertThat(child.get("c").getValue()).isEqualTo(2);
    }

    /** {@link IsolatedCachedMemoryStore#clear()} drops the call's writes. */
    @Test
    void clearEmptiesTheStore() throws Exception {
        IsolatedCachedMemoryStore store = new IsolatedCachedMemoryStore();
        MemoryObjectImpl child = newMemoryObject(store, new LinkedList<>());
        child.set("c", 2);

        store.clear();

        assertThat(store.contains("c")).isFalse();
    }
}
