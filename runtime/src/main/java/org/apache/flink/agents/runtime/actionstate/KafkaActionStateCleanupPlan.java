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
package org.apache.flink.agents.runtime.actionstate;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.util.Preconditions;
import org.apache.kafka.common.Uuid;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Immutable, reviewable Kafka prefix-cleanup boundary derived from one recovery point. */
public final class KafkaActionStateCleanupPlan implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final int CURRENT_SCHEMA_VERSION = 1;
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> JSON_FIELDS =
            Set.of("schemaVersion", "planId", "sourceRecoveryPoint", "topic", "topicId", "offsets");

    private final int schemaVersion;
    private final String planId;
    private final String sourceRecoveryPoint;
    private final String topic;
    private final String topicId;
    private final Map<Integer, Long> offsets;

    private KafkaActionStateCleanupPlan(
            int schemaVersion,
            String planId,
            String sourceRecoveryPoint,
            String topic,
            String topicId,
            Map<Integer, Long> offsets) {
        Preconditions.checkArgument(
                schemaVersion == CURRENT_SCHEMA_VERSION,
                "Unsupported Kafka action-state cleanup plan schema %s",
                schemaVersion);
        this.sourceRecoveryPoint = requireText(sourceRecoveryPoint, "source recovery point");
        this.topic = requireText(topic, "Kafka action-state topic");
        this.topicId = requireText(topicId, "Kafka action-state topic ID");
        Preconditions.checkArgument(
                !Uuid.ZERO_UUID.toString().equals(this.topicId),
                "Checkpoint-aligned cleanup requires a broker-provided Kafka topic ID");
        this.offsets = immutableOffsets(offsets);
        this.schemaVersion = schemaVersion;
        String expectedPlanId = calculatePlanId(sourceRecoveryPoint, topic, topicId, this.offsets);
        Preconditions.checkArgument(
                planId == null || expectedPlanId.equals(planId),
                "Kafka action-state cleanup plan ID does not match its contents");
        this.planId = expectedPlanId;
    }

    /**
     * Builds a plan from every union recovery marker in one selected checkpoint or savepoint.
     * Legacy map markers are intentionally rejected because they do not identify the physical Kafka
     * topic and therefore cannot authorize irreversible deletion safely.
     */
    public static KafkaActionStateCleanupPlan fromRecoveryMarkers(
            String sourceRecoveryPoint, List<?> recoveryMarkers) {
        Preconditions.checkNotNull(recoveryMarkers, "Recovery markers must not be null");
        Preconditions.checkArgument(
                !recoveryMarkers.isEmpty(), "Recovery markers must not be empty");

        String topic = null;
        String topicId = null;
        Map<Integer, Long> mergedOffsets = new HashMap<>();
        for (Object value : recoveryMarkers) {
            Preconditions.checkArgument(
                    value instanceof KafkaActionStateRecoveryMarker,
                    "Checkpoint-aligned cleanup requires versioned Kafka recovery markers; legacy or unsupported marker found: %s",
                    value == null ? "null" : value.getClass().getName());
            KafkaActionStateRecoveryMarker marker = (KafkaActionStateRecoveryMarker) value;
            Preconditions.checkArgument(
                    marker.getSchemaVersion()
                            == KafkaActionStateRecoveryMarker.CURRENT_SCHEMA_VERSION,
                    "Unsupported Kafka recovery marker schema %s",
                    marker.getSchemaVersion());
            if (topic == null) {
                topic = marker.getTopic();
                topicId = marker.getTopicId();
            } else {
                Preconditions.checkArgument(
                        topic.equals(marker.getTopic()) && topicId.equals(marker.getTopicId()),
                        "Recovery markers do not reference one physical Kafka topic");
                Preconditions.checkArgument(
                        mergedOffsets.keySet().equals(marker.getOffsets().keySet()),
                        "Recovery markers contain different Kafka partition sets");
            }
            marker.getOffsets()
                    .forEach(
                            (partition, offset) ->
                                    mergedOffsets.merge(partition, offset, Math::min));
        }

        return new KafkaActionStateCleanupPlan(
                CURRENT_SCHEMA_VERSION, null, sourceRecoveryPoint, topic, topicId, mergedOffsets);
    }

    /** Parses a previously reviewed plan and verifies its content-derived identifier. */
    public static KafkaActionStateCleanupPlan fromJson(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        Preconditions.checkArgument(root != null && root.isObject(), "Cleanup plan must be JSON");
        Set<String> fieldNames = new HashSet<>();
        root.fieldNames().forEachRemaining(fieldNames::add);
        Preconditions.checkArgument(
                fieldNames.equals(JSON_FIELDS),
                "Cleanup plan fields must be exactly %s, but were %s",
                JSON_FIELDS,
                fieldNames);
        Map<Integer, Long> offsets = new HashMap<>();
        JsonNode offsetsNode = required(root, "offsets");
        Preconditions.checkArgument(
                offsetsNode.isObject(), "Cleanup plan offsets must be an object");
        Iterator<Map.Entry<String, JsonNode>> fields = offsetsNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            try {
                Preconditions.checkArgument(
                        field.getValue().isIntegralNumber() && field.getValue().canConvertToLong(),
                        "Cleanup offset for partition %s must be an integer",
                        field.getKey());
                int partition = Integer.parseInt(field.getKey());
                Preconditions.checkArgument(
                        field.getKey().equals(Integer.toString(partition)),
                        "Cleanup plan partition %s must use canonical decimal form",
                        field.getKey());
                Long previous = offsets.put(partition, field.getValue().longValue());
                Preconditions.checkArgument(
                        previous == null,
                        "Cleanup plan contains duplicate Kafka partition %s",
                        partition);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Cleanup plan contains a non-integer Kafka partition: " + field.getKey(),
                        e);
            }
        }
        JsonNode schemaVersion = required(root, "schemaVersion");
        Preconditions.checkArgument(
                schemaVersion.isIntegralNumber() && schemaVersion.canConvertToInt(),
                "Cleanup plan schemaVersion must be an integer");
        return new KafkaActionStateCleanupPlan(
                schemaVersion.intValue(),
                requiredText(root, "planId"),
                requiredText(root, "sourceRecoveryPoint"),
                requiredText(root, "topic"),
                requiredText(root, "topicId"),
                offsets);
    }

    /** Returns deterministic JSON suitable for review and later application. */
    public String toJson() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", schemaVersion);
        value.put("planId", planId);
        value.put("sourceRecoveryPoint", sourceRecoveryPoint);
        value.put("topic", topic);
        value.put("topicId", topicId);
        value.put("offsets", new TreeMap<>(offsets));
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize Kafka action-state cleanup plan", e);
        }
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public String getPlanId() {
        return planId;
    }

    public String getSourceRecoveryPoint() {
        return sourceRecoveryPoint;
    }

    public String getTopic() {
        return topic;
    }

    public String getTopicId() {
        return topicId;
    }

    public Map<Integer, Long> getOffsets() {
        return offsets;
    }

    boolean advancesOrEquals(Map<Integer, Long> boundary) {
        if (boundary.isEmpty()) {
            return true;
        }
        if (!offsets.keySet().equals(boundary.keySet())) {
            return false;
        }
        return offsets.entrySet().stream()
                .allMatch(entry -> entry.getValue() >= boundary.get(entry.getKey()));
    }

    private static JsonNode required(JsonNode root, String name) {
        JsonNode value = root.get(name);
        Preconditions.checkArgument(
                value != null && !value.isNull(), "Cleanup plan is missing %s", name);
        return value;
    }

    private static String requiredText(JsonNode root, String name) {
        JsonNode value = required(root, name);
        Preconditions.checkArgument(value.isTextual(), "Cleanup plan %s must be a string", name);
        return value.textValue();
    }

    private static String requireText(String value, String name) {
        Preconditions.checkArgument(
                value != null && !value.trim().isEmpty(), "%s must not be blank", name);
        return value;
    }

    private static Map<Integer, Long> immutableOffsets(Map<Integer, Long> offsets) {
        Preconditions.checkNotNull(offsets, "Cleanup offsets must not be null");
        Preconditions.checkArgument(!offsets.isEmpty(), "Cleanup offsets must not be empty");
        Map<Integer, Long> copy = new HashMap<>();
        offsets.forEach(
                (partition, offset) -> {
                    Preconditions.checkNotNull(partition, "Kafka partition must not be null");
                    Preconditions.checkNotNull(offset, "Kafka cleanup offset must not be null");
                    Preconditions.checkArgument(
                            partition >= 0, "Kafka partition must be non-negative");
                    Preconditions.checkArgument(
                            offset >= 0, "Kafka cleanup offset must be non-negative");
                    copy.put(partition, offset);
                });
        return Collections.unmodifiableMap(copy);
    }

    private static String calculatePlanId(
            String sourceRecoveryPoint, String topic, String topicId, Map<Integer, Long> offsets) {
        StringBuilder canonical =
                new StringBuilder()
                        .append(CURRENT_SCHEMA_VERSION)
                        .append('\n')
                        .append(sourceRecoveryPoint)
                        .append('\n')
                        .append(topic)
                        .append('\n')
                        .append(topicId)
                        .append('\n');
        new TreeMap<>(offsets)
                .forEach(
                        (partition, offset) ->
                                canonical
                                        .append(partition)
                                        .append('=')
                                        .append(offset)
                                        .append('\n'));
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof KafkaActionStateCleanupPlan)) {
            return false;
        }
        KafkaActionStateCleanupPlan that = (KafkaActionStateCleanupPlan) object;
        return schemaVersion == that.schemaVersion
                && planId.equals(that.planId)
                && sourceRecoveryPoint.equals(that.sourceRecoveryPoint)
                && topic.equals(that.topic)
                && topicId.equals(that.topicId)
                && offsets.equals(that.offsets);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaVersion, planId, sourceRecoveryPoint, topic, topicId, offsets);
    }
}
