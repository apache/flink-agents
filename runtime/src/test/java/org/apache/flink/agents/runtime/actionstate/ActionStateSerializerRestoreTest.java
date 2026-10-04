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

import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.api.common.serialization.SerializerConfig;
import org.apache.flink.api.common.serialization.SerializerConfigImpl;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.common.typeutils.TypeSerializerSnapshotSerializationUtil;
import org.apache.flink.api.java.typeutils.GenericTypeInfo;
import org.apache.flink.core.memory.DataOutputSerializer;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks action-state compatibility with the serializer returned by a real keyed-state restore. */
class ActionStateSerializerRestoreTest {
    private static final int MAX_PARALLELISM = 128;
    private static final String TOPIC = "serializer-restore";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recoveryKeepsCompletedStateAfterPojoSubclassCacheChanges(boolean useSubclass)
            throws Exception {
        TypeSerializer<Object> previous = pojoSerializer();
        TypeSerializer<Object> next = pojoSerializer();
        TestKey key = useSubclass ? new SubclassKey(7) : new TestKey(7);
        byte[] initialSnapshot = snapshotBytes(previous);
        ActionStateKeyEncoder writer = new ActionStateKeyEncoder(MAX_PARALLELISM, previous);
        NoOpAction action = new NoOpAction("action");
        InputEvent event = new InputEvent("input");
        String stateKey = writer.generateKey(key, 1, action, event);
        ActionState completed = new ActionState(event);
        completed.markCompleted();

        OperatorSubtaskState checkpoint;
        KeyedStateProbe originalOperator = new KeyedStateProbe();
        try (var harness = harness(originalOperator, previous)) {
            harness.open();
            harness.processElement(new StreamRecord<>(key));
            // Populate the backend serializer's subclass cache before checkpointing, as occurs
            // when a backend serializes keys during normal processing.
            originalOperator.keySerializer().serialize(key, new DataOutputSerializer(64));
            checkpoint = harness.snapshot(1, 1);
        }

        KeyedStateProbe restoredOperator = new KeyedStateProbe();
        try (var harness = harness(restoredOperator, next)) {
            harness.initializeState(checkpoint);
            harness.open();
            // Restore the same job and serializer configuration.
            restoredOperator.setCurrentKey(key);
            assertThat(restoredOperator.value.value()).isEqualTo(42L);

            ActionStateKeyEncoder restored =
                    new ActionStateKeyEncoder(MAX_PARALLELISM, restoredOperator.keySerializer());
            assertThat(restored.generateKey(key, 1, action, event)).isEqualTo(stateKey);
            if (useSubclass) {
                assertThat(snapshotBytes(restoredOperator.keySerializer()))
                        .isNotEqualTo(initialSnapshot);
            }

            Map<String, ActionState> cache = new HashMap<>();
            MockConsumer<String, ActionState> consumer = new MockConsumer<>("earliest");
            TopicPartition partition = new TopicPartition(TOPIC, 0);
            consumer.updatePartitions(
                    TOPIC, List.of(new PartitionInfo(TOPIC, 0, null, null, null)));
            consumer.updateEndOffsets(Map.of(partition, 1L));
            consumer.assign(List.of(partition));
            consumer.updateBeginningOffsets(Map.of(partition, 0L));
            consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 0, stateKey, completed));
            try (KafkaActionStateStore store =
                    new KafkaActionStateStore(
                            cache, new AgentConfiguration(), null, consumer, TOPIC, restored)) {
                store.rebuildState(List.of(Map.of(0, 0L)));
                assertThat(store.get(key, 1, action, event)).isSameAs(completed);
                store.pruneState(key, 1);
                assertThat(cache).isEmpty();
            }
        } finally {
            checkpoint.discardState();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TypeSerializer<Object> pojoSerializer() {
        return (TypeSerializer)
                TypeInformation.of(TestKey.class).createSerializer(new SerializerConfigImpl());
    }

    private static byte[] snapshotBytes(TypeSerializer<?> serializer) throws Exception {
        DataOutputSerializer output = new DataOutputSerializer(128);
        TypeSerializerSnapshotSerializationUtil.writeSerializerSnapshot(
                output, serializer.snapshotConfiguration());
        return output.getCopyOfBuffer();
    }

    public static class SubclassKey extends TestKey {
        public String extra = "subclass";

        public SubclassKey() {}

        SubclassKey(int value) {
            super(value);
        }
    }

    private static KeyedOneInputStreamOperatorTestHarness<Object, Object, Object> harness(
            KeyedStateProbe operator, TypeSerializer<Object> serializer) throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(
                operator, key -> key, new SerializerTypeInfo(serializer), MAX_PARALLELISM, 1, 0);
    }

    private static final class SerializerTypeInfo extends GenericTypeInfo<Object> {
        private final TypeSerializer<Object> serializer;

        private SerializerTypeInfo(TypeSerializer<Object> serializer) {
            super(Object.class);
            this.serializer = serializer;
        }

        @Override
        public TypeSerializer<Object> createSerializer(SerializerConfig config) {
            return serializer.duplicate();
        }
    }

    private static final class KeyedStateProbe extends AbstractStreamOperator<Object>
            implements OneInputStreamOperator<Object, Object> {
        private ValueState<Long> value;

        @Override
        public void open() throws Exception {
            super.open();
            value = getRuntimeContext().getState(new ValueStateDescriptor<>("value", Long.class));
        }

        @Override
        public void processElement(StreamRecord<Object> record) throws Exception {
            value.update(42L);
        }

        private TypeSerializer<Object> keySerializer() {
            return getKeyedStateBackend().getKeySerializer();
        }
    }

    public static class TestKey implements Serializable {
        public int value;

        public TestKey() {}

        TestKey(int value) {
            this.value = value;
        }

        @Override
        public int hashCode() {
            return value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TestKey && value == ((TestKey) other).value;
        }
    }
}
