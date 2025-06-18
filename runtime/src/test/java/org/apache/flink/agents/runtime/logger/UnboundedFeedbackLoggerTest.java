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

import org.apache.flink.api.common.typeutils.base.IntSerializer;
import org.apache.flink.core.memory.DataInputViewStreamWrapper;
import org.apache.flink.core.memory.DataOutputSerializer;
import org.apache.flink.runtime.io.disk.iomanager.IOManagerAsync;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NOTE: This source code was copied from the <a
 * href="https://github.com/apache/flink-statefun">flink-statefun</a>.
 *
 * <p>Tests for {@link UnboundedFeedbackLogger}.
 */
@SuppressWarnings("SameParameterValue")
public class UnboundedFeedbackLoggerTest {
    private static IOManagerAsync IO_MANAGER;

    @BeforeAll
    public static void beforeClass() {
        IO_MANAGER = new IOManagerAsync();
    }

    @AfterAll
    public static void afterClass() throws Exception {
        if (IO_MANAGER != null) {
            IO_MANAGER.close();
            IO_MANAGER = null;
        }
    }

    @Test
    public void sanity() {
        UnboundedFeedbackLogger<Integer> logger = instanceUnderTest(128, 1);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        logger.startLogging(output);
        logger.commit();

        assertTrue(output.size() > 0);
    }

    @Test
    public void roundTrip() throws Exception {
        roundTrip(100, 1024);
    }

    @Test
    public void roundTripWithoutElements() throws Exception {
        roundTrip(0, 1024);
    }

    @Test
    public void roundTripWithHeader() throws IOException {
        DataOutputSerializer out = new DataOutputSerializer(32);
        UnboundedFeedbackLogger.Header.writeHeader(out);
        out.writeInt(123);
        out.writeInt(456);
        InputStream in = new RandomReadLengthByteArrayInputStream(out.getCopyOfBuffer());

        DataInputViewStreamWrapper view =
                new DataInputViewStreamWrapper(
                        UnboundedFeedbackLogger.Header.skipHeaderSilently(in));

        assertEquals(view.readInt(), 123);
        assertEquals(view.readInt(), 456);
    }

    @Test
    public void roundTripWithoutHeader() throws IOException {
        DataOutputSerializer out = new DataOutputSerializer(32);
        out.writeInt(123);
        out.writeInt(456);
        InputStream in = new RandomReadLengthByteArrayInputStream(out.getCopyOfBuffer());

        DataInputViewStreamWrapper view =
                new DataInputViewStreamWrapper(
                        UnboundedFeedbackLogger.Header.skipHeaderSilently(in));

        assertEquals(view.readInt(), 123);
        assertEquals(view.readInt(), 456);
    }

    @Test
    public void emptyKeyGroupWithHeader() throws IOException {
        DataOutputSerializer out = new DataOutputSerializer(32);
        UnboundedFeedbackLogger.Header.writeHeader(out);
        InputStream in = new RandomReadLengthByteArrayInputStream(out.getCopyOfBuffer());

        DataInputViewStreamWrapper view =
                new DataInputViewStreamWrapper(
                        UnboundedFeedbackLogger.Header.skipHeaderSilently(in));

        assertEquals(view.read(), -1);
    }

    @Test
    public void emptyKeyGroupWithoutHeader() throws IOException {
        InputStream in = new RandomReadLengthByteArrayInputStream(new byte[0]);
        DataInputViewStreamWrapper view =
                new DataInputViewStreamWrapper(
                        UnboundedFeedbackLogger.Header.skipHeaderSilently(in));
        assertEquals(view.read(), -1);
    }

    private void roundTrip(int numElements, int maxMemoryInBytes) throws Exception {
        InputStream input = serializeKeyGroup(1, maxMemoryInBytes, numElements);

        ArrayList<Integer> messages = new ArrayList<>();

        UnboundedFeedbackLogger<Integer> loggerUnderTest = instanceUnderTest(1, 0);
        loggerUnderTest.replyLoggedEnvelops(input, messages::add);

        for (int i = 0; i < numElements; i++) {
            Integer message = messages.get(i);
            assertEquals(message, i);
        }
    }

    private ByteArrayInputStream serializeKeyGroup(
            int maxParallelism, long maxMemory, int numItems) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        UnboundedFeedbackLogger<Integer> loggerUnderTest =
                instanceUnderTest(maxParallelism, maxMemory);

        loggerUnderTest.startLogging(output);

        for (int i = 0; i < numItems; i++) {
            loggerUnderTest.append(i);
        }

        loggerUnderTest.commit();

        return new ByteArrayInputStream(output.toByteArray());
    }

    @SuppressWarnings("unchecked")
    private UnboundedFeedbackLogger<Integer> instanceUnderTest(
            int maxParallelism, long totalMemory) {

        UnboundedFeedbackLoggerFactory<?> factory =
                Loggers.unboundedSpillableLoggerFactory(
                        IO_MANAGER,
                        maxParallelism,
                        totalMemory,
                        IntSerializer.INSTANCE,
                        Function.identity());
        factory.setCheckpointedStreamOperations(new NoopStreamOps(maxParallelism));

        return (UnboundedFeedbackLogger<Integer>) factory.create();
    }

    static final class NoopStreamOps implements CheckpointedStreamOperations {
        private final int maxParallelism;

        NoopStreamOps(int maxParallelism) {
            this.maxParallelism = maxParallelism;
        }

        @Override
        public void requireKeyedStateCheckpointed(OutputStream keyedStateCheckpointOutputStream) {
            // noop
        }

        @Override
        public Iterable<Integer> keyGroupList(OutputStream stream) {
            IntStream range = IntStream.range(0, maxParallelism);
            return range::iterator;
        }

        @Override
        public void startNewKeyGroup(OutputStream stream, int keyGroup) {}

        @Override
        public Closeable acquireLease(OutputStream keyedStateCheckpointOutputStream) {
            return () -> {}; // NOOP
        }
    }
}
