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

import org.apache.flink.api.common.serialization.SerializerConfigImpl;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.core.memory.DataInputDeserializer;
import org.apache.flink.core.memory.DataOutputSerializer;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/** Tests for {@link KafkaActionStateRecoveryMarker}. */
class KafkaActionStateRecoveryMarkerTest {

    @Test
    void testFlinkStateSerializationRoundTrip() throws Exception {
        KafkaActionStateRecoveryMarker marker =
                new KafkaActionStateRecoveryMarker(
                        "action-state", "topic-id", Map.of(0, 10L, 1, 20L));
        TypeSerializer<Object> serializer =
                TypeInformation.of(Object.class).createSerializer(new SerializerConfigImpl());
        DataOutputSerializer output = new DataOutputSerializer(256);

        serializer.serialize(marker, output);
        Object restored =
                serializer.deserialize(new DataInputDeserializer(output.getCopyOfBuffer()));

        assertThat(restored).isEqualTo(marker);
    }

    @Test
    void testOffsetsAreDefensivelyCopied() {
        Map<Integer, Long> offsets = new HashMap<>(Map.of(0, 10L));
        KafkaActionStateRecoveryMarker marker =
                new KafkaActionStateRecoveryMarker("action-state", "topic-id", offsets);

        offsets.put(1, 20L);

        assertThat(marker.getOffsets()).containsExactly(entry(0, 10L));
        assertThatThrownBy(() -> marker.getOffsets().put(1, 20L))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
