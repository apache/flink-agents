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

import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.MemoryUpdate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests read recording and {@link MemoryObjectImpl.MemoryItem#isValue()}. */
public class TestMemoryObjectImplReadRecording {

    private MemoryObjectImpl newStm(
            List<MemoryUpdate> updates, List<MemoryUpdate> reads, boolean recordReads)
            throws Exception {
        return new MemoryObjectImpl(
                MemoryObject.MemoryType.SHORT_TERM,
                new CachedMemoryStore(new ForTestMemoryMapState<>()),
                MemoryObjectImpl.ROOT_KEY,
                () -> {},
                updates,
                reads,
                recordReads);
    }

    @Test
    void testGetValueRecordsAbsolutePathWhenEnabled() throws Exception {
        List<MemoryUpdate> updates = new ArrayList<>();
        List<MemoryUpdate> reads = new ArrayList<>();
        MemoryObjectImpl stm = newStm(updates, reads, true);
        stm.set("user.tier", "gold");

        Object v = stm.get("user.tier").getValue();

        assertEquals("gold", v);
        assertEquals(1, reads.size());
        assertEquals("user.tier", reads.get(0).getPath());
        assertEquals("gold", reads.get(0).getValue());
    }

    @Test
    void testGetValueDoesNotRecordWhenDisabled() throws Exception {
        List<MemoryUpdate> reads = new ArrayList<>();
        MemoryObjectImpl stm = newStm(new ArrayList<>(), reads, false);
        stm.set("user.tier", "gold");
        stm.get("user.tier").getValue();
        assertTrue(reads.isEmpty());
    }

    @Test
    void testNestedChildInheritsReadRecording() throws Exception {
        List<MemoryUpdate> reads = new ArrayList<>();
        MemoryObjectImpl stm = newStm(new ArrayList<>(), reads, true);
        stm.set("user.address.city", "SF");
        stm.get("user").get("address").get("city").getValue();
        assertEquals(1, reads.size());
        assertEquals("user.address.city", reads.get(0).getPath());
    }

    @Test
    void testMemoryItemIsValue() {
        // MemoryItem ctors are package-private; this test lives in the same package.
        assertTrue(new MemoryObjectImpl.MemoryItem("x").isValue()); // VALUE item
        assertFalse(new MemoryObjectImpl.MemoryItem().isValue()); // OBJECT item
    }

    @Test
    void testNestedChildInheritsMailboxChecker() throws Exception {
        // Regression: nested child objects were built with a no-op mailbox thread
        // checker, silently dropping the wrong-thread guard one level down. A real
        // checker must be inherited so ops on a child still assert mailbox access.
        AtomicInteger checkerCalls = new AtomicInteger();
        MemoryObjectImpl stm =
                new MemoryObjectImpl(
                        MemoryObject.MemoryType.SHORT_TERM,
                        new CachedMemoryStore(new ForTestMemoryMapState<>()),
                        MemoryObjectImpl.ROOT_KEY,
                        checkerCalls::incrementAndGet,
                        new ArrayList<>(),
                        new ArrayList<>(),
                        false);
        stm.set("user.city", "SF");

        MemoryObject child = stm.get("user"); // nested child object
        int before = checkerCalls.get();
        child.getValue(); // op on the child must invoke the inherited checker (+1)
        child.get("city"); // navigation on the child too (+1)

        assertEquals(
                before + 2,
                checkerCalls.get(),
                "nested child must invoke the parent's real mailbox checker, not a no-op");
    }
}
