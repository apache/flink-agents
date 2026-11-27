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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.openai.core.ObjectMappers;
import com.openai.errors.BadRequestException;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.TestResourceContexts;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Checks setup parameters in the actual HTTP request sent by the SDK. */
class OpenAISetupRequestParametersTest {
    private static final ResourceContext NOOP =
            TestResourceContexts.fromGetResource((a, b) -> null);

    private static BaseChatModelSetup setup(boolean responses, Map<String, Object> arguments) {
        ResourceDescriptor.Builder builder =
                ResourceDescriptor.Builder.newBuilder(
                                responses
                                        ? OpenAIResponsesModelSetup.class.getName()
                                        : OpenAICompletionsSetup.class.getName())
                        .addInitialArgument("connection", "conn")
                        .addInitialArgument("model", "gpt-6-luna");
        arguments.forEach(builder::addInitialArgument);
        return responses
                ? new OpenAIResponsesModelSetup(builder.build(), NOOP)
                : new OpenAICompletionsSetup(builder.build(), NOOP);
    }

    private static JsonNode request(boolean responses, Map<String, Object> arguments)
            throws Exception {
        BaseChatModelSetup setup = setup(responses, arguments);
        try (FakeOpenAIErrorEndpoint endpoint = FakeOpenAIErrorEndpoint.rejectingWith400()) {
            ResourceDescriptor descriptor =
                    ResourceDescriptor.Builder.newBuilder(
                                    responses
                                            ? OpenAIResponsesModelConnection.class.getName()
                                            : OpenAICompletionsConnection.class.getName())
                            .addInitialArgument("api_key", "test-key")
                            .addInitialArgument("api_base_url", endpoint.baseUrl())
                            .build();
            BaseChatModelConnection connection =
                    responses
                            ? new OpenAIResponsesModelConnection(descriptor, NOOP)
                            : new OpenAICompletionsConnection(descriptor, NOOP);
            try {
                assertThatThrownBy(
                                () ->
                                        connection.chat(
                                                List.of(new ChatMessage(MessageRole.USER, "hi")),
                                                List.of(),
                                                setup.getParameters()))
                        .isInstanceOf(BadRequestException.class);
                return ObjectMappers.jsonMapper()
                        .reader()
                        .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                        .readTree(endpoint.requestBody());
            } finally {
                connection.close();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void defaultsOmitTemperatureAndReasoning(boolean responses) throws Exception {
        JsonNode body = request(responses, Map.of());
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.has("reasoning")).isFalse();
        assertThat(body.has("reasoning_effort")).isFalse();
        if (responses) {
            assertStore(body, false);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitTemperatureReachesRequest(boolean responses) throws Exception {
        for (double temperature : new double[] {0.0, 0.3, 2.0}) {
            assertTemperature(request(responses, Map.of("temperature", temperature)), temperature);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void additionalTemperatureReachesRequestAndOverridesExplicitValue(boolean responses)
            throws Exception {
        assertTemperature(
                request(responses, Map.of("additional_kwargs", Map.of("temperature", 0.0))), 0.0);
        assertTemperature(
                request(
                        responses,
                        Map.of(
                                "temperature",
                                0.3,
                                "additional_kwargs",
                                Map.of("temperature", 0.7))),
                0.7);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidExplicitTemperatureIsRejected(boolean responses) {
        for (double temperature : new double[] {-0.1, 2.1}) {
            assertThatThrownBy(() -> setup(responses, Map.of("temperature", temperature)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("temperature");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitStoreReachesRequest(boolean store) throws Exception {
        assertStore(request(true, Map.of("store", store)), store);
        assertStore(request(true, Map.of("additional_kwargs", Map.of("store", store))), store);
        assertStore(
                request(true, Map.of("store", !store, "additional_kwargs", Map.of("store", store))),
                store);
    }

    private static void assertStore(JsonNode body, boolean expected) {
        JsonNode store = body.path("store");
        assertThat(store.isBoolean()).as("store is a JSON boolean").isTrue();
        assertThat(store.booleanValue()).isEqualTo(expected);
    }

    private static void assertTemperature(JsonNode body, double expected) {
        JsonNode temperature = body.path("temperature");
        assertThat(temperature.isNumber()).as("temperature is a JSON number").isTrue();
        assertThat(temperature.doubleValue()).isEqualTo(expected);
    }
}
