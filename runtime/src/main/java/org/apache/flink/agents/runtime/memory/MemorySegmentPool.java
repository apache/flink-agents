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
package org.apache.flink.agents.runtime.memory;

import org.apache.flink.core.memory.MemorySegment;
import org.apache.flink.core.memory.MemorySegmentFactory;
import org.apache.flink.core.memory.MemorySegmentSource;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import java.util.ArrayDeque;

/**
 * NOTE: This source code was copied from the <a
 * href="https://github.com/apache/flink-statefun">flink-statefun</a>
 *
 * <p>A Memory pool of {@link MemorySegment}s.
 */
@NotThreadSafe
public class MemorySegmentPool implements MemorySegmentSource {
    static final int PAGE_SIZE = 64 * 1024;

    private final ArrayDeque<MemorySegment> pool;
    private final long inMemoryBufferSize;
    private long totalAllocatedMemory;

    public MemorySegmentPool(long inMemoryBufferSize) {
        this.pool = new ArrayDeque<>();
        this.inMemoryBufferSize = inMemoryBufferSize;
    }

    @Nullable
    @Override
    public MemorySegment nextSegment() {
        MemorySegment segment = pool.pollFirst();
        if (segment != null) {
            return segment;
        }
        //
        // no segments in the pool, try to allocate one.
        //
        if (!hasRemainingCapacity()) {
            return null;
        }
        segment = MemorySegmentFactory.allocateUnpooledSegment(PAGE_SIZE);
        totalAllocatedMemory += PAGE_SIZE;
        return segment;
    }

    /** Releases the MemorySegment. */
    public void release(MemorySegment segment) {
        if (totalAllocatedMemory > inMemoryBufferSize) {
            segment.free();
            totalAllocatedMemory -= PAGE_SIZE;
            return;
        }
        pool.add(segment);
    }

    public int getSegmentSize() {
        return PAGE_SIZE;
    }

    public void ensureAtLeastOneSegmentPresent() {
        if (!pool.isEmpty()) {
            //
            // the next allocation would succeed because the pool is not empty
            //
            return;
        }
        if (hasRemainingCapacity()) {
            //
            // the next allocation would succeed because the total allocated size is within the
            // allowed
            // range
            //
            return;
        }
        //
        // we overdraft momentarily.
        //
        MemorySegment segment = MemorySegmentFactory.allocateUnpooledSegment(PAGE_SIZE);
        totalAllocatedMemory += PAGE_SIZE;
        pool.add(segment);
    }

    private boolean hasRemainingCapacity() {
        return totalAllocatedMemory + PAGE_SIZE <= inMemoryBufferSize;
    }
}
