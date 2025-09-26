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
package org.apache.flink.agents.runtime.operator.queue;

import org.apache.flink.streaming.api.watermark.Watermark;
import org.junit.jupiter.api.Test;

import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

class SegmentedQueueTest {

    @Test
    void testAddKeyToLastSegmentCreatesNewSegmentWhenEmpty() {
        SegmentedQueue queue = new SegmentedQueue();

        // Add first key (creates segment)
        queue.addKeyToLastSegment("key1");

        // Add second key (should go to same segment)
        queue.addKeyToLastSegment("key2");

        // Verify both keys are in the last segment
        assertTrue(hasKeyInLastSegment(queue, "key1"));
        assertTrue(hasKeyInLastSegment(queue, "key2"));
    }

    @Test
    void testAddKeyToLastSegmentAddsToExistingSegment() {
        SegmentedQueue queue = new SegmentedQueue();

        // Add watermark (creates segment)
        Watermark watermark = new Watermark(1000L);
        queue.addWatermark(watermark);

        // Add keys to the last segment
        queue.addKeyToLastSegment("key1");
        queue.addKeyToLastSegment("key2");

        // Verify both keys are in the last segment
        assertTrue(hasKeyInLastSegment(queue, "key1"));
        assertTrue(hasKeyInLastSegment(queue, "key2"));
    }

    @Test
    void testRemoveKeyFromSegment() {
        SegmentedQueue queue = new SegmentedQueue();

        // Add keys
        queue.addKeyToLastSegment("key1");
        queue.addKeyToLastSegment("key2");

        // Remove one key
        boolean removed = queue.removeKey("key1");

        // Verify the key was removed
        assertTrue(removed);
        assertFalse(hasKeyInLastSegment(queue, "key1"));
        assertTrue(hasKeyInLastSegment(queue, "key2"));
    }

    @Test
    void testRemoveNonExistentKey() {
        SegmentedQueue queue = new SegmentedQueue();

        // Try to remove a key that doesn't exist
        boolean removed = queue.removeKey("nonExistentKey");

        // Should return false
        assertFalse(removed);
    }

    @Test
    void testAddWatermarkCreatesNewSegment() {
        SegmentedQueue queue = new SegmentedQueue();

        // Add a key first
        queue.addKeyToLastSegment("key1");
        int initialSegments = queue.getSegments().size();

        // Add a watermark
        Watermark watermark = new Watermark(1000L);
        queue.addWatermark(watermark);

        // Verify a new segment was created
        assertEquals(initialSegments + 1, queue.getSegments().size());
        assertEquals(1, queue.getWatermarks().size());
    }

    @Test
    void testPopOldestWatermarkWhenSegmentIsEmpty() {
        SegmentedQueue queue = new SegmentedQueue();

        // Add a watermark (which creates a new segment)
        Watermark watermark1 = new Watermark(1000L);
        queue.addWatermark(watermark1);

        // Add another watermark
        Watermark watermark2 = new Watermark(2000L);
        queue.addWatermark(watermark2);

        // Pop the oldest watermark
        Watermark popped = queue.popOldestWatermark();

        // Verify the correct watermark was popped
        assertEquals(watermark1.getTimestamp(), popped.getTimestamp());
        assertEquals(1, queue.getWatermarks().size());
    }

    @Test
    void testPopOldestWatermarkWhenSegmentIsNotEmpty() {
        SegmentedQueue queue = new SegmentedQueue();

        // Add a key
        queue.addKeyToLastSegment("key1");

        // Add a watermark
        Watermark watermark = new Watermark(1000L);
        queue.addWatermark(watermark);

        // Try to pop the watermark - should return null since segment is not empty
        Watermark popped = queue.popOldestWatermark();

        // Should return null because segment is not empty
        assertNull(popped);
    }

    @Test
    void testPopOldestWatermarkWhenNoWatermarks() {
        SegmentedQueue queue = new SegmentedQueue();

        // Try to pop a watermark when there are none
        Watermark popped = queue.popOldestWatermark();

        // Should return null
        assertNull(popped);
    }

    @Test
    void testComplexScenario() {
        SegmentedQueue queue = new SegmentedQueue();

        // Add two continuous watermarks (creates new segments)
        queue.addWatermark(new Watermark(1000L));
        queue.addWatermark(new Watermark(1001L));

        // The first two segment should be empty, so we can pop the watermark
        Watermark popped = queue.popOldestWatermark();
        assertNotNull(popped);
        assertEquals(1000L, popped.getTimestamp());

        popped = queue.popOldestWatermark();
        assertNotNull(popped);
        assertEquals(1001L, popped.getTimestamp());

        // Add some keys
        queue.addKeyToLastSegment("key1");
        queue.addKeyToLastSegment("key2");

        // Add another watermark (creates new segment)
        queue.addWatermark(new Watermark(2000L));

        // Add more keys to the new segment
        queue.addKeyToLastSegment("key3");
        queue.addKeyToLastSegment("key4");

        // Remove some keys
        assertTrue(queue.removeKey("key1"));
        assertTrue(queue.removeKey("key3"));

        popped = queue.popOldestWatermark();
        assertNull(popped);

        // Add another watermark (creates new segment)
        queue.addWatermark(new Watermark(3000L));

        // Add more keys
        queue.addKeyToLastSegment("key5");

        // Remove remaining keys from first segment
        assertTrue(queue.removeKey("key2"));

        popped = queue.popOldestWatermark();
        assertNotNull(popped);
        assertEquals(2000L, popped.getTimestamp());

        // Remove remaining keys from second segment
        assertTrue(queue.removeKey("key4"));

        popped = queue.popOldestWatermark();
        assertNotNull(popped);
        assertEquals(3000L, popped.getTimestamp());
    }

    // Helper methods to access private fields for testing
    private boolean hasKeyInLastSegment(SegmentedQueue queue, Object key) {
        Deque<KeySegment> segments = queue.getSegments();
        return segments.getLast().hasActiveKey(key);
    }
}
