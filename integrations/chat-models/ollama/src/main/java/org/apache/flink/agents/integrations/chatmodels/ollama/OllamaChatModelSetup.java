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

package org.apache.flink.agents.integrations.chatmodels.ollama;

import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * A chat model integration for Ollama powered by the ollama4j client.
 *
 * <p>This implementation adapts the generic Flink Agents chat model interface to Ollama's
 * conversation API.
 *
 * <p>See also {@link BaseChatModelSetup} for the common resource abstractions and lifecycle.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyAgent extends Agent {
 *   // Register the chat model setup via @ChatModelSetup metadata.
 *   @ChatModelSetup
 *   public static ResourceDesc ollama() {
 *     return ResourceDescriptor.Builder.newBuilder(OllamaChatModelSetup.class.getName())
 *                 .addInitialArgument("connection", "myConnection") // the name of OllamaChatModelConnection
 *                 .addInitialArgument("model", "qwen3:4b") // the model name
 *                 .addInitialArgument("prompt", myPrompt) // the optional prompt name
 *                 .addInitialArgument("tools", List.of("myTool")) // the optional tool names
 *                 .build();
 *   }
 * }
 * }</pre>
 */
public class OllamaChatModelSetup extends BaseChatModelSetup {

    private final String model;
    private final boolean extractReasoning;

    public OllamaChatModelSetup(
            ResourceDescriptor descriptor, BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
        this.model = descriptor.getArgument("model");
        this.extractReasoning = descriptor.getArgument("extract_reasoning", false);
    }

    /**
     * Creates a new OllamaChatModel.
     *
     * @param model the Ollama model name to use for chat completions
     * @param prompt optional prompt resource name to be used by higher-level orchestration
     * @param tools optional list of tool resource names to register as callable functions
     * @param getResource a function to resolve resources (e.g., tools) by name and type
     * @throws IllegalArgumentException if endpoint is null or empty
     */
    public OllamaChatModelSetup(
            String model,
            String prompt,
            List<String> tools,
            BiFunction<String, ResourceType, Resource> getResource) {
        this(
                new ResourceDescriptor(
                        OllamaChatModelSetup.class.getName(),
                        Map.of("model", model, "prompt", prompt, "tools", tools)),
                getResource);
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("model", model);
        params.put("extract_reasoning", extractReasoning);
        return params;
    }
}
