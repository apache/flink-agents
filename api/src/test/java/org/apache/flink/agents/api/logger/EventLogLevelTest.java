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

package org.apache.flink.agents.api.logger;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link EventLogLevel}. */
class EventLogLevelTest {

    @Test
    void testIsEnabled() {
        assertFalse(EventLogLevel.OFF.isEnabled());
        assertTrue(EventLogLevel.STANDARD.isEnabled());
        assertTrue(EventLogLevel.VERBOSE.isEnabled());
    }

    @Test
    void testParseLogLevelsSimple() {
        Map<String, EventLogLevel> result =
                EventLogLevel.parseLogLevels("ChatRequestEvent=VERBOSE,InputEvent=OFF");

        assertEquals(2, result.size());
        assertEquals(EventLogLevel.VERBOSE, result.get("ChatRequestEvent"));
        assertEquals(EventLogLevel.OFF, result.get("InputEvent"));
    }

    @Test
    void testParseLogLevelsWithWhitespace() {
        Map<String, EventLogLevel> result =
                EventLogLevel.parseLogLevels(" ChatRequestEvent = VERBOSE , InputEvent = OFF ");

        assertEquals(2, result.size());
        assertEquals(EventLogLevel.VERBOSE, result.get("ChatRequestEvent"));
        assertEquals(EventLogLevel.OFF, result.get("InputEvent"));
    }

    @Test
    void testParseLogLevelsCaseInsensitive() {
        Map<String, EventLogLevel> result =
                EventLogLevel.parseLogLevels("ChatRequestEvent=verbose,InputEvent=standard");

        assertEquals(EventLogLevel.VERBOSE, result.get("ChatRequestEvent"));
        assertEquals(EventLogLevel.STANDARD, result.get("InputEvent"));
    }

    @Test
    void testParseLogLevelsNull() {
        Map<String, EventLogLevel> result = EventLogLevel.parseLogLevels(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void testParseLogLevelsEmpty() {
        Map<String, EventLogLevel> result = EventLogLevel.parseLogLevels("");
        assertTrue(result.isEmpty());
    }

    @Test
    void testParseLogLevelsBlank() {
        Map<String, EventLogLevel> result = EventLogLevel.parseLogLevels("   ");
        assertTrue(result.isEmpty());
    }

    @Test
    void testParseLogLevelsInvalidLevel() {
        assertThrows(
                IllegalArgumentException.class,
                () -> EventLogLevel.parseLogLevels("ChatRequestEvent=INVALID"));
    }

    @Test
    void testParseLogLevelsInvalidFormat() {
        assertThrows(
                IllegalArgumentException.class,
                () -> EventLogLevel.parseLogLevels("ChatRequestEvent"));
    }

    @Test
    void testParseLogLevelsResultIsUnmodifiable() {
        Map<String, EventLogLevel> result =
                EventLogLevel.parseLogLevels("ChatRequestEvent=VERBOSE");

        assertThrows(
                UnsupportedOperationException.class, () -> result.put("foo", EventLogLevel.OFF));
    }

    @Test
    void testParseLogLevelsTrailingComma() {
        Map<String, EventLogLevel> result =
                EventLogLevel.parseLogLevels("ChatRequestEvent=VERBOSE,");

        assertEquals(1, result.size());
        assertEquals(EventLogLevel.VERBOSE, result.get("ChatRequestEvent"));
    }
}
