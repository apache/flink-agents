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
class ForTestMemoryMapState<V> implements MapState<String, V> {

    private final Map<String, V> fortest = new HashMap<>();

    @Override
    public V get(String key) {
        return fortest.get(key);
    }

    @Override
    public void put(String key, V value) {
        fortest.put(key, value);
    }

    @Override
    public void putAll(Map<String, V> map) {
        fortest.putAll(map);
    }

    @Override
    public void remove(String key) {
        fortest.remove(key);
    }

    @Override
    public boolean contains(String key) {
        return fortest.containsKey(key);
    }

    @Override
    public Iterable<Map.Entry<String, V>> entries() {
        return fortest.entrySet();
    }

    @Override
    public Iterable<String> keys() {
        return fortest.keySet();
    }

    @Override
    public Iterable<V> values() {
        return fortest.values();
    }

    @Override
    public Iterator<Map.Entry<String, V>> iterator() {
        return fortest.entrySet().iterator();
    }

    @Override
    public boolean isEmpty() {
        return fortest.isEmpty();
    }

    @Override
    public void clear() {
        fortest.clear();
    }
}
