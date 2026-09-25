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
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.DurableFuture;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.MemoryRef;
import org.apache.flink.agents.api.context.Outcome;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.memory.BaseLongTermMemory;
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

    static class ImmediateDurableFuture<T> implements DurableFuture<T> {
        private final DurableCallable<T> callable;
        private boolean done;
        private T value;

        ImmediateDurableFuture(DurableCallable<T> callable) {
            this.callable = callable;
        }

        @Override
        public T await() throws Exception {
            if (!done) {
                value = callable.call();
                done = true;
            }
            return value;
        }
    }

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
        public MemoryObject getShortTermMemory() {
            return memoryObject;
        }

        @Override
        public BaseLongTermMemory getLongTermMemory() throws Exception {
            return null;
        }

        @Override
        public MemoryObject getSensoryMemory() {
            return null;
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

        @Override
        public <T> T durableExecute(DurableCallable<T> callable) throws Exception {
            return callable.call();
        }

        @Override
        public <T> DurableFuture<T> durableExecuteAsync(DurableCallable<T> callable) {
            return new ImmediateDurableFuture<>(callable);
        }

        @Override
        public <T> DurableFuture<List<Outcome<T>>> gather(
                List<? extends DurableFuture<T>> futures) {
            return new ImmediateDurableFuture<>(
                    new DurableCallable<List<Outcome<T>>>() {
                        @Override
                        public String getId() {
                            return "gather";
                        }

                        @SuppressWarnings("unchecked")
                        @Override
                        public Class<List<Outcome<T>>> getResultClass() {
                            return (Class<List<Outcome<T>>>) (Class<?>) List.class;
                        }

                        @Override
                        public List<Outcome<T>> call() throws Exception {
                            List<Outcome<T>> outcomes = new ArrayList<>();
                            for (DurableFuture<T> future : futures) {
                                outcomes.add(Outcome.success(future.await()));
                            }
                            return outcomes;
                        }
                    });
        }

        @Override
        public void close() throws Exception {}
    }

    @BeforeEach
    void setUp() throws Exception {
        ForTestMemoryMapState<MemoryObjectImpl.MemoryItem> mapState = new ForTestMemoryMapState<>();
        memory =
                new MemoryObjectImpl(
                        MemoryObject.MemoryType.SHORT_TERM,
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
        MemoryRef ref = MemoryRef.create(MemoryObject.MemoryType.SHORT_TERM, path);

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

            Object resolvedValue = ref.resolve(ctx).getValue();
            assertEquals(entry.getValue(), resolvedValue);
        }
    }

    @Test
    void testGetWithRefToNestedObject() throws Exception {
        MemoryObject obj = memory.newObject("a.b", false);
        obj.set("c", 10);

        MemoryRef ref = MemoryRef.create(MemoryObject.MemoryType.SHORT_TERM, "a");

        MemoryObject resolvedObj = memory.get(ref);
        assertNotNull(resolvedObj);
        assertTrue(resolvedObj.isNestedObject());
        assertEquals(10, resolvedObj.get("b.c").getValue());
    }

    @Test
    void testGetWithNonExistentRef() throws Exception {
        MemoryRef nonExistentRef =
                MemoryRef.create(MemoryObject.MemoryType.SHORT_TERM, "this.path.does.not.exist");
        assertNull(memory.get(nonExistentRef));
    }

    @Test
    void testRefEqualityAndHashing() {
        MemoryRef ref1 = MemoryRef.create(MemoryObject.MemoryType.SHORT_TERM, "a.b");
        MemoryRef ref2 = MemoryRef.create(MemoryObject.MemoryType.SHORT_TERM, "a.b");
        MemoryRef ref3 = MemoryRef.create(MemoryObject.MemoryType.SHORT_TERM, "a.c");

        assertEquals(ref1, ref2);
        assertNotEquals(ref1, ref3);

        assertEquals(ref1.hashCode(), ref2.hashCode());
        assertNotEquals(ref1.hashCode(), ref3.hashCode());

        Set<MemoryRef> refSet = new HashSet<>(Arrays.asList(ref1, ref2, ref3));
        assertEquals(2, refSet.size());
        assertTrue(refSet.contains(ref1));
        assertTrue(refSet.contains(ref3));
    }

    /**
     * A reference names a slot in one memory: the same path in sensory and short-term memory is two
     * different slots, so the references must not compare equal.
     */
    @Test
    void testRefEqualityDistinguishesMemoryType() {
        MemoryRef sensory = MemoryRef.create(MemoryObject.MemoryType.SENSORY, "a.b");
        MemoryRef shortTerm = MemoryRef.create(MemoryObject.MemoryType.SHORT_TERM, "a.b");

        assertNotEquals(sensory, shortTerm);

        Set<MemoryRef> refSet = new HashSet<>(Arrays.asList(sensory, shortTerm));
        assertEquals(2, refSet.size());
        assertTrue(refSet.contains(sensory));
        assertTrue(refSet.contains(shortTerm));
    }

    @Test
    void testToStringNamesMemoryType() {
        MemoryRef ref = MemoryRef.create(MemoryObject.MemoryType.SENSORY, "a.b");
        assertTrue(ref.toString().contains("SENSORY"), ref.toString());
        assertTrue(ref.toString().contains("a.b"), ref.toString());
    }

    @Test
    void testCreateRejectsNullTypeOrPath() {
        assertThrows(IllegalArgumentException.class, () -> MemoryRef.create(null, "a.b"));
        assertThrows(
                IllegalArgumentException.class,
                () -> MemoryRef.create(MemoryObject.MemoryType.SENSORY, null));
    }
}
