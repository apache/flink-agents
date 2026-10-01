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
import org.apache.flink.agents.api.chat.messages.ContentBlock;
import org.apache.flink.agents.api.chat.messages.DocumentBlock;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.UnsupportedContentBlockException;
import org.apache.flink.agents.api.chat.messages.VideoBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests how content blocks become Chat Completions content parts. Assertions run on the serialized
 * request message, i.e. the wire shape OpenAI, Azure OpenAI and vLLM receive.
 */
class OpenAIChatCompletionsMultimodalTest {

    private static final String IMAGE_URL = "https://example.com/cat.png?sig=secret";
    private static final String BASE64 = "aGVsbG8=";

    private static JsonNode wire(ChatMessage message) {
        return ObjectMappers.jsonMapper()
                .valueToTree(OpenAIChatCompletionsUtils.convertToOpenAIMessage(message));
    }

    @Test
    @DisplayName("A text-only user message keeps plain string content")
    void testTextOnlyUserMessageKeepsStringContent() {
        JsonNode message =
                wire(ChatMessage.user(List.of(TextBlock.of("Describe "), TextBlock.of("this"))));

        assertThat(message.get("content").isTextual()).isTrue();
        assertThat(message.get("content").asText()).isEqualTo("Describe this");
    }

    @Test
    @DisplayName("User media becomes content parts in block order")
    void testUserMediaBecomesOrderedContentParts() {
        JsonNode parts =
                wire(ChatMessage.user(
                                List.of(
                                        TextBlock.of("Compare"),
                                        ImageBlock.fromUrl("image/png", IMAGE_URL),
                                        ImageBlock.fromBase64("image/jpeg", BASE64))))
                        .get("content");

        assertThat(parts).hasSize(3);
        assertThat(parts.get(0).get("type").asText()).isEqualTo("text");
        assertThat(parts.get(0).get("text").asText()).isEqualTo("Compare");
        assertThat(parts.get(1).get("type").asText()).isEqualTo("image_url");
        assertThat(parts.get(1).at("/image_url/url").asText()).isEqualTo(IMAGE_URL);
        assertThat(parts.get(2).at("/image_url/url").asText())
                .isEqualTo("data:image/jpeg;base64," + BASE64);
    }

    @Test
    @DisplayName("WAV and MP3 audio becomes input_audio with the matching format")
    void testAudioBecomesInputAudio() {
        JsonNode parts =
                wire(ChatMessage.user(
                                List.of(
                                        AudioBlock.fromBase64("audio/wav", BASE64),
                                        AudioBlock.fromBase64("audio/mpeg", BASE64))))
                        .get("content");

        assertThat(parts.get(0).get("type").asText()).isEqualTo("input_audio");
        assertThat(parts.get(0).at("/input_audio/data").asText()).isEqualTo(BASE64);
        assertThat(parts.get(0).at("/input_audio/format").asText()).isEqualTo("wav");
        assertThat(parts.get(1).at("/input_audio/format").asText()).isEqualTo("mp3");
    }

    @Test
    @DisplayName("A base64 document becomes a file part with a data URI and a file name")
    void testDocumentBecomesFilePart() {
        JsonNode parts =
                wire(ChatMessage.user(
                                List.of(
                                        new DocumentBlock(
                                                "application/pdf",
                                                new Base64Source(BASE64),
                                                "report.pdf",
                                                null,
                                                null),
                                        DocumentBlock.fromBase64("application/pdf", BASE64))))
                        .get("content");

        assertThat(parts.get(0).get("type").asText()).isEqualTo("file");
        assertThat(parts.get(0).at("/file/file_data").asText())
                .isEqualTo("data:application/pdf;base64," + BASE64);
        assertThat(parts.get(0).at("/file/filename").asText()).isEqualTo("report.pdf");
        assertThat(parts.get(1).at("/file/filename").asText()).isEqualTo("document");
    }

    @Test
    @DisplayName("Blocks without a Chat Completions content part fail without leaking the source")
    void testUnsupportedUserBlocksFailExplicitly() {
        List<ContentBlock> unsupported =
                List.of(
                        VideoBlock.fromUrl("video/mp4", IMAGE_URL),
                        AudioBlock.fromUrl("audio/wav", IMAGE_URL),
                        AudioBlock.fromBase64("audio/ogg", BASE64),
                        DocumentBlock.fromUrl("application/pdf", IMAGE_URL));
        for (ContentBlock block : unsupported) {
            assertThatThrownBy(
                            () ->
                                    OpenAIChatCompletionsUtils.convertToOpenAIMessage(
                                            ChatMessage.user(List.of(TextBlock.of("hi"), block))))
                    .isInstanceOf(UnsupportedContentBlockException.class)
                    .hasMessageContaining(block.getType() + " block")
                    .hasMessageNotContaining("secret")
                    .hasMessageNotContaining(BASE64);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = MessageRole.class,
            names = {"ASSISTANT", "TOOL"})
    @DisplayName("Media outside user messages fails explicitly")
    void testMediaOutsideUserMessagesFails(MessageRole role) {
        List<org.apache.flink.agents.api.chat.messages.ContentBlock> blocks =
                List.of(TextBlock.of("see"), ImageBlock.fromUrl("image/png", IMAGE_URL));
        ChatMessage message =
                role == MessageRole.TOOL
                        ? ChatMessage.tool(
                                new org.apache.flink.agents.api.chat.messages.ToolResultBlock(
                                        "call-1", blocks, false))
                        : new ChatMessage(role, blocks);

        assertThatThrownBy(() -> OpenAIChatCompletionsUtils.convertToOpenAIMessage(message))
                .isInstanceOf(UnsupportedContentBlockException.class)
                .hasMessageContaining("only user messages can carry media");
    }
}
