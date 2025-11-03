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
package org.apache.flink.agents.integrations.chatmodels.azureai;

import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;

/**
 * A chat model integration for Azure AI Chat Completions service.
 *
 * <p>This implementation adapts the generic Flink Agents chat model interface to the Azure AI Chat
 * Completions API.
 *
 * <p>See also {@link BaseChatModelSetup} for the common resource abstractions and lifecycle.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyAgent extends Agent {
 *   // Register the chat model setup via @ChatModelSetup metadata.
 *   @ChatModelSetup
 *   public static ResourceDesc azureAI() {
 *     return ResourceDescriptor.Builder.newBuilder(AzureAIChatModelSetup.class.getName())
 *             .addInitialArgument("model", "<your-azure-ai-model-name>")
 *             .addInitialArgument("prompt", "<your-prompt-template>")
 *             .addInitialArgument("tools", "<your-tool-list>")
 *             .addInitialArgument("key", "<your-azure-ai-key>")
 *             .addInitialArgument("endpoint", "<your-azure-ai-endpoint>")
 *             .build();
 *   }
 * }
 * }</pre>
 */
public class AzureAIChatModelSetup extends BaseChatModelSetup {

    public AzureAIChatModelSetup(
            ResourceDescriptor descriptor,
            java.util.function.BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
    }

    // For any other specific parameters, please refer to ChatCompletionsOptions
    @Override
    public java.util.Map<String, Object> getParameters() {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("model", model);
        return params;
    }
}
