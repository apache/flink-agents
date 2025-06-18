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
package org.apache.flink.agents.runtime.logger;

import org.apache.flink.agents.runtime.feedback.FeedbackConsumer;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.core.memory.DataInputView;
import org.apache.flink.core.memory.DataInputViewStreamWrapper;
import org.apache.flink.core.memory.DataOutputSerializer;
import org.apache.flink.core.memory.DataOutputView;
import org.apache.flink.core.memory.DataOutputViewStreamWrapper;
import org.apache.flink.util.IOUtils;
import org.apache.flink.util.Preconditions;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import static org.apache.flink.util.Preconditions.checkState;

/**
 * NOTE: This source code was copied from the <a
 * href="https://github.com/apache/flink-statefun">flink-statefun</a>
 *
 * <p>A {@link FeedbackLogger} that logs feedback messages to a keyed state checkpoint output
 * stream.
 */
public final class UnboundedFeedbackLogger<T> implements FeedbackLogger<T> {
    private final Supplier<KeyGroupStream<T>> supplier;
    private final ToIntFunction<T> keyGroupAssigner;
    private final Map<Integer, KeyGroupStream<T>> keyGroupStreams;
    private final CheckpointedStreamOperations checkpointedStreamOperations;
    private final TypeSerializer<T> serializer;
    private OutputStream keyedStateOutputStream;
    private Closeable snapshotLease;

    public UnboundedFeedbackLogger(
            Supplier<KeyGroupStream<T>> supplier,
            ToIntFunction<T> keyGroupAssigner,
            CheckpointedStreamOperations ops,
            TypeSerializer<T> serializer) {
        this.supplier = Objects.requireNonNull(supplier);
        this.keyGroupAssigner = Objects.requireNonNull(keyGroupAssigner);
        this.serializer = Objects.requireNonNull(serializer);
        this.keyGroupStreams = new TreeMap<>();
        this.checkpointedStreamOperations = Objects.requireNonNull(ops);
    }

    @Override
    public void startLogging(OutputStream keyedStateCheckpointOutputStream) {
        this.checkpointedStreamOperations.requireKeyedStateCheckpointed(
                keyedStateCheckpointOutputStream);
        this.keyedStateOutputStream = Objects.requireNonNull(keyedStateCheckpointOutputStream);
        this.snapshotLease =
                checkpointedStreamOperations.acquireLease(keyedStateCheckpointOutputStream);
    }

    @Override
    public void append(T message) {
        if (keyedStateOutputStream == null) {
            //
            // we are not currently logging.
            //
            return;
        }
        KeyGroupStream<T> keyGroup = keyGroupStreamFor(message);
        keyGroup.append(message);
    }

    @Override
    public void commit() {
        try {
            flushToKeyedStateOutputStream();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            keyGroupStreams.clear();
            IOUtils.closeQuietly(snapshotLease);
            snapshotLease = null;
            keyedStateOutputStream = null;
        }
    }

    private void flushToKeyedStateOutputStream() throws IOException {
        checkState(
                keyedStateOutputStream != null, "Trying to flush envelopes not in a logging state");

        final DataOutputView target = new DataOutputViewStreamWrapper(keyedStateOutputStream);
        final Iterable<Integer> assignedKeyGroupIds =
                checkpointedStreamOperations.keyGroupList(keyedStateOutputStream);
        // the underlying checkpointed raw stream, requires that all key groups assigned
        // to this operator must be written to the underlying stream.
        for (Integer keyGroupId : assignedKeyGroupIds) {
            checkpointedStreamOperations.startNewKeyGroup(keyedStateOutputStream, keyGroupId);
            Header.writeHeader(target);

            KeyGroupStream<T> stream = keyGroupStreams.get(keyGroupId);
            if (stream == null) {
                KeyGroupStream.writeEmptyTo(target);
            } else {
                stream.writeTo(target);
            }
        }
    }

    public void replyLoggedEnvelops(InputStream rawKeyedStateInputs, FeedbackConsumer<T> consumer)
            throws Exception {
        DataInputView in =
                new DataInputViewStreamWrapper(Header.skipHeaderSilently(rawKeyedStateInputs));
        KeyGroupStream.readFrom(in, serializer, consumer);
    }

    private KeyGroupStream<T> keyGroupStreamFor(T target) {
        final int keyGroupId = keyGroupAssigner.applyAsInt(target);
        KeyGroupStream<T> keyGroup = keyGroupStreams.get(keyGroupId);
        if (keyGroup == null) {
            keyGroupStreams.put(keyGroupId, keyGroup = supplier.get());
        }
        return keyGroup;
    }

    @Override
    public void close() {
        IOUtils.closeQuietly(snapshotLease);
        snapshotLease = null;
        keyedStateOutputStream = null;
        keyGroupStreams.clear();
    }

    public static final class Header {
        private static final int VERSION = 0;
        private static final int MAGIC = 710818519;
        private static final byte[] HEADER_BYTES = headerBytes();

        public static void writeHeader(DataOutputView target) throws IOException {
            target.write(HEADER_BYTES);
        }

        public static InputStream skipHeaderSilently(InputStream rawKeyedInput) throws IOException {
            byte[] header = new byte[HEADER_BYTES.length];
            PushbackInputStream input = new PushbackInputStream(rawKeyedInput, header.length);
            int bytesRead = tryReadFully(input, header);
            if (bytesRead > 0 && !Arrays.equals(header, HEADER_BYTES)) {
                input.unread(header, 0, bytesRead);
            }
            return input;
        }

        private static byte[] headerBytes() {
            DataOutputSerializer out = new DataOutputSerializer(8);
            try {
                out.writeInt(VERSION);
                out.writeInt(MAGIC);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to compute the header bytes");
            }
            return out.getCopyOfBuffer();
        }

        private static int tryReadFully(final InputStream in, final byte[] readBuffer)
                throws IOException {
            Preconditions.checkState(
                    readBuffer.length > 0, "read buffer size must be larger than 0.");

            int totalRead = 0;
            while (totalRead != readBuffer.length) {
                int read = in.read(readBuffer, totalRead, readBuffer.length - totalRead);
                if (read == -1) {
                    break;
                }
                totalRead += read;
            }
            return totalRead;
        }
    }
}
