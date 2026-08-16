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

import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link VLLMChatModelSetup}. */
class VLLMChatModelSetupTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    private static ResourceDescriptor.Builder descriptorBuilder() {
        return ResourceDescriptor.Builder.newBuilder(VLLMChatModelSetup.class.getName());
    }

    @Test
    @DisplayName("Constructor throws when model is missing: vLLM has no default model")
    void testConstructorMissingModel() {
        ResourceDescriptor desc =
                descriptorBuilder().addInitialArgument("connection", "vllm").build();
        assertThatThrownBy(() -> new VLLMChatModelSetup(desc, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model is required for vLLM");
    }

    @Test
    @DisplayName("Constructor throws when model is blank")
    void testConstructorBlankModel() {
        ResourceDescriptor desc =
                descriptorBuilder()
                        .addInitialArgument("connection", "vllm")
                        .addInitialArgument("model", " ")
                        .build();
        assertThatThrownBy(() -> new VLLMChatModelSetup(desc, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model is required for vLLM");
    }

    @Test
    @DisplayName("getParameters carries the served model name and inherited OpenAI defaults")
    void testGetParameters() {
        ResourceDescriptor desc =
                descriptorBuilder()
                        .addInitialArgument("connection", "vllm")
                        .addInitialArgument("model", "Qwen/Qwen2.5-7B-Instruct")
                        .addInitialArgument("temperature", 0.3d)
                        .addInitialArgument("max_tokens", 512)
                        .build();
        VLLMChatModelSetup setup = new VLLMChatModelSetup(desc, NOOP);

        Map<String, Object> params = setup.getParameters();
        assertThat(params)
                .containsEntry("model", "Qwen/Qwen2.5-7B-Instruct")
                .containsEntry("temperature", 0.3d)
                .containsEntry("max_tokens", 512);
    }
}
