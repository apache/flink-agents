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
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.chat.messages.UnsupportedContentBlockException;
import org.apache.flink.agents.api.chat.messages.UrlSource;
import org.apache.flink.agents.api.chat.messages.VideoBlock;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests how content blocks become OpenAI Responses API input content. */
class OpenAIResponsesMultimodalTest {

    private static final String URL = "https://example.com/cat.png?sig=secret";
    private static final String BASE64 = "aGVsbG8="; // "hello"

    /** The message's input item, as the SDK serializes it. */
    private static JsonNode wire(ChatMessage message) {
        List<com.openai.models.responses.ResponseInputItem> items =
                OpenAIResponsesUtils.convertSingleMessage(message);
        assertThat(items).hasSize(1);
        return ObjectMappers.jsonMapper().valueToTree(items.get(0));
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
        for (ContentBlock block :
                List.<ContentBlock>of(
                        AudioBlock.fromBase64("audio/wav", BASE64),
                        VideoBlock.fromUrl("video/mp4", URL))) {
            assertThatThrownBy(() -> wire(ChatMessage.user(List.of(TextBlock.of("hi"), block))))
                    .isInstanceOf(UnsupportedContentBlockException.class)
                    .hasMessageStartingWith("OpenAI Responses cannot send a")
                    .hasMessageContaining(" " + block.getType() + " block")
                    .hasMessageNotContaining("secret")
                    .hasMessageNotContaining(BASE64);
        }
    }

    @Test
    @DisplayName("Media in assistant messages and tool results fails explicitly")
    void testMediaOutsideUserMessagesFails() {
        List<ContentBlock> blocks =
                List.of(TextBlock.of("see"), ImageBlock.fromUrl("image/png", URL));
        ChatMessage assistant = ChatMessage.assistant(blocks);
        ChatMessage tool =
                ChatMessage.tool(
                        new ToolResultBlock(
                                "call-1",
                                List.of(TextBlock.of("see"), ImageBlock.fromUrl("image/png", URL)),
                                false));

        for (ChatMessage message : List.of(assistant, tool)) {
            assertThatThrownBy(() -> OpenAIResponsesUtils.convertSingleMessage(message))
                    .isInstanceOf(UnsupportedContentBlockException.class)
                    .hasMessageContaining("only user messages can carry media");
        }
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
                        descriptor, ResourceContext.fromGetResource((a, b) -> null));
        ChatMessage message = ChatMessage.user(List.of(AudioBlock.fromBase64("audio/wav", BASE64)));

        assertThatThrownBy(() -> connection.chat(List.of(message), List.of(), Map.of()))
                .isExactlyInstanceOf(UnsupportedContentBlockException.class);
    }
}
