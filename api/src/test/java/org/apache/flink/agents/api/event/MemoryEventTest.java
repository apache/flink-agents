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

package org.apache.flink.agents.api.event;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link MemoryEvent} and its seven concrete subclasses. */
public class MemoryEventTest {

    @Test
    void testSubclassesPinTypes() {
        assertEquals("_short_term_write_event", ShortTermWriteEvent.EVENT_TYPE);
        assertEquals("_short_term_read_event", ShortTermReadEvent.EVENT_TYPE);
        assertEquals("_sensory_write_event", SensoryWriteEvent.EVENT_TYPE);
        assertEquals("_sensory_read_event", SensoryReadEvent.EVENT_TYPE);
        assertEquals("_long_term_update_event", LongTermUpdateEvent.EVENT_TYPE);
        assertEquals("_long_term_get_event", LongTermGetEvent.EVENT_TYPE);
        assertEquals("_long_term_search_event", LongTermSearchEvent.EVENT_TYPE);

        assertEquals(
                ShortTermWriteEvent.EVENT_TYPE,
                new ShortTermWriteEvent("k", new LinkedHashMap<>()).getType());
        assertEquals(
                LongTermSearchEvent.EVENT_TYPE,
                new LongTermSearchEvent("k", new LinkedHashMap<>()).getType());
    }

    @Test
    void testIsMemoryType() {
        assertTrue(MemoryEvent.isMemoryType("_short_term_write_event"));
        assertTrue(MemoryEvent.isMemoryType("_short_term_read_event"));
        assertTrue(MemoryEvent.isMemoryType("_sensory_write_event"));
        assertTrue(MemoryEvent.isMemoryType("_sensory_read_event"));
        assertTrue(MemoryEvent.isMemoryType("_long_term_update_event"));
        assertTrue(MemoryEvent.isMemoryType("_long_term_get_event"));
        assertTrue(MemoryEvent.isMemoryType("_long_term_search_event"));
        assertFalse(MemoryEvent.isMemoryType("_input_event"));
        assertFalse(MemoryEvent.isMemoryType("_agent_run_begin_event"));
        assertFalse(MemoryEvent.isMemoryType(null));
    }

    @Test
    void testEventTypeAggregateConstants() {
        assertEquals(ShortTermWriteEvent.EVENT_TYPE, EventType.ShortTermWriteEvent);
        assertEquals(ShortTermReadEvent.EVENT_TYPE, EventType.ShortTermReadEvent);
        assertEquals(SensoryWriteEvent.EVENT_TYPE, EventType.SensoryWriteEvent);
        assertEquals(SensoryReadEvent.EVENT_TYPE, EventType.SensoryReadEvent);
        assertEquals(LongTermUpdateEvent.EVENT_TYPE, EventType.LongTermUpdateEvent);
        assertEquals(LongTermGetEvent.EVENT_TYPE, EventType.LongTermGetEvent);
        assertEquals(LongTermSearchEvent.EVENT_TYPE, EventType.LongTermSearchEvent);
    }

    @Test
    void testFromEventRejectsNonMemoryType() {
        Event nonMemory = new InputEvent("data");
        assertThrows(IllegalArgumentException.class, () -> MemoryEvent.fromEvent(nonMemory));
    }

    @Test
    void testKeyValueLiveInAttributes() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("user.tier", "gold");
        MemoryEvent event = new ShortTermWriteEvent("user-42", value);

        assertEquals("user-42", event.getKey());
        assertEquals("gold", event.getValue().get("user.tier"));
        assertEquals("user-42", event.getAttributes().get("key"));
        assertEquals(value, event.getAttributes().get("value"));
    }

    @Test
    void testLiveConstructorNormalizesValuesToJsonEquivalentObjects() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("bytes", new byte[] {1, 2, 3});
        value.put("pojo", new ObservationValuePojo("hello", 7));

        MemoryEvent event = new ShortTermWriteEvent("user-42", value);

        assertEquals("AQID", event.getValue().get("bytes"));
        assertEquals(Map.of("name", "hello", "count", 7), event.getValue().get("pojo"));
        assertInstanceOf(byte[].class, value.get("bytes"));
        assertInstanceOf(ObservationValuePojo.class, value.get("pojo"));
    }

    @Test
    void testLiveConstructorRejectsNonJsonSerializableValue() {
        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new ShortTermWriteEvent("user-42", Map.of("bad", new Object())));

        assertTrue(
                error.getMessage().contains("Memory observation value must be JSON serializable"));
    }

    @Test
    void testLiveConstructorRejectsNestedNonFiniteNumbers() {
        for (Number nonFinite :
                List.of(
                        Double.NaN,
                        Double.POSITIVE_INFINITY,
                        Double.NEGATIVE_INFINITY,
                        Float.NaN,
                        Float.POSITIVE_INFINITY,
                        Float.NEGATIVE_INFINITY)) {
            Map<String, Object> value = Map.of("nested", Map.of("numbers", List.of(nonFinite)));

            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ShortTermWriteEvent("user-42", value),
                    nonFinite.toString());
        }
    }

    @Test
    void testFromEventDispatchesToSubclassAndNormalizesRawValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("bytes", new byte[] {1, 2, 3});
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("key", "k1");
        attributes.put("value", value);
        Event generic = new Event(LongTermUpdateEvent.EVENT_TYPE, attributes);

        MemoryEvent restored = MemoryEvent.fromEvent(generic);

        assertInstanceOf(LongTermUpdateEvent.class, restored);
        assertEquals(LongTermUpdateEvent.EVENT_TYPE, restored.getType());
        assertEquals("k1", restored.getKey());
        assertEquals("AQID", restored.getValue().get("bytes"));
        assertInstanceOf(byte[].class, value.get("bytes"));
    }

    @Test
    void testFromEventRejectsRawNonFiniteNumber() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("key", "k1");
        attributes.put("value", Map.of("nested", List.of(Double.NaN)));
        Event generic = new Event(LongTermUpdateEvent.EVENT_TYPE, attributes);

        assertThrows(IllegalArgumentException.class, () -> MemoryEvent.fromEvent(generic));
    }

    @Test
    void testConstructorsRejectIncompleteAttributes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ShortTermWriteEvent((String) null, new LinkedHashMap<>()));
        assertThrows(IllegalArgumentException.class, () -> new ShortTermWriteEvent("k", null));

        Event missingValue = new Event(ShortTermWriteEvent.EVENT_TYPE, Map.of("key", "user-42"));
        assertThrows(IllegalArgumentException.class, () -> MemoryEvent.fromEvent(missingValue));
    }

    @Test
    void testLongTermSearchTypedResults() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(
                "policies",
                Map.of(
                        "refund policy",
                        List.of(
                                Map.of("id", "p_01", "value", "7-day refund", "score", 0.92),
                                Map.of(
                                        "id",
                                        "p_02",
                                        "value",
                                        "no custom refunds",
                                        "score",
                                        0.81))));
        LongTermSearchEvent event = new LongTermSearchEvent("user-42", value);

        Map<String, Map<String, List<Map<String, Object>>>> results = event.getResults();
        assertEquals(2, results.get("policies").get("refund policy").size());
        assertEquals("p_01", results.get("policies").get("refund policy").get(0).get("id"));
    }

    @Test
    void testLongTermUpdateCarriesClearedSets() {
        LongTermUpdateEvent event =
                new LongTermUpdateEvent(
                        "k", Map.of("prefs", Map.of("m2", "new")), List.of("prefs"));

        assertEquals(List.of("prefs"), event.getClearedSets());
        assertEquals(Map.of("m2", "new"), event.getValue().get("prefs"));
    }

    public static class ObservationValuePojo {
        private final String name;
        private final int count;

        public ObservationValuePojo(String name, int count) {
            this.name = name;
            this.count = count;
        }

        public String getName() {
            return name;
        }

        public int getCount() {
            return count;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ObservationValuePojo)) return false;
            ObservationValuePojo that = (ObservationValuePojo) o;
            return count == that.count && Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, count);
        }
    }

    public static class NonFiniteValuePojo {
        private final double value;

        public NonFiniteValuePojo(double value) {
            this.value = value;
        }

        public double getValue() {
            return value;
        }
    }
}
