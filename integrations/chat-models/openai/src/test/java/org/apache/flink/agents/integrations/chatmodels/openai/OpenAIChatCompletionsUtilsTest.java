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

import com.openai.models.chat.completions.ChatCompletionMessage;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for how {@link OpenAIChatCompletionsUtils} carries a provider refusal from a Chat
 * Completions message onto the returned {@link ChatMessage}. The converter is shared by every Chat
 * Completions connection in this module.
 */
class OpenAIChatCompletionsUtilsTest {

    @Test
    @DisplayName("A refusal reason is carried into extraArgs")
    void testRefusalPreservedInExtraArgs() {
        // A refused response has no content, so the reason is the only thing separating it
        // from a genuinely empty completion.
        ChatCompletionMessage message =
                ChatCompletionMessage.builder()
                        .content(Optional.empty())
                        .refusal("I cannot help with that")
                        .build();

        ChatMessage result = OpenAIChatCompletionsUtils.convertFromOpenAIMessage(message);

        assertThat(result.getExtraArgs()).containsEntry("refusal", "I cannot help with that");
    }

    @Test
    @DisplayName("No refusal key is added when the provider did not refuse")
    void testNoRefusalKeyWhenAbsent() {
        ChatCompletionMessage message =
                ChatCompletionMessage.builder().content("hello").refusal(Optional.empty()).build();

        ChatMessage result = OpenAIChatCompletionsUtils.convertFromOpenAIMessage(message);

        assertThat(result.getExtraArgs()).doesNotContainKey("refusal");
    }

    @Test
    void testMaxRetriesRejectsFractionalBigDecimal() {
        assertThatThrownBy(
                        () ->
                                OpenAIChatCompletionsUtils.parseMaxRetries(
                                        descriptor(
                                                "max_retries",
                                                new BigDecimal("2.0000000000000000000000001"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testPositiveTimeoutBelowMillisecondRoundsUpToSdkPrecision() {
        assertThat(
                        OpenAIChatCompletionsUtils.parseTimeout(
                                descriptor("timeout", new BigDecimal("0.0000000001"))))
                .isEqualTo(Duration.ofMillis(1));
    }

    private static ResourceDescriptor descriptor(String argumentName, Number value) {
        return ResourceDescriptor.Builder.newBuilder(OpenAICompletionsConnection.class.getName())
                .addInitialArgument(argumentName, value)
                .build();
    }
}
