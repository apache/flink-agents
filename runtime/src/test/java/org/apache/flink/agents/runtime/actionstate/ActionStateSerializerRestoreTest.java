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
import org.apache.flink.api.java.typeutils.GenericTypeInfo;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Checks action-state compatibility with the serializer returned by a real keyed-state restore. */
class ActionStateSerializerRestoreTest {
    private static final int MAX_PARALLELISM = 128;
    private static final String TOPIC = "serializer-restore";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recoveryChecksSerializerReturnedByKeyedBackend(boolean registerKeyType) throws Exception {
        TypeSerializer<Object> previous =
                TypeInformation.of(Object.class).createSerializer(new SerializerConfigImpl());
        SerializerConfigImpl nextConfig = new SerializerConfigImpl();
        if (registerKeyType) {
            nextConfig.registerKryoType(TestKey.class);
        }
        TypeSerializer<Object> next = TypeInformation.of(Object.class).createSerializer(nextConfig);
        TestKey key = new TestKey(7);
        ActionStateKeyEncoder writer = new ActionStateKeyEncoder(MAX_PARALLELISM, previous);
        NoOpAction action = new NoOpAction("action");
        InputEvent event = new InputEvent("input");
        String stateKey = writer.generateKey(key, 1, action, event);
        ActionState completed = new ActionState(event);
        completed.markCompleted();

        OperatorSubtaskState checkpoint;
        try (var harness = harness(new KeyedStateProbe(), previous)) {
            harness.open();
            harness.processElement(new StreamRecord<>(key));
            checkpoint = harness.snapshot(1, 1);
        }

        KeyedStateProbe restoredOperator = new KeyedStateProbe();
        try (var harness = harness(restoredOperator, next)) {
            harness.initializeState(checkpoint);
            harness.open();
            // Heap state remains readable after Kryo registration changes.
            restoredOperator.setCurrentKey(key);
            assertThat(restoredOperator.value.value()).isEqualTo(42L);

            ActionStateKeyEncoder restored =
                    new ActionStateKeyEncoder(MAX_PARALLELISM, restoredOperator.keySerializer());
            if (registerKeyType) {
                assertThat(restored.generateBusinessKeyIdentity(key))
                        .isNotEqualTo(writer.generateBusinessKeyIdentity(key));
                // Validate foreign records as well: filtering must not hide incompatible state.
                assertThatThrownBy(() -> restored.isKeyRetained(group -> false, stateKey))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("serializer fingerprint");
            } else {
                assertThat(restored.generateKey(key, 1, action, event)).isEqualTo(stateKey);
            }

            Map<String, ActionState> cache = new HashMap<>();
            MockConsumer<String, ActionState> consumer = new MockConsumer<>("earliest");
            TopicPartition partition = new TopicPartition(TOPIC, 0);
            consumer.assign(List.of(partition));
            consumer.updateBeginningOffsets(Map.of(partition, 0L));
            consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 0, stateKey, completed));
            try (KafkaActionStateStore store =
                    new KafkaActionStateStore(
                            cache, new AgentConfiguration(), null, consumer, TOPIC, restored)) {
                if (registerKeyType) {
                    assertThatThrownBy(() -> store.rebuildState(List.of(Map.of(0, 0L))))
                            .isInstanceOf(RuntimeException.class)
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasStackTraceContaining("serializer fingerprint");
                    assertThat(cache).isEmpty();
                } else {
                    store.rebuildState(List.of(Map.of(0, 0L)));
                    assertThat(store.get(key, 1, action, event)).isSameAs(completed);
                    store.pruneState(key, 1);
                    assertThat(cache).isEmpty();
                }
            }
        } finally {
            checkpoint.discardState();
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

        private TypeSerializer<?> keySerializer() {
            return getKeyedStateBackend().getKeySerializer();
        }
    }

    public static final class TestKey implements Serializable {
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
