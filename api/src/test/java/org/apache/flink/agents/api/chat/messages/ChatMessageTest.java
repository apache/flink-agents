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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.event.ToolRequestEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Core message and response value contracts. */
class ChatMessageTest {
    @Test
    void preservesMutableNestedDataAndWireShape() {
        Map<String, Object> nested = new HashMap<>(Map.of("x", 1));
        Map<String, Object> input = new HashMap<>(Map.of("nested", List.of(nested)));
        ToolCallBlock call = new ToolCallBlock("provider-id", "tool", input);
        nested.put("x", 2);
        ChatMessage message =
                ChatMessage.assistant(
                        List.of(
                                new ReasoningBlock("private", Map.of()),
                                call,
                                new TextBlock("answer")));
        assertThat(message.getText()).isEqualTo("answer");
        assertThat(message.getToolCalls()).containsExactly(call);
        assertThat(message.toMap()).containsOnlyKeys("role", "blocks", "metadata");
        assertThat(ChatMessage.fromMap(message.toMap())).isEqualTo(message);
        assertThat(call.getInput()).isSameAs(input);
        assertThat(call.getInput()).isEqualTo(Map.of("nested", List.of(Map.of("x", 2))));
        call.getInput().put("added", 3);
        assertThat(input).containsEntry("added", 3);
        Object value = new Object();
        message.getMetadata().put("custom", value);
        assertThat(message.getMetadata().get("custom")).isSameAs(value);
        Map<String, Object> metadata = new HashMap<>(Map.of("custom", value));
        assertThat(message.withMetadata(metadata).getMetadata()).isSameAs(metadata);
        assertThat(new ToolCallBlock("id", "tool", metadata, metadata).getInput())
                .isSameAs(metadata);
        assertThat(new ToolCallBlock("id", "tool", input, metadata).getMetadata())
                .isSameAs(metadata);
        assertThat(new ReasoningBlock("private", metadata).getMetadata()).isSameAs(metadata);
        assertThat(new ToolResultBlock("id", List.of(), false, metadata).getMetadata())
                .isSameAs(metadata);
        assertThat(
                        new ChatResult(
                                        ChatMessage.assistant(List.of()),
                                        null,
                                        null,
                                        null,
                                        null,
                                        metadata)
                                .getMetadata())
                .isSameAs(metadata);
        assertThatThrownBy(() -> message.getBlocks().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toolCallsSerializeInEventsAndMessagesWithoutDuplicateTypeFields() throws Exception {
        ObjectMapper mapper =
                new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        ToolCallBlock call = new ToolCallBlock("id", "tool", Map.of("x", 1));
        ToolRequestEvent event = new ToolRequestEvent("model", List.of(call));
        assertThat(((List<?>) event.getAttr("tool_calls")).get(0)).isSameAs(call);
        String json = mapper.writeValueAsString(event);
        assertThat(mapper.readTree(json).at("/attributes/tool_calls/0/type").asText())
                .isEqualTo("tool_call");
        ToolRequestEvent restored = mapper.readValue(json, ToolRequestEvent.class);
        assertThat(restored.getToolCalls()).containsExactly(call);
        assertThat(((List<?>) restored.getAttr("tool_calls")).get(0))
                .isInstanceOf(ToolCallBlock.class);
        ChatMessage message = ChatMessage.assistant(List.of(call));
        assertThat(mapper.readValue(mapper.writeValueAsString(message), ChatMessage.class))
                .isEqualTo(message);
        assertThatThrownBy(() -> new ToolRequestEvent("model", List.of(call, call)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesRolesAndDuplicateCalls() {
        ToolCallBlock call = new ToolCallBlock("id", "tool", Map.of());
        assertThatThrownBy(() -> ChatMessage.assistant(List.of(call, call)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChatMessage.user(List.of(call)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolResultBlock("id", List.of(call), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void responsePreservesOrderedBlocksAndProjectsOnlyAssistantText() {
        ToolCallBlock call = new ToolCallBlock("provider-id", "tool", Map.of("x", 1));
        List<ContentBlock> blocks =
                new ArrayList<>(
                        List.of(
                                new TextBlock("before"),
                                new ReasoningBlock("private", Map.of()),
                                ImageBlock.fromBase64("image/png", "aGk="),
                                call,
                                new TextBlock("after")));
        ChatResult response = new ChatResult(ChatMessage.assistant(blocks));
        blocks.clear();
        assertThat(response.getMessage().getBlocks()).hasSize(5);
        assertThat(response.getText()).isEqualTo("beforeafter");
        assertThat(response.getToolCalls()).containsExactly(call);
        assertThat(response.toMap())
                .containsOnlyKeys(
                        "message", "model", "response_id", "usage", "finish_reason", "metadata");
        assertThat(ChatResult.fromMap(response.toMap())).isEqualTo(response);
        assertThatThrownBy(() -> response.getMessage().getBlocks().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new ChatResult(ChatMessage.assistant(List.of())).getText()).isEmpty();
    }

    @Test
    void responseRejectsToolResultsAndDuplicateCallsBeforeDispatch() throws Exception {
        ToolCallBlock call = new ToolCallBlock("id", "tool", Map.of());
        for (List<ContentBlock> blocks :
                List.of(
                        List.<ContentBlock>of(call, call),
                        List.<ContentBlock>of(new ToolResultBlock("id", List.of(), false)))) {
            assertThatThrownBy(() -> new ChatResult(ChatMessage.assistant(blocks)))
                    .isInstanceOf(IllegalArgumentException.class);
            Map<String, Object> wire =
                    Map.of("message", Map.of("role", "assistant", "blocks", blocks));
            assertThatThrownBy(() -> ChatResult.fromMap(wire))
                    .isInstanceOf(IllegalArgumentException.class);
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(wire);
            assertThatThrownBy(() -> mapper.readValue(json, ChatResult.class))
                    .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
        }
        ObjectMapper mapper = new ObjectMapper();
        for (String json : List.of("{}", "{\"blocks\":null}", "{\"blocks\":[null]}")) {
            assertThatThrownBy(() -> mapper.readValue(json, ChatResult.class))
                    .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
        }
    }

    @Test
    void resultRequiresAssistantMessageAndPreservesMessageMetadata() {
        for (ChatMessage message :
                List.of(
                        ChatMessage.user("user"),
                        new ChatMessage(MessageRole.SYSTEM, "system"),
                        ChatMessage.tool(new ToolResultBlock("id", List.of(), false)))) {
            assertThatThrownBy(() -> new ChatResult(message))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        ChatMessage message =
                ChatMessage.assistant("answer").withMetadata(Map.of("signature", "sig"));
        ChatResult result = new ChatResult(message);
        assertThat(result.getMessage()).isSameAs(message);
        assertThat(ChatResult.fromMap(result.toMap()).getMessage()).isEqualTo(message);
    }

    @Test
    void responseRoundTripDistinguishesZeroFromUnknown() {
        ChatResult response =
                new ChatResult(
                        ChatMessage.assistant(List.of(new TextBlock("answer"))),
                        "local",
                        "response-id",
                        new TokenUsage(0L, null, Map.of("cached", 0L), Map.of("reasoning", 1L)),
                        "stop",
                        Map.of("opaque", List.of(1)));
        ChatResult restored = ChatResult.fromMap(response.toMap());
        assertThat(restored).isEqualTo(response);
        assertThat(restored.getUsage().getPromptTokens()).isZero();
        assertThat(restored.getUsage().getCompletionTokens()).isNull();
        assertThat(restored.getUsage().getPromptTokenDetails()).containsEntry("cached", 0L);
        assertThat(restored.getUsage().getCompletionTokenDetails()).containsEntry("reasoning", 1L);
        assertThat((Map<String, Object>) response.toMap().get("usage"))
                .containsOnlyKeys(
                        "prompt_tokens",
                        "completion_tokens",
                        "prompt_token_details",
                        "completion_token_details");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"stop", "tool_calls", "length", "content_filter", "some_vendor_reason"})
    void responseRoundTripPreservesFinishReason(String reason) throws Exception {
        ChatResult response =
                new ChatResult(
                        ChatMessage.assistant(List.of(new TextBlock("answer"))),
                        null,
                        null,
                        null,
                        reason,
                        Map.of());
        ObjectMapper mapper = new ObjectMapper();
        ChatResult restored =
                mapper.readValue(mapper.writeValueAsString(response), ChatResult.class);
        assertThat(restored.getFinishReason()).isEqualTo(reason);
        assertThat(restored).isEqualTo(response);
        assertThat(ChatResult.fromMap(response.toMap())).isEqualTo(response);
    }

    @Test
    void finishReasonRejectsNonStringValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (Object value : List.of(true, 5, 1.5, List.of(), Map.of())) {
            Map<String, Object> wire =
                    Map.of("message", ChatMessage.assistant("answer"), "finish_reason", value);
            assertThatThrownBy(() -> ChatResult.fromMap(wire))
                    .isInstanceOf(IllegalArgumentException.class);
            String json = mapper.writeValueAsString(wire);
            assertThatThrownBy(() -> mapper.readValue(json, ChatResult.class))
                    .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
        }
    }
}
