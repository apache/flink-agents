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

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Jackson round-trip tests for {@link ChatMessage} content blocks — the wire contract shared with
 * the Python API (see the cross-language snapshot tests for the full event-level contract) — plus
 * the block-level immutability and {@link ContentBlock#sanitize()} contracts.
 */
class ChatMessageSerializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void nullBlocksFailAtEveryEntryPoint() throws Exception {
        for (String blocks : List.of("null", "[null]")) {
            assertThatThrownBy(
                            () ->
                                    MAPPER.readValue(
                                            "{\"role\":\"user\",\"blocks\":" + blocks + "}",
                                            ChatMessage.class))
                    .isInstanceOf(JsonMappingException.class);
        }
        ChatMessage message = ChatMessage.user("original");
        assertThatThrownBy(() -> message.withBlocks(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> message.withBlocks(Arrays.asList((ContentBlock) null)))
                .isInstanceOf(NullPointerException.class);
        assertThat(message.getText()).isEqualTo("original");
    }

    @Test
    void mediaFieldsRejectScalarCoercionOnJsonAndMapPaths() throws Exception {
        for (String kind : List.of("image", "audio", "video", "document")) {
            for (String field : List.of("media_type", "name", "sha256", "size_bytes")) {
                for (Object value : List.of(true, 1.5, "1.0")) {
                    if (!field.equals("size_bytes") && value instanceof String) {
                        continue;
                    }
                    Map<String, Object> block =
                            new HashMap<>(
                                    Map.of(
                                            "type",
                                            kind,
                                            "media_type",
                                            "image/png",
                                            "source",
                                            Map.of("type", "base64", "data", "aGk=")));
                    block.put(field, value);
                    assertRejectedBlock(block);
                }
            }
        }
        for (String field : List.of("media_type", "name", "sha256")) {
            Map<String, Object> block =
                    new HashMap<>(
                            Map.of(
                                    "type",
                                    "image",
                                    "media_type",
                                    "image/png",
                                    "source",
                                    Map.of("type", "base64", "data", "aGk=")));
            block.put(field, 5);
            assertRejectedBlock(block);
        }
        for (Object value : List.of(-1, 1.0, "1", new BigInteger("9223372036854775808"))) {
            assertRejectedBlock(
                    Map.of(
                            "type",
                            "image",
                            "media_type",
                            "image/png",
                            "source",
                            Map.of("type", "base64", "data", "aGk="),
                            "size_bytes",
                            value));
        }
        for (String source : List.of("base64", "url")) {
            for (Object value : List.of(5, true, 1.5)) {
                assertRejectedBlock(
                        Map.of(
                                "type",
                                "image",
                                "media_type",
                                "image/png",
                                "source",
                                Map.of(
                                        "type",
                                        source,
                                        source.equals("base64") ? "data" : "url",
                                        value)));
            }
        }
        for (long size : List.of(0L, Long.MAX_VALUE)) {
            ImageBlock block =
                    new ImageBlock("image/png", new Base64Source("aGk="), null, size, null);
            assertThat(MAPPER.readValue(MAPPER.writeValueAsString(block), ContentBlock.class))
                    .isEqualTo(block);
        }
        assertThatThrownBy(
                        () ->
                                new ImageBlock(
                                        "image/png", new Base64Source("aGk="), null, -1L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertRejectedBlock(Map<String, Object> block) throws Exception {
        String json = MAPPER.writeValueAsString(Map.of("role", "user", "blocks", List.of(block)));
        assertThatThrownBy(() -> MAPPER.readValue(json, ChatMessage.class))
                .isInstanceOf(JsonMappingException.class);
        assertThatThrownBy(
                        () -> ChatMessage.fromMap(Map.of("role", "user", "blocks", List.of(block))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void base64PayloadPreservationAndSafeRepresentations() throws Exception {
        // Sources carry data without decoding; even malformed payloads must not report negatives.
        for (String data :
                List.of("=", "===", "a", "a===", "aGk==", "aG=k", "aGk\n", "aG-_", "图像")) {
            Base64Source source = new Base64Source(data);
            assertThat(source.getSizeBytes()).isNotNegative();
            assertThat(MAPPER.readValue(MAPPER.writeValueAsString(source), MediaSource.class))
                    .isEqualTo(source);
            ChatMessage message =
                    ChatMessage.fromMap(
                            Map.of(
                                    "role",
                                    "user",
                                    "blocks",
                                    List.of(
                                            Map.of(
                                                    "type",
                                                    "image",
                                                    "media_type",
                                                    "image/png",
                                                    "source",
                                                    Map.of("type", "base64", "data", data)))));
            assertThat(((ImageBlock) message.getBlocks().get(0)).getSource()).isEqualTo(source);
        }
        for (String data : List.of("YQ==", "YQ", "aGk=", "aGk", "YWJj")) {
            Base64Source source = new Base64Source(data);
            assertThat(source.getSizeBytes())
                    .isEqualTo((long) Base64.getDecoder().decode(data).length);
            assertThat(MAPPER.readValue(MAPPER.writeValueAsString(source), MediaSource.class))
                    .isEqualTo(source);
        }
        UrlSource url = new UrlSource("https://user:password@example.org/x?token=secret");
        assertThat(url.toString()).doesNotContain("password", "secret");
        assertThat(MAPPER.writeValueAsString(url)).contains("password", "secret");
    }

    @Test
    @DisplayName("A text-only message serializes to a single typed text block")
    void testTextOnlyWireShape() throws Exception {
        ChatMessage message = ChatMessage.user("hello world");

        JsonNode json = MAPPER.valueToTree(message);

        assertThat(json.get("role").asText()).isEqualTo("user");
        assertThat(json.get("blocks")).hasSize(1);
        assertThat(json.get("blocks").get(0).get("type").asText()).isEqualTo("text");
        assertThat(json.get("blocks").get(0).get("text").asText()).isEqualTo("hello world");
        assertThat(json.has("tool_calls")).isFalse();
        assertThat(json.get("metadata")).isEmpty();
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
                        MessageRole.USER,
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
        assertThatThrownBy(() -> ImageBlock.fromBase64("", "aGk="))
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
            // Empty media type.
            "{\"type\":\"image\",\"media_type\":\"\","
                    + "\"source\":{\"type\":\"base64\",\"data\":\"aGk=\"}}",
            // Unknown field on a base64 source.
            "{\"type\":\"image\",\"media_type\":\"image/png\",\"source\":"
                    + "{\"type\":\"base64\",\"data\":\"aGk=\",\"url\":\"https://example.org/x\"}}",
            // Unknown field on a URL source.
            "{\"type\":\"image\",\"media_type\":\"image/png\",\"source\":"
                    + "{\"type\":\"url\",\"url\":\"https://example.org/x\",\"data\":\"aGk=\"}}",
            // Unknown field on a media block.
            "{\"type\":\"image\",\"media_type\":\"image/png\","
                    + "\"source\":{\"type\":\"base64\",\"data\":\"aGk=\"},\"caption\":\"x\"}",
            // Unknown field on a text block.
            "{\"type\":\"text\",\"text\":\"hi\",\"caption\":\"x\"}",
            // Explicit null text.
            "{\"type\":\"text\",\"text\":null}",
            // Non-string text.
            "{\"type\":\"text\",\"text\":5}",
        };
        for (String json : invalid) {
            assertThatThrownBy(() -> MAPPER.readValue(json, ContentBlock.class))
                    .as(json)
                    .isInstanceOf(JsonMappingException.class);
        }
    }

    @Test
    @DisplayName("An omitted text defaults to empty, as in Python; null text is rejected")
    void testTextBlockNullContract() throws Exception {
        TextBlock omitted = (TextBlock) MAPPER.readValue("{\"type\":\"text\"}", ContentBlock.class);
        assertThat(omitted.getText()).isEmpty();
        assertThatThrownBy(() -> TextBlock.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("A message's block list is an unmodifiable snapshot")
    void testBlockListIsSnapshot() {
        ChatMessage message = ChatMessage.user("hello");
        assertThatThrownBy(() -> message.getBlocks().add(TextBlock.of("injected")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("A message stores its blocks in a list Flink 1.20's Kryo can rebuild")
    void testStoredBlocksAreKryoRebuildable() throws Exception {
        // The runtime keeps action-task events (and their ChatMessages) in operator state that
        // Flink serializes with Kryo. Flink 1.20's Kryo rebuilds a stored collection by
        // allocating it and calling add() per element, which the immutable lists returned by
        // List.of and List.copyOf reject, so such state cannot be restored. Flink 2.x's Kryo
        // ships immutable-collection serializers, which is why only the it-python [flink-1.20]
        // suite catches this. The stored block list must therefore stay structurally modifiable,
        // while getBlocks() still hands callers an unmodifiable view.
        ChatMessage message =
                ChatMessage.user(
                        List.of(
                                TextBlock.of("What's in this picture?"),
                                ImageBlock.fromBase64("image/png", "aGk=")));

        Field stored = ChatMessage.class.getDeclaredField("blocks");
        stored.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ContentBlock> backing = (List<ContentBlock>) stored.get(message);

        // The exact operation Flink 1.20's Kryo performs while rebuilding the list from state.
        assertThatCode(() -> backing.add(TextBlock.of("probe"))).doesNotThrowAnyException();
        // Callers still cannot mutate the message through the public view.
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
