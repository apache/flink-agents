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

import org.apache.flink.api.common.serialization.SerializerConfigImpl;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Memory owned by one internal sub-agent invocation and shared by its actions. Child writes never
 * enter the parent's keyed memory. Each action uses an overlay and publishes its writes only on
 * completion. Checkpoints retain committed values; durable action replay reapplies later updates.
 */
public class IsolatedCachedMemoryStore extends CachedMemoryStore {
    private final Map<String, MemoryObjectImpl.MemoryItem> values = new HashMap<>();
    private final Set<String> writtenKeys = new HashSet<>();
    private final IsolatedCachedMemoryStore committedStore;
    private TypeSerializer<MemoryObjectImpl.MemoryItem> serializer;

    public IsolatedCachedMemoryStore() {
        this(
                null,
                TypeInformation.of(MemoryObjectImpl.MemoryItem.class)
                        .createSerializer(new SerializerConfigImpl()));
    }

    private IsolatedCachedMemoryStore(
            IsolatedCachedMemoryStore committedStore,
            TypeSerializer<MemoryObjectImpl.MemoryItem> serializer) {
        super(null);
        this.committedStore = committedStore;
        this.serializer = serializer;
    }

    /** Creates an action's uncommitted view of this invocation's memory. */
    public IsolatedCachedMemoryStore createActionStore(
            TypeSerializer<MemoryObjectImpl.MemoryItem> serializer) {
        this.serializer = serializer;
        return new IsolatedCachedMemoryStore(this, serializer);
    }

    /** Copies committed values so later actions cannot mutate an earlier checkpoint snapshot. */
    public Map<String, MemoryObjectImpl.MemoryItem> snapshot() {
        Map<String, MemoryObjectImpl.MemoryItem> snapshot = new HashMap<>();
        values.forEach((key, value) -> snapshot.put(key, serializer.copy(value)));
        return snapshot;
    }

    /** Copies an untyped call result with the same serializer used for memory values. */
    public Object copyValue(Object value) {
        return serializer.copy(new MemoryObjectImpl.MemoryItem(value)).getValue();
    }

    /** Restores values before any checkpointed child action is dispatched. */
    public void restore(Map<String, MemoryObjectImpl.MemoryItem> restoredValues) {
        values.clear();
        writtenKeys.clear();
        restoredValues.forEach((key, value) -> values.put(key, serializer.copy(value)));
    }

    /** Restores using the job's configured memory serializer. */
    public void restore(
            Map<String, MemoryObjectImpl.MemoryItem> restoredValues,
            TypeSerializer<MemoryObjectImpl.MemoryItem> serializer) {
        this.serializer = serializer;
        restore(restoredValues);
    }

    @Override
    public MemoryObjectImpl.MemoryItem get(String key) {
        if (!values.containsKey(key) && committedStore != null) {
            MemoryObjectImpl.MemoryItem committed = committedStore.get(key);
            if (committed != null) {
                // Values can contain mutable conversation maps/lists. An unfinished action
                // must not change the committed checkpoint merely by mutating a value it read.
                values.put(key, serializer.copy(committed));
            }
        }
        return values.get(key);
    }

    @Override
    public void put(String key, MemoryObjectImpl.MemoryItem value) {
        values.put(key, value);
        writtenKeys.add(key);
    }

    @Override
    public boolean contains(String key) {
        return values.containsKey(key) || (committedStore != null && committedStore.contains(key));
    }

    @Override
    public void persistCache() {
        if (committedStore != null) {
            for (String key : writtenKeys) {
                MemoryObjectImpl.MemoryItem value = values.get(key);
                MemoryObjectImpl.MemoryItem committed = committedStore.get(key);
                if (!value.isValue() && committed != null && !committed.isValue()) {
                    // Actions can create different fields while another action is suspended.
                    // Preserve both sets of child names when publishing their parent object.
                    value.getSubKeys().addAll(committed.getSubKeys());
                }
                committedStore.put(key, serializer.copy(value));
            }
            values.clear();
            writtenKeys.clear();
        }
    }

    @Override
    public void clear() {
        values.clear();
        writtenKeys.clear();
        if (committedStore != null) {
            committedStore.clear();
        }
    }
}
