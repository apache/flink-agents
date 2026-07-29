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

import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link AzureOpenAIChatModelSetup}. */
class AzureOpenAIChatModelSetupTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    private static ResourceDescriptor.Builder descriptorBuilder() {
        return ResourceDescriptor.Builder.newBuilder(AzureOpenAIChatModelSetup.class.getName());
    }

    @Test
    @DisplayName("getParameters includes model and default logprobs=false")
    void testGetParametersMinimal() {
        ResourceDescriptor desc =
                descriptorBuilder().addInitialArgument("model", "my-deployment").build();
        AzureOpenAIChatModelSetup setup = new AzureOpenAIChatModelSetup(desc, NOOP);

        Map<String, Object> params = setup.getParameters();
        assertThat(params).containsEntry("model", "my-deployment");
        assertThat(params).containsEntry("logprobs", false);
        assertThat(params)
                .doesNotContainKeys("temperature", "max_tokens", "model_of_azure_deployment");
    }

    @Test
    @DisplayName("getParameters includes all explicitly-set fields")
    void testGetParametersAllFields() {
        ResourceDescriptor desc =
                descriptorBuilder()
                        .addInitialArgument("model", "my-deployment")
                        .addInitialArgument("model_of_azure_deployment", "gpt-4o")
                        .addInitialArgument("temperature", 0.3d)
                        .addInitialArgument("max_tokens", 500)
                        .addInitialArgument("logprobs", true)
                        .build();
        AzureOpenAIChatModelSetup setup = new AzureOpenAIChatModelSetup(desc, NOOP);

        Map<String, Object> params = setup.getParameters();
        assertThat(params)
                .containsEntry("model", "my-deployment")
                .containsEntry("model_of_azure_deployment", "gpt-4o")
                .containsEntry("temperature", 0.3d)
                .containsEntry("max_tokens", 500)
                .containsEntry("logprobs", true);
    }

    @Test
    @DisplayName("getParameters nests additional_kwargs under a dedicated key")
    void testGetParametersNestsAdditionalKwargs() {
        ResourceDescriptor desc =
                descriptorBuilder()
                        .addInitialArgument("model", "my-deployment")
                        .addInitialArgument(
                                "additional_kwargs", Map.of("seed", 42, "user", "user-123"))
                        .build();
        AzureOpenAIChatModelSetup setup = new AzureOpenAIChatModelSetup(desc, NOOP);

        Map<String, Object> params = setup.getParameters();
        assertThat(params)
                .containsEntry("model", "my-deployment")
                .containsEntry("additional_kwargs", Map.of("seed", 42, "user", "user-123"))
                .doesNotContainKeys("seed", "user");
    }

    @Test
    @DisplayName("temperature must be in [0.0, 2.0]")
    void testTemperatureValidation() {
        ResourceDescriptor tooHigh =
                descriptorBuilder()
                        .addInitialArgument("model", "m")
                        .addInitialArgument("temperature", 2.5d)
                        .build();
        assertThatThrownBy(() -> new AzureOpenAIChatModelSetup(tooHigh, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("temperature must be between 0.0 and 2.0");

        ResourceDescriptor negative =
                descriptorBuilder()
                        .addInitialArgument("model", "m")
                        .addInitialArgument("temperature", -0.1d)
                        .build();
        assertThatThrownBy(() -> new AzureOpenAIChatModelSetup(negative, NOOP))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("max_tokens must be greater than 0")
    void testMaxTokensValidation() {
        ResourceDescriptor zero =
                descriptorBuilder()
                        .addInitialArgument("model", "m")
                        .addInitialArgument("max_tokens", 0)
                        .build();
        assertThatThrownBy(() -> new AzureOpenAIChatModelSetup(zero, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max_tokens must be greater than 0");
    }

    @Test
    @DisplayName("Extends BaseChatModelSetup")
    void testInheritance() {
        ResourceDescriptor desc = descriptorBuilder().addInitialArgument("model", "m").build();
        assertThat(new AzureOpenAIChatModelSetup(desc, NOOP))
                .isInstanceOf(BaseChatModelSetup.class);
    }

    @Test
    @DisplayName("model field is preserved through descriptor round-trip")
    void testModelFieldRoundtrip() {
        ResourceDescriptor desc =
                descriptorBuilder().addInitialArgument("model", "test-deployment").build();
        AzureOpenAIChatModelSetup setup = new AzureOpenAIChatModelSetup(desc, NOOP);
        assertThat(setup.getParameters()).containsEntry("model", "test-deployment");
    }
}
