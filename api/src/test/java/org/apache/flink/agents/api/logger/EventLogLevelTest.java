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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link EventLogLevel}. */
class EventLogLevelTest {

    @Test
    @DisplayName("Parse a log level from its name, ignoring case")
    void testFromStringIsCaseInsensitive() {
        assertThat(EventLogLevel.fromString("off")).isEqualTo(EventLogLevel.OFF);
        assertThat(EventLogLevel.fromString("STANDARD")).isEqualTo(EventLogLevel.STANDARD);
        assertThat(EventLogLevel.fromString("Verbose")).isEqualTo(EventLogLevel.VERBOSE);
    }

    @Test
    @DisplayName("Every declared log level round-trips through its name")
    void testFromStringCoversEveryValue() {
        for (EventLogLevel level : EventLogLevel.values()) {
            assertThat(EventLogLevel.fromString(level.name())).isEqualTo(level);
        }
    }

    @Test
    @DisplayName("A null value is rejected with a message naming the enum")
    void testFromStringRejectsNull() {
        assertThatThrownBy(() -> EventLogLevel.fromString(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EventLogLevel value cannot be null");
    }

    @Test
    @DisplayName("An unknown value is rejected with a message listing the valid values")
    void testFromStringRejectsUnknownValue() {
        assertThatThrownBy(() -> EventLogLevel.fromString("trace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid EventLogLevel")
                .hasMessageContaining("OFF, STANDARD, VERBOSE");
    }
}
