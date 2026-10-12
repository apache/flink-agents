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

package org.apache.flink.agents.integrations.chatmodels.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ollama4j.models.chat.OllamaChatMessage;
import io.github.ollama4j.models.chat.OllamaChatRequest;
import io.github.ollama4j.utils.Utils;
import org.apache.flink.agents.api.chat.messages.AudioBlock;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.ContentBlock;
import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.chat.messages.DocumentBlock;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.chat.messages.UnsupportedContentBlockException;
import org.apache.flink.agents.api.chat.messages.VideoBlock;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests how content blocks reach an Ollama chat request. */
class OllamaMultimodalTest {

    private static final String URL = "https://example.com/cat.png?sig=secret";
    private static final String FIRST = "Zmlyc3Q="; // "first"
    private static final String SECOND = "c2Vjb25k"; // "second"

    private static OllamaChatRequest request(ChatMessage message) {
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(OllamaChatModelConnection.class.getName())
                        .addInitialArgument("endpoint", "http://localhost:11434")
                        .build();
        return new OllamaChatModelConnection(
                        descriptor, ResourceContext.fromGetResource((a, b) -> null))
                .buildRequest(List.of(message), List.of(), Map.of("model", "llava"), null);
    }

    @Test
    @DisplayName("A text-only message carries no images")
    void testTextOnlyMessageHasNoImages() {
        OllamaChatMessage message =
                request(ChatMessage.user(List.of(TextBlock.of("hi")))).getMessages().get(0);

        assertThat(message.getResponse()).isEqualTo("hi");
        assertThat(message.getImages()).isNull();
    }

    @Test
    @DisplayName("Base64 images are attached in block order, next to the text projection")
    void testBase64ImagesAttachedInBlockOrder() throws Exception {
        OllamaChatRequest request =
                request(
                        ChatMessage.user(
                                List.of(
                                        TextBlock.of("Compare "),
                                        ImageBlock.fromBase64("image/png", FIRST),
                                        TextBlock.of("and"),
                                        ImageBlock.fromBase64("image/jpeg", SECOND))));
        OllamaChatMessage message = request.getMessages().get(0);

        assertThat(message.getResponse()).isEqualTo("Compare and");
        assertThat(message.getImages())
                .containsExactly(
                        "first".getBytes(StandardCharsets.UTF_8),
                        "second".getBytes(StandardCharsets.UTF_8));
        // On the wire the images are the original base64 strings again.
        JsonNode wire = Utils.getObjectMapper().valueToTree(request);
        assertThat(wire.at("/messages/0/images/0").asText()).isEqualTo(FIRST);
        assertThat(wire.at("/messages/0/images/1").asText()).isEqualTo(SECOND);
    }

    @Test
    @DisplayName("Media Ollama cannot take fails without leaking the source")
    void testUnsupportedMediaFailsExplicitly() {
        List<ContentBlock> unsupported =
                List.of(
                        ImageBlock.fromUrl("image/png", URL),
                        AudioBlock.fromBase64("audio/wav", FIRST),
                        VideoBlock.fromUrl("video/mp4", URL),
                        DocumentBlock.fromBase64("application/pdf", FIRST));
        for (ContentBlock block : unsupported) {
            assertThatThrownBy(() -> request(ChatMessage.user(List.of(TextBlock.of("hi"), block))))
                    .isInstanceOf(UnsupportedContentBlockException.class)
                    .hasMessageStartingWith("Ollama cannot send a")
                    .hasMessageContaining(" " + block.getType() + " block")
                    .hasMessageNotContaining("secret")
                    .hasMessageNotContaining(FIRST);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = MessageRole.class,
            names = {"SYSTEM", "ASSISTANT", "TOOL"})
    @DisplayName("Images outside user messages fail explicitly")
    void testImagesOutsideUserMessagesFail(MessageRole role) {
        List<DataContentBlock> blocks =
                List.of(TextBlock.of("see"), ImageBlock.fromBase64("image/png", FIRST));
        if (role == MessageRole.SYSTEM) {
            assertThatThrownBy(() -> new ChatMessage(role, blocks))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SYSTEM messages accept only text");
            return;
        }
        ChatMessage message =
                role == MessageRole.TOOL
                        ? ChatMessage.tool(new ToolResultBlock("call", blocks, false))
                        : new ChatMessage(role, blocks);

        assertThatThrownBy(() -> request(message))
                .isInstanceOf(UnsupportedContentBlockException.class)
                .hasMessageContaining("only user messages can carry media");
    }

    @Test
    @DisplayName("Image data that is not base64 fails with a clear error")
    void testInvalidBase64Fails() {
        ChatMessage message = ChatMessage.user(List.of(ImageBlock.fromBase64("image/png", "!!")));

        assertThatThrownBy(() -> request(message))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(UnsupportedContentBlockException.class)
                .hasMessage("An image block's base64 data could not be decoded.");
    }

    @Test
    @DisplayName("chat() surfaces media errors with their own types")
    void testChatKeepsMediaErrorTypes() {
        assertThatThrownBy(
                        () ->
                                chat(
                                        ChatMessage.user(
                                                List.of(
                                                        AudioBlock.fromBase64(
                                                                "audio/wav", FIRST)))))
                .isExactlyInstanceOf(UnsupportedContentBlockException.class);
        assertThatThrownBy(
                        () ->
                                chat(
                                        ChatMessage.user(
                                                List.of(ImageBlock.fromBase64("image/png", "!!")))))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("An image block's base64 data could not be decoded.");
    }

    /** Goes through the public chat(); the errors are raised before any request is sent. */
    private static ChatResult chat(ChatMessage message) {
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(OllamaChatModelConnection.class.getName())
                        .addInitialArgument("endpoint", "http://localhost:11434")
                        .build();
        return new OllamaChatModelConnection(
                        descriptor, ResourceContext.fromGetResource((a, b) -> null))
                .chat(List.of(message), List.of(), Map.of("model", "llava"));
    }
}
