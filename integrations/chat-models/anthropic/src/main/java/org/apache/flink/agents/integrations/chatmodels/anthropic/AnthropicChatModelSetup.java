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
package org.apache.flink.agents.integrations.chatmodels.anthropic;

import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Chat model setup for the Anthropic Messages API.
 *
 * <p>Responsible for providing per-chat configuration such as model, temperature, max_tokens, tool
 * bindings, and additional Anthropic parameters. The setup delegates execution to {@link
 * AnthropicChatModelConnection}.
 *
 * <p>Supported parameters:
 *
 * <ul>
 *   <li><b>connection</b> (required): Name of the AnthropicChatModelConnection resource
 *   <li><b>model</b> (optional): Model name (default: claude-sonnet-4-20250514)
 *   <li><b>temperature</b> (optional): Sampling temperature 0.0-1.0 (default: 0.1)
 *   <li><b>max_tokens</b> (optional): Maximum tokens in response (default: 1024)
 *   <li><b>json_prefill</b> (optional): When true, prefills assistant response with "{" to enforce
 *       JSON output. Automatically disabled when tools are passed. (default: true)
 *   <li><b>strict_tools</b> (optional): When true, tool calls adhere strictly to the JSON schema.
 *       (default: false)
 *   <li><b>tools</b> (optional): List of tool names available for the model to use
 *   <li><b>additional_kwargs</b> (optional): Additional parameters (top_k, top_p, stop_sequences)
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyAgent extends Agent {
 *   @ChatModelSetup
 *   public static ResourceDesc anthropic() {
 *     return ResourceDescriptor.Builder.newBuilder(AnthropicChatModelSetup.class.getName())
 *             .addInitialArgument("connection", "myAnthropicConnection")
 *             .addInitialArgument("model", "claude-sonnet-4-20250514")
 *             .addInitialArgument("temperature", 0.3d)
 *             .addInitialArgument("max_tokens", 2048)
 *             .addInitialArgument("strict_tools", true)
 *             .addInitialArgument("tools", List.of("convertTemperature", "calculateBMI"))
 *             .addInitialArgument(
 *                     "additional_kwargs",
 *                     Map.of("top_k", 40, "top_p", 0.9))
 *             .build();
 *   }
 * }
 * }</pre>
 */
public class AnthropicChatModelSetup extends BaseChatModelSetup {

    private static final String DEFAULT_MODEL = "claude-sonnet-4-20250514";
    private static final double DEFAULT_TEMPERATURE = 0.1d;
    private static final long DEFAULT_MAX_TOKENS = 1024L;
    private static final boolean DEFAULT_JSON_PREFILL = true;
    private static final boolean DEFAULT_STRICT_TOOLS = false;

    private final Double temperature;
    private final Long maxTokens;
    private final Boolean jsonPrefill;
    private final Boolean strictTools;
    private final Map<String, Object> additionalArguments;

    public AnthropicChatModelSetup(
            ResourceDescriptor descriptor, BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
        this.temperature =
                Optional.ofNullable(descriptor.<Number>getArgument("temperature"))
                        .map(Number::doubleValue)
                        .orElse(DEFAULT_TEMPERATURE);
        if (this.temperature < 0.0 || this.temperature > 1.0) {
            throw new IllegalArgumentException("temperature must be between 0.0 and 1.0");
        }

        this.maxTokens =
                Optional.ofNullable(descriptor.<Number>getArgument("max_tokens"))
                        .map(Number::longValue)
                        .orElse(DEFAULT_MAX_TOKENS);
        if (this.maxTokens <= 0) {
            throw new IllegalArgumentException("max_tokens must be greater than 0");
        }

        this.jsonPrefill =
                Optional.ofNullable(descriptor.<Boolean>getArgument("json_prefill"))
                        .orElse(DEFAULT_JSON_PREFILL);

        this.strictTools =
                Optional.ofNullable(descriptor.<Boolean>getArgument("strict_tools"))
                        .orElse(DEFAULT_STRICT_TOOLS);

        this.additionalArguments =
                Optional.ofNullable(
                                descriptor.<Map<String, Object>>getArgument("additional_kwargs"))
                        .map(HashMap::new)
                        .orElseGet(HashMap::new);

        if (this.model == null || this.model.isBlank()) {
            this.model = DEFAULT_MODEL;
        }
    }

    public AnthropicChatModelSetup(
            String model,
            double temperature,
            long maxTokens,
            Map<String, Object> additionalArguments,
            List<String> tools,
            BiFunction<String, ResourceType, Resource> getResource) {
        this(
                createDescriptor(model, temperature, maxTokens, additionalArguments, tools),
                getResource);
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> parameters = new HashMap<>();
        if (model != null) {
            parameters.put("model", model);
        }
        parameters.put("temperature", temperature);
        parameters.put("max_tokens", maxTokens);
        parameters.put("json_prefill", jsonPrefill);
        parameters.put("strict_tools", strictTools);
        if (additionalArguments != null && !additionalArguments.isEmpty()) {
            parameters.put("additional_kwargs", additionalArguments);
        }
        return parameters;
    }

    private static ResourceDescriptor createDescriptor(
            String model,
            double temperature,
            long maxTokens,
            Map<String, Object> additionalArguments,
            List<String> tools) {
        ResourceDescriptor.Builder builder =
                ResourceDescriptor.Builder.newBuilder(AnthropicChatModelSetup.class.getName())
                        .addInitialArgument("model", model)
                        .addInitialArgument("temperature", temperature)
                        .addInitialArgument("max_tokens", maxTokens);

        if (additionalArguments != null && !additionalArguments.isEmpty()) {
            builder.addInitialArgument("additional_kwargs", additionalArguments);
        }
        if (tools != null && !tools.isEmpty()) {
            builder.addInitialArgument("tools", tools);
        }

        return builder.build();
    }
}
