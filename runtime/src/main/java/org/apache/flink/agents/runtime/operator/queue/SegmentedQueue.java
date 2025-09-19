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

import java.util.ArrayDeque;
import java.util.Deque;

public class SegmentedQueue {
    /** Queue of queue entries segmented by watermarks. */
    private final Deque<KeySegment> segments;

    public SegmentedQueue() {
        this.segments = new ArrayDeque<>();
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
     * Removes a key from all segments, decrementing its reference count. Returns true if the key
     * was found and removed.
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

    /** Creates a new key segment and appends it to the end of the queue. */
    public KeySegment appendNewSegment() {
        KeySegment newSegment = new KeySegment();
        segments.addLast(newSegment);
        return newSegment;
    }

    /**
     * Removes the oldest key segment from the queue. If the queue is empty, this method does
     * nothing.
     */
    public void removeOldestSegment() {
        if (!segments.isEmpty()) {
            segments.pop();
        }
    }

    /**
     * Checks if the oldest key segment is empty. Returns true if the oldest segment exists and is
     * empty.
     */
    public boolean isOldestSegmentEmpty() {
        return !this.segments.isEmpty() && segments.getFirst().isEmpty();
    }
}
