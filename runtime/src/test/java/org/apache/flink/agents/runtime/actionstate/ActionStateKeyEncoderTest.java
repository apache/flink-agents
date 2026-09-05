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
import org.apache.flink.api.common.serialization.SerializerConfigImpl;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.common.typeutils.TypeSerializerSchemaCompatibility;
import org.apache.flink.api.common.typeutils.TypeSerializerSnapshot;
import org.apache.flink.api.common.typeutils.base.LongSerializer;
import org.apache.flink.core.memory.DataInputView;
import org.apache.flink.core.memory.DataOutputView;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Serializable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Tests for {@link ActionStateKeyEncoder}. */
class ActionStateKeyEncoderTest {

    private static final int MAX_PARALLELISM = 128;

    @Test
    void keysAreStableAcrossIndependentLongSerializers() throws Exception {
        ActionStateKeyEncoder first =
                new ActionStateKeyEncoder(
                        MAX_PARALLELISM,
                        TypeInformation.of(Long.class)
                                .createSerializer(new SerializerConfigImpl()));
        ActionStateKeyEncoder restored =
                new ActionStateKeyEncoder(
                        MAX_PARALLELISM,
                        TypeInformation.of(Long.class)
                                .createSerializer(new SerializerConfigImpl()));
        String stateKey =
                first.generateKey(1L, 1L, new NoOpAction("action"), new InputEvent("input"));

        assertThat(restored.generateKey(1L, 1L, new NoOpAction("action"), new InputEvent("input")))
                .isEqualTo(stateKey);
        assertThat(restored.isKeyRetained(keyGroup -> true, stateKey)).isTrue();
    }

    @Test
    void keysAreStableAcrossIndependentGenericSerializers() throws Exception {
        ActionStateKeyEncoder first =
                new ActionStateKeyEncoder(
                        MAX_PARALLELISM,
                        TypeInformation.of(Object.class)
                                .createSerializer(new SerializerConfigImpl()));
        ActionStateKeyEncoder restored =
                new ActionStateKeyEncoder(
                        MAX_PARALLELISM,
                        TypeInformation.of(Object.class)
                                .createSerializer(new SerializerConfigImpl()));
        String stateKey =
                first.generateKey("key", 1L, new NoOpAction("action"), new InputEvent("input"));

        assertThat(
                        restored.generateKey(
                                "key", 1L, new NoOpAction("action"), new InputEvent("input")))
                .isEqualTo(stateKey);
        assertThat(restored.isKeyRetained(keyGroup -> true, stateKey)).isTrue();
    }

    /**
     * A changed snapshot is conservatively rejected even when a particular key's bytes stay equal.
     */
    @Test
    void recoveryRejectsChangedSnapshotEvenWhenKeyBytesStayEqual() throws Exception {
        TypeSerializer<Object> before =
                TypeInformation.of(Object.class).createSerializer(new SerializerConfigImpl());
        SerializerConfigImpl reconfigured = new SerializerConfigImpl();
        reconfigured.registerKryoType(UnrelatedRegisteredType.class);
        TypeSerializer<Object> after =
                TypeInformation.of(Object.class).createSerializer(reconfigured);
        var compatibility =
                after.snapshotConfiguration()
                        .resolveSchemaCompatibility(before.snapshotConfiguration());
        assertThat(compatibility.isCompatibleWithReconfiguredSerializer()).isTrue();

        ActionStateKeyEncoder writer = new ActionStateKeyEncoder(MAX_PARALLELISM, before);
        ActionStateKeyEncoder restored =
                new ActionStateKeyEncoder(
                        MAX_PARALLELISM, compatibility.getReconfiguredSerializer());
        String stateKey =
                writer.generateKey("key", 1L, new NoOpAction("action"), new InputEvent("input"));

        assertThat(restored.generateBusinessKeyIdentity("key"))
                .isEqualTo(writer.generateBusinessKeyIdentity("key"));
        assertThatThrownBy(() -> restored.isKeyRetained(keyGroup -> true, stateKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("serializer fingerprint");
    }

    @Test
    @SuppressWarnings("unchecked")
    void serializerSnapshotFailureIsReported() throws Exception {
        TypeSerializer<Object> serializer = mock(TypeSerializer.class);
        TypeSerializerSnapshot<Object> snapshot = mock(TypeSerializerSnapshot.class);
        IOException failure = new IOException("snapshot write failed");
        when(serializer.duplicate()).thenReturn(serializer);
        when(serializer.snapshotConfiguration()).thenReturn(snapshot);
        doThrow(failure).when(snapshot).writeSnapshot(any());

        assertThatThrownBy(() -> new ActionStateKeyEncoder(MAX_PARALLELISM, serializer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to fingerprint the Flink key serializer")
                .hasCause(failure);
    }

    @Test
    void differentKeyTypesProduceDifferentIdentities() {
        ActionStateKeyEncoder longEncoder =
                new ActionStateKeyEncoder(MAX_PARALLELISM, LongSerializer.INSTANCE);
        ActionStateKeyEncoder genericEncoder =
                new ActionStateKeyEncoder(
                        MAX_PARALLELISM,
                        TypeInformation.of(Object.class)
                                .createSerializer(new SerializerConfigImpl()));

        assertThat(longEncoder.generateBusinessKeyIdentity(1L))
                .isNotEqualTo(genericEncoder.generateBusinessKeyIdentity(1L));
    }

    @Test
    void businessKeySerializationFailureIsReported() {
        ActionStateKeyEncoder encoder =
                new ActionStateKeyEncoder(MAX_PARALLELISM, new FailingKeySerializer());

        assertThatThrownBy(() -> encoder.generateBusinessKeyIdentity("key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to serialize the Flink key")
                .hasCauseInstanceOf(IOException.class);
    }

    public static class UnrelatedRegisteredType implements Serializable {
        public int value;
    }

    private static final class FailingKeySerializer extends TypeSerializer<Object> {

        private static final long serialVersionUID = 1L;

        @Override
        public boolean isImmutableType() {
            return true;
        }

        @Override
        public TypeSerializer<Object> duplicate() {
            return new FailingKeySerializer();
        }

        @Override
        public Object createInstance() {
            return "";
        }

        @Override
        public Object copy(Object from) {
            return from;
        }

        @Override
        public Object copy(Object from, Object reuse) {
            return from;
        }

        @Override
        public int getLength() {
            return -1;
        }

        @Override
        public void serialize(Object record, DataOutputView target) throws IOException {
            throw new IOException("key serialization failed");
        }

        @Override
        public Object deserialize(DataInputView source) throws IOException {
            return source.readUTF();
        }

        @Override
        public Object deserialize(Object reuse, DataInputView source) throws IOException {
            return deserialize(source);
        }

        @Override
        public void copy(DataInputView source, DataOutputView target) throws IOException {
            target.writeUTF(source.readUTF());
        }

        @Override
        public TypeSerializerSnapshot<Object> snapshotConfiguration() {
            return new FailingKeySerializerSnapshot();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof FailingKeySerializer;
        }

        @Override
        public int hashCode() {
            return FailingKeySerializer.class.hashCode();
        }
    }

    public static final class FailingKeySerializerSnapshot
            implements TypeSerializerSnapshot<Object> {

        @Override
        public int getCurrentVersion() {
            return 1;
        }

        @Override
        public void writeSnapshot(DataOutputView out) {}

        @Override
        public void readSnapshot(int readVersion, DataInputView in, ClassLoader classLoader) {}

        @Override
        public TypeSerializer<Object> restoreSerializer() {
            return new FailingKeySerializer();
        }

        @Override
        public TypeSerializerSchemaCompatibility<Object> resolveSchemaCompatibility(
                TypeSerializerSnapshot<Object> oldSerializerSnapshot) {
            return TypeSerializerSchemaCompatibility.compatibleAsIs();
        }
    }
}
