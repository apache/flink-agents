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

package org.apache.flink.agents.integrations.chatmodels.ollama;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ollama4j.exceptions.RoleNotFoundException;
import io.github.ollama4j.models.chat.*;
import io.github.ollama4j.models.request.OllamaChatEndpointCaller;
import io.github.ollama4j.models.request.ThinkMode;
import io.github.ollama4j.tools.Tools;
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
 * A chat model integration for Ollama powered by the ollama4j client.
 *
 * <p>This implementation adapts the generic Flink Agents chat model interface to Ollama's
 * conversation API.
 *
 * <p>See also {@link BaseChatModelConnection} for the common resource abstractions and lifecycle.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyAgent extends Agent {
 *   // Register the chat model connection via @ChatModelConnection metadata.
 *   @ChatModelConnection
 *   public static ResourceDesc ollama() {
 *     return ResourceDescriptor.Builder.newBuilder(OllamaChatModelConnection.class.getName())
 *                 .addInitialArgument("endpoint", "http://localhost:11434") // the ollama server endpoint
 *                 .build();
 *   }
 * }
 * }</pre>
 */
public class OllamaChatModelConnection extends BaseChatModelConnection {

    private final OllamaChatEndpointCaller caller;

    /**
     * Creates a new ollama chat model connection.
     *
     * @param descriptor a resource descriptor contains the initial parameters
     * @param getResource a function to resolve resources (e.g., tools) by name and type
     * @throws IllegalArgumentException if endpoint is null or empty
     */
    public OllamaChatModelConnection(
            ResourceDescriptor descriptor, BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
        String endpoint = descriptor.getArgument("endpoint");
        if (endpoint == null || endpoint.isEmpty()) {
            throw new IllegalArgumentException("endpoint should not be null or empty.");
        }
        Integer requestTimeout = descriptor.getArgument("requestTimeout");
        this.caller =
                new OllamaChatEndpointCaller(
                        endpoint, null, requestTimeout != null ? requestTimeout : 60);
    }

    /**
     * Creates a new ollama chat model connection.
     *
     * @param endpoint the endpoint of the ollama server.
     * @param getResource a function to resolve resources (e.g., tools) by name and type
     * @throws IllegalArgumentException if endpoint is null or empty
     */
    public OllamaChatModelConnection(
            String endpoint, BiFunction<String, ResourceType, Resource> getResource) {
        this(
                new ResourceDescriptor(
                        OllamaChatModelConnection.class.getName(), Map.of("endpoint", endpoint)),
                getResource);
    }

    /**
     * Converts Flink Agent tools to Ollama compatible tool specifications.
     *
     * <p>Each tool's input schema is expected to be a JSON schema containing "properties" and
     * "required" keys. The schema is converted into the function/tool specification that Ollama
     * understands, and each tool is properly formatted for Ollama API integration.
     *
     * @param tools List of Flink Agent tools to be converted to Ollama tools
     * @return List of Ollama compatible tool specifications
     * @throws RuntimeException if schema parsing or conversion fails
     */
    @SuppressWarnings("unchecked")
    private List<Tools.Tool> convertToOllamaTools(List<Tool> tools) {
        final ObjectMapper mapper = new ObjectMapper();
        final List<Tools.Tool> ollamaTools = new ArrayList<>();
        try {
            for (Tool tool : tools) {
                final Map<String, Object> schema =
                        mapper.readValue(
                                tool.getMetadata().getInputSchema(), new TypeReference<>() {});

                final Map<String, Map<String, String>> properties =
                        (Map<String, Map<String, String>>) schema.get("properties");
                final List<String> required = (List<String>) schema.get("required");

                Map<String, Tools.Property> propertiesMap = new HashMap<>();

                for (Map.Entry<String, Map<String, String>> entry : properties.entrySet()) {
                    final String paramName = entry.getKey();
                    final Map<String, String> paramSchema = entry.getValue();
                    final String type = paramSchema.get("type");
                    final String description = paramSchema.get("description");

                    propertiesMap.put(
                            paramName,
                            Tools.Property.builder()
                                    .type(type)
                                    .description(description)
                                    .required(required.contains(paramName))
                                    .build());
                }

                final Tools.Tool toolSpec =
                        Tools.Tool.builder()
                                .toolSpec(
                                        Tools.ToolSpec.builder()
                                                .name(tool.getName())
                                                .description(tool.getDescription())
                                                .parameters(Tools.Parameters.of(propertiesMap))
                                                .build())
                                .build();
                ollamaTools.add(toolSpec);
            }

            return ollamaTools;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Convert a framework ChatMessage into an {@link OllamaChatMessage}, mapping roles accordingly.
     *
     * @param message the framework message
     * @return the corresponding Ollama message
     * @throws RuntimeException if the role cannot be mapped to an Ollama role
     */
    private OllamaChatMessage convertToOllamaChatMessages(ChatMessage message) {
        final MessageRole role = message.getRole();
        try {
            final OllamaChatMessageRole ollamaRole =
                    OllamaChatMessageRole.getRole(role.name().toLowerCase());
            return new OllamaChatMessage(ollamaRole, message.getContent());
        } catch (RoleNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ChatMessage chat(
            List<ChatMessage> messages, List<Tool> tools, Map<String, Object> arguments) {
        try {
            final boolean extractReasoning =
                    (boolean) arguments.getOrDefault("extract_reasoning", false);

            final List<Tools.Tool> ollamaTools = this.convertToOllamaTools(tools);
            final List<OllamaChatMessage> ollamaChatMessages =
                    messages.stream()
                            .map(this::convertToOllamaChatMessages)
                            .collect(Collectors.toList());

            final OllamaChatRequest chatRequest =
                    OllamaChatRequest.builder()
                            .withMessages(ollamaChatMessages)
                            .withModel((String) arguments.get("model"))
                            .withThinking(extractReasoning ? ThinkMode.ENABLED : ThinkMode.DISABLED)
                            .withUseTools(false)
                            .build();

            chatRequest.setTools(ollamaTools);
            final OllamaChatResult ollamaChatResult = this.caller.callSync(chatRequest);
            final OllamaChatResponseModel ollamaChatResponse = ollamaChatResult.getResponseModel();
            final OllamaChatMessage ollamaChatMessage = ollamaChatResponse.getMessage();

            Map<String, Object> extraArgs = new HashMap<>();
            if (extractReasoning) {
                extraArgs.put("reasoning", ollamaChatMessage.getThinking());
            }

            final List<OllamaChatToolCalls> ollamaToolCalls = ollamaChatMessage.getToolCalls();
            final ChatMessage chatMessage = ChatMessage.assistant(ollamaChatMessage.getResponse());
            chatMessage.setExtraArgs(extraArgs);

            if (ollamaToolCalls != null) {
                final List<Map<String, Object>> toolCalls = convertToAgentsTools(ollamaToolCalls);
                chatMessage.setToolCalls(toolCalls);
            }

            return chatMessage;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Converts Ollama tool calls to the format expected by the Flink Agents framework.
     *
     * <p>This method transforms Ollama-specific tool call representations into a generic format
     * that can be used by the Flink Agents framework. Each tool call is assigned a unique ID and
     * structured with the appropriate function name and arguments.
     *
     * @param ollamaToolCalls the list of tool calls returned from Ollama API
     * @return a list of tool calls formatted for Flink Agents, where each tool call is represented
     *     as a map containing id, type, and function details
     */
    private List<Map<String, Object>> convertToAgentsTools(
            List<OllamaChatToolCalls> ollamaToolCalls) {
        final List<Map<String, Object>> toolCalls = new ArrayList<>(ollamaToolCalls.size());
        for (OllamaChatToolCalls ollamaToolCall : ollamaToolCalls) {
            final UUID id = UUID.randomUUID();
            final Map<String, Object> toolCall =
                    Map.of(
                            "id",
                            id,
                            "type",
                            "function",
                            "function",
                            Map.of(
                                    "name",
                                    ollamaToolCall.getFunction().getName(),
                                    "arguments",
                                    ollamaToolCall.getFunction().getArguments()));
            toolCalls.add(toolCall);
        }
        return toolCalls;
    }
}
