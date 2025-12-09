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
 * Chat model setup for the OpenAI Chat Completions API.
 *
 * <p>Responsible for providing per-chat configuration such as model, temperature, tool bindings,
 * and additional OpenAI parameters. The setup delegates execution to {@link
 * OpenAIChatModelConnection}.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyAgent extends Agent {
 *   @ChatModelSetup
 *   public static ResourceDesc openAI() {
 *     return ResourceDescriptor.Builder.newBuilder(OpenAIChatModelSetup.class.getName())
 *             .addInitialArgument("connection", "myOpenAIConnection")
 *             .addInitialArgument("model", "gpt-4o-mini")
 *             .addInitialArgument("temperature", 0.3d)
 *             .addInitialArgument("max_tokens", 500)
 *             .addInitialArgument("strict", true)
 *             .addInitialArgument("reasoning_effort", "medium")
 *             .addInitialArgument("tools", List.of("convertTemperature", "calculateBMI"))
 *             .addInitialArgument(
 *                     "additional_kwargs",
 *                     Map.of("seed", 42, "user", "user-123"))
 *             .build();
 *   }
 * }
 * }</pre>
 */
public class OpenAIChatModelSetup extends BaseChatModelSetup {

    private static final String DEFAULT_MODEL = "gpt-3.5-turbo";
    private static final double DEFAULT_TEMPERATURE = 0.1d;
    private static final int DEFAULT_TOP_LOGPROBS = 0;
    private static final boolean DEFAULT_STRICT = false;
    private static final Set<String> VALID_REASONING_EFFORTS = Set.of("low", "medium", "high");

    private final Double temperature;
    private final Integer maxTokens;
    private final Boolean logprobs;
    private final Integer topLogprobs;
    private final Boolean strict;
    private final String reasoningEffort;
    private final Map<String, Object> additionalArguments;

    public OpenAIChatModelSetup(
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

        this.logprobs = descriptor.getArgument("logprobs");

        this.topLogprobs =
                Optional.ofNullable(descriptor.<Number>getArgument("top_logprobs"))
                        .map(Number::intValue)
                        .orElse(DEFAULT_TOP_LOGPROBS);
        if (this.topLogprobs < 0 || this.topLogprobs > 20) {
            throw new IllegalArgumentException("top_logprobs must be between 0 and 20");
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

        Map<String, Object> additional =
                Optional.ofNullable(
                                descriptor.<Map<String, Object>>getArgument("additional_kwargs"))
                        .map(HashMap::new)
                        .orElseGet(HashMap::new);
        this.additionalArguments = additional;

        if (this.model == null || this.model.isBlank()) {
            this.model = DEFAULT_MODEL;
        }
    }

    public OpenAIChatModelSetup(
            String model,
            double temperature,
            Integer maxTokens,
            Boolean logprobs,
            Integer topLogprobs,
            Boolean strict,
            String reasoningEffort,
            Map<String, Object> additionalArguments,
            List<String> tools,
            BiFunction<String, ResourceType, Resource> getResource) {
        this(
                createDescriptor(
                        model,
                        temperature,
                        maxTokens,
                        logprobs,
                        topLogprobs,
                        strict,
                        reasoningEffort,
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
        if (Boolean.TRUE.equals(logprobs)) {
            parameters.put("logprobs", logprobs);
            parameters.put("top_logprobs", topLogprobs);
        }
        if (strict) {
            parameters.put("strict", strict);
        }
        if (reasoningEffort != null) {
            parameters.put("reasoning_effort", reasoningEffort);
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
            Boolean logprobs,
            Integer topLogprobs,
            Boolean strict,
            String reasoningEffort,
            Map<String, Object> additionalArguments,
            List<String> tools) {
        ResourceDescriptor.Builder builder =
                ResourceDescriptor.Builder.newBuilder(OpenAIChatModelSetup.class.getName())
                        .addInitialArgument("model", model)
                        .addInitialArgument("temperature", temperature);

        if (maxTokens != null) {
            builder.addInitialArgument("max_tokens", maxTokens);
        }
        if (logprobs != null) {
            builder.addInitialArgument("logprobs", logprobs);
        }
        if (topLogprobs != null) {
            builder.addInitialArgument("top_logprobs", topLogprobs);
        }
        if (strict != null) {
            builder.addInitialArgument("strict", strict);
        }
        if (reasoningEffort != null) {
            builder.addInitialArgument("reasoning_effort", reasoningEffort);
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
