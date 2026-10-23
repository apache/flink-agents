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
import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.chat.messages.DocumentBlock;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.MediaBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.chat.messages.UnsupportedContentBlockException;
import org.apache.flink.agents.api.chat.messages.VideoBlock;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Tests how content blocks become Anthropic Messages API content blocks. */
class AnthropicMultimodalTest {

    private static final String URL = "https://example.com/cat.png?sig=secret";
    private static final String BASE64 = "aGVsbG8="; // "hello"

    /** Media Anthropic has no block for. */
    private static final List<MediaBlock> UNSUPPORTED =
            List.of(
                    AudioBlock.fromBase64("audio/wav", BASE64),
                    VideoBlock.fromUrl("video/mp4", URL),
                    ImageBlock.fromBase64("image/bmp", BASE64),
                    DocumentBlock.fromUrl("text/plain", URL),
                    DocumentBlock.fromBase64("application/msword", BASE64));

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
        return wire(List.of(message)).get(0);
    }

    /** The request messages, as the SDK serializes them. */
    private static JsonNode wire(List<ChatMessage> messages) {
        return ObjectMappers.jsonMapper()
                .valueToTree(
                        connection()
                                .buildRequest(messages, List.of(), params(), null)
                                .params
                                .messages());
    }

    private static JsonNode json(Object value) {
        return ObjectMappers.jsonMapper().valueToTree(value);
    }

    /** The tool_result block a tool message is sent as. */
    private static JsonNode toolResult(ToolResultBlock result) {
        JsonNode message = wire(ChatMessage.tool(result));
        assertThat(message.get("role").asText()).isEqualTo("user");
        assertThat(message.get("content")).hasSize(1);
        return message.at("/content/0");
    }

    @Test
    @DisplayName("A text-only user message is sent as before, as one text block")
    void testTextOnlyUserMessageIsUnchanged() {
        JsonNode content = wire(ChatMessage.user(List.of(TextBlock.of("hi")))).get("content");

        assertThat(content).hasSize(1);
        assertThat(content.at("/0/type").asText()).isEqualTo("text");
        assertThat(content.at("/0/text").asText()).isEqualTo("hi");
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
        for (MediaBlock block : UNSUPPORTED) {
            assertThatThrownBy(() -> wire(ChatMessage.user(List.of(TextBlock.of("hi"), block))))
                    .isInstanceOf(UnsupportedContentBlockException.class)
                    .hasMessageStartingWith("Anthropic cannot send a")
                    .hasMessageContaining(" " + block.getType() + " block")
                    .hasMessageNotContaining("secret")
                    .hasMessageNotContaining(BASE64);
        }
    }

    @Test
    @DisplayName("Media in assistant messages fails explicitly")
    void testMediaInAssistantMessagesFails() {
        ChatMessage assistant =
                ChatMessage.assistant(
                        List.of(TextBlock.of("see"), ImageBlock.fromUrl("image/png", URL)));

        assertThatThrownBy(() -> wire(List.of(ChatMessage.user("hi"), assistant)))
                .isInstanceOf(UnsupportedContentBlockException.class)
                .hasMessage(
                        "Anthropic cannot send an image block (image/png, url source): only user"
                                + " messages and tool results can carry media, not assistant"
                                + " messages.");
    }

    @Test
    @DisplayName("A text-only tool result is sent as before, as one string")
    void testTextOnlyToolResultIsUnchanged() {
        JsonNode message =
                wire(
                        ChatMessage.tool(
                                new ToolResultBlock(
                                        "call-1",
                                        List.of(TextBlock.of("4"), TextBlock.of("2")),
                                        false)));

        assertThat(message.toString())
                .isEqualTo(
                        "{\"content\":[{\"tool_use_id\":\"call-1\",\"type\":\"tool_result\","
                                + "\"content\":\"42\",\"is_error\":false}],\"role\":\"user\"}");
    }

    @Test
    @DisplayName("Media in a tool result goes into tool_result content, in block order")
    void testToolResultMediaIsSentInOrder() {
        List<DataContentBlock> blocks =
                List.of(
                        TextBlock.of("Chart:"),
                        ImageBlock.fromUrl("image/png", URL),
                        ImageBlock.fromBase64("image/png", BASE64),
                        new DocumentBlock(
                                "application/pdf",
                                new Base64Source(BASE64),
                                "report.pdf",
                                null,
                                null),
                        DocumentBlock.fromUrl("application/pdf", "https://example.com/a.pdf"),
                        DocumentBlock.fromBase64("text/plain", BASE64),
                        TextBlock.of("Done."));

        JsonNode result = toolResult(new ToolResultBlock("call-1", blocks, false));

        assertThat(result)
                .isEqualTo(
                        json(
                                Map.of(
                                        "type",
                                        "tool_result",
                                        "tool_use_id",
                                        "call-1",
                                        "content",
                                        List.of(
                                                Map.of("type", "text", "text", "Chart:"),
                                                Map.of(
                                                        "type",
                                                        "image",
                                                        "source",
                                                        Map.of("type", "url", "url", URL)),
                                                Map.of(
                                                        "type",
                                                        "image",
                                                        "source",
                                                        Map.of(
                                                                "type", "base64",
                                                                "media_type", "image/png",
                                                                "data", BASE64)),
                                                Map.of(
                                                        "type", "document",
                                                        "source",
                                                                Map.of(
                                                                        "type", "base64",
                                                                        "media_type",
                                                                                "application/pdf",
                                                                        "data", BASE64),
                                                        "title", "report.pdf"),
                                                Map.of(
                                                        "type",
                                                        "document",
                                                        "source",
                                                        Map.of(
                                                                "type", "url",
                                                                "url",
                                                                        "https://example.com/a.pdf")),
                                                Map.of(
                                                        "type",
                                                        "document",
                                                        "source",
                                                        Map.of(
                                                                "type", "text",
                                                                "media_type", "text/plain",
                                                                "data", "hello")),
                                                Map.of("type", "text", "text", "Done.")),
                                        "is_error",
                                        false)));
        // Each block is sent exactly as in a user message.
        assertThat(result.get("content")).isEqualTo(wire(ChatMessage.user(blocks)).get("content"));
    }

    @Test
    @DisplayName("An error tool result with media keeps is_error")
    void testErrorToolResultWithMedia() {
        JsonNode result =
                toolResult(
                        new ToolResultBlock(
                                "call-1",
                                List.of(
                                        TextBlock.of("Render failed; partial output:"),
                                        ImageBlock.fromUrl("image/png", URL)),
                                true));

        assertThat(result.get("tool_use_id").asText()).isEqualTo("call-1");
        assertThat(result.get("is_error").asBoolean()).isTrue();
        assertThat(result.at("/content/0/type").asText()).isEqualTo("text");
        assertThat(result.at("/content/1/type").asText()).isEqualTo("image");
    }

    @Test
    @DisplayName("Media Anthropic cannot send fails in a tool result as in a user message")
    void testUnsupportedToolResultMediaFailsAsForUserMessages() {
        for (MediaBlock block : UNSUPPORTED) {
            Throwable inUserMessage = catchThrowable(() -> wire(ChatMessage.user(List.of(block))));
            assertThat(inUserMessage).isInstanceOf(UnsupportedContentBlockException.class);

            assertThatThrownBy(
                            () ->
                                    wire(
                                            ChatMessage.tool(
                                                    new ToolResultBlock(
                                                            "call-1",
                                                            List.of(TextBlock.of("hi"), block),
                                                            false))))
                    .isInstanceOf(UnsupportedContentBlockException.class)
                    .hasMessage(inUserMessage.getMessage())
                    .hasMessageNotContaining("secret")
                    .hasMessageNotContaining(BASE64);
        }
    }

    @Test
    @DisplayName("Undecodable plain-text data in a tool result fails as invalid")
    void testInvalidBase64TextDocumentInToolResultFails() {
        ChatMessage message =
                ChatMessage.tool(
                        new ToolResultBlock(
                                "call-1",
                                List.of(DocumentBlock.fromBase64("text/plain", "not base64!")),
                                false));

        assertThatThrownBy(() -> wire(message))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("A document block's base64 data could not be decoded.");
    }

    @Test
    @DisplayName("Parallel tool results keep their own call IDs and content")
    void testParallelToolResultsKeepTheirCorrelation() {
        JsonNode messages =
                wire(
                        List.of(
                                ChatMessage.user("What is the weather, and show me the map."),
                                ChatMessage.assistant(
                                        List.of(
                                                new ToolCallBlock("call-1", "weather", Map.of()),
                                                new ToolCallBlock("call-2", "map", Map.of()))),
                                ChatMessage.tool(
                                        new ToolResultBlock(
                                                "call-1", List.of(TextBlock.of("Sunny")), false)),
                                ChatMessage.tool(
                                        new ToolResultBlock(
                                                "call-2",
                                                List.of(
                                                        TextBlock.of("Map:"),
                                                        ImageBlock.fromUrl("image/png", URL)),
                                                false))));

        assertThat(messages).hasSize(4);
        assertThat(messages.at("/2/content")).hasSize(1);
        assertThat(messages.at("/2/content/0/tool_use_id").asText()).isEqualTo("call-1");
        assertThat(messages.at("/2/content/0/content").asText()).isEqualTo("Sunny");
        assertThat(messages.at("/3/content")).hasSize(1);
        assertThat(messages.at("/3/content/0/tool_use_id").asText()).isEqualTo("call-2");
        assertThat(messages.at("/3/content/0/content")).hasSize(2);
        assertThat(messages.at("/3/content/0/content/0/text").asText()).isEqualTo("Map:");
        assertThat(messages.at("/3/content/0/content/1/source/url").asText()).isEqualTo(URL);
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
