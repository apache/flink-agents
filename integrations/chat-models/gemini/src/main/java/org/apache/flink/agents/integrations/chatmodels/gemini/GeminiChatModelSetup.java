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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Chat model setup for the Google Gemini {@code generateContent} API.
 *
 * <p>Responsible for providing per-chat configuration such as model, temperature, max output
 * tokens, tool bindings, and additional Gemini parameters. The setup delegates execution to {@link
 * GeminiChatModelConnection}.
 *
 * <p>Supported parameters:
 *
 * <ul>
 *   <li><b>connection</b> (required): Name of the GeminiChatModelConnection resource
 *   <li><b>model</b> (optional): Model name (default: gemini-3.1-pro-preview)
 *   <li><b>temperature</b> (optional): Sampling temperature 0.0-2.0 (default: 0.1)
 *   <li><b>max_output_tokens</b> (optional): Maximum tokens in response (default: 1024)
 *   <li><b>tools</b> (optional): List of tool names available for the model to use
 *   <li><b>additional_kwargs</b> (optional): Additional parameters (e.g. top_k, top_p)
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyAgent extends Agent {
 *   @ChatModelSetup
 *   public static ResourceDesc gemini() {
 *     return ResourceDescriptor.Builder.newBuilder(GeminiChatModelSetup.class.getName())
 *             .addInitialArgument("connection", "myGeminiConnection")
 *             .addInitialArgument("model", "gemini-3.1-pro-preview")
 *             .addInitialArgument("temperature", 0.3d)
 *             .addInitialArgument("max_output_tokens", 2048)
 *             .addInitialArgument("tools", List.of("getWeather"))
 *             .build();
 *   }
 * }
 * }</pre>
 */
public class GeminiChatModelSetup extends BaseChatModelSetup {

    private static final String DEFAULT_MODEL = "gemini-3.1-pro-preview";
    private static final double DEFAULT_TEMPERATURE = 0.1d;
    private static final long DEFAULT_MAX_OUTPUT_TOKENS = 1024L;

    private final Double temperature;
    private final Long maxOutputTokens;
    private final Map<String, Object> additionalArguments;

    public GeminiChatModelSetup(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.temperature =
                Optional.ofNullable(descriptor.<Number>getArgument("temperature"))
                        .map(Number::doubleValue)
                        .orElse(DEFAULT_TEMPERATURE);
        if (this.temperature < 0.0 || this.temperature > 2.0) {
            throw new IllegalArgumentException("temperature must be between 0.0 and 2.0");
        }

        this.maxOutputTokens =
                Optional.ofNullable(descriptor.<Number>getArgument("max_output_tokens"))
                        .map(Number::longValue)
                        .orElse(DEFAULT_MAX_OUTPUT_TOKENS);
        if (this.maxOutputTokens <= 0) {
            throw new IllegalArgumentException("max_output_tokens must be greater than 0");
        }

        this.additionalArguments =
                Optional.ofNullable(
                                descriptor.<Map<String, Object>>getArgument("additional_kwargs"))
                        .map(HashMap::new)
                        .orElseGet(HashMap::new);

        if (this.model == null || this.model.isBlank()) {
            this.model = DEFAULT_MODEL;
        }
    }

    public GeminiChatModelSetup(
            String model,
            double temperature,
            long maxOutputTokens,
            Map<String, Object> additionalArguments,
            List<String> tools,
            ResourceContext resourceContext) {
        this(
                createDescriptor(model, temperature, maxOutputTokens, additionalArguments, tools),
                resourceContext);
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> parameters = new HashMap<>();
        if (model != null) {
            parameters.put("model", model);
        }
        parameters.put("temperature", temperature);
        parameters.put("max_output_tokens", maxOutputTokens);
        if (additionalArguments != null && !additionalArguments.isEmpty()) {
            parameters.put("additional_kwargs", additionalArguments);
        }
        return parameters;
    }

    private static ResourceDescriptor createDescriptor(
            String model,
            double temperature,
            long maxOutputTokens,
            Map<String, Object> additionalArguments,
            List<String> tools) {
        ResourceDescriptor.Builder builder =
                ResourceDescriptor.Builder.newBuilder(GeminiChatModelSetup.class.getName())
                        .addInitialArgument("model", model)
                        .addInitialArgument("temperature", temperature)
                        .addInitialArgument("max_output_tokens", maxOutputTokens);

        if (additionalArguments != null && !additionalArguments.isEmpty()) {
            builder.addInitialArgument("additional_kwargs", additionalArguments);
        }
        if (tools != null && !tools.isEmpty()) {
            builder.addInitialArgument("tools", tools);
        }

        return builder.build();
    }
}
