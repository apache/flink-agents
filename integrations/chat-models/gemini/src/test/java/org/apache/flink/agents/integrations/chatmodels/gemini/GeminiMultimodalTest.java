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

package org.apache.flink.agents.integrations.chatmodels.gemini;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.JsonSerializable;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.FunctionResponsePart;
import com.google.genai.types.Part;
import org.apache.flink.agents.api.chat.messages.AudioBlock;
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
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.TestResourceContexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class GeminiMultimodalTest {

    private static final String URL = "https://example.com/cat.png?sig=secret";
    private static final String BASE64 = "aGVsbG8="; // "hello"
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String AUDIO_VIDEO =
            ": audio and video are accepted in user messages, not in tool results.";
    private static final String OTHER_TYPE =
            ": tool results accept only PNG, JPEG or WebP images and PDF or plain-text documents.";

    private static List<Part> parts(ChatMessage message) {
        Content content = GeminiChatUtils.convertToContent(message, Map.of());
        assertThat(content.role()).hasValue("user");
        return content.parts().orElseThrow();
    }

    private static ChatMessage toolResult(boolean isError, DataContentBlock... blocks) {
        return ChatMessage.tool(new ToolResultBlock("call-1", List.of(blocks), isError));
    }

    /** The tool message's turn, for a result of a call to {@code lookup}. */
    private static Content toolTurn(ChatMessage message) {
        return GeminiChatUtils.convertToContent(message, Map.of("call-1", "lookup"));
    }

    private static JsonNode json(JsonSerializable value) {
        try {
            return MAPPER.readTree(value.toJson());
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The expected turn: one function response with the result's text and the media parts. */
    private static JsonNode functionResponseTurn(
            String name, String text, List<Map<String, Object>> parts) {
        return MAPPER.valueToTree(
                Map.of(
                        "role",
                        "user",
                        "parts",
                        List.of(
                                Map.of(
                                        "functionResponse",
                                        Map.of(
                                                "name",
                                                name,
                                                "response",
                                                Map.of("result", text),
                                                "parts",
                                                parts)))));
    }

    private static Map<String, Object> inlineData(String mimeType) {
        return Map.of("inlineData", Map.of("mimeType", mimeType, "data", BASE64));
    }

    private static Map<String, Object> fileData(String uri, String mimeType) {
        return Map.of("fileData", Map.of("fileUri", uri, "mimeType", mimeType));
    }

    private static void assertRejectedInToolResult(MediaBlock block, String message) {
        assertThatThrownBy(() -> toolTurn(toolResult(false, TextBlock.of("see"), block)))
                .isExactlyInstanceOf(UnsupportedContentBlockException.class)
                .hasMessage(message);
    }

    @Test
    @DisplayName("A text-only user message is sent as before, one text part per block")
    void testTextOnlyUserMessageIsUnchanged() {
        List<Part> parts = parts(ChatMessage.user(List.of(TextBlock.of("hi"))));

        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).text()).hasValue("hi");
        assertThat(parts.get(0).inlineData()).isEmpty();
        assertThat(parts.get(0).fileData()).isEmpty();
    }

    @Test
    @DisplayName("Base64 media becomes inline data with the block's media type, in block order")
    void testBase64MediaBecomesInlineData() {
        List<Part> parts =
                parts(
                        ChatMessage.user(
                                List.of(
                                        TextBlock.of("Compare"),
                                        ImageBlock.fromBase64("image/png", BASE64),
                                        AudioBlock.fromBase64("audio/wav", BASE64),
                                        VideoBlock.fromBase64("video/mp4", BASE64),
                                        DocumentBlock.fromBase64("application/pdf", BASE64))));

        assertThat(parts).hasSize(5);
        assertThat(parts.get(0).text()).hasValue("Compare");
        List<String> mediaTypes = List.of("image/png", "audio/wav", "video/mp4", "application/pdf");
        for (int i = 0; i < mediaTypes.size(); i++) {
            Blob blob = parts.get(i + 1).inlineData().orElseThrow();
            assertThat(blob.mimeType()).hasValue(mediaTypes.get(i));
            assertThat(blob.data()).hasValue("hello".getBytes(StandardCharsets.UTF_8));
            assertThat(parts.get(i + 1).fileData()).isEmpty();
        }
    }

    @Test
    @DisplayName("URL media becomes file data with the URL and the block's media type")
    void testUrlMediaBecomesFileData() {
        List<Part> parts =
                parts(
                        ChatMessage.user(
                                List.of(
                                        ImageBlock.fromUrl("image/png", URL),
                                        AudioBlock.fromUrl("audio/mp3", "gs://bucket/a.mp3"),
                                        VideoBlock.fromUrl("video/mp4", "https://example.com/v"),
                                        DocumentBlock.fromUrl(
                                                "application/pdf", "https://example.com/a.pdf"),
                                        TextBlock.of("Summarize"))));

        assertThat(parts).hasSize(5);
        List<String> urls =
                List.of(
                        URL,
                        "gs://bucket/a.mp3",
                        "https://example.com/v",
                        "https://example.com/a.pdf");
        List<String> mediaTypes = List.of("image/png", "audio/mp3", "video/mp4", "application/pdf");
        for (int i = 0; i < urls.size(); i++) {
            FileData file = parts.get(i).fileData().orElseThrow();
            assertThat(file.fileUri()).hasValue(urls.get(i));
            assertThat(file.mimeType()).hasValue(mediaTypes.get(i));
            assertThat(parts.get(i).inlineData()).isEmpty();
        }
        assertThat(parts.get(4).text()).hasValue("Summarize");
    }

    @Test
    @DisplayName("Media in assistant messages fails without leaking the source")
    void testMediaInAssistantMessagesFails() {
        ChatMessage assistant =
                ChatMessage.assistant(
                        List.of(TextBlock.of("see"), ImageBlock.fromUrl("image/png", URL)));

        assertThatThrownBy(() -> GeminiChatUtils.convertToContent(assistant, Map.of()))
                .isInstanceOf(UnsupportedContentBlockException.class)
                .hasMessage(
                        "Gemini cannot send an image block (image/png, url source): only user"
                                + " messages and tool results can carry media, not assistant"
                                + " messages.");
    }

    @Test
    @DisplayName("A text-only tool result is sent as before")
    void testTextOnlyToolResultIsUnchanged() {
        assertThat(toolTurn(toolResult(false, TextBlock.of("4"), TextBlock.of("2"))).toJson())
                .isEqualTo(
                        "{\"parts\":[{\"functionResponse\":{\"parts\":[],\"name\":\"lookup\","
                                + "\"response\":{\"result\":\"42\"}}}],\"role\":\"user\"}");
        assertThat(toolTurn(toolResult(false)).toJson())
                .isEqualTo(
                        "{\"parts\":[{\"functionResponse\":{\"parts\":[],\"name\":\"lookup\","
                                + "\"response\":{\"result\":\"\"}}}],\"role\":\"user\"}");
        assertThat(toolTurn(toolResult(true, TextBlock.of("Not found"))).toJson())
                .isEqualTo(
                        "{\"parts\":[{\"functionResponse\":{\"parts\":[],\"name\":\"lookup\","
                                + "\"response\":{\"result\":\"Not found\"}}}],\"role\":\"user\"}");
    }

    @Test
    @DisplayName(
            "Images and documents in a tool result go into the function response's parts, in block"
                    + " order")
    void testToolResultMediaIsSentInOrder() {
        MediaBlock[] media = {
            ImageBlock.fromBase64("image/png", BASE64),
            ImageBlock.fromUrl("image/jpeg", URL),
            ImageBlock.fromBase64("image/webp", BASE64),
            DocumentBlock.fromBase64("application/pdf", BASE64),
            DocumentBlock.fromUrl("application/pdf", "https://example.com/a.pdf"),
            DocumentBlock.fromBase64("text/plain", BASE64)
        };

        Content content =
                toolTurn(
                        toolResult(
                                false,
                                TextBlock.of("Charts:"),
                                media[0],
                                media[1],
                                media[2],
                                TextBlock.of("Reports:"),
                                media[3],
                                media[4],
                                media[5],
                                TextBlock.of("Done.")));

        assertThat(json(content))
                .isEqualTo(
                        functionResponseTurn(
                                "lookup",
                                "Charts:Reports:Done.",
                                List.of(
                                        inlineData("image/png"),
                                        fileData(URL, "image/jpeg"),
                                        inlineData("image/webp"),
                                        inlineData("application/pdf"),
                                        fileData("https://example.com/a.pdf", "application/pdf"),
                                        inlineData("text/plain"))));
        // Each block is sent with the same values as in a user message.
        List<FunctionResponsePart> toolParts =
                content.parts()
                        .orElseThrow()
                        .get(0)
                        .functionResponse()
                        .orElseThrow()
                        .parts()
                        .orElseThrow();
        List<Part> userParts = parts(ChatMessage.user(List.of(media)));
        assertThat(toolParts).hasSameSizeAs(userParts);
        for (int i = 0; i < media.length; i++) {
            assertThat(json(toolParts.get(i))).isEqualTo(json(userParts.get(i)));
        }
    }

    @Test
    @DisplayName("Audio and video in a tool result fail, and still convert in a user message")
    void testAudioAndVideoInToolResultFail() {
        assertRejectedInToolResult(
                AudioBlock.fromBase64("audio/wav", BASE64),
                "Gemini cannot send an audio block (audio/wav, base64 source)" + AUDIO_VIDEO);
        assertRejectedInToolResult(
                AudioBlock.fromUrl("audio/mpeg", URL),
                "Gemini cannot send an audio block (audio/mpeg, url source)" + AUDIO_VIDEO);
        assertRejectedInToolResult(
                VideoBlock.fromBase64("video/mp4", BASE64),
                "Gemini cannot send a video block (video/mp4, base64 source)" + AUDIO_VIDEO);
        assertRejectedInToolResult(
                VideoBlock.fromUrl("video/mp4", URL),
                "Gemini cannot send a video block (video/mp4, url source)" + AUDIO_VIDEO);

        List<Part> userParts =
                parts(
                        ChatMessage.user(
                                List.of(
                                        AudioBlock.fromBase64("audio/wav", BASE64),
                                        AudioBlock.fromUrl("audio/mpeg", URL),
                                        VideoBlock.fromBase64("video/mp4", BASE64),
                                        VideoBlock.fromUrl("video/mp4", URL))));
        assertThat(userParts.stream().map(GeminiMultimodalTest::json))
                .containsExactly(
                        MAPPER.valueToTree(inlineData("audio/wav")),
                        MAPPER.valueToTree(fileData(URL, "audio/mpeg")),
                        MAPPER.valueToTree(inlineData("video/mp4")),
                        MAPPER.valueToTree(fileData(URL, "video/mp4")));
    }

    @Test
    @DisplayName("Other media types in a tool result fail, whatever the block kind")
    void testOtherMediaTypesInToolResultFail() {
        assertRejectedInToolResult(
                ImageBlock.fromUrl("image/gif", URL),
                "Gemini cannot send an image block (image/gif, url source)" + OTHER_TYPE);
        assertRejectedInToolResult(
                DocumentBlock.fromBase64("text/csv", BASE64),
                "Gemini cannot send a document block (text/csv, base64 source)" + OTHER_TYPE);
        assertRejectedInToolResult(
                ImageBlock.fromBase64("application/pdf", BASE64),
                "Gemini cannot send an image block (application/pdf, base64 source)" + OTHER_TYPE);
        assertRejectedInToolResult(
                DocumentBlock.fromUrl("image/png", URL),
                "Gemini cannot send a document block (image/png, url source)" + OTHER_TYPE);
    }

    @Test
    @DisplayName("Tool-result media types ignore parameters and case, and are sent as given")
    void testToolResultMediaTypeParametersAreAccepted() {
        assertThat(
                        json(
                                toolTurn(
                                        toolResult(
                                                false,
                                                DocumentBlock.fromBase64(
                                                        "text/plain; charset=utf-8", BASE64),
                                                ImageBlock.fromUrl("IMAGE/PNG", URL)))))
                .isEqualTo(
                        functionResponseTurn(
                                "lookup",
                                "",
                                List.of(
                                        inlineData("text/plain; charset=utf-8"),
                                        fileData(URL, "IMAGE/PNG"))));
    }

    @Test
    @DisplayName("A rejected tool-result block fails before its data is decoded")
    void testRejectedToolResultBlockIsNotDecoded() {
        assertRejectedInToolResult(
                AudioBlock.fromBase64("audio/wav", "not base64!"),
                "Gemini cannot send an audio block (audio/wav, base64 source)" + AUDIO_VIDEO);
    }

    @Test
    @DisplayName("One rejected block fails the whole tool result")
    void testOneRejectedBlockFailsTheWholeToolResult() {
        assertThatThrownBy(
                        () ->
                                toolTurn(
                                        toolResult(
                                                false,
                                                ImageBlock.fromBase64("image/png", BASE64),
                                                TextBlock.of("and"),
                                                AudioBlock.fromUrl("audio/wav", URL))))
                .isExactlyInstanceOf(UnsupportedContentBlockException.class)
                .hasMessage(
                        "Gemini cannot send an audio block (audio/wav, url source)" + AUDIO_VIDEO);
    }

    @Test
    @DisplayName("A tool result with only media sends an empty text result")
    void testMediaOnlyToolResultSendsEmptyText() {
        assertThat(json(toolTurn(toolResult(false, ImageBlock.fromUrl("image/png", URL)))))
                .isEqualTo(functionResponseTurn("lookup", "", List.of(fileData(URL, "image/png"))));
    }

    @Test
    @DisplayName("An error tool result with media is sent like any other, with no error field")
    void testErrorToolResultWithMediaSendsNoErrorField() {
        DataContentBlock[] blocks = {
            TextBlock.of("Render failed; partial output:"), ImageBlock.fromUrl("image/png", URL)
        };

        JsonNode turn = json(toolTurn(toolResult(true, blocks)));

        assertThat(turn).isEqualTo(json(toolTurn(toolResult(false, blocks))));
        JsonNode functionResponse = turn.at("/parts/0/functionResponse");
        assertThat(functionResponse.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("name", "response", "parts");
        assertThat(functionResponse.get("response").fieldNames())
                .toIterable()
                .containsExactly("result");
    }

    @Test
    @DisplayName("Malformed Base64 data in a tool result fails as in a user message")
    void testMalformedBase64InToolResultFails() {
        ImageBlock malformed = ImageBlock.fromBase64("image/png", "not base64!");
        Throwable inUserMessage = catchThrowable(() -> parts(ChatMessage.user(List.of(malformed))));
        assertThat(inUserMessage).isExactlyInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> toolTurn(toolResult(false, TextBlock.of("see"), malformed)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage(inUserMessage.getMessage());
    }

    @Test
    @DisplayName("Parallel tool results keep their own function names and responses")
    void testParallelToolResultsKeepTheirCorrelation() {
        List<ChatMessage> messages =
                List.of(
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
                                        false)));
        Map<String, String> names = GeminiChatUtils.buildToolCallIdToNameMap(messages);

        assertThat(json(GeminiChatUtils.convertToContent(messages.get(1), names)))
                .isEqualTo(functionResponseTurn("weather", "Sunny", List.of()));
        assertThat(json(GeminiChatUtils.convertToContent(messages.get(2), names)))
                .isEqualTo(
                        functionResponseTurn("map", "Map:", List.of(fileData(URL, "image/png"))));
    }

    @Test
    @DisplayName("Malformed Base64 data fails while the request is built")
    void testMalformedBase64Fails() {
        ChatMessage message =
                ChatMessage.user(List.of(ImageBlock.fromBase64("image/png", "not base64!")));

        assertThatThrownBy(() -> parts(message)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("chat() surfaces media errors with their own types, before any request")
    void testChatKeepsMediaErrorTypes() {
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(GeminiChatModelConnection.class.getName())
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("model", "gemini-3-pro-preview")
                        .build();
        GeminiChatModelConnection connection =
                new GeminiChatModelConnection(
                        descriptor, TestResourceContexts.fromGetResource((a, b) -> null));
        ChatMessage assistant =
                ChatMessage.assistant(
                        List.of(TextBlock.of("hi"), AudioBlock.fromBase64("audio/wav", BASE64)));
        ChatMessage malformed =
                ChatMessage.user(List.of(ImageBlock.fromBase64("image/png", "not base64!")));
        List<ChatMessage> malformedToolResult =
                List.of(
                        ChatMessage.assistant(
                                List.of(new ToolCallBlock("call-1", "lookup", Map.of()))),
                        toolResult(false, ImageBlock.fromBase64("image/png", "not base64!")));
        List<ChatMessage> audioToolResult =
                List.of(
                        ChatMessage.assistant(
                                List.of(new ToolCallBlock("call-1", "lookup", Map.of()))),
                        toolResult(false, AudioBlock.fromBase64("audio/wav", BASE64)));

        assertThatThrownBy(() -> connection.chat(List.of(assistant), List.of(), Map.of()))
                .isExactlyInstanceOf(UnsupportedContentBlockException.class);
        assertThatThrownBy(() -> connection.chat(List.of(malformed), List.of(), Map.of()))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> connection.chat(malformedToolResult, List.of(), Map.of()))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> connection.chat(audioToolResult, List.of(), Map.of()))
                .isExactlyInstanceOf(UnsupportedContentBlockException.class);
    }
}
