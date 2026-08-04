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

package org.apache.flink.agents.integrations.chatmodels.anthropic;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AnthropicChatModelConnection}'s request construction and the response
 * conversion that consumes it. These inspect the request returned by {@code buildRequest} and feed
 * {@code convertResponse} a hand-built response, so they need no credentials, no network, and no
 * mocking framework.
 */
class AnthropicChatModelConnectionTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    /** The continuation an assistant returns after a "{" prefill, and the document it completes. */
    private static final String CONTINUATION = "\"ok\": true}";

    private static final String COMPLETED = "{" + CONTINUATION;

    private static AnthropicChatModelConnection connection() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(AnthropicChatModelConnection.class.getName())
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("model", "claude-sonnet-4-20250514")
                        .build();
        return new AnthropicChatModelConnection(desc, NOOP);
    }

    private static Map<String, Object> params(Object jsonPrefill) {
        Map<String, Object> params = new HashMap<>();
        params.put("max_tokens", 256);
        if (jsonPrefill != null) {
            params.put("json_prefill", jsonPrefill);
        }
        return params;
    }

    private static List<ChatMessage> userMessage() {
        return List.of(new ChatMessage(MessageRole.USER, "hi"));
    }

    /** An assistant response carrying a single text block. */
    private static Message textResponse(String text) {
        Usage usage =
                Usage.builder()
                        .inputTokens(1)
                        .outputTokens(1)
                        .cacheCreation(Optional.empty())
                        .cacheCreationInputTokens(Optional.empty())
                        .cacheReadInputTokens(Optional.empty())
                        .serverToolUse(Optional.empty())
                        .serviceTier(Optional.empty())
                        .build();
        return Message.builder()
                .id("msg_test")
                .model(Model.of("claude-sonnet-4-20250514"))
                .addContent(TextBlock.builder().text(text).citations(Optional.empty()).build())
                .stopReason(Optional.empty())
                .stopSequence(Optional.empty())
                .usage(usage)
                .build();
    }

    /** True when the built request ends with the prefilled assistant "{" message. */
    private static boolean requestCarriesPrefill(AnthropicChatModelConnection.BuiltRequest built) {
        List<MessageParam> messages = built.params.messages();
        MessageParam last = messages.get(messages.size() - 1);
        return last.role().equals(MessageParam.Role.ASSISTANT)
                && last.content().string().isPresent()
                && "{".equals(last.content().string().get());
    }

    /**
     * Asserts that the recorded decision, the request content, and the converted response all agree
     * with each other and with {@code expectedApplied}.
     *
     * <p>The agreement is the invariant that matters: a decision that does not match what the
     * request actually carries makes the conversion either prepend a stray {@code "{"} or drop a
     * required one, yielding malformed JSON the response gives no sign of.
     */
    private static void assertPrefillDecision(
            Object jsonPrefill, List<Tool> tools, boolean expectedApplied) {
        AnthropicChatModelConnection connection = connection();
        AnthropicChatModelConnection.BuiltRequest built =
                connection.buildRequest(userMessage(), tools, params(jsonPrefill));

        assertThat(built.jsonPrefillApplied).isEqualTo(expectedApplied);
        assertThat(requestCarriesPrefill(built)).isEqualTo(expectedApplied);
        assertThat(connection.convertResponse(built, textResponse(CONTINUATION)).getContent())
                .isEqualTo(expectedApplied ? COMPLETED : CONTINUATION);
    }

    @Test
    @DisplayName("json_prefill applied when requested with no tools")
    void testPrefillAppliedWithoutTools() {
        assertPrefillDecision(true, List.of(), true);
    }

    @Test
    @DisplayName("json_prefill not applied when tools are present")
    void testPrefillNotAppliedWithTools() {
        assertPrefillDecision(true, List.of(new StubTool()), false);
    }

    @Test
    @DisplayName("json_prefill not applied when the parameter is absent")
    void testPrefillNotAppliedWhenAbsent() {
        assertPrefillDecision(null, List.of(), false);
    }

    @Test
    @DisplayName("json_prefill not applied when the parameter is false")
    void testPrefillNotAppliedWhenFalse() {
        assertPrefillDecision(false, List.of(), false);
    }

    @Test
    @DisplayName("json_prefill applied when the tools list is null")
    void testPrefillAppliedWithNullTools() {
        assertPrefillDecision(true, null, true);
    }

    @Test
    @DisplayName("null model params are copied rather than dereferenced")
    void testNullModelParamsAreCopied() {
        // With no params there is no max_tokens either, so the SDK's own required-field check is
        // the first thing that can fail. Reaching it at all is the assertion: a regression in the
        // null handling would surface earlier, as a NullPointerException.
        assertThatThrownBy(() -> connection().buildRequest(userMessage(), List.of(), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("request build failures surface as a wrapped RuntimeException")
    void testBuildFailureIsWrapped() {
        List<ChatMessage> messages = List.of(new ChatMessage(MessageRole.TOOL, "result"));

        assertThatThrownBy(() -> connection().chat(messages, List.of(), params(null)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to call Anthropic messages API.")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    /** Minimal tool stub; only its presence in the tools list matters. */
    private static class StubTool extends Tool {
        StubTool() {
            super(new ToolMetadata("add", "adds", "{\"type\":\"object\"}"));
        }

        @Override
        public ToolType getToolType() {
            return ToolType.FUNCTION;
        }

        @Override
        public ToolResponse call(ToolParameters parameters) {
            return ToolResponse.success(null);
        }
    }
}
