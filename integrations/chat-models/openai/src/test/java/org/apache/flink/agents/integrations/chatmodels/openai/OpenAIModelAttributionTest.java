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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The returned model attribution must match the effective model sent over HTTP. */
class OpenAIModelAttributionTest {

    private static final String DEFAULT_MODEL = "configured-model";

    static Stream<Arguments> modelParameters() {
        return Stream.of(false, true)
                .flatMap(
                        vllm ->
                                Stream.of(
                                        Arguments.of(vllm, null, DEFAULT_MODEL),
                                        Arguments.of(vllm, Map.of(), DEFAULT_MODEL),
                                        Arguments.of(vllm, parameters(null), DEFAULT_MODEL),
                                        Arguments.of(vllm, parameters(""), DEFAULT_MODEL),
                                        Arguments.of(vllm, parameters(" \t\n"), DEFAULT_MODEL),
                                        Arguments.of(
                                                vllm,
                                                parameters("override-model"),
                                                "override-model")));
    }

    private static Map<String, Object> parameters(String model) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("model", model);
        return parameters;
    }

    @ParameterizedTest
    @MethodSource("modelParameters")
    void attributesUsageToTheRequestedModel(
            boolean vllm, Map<String, Object> parameters, String expectedModel) throws IOException {
        try (FakeOpenAICompletionsEndpoint endpoint =
                FakeOpenAICompletionsEndpoint.servingFinishReason("stop")) {
            ResourceDescriptor descriptor =
                    ResourceDescriptor.Builder.newBuilder(
                                    vllm
                                            ? VLLMChatModelConnection.class.getName()
                                            : OpenAICompletionsConnection.class.getName())
                            .addInitialArgument("api_key", "test-key")
                            .addInitialArgument("api_base_url", endpoint.baseUrl())
                            .addInitialArgument("model", DEFAULT_MODEL)
                            .build();
            ResourceContext context = ResourceContext.fromGetResource((a, b) -> null);
            OpenAICompletionsConnection connection =
                    vllm
                            ? new VLLMChatModelConnection(descriptor, context)
                            : new OpenAICompletionsConnection(descriptor, context);
            Map<String, Object> original = parameters == null ? null : new HashMap<>(parameters);
            ChatResult result =
                    connection.chat(List.of(ChatMessage.user("hi")), List.of(), parameters);

            assertThat(
                            new ObjectMapper()
                                    .readTree(endpoint.lastRequestBody())
                                    .get("model")
                                    .asText())
                    .isEqualTo(expectedModel);
            assertThat(result.getModel()).isEqualTo(expectedModel);
            assertThat(result.getUsage().getPromptTokens()).isEqualTo(11);
            assertThat(result.getUsage().getCompletionTokens()).isEqualTo(7);
            assertThat(parameters).isEqualTo(original);
        }
    }
}
