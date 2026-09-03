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
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Tests for {@link BedrockChatModelConnection}. */
class BedrockChatModelConnectionTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    private static ResourceDescriptor descriptor(String region, String model) {
        ResourceDescriptor.Builder b =
                ResourceDescriptor.Builder.newBuilder(BedrockChatModelConnection.class.getName());
        if (region != null) b.addInitialArgument("region", region);
        if (model != null) b.addInitialArgument("model", model);
        return b.build();
    }

    private static BedrockChatModelConnection connection() {
        return new BedrockChatModelConnection(
                descriptor("us-east-1", "us.anthropic.claude-sonnet-4-20250514-v1:0"), NOOP);
    }

    /** Minimal tool carrying only metadata; never invoked in these tests. */
    private static final class SchemaOnlyTool extends Tool {
        SchemaOnlyTool(String inputSchema) {
            super(new ToolMetadata("add", "Add two numbers.", inputSchema));
        }

        @Override
        public ToolType getToolType() {
            return ToolType.FUNCTION;
        }

        @Override
        public ToolResponse call(ToolParameters parameters) {
            throw new UnsupportedOperationException("not invoked in this test");
        }
    }

    private static ChatMessage toolMessage(String externalId, String content) {
        Map<String, Object> extraArgs = new HashMap<>();
        extraArgs.put("externalId", externalId);
        return new ChatMessage(MessageRole.TOOL, content, extraArgs);
    }

    @Test
    @DisplayName("Constructor creates client with default region")
    void testConstructorDefaultRegion() {
        BedrockChatModelConnection conn =
                new BedrockChatModelConnection(
                        descriptor(null, "us.anthropic.claude-sonnet-4-20250514-v1:0"), NOOP);
        assertNotNull(conn);
    }

    @Test
    @DisplayName("Constructor creates client with explicit region")
    void testConstructorExplicitRegion() {
        BedrockChatModelConnection conn =
                new BedrockChatModelConnection(
                        descriptor("us-west-2", "us.anthropic.claude-sonnet-4-20250514-v1:0"),
                        NOOP);
        assertNotNull(conn);
    }

    @Test
    @DisplayName("Extends BaseChatModelConnection")
    void testInheritance() {
        BedrockChatModelConnection conn =
                new BedrockChatModelConnection(descriptor("us-east-1", "test-model"), NOOP);
        assertThat(conn).isInstanceOf(BaseChatModelConnection.class);
    }

    @Test
    @DisplayName("Chat throws when no model specified")
    void testChatThrowsWithoutModel() {
        BedrockChatModelConnection conn =
                new BedrockChatModelConnection(descriptor("us-east-1", null), NOOP);
        List<ChatMessage> msgs = List.of(new ChatMessage(MessageRole.USER, "hello"));
        assertThatThrownBy(() -> conn.chat(msgs, null, Collections.emptyMap()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("stripMarkdownFences: normal text with braces is not modified")
    void testStripMarkdownFencesPreservesTextWithBraces() {
        assertThat(
                        BedrockChatModelConnection.stripMarkdownFences(
                                "Use the format {key: value} for config"))
                .isEqualTo("Use the format {key: value} for config");
    }

    @Test
    @DisplayName("stripMarkdownFences: clean JSON passes through")
    void testStripMarkdownFencesCleanJson() {
        assertThat(
                        BedrockChatModelConnection.stripMarkdownFences(
                                "{\"score\": 5, \"reasons\": []}"))
                .isEqualTo("{\"score\": 5, \"reasons\": []}");
    }

    @Test
    @DisplayName("stripMarkdownFences: strips ```json fences")
    void testStripMarkdownFencesJsonBlock() {
        assertThat(BedrockChatModelConnection.stripMarkdownFences("```json\n{\"score\": 5}\n```"))
                .isEqualTo("{\"score\": 5}");
    }

    @Test
    @DisplayName("stripMarkdownFences: strips plain ``` fences")
    void testStripMarkdownFencesPlainBlock() {
        assertThat(BedrockChatModelConnection.stripMarkdownFences("```\n{\"id\": \"P001\"}\n```"))
                .isEqualTo("{\"id\": \"P001\"}");
    }

    @Test
    @DisplayName("stripMarkdownFences: null returns null")
    void testStripMarkdownFencesNull() {
        assertThat(BedrockChatModelConnection.stripMarkdownFences(null)).isNull();
    }

    @Test
    @DisplayName("buildRequest: the effective model id lands in the request")
    void testBuildRequestResolvesModelId() {
        List<ChatMessage> messages = List.of(ChatMessage.user("hello"));

        ConverseRequest fromConnection = connection().buildRequest(messages, null, Map.of());
        assertThat(fromConnection.modelId())
                .isEqualTo("us.anthropic.claude-sonnet-4-20250514-v1:0");

        ConverseRequest fromCall =
                connection().buildRequest(messages, null, Map.of("model", "per-call-model"));
        assertThat(fromCall.modelId()).isEqualTo("per-call-model");
    }

    @Test
    @DisplayName("buildRequest: tools land in toolConfig")
    void testBuildRequestPreservesToolConfig() {
        ConverseRequest request =
                connection()
                        .buildRequest(
                                List.of(ChatMessage.user("hello")),
                                List.of(new SchemaOnlyTool("{\"type\": \"object\"}")),
                                Map.of());

        assertThat(request.toolConfig()).isNotNull();
        assertThat(request.toolConfig().tools()).hasSize(1);
        assertThat(request.toolConfig().tools().get(0).toolSpec().name()).isEqualTo("add");
        assertThat(request.toolConfig().tools().get(0).toolSpec().description())
                .isEqualTo("Add two numbers.");
        assertThat(request.toolConfig().tools().get(0).toolSpec().inputSchema().json().asMap())
                .containsEntry("type", Document.fromString("object"));
    }

    @Test
    @DisplayName("buildRequest: system messages land in system, the rest in messages")
    void testBuildRequestPreservesSystemMessages() {
        ConverseRequest request =
                connection()
                        .buildRequest(
                                List.of(ChatMessage.system("be terse"), ChatMessage.user("hello")),
                                null,
                                Map.of());

        assertThat(request.system()).hasSize(1);
        assertThat(request.system().get(0).text()).isEqualTo("be terse");
        assertThat(request.messages()).hasSize(1);
        assertThat(request.messages().get(0).role()).isEqualTo(ConversationRole.USER);
        assertThat(request.messages().get(0).content().get(0).text()).isEqualTo("hello");
    }

    @Test
    @DisplayName("buildRequest: temperature and max_tokens land in inferenceConfig")
    void testBuildRequestPreservesInferenceConfig() {
        List<ChatMessage> messages = List.of(ChatMessage.user("hello"));

        ConverseRequest configured =
                connection()
                        .buildRequest(messages, null, Map.of("temperature", 0.7, "max_tokens", 64));
        assertThat(configured.inferenceConfig()).isNotNull();
        assertThat(configured.inferenceConfig().temperature()).isEqualTo(0.7f);
        assertThat(configured.inferenceConfig().maxTokens()).isEqualTo(64);

        ConverseRequest bare = connection().buildRequest(messages, null, Map.of());
        assertThat(bare.inferenceConfig()).isNull();
    }

    @Test
    @DisplayName("buildRequest: consecutive tool messages merge into one user message")
    void testBuildRequestMergesConsecutiveToolMessages() {
        ConverseRequest request =
                connection()
                        .buildRequest(
                                List.of(
                                        ChatMessage.user("hello"),
                                        toolMessage("call-1", "first result"),
                                        toolMessage("call-2", "second result")),
                                null,
                                Map.of());

        assertThat(request.messages()).hasSize(2);
        Message merged = request.messages().get(1);
        assertThat(merged.role()).isEqualTo(ConversationRole.USER);
        assertThat(merged.content()).hasSize(2);
        assertThat(merged.content())
                .extracting(block -> block.toolResult().toolUseId())
                .containsExactly("call-1", "call-2");
    }
}
