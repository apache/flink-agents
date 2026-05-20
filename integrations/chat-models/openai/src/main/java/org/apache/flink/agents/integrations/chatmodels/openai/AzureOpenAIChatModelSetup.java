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
import java.util.Map;
import java.util.Optional;

/**
 * Setup for Azure OpenAI Chat Completions. Exposes {@code temperature}, {@code max_tokens}, {@code
 * logprobs}, {@code additional_kwargs}, plus {@code model_of_azure_deployment} for token-metrics
 * tracking. {@code top_logprobs}, {@code strict}, and {@code reasoning_effort} are intentionally
 * NOT exposed (users can pass them via {@code additional_kwargs}).
 *
 * <p>{@code model} (inherited from {@link BaseChatModelSetup}) is the Azure deployment name, not
 * the underlying OpenAI model name. {@code model_of_azure_deployment} is the underlying model name
 * and is used solely for token metrics.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * @ChatModelSetup
 * public static ResourceDescriptor azureOpenAI() {
 *   return ResourceDescriptor.Builder.newBuilder(AzureOpenAIChatModelSetup.class.getName())
 *           .addInitialArgument("connection", "myAzureOpenAIConnection")
 *           .addInitialArgument("model", "my-gpt4o-deployment")
 *           .addInitialArgument("model_of_azure_deployment", "gpt-4o")
 *           .addInitialArgument("temperature", 0.3d)
 *           .addInitialArgument("max_tokens", 500)
 *           .build();
 * }
 * }</pre>
 */
public class AzureOpenAIChatModelSetup extends BaseChatModelSetup {

    private final String modelOfAzureDeployment;
    private final Double temperature;
    private final Integer maxTokens;
    private final Boolean logprobs;
    private final Map<String, Object> additionalKwargs;

    public AzureOpenAIChatModelSetup(
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);

        this.modelOfAzureDeployment = descriptor.getArgument("model_of_azure_deployment");

        this.temperature =
                Optional.ofNullable(descriptor.<Number>getArgument("temperature"))
                        .map(Number::doubleValue)
                        .orElse(null);
        if (this.temperature != null && (this.temperature < 0.0 || this.temperature > 2.0)) {
            throw new IllegalArgumentException("temperature must be between 0.0 and 2.0");
        }

        this.maxTokens =
                Optional.ofNullable(descriptor.<Number>getArgument("max_tokens"))
                        .map(Number::intValue)
                        .orElse(null);
        if (this.maxTokens != null && this.maxTokens <= 0) {
            throw new IllegalArgumentException("max_tokens must be greater than 0");
        }

        this.logprobs =
                Optional.ofNullable(descriptor.<Boolean>getArgument("logprobs")).orElse(false);

        Map<String, Object> additional =
                Optional.ofNullable(
                                descriptor.<Map<String, Object>>getArgument("additional_kwargs"))
                        .map(HashMap::new)
                        .orElseGet(HashMap::new);
        this.additionalKwargs = additional;
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> params = new HashMap<>();
        if (model != null) {
            params.put("model", model);
        }
        if (modelOfAzureDeployment != null) {
            params.put("model_of_azure_deployment", modelOfAzureDeployment);
        }
        params.put("logprobs", logprobs);
        if (temperature != null) {
            params.put("temperature", temperature);
        }
        if (maxTokens != null) {
            params.put("max_tokens", maxTokens);
        }
        if (additionalKwargs != null && !additionalKwargs.isEmpty()) {
            params.putAll(additionalKwargs);
        }
        return params;
    }
}
