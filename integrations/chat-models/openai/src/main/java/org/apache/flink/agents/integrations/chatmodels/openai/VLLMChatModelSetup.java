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

/**
 * Chat model setup for a <a href="https://docs.vllm.ai">vLLM</a> server, delegating execution to
 * {@link VLLMChatModelConnection}.
 *
 * <p>Behaves like {@link OpenAICompletionsSetup} with one difference: <b>model</b> is required and
 * has no default, because a vLLM server only serves the model(s) it was started with — there is no
 * meaningful universal default. The value must match the model name announced by the server (see
 * {@code vllm serve <model>}, or query {@code GET /v1/models}).
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyAgent extends Agent {
 *   @ChatModelSetup
 *   public static ResourceDescriptor vllmModel() {
 *     return ResourceDescriptor.Builder.newBuilder(VLLMChatModelSetup.class.getName())
 *             .addInitialArgument("connection", "vllm")
 *             .addInitialArgument("model", "Qwen/Qwen2.5-7B-Instruct")
 *             .addInitialArgument("temperature", 0.3d)
 *             .build();
 *   }
 * }
 * }</pre>
 */
public class VLLMChatModelSetup extends OpenAICompletionsSetup {

    public VLLMChatModelSetup(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        String model = descriptor.getArgument("model");
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException(
                    "model is required for vLLM: it must match the model name served by the vLLM"
                            + " server (see `vllm serve <model>` or GET /v1/models).");
        }
    }
}
