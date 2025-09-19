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

import java.util.HashMap;
import java.util.Map;

/** A group of keys with reference counting to track active keys. */
public class KeySegment {
    /** Maps keys to their reference counts (number of active occurrences). */
    private final Map<Object, Integer> keyReferenceCounts;

    public KeySegment() {
        this.keyReferenceCounts = new HashMap<>();
    }

    /** Increments the reference count for a key. */
    public void incrementKeyReference(Object key) {
        keyReferenceCounts.merge(key, 1, Integer::sum);
    }

    /** Decrements the reference count for a key. Removes the key if the count reaches zero. */
    public void decrementKeyReference(Object key) {
        keyReferenceCounts.computeIfPresent(
                key,
                (k, count) -> {
                    if (count <= 1) {
                        return null; // Remove the key if count is 1 or less
                    } else {
                        return count - 1;
                    }
                });
    }

    /** Checks if a key is active (i.e., its reference count is greater than zero). */
    public boolean hasActiveKey(Object key) {
        return keyReferenceCounts.containsKey(key) && keyReferenceCounts.get(key) > 0;
    }

    /** Checks if the group is empty (no active keys). */
    public boolean isEmpty() {
        return keyReferenceCounts.isEmpty();
    }
}
