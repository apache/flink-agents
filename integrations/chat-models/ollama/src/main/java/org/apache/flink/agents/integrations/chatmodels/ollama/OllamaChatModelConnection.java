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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.generator.impl.PropertySortUtils;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import io.github.ollama4j.exceptions.RoleNotFoundException;
import io.github.ollama4j.models.chat.*;
import io.github.ollama4j.models.request.OllamaChatEndpointCaller;
import io.github.ollama4j.models.request.ThinkMode;
import io.github.ollama4j.tools.Tools;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.tools.Tool;

import java.util.*;
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
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
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
    public OllamaChatModelConnection(String endpoint, ResourceContext resourceContext) {
        this(
                new ResourceDescriptor(
                        OllamaChatModelConnection.class.getName(), Map.of("endpoint", endpoint)),
                resourceContext);
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
    // Package-visible for unit testing of the schema conversion.
    @SuppressWarnings("unchecked")
    List<Tools.Tool> convertToOllamaTools(List<Tool> tools) {
        final ObjectMapper mapper = new ObjectMapper();
        final List<Tools.Tool> ollamaTools = new ArrayList<>();
        try {
            for (Tool tool : tools) {
                final Map<String, Object> schema =
                        mapper.readValue(
                                tool.getMetadata().getInputSchema(), new TypeReference<>() {});

                final Map<String, Map<String, String>> properties =
                        (Map<String, Map<String, String>>) schema.get("properties");
                // "required" is optional in JSON Schema, and SchemaUtils only emits it when at
                // least one parameter is required — treat a missing list as empty (#1014).
                final List<String> required =
                        (List<String>) schema.getOrDefault("required", Collections.emptyList());

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
            return new OllamaChatMessage(ollamaRole, message.getText());
        } catch (RoleNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Whether Ollama can constrain generation to a schema for {@code effectiveModel}.
     *
     * <p>Always {@code true}, and deliberately independent of the argument: schema-constrained
     * decoding is applied by the Ollama server's sampler rather than by the model, so it holds for
     * every model served by a server at or above v0.5.0. There is also no model-level signal to key
     * on. Ollama's model capability set — completion, tools, insert, vision, embedding, thinking,
     * image, audio — carries nothing schema-related, {@code /api/show} reports exactly that set,
     * and {@code /api/version} reports only a version string. Since a server runs arbitrary local
     * models, any allowlist would be invented, and would report not-capable for models that do
     * work.
     *
     * <p>Three deployments break the guarantee, none of them distinguishable from a model name: a
     * server below v0.5.0 rejects the {@code format} field with HTTP 400; Ollama Cloud accepts the
     * request but does not enforce the schema; and the MLX runner accepts the field and drops it.
     *
     * <p>Reads no instance state, so capability stays answerable independently of how the
     * connection was configured.
     */
    @Override
    protected boolean supportsNativeStructuredOutput(String effectiveModel) {
        return true;
    }

    @Override
    public ChatMessage chat(
            List<ChatMessage> messages, List<Tool> tools, Map<String, Object> modelParams) {
        return doChat(messages, tools, modelParams, null);
    }

    /**
     * Translates {@code outputSchema} into Ollama's native {@code format} field when it is a POJO
     * {@link Class}. Any other schema form — notably a {@code RowTypeInfo} wrapped in {@code
     * OutputSchema} — has no native translation here and leaves the request unconstrained, so that
     * the prompt-engineering fallback still governs the response.
     */
    @Override
    public ChatMessage chat(
            List<ChatMessage> messages,
            List<Tool> tools,
            Map<String, Object> modelParams,
            Object outputSchema) {
        return doChat(messages, tools, modelParams, outputSchema);
    }

    private ChatMessage doChat(
            List<ChatMessage> messages,
            List<Tool> tools,
            Map<String, Object> modelParams,
            Object outputSchema) {
        try {
            final boolean extractReasoning =
                    (boolean) modelParams.getOrDefault("extract_reasoning", true);

            final OllamaChatRequest chatRequest =
                    buildRequest(messages, tools, modelParams, outputSchema);
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

            // Stash token usage if model name is available
            final String modelName = (String) modelParams.get("model");
            if (modelName != null && !modelName.isBlank()) {
                Integer promptTokens = ollamaChatResponse.getPromptEvalCount();
                Integer completionTokens = ollamaChatResponse.getEvalCount();
                if (promptTokens != null && completionTokens != null) {
                    extraArgs.put("model_name", modelName);
                    extraArgs.put("promptTokens", promptTokens.longValue());
                    extraArgs.put("completionTokens", completionTokens.longValue());
                }
            }

            return chatMessage;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Package-private so the request body (including the native format) can be asserted without
    // issuing a live call through the Ollama endpoint caller.
    OllamaChatRequest buildRequest(
            List<ChatMessage> messages,
            List<Tool> tools,
            Map<String, Object> modelParams,
            Object outputSchema) {
        // convert think to think mode.
        final Object think = modelParams.getOrDefault("think", true);
        ThinkMode thinkMode = ThinkMode.ENABLED;
        for (ThinkMode mode : ThinkMode.values()) {
            if (mode.getValue().equals(think)) {
                thinkMode = mode;
                break;
            }
        }

        final List<Tools.Tool> ollamaTools = this.convertToOllamaTools(tools);
        final List<OllamaChatMessage> ollamaChatMessages =
                messages.stream()
                        .map(this::convertToOllamaChatMessages)
                        .collect(Collectors.toList());

        final String modelName = (String) modelParams.get("model");
        final OllamaChatRequest chatRequest =
                OllamaChatRequest.builder()
                        .withMessages(ollamaChatMessages)
                        .withModel(modelName)
                        .withThinking(thinkMode)
                        .withUseTools(false)
                        .build();

        chatRequest.setTools(ollamaTools);

        // Native structured output applies only for a POJO Class schema; any other schema form,
        // such as a RowTypeInfo wrapped in OutputSchema, keeps the prompt-engineering fallback.
        // The schema is a request field of its own rather than a sampling option, so it is set as
        // the request's format, which is left unset when no native translation applies and is then
        // omitted from the serialized body rather than serialized as null.
        //
        // TODO(#912): the requested strategy is not visible here, so this re-check cannot tell an
        // explicit NATIVE request apart from one that merely resolved to native. A caller asking
        // for NATIVE on a schema form this branch skips therefore gets an unconstrained response
        // instead of an error. Once strategy resolution is wired up, NATIVE must either bypass
        // this capability re-check or fail explicitly.
        if (outputSchema instanceof Class && supportsNativeStructuredOutput(modelName)) {
            chatRequest.setFormat(toNativeFormat((Class<?>) outputSchema));
        }

        return chatRequest;
    }

    // Derives the JSON schema Ollama's format field expects from a POJO class. Every setting below
    // addresses a concrete way the generated schema otherwise fails to constrain generation:
    //
    //   - DRAFT_2020_12 is the draft pydantic generates on the Python side, so a schema derived
    //     from a Java class states the same contract in the same dialect.
    //   - The PLAIN_JSON preset keeps generation to fields. Without a preset, getters surface as
    //     properties of their own, named after the accessor call, e.g. "getSummary()".
    //   - MAP_VALUES_AS_ADDITIONAL_PROPERTIES gives a Map its value schema. Without it the map
    //     admits any value, and a model does emit values that the declared value type then fails
    //     to deserialize.
    //   - Sorting fields before methods and applying no further comparison leaves properties in
    //     declaration order. Ollama's grammar fixes generation order to the order the schema
    //     declares its properties, so the default alphabetical order would condition generation on
    //     an order the class does not read in.
    //   - The required check marks every field required except an Optional one. The default marks
    //     nothing required, which lets a model omit fields at will, while marking everything
    //     required would force the fields a caller declared omissible.
    //   - The Jackson module makes the schema name properties the way Jackson names them. The
    //     response is read back into the same class with an ObjectMapper, so a property that
    //     @JsonProperty renames or @JsonIgnore drops has to be stated in the schema under the name
    //     the mapper reads, or a response that satisfies the schema still fails to deserialize.
    //     It is applied with no JacksonOption, so it contributes property naming and visibility
    //     only: the required set and the property order stay the ones configured below.
    //
    // Two settings are deliberately absent:
    //
    //   - FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT gains nothing: Ollama's grammar already
    //     refuses a key the schema does not declare, even one a prompt explicitly asks for, and
    //     only an explicit additionalProperties: true admits one.
    //   - DEFINITION_FOR_MAIN_SCHEMA lets a recursive type generate a schema, but when the
    //     document root is a $ref and one $defs entry references another, the server drops the
    //     grammar and returns a free-form object. Any nested type used twice is extracted into
    //     $defs, so enabling it would silently unconstrain a common shape to rescue a rare one. A
    //     recursive type instead fails loudly, with HTTP 400 from the server.
    private static ObjectNode toNativeFormat(Class<?> schemaClass) {
        SchemaGeneratorConfigBuilder configBuilder =
                new SchemaGeneratorConfigBuilder(
                                SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                        .with(Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES)
                        .with(new JacksonModule());
        configBuilder
                .forTypesInGeneral()
                .withPropertySorter(PropertySortUtils.SORT_PROPERTIES_FIELDS_BEFORE_METHODS);
        configBuilder
                .forFields()
                .withRequiredCheck(field -> !Optional.class.equals(field.getRawMember().getType()));
        return new SchemaGenerator(configBuilder.build()).generateSchema(schemaClass);
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
