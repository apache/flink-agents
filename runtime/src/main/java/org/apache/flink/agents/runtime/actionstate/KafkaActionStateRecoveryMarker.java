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

import org.apache.flink.annotation.Internal;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;

/** Versioned Kafka topic identity and per-partition offsets used to rebuild action state. */
@Internal
public final class KafkaActionStateRecoveryMarker implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String UNION_STATE_NAME = "recoveryMarker";

    private final int schemaVersion;
    private final String topic;
    private final String topicId;
    private final Map<Integer, Long> offsets;

    KafkaActionStateRecoveryMarker(String topic, String topicId, Map<Integer, Long> offsets) {
        this(CURRENT_SCHEMA_VERSION, topic, topicId, offsets);
    }

    KafkaActionStateRecoveryMarker(
            int schemaVersion, String topic, String topicId, Map<Integer, Long> offsets) {
        this.schemaVersion = schemaVersion;
        this.topic = checkNotNull(topic, "Kafka topic must not be null");
        this.topicId = checkNotNull(topicId, "Kafka topic ID must not be null");
        checkArgument(!topic.trim().isEmpty(), "Kafka topic must not be blank");
        checkArgument(!topicId.trim().isEmpty(), "Kafka topic ID must not be blank");
        checkNotNull(offsets, "Kafka recovery offsets must not be null");
        checkArgument(!offsets.isEmpty(), "Kafka recovery offsets must not be empty");
        offsets.forEach(
                (partition, offset) -> {
                    checkNotNull(partition, "Kafka partition must not be null");
                    checkNotNull(offset, "Kafka recovery offset must not be null");
                    checkArgument(partition >= 0, "Kafka partition must be non-negative");
                    checkArgument(offset >= 0, "Kafka recovery offset must be non-negative");
                });
        this.offsets = new HashMap<>(offsets);
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public String getTopic() {
        return topic;
    }

    public String getTopicId() {
        return topicId;
    }

    public Map<Integer, Long> getOffsets() {
        return Collections.unmodifiableMap(offsets);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof KafkaActionStateRecoveryMarker)) {
            return false;
        }
        KafkaActionStateRecoveryMarker that = (KafkaActionStateRecoveryMarker) object;
        return schemaVersion == that.schemaVersion
                && topic.equals(that.topic)
                && topicId.equals(that.topicId)
                && offsets.equals(that.offsets);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaVersion, topic, topicId, offsets);
    }

    @Override
    public String toString() {
        return "KafkaActionStateRecoveryMarker{"
                + "schemaVersion="
                + schemaVersion
                + ", topic='"
                + topic
                + '\''
                + ", topicId='"
                + topicId
                + '\''
                + ", offsets="
                + offsets
                + '}';
    }
}
