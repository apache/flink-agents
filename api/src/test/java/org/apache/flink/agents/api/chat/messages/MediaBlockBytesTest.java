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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Raw-byte factory contracts shared by every media modality. */
class MediaBlockBytesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static Stream<BiFunction<String, byte[], MediaBlock>> factories() {
        return Stream.of(
                ImageBlock::fromBytes,
                AudioBlock::fromBytes,
                VideoBlock::fromBytes,
                DocumentBlock::fromBytes);
    }

    @ParameterizedTest
    @MethodSource("factories")
    void preservesBinaryPayloadAndExistingWireShape(BiFunction<String, byte[], MediaBlock> factory)
            throws Exception {
        byte[] allBytes = new byte[256];
        for (int i = 0; i < allBytes.length; i++) {
            allBytes[i] = (byte) i;
        }
        // Exercise both padding lengths, no padding, non-UTF-8 data and no line wrapping.
        for (byte[] data :
                List.of(
                        new byte[] {(byte) 0xff},
                        new byte[] {(byte) 0xff, 0},
                        new byte[] {(byte) 0xff, 0, (byte) 0x80},
                        allBytes)) {
            MediaBlock block = factory.apply("application/octet-stream", data);
            String encoded = ((Base64Source) block.getSource()).getData();
            assertThat(encoded).doesNotContain("\n", "\r");
            assertThat(Base64.getDecoder().decode(encoded)).isEqualTo(data);
            assertThat(encoded).hasSize(4 * ((data.length + 2) / 3));
            assertThat(block.getSource().getSizeBytes()).isEqualTo((long) data.length);
            assertThat(block.getSizeBytes()).isNull();
            ChatMessage message = ChatMessage.user(List.of(block));
            Map<String, Object> expected =
                    Map.of(
                            "type",
                            block.getType(),
                            "media_type",
                            "application/octet-stream",
                            "source",
                            Map.of("type", "base64", "data", encoded));
            JsonNode serialized = MAPPER.valueToTree(block);
            assertThat(serialized).isEqualTo(MAPPER.valueToTree(expected));
            assertThat(
                            MAPPER.readValue(MAPPER.writeValueAsString(message), ChatMessage.class)
                                    .getBlocks())
                    .containsExactly(block);
            ChatMessage fromMap =
                    ChatMessage.fromMap(Map.of("role", "user", "blocks", List.of(expected)));
            assertThat(fromMap.getBlocks()).containsExactly(block);
            assertThat(block.toString()).doesNotContain(encoded);
            assertThat(MAPPER.writeValueAsString(block.sanitize())).doesNotContain(encoded);
        }
    }

    @ParameterizedTest
    @MethodSource("factories")
    void doesNotRetainCallerArray(BiFunction<String, byte[], MediaBlock> factory) {
        byte[] data = {(byte) 0xff, 0, (byte) 0x80};
        MediaBlock block = factory.apply("application/octet-stream", data);
        Arrays.fill(data, (byte) 1);
        assertThat(((Base64Source) block.getSource()).getData()).isEqualTo("/wCA");
    }

    @ParameterizedTest
    @MethodSource("factories")
    void rejectsMissingDataAndMediaType(BiFunction<String, byte[], MediaBlock> factory) {
        assertThatThrownBy(() -> factory.apply("image/png", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.apply("image/png", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.apply(null, new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.apply("", new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
