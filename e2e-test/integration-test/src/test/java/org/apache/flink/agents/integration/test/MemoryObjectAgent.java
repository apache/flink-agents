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
package org.apache.flink.agents.integration.test;

import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;

import java.io.Serializable;
import java.util.*;

/** An example agent that tests usages of MemoryObject. */
public class MemoryObjectAgent extends Agent {

    /** A custom POJO for testing serialization. */
    public static class Person implements Serializable {
        public String name;
        public int age;

        public Person() {}

        public Person(String name, int age) {
            this.name = name;
            this.age = age;
        }

        @Override
        public String toString() {
            return "Person{" + "name='" + name + '\'' + ", age=" + age + '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Person person = (Person) o;
            return age == person.age && Objects.equals(name, person.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, age);
        }
    }

    @Action(listenEvents = {InputEvent.class})
    public static void testMemoryObject(Event event, RunnerContext ctx) throws Exception {
        MemoryObject stm = ctx.getShortTermMemory();
        Integer key = (Integer) ((InputEvent) event).getInput();

        int visitCount = 1;
        if (stm.isExist("visit_count")) {
            visitCount = ((Number) stm.get("visit_count").getValue()).intValue() + 1;
        }
        stm.set("visit_count", visitCount);

        // isExist
        stm.set("existing.path", true);
        assertEquals(stm.isExist("existing.path"), true);
        assertEquals(stm.isExist("non.existing.path"), false);

        // getFieldNames and getFields
        MemoryObject fieldsTestObj = stm.newObject("fieldsTest", true);
        fieldsTestObj.set("x", 1);
        fieldsTestObj.set("y", 2);
        fieldsTestObj.newObject("obj", false);
        List<String> names = fieldsTestObj.getFieldNames();
        assertEquals(new HashSet<>(names).containsAll(Arrays.asList("x", "y", "obj")), true);
        Map<String, Object> fields = fieldsTestObj.getFields();
        assertEquals(1, ((Number) fields.get("x")).intValue());
        assertEquals("NestedObject", fields.get("obj"));

        // List
        List<String> tags = Arrays.asList("gamer", "developer", "flink-user");
        stm.set("list", tags);
        assertEquals(tags, stm.get("list").getValue());

        // Map
        Map<String, Integer> inventory = new HashMap<>();
        inventory.put("potion", 10);
        inventory.put("gold", 500);
        stm.set("map", inventory);
        assertEquals(inventory, stm.get("map").getValue());

        // Custom POJO
        Person person = new Person("Bob", 22);
        stm.set("person", person);
        assertEquals(person, stm.get("person").getValue());

        String result =
                String.format("All assertions passed for key: %d (visit #%d)", key, visitCount);
        String output = result + " [Agent Complete]";
        ctx.sendEvent(new OutputEvent(output));
    }

    /**
     * A simple, custom assertion helper to verify equality.
     *
     * @param expected The expected value.
     * @param actual The actual value from MemoryObject.
     */
    private static void assertEquals(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(
                    String.format(
                            "Assertion FAILED : Expected <%s>, but was <%s>.", expected, actual));
        }
    }
}
