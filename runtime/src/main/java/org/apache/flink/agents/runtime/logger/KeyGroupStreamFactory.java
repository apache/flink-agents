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

import org.apache.flink.agents.runtime.memory.MemorySegmentPool;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.runtime.io.disk.iomanager.IOManager;

import java.util.function.Supplier;

/**
 * NOTE: This source code was copied from the <a
 * href="https://github.com/apache/flink-statefun">flink-statefun</a>
 *
 * <p>A factory for {@link KeyGroupStream}.
 */
public final class KeyGroupStreamFactory<T> implements Supplier<KeyGroupStream<T>> {
    private final IOManager ioManager;
    private final MemorySegmentPool memorySegmentPool;
    private final TypeSerializer<T> serializer;

    public KeyGroupStreamFactory(
            IOManager ioManager, long inMemoryBufferSize, TypeSerializer<T> serializer) {
        this.ioManager = ioManager;
        this.serializer = serializer;
        this.memorySegmentPool = new MemorySegmentPool(inMemoryBufferSize);
    }

    @Override
    public KeyGroupStream<T> get() {
        return new KeyGroupStream<>(serializer, ioManager, memorySegmentPool);
    }
}
