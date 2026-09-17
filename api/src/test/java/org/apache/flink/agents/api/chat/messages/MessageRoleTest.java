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

package org.apache.flink.agents.api.chat.messages;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link MessageRole}. */
class MessageRoleTest {

    @Test
    @DisplayName("Resolve a message role from its serialized value")
    void testFromValue() {
        assertThat(MessageRole.fromValue("user")).isEqualTo(MessageRole.USER);
        assertThat(MessageRole.fromValue("assistant")).isEqualTo(MessageRole.ASSISTANT);
        assertThat(MessageRole.fromValue("system")).isEqualTo(MessageRole.SYSTEM);
        assertThat(MessageRole.fromValue("tool")).isEqualTo(MessageRole.TOOL);
    }

    @Test
    @DisplayName("Every declared role round-trips through its serialized value")
    void testFromValueCoversEveryValue() {
        for (MessageRole role : MessageRole.values()) {
            assertThat(MessageRole.fromValue(role.getValue())).isEqualTo(role);
        }
    }

    @Test
    @DisplayName("The serialized value is what toString returns as well")
    void testToStringReturnsValue() {
        for (MessageRole role : MessageRole.values()) {
            assertThat(role.toString()).isEqualTo(role.getValue());
        }
    }

    @Test
    @DisplayName("An unknown value is rejected and echoed back")
    void testFromValueRejectsUnknownValue() {
        assertThatThrownBy(() -> MessageRole.fromValue("admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid MessageRole value: admin");
    }

    @Test
    @DisplayName("A null value is rejected rather than matching a role")
    void testFromValueRejectsNull() {
        assertThatThrownBy(() -> MessageRole.fromValue(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid MessageRole value: null");
    }
}
