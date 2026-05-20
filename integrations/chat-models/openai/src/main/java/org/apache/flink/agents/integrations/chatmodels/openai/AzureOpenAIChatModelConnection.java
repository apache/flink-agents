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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.azure.AzureOpenAIServiceVersion;
import com.openai.azure.AzureUrlPathMode;
import com.openai.azure.credential.AzureApiKeyCredential;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.ChatModel;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionTool;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat model integration for Azure OpenAI Service. Built on the openai-java SDK using its built-in
 * Azure support ({@link AzureOpenAIServiceVersion}, {@link AzureApiKeyCredential}).
 *
 * <p>Required connection arguments:
 *
 * <ul>
 *   <li><b>api_key</b>: Azure OpenAI API key
 *   <li><b>api_version</b>: Azure OpenAI REST API version (e.g., {@code "2024-02-01"})
 *   <li><b>azure_endpoint</b>: base URL for the Azure OpenAI deployment — either a direct Azure
 *       resource (e.g., {@code "https://your-resource.openai.azure.com"}) or a proxy/gateway URL
 *       that fronts an Azure OpenAI service. Custom gateway hostnames also require setting {@code
 *       azure_url_path_mode} below.
 * </ul>
 *
 * <p>Optional connection arguments:
 *
 * <ul>
 *   <li><b>timeout</b> (Number): seconds before an API call times out (default 60)
 *   <li><b>max_retries</b> (Number): retry attempts on failure (default 3)
 *   <li><b>azure_url_path_mode</b> (String): one of {@code "AUTO"}, {@code "LEGACY"}, or {@code
 *       "UNIFIED"} (default {@code "AUTO"}). Controls how the SDK constructs Azure OpenAI request
 *       URLs. In {@code AUTO} mode the SDK only treats the endpoint as Azure when its hostname
 *       matches a known suffix (e.g. {@code .openai.azure.com}); custom gateways that proxy Azure
 *       OpenAI need {@code LEGACY} to force the {@code /openai/deployments/{model}} path.
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * @ChatModelConnection
 * public static ResourceDescriptor azureOpenAI() {
 *   return ResourceDescriptor.Builder.newBuilder(
 *               AzureOpenAIChatModelConnection.class.getName())
 *           .addInitialArgument("api_key", System.getenv("AZURE_OPENAI_API_KEY"))
 *           .addInitialArgument("api_version", "2024-02-01")
 *           .addInitialArgument("azure_endpoint", "https://my-resource.openai.azure.com")
 *           .build();
 * }
 * }</pre>
 */
public class AzureOpenAIChatModelConnection extends BaseChatModelConnection {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final OpenAIClient client;

    public AzureOpenAIChatModelConnection(
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);

        String apiKey = descriptor.getArgument("api_key");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("api_key should not be null or empty.");
        }

        String apiVersion = descriptor.getArgument("api_version");
        if (apiVersion == null || apiVersion.isBlank()) {
            throw new IllegalArgumentException("api_version should not be null or empty.");
        }

        String azureEndpoint = descriptor.getArgument("azure_endpoint");
        if (azureEndpoint == null || azureEndpoint.isBlank()) {
            throw new IllegalArgumentException("azure_endpoint should not be null or empty.");
        }

        Integer timeoutSeconds = descriptor.getArgument("timeout");
        if (timeoutSeconds == null) {
            timeoutSeconds = 60;
        }

        Integer maxRetries = descriptor.getArgument("max_retries");
        if (maxRetries == null) {
            maxRetries = 3;
        }

        OpenAIOkHttpClient.Builder clientBuilder =
                OpenAIOkHttpClient.builder()
                        .baseUrl(azureEndpoint)
                        .credential(AzureApiKeyCredential.create(apiKey))
                        .azureServiceVersion(AzureOpenAIServiceVersion.fromString(apiVersion))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .maxRetries(maxRetries);

        String azureUrlPathMode = descriptor.getArgument("azure_url_path_mode");
        if (azureUrlPathMode != null && !azureUrlPathMode.isBlank()) {
            try {
                clientBuilder.azureUrlPathMode(
                        AzureUrlPathMode.valueOf(azureUrlPathMode.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "azure_url_path_mode must be one of AUTO, LEGACY, or UNIFIED; got: "
                                + azureUrlPathMode,
                        e);
            }
        }

        this.client = clientBuilder.build();
    }

    @Override
    public ChatMessage chat(
            List<ChatMessage> messages, List<Tool> tools, Map<String, Object> arguments) {
        try {
            Map<String, Object> mutableArgs =
                    arguments != null ? new HashMap<>(arguments) : new HashMap<>();

            String azureDeployment = (String) mutableArgs.remove("model");
            if (azureDeployment == null || azureDeployment.isBlank()) {
                throw new IllegalArgumentException("model is required for Azure OpenAI API calls");
            }
            String modelOfAzureDeployment =
                    (String) mutableArgs.remove("model_of_azure_deployment");

            ChatCompletionCreateParams.Builder builder =
                    ChatCompletionCreateParams.builder()
                            .model(ChatModel.of(azureDeployment))
                            .messages(OpenAIChatCompletionsUtils.convertToOpenAIMessages(messages));

            if (tools != null && !tools.isEmpty()) {
                builder.tools(convertTools(tools));
            }

            Object temperature = mutableArgs.remove("temperature");
            if (temperature instanceof Number) {
                builder.temperature(((Number) temperature).doubleValue());
            }

            Object maxTokens = mutableArgs.remove("max_tokens");
            if (maxTokens instanceof Number) {
                builder.maxCompletionTokens(((Number) maxTokens).longValue());
            }

            Object logprobs = mutableArgs.remove("logprobs");
            if (Boolean.TRUE.equals(logprobs)) {
                builder.logprobs(true);
            }

            // Pass-through: AzureOpenAIChatModelSetup flattens additional_kwargs into the top
            // level, so any remaining entries here are user-provided extras that should flow
            // through to the OpenAI request body.
            for (Map.Entry<String, Object> entry : mutableArgs.entrySet()) {
                builder.putAdditionalBodyProperty(entry.getKey(), toJsonValue(entry.getValue()));
            }

            ChatCompletion completion = client.chat().completions().create(builder.build());

            ChatMessage response =
                    OpenAIChatCompletionsUtils.convertFromOpenAIMessage(
                            completion.choices().get(0).message(), Map.of());

            if (modelOfAzureDeployment != null
                    && !modelOfAzureDeployment.isBlank()
                    && completion.usage().isPresent()) {
                recordTokenMetrics(
                        modelOfAzureDeployment,
                        completion.usage().get().promptTokens(),
                        completion.usage().get().completionTokens());
            }

            return response;
        } catch (Exception e) {
            throw new RuntimeException("Failed to call Azure OpenAI chat completions API.", e);
        }
    }

    @Override
    public void close() throws Exception {
        this.client.close();
    }

    // ----- Inlined tool-definition helpers (mirror OpenAICompletionsConnection per the
    // "inline copies" decision in the design spec) -----

    private List<ChatCompletionTool> convertTools(List<Tool> tools) {
        List<ChatCompletionTool> openaiTools = new ArrayList<>(tools.size());
        for (Tool tool : tools) {
            ToolMetadata metadata = tool.getMetadata();
            FunctionDefinition.Builder functionBuilder =
                    FunctionDefinition.builder()
                            .name(metadata.getName())
                            .description(metadata.getDescription());

            String schema = metadata.getInputSchema();
            if (schema != null && !schema.isBlank()) {
                functionBuilder.parameters(parseFunctionParameters(schema));
            }

            ChatCompletionFunctionTool functionTool =
                    ChatCompletionFunctionTool.builder()
                            .function(functionBuilder.build())
                            .type(JsonValue.from("function"))
                            .build();

            openaiTools.add(ChatCompletionTool.ofFunction(functionTool));
        }
        return openaiTools;
    }

    private FunctionParameters parseFunctionParameters(String schemaJson) {
        try {
            JsonNode root = mapper.readTree(schemaJson);
            if (root == null || !root.isObject()) {
                return FunctionParameters.builder().build();
            }
            FunctionParameters.Builder builder = FunctionParameters.builder();
            root.fields()
                    .forEachRemaining(
                            entry ->
                                    builder.putAdditionalProperty(
                                            entry.getKey(),
                                            JsonValue.fromJsonNode(entry.getValue())));
            return builder.build();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse tool schema JSON.", e);
        }
    }

    private JsonValue toJsonValue(Object value) {
        if (value instanceof JsonValue) {
            return (JsonValue) value;
        }
        if (value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value == null) {
            return JsonValue.from(value);
        }
        return JsonValue.fromJsonNode(mapper.valueToTree(value));
    }
}
