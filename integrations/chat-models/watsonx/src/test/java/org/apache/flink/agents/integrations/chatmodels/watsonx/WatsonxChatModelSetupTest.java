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

import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link WatsonxChatModelSetup}. */
class WatsonxChatModelSetupTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    @Test
    @DisplayName("Default model applies when the model argument is omitted")
    void testDefaultModel() {
        WatsonxChatModelSetup setup =
                new WatsonxChatModelSetup(
                        ResourceDescriptor.Builder.newBuilder(WatsonxChatModelSetup.class.getName())
                                .addInitialArgument("connection", "watsonx")
                                .build(),
                        NOOP);

        Map<String, Object> params = setup.getParameters();
        assertThat(params.get("model")).isEqualTo(WatsonxChatModelSetup.DEFAULT_MODEL);
        assertThat(params.get("temperature")).isEqualTo(WatsonxChatModelSetup.DEFAULT_TEMPERATURE);
        assertThat(params).doesNotContainKey("max_tokens");
    }

    @Test
    @DisplayName("Configured model, temperature and max_tokens are exposed as parameters")
    void testConfiguredParameters() {
        WatsonxChatModelSetup setup =
                new WatsonxChatModelSetup(
                        ResourceDescriptor.Builder.newBuilder(WatsonxChatModelSetup.class.getName())
                                .addInitialArgument("connection", "watsonx")
                                .addInitialArgument("model", "ibm/granite-3-3-8b-instruct")
                                .addInitialArgument("temperature", 0.2)
                                .addInitialArgument("max_tokens", 512)
                                .addInitialArgument("extract_reasoning", true)
                                .addInitialArgument(
                                        "additional_kwargs", Map.of("top_p", 0.9, "seed", 7))
                                .build(),
                        NOOP);

        Map<String, Object> params = setup.getParameters();
        assertThat(params.get("model")).isEqualTo("ibm/granite-3-3-8b-instruct");
        assertThat(params.get("temperature")).isEqualTo(0.2);
        assertThat(params.get("max_tokens")).isEqualTo(512);
        assertThat(params.get("extract_reasoning")).isEqualTo(true);
        assertThat(params.get("additional_kwargs"))
                .isEqualTo(Map.of("top_p", 0.9, "seed", 7));
    }

    @Test
    @DisplayName("Out-of-range and non-finite temperatures are rejected")
    void testTemperatureValidation() {
        for (double invalidTemperature :
                new double[] {-0.1, 2.5, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(
                            () ->
                                    new WatsonxChatModelSetup(
                                            ResourceDescriptor.Builder.newBuilder(
                                                            WatsonxChatModelSetup.class.getName())
                                                    .addInitialArgument("connection", "watsonx")
                                                    .addInitialArgument(
                                                            "temperature", invalidTemperature)
                                                    .build(),
                                            NOOP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("temperature");
        }
    }

    @Test
    @DisplayName("max_tokens must be a positive integer")
    void testMaxTokensValidation() {
        for (Number invalidMaxTokens :
                new Number[] {0, -1, 1.5, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(
                            () ->
                                    new WatsonxChatModelSetup(
                                            ResourceDescriptor.Builder.newBuilder(
                                                            WatsonxChatModelSetup.class.getName())
                                                    .addInitialArgument("connection", "watsonx")
                                                    .addInitialArgument(
                                                            "max_tokens", invalidMaxTokens)
                                                    .build(),
                                            NOOP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("max_tokens");
        }
    }

}
