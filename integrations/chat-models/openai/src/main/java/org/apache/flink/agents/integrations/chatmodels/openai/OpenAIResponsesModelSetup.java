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
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Chat model setup for the OpenAI Responses API.
 *
 * <p>Responsible for providing per-chat configuration such as model, temperature, max tokens, tool
 * bindings, and Responses API-specific parameters. The setup delegates execution to {@link
 * OpenAIResponsesModelConnection}.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyAgent extends Agent {
 *   @ChatModelSetup
 *   public static ResourceDesc openAIResponses() {
 *     return ResourceDescriptor.Builder.newBuilder(OpenAIResponsesModelSetup.class.getName())
 *             .addInitialArgument("connection", "myOpenAIResponsesConnection")
 *             .addInitialArgument("model", "gpt-4o")
 *             .addInitialArgument("temperature", 0.3d)
 *             .addInitialArgument("max_tokens", 2048)
 *             .addInitialArgument("strict", true)
 *             .addInitialArgument("reasoning_effort", "medium")
 *             .addInitialArgument("store", true)
 *             .addInitialArgument("tools", List.of("getWeather", "searchDB"))
 *             .build();
 *   }
 * }
 * }</pre>
 */
public class OpenAIResponsesModelSetup extends BaseChatModelSetup {

    private static final String DEFAULT_MODEL = "gpt-4o";
    private static final double DEFAULT_TEMPERATURE = 0.1d;
    private static final boolean DEFAULT_STRICT = false;
    private static final boolean DEFAULT_STORE = false;
    private static final Set<String> VALID_REASONING_EFFORTS = Set.of("low", "medium", "high");

    private final Double temperature;
    private final Integer maxTokens;
    private final Boolean strict;
    private final String reasoningEffort;
    private final Boolean store;
    private final String instructions;
    private final Map<String, Object> additionalArguments;

    public OpenAIResponsesModelSetup(
            ResourceDescriptor descriptor, BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);

        this.temperature =
                Optional.ofNullable(descriptor.<Number>getArgument("temperature"))
                        .map(Number::doubleValue)
                        .orElse(DEFAULT_TEMPERATURE);
        if (this.temperature < 0.0 || this.temperature > 2.0) {
            throw new IllegalArgumentException("temperature must be between 0.0 and 2.0");
        }

        this.maxTokens =
                Optional.ofNullable(descriptor.<Number>getArgument("max_tokens"))
                        .map(Number::intValue)
                        .orElse(null);
        if (this.maxTokens != null && this.maxTokens <= 0) {
            throw new IllegalArgumentException("max_tokens must be greater than 0");
        }

        this.strict =
                Optional.ofNullable(descriptor.<Boolean>getArgument("strict"))
                        .orElse(DEFAULT_STRICT);

        this.reasoningEffort = descriptor.getArgument("reasoning_effort");
        if (this.reasoningEffort != null
                && !VALID_REASONING_EFFORTS.contains(this.reasoningEffort)) {
            throw new IllegalArgumentException(
                    "reasoning_effort must be one of: low, medium, high");
        }

        this.store =
                Optional.ofNullable(descriptor.<Boolean>getArgument("store")).orElse(DEFAULT_STORE);

        this.instructions = descriptor.getArgument("instructions");

        this.additionalArguments =
                Optional.ofNullable(
                                descriptor.<Map<String, Object>>getArgument("additional_kwargs"))
                        .map(HashMap::new)
                        .orElseGet(HashMap::new);

        if (this.model == null || this.model.isBlank()) {
            this.model = DEFAULT_MODEL;
        }
    }

    public OpenAIResponsesModelSetup(
            String model,
            double temperature,
            Integer maxTokens,
            Boolean strict,
            String reasoningEffort,
            Boolean store,
            String instructions,
            Map<String, Object> additionalArguments,
            List<String> tools,
            BiFunction<String, ResourceType, Resource> getResource) {
        this(
                createDescriptor(
                        model,
                        temperature,
                        maxTokens,
                        strict,
                        reasoningEffort,
                        store,
                        instructions,
                        additionalArguments,
                        tools),
                getResource);
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> parameters = new HashMap<>();
        if (model != null) {
            parameters.put("model", model);
        }
        parameters.put("temperature", temperature);
        if (maxTokens != null) {
            parameters.put("max_tokens", maxTokens);
        }
        if (strict) {
            parameters.put("strict", strict);
        }
        if (reasoningEffort != null) {
            parameters.put("reasoning_effort", reasoningEffort);
        }
        if (store) {
            parameters.put("store", store);
        }
        if (instructions != null) {
            parameters.put("instructions", instructions);
        }
        if (additionalArguments != null && !additionalArguments.isEmpty()) {
            parameters.put("additional_kwargs", additionalArguments);
        }
        return parameters;
    }

    private static ResourceDescriptor createDescriptor(
            String model,
            double temperature,
            Integer maxTokens,
            Boolean strict,
            String reasoningEffort,
            Boolean store,
            String instructions,
            Map<String, Object> additionalArguments,
            List<String> tools) {
        ResourceDescriptor.Builder builder =
                ResourceDescriptor.Builder.newBuilder(OpenAIResponsesModelSetup.class.getName())
                        .addInitialArgument("model", model)
                        .addInitialArgument("temperature", temperature);

        if (maxTokens != null) {
            builder.addInitialArgument("max_tokens", maxTokens);
        }
        if (strict != null) {
            builder.addInitialArgument("strict", strict);
        }
        if (reasoningEffort != null) {
            builder.addInitialArgument("reasoning_effort", reasoningEffort);
        }
        if (store != null) {
            builder.addInitialArgument("store", store);
        }
        if (instructions != null) {
            builder.addInitialArgument("instructions", instructions);
        }
        if (additionalArguments != null && !additionalArguments.isEmpty()) {
            builder.addInitialArgument("additional_kwargs", additionalArguments);
        }
        if (tools != null && !tools.isEmpty()) {
            builder.addInitialArgument("tools", tools);
        }

        return builder.build();
    }
}
