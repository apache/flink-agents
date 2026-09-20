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

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Jackson round-trip tests for {@link ChatMessage} content blocks — the wire contract shared with
 * the Python API (see the cross-language snapshot tests for the full event-level contract) — plus
 * the block-level immutability and {@link ContentBlock#sanitize()} contracts.
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

        // Each discriminator is written once: getType() is @JsonIgnore on both the block and
        // its source, the type resolver owns the key.
        assertThat(MAPPER.writeValueAsString(message.getBlocks().get(1)))
                .containsOnlyOnce("\"type\":\"image\"")
                .containsOnlyOnce("\"type\":\"base64\"");
        assertThat(image.get("type").asText()).isEqualTo("image");
        assertThat(image.get("media_type").asText()).isEqualTo("image/png");
        // The payload location is a typed, discriminated source.
        assertThat(image.get("source").get("type").asText()).isEqualTo("base64");
        assertThat(image.get("source").get("data").asText()).isEqualTo("aGk=");
        assertThat(image.get("source").has("url")).isFalse();
        // Absent optional fields are omitted, not serialized as nulls.
        assertThat(image.has("name")).isFalse();
        assertThat(image.has("size_bytes")).isFalse();
        assertThat(image.has("sha256")).isFalse();
    }

    @Test
    @DisplayName("A mixed-block message round-trips through Jackson preserving order and types")
    void testMixedBlocksRoundTrip() throws Exception {
        ImageBlock image =
                new ImageBlock(
                        "image/jpeg",
                        new UrlSource("https://example.org/cat.jpg"),
                        "cat.jpg",
                        123L,
                        null);
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
    @DisplayName("Media factories and sources reject empty payloads, URLs, and media types")
    void testMediaSourceValidation() {
        assertThatThrownBy(() -> ImageBlock.fromBase64("image/png", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImageBlock.fromBase64("image/png", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImageBlock.fromUrl("image/png", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImageBlock.fromUrl("image/png", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImageBlock.fromBase64(null, "aGk="))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageBlock("image/png", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The Jackson path runs the same validation as the factories. The invalid payloads here are
     * mirrored verbatim in the Python suite ({@code test_invalid_wire_payloads_rejected}), so both
     * languages agree on which wire values are valid.
     */
    @Test
    @DisplayName("The Jackson path rejects the same wire payloads as the factories and Python")
    void testJacksonPathValidation() {
        String[] invalid = {
            // Missing source.
            "{\"type\":\"image\",\"media_type\":\"image/png\"}",
            // Unknown source kind.
            "{\"type\":\"image\",\"media_type\":\"image/png\","
                    + "\"source\":{\"type\":\"blob\",\"blob_id\":\"b1\"}}",
            // Empty base64 payload.
            "{\"type\":\"image\",\"media_type\":\"image/png\","
                    + "\"source\":{\"type\":\"base64\",\"data\":\"\"}}",
            // Empty URL.
            "{\"type\":\"image\",\"media_type\":\"image/png\","
                    + "\"source\":{\"type\":\"url\",\"url\":\"\"}}",
            // Missing media type.
            "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"data\":\"aGk=\"}}",
        };
        for (String json : invalid) {
            assertThatThrownBy(() -> MAPPER.readValue(json, ContentBlock.class))
                    .as(json)
                    .isInstanceOf(JsonMappingException.class);
        }
    }

    @Test
    @DisplayName("A message's block list is an unmodifiable snapshot")
    void testBlockListIsSnapshot() {
        ChatMessage message = ChatMessage.user("hello");
        assertThatThrownBy(() -> message.getBlocks().add(TextBlock.of("injected")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("sanitize() keeps text and media metadata but never payload bytes")
    void testSanitizeInlineMedia() {
        Map<String, Object> text = TextBlock.of("hello").sanitize();
        assertThat(text).containsExactly(Map.entry("type", "text"), Map.entry("text", "hello"));

        // "aGVsbG8=" is 8 base64 chars with one padding char: 5 decoded bytes.
        Map<String, Object> image = ImageBlock.fromBase64("image/png", "aGVsbG8=").sanitize();
        assertThat(image.get("type")).isEqualTo("image");
        assertThat(image.get("media_type")).isEqualTo("image/png");
        // The inline payload is dropped entirely, so a reconstruction attempt fails loudly
        // instead of producing a block with a fake payload; the source keeps only its kind.
        assertThat(image.get("source")).isEqualTo(Map.of("type", "base64"));
        assertThat(image.get("size_bytes")).isEqualTo(5L);

        Map<String, Object> document =
                new DocumentBlock(
                                "application/pdf",
                                new Base64Source("cGRm"),
                                "a.pdf",
                                999L,
                                "abc123")
                        .sanitize();
        // Stored metadata wins over the derived size, and the whitelist keeps name and sha256.
        assertThat(document.get("size_bytes")).isEqualTo(999L);
        assertThat(document.get("name")).isEqualTo("a.pdf");
        assertThat(document.get("sha256")).isEqualTo("abc123");
        assertThat(document.get("source")).isEqualTo(Map.of("type", "base64"));
    }

    @Test
    @DisplayName("sanitize() strips credentials, query, and fragment from URLs")
    void testSanitizeUrls() {
        Map<String, Object> signed =
                ImageBlock.fromUrl(
                                "image/png",
                                "https://user:secret@example.org:8443/media/cat.png"
                                        + "?X-Amz-Signature=abc&token=t#frag")
                        .sanitize();
        assertThat(signed.get("source"))
                .isEqualTo(Map.of("type", "url", "url", "https://example.org:8443/media/cat.png"));
        assertThat(signed).doesNotContainKey("size_bytes");

        Map<String, Object> unparseable =
                ImageBlock.fromUrl("image/png", "http://exa mple.org/x").sanitize();
        assertThat(unparseable.get("source"))
                .isEqualTo(Map.of("type", "url", "url", "<unparseable-url>"));
    }
}
