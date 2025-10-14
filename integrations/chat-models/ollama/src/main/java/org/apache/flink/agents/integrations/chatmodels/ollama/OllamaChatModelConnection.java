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
import io.github.ollama4j.OllamaAPI;
import io.github.ollama4j.exceptions.RoleNotFoundException;
import io.github.ollama4j.models.chat.OllamaChatMessage;
import io.github.ollama4j.models.chat.OllamaChatMessageRole;
import io.github.ollama4j.models.chat.OllamaChatResult;
import io.github.ollama4j.tools.Tools;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolParameters;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final OllamaAPI client;
    private final Pattern pattern;

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
        this.client = new OllamaAPI(endpoint);
        Integer maxChatToolCallRetries = descriptor.getArgument("maxChatToolCallRetries");
        this.client.setMaxChatToolCallRetries(
                maxChatToolCallRetries != null ? maxChatToolCallRetries : 10);
        Integer requestTimeout = descriptor.getArgument("requestTimeout");
        this.client.setRequestTimeoutSeconds(requestTimeout != null ? requestTimeout : 10);
        this.pattern = Pattern.compile("<think>(.*?)</think>", Pattern.DOTALL);
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
     * Registers tools with the Ollama client based on tool resource names.
     *
     * <p>Each tool's input schema is expected to be a JSON schema containing "properties" and
     * "required" keys. The schema is converted into the function/tool specification that Ollama
     * understands, and a callable is wired to invoke the underlying BaseTool with ToolParameters.
     *
     * @param tools tools to be registered to the client
     * @throws RuntimeException if schema parsing or registration fails
     */
    @SuppressWarnings("unchecked")
    private void registerTools(List<Tool> tools) {
        final ObjectMapper mapper = new ObjectMapper();
        try {
            for (Tool tool : tools) {
                final Map<String, Object> schema =
                        mapper.readValue(
                                tool.getMetadata().getInputSchema(), new TypeReference<>() {});

                final Map<String, Map<String, String>> properties =
                        (Map<String, Map<String, String>>) schema.get("properties");
                final List<String> required = (List<String>) schema.get("required");

                Map<String, Tools.PromptFuncDefinition.Property> propertiesMap = new HashMap<>();

                for (Map.Entry<String, Map<String, String>> entry : properties.entrySet()) {
                    final String paramName = entry.getKey();
                    final Map<String, String> paramSchema = entry.getValue();
                    final String type = paramSchema.get("type");
                    final String description = paramSchema.get("description");

                    propertiesMap.put(
                            paramName,
                            Tools.PromptFuncDefinition.Property.builder()
                                    .type(type)
                                    .description(description)
                                    .required(required.contains(paramName))
                                    .build());
                }

                final Tools.ToolSpecification toolSpec =
                        Tools.ToolSpecification.builder()
                                .functionName(tool.getName())
                                .functionDescription(tool.getDescription())
                                .toolPrompt(
                                        Tools.PromptFuncDefinition.builder()
                                                .type("prompt")
                                                .function(
                                                        Tools.PromptFuncDefinition.PromptFuncSpec
                                                                .builder()
                                                                .name(tool.getName())
                                                                .description(tool.getDescription())
                                                                .parameters(
                                                                        Tools.PromptFuncDefinition
                                                                                .Parameters
                                                                                .builder()
                                                                                .type("object")
                                                                                .properties(
                                                                                        propertiesMap)
                                                                                .build())
                                                                .build())
                                                .build())
                                .toolFunction(arguments -> tool.call(new ToolParameters(arguments)))
                                .build();

                this.client.registerTool(toolSpec);
            }
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
            registerTools(tools);
            final List<OllamaChatMessage> ollamaChatMessages =
                    messages.stream()
                            .map(this::convertToOllamaChatMessages)
                            .collect(Collectors.toList());

            final OllamaChatResult ollamaChatResult =
                    this.client.chat((String) arguments.get("model"), ollamaChatMessages);

            return extraReasoning(ollamaChatResult.getResponse());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ChatMessage extraReasoning(String response) {
        Matcher matcher = pattern.matcher(response);
        StringBuilder reasoning = new StringBuilder();
        while (matcher.find()) {
            reasoning.append(matcher.group(1));
        }
        response = matcher.replaceAll("").strip();
        ChatMessage responseMessage = ChatMessage.assistant(response);
        Map<String, Object> extraArgs = new HashMap<>();
        extraArgs.put("reasoning", reasoning.toString().strip());
        responseMessage.setExtraArgs(extraArgs);
        return responseMessage;
    }
}
