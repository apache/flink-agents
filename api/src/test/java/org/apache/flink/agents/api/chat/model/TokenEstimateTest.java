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
package org.apache.flink.agents.api.chat.model;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.ReasoningBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The token estimate a chat model reports before a request is sent: the approximation used when a
 * provider cannot count tokens, and what the setup counts.
 *
 * <p>The expected numbers of the approximation are the same as in the Python test {@code
 * test_token_estimate.py}; a change to one side has to be made on the other.
 */
class TokenEstimateTest {

    private static final String LOOKUP_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}";

    /** A tool that is only described, never called. */
    private static final class DescribedTool extends Tool {
        DescribedTool(String name, String description, String inputSchema) {
            super(new ToolMetadata(name, description, inputSchema));
        }

        @Override
        public ToolType getToolType() {
            return ToolType.FUNCTION;
        }

        @Override
        public ToolResponse call(ToolParameters parameters) {
            throw new UnsupportedOperationException();
        }
    }

    /** A connection that records what it is asked to estimate and can answer with a count. */
    private static final class CountingConnection extends BaseChatModelConnection {
        private final TokenEstimate answer;
        List<ChatMessage> estimatedMessages;
        List<Tool> estimatedTools;
        Map<String, Object> estimatedParams;

        CountingConnection(TokenEstimate answer) {
            super(
                    new ResourceDescriptor(
                            CountingConnection.class.getName(), Collections.emptyMap()),
                    null);
            this.answer = answer;
        }

        @Override
        public ChatResult chat(
                List<ChatMessage> messages, List<Tool> tools, Map<String, Object> modelParams) {
            return new ChatResult(ChatMessage.assistant("ok"));
        }

        @Override
        public TokenEstimate estimateInputTokens(
                List<ChatMessage> messages, List<Tool> tools, Map<String, Object> modelParams) {
            estimatedMessages = new ArrayList<>(messages);
            estimatedTools = new ArrayList<>(tools);
            estimatedParams = new HashMap<>(modelParams);
            return answer == null
                    ? super.estimateInputTokens(messages, tools, modelParams)
                    : answer;
        }
    }

    private static final class Setup extends BaseChatModelSetup {
        Setup(BaseChatModelConnection connection, Prompt prompt, List<Tool> tools) {
            super(new ResourceDescriptor(Setup.class.getName(), Collections.emptyMap()), null);
            this.connection = connection;
            this.prompt = prompt;
            this.tools.addAll(tools);
        }

        @Override
        public Map<String, Object> getParameters() {
            return new HashMap<>(Map.of("model", "m", "temperature", 0.1));
        }
    }

    private static long approximate(List<ChatMessage> messages, List<Tool> tools) {
        TokenEstimate estimate = ApproximateTokenEstimator.estimate(messages, tools);
        assertThat(estimate.isApproximate()).isTrue();
        return estimate.getTokens();
    }

    @Test
    void anEstimateSaysWhetherItIsApproximate() {
        assertThat(TokenEstimate.exact(12).isApproximate()).isFalse();
        assertThat(TokenEstimate.exact(12).getTokens()).isEqualTo(12);
        assertThat(TokenEstimate.approximate(12).isApproximate()).isTrue();
        assertThat(TokenEstimate.approximate(12)).isNotEqualTo(TokenEstimate.exact(12));
        assertThat(TokenEstimate.approximate(12)).isEqualTo(TokenEstimate.approximate(12));
        assertThat(TokenEstimate.approximate(12)).hasToString("~12 tokens");
        assertThat(TokenEstimate.exact(12)).hasToString("12 tokens");
        assertThatThrownBy(() -> TokenEstimate.exact(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-1");
    }

    @Test
    void anEmptyRequestCostsNothing() {
        assertThat(approximate(List.of(), List.of())).isZero();
    }

    @Test
    void asciiTextCostsOneTokenPerFourCharactersPlusTheMessageOverhead() {
        // 4 for the message, 5 characters round up to 2 tokens.
        assertThat(approximate(List.of(ChatMessage.user("hello")), List.of())).isEqualTo(6);
        // Two messages: 8, and 4 + 4 characters are 2 tokens.
        assertThat(
                        approximate(
                                List.of(ChatMessage.system("abcd"), ChatMessage.user("efgh")),
                                List.of()))
                .isEqualTo(10);
    }

    @Test
    void otherCharactersCostOneTokenEachCountedInCodePoints() {
        // 8 for two messages, 14 ASCII characters and 2 CJK characters at 4 units each: 22 units.
        assertThat(
                        approximate(
                                List.of(
                                        ChatMessage.system("You are terse."),
                                        ChatMessage.user("你好")),
                                List.of()))
                .isEqualTo(14);
        // One emoji is one code point (two UTF-16 units): 4 units, 1 token.
        assertThat(approximate(List.of(ChatMessage.user("😀")), List.of())).isEqualTo(5);
    }

    @Test
    void aToolCallCostsItsNameAndTheStructureOfItsArguments() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("q", "x");
        input.put("n", 3);
        ChatMessage call =
                ChatMessage.assistant(List.of(new ToolCallBlock("call-1", "lookup", input)));
        // name 6; map 2 + (1 + 4 + 3 + 1) + (1 + 4 + 8 + 1) = 25; 31 units are 8 tokens; + 4.
        assertThat(approximate(List.of(call), List.of())).isEqualTo(12);

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("a", Arrays.asList(true, null, 1.5, "é"));
        ChatMessage nestedCall =
                ChatMessage.assistant(List.of(new ToolCallBlock("call-2", "t", nested)));
        // name 1; list 2 + 6 + 5 + 9 + 7 = 29; map 2 + (1 + 4 + 29 + 1) = 37; 38 units; + 4.
        assertThat(approximate(List.of(nestedCall), List.of())).isEqualTo(14);
    }

    @Test
    void anArrayArgumentIsCountedByItsElementsAsAListIs() {
        // An array has no text form of its own: as a string it would be its type and identity.
        Map<String, Object> asArrays = new LinkedHashMap<>();
        asArrays.put("ids", new int[] {1, 22, 333});
        asArrays.put("names", new String[] {"a", "é"});
        asArrays.put("grid", new long[][] {{1L}, {2L, 3L}});
        Map<String, Object> asLists = new LinkedHashMap<>();
        asLists.put("ids", Arrays.asList(1, 22, 333));
        asLists.put("names", Arrays.asList("a", "é"));
        asLists.put("grid", Arrays.asList(Arrays.asList(1L), Arrays.asList(2L, 3L)));

        long arrays =
                approximate(
                        List.of(
                                ChatMessage.assistant(
                                        List.of(new ToolCallBlock("c", "t", asArrays)))),
                        List.of());
        long lists =
                approximate(
                        List.of(
                                ChatMessage.assistant(
                                        List.of(new ToolCallBlock("c", "t", asLists)))),
                        List.of());

        assertThat(arrays).isEqualTo(lists);
        // name 1; ids 2 + 3 * (8 + 1) = 29; names 2 + (3 + 1) + (6 + 1) = 13; grid
        // 2 + (11 + 1) + (20 + 1) = 35; map 2 + (3 + 4 + 29 + 1) + (5 + 4 + 13 + 1)
        // + (4 + 4 + 35 + 1) = 106; 107 units are 27 tokens; + 4.
        assertThat(arrays).isEqualTo(31);
    }

    @Test
    void aRequestWithoutAToolListIsARequestWithoutTools() {
        List<ChatMessage> messages = List.of(new ChatMessage(MessageRole.USER, "abcdefgh"));

        assertThat(ApproximateTokenEstimator.estimate(messages, null).getTokens())
                .isEqualTo(approximate(messages, List.of()));
    }

    @Test
    void aToolResultCostsItsTextAndAFlatAmountPerMediaBlock() {
        ChatMessage result =
                ChatMessage.tool(
                        new ToolResultBlock(
                                "call-1",
                                List.of(
                                        new TextBlock("abcdefgh"),
                                        ImageBlock.fromUrl("image/png", "https://example.org/i")),
                                false));
        assertThat(approximate(List.of(result), List.of())).isEqualTo(4 + 2 + 1024);
    }

    @Test
    void mediaAndReasoningBlocksOfAMessageAreCounted() {
        ChatMessage message =
                new ChatMessage(
                        MessageRole.USER,
                        List.of(
                                new TextBlock("abcd"),
                                ImageBlock.fromUrl("image/png", "https://example.org/i")));
        assertThat(approximate(List.of(message), List.of())).isEqualTo(4 + 1 + 1024);

        ChatMessage reasoning =
                ChatMessage.assistant(
                        List.of(new ReasoningBlock("12345678"), new TextBlock("abcd")));
        assertThat(approximate(List.of(reasoning), List.of())).isEqualTo(4 + 3);
    }

    @Test
    void aToolSchemaIsMeasuredAsCompactJsonHoweverItIsWritten() {
        long compact = approximate(List.of(), List.of(describedLookup(LOOKUP_SCHEMA)));
        assertThat(compact).isEqualTo(28);
        // As a tool that is declared in Python reaches Java: a space after every separator.
        String spaced = "{\"type\": \"object\", \"properties\": {\"q\": {\"type\": \"string\"}}}";
        assertThat(spaced).hasSize(59);
        assertThat(approximate(List.of(), List.of(describedLookup(spaced)))).isEqualTo(compact);
        String indented =
                "{\n  \"type\" : \"object\",\n  \"properties\" : {\n    \"q\" : {\n"
                        + "      \"type\" : \"string\"\n    }\n  }\n}";
        assertThat(approximate(List.of(), List.of(describedLookup(indented)))).isEqualTo(compact);

        // A character outside ASCII counts as one, whether the text escapes it or not:
        // 8 for the tool; 6 + 18 + 25 = 49 units are 13 tokens.
        assertThat(
                        approximate(
                                List.of(),
                                List.of(describedLookup("{\"description\":\"caf\u00e9\"}"))))
                .isEqualTo(21);
        assertThat(
                        approximate(
                                List.of(),
                                List.of(describedLookup("{\"description\": \"caf\\u00e9\"}"))))
                .isEqualTo(21);

        // A schema that is not JSON is measured as it is written: 6 + 18 + 10 = 34 units.
        assertThat(approximate(List.of(), List.of(describedLookup("not {json}")))).isEqualTo(17);
    }

    private static Tool describedLookup(String inputSchema) {
        return new DescribedTool("lookup", "Look something up.", inputSchema);
    }

    @Test
    void instructionsGivenToTheProviderAsAParameterAreCountedLikeASystemMessage() {
        CountingConnection connection = new CountingConnection(null);
        List<ChatMessage> messages = List.of(ChatMessage.user("hello"));
        Map<String, Object> params = new HashMap<>();
        params.put("model", "m");

        assertThat(connection.estimateInputTokens(messages, List.of(), params))
                .isEqualTo(TokenEstimate.approximate(6));

        // 4 more for the message the provider is sent, and 40 characters are 10 tokens.
        params.put("instructions", "i".repeat(40));
        assertThat(connection.estimateInputTokens(messages, List.of(), params))
                .isEqualTo(TokenEstimate.approximate(20));
        params.put("instructions", "");
        assertThat(connection.estimateInputTokens(messages, List.of(), params))
                .isEqualTo(TokenEstimate.approximate(6));
        params.put("instructions", 12);
        assertThat(connection.estimateInputTokens(messages, List.of(), params))
                .isEqualTo(TokenEstimate.approximate(6));

        // A connection can be given them inside additional_kwargs too. The parameter itself
        // comes first.
        params.remove("instructions");
        params.put("additional_kwargs", Map.of("instructions", "i".repeat(40)));
        assertThat(BaseChatModelConnection.providerInstructions(params)).isEqualTo("i".repeat(40));
        assertThat(connection.estimateInputTokens(messages, List.of(), params))
                .isEqualTo(TokenEstimate.approximate(20));
        // With both, the ones inside are written to the request last: they are the ones the
        // provider is given.
        params.put("instructions", "top");
        assertThat(BaseChatModelConnection.providerInstructions(params)).isEqualTo("i".repeat(40));
        // Also when they are empty: that clears the parameter, and the provider is given no
        // instructions.
        params.put("additional_kwargs", Map.of("instructions", ""));
        assertThat(BaseChatModelConnection.providerInstructions(params)).isNull();
        params.put("additional_kwargs", Map.of());
        assertThat(BaseChatModelConnection.providerInstructions(params)).isEqualTo("top");
        params.put("instructions", "");
        params.put("additional_kwargs", Map.of("instructions", ""));
        assertThat(BaseChatModelConnection.providerInstructions(params)).isNull();
        params.put("additional_kwargs", "not a map");
        assertThat(BaseChatModelConnection.providerInstructions(params)).isNull();
        assertThat(BaseChatModelConnection.providerInstructions(null)).isNull();

        // The Anthropic API takes its instructions as system: a text, or a list of text
        // blocks. Inside additional_kwargs it is read when there are no instructions.
        params.remove("instructions");
        params.put("additional_kwargs", Map.of("system", "s".repeat(40)));
        assertThat(BaseChatModelConnection.providerInstructions(params)).isEqualTo("s".repeat(40));
        assertThat(connection.estimateInputTokens(messages, List.of(), params))
                .isEqualTo(TokenEstimate.approximate(20));
        params.put(
                "additional_kwargs",
                Map.of(
                        "system",
                        List.of(
                                Map.of("type", "text", "text", "first"),
                                Map.of(
                                        "type",
                                        "text",
                                        "text",
                                        "second",
                                        "cache_control",
                                        Map.of("type", "ephemeral")),
                                Map.of("type", "image"),
                                "not a block")));
        assertThat(BaseChatModelConnection.providerInstructions(params)).isEqualTo("first\nsecond");
        params.put("instructions", "top");
        params.put("additional_kwargs", Map.of("system", "s"));
        assertThat(BaseChatModelConnection.providerInstructions(params)).isEqualTo("top");
        params.remove("instructions");
        params.put("additional_kwargs", Map.of("system", List.of()));
        assertThat(BaseChatModelConnection.providerInstructions(params)).isNull();
        params.put("additional_kwargs", Map.of());
        params.put("system", "outside additional_kwargs");
        assertThat(BaseChatModelConnection.providerInstructions(params)).isNull();
    }

    @Test
    void aToolDefinitionCostsItsNameDescriptionAndInputSchema() {
        Tool lookup = new DescribedTool("lookup", "Look something up.", LOOKUP_SCHEMA);
        // 8 for the tool; 6 + 18 + 54 = 78 units are 20 tokens.
        assertThat(LOOKUP_SCHEMA).hasSize(54);
        assertThat(approximate(List.of(), List.of(lookup))).isEqualTo(28);
        // Units of messages and tools are added before they are rounded up: 81 units, 21 tokens.
        assertThat(approximate(List.of(ChatMessage.user("abc")), List.of(lookup)))
                .isEqualTo(4 + 8 + 21);
    }

    @Test
    void aConnectionApproximatesUnlessItOverridesTheEstimate() {
        CountingConnection connection = new CountingConnection(null);

        TokenEstimate estimate =
                connection.estimateInputTokens(
                        List.of(ChatMessage.user("hello")), List.of(), Map.of());

        assertThat(estimate).isEqualTo(TokenEstimate.approximate(6));
    }

    @Test
    void theSetupEstimatesTheRequestItWouldSend() {
        CountingConnection connection = new CountingConnection(TokenEstimate.exact(321));
        Prompt prompt = Prompt.fromText("Answer about {topic}.");
        Tool lookup = new DescribedTool("lookup", "Look something up.", LOOKUP_SCHEMA);
        Setup setup = new Setup(connection, prompt, List.of(lookup));

        TokenEstimate estimate =
                setup.estimateInputTokens(
                        List.of(ChatMessage.user("hello")), Map.of("topic", "Flink"));

        // The connection decides the count.
        assertThat(estimate).isEqualTo(TokenEstimate.exact(321));
        // It is asked about the prepared request: the rendered prompt, then the messages, with the
        // bound tools and the setup's parameters.
        assertThat(connection.estimatedMessages)
                .extracting(ChatMessage::getText)
                .containsExactly("Answer about Flink.", "hello");
        assertThat(connection.estimatedTools).containsExactly(lookup);
        assertThat(connection.estimatedParams).containsEntry("model", "m");
    }

    @Test
    void theSetupEstimateFallsBackToTheApproximation() {
        Setup setup = new Setup(new CountingConnection(null), null, List.of());

        assertThat(setup.estimateInputTokens(List.of(ChatMessage.user("hello")), Map.of()))
                .isEqualTo(TokenEstimate.approximate(6));
    }

    @Test
    void theSetupEstimateNeedsAnOpenConnection() {
        Setup setup = new Setup(null, null, List.of());

        assertThatThrownBy(
                        () ->
                                setup.estimateInputTokens(
                                        List.of(ChatMessage.user("hello")), Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("open()");
    }
}
