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

/** Tests for {@link LoggerType}. */
class LoggerTypeTest {

    @Test
    @DisplayName("Resolve a logger type from its identifier, ignoring case")
    void testFromTypeIsCaseInsensitive() {
        assertThat(LoggerType.fromType("slf4j")).isEqualTo(LoggerType.SLF4J);
        assertThat(LoggerType.fromType("SLF4J")).isEqualTo(LoggerType.SLF4J);
        assertThat(LoggerType.fromType("File")).isEqualTo(LoggerType.FILE);
    }

    @Test
    @DisplayName("Surrounding whitespace is trimmed before resolution")
    void testFromTypeTrimsWhitespace() {
        assertThat(LoggerType.fromType(" file ")).isEqualTo(LoggerType.FILE);
        assertThat(LoggerType.fromType("\tslf4j\n")).isEqualTo(LoggerType.SLF4J);
    }

    @Test
    @DisplayName("Every declared type round-trips through its identifier")
    void testFromTypeCoversEveryValue() {
        for (LoggerType type : LoggerType.values()) {
            assertThat(LoggerType.fromType(type.getType())).isEqualTo(type);
        }
    }

    @Test
    @DisplayName("A null identifier is rejected")
    void testFromTypeRejectsNull() {
        assertThatThrownBy(() -> LoggerType.fromType(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Logger type cannot be null");
    }

    @Test
    @DisplayName("An unknown identifier is rejected and echoed back")
    void testFromTypeRejectsUnknownType() {
        assertThatThrownBy(() -> LoggerType.fromType("kafka"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown logger type: kafka");
    }
}
