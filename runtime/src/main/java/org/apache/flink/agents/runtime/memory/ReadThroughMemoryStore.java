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

/**
 * Call-local memory that reads copies of the caller's values. Writes and persistence remain local
 * to the call, including mutations of values and object field lists obtained through {@link #get}.
 */
public final class ReadThroughMemoryStore extends IsolatedCachedMemoryStore {
    private final CachedMemoryStore parent;
    private final TypeSerializer<MemoryObjectImpl.MemoryItem> serializer =
            TypeInformation.of(MemoryObjectImpl.MemoryItem.class)
                    .createSerializer(new SerializerConfigImpl());
    private boolean cleared;

    public ReadThroughMemoryStore(CachedMemoryStore parent) {
        this.parent = parent;
    }

    @Override
    public MemoryObjectImpl.MemoryItem get(String key) throws Exception {
        if (!super.contains(key) && !cleared) {
            MemoryObjectImpl.MemoryItem item = parent.get(key);
            if (item != null) {
                // Copy before exposing anything: copying on put is too late for a List/Map or
                // MemoryItem.subKeys mutated through a reference returned by get.
                super.put(key, serializer.copy(item));
            }
        }
        return super.get(key);
    }

    @Override
    public boolean contains(String key) throws Exception {
        return super.contains(key) || (!cleared && parent.contains(key));
    }

    @Override
    public void clear() throws Exception {
        super.clear();
        // Clearing this view must neither clear the caller nor reveal its values again.
        cleared = true;
    }
}
