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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KeySegmentTest {

    @Test
    void testIncrementKeyReference() {
        KeySegment keySegment = new KeySegment();

        // Test that a key is added with count 1
        keySegment.incrementKeyReference("key1");
        assertTrue(keySegment.hasActiveKey("key1"));
        assertFalse(keySegment.isEmpty());
    }

    @Test
    void testIncrementKeyReferenceMultipleTimes() {
        KeySegment keySegment = new KeySegment();

        // Test incrementing the same key multiple times
        keySegment.incrementKeyReference("key1");
        keySegment.incrementKeyReference("key1");
        keySegment.incrementKeyReference("key1");

        assertTrue(keySegment.hasActiveKey("key1"));
        assertEquals(3, keySegment.getKeyReferenceCounts().get("key1"));
    }

    @Test
    void testDecrementKeyReference() {
        KeySegment keySegment = new KeySegment();

        // Add a key with count 1
        keySegment.incrementKeyReference("key1");

        // Decrement it - should be removed
        keySegment.decrementKeyReference("key1");
        assertFalse(keySegment.hasActiveKey("key1"));
        assertTrue(keySegment.isEmpty());
    }

    @Test
    void testDecrementKeyReferenceMultipleTimes() {
        KeySegment keySegment = new KeySegment();

        // Add a key with count 3
        keySegment.incrementKeyReference("key1");
        keySegment.incrementKeyReference("key1");
        keySegment.incrementKeyReference("key1");

        // Decrement it twice - should still exist with count 1
        keySegment.decrementKeyReference("key1");
        keySegment.decrementKeyReference("key1");
        assertTrue(keySegment.hasActiveKey("key1"));
        assertEquals(1, keySegment.getKeyReferenceCounts().get("key1"));

        // Decrement once more - should be removed
        keySegment.decrementKeyReference("key1");
        assertFalse(keySegment.hasActiveKey("key1"));
        assertTrue(keySegment.isEmpty());
    }

    @Test
    void testHasActiveKey() {
        KeySegment keySegment = new KeySegment();

        // Test with non-existent key
        assertFalse(keySegment.hasActiveKey("key1"));

        // Test with existing key
        keySegment.incrementKeyReference("key1");
        assertTrue(keySegment.hasActiveKey("key1"));
    }

    @Test
    void testIsEmpty() {
        KeySegment keySegment = new KeySegment();

        // Should be empty initially
        assertTrue(keySegment.isEmpty());

        // Add a key - should not be empty
        keySegment.incrementKeyReference("key1");
        assertFalse(keySegment.isEmpty());

        // Remove the key - should be empty again
        keySegment.decrementKeyReference("key1");
        assertTrue(keySegment.isEmpty());
    }

    @Test
    void testMultipleKeys() {
        KeySegment keySegment = new KeySegment();

        // Add multiple keys
        keySegment.incrementKeyReference("key1");
        keySegment.incrementKeyReference("key2");
        keySegment.incrementKeyReference("key3");

        assertTrue(keySegment.hasActiveKey("key1"));
        assertTrue(keySegment.hasActiveKey("key2"));
        assertTrue(keySegment.hasActiveKey("key3"));
        assertFalse(keySegment.isEmpty());

        // Remove one key
        keySegment.decrementKeyReference("key2");
        assertTrue(keySegment.hasActiveKey("key1"));
        assertFalse(keySegment.hasActiveKey("key2"));
        assertTrue(keySegment.hasActiveKey("key3"));
        assertFalse(keySegment.isEmpty());

        // Remove remaining keys
        keySegment.decrementKeyReference("key1");
        keySegment.decrementKeyReference("key3");
        assertTrue(keySegment.isEmpty());
    }
}
