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
import org.apache.flink.agents.api.chat.model.BaseChatModel;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.plan.tools.BaseTool;
import org.apache.flink.agents.plan.tools.ToolParameters;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * A chat model integration for Ollama powered by the ollama4j client.
 *
 * <p>This implementation adapts the generic Flink Agents chat model interface to Ollama's
 * conversation API.
 *
 * <p>See also {@link BaseChatModel} for the common resource abstractions and lifecycle.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyAgent extends Agent {
 *   // Register the chat model via @ChatModel metadata.
 *   @ChatModel
 *   public static Map<String, Object> ollama() {
 *     Map<String, Object> meta = new HashMap<>();
 *     meta.put(ChatModel.CHAT_MODEL_CLASS_NAME, OllamaChatModel.class.getName());
 *     meta.put(ChatModel.CHAT_MODEL_ARGUMENTS,
 *         List.of("http://localhost:11434",   // endpoint
 *                 "qwen3:4b",                  // model name
 *                 "myPrompt",                  // optional prompt resource name
 *                 List.of("myTool") // optional tools
 *         ));
 *     meta.put(ChatModel.CHAT_MODEL_ARGUMENTS_TYPES,
 *         List.of(String.class.getName(), String.class.getName(),
 *                String.class.getName(), List.class.getName()));
 *     return meta;
 *   }
 * }
 * }</pre>
 */
public class OllamaChatModel extends BaseChatModel {

    private final OllamaAPI client;
    private final String modelName;

    /**
     * Creates a new OllamaChatModel.
     *
     * @param getResource a function to resolve resources (e.g., tools) by name and type
     * @param endpoint the Ollama server endpoint (e.g., http://localhost:11434)
     * @param modelName the Ollama model name to use for chat completions
     * @param promptName optional prompt resource name to be used by higher-level orchestration
     * @param toolNames optional list of tool resource names to register as callable functions
     * @throws IllegalArgumentException if endpoint is null or empty
     */
    public OllamaChatModel(
            BiFunction<String, ResourceType, Resource> getResource,
            String endpoint,
            String modelName,
            String promptName,
            List<String> toolNames) {
        super(getResource, promptName, toolNames);
        if (endpoint == null || endpoint.isEmpty()) {
            throw new IllegalArgumentException("endpoint should not be null or empty.");
        }

        this.client = new OllamaAPI(endpoint);
        this.modelName = modelName;

        if (toolNames != null && !toolNames.isEmpty()) {
            this.registerTools(toolNames);
        }
    }

    /**
     * Registers tools with the Ollama client based on tool resource names.
     *
     * <p>Each tool's input schema is expected to be a JSON schema containing "properties" and
     * "required" keys. The schema is converted into the function/tool specification that Ollama
     * understands, and a callable is wired to invoke the underlying BaseTool with ToolParameters.
     *
     * @param toolNames names of tools to resolve via getResource and register with the client
     * @throws RuntimeException if schema parsing or registration fails
     */
    @SuppressWarnings("unchecked")
    private void registerTools(List<String> toolNames) {
        final ObjectMapper mapper = new ObjectMapper();
        try {
            for (String toolName : toolNames) {
                final BaseTool tool =
                        (BaseTool) this.getResource.apply(toolName, ResourceType.TOOL);
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
    public ChatMessage chat(List<ChatMessage> messages) {
        try {
            final List<OllamaChatMessage> ollamaChatMessages =
                    messages.stream()
                            .map(this::convertToOllamaChatMessages)
                            .collect(Collectors.toList());

            final OllamaChatResult ollamaChatResult =
                    this.client.chat(this.modelName, ollamaChatMessages);

            return ChatMessage.assistant(ollamaChatResult.getResponse());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
