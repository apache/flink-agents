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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NOTE: This source code was copied from the <a
 * href="https://github.com/apache/flink-statefun">flink-statefun</a>.
 *
 * <p>Tests for {@link MemorySegmentPool}.
 */
public class MemorySegmentPoolTest {

    @Test
    public void emptyMemorySegmentPoolDoesNotAllocateSegments() {
        MemorySegmentPool pool = new MemorySegmentPool(0);

        assertNull(pool.nextSegment());
    }

    @Test
    public void emptyMemorySegmentPoolOverdraftsWhenAskedTo() {
        MemorySegmentPool pool = new MemorySegmentPool(0);

        pool.ensureAtLeastOneSegmentPresent();

        assertNotNull(pool.nextSegment());
    }

    @Test
    public void emptyMemorySegmentPoolOverdraftsTemporally() {
        MemorySegmentPool pool = new MemorySegmentPool(0);

        pool.ensureAtLeastOneSegmentPresent();
        final MemorySegment overdraft = pool.nextSegment();
        pool.release(overdraft);

        assertNotNull(overdraft);
        assertTrue(overdraft.isFreed());
        assertNull(pool.nextSegment());
    }

    @Test
    public void minimalAllocationUnitIsPageSize() {
        MemorySegmentPool pool = new MemorySegmentPool(MemorySegmentPool.PAGE_SIZE - 1);

        assertNull(pool.nextSegment());
    }

    @Test
    public void poolIsAbleToAllocateTheRequiredNumberOfPages() {
        final int pageCount = 10;
        MemorySegmentPool pool = new MemorySegmentPool(pageCount * MemorySegmentPool.PAGE_SIZE);

        for (int i = 0; i < pageCount; i++) {
            MemorySegment segment = pool.nextSegment();

            assertNotNull(segment);
            assertEquals(segment.size(), MemorySegmentPool.PAGE_SIZE);
        }

        assertNull(pool.nextSegment());
    }

    @SuppressWarnings("PointlessArithmeticExpression")
    @Test
    public void segmentsCanBeReturnedToThePool() {
        MemorySegmentPool pool = new MemorySegmentPool(1 * MemorySegmentPool.PAGE_SIZE);
        //
        // we can allocate at least 1 segment
        //
        MemorySegment segment = pool.nextSegment();
        assertNotNull(segment);
        //
        // we can allocate exactly 1 segment
        //
        assertNull(pool.nextSegment());
        //
        // return a segment to the pool
        //
        pool.release(segment);
        //
        // now we can use the segment
        //
        MemorySegment pooled = pool.nextSegment();
        assertNotNull(pooled);
        assertFalse(pooled.isFreed());
    }
}
