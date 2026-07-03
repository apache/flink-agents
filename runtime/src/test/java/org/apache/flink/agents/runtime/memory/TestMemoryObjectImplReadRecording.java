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
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests read recording and {@link MemoryObjectImpl.MemoryItem#isValue()}. */
public class TestMemoryObjectImplReadRecording {

    private MemoryObjectImpl newStm(@Nullable List<MemoryValueObservation> readObservations)
            throws Exception {
        return new MemoryObjectImpl(
                MemoryObject.MemoryType.SHORT_TERM,
                new CachedMemoryStore(new ForTestMemoryMapState<>()),
                MemoryObjectImpl.ROOT_KEY,
                () -> {},
                new ArrayList<>(),
                readObservations);
    }

    @Test
    void testGetValueRecordsAbsolutePathWhenEnabled() throws Exception {
        List<MemoryValueObservation> reads = new ArrayList<>();
        MemoryObjectImpl stm = newStm(reads);
        stm.set("user.tier", "gold");

        Object v = stm.get("user.tier").getValue();

        assertEquals("gold", v);
        assertEquals(1, reads.size());
        assertEquals("user.tier", reads.get(0).getPath());
        assertEquals("gold", reads.get(0).getValue());
    }

    @Test
    void testGetValueWorksWithoutReadObservationSink() throws Exception {
        MemoryObjectImpl stm = newStm(null);
        stm.set("user.tier", "gold");

        assertEquals("gold", stm.get("user.tier").getValue());
    }

    @Test
    void testNestedChildInheritsReadRecording() throws Exception {
        List<MemoryValueObservation> reads = new ArrayList<>();
        MemoryObjectImpl stm = newStm(reads);
        stm.set("user.address.city", "SF");
        stm.get("user").get("address").get("city").getValue();
        assertEquals(1, reads.size());
        assertEquals("user.address.city", reads.get(0).getPath());
    }

    @Test
    void testGetFieldsRecordsOnlyValueChildren() throws Exception {
        List<MemoryValueObservation> reads = new ArrayList<>();
        MemoryObjectImpl stm = newStm(reads);
        stm.newObject("empty", false);
        stm.set("tier", "gold");

        stm.getFields();

        assertEquals(1, reads.size());
        assertEquals("tier", reads.get(0).getPath());
        assertEquals("gold", reads.get(0).getValue());
    }

    @Test
    void testMemoryItemIsValue() {
        // MemoryItem ctors are package-private; this test lives in the same package.
        assertTrue(new MemoryObjectImpl.MemoryItem("x").isValue()); // VALUE item
        assertFalse(new MemoryObjectImpl.MemoryItem().isValue()); // OBJECT item
    }

    @Test
    void testNestedChildInheritsMailboxChecker() throws Exception {
        // Child objects share the parent's store and must also share its mailbox-thread contract.
        AtomicInteger checkerCalls = new AtomicInteger();
        MemoryObjectImpl stm =
                new MemoryObjectImpl(
                        MemoryObject.MemoryType.SHORT_TERM,
                        new CachedMemoryStore(new ForTestMemoryMapState<>()),
                        MemoryObjectImpl.ROOT_KEY,
                        checkerCalls::incrementAndGet,
                        new ArrayList<>(),
                        null);
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
