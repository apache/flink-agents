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

import org.apache.flink.agents.api.context.MemoryUpdate;
import org.apache.flink.agents.api.event.LongTermGetEvent;
import org.apache.flink.agents.api.event.LongTermSearchEvent;
import org.apache.flink.agents.api.event.LongTermUpdateEvent;
import org.apache.flink.agents.api.event.MemoryEvent;
import org.apache.flink.agents.api.event.ShortTermWriteEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Memory-update folding and versioned long-term-memory observation rules. */
class TestMemoryEventBuilder {
    private static MemoryEventSettings allOn() {
        return MemoryEventSettings.from(Map.of("memory.generate-event", true));
    }

    @Test
    void writesFoldLastValueAndKeepNullMarkers() {
        List<MemoryEvent> events =
                MemoryEventBuilder.buildWriteEvents(
                        "user-42",
                        List.of(),
                        List.of(
                                new MemoryUpdate("user.tier", "silver"),
                                new MemoryUpdate("user.tier", "gold"),
                                new MemoryUpdate("nullable", null)),
                        allOn());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getType()).isEqualTo(ShortTermWriteEvent.EVENT_TYPE);
        assertThat(events.get(0).getValue())
                .containsEntry("user.tier", "gold")
                .containsEntry("nullable", null);
    }

    @Test
    void invalidValueIsSkippedWithoutDroppingValidValue() {
        List<MemoryEvent> events =
                MemoryEventBuilder.buildWriteEvents(
                        "k",
                        List.of(),
                        List.of(new MemoryUpdate("bad", Double.NaN), new MemoryUpdate("good", "v")),
                        allOn());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getValue()).containsOnlyKeys("good");
    }

    @Test
    void invalidLastShortTermValueRemovesEarlierFoldedValue() {
        List<MemoryEvent> events =
                MemoryEventBuilder.buildWriteEvents(
                        "k",
                        List.of(),
                        List.of(
                                new MemoryUpdate("stale", "old"),
                                new MemoryUpdate("kept", "current"),
                                new MemoryUpdate("stale", Double.NaN)),
                        allOn());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getValue())
                .containsEntry("kept", "current")
                .doesNotContainKey("stale");
    }

    @Test
    void ltmUsesStructuredSetIdentityAndExplicitSetClear() {
        List<Map<String, Object>> records =
                MemoryEventBuilder.parseLtmObservationRecords(
                        "["
                                + "{\"version\":1,\"op\":\"ADD\",\"set\":\"a\",\"id\":\"b.m1\",\"value\":\"old\"},"
                                + "{\"version\":1,\"op\":\"ADD\",\"set\":\"a.b\",\"id\":\"m1\",\"value\":\"kept\"},"
                                + "{\"version\":1,\"op\":\"DELETE_SET\",\"set\":\"a\"},"
                                + "{\"version\":1,\"op\":\"UPDATE\",\"set\":\"a\",\"id\":\"m2\",\"value\":\"new\"},"
                                + "{\"version\":1,\"op\":\"GET\",\"set\":\"a.b\",\"id\":\"m1\",\"value\":\"kept\"},"
                                + "{\"version\":1,\"op\":\"SEARCH\",\"set\":\"a.b\",\"query\":\"q\",\"value\":[{\"id\":\"m1\"}]}"
                                + "]");

        List<MemoryEvent> events = MemoryEventBuilder.buildLtmEvents("k", records, allOn());

        assertThat(events).hasSize(3);
        LongTermUpdateEvent update = (LongTermUpdateEvent) events.get(0);
        assertThat(update.getClearedSets()).containsExactly("a");
        assertThat(update.getValue())
                .containsEntry("a", Map.of("m2", "new"))
                .containsEntry("a.b", Map.of("m1", "kept"));
        assertThat(events.get(1).getType()).isEqualTo(LongTermGetEvent.EVENT_TYPE);
        assertThat(events.get(1).getValue()).containsEntry("a.b", Map.of("m1", "kept"));
        assertThat(events.get(2).getType()).isEqualTo(LongTermSearchEvent.EVENT_TYPE);
        assertThat(events.get(2).getValue()).containsKey("a.b");
    }

    @Test
    void parserRejectsUnknownOrUnversionedRecords() {
        assertThat(
                        MemoryEventBuilder.parseLtmObservationRecords(
                                "[{\"op\":\"ADD\",\"set\":\"s\",\"id\":\"i\"},"
                                        + "{\"version\":1,\"op\":\"UNKNOWN\",\"set\":\"s\"}]"))
                .isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void secondaryJavaNormalizationFailureRemovesEarlierLtmValue() {
        List<Map<String, Object>> records =
                MemoryEventBuilder.parseLtmObservationRecords(
                        "["
                                + "{\"version\":1,\"op\":\"UPDATE\",\"set\":\"s\",\"id\":\"stale\",\"value\":\"old\"},"
                                + "{\"version\":1,\"op\":\"UPDATE\",\"set\":\"s\",\"id\":\"stale\",\"value\":{}},"
                                + "{\"version\":1,\"op\":\"UPDATE\",\"set\":\"s\",\"id\":\"kept\",\"value\":\"new\"}"
                                + "]");
        ((Map<String, Object>) records.get(1).get("value")).put("invalid", Double.NaN);

        LongTermUpdateEvent event =
                (LongTermUpdateEvent)
                        MemoryEventBuilder.buildLtmEvents("k", records, allOn()).get(0);

        assertThat(event.getValue()).containsEntry("s", Map.of("kept", "new"));
    }

    @Test
    void parserRequiresExactVersionAndNonEmptyIdentityFields() {
        assertThat(
                        MemoryEventBuilder.parseLtmObservationRecords(
                                "["
                                        + "{\"version\":1.5,\"op\":\"ADD\",\"set\":\"s\",\"id\":\"i\"},"
                                        + "{\"version\":1,\"op\":\"GET\",\"set\":\"s\",\"id\":\"\"},"
                                        + "{\"version\":1,\"op\":\"SEARCH\",\"set\":\"s\",\"query\":\"\"}"
                                        + "]"))
                .isEmpty();
    }
}
