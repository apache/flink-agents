/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.flink.agents.api.prompt;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.ReasoningBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Tool results in explicit prompt templates follow the text-block substitution contract. */
class ToolResultPromptTest {
    @ParameterizedTest
    @ValueSource(strings = {"Paris", "{secret}"})
    void formatsToolResultTextWithoutChangingOtherFieldsOrTemplate(String city) {
        ImageBlock image = ImageBlock.fromUrl("image/png", "https://example.com/{city}.png");
        ToolResultBlock result =
                new ToolResultBlock(
                        "{city}",
                        List.of(
                                new TextBlock("weather: {city}"),
                                image,
                                new TextBlock("; {unknown}; {block}")),
                        true,
                        Map.of("source", "{city}"));
        ChatMessage message = ChatMessage.tool(result).withMetadata(Map.of("trace", "{city}"));
        Prompt prompt = Prompt.fromMessages(List.of(message));
        Map<String, String> args =
                Map.of("city", city, "secret", "do-not-expand", "block", "value");

        assertThat(prompt.formatString(args))
                .isEqualTo("tool: weather: " + city + "; {unknown}; value");
        ChatMessage formatted = prompt.formatMessages(MessageRole.USER, args).get(0);
        ToolResultBlock formattedResult = (ToolResultBlock) formatted.getBlocks().get(0);
        assertThat(formatted.getRole()).isEqualTo(MessageRole.TOOL);
        assertThat(formatted.getMetadata()).isEqualTo(message.getMetadata());
        assertThat(formattedResult.getCallId()).isEqualTo("{city}");
        assertThat(formattedResult.isError()).isTrue();
        assertThat(formattedResult.getMetadata()).isEqualTo(result.getMetadata());
        assertThat(formattedResult.getBlocks())
                .containsExactly(
                        new TextBlock("weather: " + city),
                        image,
                        new TextBlock("; {unknown}; value"));
        assertThat(formattedResult.getBlocks().get(1)).isSameAs(image);
        assertThat(result.getText()).isEqualTo("weather: {city}; {unknown}; {block}");
        assertThat(prompt.formatString(Map.of("city", "Berlin")))
                .isEqualTo("tool: weather: Berlin; {unknown}; {block}");
    }

    @Test
    void keepsToolCallsAndReasoningLiteralInTemplates() {
        ReasoningBlock reasoning = new ReasoningBlock("{city}", Map.of("signature", "{city}"));
        ToolCallBlock call = new ToolCallBlock("{city}", "lookup", Map.of("city", "{city}"));
        Prompt prompt =
                Prompt.fromMessages(
                        List.of(
                                ChatMessage.assistant(
                                        List.of(
                                                new TextBlock("checking {city}"),
                                                reasoning,
                                                call))));
        ChatMessage formatted =
                prompt.formatMessages(MessageRole.USER, Map.of("city", "Paris")).get(0);
        assertThat(formatted.getBlocks())
                .containsExactly(new TextBlock("checking Paris"), reasoning, call);
        assertThat(prompt.formatString(Map.of("city", "Paris")))
                .isEqualTo("assistant: checking Paris");
    }
}
