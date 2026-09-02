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
package org.apache.flink.agents.api.chat.messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Jackson round-trip tests for {@link ChatMessage} content blocks — the wire contract shared with
 * the Python API (see the cross-language snapshot tests for the full event-level contract).
 */
class ChatMessageSerializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("A text-only message serializes to a single typed text block")
    void testTextOnlyWireShape() throws Exception {
        ChatMessage message = ChatMessage.user("hello world");

        JsonNode json = MAPPER.valueToTree(message);

        assertThat(json.get("role").asText()).isEqualTo("user");
        assertThat(json.get("blocks")).hasSize(1);
        assertThat(json.get("blocks").get(0).get("type").asText()).isEqualTo("text");
        assertThat(json.get("blocks").get(0).get("text").asText()).isEqualTo("hello world");
        assertThat(json.get("tool_calls")).isEmpty();
        assertThat(json.get("extra_args")).isEmpty();
        assertThat(json.has("content")).isFalse();
    }

    @Test
    @DisplayName("Media blocks carry the snake_case discriminated shape and omit absent fields")
    void testMediaBlockWireShape() throws Exception {
        ChatMessage message =
                ChatMessage.user(
                        List.of(
                                TextBlock.of("What's in this picture?"),
                                ImageBlock.fromBase64("image/png", "aGk=")));

        JsonNode image = MAPPER.valueToTree(message).get("blocks").get(1);

        assertThat(image.get("type").asText()).isEqualTo("image");
        assertThat(image.get("mime_type").asText()).isEqualTo("image/png");
        assertThat(image.get("data").asText()).isEqualTo("aGk=");
        // Absent optional fields are omitted, not serialized as nulls.
        assertThat(image.has("url")).isFalse();
        assertThat(image.has("name")).isFalse();
        assertThat(image.has("size_bytes")).isFalse();
        assertThat(image.has("sha256")).isFalse();
    }

    @Test
    @DisplayName("A mixed-block message round-trips through Jackson preserving order and types")
    void testMixedBlocksRoundTrip() throws Exception {
        ImageBlock image = ImageBlock.fromUrl("image/jpeg", "https://example.org/cat.jpg");
        image.setName("cat.jpg");
        image.setSizeBytes(123L);
        ChatMessage original =
                new ChatMessage(
                        MessageRole.TOOL,
                        List.of(
                                TextBlock.of("before"),
                                image,
                                DocumentBlock.fromBase64("application/pdf", "cGRm"),
                                TextBlock.of("after")));

        ChatMessage restored =
                MAPPER.readValue(MAPPER.writeValueAsString(original), ChatMessage.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.getBlocks())
                .extracting(block -> block.getClass().getSimpleName())
                .containsExactly("TextBlock", "ImageBlock", "DocumentBlock", "TextBlock");
        assertThat(restored.getText()).isEqualTo("beforeafter");
    }

    @Test
    @DisplayName("Audio and video blocks round-trip through the same discriminator")
    void testAudioAndVideoRoundTrip() throws Exception {
        ChatMessage original =
                ChatMessage.user(
                        List.of(
                                AudioBlock.fromBase64("audio/wav", "d2F2"),
                                VideoBlock.fromUrl("video/mp4", "https://example.org/v.mp4")));

        ChatMessage restored =
                MAPPER.readValue(MAPPER.writeValueAsString(original), ChatMessage.class);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("Media factories enforce exactly one of data and url")
    void testMediaSourceValidation() {
        assertThatThrownBy(() -> ImageBlock.fromBase64("image/png", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImageBlock.fromUrl("image/png", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImageBlock.fromBase64(null, "aGk="))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
