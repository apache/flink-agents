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
import org.apache.flink.api.common.state.MapState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link MemoryObject}. */
public class MemoryObjectTest {

    private MemoryObject memory;
    private List<MemoryUpdate> memoryUpdates;

    /** Simple POJO example. */
    static class Person {
        String name;
        int age;

        Person(String n, int a) {
            this.name = n;
            this.age = a;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Person)) return false;
            Person p = (Person) o;
            return age == p.age && Objects.equals(name, p.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, age);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        ForTestMemoryMapState<MemoryObjectImpl.MemoryItem> mapState = new ForTestMemoryMapState<>();
        memoryUpdates = new LinkedList<>();
        memory = new MemoryObjectImpl(mapState, MemoryObjectImpl.ROOT_KEY, memoryUpdates);
    }

    @Test
    public void testSetAndGet() throws Exception {
        memory.set("str", "hello");
        assertEquals("hello", memory.get("str").getValue());

        memory.set("int", 42);
        assertEquals(42, memory.get("int").getValue());

        memory.set("float", 3.14f);
        assertEquals(3.14f, memory.get("float").getValue());

        memory.set("double", 1.618);
        assertEquals(1.618, memory.get("double").getValue());

        memory.set("bool", true);
        assertEquals(true, memory.get("bool").getValue());

        byte[] bytes = {1, 2, 3};
        memory.set("bytes", bytes);
        assertArrayEquals(bytes, (byte[]) memory.get("bytes").getValue());

        // List
        List<String> list = Arrays.asList("x", "y", "z");
        memory.set("list", list);
        assertEquals(list, memory.get("list").getValue());

        // Map (dict)
        Map<String, Integer> dict = new HashMap<>();
        dict.put("k1", 1);
        dict.put("k2", 2);
        memory.set("dict", dict);
        assertEquals(dict, memory.get("dict").getValue());

        // Custom POJO
        Person alice = new Person("Alice", 23);
        memory.set("pojo", alice);
        assertEquals(alice, memory.get("pojo").getValue());
    }

    @Test
    public void testNewObject() throws Exception {
        MemoryObject obj = memory.newObject("level1", false);
        assertNotNull(obj);
        assertTrue(obj.isNestedObject());
    }

    @Test
    void testDeepNestedObjects() throws Exception {
        Map<String, Object> meta = new HashMap<>();
        meta.put("id", 123);
        meta.put("tags", Arrays.asList("flink", "ai"));

        memory.set("a.b.c.d.e", meta);
        assertEquals(meta, memory.get("a.b.c.d.e").getValue());

        MemoryObject levelA = memory.get("a");
        assertTrue(levelA.getFieldNames().contains("b"));
        MemoryObject levelB = levelA.get("b");
        assertTrue(levelB.getFieldNames().contains("c"));
    }

    @Test
    void testFieldNamesAndFields() throws Exception {
        memory.set("x", 1);
        memory.set("y", 2);
        memory.newObject("obj", false).set("inner", 3);

        List<String> names = memory.getFieldNames();
        assertTrue(names.containsAll(Arrays.asList("x", "y", "obj")));

        Map<String, Object> fields = memory.getFields();
        assertEquals(1, fields.get("x"));
        assertEquals(2, fields.get("y"));
        assertEquals("NestedObject", fields.get("obj"));
    }

    @Test
    void testOverwriteRules() throws Exception {
        memory.newObject("conflict", false);
        assertThrows(IllegalArgumentException.class, () -> memory.set("conflict", 100));

        memory.set("scalar", 5);
        assertThrows(IllegalArgumentException.class, () -> memory.newObject("scalar", false));

        MemoryObject obj = memory.newObject("scalar", true);
        obj.set("k", "v");
        assertEquals("v", memory.get("scalar.k").getValue());
    }

    @Test
    void testIsExist() throws Exception {
        memory.set("exist", 1);
        assertTrue(memory.isExist("exist"));
        assertFalse(memory.isExist("not.exist"));
    }

    @Test
    void testMemoryUpdates() throws Exception {
        memory.set("str", "hello");
        memory = memory.newObject("str", true);
        memory.set("test", 100);
        memory = memory.newObject("new_str", false);
        memory.set("int", 42);
        memory.set("str", "world");

        assertThat(memoryUpdates)
                .containsExactlyInAnyOrder(
                        new MemoryUpdate("str", "hello"),
                        new MemoryUpdate("str", null),
                        new MemoryUpdate("str.test", 100),
                        new MemoryUpdate("str.new_str", null),
                        new MemoryUpdate("str.new_str.int", 42),
                        new MemoryUpdate("str.new_str.str", "world"));
    }
}

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
