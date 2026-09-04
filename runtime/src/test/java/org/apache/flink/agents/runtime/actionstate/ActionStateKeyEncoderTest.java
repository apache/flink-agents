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
import org.apache.flink.core.memory.DataInputView;
import org.apache.flink.core.memory.DataOutputView;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link ActionStateKeyEncoder}. */
class ActionStateKeyEncoderTest {

    private static final int MAX_PARALLELISM = 128;

    @Test
    void serializerFingerprintIsStableAcrossEquivalentInstances() {
        ActionStateKeyEncoder first =
                new ActionStateKeyEncoder(MAX_PARALLELISM, new TestKeySerializer(1));
        ActionStateKeyEncoder second =
                new ActionStateKeyEncoder(MAX_PARALLELISM, new TestKeySerializer(1));

        assertThat(first.getSerializerFingerprint()).isEqualTo(second.getSerializerFingerprint());
    }

    @Test
    void fingerprintIsStableAcrossIndependentLongSerializers() throws Exception {
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

        assertThat(restored.getSerializerFingerprint()).isEqualTo(first.getSerializerFingerprint());
        assertThat(
                        restored.isKeyRetained(
                                keyGroup -> true,
                                first.generateKey(
                                        1L, 1L, new NoOpAction("action"), new InputEvent("input"))))
                .isTrue();
    }

    @Test
    void fingerprintIsStableAcrossIndependentGenericSerializers() throws Exception {
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

        assertThat(restored.getSerializerFingerprint()).isEqualTo(first.getSerializerFingerprint());
        assertThat(
                        restored.isKeyRetained(
                                keyGroup -> true,
                                first.generateKey(
                                        "key",
                                        1L,
                                        new NoOpAction("action"),
                                        new InputEvent("input"))))
                .isTrue();
    }

    @Test
    void recoveryRejectsSerializerThatRequiresMigration() throws Exception {
        TestKeySerializer previousSerializer = new TestKeySerializer(1);
        TestKeySerializer changedSerializer = new TestKeySerializer(2);
        assertThat(
                        changedSerializer
                                .snapshotConfiguration()
                                .resolveSchemaCompatibility(
                                        previousSerializer.snapshotConfiguration())
                                .isCompatibleAfterMigration())
                .isTrue();

        ActionStateKeyEncoder writer =
                new ActionStateKeyEncoder(MAX_PARALLELISM, previousSerializer);
        String stateKey =
                writer.generateKey("key", 1L, new NoOpAction("action"), new InputEvent("input"));
        ActionStateKeyEncoder restored =
                new ActionStateKeyEncoder(MAX_PARALLELISM, changedSerializer);

        assertThatThrownBy(() -> restored.isKeyRetained(keyGroup -> true, stateKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("serializer fingerprint");
    }

    @Test
    void businessKeySerializationFailureIsReported() {
        ActionStateKeyEncoder encoder =
                new ActionStateKeyEncoder(MAX_PARALLELISM, new TestKeySerializer(1, true, false));

        assertThatThrownBy(() -> encoder.generateBusinessKeyIdentity("key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to serialize the Flink key")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void serializerSnapshotFailureIsReported() {
        assertThatThrownBy(
                        () ->
                                new ActionStateKeyEncoder(
                                        MAX_PARALLELISM, new TestKeySerializer(1, false, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to fingerprint the Flink key serializer")
                .hasCauseInstanceOf(IOException.class);
    }

    private static final class TestKeySerializer extends TypeSerializer<Object> {

        private static final long serialVersionUID = 1L;

        private final int encodingVersion;
        private final boolean failSerialization;
        private final boolean failSnapshot;

        private TestKeySerializer(int encodingVersion) {
            this(encodingVersion, false, false);
        }

        private TestKeySerializer(
                int encodingVersion, boolean failSerialization, boolean failSnapshot) {
            this.encodingVersion = encodingVersion;
            this.failSerialization = failSerialization;
            this.failSnapshot = failSnapshot;
        }

        @Override
        public boolean isImmutableType() {
            return true;
        }

        @Override
        public TypeSerializer<Object> duplicate() {
            return new TestKeySerializer(encodingVersion, failSerialization, failSnapshot);
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
            if (failSerialization) {
                throw new IOException("key serialization failed");
            }
            target.writeInt(encodingVersion);
            target.writeUTF(record.toString());
        }

        @Override
        public Object deserialize(DataInputView source) throws IOException {
            source.readInt();
            return source.readUTF();
        }

        @Override
        public Object deserialize(Object reuse, DataInputView source) throws IOException {
            return deserialize(source);
        }

        @Override
        public void copy(DataInputView source, DataOutputView target) throws IOException {
            target.writeInt(source.readInt());
            target.writeUTF(source.readUTF());
        }

        @Override
        public TypeSerializerSnapshot<Object> snapshotConfiguration() {
            return new TestKeySerializerSnapshot(encodingVersion, failSnapshot);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TestKeySerializer
                    && encodingVersion == ((TestKeySerializer) other).encodingVersion;
        }

        @Override
        public int hashCode() {
            return encodingVersion;
        }
    }

    public static final class TestKeySerializerSnapshot implements TypeSerializerSnapshot<Object> {

        private int encodingVersion;
        private boolean failWrite;

        public TestKeySerializerSnapshot() {}

        private TestKeySerializerSnapshot(int encodingVersion) {
            this(encodingVersion, false);
        }

        private TestKeySerializerSnapshot(int encodingVersion, boolean failWrite) {
            this.encodingVersion = encodingVersion;
            this.failWrite = failWrite;
        }

        @Override
        public int getCurrentVersion() {
            return 1;
        }

        @Override
        public void writeSnapshot(DataOutputView out) throws IOException {
            if (failWrite) {
                throw new IOException("serializer snapshot failed");
            }
            out.writeInt(encodingVersion);
        }

        @Override
        public void readSnapshot(int readVersion, DataInputView in, ClassLoader userCodeClassLoader)
                throws IOException {
            encodingVersion = in.readInt();
        }

        @Override
        public TypeSerializer<Object> restoreSerializer() {
            return new TestKeySerializer(encodingVersion);
        }

        @Override
        public TypeSerializerSchemaCompatibility<Object> resolveSchemaCompatibility(
                TypeSerializerSnapshot<Object> oldSerializerSnapshot) {
            if (!(oldSerializerSnapshot instanceof TestKeySerializerSnapshot)) {
                return TypeSerializerSchemaCompatibility.incompatible();
            }
            TestKeySerializerSnapshot previous = (TestKeySerializerSnapshot) oldSerializerSnapshot;
            return encodingVersion == previous.encodingVersion
                    ? TypeSerializerSchemaCompatibility.compatibleAsIs()
                    : TypeSerializerSchemaCompatibility.compatibleAfterMigration();
        }
    }
}
