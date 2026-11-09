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

package org.apache.flink.agents.integrations.chatmodels.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.core.ObjectMappers;
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
import org.apache.flink.agents.api.chat.messages.UrlSource;
import org.apache.flink.agents.api.chat.messages.VideoBlock;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.TestResourceContexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Tests how content blocks become OpenAI Responses API input content. */
class OpenAIResponsesMultimodalTest {

    private static final String URL = "https://example.com/cat.png?sig=secret";
    private static final String BASE64 = "aGVsbG8="; // "hello"

    /** Media the Responses API has no input content for. */
    private static final List<MediaBlock> UNSUPPORTED =
            List.of(
                    AudioBlock.fromBase64("audio/wav", BASE64),
                    VideoBlock.fromUrl("video/mp4", URL));

    /** The message's input item, as the SDK serializes it. */
    private static JsonNode wire(ChatMessage message) {
        List<com.openai.models.responses.ResponseInputItem> items =
                OpenAIResponsesUtils.convertSingleMessage(message);
        assertThat(items).hasSize(1);
        return json(items.get(0));
    }

    private static JsonNode json(Object value) {
        return ObjectMappers.jsonMapper().valueToTree(value);
    }

    private static ChatMessage toolResult(boolean isError, DataContentBlock... blocks) {
        return ChatMessage.tool(new ToolResultBlock("call-1", List.of(blocks), isError));
    }

    @Test
    @DisplayName("A text-only user message is sent as before, as one input_text")
    void testTextOnlyUserMessageIsUnchanged() {
        JsonNode content = wire(ChatMessage.user(List.of(TextBlock.of("hi")))).get("content");

        assertThat(content).hasSize(1);
        assertThat(content.at("/0/type").asText()).isEqualTo("input_text");
        assertThat(content.at("/0/text").asText()).isEqualTo("hi");
    }

    @Test
    @DisplayName("Images become input_image in block order, by URL or as a data URI")
    void testImagesBecomeInputImages() {
        JsonNode content =
                wire(ChatMessage.user(
                                List.of(
                                        TextBlock.of("Compare"),
                                        ImageBlock.fromUrl("image/png", URL),
                                        ImageBlock.fromBase64("image/png", BASE64))))
                        .get("content");

        assertThat(content).hasSize(3);
        assertThat(content.at("/0/type").asText()).isEqualTo("input_text");
        assertThat(content.at("/0/text").asText()).isEqualTo("Compare");
        assertThat(content.at("/1/type").asText()).isEqualTo("input_image");
        assertThat(content.at("/1/image_url").asText()).isEqualTo(URL);
        assertThat(content.at("/1/detail").asText()).isEqualTo("auto");
        assertThat(content.at("/2/image_url").asText())
                .isEqualTo("data:image/png;base64," + BASE64);
    }

    @Test
    @DisplayName(
            "Documents become input_file: Base64 as file_data with a filename, URLs as file_url")
    void testDocumentsBecomeInputFiles() {
        JsonNode content =
                wire(ChatMessage.user(
                                List.of(
                                        new DocumentBlock(
                                                "application/pdf",
                                                new Base64Source(BASE64),
                                                "report.pdf",
                                                null,
                                                null),
                                        DocumentBlock.fromBase64("application/pdf", BASE64),
                                        DocumentBlock.fromUrl(
                                                "application/pdf", "https://example.com/a.pdf"),
                                        new DocumentBlock(
                                                "application/pdf",
                                                new UrlSource("https://example.com/b.pdf"),
                                                "b.pdf",
                                                null,
                                                null))))
                        .get("content");

        assertThat(content.at("/0/type").asText()).isEqualTo("input_file");
        assertThat(content.at("/0/file_data").asText())
                .isEqualTo("data:application/pdf;base64," + BASE64);
        assertThat(content.at("/0/filename").asText()).isEqualTo("report.pdf");
        assertThat(content.at("/1/filename").asText()).isEqualTo("document");
        assertThat(content.at("/2/file_url").asText()).isEqualTo("https://example.com/a.pdf");
        assertThat(content.get(2).has("file_data")).isFalse();
        assertThat(content.get(2).has("filename")).isFalse();
        assertThat(content.at("/3/file_url").asText()).isEqualTo("https://example.com/b.pdf");
        assertThat(content.at("/3/filename").asText()).isEqualTo("b.pdf");
    }

    @Test
    @DisplayName("Audio and video fail without leaking the source")
    void testUnsupportedBlocksFailExplicitly() {
        for (MediaBlock block : UNSUPPORTED) {
            assertThatThrownBy(() -> wire(ChatMessage.user(List.of(TextBlock.of("hi"), block))))
                    .isInstanceOf(UnsupportedContentBlockException.class)
                    .hasMessageStartingWith("OpenAI Responses cannot send a")
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

        assertThatThrownBy(() -> OpenAIResponsesUtils.convertSingleMessage(assistant))
                .isInstanceOf(UnsupportedContentBlockException.class)
                .hasMessage(
                        "OpenAI Responses cannot send an image block (image/png, url source): only"
                                + " user messages and tool results can carry media, not assistant"
                                + " messages.");
    }

    @Test
    @DisplayName("A text-only tool result is sent as before, as one string")
    void testTextOnlyToolResultIsUnchanged() {
        assertThat(wire(toolResult(false, TextBlock.of("4"), TextBlock.of("2"))).toString())
                .isEqualTo(
                        "{\"call_id\":\"call-1\",\"output\":\"42\","
                                + "\"type\":\"function_call_output\"}");
        assertThat(wire(toolResult(false)).toString())
                .isEqualTo(
                        "{\"call_id\":\"call-1\",\"output\":\"\","
                                + "\"type\":\"function_call_output\"}");
        assertThat(wire(toolResult(true, TextBlock.of("Not found"))).toString())
                .isEqualTo(
                        "{\"call_id\":\"call-1\",\"output\":\"Not found\","
                                + "\"type\":\"function_call_output\"}");
    }

    @Test
    @DisplayName("Media in a tool result goes into a list output, in block order")
    void testToolResultMediaIsSentInOrder() {
        DataContentBlock[] blocks = {
            TextBlock.of("Chart:"),
            ImageBlock.fromUrl("image/png", URL),
            ImageBlock.fromBase64("image/png", BASE64),
            new DocumentBlock(
                    "application/pdf", new Base64Source(BASE64), "report.pdf", null, null),
            DocumentBlock.fromBase64("application/pdf", BASE64),
            DocumentBlock.fromUrl("application/pdf", "https://example.com/a.pdf"),
            new DocumentBlock(
                    "application/pdf",
                    new UrlSource("https://example.com/b.pdf"),
                    "b.pdf",
                    null,
                    null),
            TextBlock.of("Done.")
        };

        JsonNode item = wire(toolResult(false, blocks));

        assertThat(item)
                .isEqualTo(
                        json(
                                Map.of(
                                        "type",
                                        "function_call_output",
                                        "call_id",
                                        "call-1",
                                        "output",
                                        List.of(
                                                Map.of("type", "input_text", "text", "Chart:"),
                                                Map.of(
                                                        "type", "input_image",
                                                        "image_url", URL,
                                                        "detail", "auto"),
                                                Map.of(
                                                        "type", "input_image",
                                                        "image_url",
                                                                "data:image/png;base64," + BASE64,
                                                        "detail", "auto"),
                                                Map.of(
                                                        "type", "input_file",
                                                        "file_data",
                                                                "data:application/pdf;base64,"
                                                                        + BASE64,
                                                        "filename", "report.pdf"),
                                                Map.of(
                                                        "type", "input_file",
                                                        "file_data",
                                                                "data:application/pdf;base64,"
                                                                        + BASE64,
                                                        "filename", "document"),
                                                Map.of(
                                                        "type", "input_file",
                                                        "file_url", "https://example.com/a.pdf"),
                                                Map.of(
                                                        "type", "input_file",
                                                        "file_url", "https://example.com/b.pdf",
                                                        "filename", "b.pdf"),
                                                Map.of("type", "input_text", "text", "Done.")))));
        // Each block is sent exactly as in a user message.
        assertThat(item.get("output"))
                .isEqualTo(wire(ChatMessage.user(List.of(blocks))).get("content"));
    }

    @Test
    @DisplayName("An error tool result with media is sent like any other, with no error field")
    void testErrorToolResultWithMediaSendsNoErrorField() {
        DataContentBlock[] blocks = {
            TextBlock.of("Render failed; partial output:"), ImageBlock.fromUrl("image/png", URL)
        };

        JsonNode item = wire(toolResult(true, blocks));

        assertThat(item).isEqualTo(wire(toolResult(false, blocks)));
        assertThat(item.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("call_id", "output", "type");
    }

    @Test
    @DisplayName("Audio and video fail in a tool result as in a user message")
    void testUnsupportedToolResultMediaFailsAsForUserMessages() {
        for (MediaBlock block : UNSUPPORTED) {
            Throwable inUserMessage = catchThrowable(() -> wire(ChatMessage.user(List.of(block))));
            assertThat(inUserMessage).isInstanceOf(UnsupportedContentBlockException.class);

            assertThatThrownBy(() -> wire(toolResult(false, TextBlock.of("hi"), block)))
                    .isInstanceOf(UnsupportedContentBlockException.class)
                    .hasMessage(inUserMessage.getMessage())
                    .hasMessageContaining("the Responses API takes images and documents only")
                    .hasMessageNotContaining("secret")
                    .hasMessageNotContaining(BASE64);
        }
    }

    @Test
    @DisplayName("Parallel tool results keep their own call IDs and outputs")
    void testParallelToolResultsKeepTheirCorrelation() {
        JsonNode items =
                json(
                        OpenAIResponsesUtils.convertInputItems(
                                List.of(
                                        ChatMessage.assistant(
                                                List.of(
                                                        new ToolCallBlock(
                                                                "call-1", "weather", Map.of()),
                                                        new ToolCallBlock(
                                                                "call-2", "map", Map.of()))),
                                        ChatMessage.tool(
                                                new ToolResultBlock(
                                                        "call-1",
                                                        List.of(TextBlock.of("Sunny")),
                                                        false)),
                                        ChatMessage.tool(
                                                new ToolResultBlock(
                                                        "call-2",
                                                        List.of(
                                                                TextBlock.of("Map:"),
                                                                ImageBlock.fromUrl(
                                                                        "image/png", URL)),
                                                        false)))));

        assertThat(items).hasSize(4);
        assertThat(items.at("/0/call_id").asText()).isEqualTo("call-1");
        assertThat(items.at("/1/call_id").asText()).isEqualTo("call-2");
        assertThat(items.get(2))
                .isEqualTo(
                        json(
                                Map.of(
                                        "type", "function_call_output",
                                        "call_id", "call-1",
                                        "output", "Sunny")));
        assertThat(items.get(3))
                .isEqualTo(
                        json(
                                Map.of(
                                        "type",
                                        "function_call_output",
                                        "call_id",
                                        "call-2",
                                        "output",
                                        List.of(
                                                Map.of("type", "input_text", "text", "Map:"),
                                                Map.of(
                                                        "type", "input_image",
                                                        "image_url", URL,
                                                        "detail", "auto")))));
    }

    @Test
    @DisplayName("chat() surfaces media errors with their own types, before any request")
    void testChatKeepsMediaErrorTypes() {
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(
                                OpenAIResponsesModelConnection.class.getName())
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("model", "gpt-4o")
                        .build();
        OpenAIResponsesModelConnection connection =
                new OpenAIResponsesModelConnection(
                        descriptor, TestResourceContexts.fromGetResource((a, b) -> null));
        AudioBlock audio = AudioBlock.fromBase64("audio/wav", BASE64);

        for (ChatMessage message :
                List.of(ChatMessage.user(List.of(audio)), toolResult(false, audio))) {
            assertThatThrownBy(() -> connection.chat(List.of(message), List.of(), Map.of()))
                    .isExactlyInstanceOf(UnsupportedContentBlockException.class);
        }
    }
}
