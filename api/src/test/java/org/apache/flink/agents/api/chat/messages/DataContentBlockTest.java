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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Text/media subset contracts at message, tool output, and serialization boundaries. */
class DataContentBlockTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesOrderedDataInToolResultsAndMessages() throws Exception {
        List<DataContentBlock> blocks =
                List.of(
                        new TextBlock("before"),
                        ImageBlock.fromBase64("image/png", "aGk="),
                        AudioBlock.fromBase64("audio/wav", "aGk="),
                        VideoBlock.fromUrl("video/mp4", "https://example.com/video.mp4"),
                        DocumentBlock.fromUrl("application/pdf", "https://example.com/doc.pdf"),
                        new TextBlock("after"));
        ToolResultBlock result = new ToolResultBlock("call", blocks, false);
        ToolResponse response = ToolResponse.success(blocks);
        assertThat(response.toResultBlock("call")).isEqualTo(result);
        assertThat(result.getText()).isEqualTo("beforeafter");
        assertThat(mapper.readValue(mapper.writeValueAsString(result), ToolResultBlock.class))
                .isEqualTo(result);
        assertThat(mapper.readValue(mapper.writeValueAsString(response), ToolResponse.class))
                .isEqualTo(response);
        assertThat(ChatMessage.user(blocks).getBlocks()).containsExactlyElementsOf(blocks);
        ChatMessage message = ChatMessage.tool(result);
        assertThat(ChatMessage.fromMap(message.toMap())).isEqualTo(message);
        assertThat(
                        ((ToolResultBlock) ChatMessage.fromMap(message.toMap()).getBlocks().get(0))
                                .getBlocks())
                .containsExactlyElementsOf(blocks);
        for (DataContentBlock block : blocks) {
            String json = mapper.writeValueAsString(block);
            assertThat(mapper.readValue(json, DataContentBlock.class)).isEqualTo(block);
            assertThat(mapper.readValue(json, ContentBlock.class)).isEqualTo(block);
        }
    }

    @ParameterizedTest
    @MethodSource("nonDataBlocks")
    void rejectsNonDataBlocksAtDeserializationBoundaries(ContentBlock block) throws Exception {
        Map<String, Object> result =
                Map.of("type", "tool_result", "call_id", "call", "blocks", List.of(block));
        Map<String, Object> response = Map.of("success", true, "blocks", List.of(block));
        assertThatThrownBy(
                        () ->
                                mapper.readValue(
                                        mapper.writeValueAsString(block), DataContentBlock.class))
                .isInstanceOf(InvalidTypeIdException.class);
        assertThatThrownBy(
                        () ->
                                mapper.readValue(
                                        mapper.writeValueAsString(result), ToolResultBlock.class))
                .isInstanceOf(InvalidTypeIdException.class);
        assertThatThrownBy(
                        () ->
                                mapper.readValue(
                                        mapper.writeValueAsString(response), ToolResponse.class))
                .isInstanceOf(InvalidTypeIdException.class);
        assertThatThrownBy(() -> mapper.convertValue(response, ToolResponse.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasCauseInstanceOf(InvalidTypeIdException.class);
        assertThatThrownBy(
                        () ->
                                ChatMessage.fromMap(
                                        Map.of("role", "tool", "blocks", List.of(result))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasCauseInstanceOf(InvalidTypeIdException.class);
    }

    private static Stream<ContentBlock> nonDataBlocks() {
        return Stream.of(
                new ReasoningBlock("private", Map.of()),
                new ToolCallBlock("nested", "tool", Map.of()),
                new ToolResultBlock("nested", List.of(), false));
    }
}
