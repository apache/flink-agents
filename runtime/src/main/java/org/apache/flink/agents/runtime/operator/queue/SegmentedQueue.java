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

import java.util.ArrayDeque;
import java.util.Deque;

public class SegmentedQueue {
    /** Queue of queue entries segmented by watermarks. */
    private final Deque<KeySegment> segments;

    /** Buffer for pending watermarks. */
    private final Deque<Watermark> watermarks;

    public SegmentedQueue() {
        this.segments = new ArrayDeque<>();
        this.watermarks = new ArrayDeque<>();
    }

    /** Adds a key to the last key segment. If the queue is empty, a new segment is created. */
    public void addKeyToLastSegment(Object key) {
        KeySegment lastSegment;
        if (segments.isEmpty()) {
            lastSegment = appendNewSegment();
        } else {
            lastSegment = segments.getLast();
        }
        lastSegment.incrementKeyReference(key);
    }

    /**
     * Removes the key from the first segment that contains the key. Returns true if the key was
     * found and removed.
     */
    public boolean removeKey(Object key) {
        boolean removed = false;
        for (KeySegment segment : segments) {
            if (segment.hasActiveKey(key)) {
                segment.decrementKeyReference(key);
                removed = true;
                break;
            }
        }
        return removed;
    }

    /** Adds a watermark and creates a new segment to associate with it. */
    public void addWatermark(Watermark watermark) {
        watermarks.addLast(watermark);
        appendNewSegment();
    }

    /** Creates a new key segment and appends it to the end of the queue. */
    private KeySegment appendNewSegment() {
        KeySegment newSegment = new KeySegment();
        segments.addLast(newSegment);
        return newSegment;
    }

    /**
     * Pops the oldest watermark from the watermark deque and removes the corresponding key segment
     * from the segments queue.
     */
    public Watermark popOldestWatermark() {
        if (canProcessWatermark()) {
            segments.pop();
            return watermarks.pop();
        }
        return null;
    }

    /** Checks if a watermark is ready to be processed (i.e., oldest segment is empty). */
    private boolean canProcessWatermark() {
        return isFirstSegmentEmpty() && !watermarks.isEmpty();
    }

    /** Checks if the first key segment is empty. */
    private boolean isFirstSegmentEmpty() {
        return !this.segments.isEmpty() && segments.getFirst().isEmpty();
    }

    // Package-private getter for test access
    Deque<KeySegment> getSegments() {
        return segments;
    }

    // Package-private getter for test access
    Deque<Watermark> getWatermarks() {
        return watermarks;
    }
}
