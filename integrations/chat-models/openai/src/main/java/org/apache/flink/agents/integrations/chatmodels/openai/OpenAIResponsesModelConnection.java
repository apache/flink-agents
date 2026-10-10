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

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.TokenUsage;
import org.apache.flink.agents.api.chat.messages.UnsupportedContentBlockException;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A <b>dedicated</b> OpenAI chat model integration using the Responses API.
 *
 * <p>Unlike {@link OpenAICompletionsConnection} which uses the Chat Completions API and works with
 * any OpenAI-compatible provider (DeepSeek, DashScope, etc.), this implementation uses OpenAI's
 * Responses API which is specific to OpenAI.
 *
 * <p>For OpenAI-compatible providers that only support the Chat Completions API, use {@link
 * OpenAICompletionsConnection} instead.
 *
 * <p>Supported connection parameters:
 *
 * <ul>
 *   <li><b>api_key</b> (required): OpenAI API key
 *   <li><b>api_base_url</b> (optional): Base URL for OpenAI API (useful for proxies)
 *   <li><b>timeout</b> (optional): Timeout in seconds for API requests; must be non-negative
 *       (default: 60)
 *   <li><b>max_retries</b> (optional): Maximum number of retry attempts; must be non-negative
 *       (default: 3)
 *   <li><b>default_headers</b> (optional): Map of default headers to include in all requests
 *   <li><b>model</b> (optional): Default model to use if not specified in setup
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyAgent extends Agent {
 *   @ChatModelConnection
 *   public static ResourceDesc openAIResponses() {
 *     return ResourceDescriptor.Builder.newBuilder(OpenAIResponsesModelConnection.class.getName())
 *             .addInitialArgument("api_key", System.getenv("OPENAI_API_KEY"))
 *             .addInitialArgument("timeout", 120)
 *             .addInitialArgument("max_retries", 3)
 *             .build();
 *   }
 * }
 * }</pre>
 */
public class OpenAIResponsesModelConnection extends BaseChatModelConnection {

    private final OpenAIClient client;
    private final String defaultModel;
    private final Duration timeout;
    private final int maxRetries;

    public OpenAIResponsesModelConnection(
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);

        String apiKey = descriptor.getArgument("api_key");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("api_key should not be null or empty.");
        }

        OpenAIOkHttpClient.Builder builder = new OpenAIOkHttpClient.Builder().apiKey(apiKey);

        String apiBaseUrl = descriptor.getArgument("api_base_url");
        if (apiBaseUrl != null && !apiBaseUrl.isBlank()) {
            builder.baseUrl(apiBaseUrl);
        }

        this.timeout = OpenAIChatCompletionsUtils.parseTimeout(descriptor);
        builder.timeout(OpenAIChatCompletionsUtils.toSdkTimeout(this.timeout));

        this.maxRetries = OpenAIChatCompletionsUtils.parseMaxRetries(descriptor);
        builder.maxRetries(this.maxRetries);

        Map<String, String> defaultHeaders = descriptor.getArgument("default_headers");
        if (defaultHeaders != null && !defaultHeaders.isEmpty()) {
            for (Map.Entry<String, String> header : defaultHeaders.entrySet()) {
                builder.putHeader(header.getKey(), header.getValue());
            }
        }

        this.defaultModel = descriptor.getArgument("model");
        this.client = builder.build();
    }

    @Override
    public ChatResult chat(
            List<ChatMessage> messages,
            List<org.apache.flink.agents.api.tools.Tool> tools,
            Map<String, Object> modelParams) {
        // Media blocks are not sent yet; fail rather than drop them (#1059).
        UnsupportedContentBlockException.rejectMedia("OpenAI Responses", messages);
        ResponseCreateParams params = buildRequest(messages, tools, modelParams);
        Response response = client.responses().create(params);
        ChatMessage result = OpenAIResponsesUtils.convertResponse(response);

        String modelName = modelParams != null ? (String) modelParams.get("model") : null;
        if (modelName == null || modelName.isBlank()) {
            modelName = this.defaultModel;
        }
        return new ChatResult(
                result,
                modelName,
                response.id(),
                response.usage()
                        .map(usage -> new TokenUsage(usage.inputTokens(), usage.outputTokens()))
                        .orElse(null),
                response.incompleteDetails()
                        .flatMap(details -> details.reason())
                        .map(
                                reason ->
                                        "max_output_tokens".equals(reason.asString())
                                                ? "length"
                                                : reason.asString())
                        .orElse(null),
                null);
    }

    private ResponseCreateParams buildRequest(
            List<ChatMessage> messages,
            List<org.apache.flink.agents.api.tools.Tool> tools,
            Map<String, Object> rawModelParams) {
        Map<String, Object> modelParams =
                rawModelParams != null ? new HashMap<>(rawModelParams) : new HashMap<>();

        boolean strictMode = Boolean.TRUE.equals(modelParams.remove("strict"));
        String modelName = (String) modelParams.remove("model");
        if (modelName == null || modelName.isBlank()) {
            modelName = this.defaultModel;
        }

        List<ResponseInputItem> inputItems = OpenAIResponsesUtils.convertInputItems(messages);

        ResponseCreateParams.Builder builder =
                ResponseCreateParams.builder()
                        .model(ChatModel.of(modelName))
                        .inputOfResponse(inputItems);

        if (tools != null && !tools.isEmpty()) {
            builder.tools(OpenAIResponsesUtils.convertTools(tools, strictMode));
        }

        Object temperature = modelParams.remove("temperature");
        if (temperature instanceof Number) {
            builder.temperature(((Number) temperature).doubleValue());
        }

        Object maxTokens = modelParams.remove("max_tokens");
        if (maxTokens instanceof Number) {
            builder.maxOutputTokens(((Number) maxTokens).longValue());
        }

        Object reasoningEffort = modelParams.remove("reasoning_effort");
        if (reasoningEffort instanceof String) {
            builder.reasoning(
                    Reasoning.builder()
                            .effort(ReasoningEffort.of((String) reasoningEffort))
                            .build());
        }

        Object store = modelParams.remove("store");
        if (Boolean.TRUE.equals(store)) {
            builder.store(true);
        }

        Object instructions = modelParams.remove("instructions");
        if (instructions instanceof String) {
            builder.instructions((String) instructions);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> additionalKwargs =
                (Map<String, Object>) modelParams.remove("additional_kwargs");
        if (additionalKwargs != null) {
            additionalKwargs.forEach(
                    (key, value) ->
                            builder.putAdditionalBodyProperty(
                                    key, OpenAIResponsesUtils.toJsonValue(value)));
        }

        return builder.build();
    }

    Duration getTimeout() {
        return timeout;
    }

    int getMaxRetries() {
        return maxRetries;
    }

    @Override
    public void close() throws Exception {
        this.client.close();
    }
}
