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
package org.apache.flink.agents.integrations.chatmodels.watsonx;

import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;

import java.util.HashMap;
import java.util.Map;

/** Chat model setup for IBM watsonx.ai. */
public class WatsonxChatModelSetup extends BaseChatModelSetup {

    public static final String DEFAULT_MODEL = "ibm/granite-4-h-small";
    public static final double DEFAULT_TEMPERATURE = 0.1;

    private final Double temperature;
    private final Integer maxTokens;
    private final boolean extractReasoning;
    private final Map<String, Object> additionalKwargs;

    public WatsonxChatModelSetup(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        if (this.model == null || this.model.isEmpty()) {
            this.model = DEFAULT_MODEL;
        }
        Number temperature = descriptor.getArgument("temperature");
        this.temperature = temperature != null ? temperature.doubleValue() : DEFAULT_TEMPERATURE;
        if (!Double.isFinite(this.temperature)
                || this.temperature < 0.0
                || this.temperature > 2.0) {
            throw new IllegalArgumentException(
                    "temperature must be a finite number between 0.0 and 2.0");
        }
        Number maxTokens = descriptor.getArgument("max_tokens");
        this.maxTokens =
                maxTokens != null
                        ? WatsonxChatModelConnection.requireInteger(maxTokens, "max_tokens", 1)
                        : null;
        this.extractReasoning = Boolean.TRUE.equals(descriptor.getArgument("extract_reasoning"));
        Map<String, Object> additionalKwargs = descriptor.getArgument("additional_kwargs");
        this.additionalKwargs = additionalKwargs != null ? additionalKwargs : Map.of();
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("model", model);
        params.put("temperature", temperature);
        params.put("extract_reasoning", extractReasoning);
        if (maxTokens != null) {
            params.put("max_tokens", maxTokens);
        }
        if (!additionalKwargs.isEmpty()) {
            params.put("additional_kwargs", additionalKwargs);
        }
        return params;
    }
}
