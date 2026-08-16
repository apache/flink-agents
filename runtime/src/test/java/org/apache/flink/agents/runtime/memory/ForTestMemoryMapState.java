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
import java.util.Iterator;
import java.util.Map;

/** Simple, non-serialized HashMap implementation. */
public class ForTestMemoryMapState<V> implements MapState<String, V> {

    private final Map<String, V> delegate = new HashMap<>();

    @Override
    public V get(String key) {
        return delegate.get(key);
    }

    @Override
    public void put(String key, V value) {
        delegate.put(key, value);
    }

    @Override
    public void putAll(Map<String, V> map) {
        delegate.putAll(map);
    }

    @Override
    public void remove(String key) {
        delegate.remove(key);
    }

    @Override
    public boolean contains(String key) {
        return delegate.containsKey(key);
    }

    @Override
    public Iterable<Map.Entry<String, V>> entries() {
        return delegate.entrySet();
    }

    @Override
    public Iterable<String> keys() {
        return delegate.keySet();
    }

    @Override
    public Iterable<V> values() {
        return delegate.values();
    }

    @Override
    public Iterator<Map.Entry<String, V>> iterator() {
        return delegate.entrySet().iterator();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public void clear() {
        delegate.clear();
    }
}
