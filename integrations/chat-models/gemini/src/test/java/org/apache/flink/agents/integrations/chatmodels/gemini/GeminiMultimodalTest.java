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

import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.Part;
import org.apache.flink.agents.api.chat.messages.AudioBlock;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.DocumentBlock;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.chat.messages.UnsupportedContentBlockException;
import org.apache.flink.agents.api.chat.messages.VideoBlock;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiMultimodalTest {

    private static final String URL = "https://example.com/cat.png?sig=secret";
    private static final String BASE64 = "aGVsbG8="; // "hello"

    private static List<Part> parts(ChatMessage message) {
        Content content = GeminiChatUtils.convertToContent(message, Map.of());
        assertThat(content.role()).hasValue("user");
        return content.parts().orElseThrow();
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
    @DisplayName("Media in assistant messages and tool results fails without leaking the source")
    void testMediaOutsideUserMessagesFails() {
        ChatMessage assistant =
                ChatMessage.assistant(
                        List.of(TextBlock.of("see"), ImageBlock.fromUrl("image/png", URL)));
        ChatMessage tool =
                ChatMessage.tool(
                        new ToolResultBlock(
                                "call-1",
                                List.of(
                                        TextBlock.of("see"),
                                        ImageBlock.fromBase64("image/png", BASE64)),
                                false));
        Map<String, String> names = Map.of("call-1", "lookup");

        for (ChatMessage message : List.of(assistant, tool)) {
            assertThatThrownBy(() -> GeminiChatUtils.convertToContent(message, names))
                    .isInstanceOf(UnsupportedContentBlockException.class)
                    .hasMessageStartingWith("Gemini cannot send an image block (image/png, ")
                    .hasMessageContaining(
                            "only user messages can carry media, not "
                                    + message.getRole().getValue()
                                    + " messages")
                    .hasMessageNotContaining("secret")
                    .hasMessageNotContaining(BASE64);
        }
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
                        descriptor, ResourceContext.fromGetResource((a, b) -> null));
        ChatMessage assistant =
                ChatMessage.assistant(
                        List.of(TextBlock.of("hi"), AudioBlock.fromBase64("audio/wav", BASE64)));
        ChatMessage malformed =
                ChatMessage.user(List.of(ImageBlock.fromBase64("image/png", "not base64!")));

        assertThatThrownBy(() -> connection.chat(List.of(assistant), List.of(), Map.of()))
                .isExactlyInstanceOf(UnsupportedContentBlockException.class);
        assertThatThrownBy(() -> connection.chat(List.of(malformed), List.of(), Map.of()))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }
}
