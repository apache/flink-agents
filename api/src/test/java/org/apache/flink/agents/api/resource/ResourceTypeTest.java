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

package org.apache.flink.agents.api.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link ResourceType}. */
class ResourceTypeTest {

    @Test
    @DisplayName("Resolve a resource type from its string value")
    void testFromValue() {
        assertThat(ResourceType.fromValue("chat_model")).isEqualTo(ResourceType.CHAT_MODEL);
        assertThat(ResourceType.fromValue("vector_store")).isEqualTo(ResourceType.VECTOR_STORE);
        assertThat(ResourceType.fromValue("mcp_server")).isEqualTo(ResourceType.MCP_SERVER);
    }

    @Test
    @DisplayName("Every declared type round-trips through its string value")
    void testFromValueCoversEveryValue() {
        for (ResourceType type : ResourceType.values()) {
            assertThat(ResourceType.fromValue(type.getValue())).isEqualTo(type);
        }
    }

    @Test
    @DisplayName("An unknown value is rejected and echoed back")
    void testFromValueRejectsUnknownValue() {
        assertThatThrownBy(() -> ResourceType.fromValue("chat_model_connections"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown ResourceType value: chat_model_connections");
    }

    @Test
    @DisplayName("A null value is rejected rather than matching a type")
    void testFromValueRejectsNull() {
        assertThatThrownBy(() -> ResourceType.fromValue(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown ResourceType value: null");
    }
}
