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
package org.apache.flink.agents.integrations.chatmodels.azureai;

import com.azure.ai.inference.ChatCompletionsClient;
import com.azure.ai.inference.ChatCompletionsClientBuilder;
import com.azure.ai.inference.models.*;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.util.BinaryData;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.shaded.gson.Gson;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;

import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * A chat model integration for Azure AI Chat Completions service.
 *
 * <p>This implementation adapts the generic Flink Agents chat model interface to the Azure AI Chat
 * Completions API.
 *
 * <p>See also {@link BaseChatModelConnection} for the common resource abstractions and lifecycle.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyAgent extends Agent {
 *   // Register the chat model connection via @ChatModelConnection metadata.
 *   @ChatModelConnection
 *   public static ResourceDesc azureAI() {
 *     return ResourceDescriptor.Builder.newBuilder(AzureAIChatModelConnection.class.getName())
 *        .addInitialArgument("endpoint", "<your-azure-ai-endpoint>")
 *        .addInitialArgument("apiKey", "<your-azure-ai-api-key>")
 *        .build();
 *   }
 * }
 * }</pre>
 */
public class AzureAIChatModelConnection extends BaseChatModelConnection {

    private final Gson gson = new Gson();

    private final ChatCompletionsClient client;

    /**
     * Creates a new AzureAI chat model connection.
     *
     * @param descriptor a resource descriptor contains the initial parameters
     * @param getResource a function to resolve resources (e.g., tools) by name and type
     * @throws IllegalArgumentException if endpoint is null or empty
     */
    public AzureAIChatModelConnection(
            ResourceDescriptor descriptor, BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);

        String endpoint = descriptor.getArgument("endpoint");
        String apiKey = descriptor.getArgument("apiKey");
        if (endpoint == null || endpoint.isEmpty()) {
            throw new IllegalArgumentException("endpoint should not be null or empty.");
        }
        this.client =
                new ChatCompletionsClientBuilder()
                        .credential(new AzureKeyCredential(apiKey))
                        .endpoint(endpoint)
                        .buildClient();
    }

    private List<ChatCompletionsToolDefinition> convertToAzureAITools(List<Tool> tools) {
        final ObjectMapper mapper = new ObjectMapper();
        final List<ChatCompletionsToolDefinition> azureAITools = new ArrayList<>();
        try {
            for (Tool tool : tools) {
                final Map<String, Object> schema =
                        mapper.readValue(
                                tool.getMetadata().getInputSchema(), new TypeReference<>() {});

                final FunctionDefinition functionDef =
                        new FunctionDefinition(tool.getName())
                                .setDescription(tool.getDescription())
                                .setParameters(BinaryData.fromObject(schema));

                azureAITools.add(new ChatCompletionsFunctionToolDefinition(functionDef));
            }
            return azureAITools;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ChatRequestMessage convertToChatRequestMessage(ChatMessage message) {
        final String content = message.getContent();
        final MessageRole role = message.getRole();
        final List<Map<String, Object>> toolCalls = message.getToolCalls();
        final Map<String, Object> extraArgs = message.getExtraArgs();
        switch (role) {
            case SYSTEM:
                return new ChatRequestSystemMessage(content);
            case USER:
                return new ChatRequestUserMessage(content);
            case ASSISTANT:
                final List<ChatCompletionsToolCall> azureToolCalls =
                        toolCalls != null
                                ? transformToAzureToolCalls(toolCalls)
                                : Collections.emptyList();
                return new ChatRequestAssistantMessage(content).setToolCalls(azureToolCalls);
            case TOOL:
                String toolCallId =
                        extraArgs != null && extraArgs.containsKey("externalId")
                                ? extraArgs.get("externalId").toString()
                                : null;
                return new ChatRequestToolMessage(toolCallId).setContent(content);
            default:
                throw new IllegalArgumentException("Unsupported role: " + role);
        }
    }

    // the structure of toolCalls should be like the returned value of Method:convertToAgentsTools
    private List<ChatCompletionsToolCall> transformToAzureToolCalls(
            List<Map<String, Object>> toolCalls) {
        final List<ChatCompletionsToolCall> azureToolCalls = new ArrayList<>();
        for (Map<String, Object> call : toolCalls) {
            final String id = (String) call.get("id");
            final String type = (String) call.get("type");

            if ("function".equals(type)) {
                final Map<String, Object> functionCall = (Map<String, Object>) call.get("function");
                final String functionName = (String) functionCall.get("name");
                final Map<String, Object> functionArguments =
                        (Map<String, Object>) functionCall.get("arguments");
                final String functionArgumentsJson = gson.toJson(functionArguments);
                ChatCompletionsFunctionToolCall function =
                        new ChatCompletionsFunctionToolCall(
                                id, new FunctionCall(functionName, functionArgumentsJson));
                azureToolCalls.add(function);
            }
        }
        return azureToolCalls;
    }

    @Override
    public ChatMessage chat(
            List<ChatMessage> messages, List<Tool> tools, Map<String, Object> arguments) {
        try {
            final List<ChatCompletionsToolDefinition> azureTools = convertToAzureAITools(tools);
            final List<ChatRequestMessage> chatMessages =
                    messages.stream()
                            .map(this::convertToChatRequestMessage)
                            .collect(Collectors.toList());

            final String modelName = (String) arguments.get("model");
            ChatCompletionsOptions options =
                    new ChatCompletionsOptions(chatMessages)
                            .setModel(modelName)
                            .setTools(azureTools);

            ChatCompletions completions = client.complete(options);
            ChatChoice choice = completions.getChoices().get(0);
            ChatResponseMessage responseMessage = choice.getMessage();

            ChatMessage chatMessage = ChatMessage.assistant(responseMessage.getContent());

            List<ChatCompletionsToolCall> toolCalls = responseMessage.getToolCalls();
            if (toolCalls != null && !toolCalls.isEmpty()) {
                List<Map<String, Object>> convertedToolCalls = convertToAgentsTools(toolCalls);
                chatMessage.setToolCalls(convertedToolCalls);
            }

            // Record token metrics if model name is available
            if (modelName != null && !modelName.isBlank()) {
                CompletionsUsage usage = completions.getUsage();
                if (usage != null) {
                    recordTokenMetrics(
                            modelName, usage.getPromptTokens(), usage.getCompletionTokens());
                }
            }

            return chatMessage;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<Map<String, Object>> convertToAgentsTools(
            List<ChatCompletionsToolCall> azureToolCalls) {
        final List<Map<String, Object>> toolCalls = new ArrayList<>(azureToolCalls.size());
        for (ChatCompletionsToolCall toolCall : azureToolCalls) {
            if (toolCall != null) {
                final Map<String, Object> call =
                        Map.of(
                                // todo: I don't think the magic name is a good idea here, need to
                                // unify later (maybe we can consider standardizing tool call
                                // structure across different LLM integrations)
                                "id", toolCall.getId(),
                                "original_id", toolCall.getId(),
                                "type", toolCall.getType(),
                                "function",
                                        Map.of(
                                                "name", toolCall.getFunction().getName(),
                                                "arguments",
                                                        gson.fromJson(
                                                                toolCall.getFunction()
                                                                        .getArguments(),
                                                                Map.class)));
                toolCalls.add(call);
            }
        }
        return toolCalls;
    }
}
