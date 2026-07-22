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
package org.apache.flink.agents.integrations.chatmodels.watsonx;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live tests for {@link WatsonxChatModelConnection} against the real watsonx.ai service.
 *
 * <p>The test requires {@code WATSONX_URL}, either {@code WATSONX_API_KEY} or {@code
 * WATSONX_TOKEN}, and either {@code WATSONX_PROJECT_ID} or {@code WATSONX_SPACE_ID}. Override the
 * model with {@code WATSONX_CHAT_MODEL} if the default is not available in your region.
 */
@EnabledIf("credentialsAvailable")
class WatsonxChatModelLiveTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    static boolean credentialsAvailable() {
        return isSet("WATSONX_URL")
                && (isSet("WATSONX_API_KEY") || isSet("WATSONX_TOKEN"))
                && (isSet("WATSONX_PROJECT_ID") || isSet("WATSONX_SPACE_ID"));
    }

    private static boolean isSet(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }

    private static String model() {
        String model = System.getenv("WATSONX_CHAT_MODEL");
        return model != null ? model : WatsonxChatModelSetup.DEFAULT_MODEL;
    }

    private static WatsonxChatModelConnection connection() {
        return new WatsonxChatModelConnection(
                ResourceDescriptor.Builder.newBuilder(WatsonxChatModelConnection.class.getName())
                        .build(),
                NOOP);
    }

    @Test
    @DisplayName("Basic chat returns a non-empty assistant message")
    void testBasicChat() {
        ChatMessage response =
                connection()
                        .chat(
                                List.of(
                                        new ChatMessage(
                                                MessageRole.USER,
                                                "Say hello in one short sentence.")),
                                List.of(),
                                Map.of("model", model(), "max_tokens", 100));

        assertThat(response.getRole()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(response.getContent()).isNotBlank();
        assertThat(response.getExtraArgs().get("promptTokens")).isNotNull();
        assertThat(response.getExtraArgs().get("completionTokens")).isNotNull();
    }
}
