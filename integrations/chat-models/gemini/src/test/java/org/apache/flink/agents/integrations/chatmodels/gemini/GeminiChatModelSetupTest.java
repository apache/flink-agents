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

package org.apache.flink.agents.integrations.chatmodels.gemini;

import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link GeminiChatModelSetup}. */
class GeminiChatModelSetupTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    private static ResourceDescriptor.Builder base() {
        return ResourceDescriptor.Builder.newBuilder(GeminiChatModelSetup.class.getName())
                .addInitialArgument("connection", "conn");
    }

    @Test
    @DisplayName("getParameters applies default model, temperature and max output tokens")
    void testGetParametersDefaults() {
        GeminiChatModelSetup setup = new GeminiChatModelSetup(base().build(), NOOP);

        Map<String, Object> params = setup.getParameters();
        assertThat(params).containsEntry("model", "gemini-3-pro-preview");
        assertThat(params).containsEntry("temperature", 0.1);
        assertThat(params).containsEntry("max_output_tokens", 1024L);
    }

    @Test
    @DisplayName("getParameters honors custom model, temperature and max output tokens")
    void testGetParametersCustom() {
        ResourceDescriptor desc =
                base().addInitialArgument("model", "gemini-3-flash-preview")
                        .addInitialArgument("temperature", 0.7)
                        .addInitialArgument("max_output_tokens", 4096)
                        .build();
        GeminiChatModelSetup setup = new GeminiChatModelSetup(desc, NOOP);

        Map<String, Object> params = setup.getParameters();
        assertThat(params).containsEntry("model", "gemini-3-flash-preview");
        assertThat(params).containsEntry("temperature", 0.7);
        assertThat(params).containsEntry("max_output_tokens", 4096L);
    }

    @Test
    @DisplayName("Constructor rejects out-of-range temperature")
    void testInvalidTemperature() {
        assertThatThrownBy(
                        () ->
                                new GeminiChatModelSetup(
                                        base().addInitialArgument("temperature", 2.5).build(),
                                        NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("temperature");
    }

    @Test
    @DisplayName("Constructor rejects non-positive max output tokens")
    void testInvalidMaxOutputTokens() {
        assertThatThrownBy(
                        () ->
                                new GeminiChatModelSetup(
                                        base().addInitialArgument("max_output_tokens", 0).build(),
                                        NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max_output_tokens");
    }

    @Test
    @DisplayName("Extends BaseChatModelSetup")
    void testInheritance() {
        assertThat(new GeminiChatModelSetup(base().build(), NOOP))
                .isInstanceOf(BaseChatModelSetup.class);
    }
}
