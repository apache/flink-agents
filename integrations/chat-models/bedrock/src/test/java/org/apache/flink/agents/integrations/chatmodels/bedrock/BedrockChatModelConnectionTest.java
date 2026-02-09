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

package org.apache.flink.agents.integrations.chatmodels.bedrock;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Tests for {@link BedrockChatModelConnection}. */
class BedrockChatModelConnectionTest {

    private static final BiFunction<String, ResourceType, Resource> NOOP = (a, b) -> null;

    private static ResourceDescriptor descriptor(String region, String model) {
        ResourceDescriptor.Builder b =
                ResourceDescriptor.Builder.newBuilder(BedrockChatModelConnection.class.getName());
        if (region != null) b.addInitialArgument("region", region);
        if (model != null) b.addInitialArgument("model", model);
        return b.build();
    }

    @Test
    @DisplayName("Constructor creates client with default region")
    void testConstructorDefaultRegion() {
        BedrockChatModelConnection conn = new BedrockChatModelConnection(
                descriptor(null, "us.anthropic.claude-sonnet-4-20250514-v1:0"), NOOP);
        assertNotNull(conn);
    }

    @Test
    @DisplayName("Constructor creates client with explicit region")
    void testConstructorExplicitRegion() {
        BedrockChatModelConnection conn = new BedrockChatModelConnection(
                descriptor("us-west-2", "us.anthropic.claude-sonnet-4-20250514-v1:0"), NOOP);
        assertNotNull(conn);
    }

    @Test
    @DisplayName("Extends BaseChatModelConnection")
    void testInheritance() {
        BedrockChatModelConnection conn = new BedrockChatModelConnection(
                descriptor("us-east-1", "test-model"), NOOP);
        assertThat(conn).isInstanceOf(BaseChatModelConnection.class);
    }

    @Test
    @DisplayName("Chat throws when no model specified")
    void testChatThrowsWithoutModel() {
        BedrockChatModelConnection conn = new BedrockChatModelConnection(
                descriptor("us-east-1", null), NOOP);
        List<ChatMessage> msgs = List.of(new ChatMessage(MessageRole.USER, "hello"));
        assertThatThrownBy(() -> conn.chat(msgs, null, Collections.emptyMap()))
                .isInstanceOf(RuntimeException.class);
    }
}
