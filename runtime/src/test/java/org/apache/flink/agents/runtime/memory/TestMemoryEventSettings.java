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

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.apache.flink.agents.runtime.memory.MemoryEventSettings.MemoryOp;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Three-level fallback: sub-key explicit → master explicit → per-op default. */
public class TestMemoryEventSettings {

    @Test
    void testPerOpDefaultsWhenNothingConfigured() {
        MemoryEventSettings settings = MemoryEventSettings.from(Map.of());
        assertTrue(settings.generate(MemoryOp.SHORT_TERM_WRITE));
        assertTrue(settings.generate(MemoryOp.SENSORY_WRITE));
        assertTrue(settings.generate(MemoryOp.LONG_TERM_UPDATE));
        assertTrue(settings.generate(MemoryOp.LONG_TERM_GET));
        assertTrue(settings.generate(MemoryOp.LONG_TERM_SEARCH));
        assertFalse(settings.generate(MemoryOp.SHORT_TERM_READ));
        assertFalse(settings.generate(MemoryOp.SENSORY_READ));
    }

    @Test
    void testMasterSwitchFalseDisablesEverything() {
        MemoryEventSettings settings =
                MemoryEventSettings.from(Map.of("memory.generate-event", false));
        for (MemoryOp op : MemoryOp.values()) {
            assertFalse(settings.generate(op));
        }
        assertFalse(settings.anyEnabled());
    }

    @Test
    void testMasterSwitchTrueEnablesReadsToo() {
        MemoryEventSettings settings =
                MemoryEventSettings.from(Map.of("memory.generate-event", true));
        assertTrue(settings.generate(MemoryOp.SHORT_TERM_READ));
        assertTrue(settings.generate(MemoryOp.SENSORY_READ));
    }

    @Test
    void testSubKeyOverridesMaster() {
        // YAML also accepts string-valued booleans.
        MemoryEventSettings settings =
                MemoryEventSettings.from(
                        Map.of(
                                "memory.generate-event", false,
                                "memory.generate-event.long-term-update", true,
                                "memory.generate-event.short-term-write", "true"));
        assertTrue(settings.generate(MemoryOp.LONG_TERM_UPDATE));
        assertTrue(settings.generate(MemoryOp.SHORT_TERM_WRITE));
        // The explicit master value takes precedence over the per-operation default.
        assertFalse(settings.generate(MemoryOp.SENSORY_WRITE));
    }

    @Test
    void testExplicitFalseSubKeyBeatsMasterTrue() {
        MemoryEventSettings settings =
                MemoryEventSettings.from(
                        Map.of(
                                "memory.generate-event", true,
                                "memory.generate-event.long-term-search", false));
        assertFalse(settings.generate(MemoryOp.LONG_TERM_SEARCH));
        assertTrue(settings.generate(MemoryOp.LONG_TERM_GET));
    }
}
