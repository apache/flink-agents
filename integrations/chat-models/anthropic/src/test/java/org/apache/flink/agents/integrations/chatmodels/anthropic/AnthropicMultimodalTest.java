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

import com.anthropic.core.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.agents.api.chat.messages.AudioBlock;
import org.apache.flink.agents.api.chat.messages.Base64Source;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ContentBlock;
import org.apache.flink.agents.api.chat.messages.DocumentBlock;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.UnsupportedContentBlockException;
import org.apache.flink.agents.api.chat.messages.VideoBlock;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests how content blocks become Anthropic Messages API content blocks. */
class AnthropicMultimodalTest {

    private static final String URL = "https://example.com/cat.png?sig=secret";
    private static final String BASE64 = "aGVsbG8="; // "hello"

    private static AnthropicChatModelConnection connection() {
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(AnthropicChatModelConnection.class.getName())
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("model", "claude-sonnet-4-20250514")
                        .build();
        return new AnthropicChatModelConnection(
                descriptor, ResourceContext.fromGetResource((a, b) -> null));
    }

    private static Map<String, Object> params() {
        Map<String, Object> params = new HashMap<>();
        params.put("max_tokens", 256);
        return params;
    }

    /** The first request message, as the SDK serializes it. */
    private static JsonNode wire(ChatMessage message) {
        return ObjectMappers.jsonMapper()
                .valueToTree(
                        connection()
                                .buildRequest(List.of(message), List.of(), params(), null)
                                .params
                                .messages()
                                .get(0));
    }

    @Test
    @DisplayName("A text-only user message keeps plain string content")
    void testTextOnlyUserMessageKeepsStringContent() {
        JsonNode message = wire(ChatMessage.user(List.of(TextBlock.of("hi"))));

        assertThat(message.get("content").isTextual()).isTrue();
        assertThat(message.get("content").asText()).isEqualTo("hi");
    }

    @Test
    @DisplayName("Images become image blocks in block order, by URL or as Base64 data")
    void testImagesBecomeImageBlocks() {
        JsonNode content =
                wire(ChatMessage.user(
                                List.of(
                                        TextBlock.of("Compare"),
                                        ImageBlock.fromUrl("image/png", URL),
                                        ImageBlock.fromBase64("image/png", BASE64))))
                        .get("content");

        assertThat(content).hasSize(3);
        assertThat(content.at("/0/type").asText()).isEqualTo("text");
        assertThat(content.at("/0/text").asText()).isEqualTo("Compare");
        assertThat(content.at("/1/type").asText()).isEqualTo("image");
        assertThat(content.at("/1/source/type").asText()).isEqualTo("url");
        assertThat(content.at("/1/source/url").asText()).isEqualTo(URL);
        assertThat(content.at("/2/source/type").asText()).isEqualTo("base64");
        assertThat(content.at("/2/source/media_type").asText()).isEqualTo("image/png");
        assertThat(content.at("/2/source/data").asText()).isEqualTo(BASE64);
    }

    @Test
    @DisplayName("PDFs and Base64 plain text become document blocks, titled by the block name")
    void testDocumentsBecomeDocumentBlocks() {
        JsonNode content =
                wire(ChatMessage.user(
                                List.of(
                                        new DocumentBlock(
                                                "application/pdf",
                                                new Base64Source(BASE64),
                                                "report.pdf",
                                                null,
                                                null),
                                        DocumentBlock.fromUrl(
                                                "application/pdf", "https://example.com/a.pdf"),
                                        DocumentBlock.fromBase64("text/plain", BASE64))))
                        .get("content");

        assertThat(content.at("/0/type").asText()).isEqualTo("document");
        assertThat(content.at("/0/source/type").asText()).isEqualTo("base64");
        assertThat(content.at("/0/source/media_type").asText()).isEqualTo("application/pdf");
        assertThat(content.at("/0/source/data").asText()).isEqualTo(BASE64);
        assertThat(content.at("/0/title").asText()).isEqualTo("report.pdf");
        assertThat(content.at("/1/source/type").asText()).isEqualTo("url");
        assertThat(content.at("/1/source/url").asText()).isEqualTo("https://example.com/a.pdf");
        assertThat(content.at("/2/source/type").asText()).isEqualTo("text");
        assertThat(content.at("/2/source/media_type").asText()).isEqualTo("text/plain");
        assertThat(content.at("/2/source/data").asText()).isEqualTo("hello");
    }

    @Test
    @DisplayName("Blocks Anthropic has no block for fail without leaking the source")
    void testUnsupportedBlocksFailExplicitly() {
        List<ContentBlock> unsupported =
                List.of(
                        AudioBlock.fromBase64("audio/wav", BASE64),
                        VideoBlock.fromUrl("video/mp4", URL),
                        ImageBlock.fromBase64("image/bmp", BASE64),
                        DocumentBlock.fromUrl("text/plain", URL),
                        DocumentBlock.fromBase64("application/msword", BASE64));
        for (ContentBlock block : unsupported) {
            assertThatThrownBy(() -> wire(ChatMessage.user(List.of(TextBlock.of("hi"), block))))
                    .isInstanceOf(UnsupportedContentBlockException.class)
                    .hasMessageStartingWith("Anthropic cannot send a")
                    .hasMessageContaining(" " + block.getType() + " block")
                    .hasMessageNotContaining("secret")
                    .hasMessageNotContaining(BASE64);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = MessageRole.class,
            names = {"SYSTEM", "ASSISTANT", "TOOL"})
    @DisplayName("Media outside user messages fails explicitly")
    void testMediaOutsideUserMessagesFails(MessageRole role) {
        ChatMessage message =
                new ChatMessage(
                        role, List.of(TextBlock.of("see"), ImageBlock.fromUrl("image/png", URL)));

        assertThatThrownBy(
                        () ->
                                connection()
                                        .buildRequest(
                                                List.of(ChatMessage.user("hi"), message),
                                                List.of(),
                                                params(),
                                                null))
                .isInstanceOf(UnsupportedContentBlockException.class)
                .hasMessageContaining("only user messages can carry media");
    }

    @Test
    @DisplayName("Undecodable plain-text data fails as invalid, not as unsupported")
    void testInvalidBase64TextDocumentFails() {
        ChatMessage message =
                ChatMessage.user(List.of(DocumentBlock.fromBase64("text/plain", "not base64!")));

        assertThatThrownBy(() -> wire(message))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("A document block's base64 data could not be decoded.");
    }

    @Test
    @DisplayName("chat() surfaces media errors with their own types")
    void testChatKeepsMediaErrorTypes() {
        ChatMessage message = ChatMessage.user(List.of(AudioBlock.fromBase64("audio/wav", BASE64)));

        assertThatThrownBy(() -> connection().chat(List.of(message), List.of(), params(), null))
                .isExactlyInstanceOf(UnsupportedContentBlockException.class);
    }
}
