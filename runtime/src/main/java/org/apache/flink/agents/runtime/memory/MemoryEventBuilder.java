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

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.flink.agents.api.context.MemoryUpdate;
import org.apache.flink.agents.api.event.LongTermUpdateEvent;
import org.apache.flink.agents.api.event.MemoryEvent;
import org.apache.flink.agents.runtime.memory.MemoryEventSettings.MemoryOp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/** Converts per-action memory observations into at most one event of each operation type. */
public final class MemoryEventBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(MemoryEventBuilder.class);
    private static final ObjectMapper MAPPER =
            JsonMapper.builder().disable(JsonWriteFeature.WRITE_NAN_AS_STRINGS).build();
    private static final int LTM_RECORD_VERSION = 1;
    private static final Object INVALID_VALUE = new Object();

    private enum LtmOperation {
        ADD,
        UPDATE,
        DELETE,
        DELETE_SET,
        GET,
        SEARCH
    }

    private MemoryEventBuilder() {}

    public static List<MemoryEvent> buildWriteEvents(
            String eventKeyText,
            List<MemoryUpdate> sensoryWrites,
            List<MemoryUpdate> shortTermWrites,
            MemoryEventSettings settings) {
        List<MemoryEvent> events = new ArrayList<>(2);
        addFoldedEvent(
                events,
                eventKeyText,
                MemoryOp.SENSORY_WRITE,
                sensoryWrites,
                MemoryUpdate::getPath,
                MemoryUpdate::getValue,
                settings);
        addFoldedEvent(
                events,
                eventKeyText,
                MemoryOp.SHORT_TERM_WRITE,
                shortTermWrites,
                MemoryUpdate::getPath,
                MemoryUpdate::getValue,
                settings);
        return events;
    }

    public static List<MemoryEvent> buildReadEvents(
            String eventKeyText,
            List<MemoryValueObservation> sensoryReads,
            List<MemoryValueObservation> shortTermReads,
            MemoryEventSettings settings) {
        List<MemoryEvent> events = new ArrayList<>(2);
        addFoldedEvent(
                events,
                eventKeyText,
                MemoryOp.SENSORY_READ,
                sensoryReads,
                MemoryValueObservation::getPath,
                MemoryValueObservation::getValue,
                settings);
        addFoldedEvent(
                events,
                eventKeyText,
                MemoryOp.SHORT_TERM_READ,
                shortTermReads,
                MemoryValueObservation::getPath,
                MemoryValueObservation::getValue,
                settings);
        return events;
    }

    public static List<MemoryEvent> buildLtmEvents(
            String eventKeyText, List<Map<String, Object>> records, MemoryEventSettings settings) {
        Map<String, Map<String, Object>> updates = new LinkedHashMap<>();
        Map<String, Map<String, Object>> gets = new LinkedHashMap<>();
        Map<String, Map<String, Object>> searches = new LinkedHashMap<>();
        Set<String> clearedSets = new LinkedHashSet<>();

        for (Map<String, Object> record : records) {
            LtmOperation operation = parseLtmOperation(record.get("op"));
            String memorySet = nonEmptyString(record.get("set"));
            if (operation == null || memorySet == null) {
                continue;
            }
            String memoryId = nonEmptyString(record.get("id"));
            String query = nonEmptyString(record.get("query"));
            Object value = record.get("value");

            if (operation == LtmOperation.DELETE_SET) {
                updates.remove(memorySet);
                clearedSets.add(memorySet);
            } else if ((operation == LtmOperation.ADD || operation == LtmOperation.UPDATE)
                    && memoryId != null) {
                putNormalized(updates, memorySet, memoryId, value);
            } else if (operation == LtmOperation.DELETE && memoryId != null) {
                updates.computeIfAbsent(memorySet, ignored -> new LinkedHashMap<>())
                        .put(memoryId, null);
            } else if (operation == LtmOperation.GET && memoryId != null) {
                putNormalized(gets, memorySet, memoryId, value);
            } else if (operation == LtmOperation.SEARCH && query != null) {
                putNormalized(searches, memorySet, query, value);
            }
        }

        List<MemoryEvent> events = new ArrayList<>(3);
        if (settings.generate(MemoryOp.LONG_TERM_UPDATE)
                && (!updates.isEmpty() || !clearedSets.isEmpty())) {
            addSafely(
                    events,
                    () ->
                            new LongTermUpdateEvent(
                                    eventKeyText,
                                    toObjectMap(updates),
                                    new ArrayList<>(clearedSets)),
                    MemoryOp.LONG_TERM_UPDATE);
        }
        addNestedEvent(events, eventKeyText, MemoryOp.LONG_TERM_GET, gets, settings);
        addNestedEvent(events, eventKeyText, MemoryOp.LONG_TERM_SEARCH, searches, settings);
        return events;
    }

    /**
     * Parses and validates the private, versioned Python-to-Java LTM observation records. Malformed
     * individual records are skipped; a malformed envelope yields no records.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> parseLtmObservationRecords(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<?> raw = MAPPER.readValue(json, List.class);
            List<Map<String, Object>> records = new ArrayList<>(raw.size());
            for (Object item : raw) {
                if (!(item instanceof Map)) {
                    LOG.warn("Skipping malformed LTM observation record");
                    continue;
                }
                Map<String, Object> record = (Map<String, Object>) item;
                if (isValidLtmRecord(record)) {
                    records.add(record);
                }
            }
            return records;
        } catch (Exception | LinkageError e) {
            LOG.warn("Dropping malformed LTM observation payload");
            return Collections.emptyList();
        }
    }

    /** Strict JSON round-trip shared by runtime memory-observation producers. */
    @Nullable
    public static Object normalizeValue(@Nullable Object value) throws IOException {
        return MAPPER.readValue(MAPPER.writeValueAsBytes(value), Object.class);
    }

    private static boolean isValidLtmRecord(Map<String, Object> record) {
        Object version = record.get("version");
        LtmOperation operation = parseLtmOperation(record.get("op"));
        String memorySet = nonEmptyString(record.get("set"));
        if (!(version instanceof Integer)
                || ((Integer) version) != LTM_RECORD_VERSION
                || operation == null
                || memorySet == null) {
            LOG.warn("Skipping malformed LTM observation record");
            return false;
        }
        String memoryId = nonEmptyString(record.get("id"));
        if ((operation == LtmOperation.ADD
                        || operation == LtmOperation.UPDATE
                        || operation == LtmOperation.DELETE
                        || operation == LtmOperation.GET)
                && memoryId == null) {
            LOG.warn("Skipping malformed LTM observation record for operation '{}'", operation);
            return false;
        }
        if (operation == LtmOperation.SEARCH && nonEmptyString(record.get("query")) == null) {
            LOG.warn("Skipping malformed LTM search observation record");
            return false;
        }
        return true;
    }

    @Nullable
    private static LtmOperation parseLtmOperation(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        try {
            return LtmOperation.valueOf((String) value);
        } catch (IllegalArgumentException e) {
            LOG.warn("Skipping LTM observation record with unknown operation '{}'", value);
            return null;
        }
    }

    @Nullable
    private static String nonEmptyString(Object value) {
        return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
    }

    private static <T> void addFoldedEvent(
            List<MemoryEvent> events,
            String eventKeyText,
            MemoryOp operation,
            List<T> records,
            Function<T, String> pathExtractor,
            Function<T, Object> valueExtractor,
            MemoryEventSettings settings) {
        if (records == null || records.isEmpty() || !settings.generate(operation)) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (T record : records) {
            String path = pathExtractor.apply(record);
            Object normalized = normalizeSafely(valueExtractor.apply(record), operation);
            if (normalized == INVALID_VALUE) {
                // Last observation wins even when its value cannot be represented. Retaining an
                // older value would falsely report stale memory state.
                values.remove(path);
            } else {
                values.put(path, normalized);
            }
        }
        if (!values.isEmpty()) {
            addSafely(events, () -> operation.createEvent(eventKeyText, values), operation);
        }
    }

    private static void addNestedEvent(
            List<MemoryEvent> events,
            String eventKeyText,
            MemoryOp operation,
            Map<String, Map<String, Object>> values,
            MemoryEventSettings settings) {
        if (!values.isEmpty() && settings.generate(operation)) {
            addSafely(
                    events,
                    () -> operation.createEvent(eventKeyText, toObjectMap(values)),
                    operation);
        }
    }

    private static void putNormalized(
            Map<String, Map<String, Object>> destination,
            String memorySet,
            @Nullable String itemKey,
            @Nullable Object value) {
        if (itemKey == null) {
            return;
        }
        Object normalized = normalizeSafely(value, null);
        if (normalized == INVALID_VALUE) {
            removeNestedValue(destination, memorySet, itemKey);
            return;
        }
        destination
                .computeIfAbsent(memorySet, ignored -> new LinkedHashMap<>())
                .put(itemKey, normalized);
    }

    private static void removeNestedValue(
            Map<String, Map<String, Object>> destination, String memorySet, String itemKey) {
        Map<String, Object> values = destination.get(memorySet);
        if (values == null) {
            return;
        }
        values.remove(itemKey);
        if (values.isEmpty()) {
            destination.remove(memorySet);
        }
    }

    private static void addSafely(
            List<MemoryEvent> events, Supplier<MemoryEvent> supplier, MemoryOp operation) {
        try {
            events.add(supplier.get());
        } catch (RuntimeException e) {
            LOG.warn(
                    "Skipping framework memory observation event for operation '{}' because its value is not JSON-compatible ({})",
                    operation,
                    e.getClass().getSimpleName());
        }
    }

    private static Object normalizeSafely(@Nullable Object value, @Nullable MemoryOp operation) {
        try {
            return normalizeValue(value);
        } catch (Exception | LinkageError e) {
            LOG.warn(
                    "Skipping framework memory observation value{} because it is not JSON-compatible ({})",
                    operation == null ? "" : " for operation '" + operation + "'",
                    e.getClass().getSimpleName());
            return INVALID_VALUE;
        }
    }

    private static Map<String, Object> toObjectMap(Map<String, Map<String, Object>> nested) {
        return new LinkedHashMap<>(nested);
    }
}
