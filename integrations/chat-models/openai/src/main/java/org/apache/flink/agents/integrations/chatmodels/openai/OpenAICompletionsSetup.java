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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Chat model setup for the OpenAI Chat Completions API.
 *
 * <p>Responsible for providing per-chat configuration such as model, temperature, tool bindings,
 * and additional OpenAI parameters. The setup delegates execution to {@link
 * OpenAICompletionsConnection}.
 *
 * <p>{@code reasoning_effort} accepts {@code none}, {@code low}, {@code medium} or {@code high} and
 * is unset by default. One exception: OpenAI documents some models as calling functions through
 * Chat Completions only with {@code reasoning_effort} set to {@code none} ({@code gpt-6-luna} and
 * {@code gpt-6-sol}). For a setup on one of these models that binds at least one tool, counting the
 * tools added for skills and sub-agents, {@link #open()} sets {@code reasoning_effort} to {@code
 * none} unless it is configured, either directly or as an {@code additional_kwargs} entry. A
 * configured value is always sent as given.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyAgent extends Agent {
 *   @ChatModelSetup
 *   public static ResourceDesc openAI() {
 *     return ResourceDescriptor.Builder.newBuilder(OpenAICompletionsSetup.class.getName())
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
public class OpenAICompletionsSetup extends BaseChatModelSetup {

    private static final String DEFAULT_MODEL = "gpt-6-luna";
    private static final double DEFAULT_TEMPERATURE = 0.1d;
    private static final int DEFAULT_TOP_LOGPROBS = 0;
    private static final boolean DEFAULT_STRICT = false;
    private static final String REASONING_EFFORT_KEY = "reasoning_effort";
    private static final String NO_REASONING_EFFORT = "none";
    private static final Set<String> VALID_REASONING_EFFORTS =
            Set.of(NO_REASONING_EFFORT, "low", "medium", "high");

    // Models whose OpenAI model page states: "Chat Completions supports function calling only with
    // `reasoning_effort` set to `none`." Source of truth:
    // https://developers.openai.com/api/docs/models/gpt-6-luna and
    // https://developers.openai.com/api/docs/models/gpt-6-sol
    //
    // Matched exactly rather than by a "gpt-6" prefix: other GPT-6 models reject "none" (for
    // example gpt-6.2-astra) or call tools through the Responses API only, so "none" would either
    // fail the request or turn reasoning off without making tool calls possible.
    private static final Set<String> MODELS_CALLING_TOOLS_ONLY_WITHOUT_REASONING =
            Set.of("gpt-6-luna", "gpt-6-sol");

    private final Double temperature;
    private final Integer maxTokens;
    private final Boolean logprobs;
    private final Integer topLogprobs;
    private final Boolean strict;
    private final String reasoningEffort;
    private final Map<String, Object> additionalArguments;

    /** The effort {@link #open()} chose because none was configured, or null. */
    private String defaultReasoningEffort;

    public OpenAICompletionsSetup(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
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

        this.reasoningEffort = descriptor.getArgument(REASONING_EFFORT_KEY);
        if (this.reasoningEffort != null
                && !VALID_REASONING_EFFORTS.contains(this.reasoningEffort)) {
            throw new IllegalArgumentException(
                    "reasoning_effort must be one of: none, low, medium, high");
        }

        Map<String, Object> additional =
                Optional.ofNullable(
                                descriptor.<Map<String, Object>>getArgument("additional_kwargs"))
                        .map(HashMap::new)
                        .orElseGet(HashMap::new);
        this.additionalArguments = additional;

        if (this.model == null) {
            this.model = DEFAULT_MODEL;
        }
    }

    public OpenAICompletionsSetup(
            String model,
            double temperature,
            Integer maxTokens,
            Boolean logprobs,
            Integer topLogprobs,
            Boolean strict,
            String reasoningEffort,
            Map<String, Object> additionalArguments,
            List<String> tools,
            ResourceContext resourceContext) {
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
                resourceContext);
    }

    /**
     * Resolves the connection, prompt and tools, then picks the default {@code reasoning_effort}
     * described on this class. The default depends on the resolved tools, so it is chosen here
     * rather than in the constructor.
     */
    @Override
    public void open() throws Exception {
        defaultReasoningEffort = null;
        super.open();
        boolean configured =
                reasoningEffort != null || additionalArguments.containsKey(REASONING_EFFORT_KEY);
        if (!configured
                && !tools.isEmpty()
                && MODELS_CALLING_TOOLS_ONLY_WITHOUT_REASONING.contains(model)) {
            defaultReasoningEffort = NO_REASONING_EFFORT;
        }
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
        String effectiveReasoningEffort =
                reasoningEffort != null ? reasoningEffort : defaultReasoningEffort;
        if (effectiveReasoningEffort != null) {
            parameters.put(REASONING_EFFORT_KEY, effectiveReasoningEffort);
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
                ResourceDescriptor.Builder.newBuilder(OpenAICompletionsSetup.class.getName())
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
