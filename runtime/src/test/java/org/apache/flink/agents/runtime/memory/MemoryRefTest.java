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

import org.apache.flink.agents.api.configuration.ReadableConfiguration;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.MemoryRef;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link MemoryRef} and its integration with {@link MemoryObject}. */
public class MemoryRefTest {

    private MemoryObjectImpl memory;

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

    /** Mock RunnerContext for testing resolve(). */
    static class MockRunnerContext implements RunnerContext {
        private final MemoryObject memoryObject;

        MockRunnerContext(MemoryObject memoryObject) {
            this.memoryObject = memoryObject;
        }

        @Override
        public MemoryObject getSensoryMemory() throws Exception {
            return memoryObject;
        }

        @Override
        public MemoryObject getShortTermMemory() {
            return memoryObject;
        }

        @Override
        public void sendEvent(org.apache.flink.agents.api.Event event) {}

        @Override
        public FlinkAgentsMetricGroup getAgentMetricGroup() {
            return null;
        }

        @Override
        public FlinkAgentsMetricGroup getActionMetricGroup() {
            return null;
        }

        @Override
        public Resource getResource(String name, ResourceType type) throws Exception {
            return null;
        }

        @Override
        public ReadableConfiguration getConfig() {
            return null;
        }

        @Override
        public Map<String, Object> getActionConfig() {
            return Map.of();
        }

        @Override
        public Object getActionConfigValue(String key) {
            return null;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        ForTestMemoryMapState<MemoryObjectImpl.MemoryItem> mapState = new ForTestMemoryMapState<>();
        memory =
                new MemoryObjectImpl(
                        new CachedMemoryStore(mapState),
                        MemoryObjectImpl.ROOT_KEY,
                        new LinkedList<>());
    }

    @Test
    void testSetAndGetInvolvedRef() throws Exception {
        MemoryRef intRef = memory.set("my_int", 123);
        assertEquals("my_int", intRef.getPath());
        assertEquals(123, memory.get(intRef).getValue());

        MemoryRef strRef = memory.set("my_str", "hello");
        assertEquals("my_str", strRef.getPath());
        assertEquals("hello", memory.get(strRef).getValue());

        // List
        List<String> list = Arrays.asList("a", "b");
        MemoryRef listRef = memory.set("my_list", list);
        assertEquals("my_list", listRef.getPath());
        assertEquals(list, memory.get(listRef).getValue());

        // Map
        Map<String, Integer> map = new HashMap<>();
        map.put("x", 10);
        MemoryRef mapRef = memory.set("my_map", map);
        assertEquals("my_map", mapRef.getPath());
        assertEquals(map, memory.get(mapRef).getValue());

        // Set
        Set<Integer> set = new HashSet<>(Arrays.asList(1, 2, 3));
        MemoryRef setRef = memory.set("my_set", set);
        assertEquals("my_set", setRef.getPath());
        assertEquals(set, memory.get(setRef).getValue());

        // Custom POJO
        Person alice = new Person("Alice", 23);
        MemoryRef personRef = memory.set("pojo", alice);
        assertEquals("pojo", personRef.getPath());
        assertEquals(alice, memory.get(personRef).getValue());
    }

    @Test
    void testMemoryRefCreate() {
        String path = "a.b.c";
        String typeName = "String";
        MemoryRef ref = MemoryRef.create(path);

        assertNotNull(ref);
        assertEquals(path, ref.getPath());
    }

    @Test
    void testMemoryRefResolve() throws Exception {
        MockRunnerContext ctx = new MockRunnerContext(memory);

        Map<String, Object> testData = new HashMap<>();
        testData.put("my_int", 1);
        testData.put("my_str", "resolve_test");
        testData.put("my_list", Arrays.asList("x", "y", "z"));
        testData.put("my_person", new Person("Resolver", 42));

        for (Map.Entry<String, Object> entry : testData.entrySet()) {
            MemoryRef ref = memory.set(entry.getKey(), entry.getValue());

            Object resolvedValue = ref.resolve(ctx.getShortTermMemory()).getValue();
            assertEquals(entry.getValue(), resolvedValue);
        }
    }

    @Test
    void testGetWithRefToNestedObject() throws Exception {
        MemoryObject obj = memory.newObject("a.b", false);
        obj.set("c", 10);

        MemoryRef ref = MemoryRef.create("a");

        MemoryObject resolvedObj = memory.get(ref);
        assertNotNull(resolvedObj);
        assertTrue(resolvedObj.isNestedObject());
        assertEquals(10, resolvedObj.get("b.c").getValue());
    }

    @Test
    void testGetWithNonExistentRef() throws Exception {
        MemoryRef nonExistentRef = MemoryRef.create("this.path.does.not.exist");
        assertNull(memory.get(nonExistentRef));
    }

    @Test
    void testRefEqualityAndHashing() {
        MemoryRef ref1 = MemoryRef.create("a.b");
        MemoryRef ref2 = MemoryRef.create("a.b");
        MemoryRef ref3 = MemoryRef.create("a.c");

        assertEquals(ref1, ref2);
        assertNotEquals(ref1, ref3);

        assertEquals(ref1.hashCode(), ref2.hashCode());
        assertNotEquals(ref1.hashCode(), ref3.hashCode());

        Set<MemoryRef> refSet = new HashSet<>(Arrays.asList(ref1, ref2, ref3));
        assertEquals(2, refSet.size());
        assertTrue(refSet.contains(ref1));
        assertTrue(refSet.contains(ref3));
    }
}
