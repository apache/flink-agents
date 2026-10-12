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
import io.github.ollama4j.exceptions.RoleNotFoundException;
import io.github.ollama4j.models.chat.OllamaChatMessage;
import io.github.ollama4j.models.chat.OllamaChatMessageRole;
import io.github.ollama4j.models.chat.OllamaChatRequest;
import io.github.ollama4j.models.chat.OllamaChatResponseModel;
import io.github.ollama4j.models.chat.OllamaChatResult;
import io.github.ollama4j.models.chat.OllamaChatToolCalls;
import io.github.ollama4j.models.request.OllamaChatEndpointCaller;
import io.github.ollama4j.models.request.ThinkMode;
import io.github.ollama4j.tools.OllamaToolCallsFunction;
import io.github.ollama4j.tools.Tools;
import org.apache.flink.agents.api.chat.messages.Base64Source;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.ContentBlock;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.MediaBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.ReasoningBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.TokenUsage;
import org.apache.flink.agents.api.chat.messages.ToolCallBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.apache.flink.agents.api.chat.messages.UnsupportedContentBlockException;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.chat.model.NativeStructuredOutputSupport;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.integrations.chatmodels.common.PojoJsonSchemaGenerator;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
                // "required" is optional in JSON Schema; treat a missing list as empty (#1014).
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
     * @throws UnsupportedContentBlockException if the message has media Ollama cannot take
     */
    private OllamaChatMessage convertToOllamaChatMessages(ChatMessage message) {
        final MessageRole role = message.getRole();
        try {
            final OllamaChatMessageRole ollamaRole =
                    OllamaChatMessageRole.getRole(role.name().toLowerCase());
            final List<byte[]> images = toOllamaImages(message);
            final OllamaChatMessage ollamaMessage =
                    new OllamaChatMessage(ollamaRole, ollamaText(message));
            final List<ToolCallBlock> toolCalls = message.getToolCalls();
            if (!toolCalls.isEmpty()) {
                // Without the calls, the history shows tool results the model never requested.
                ollamaMessage.setToolCalls(toOllamaToolCalls(toolCalls));
            }
            if (!images.isEmpty()) {
                ollamaMessage.setImages(images);
            }
            return ollamaMessage;
        } catch (RoleNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /** Extracts text while skipping tool calls and reasoning, and rejects unsupported media. */
    private static String ollamaText(ChatMessage message) {
        List<? extends ContentBlock> blocks =
                message.getRole() == MessageRole.TOOL
                        ? ((ToolResultBlock) message.getBlocks().get(0)).getBlocks()
                        : message.getBlocks();
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock) {
                text.append(((TextBlock) block).getText());
            } else if (block instanceof ToolCallBlock) {
                continue;
            } else if (block instanceof ReasoningBlock) {
                continue;
            } else if (block instanceof MediaBlock && message.getRole() == MessageRole.USER) {
                // Validated and attached separately by toOllamaImages.
                continue;
            } else {
                throw new IllegalArgumentException(
                        "Ollama cannot send block type " + block.getType());
            }
        }
        return text.toString();
    }

    /**
     * Converts framework tool calls back to Ollama's shape, the function name and its arguments as
     * an object, as the Python connection does. The framework-assigned id is not sent.
     */
    private static List<OllamaChatToolCalls> toOllamaToolCalls(List<ToolCallBlock> toolCalls) {
        List<OllamaChatToolCalls> calls = new ArrayList<>();
        for (ToolCallBlock call : toolCalls) {
            calls.add(
                    new OllamaChatToolCalls(
                            null, new OllamaToolCallsFunction(call.getName(), call.getInput())));
        }
        return calls;
    }

    /** Ollama expects the arguments as an object; a JSON string is parsed into one. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toArgumentsMap(Object arguments) {
        if (arguments == null) {
            return Collections.emptyMap();
        }
        if (arguments instanceof Map) {
            return (Map<String, Object>) arguments;
        }
        try {
            return new ObjectMapper()
                    .readValue(
                            String.valueOf(arguments), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Tool call arguments must be a JSON object.", e);
        }
    }

    /**
     * The images of a user message, in block order. Ollama takes inline image data only, attached
     * to the message rather than interleaved with its text; any other media fails explicitly.
     */
    private static List<byte[]> toOllamaImages(ChatMessage message) {
        final List<byte[]> images = new ArrayList<>();
        List<? extends ContentBlock> blocks =
                message.getRole() == MessageRole.TOOL
                        ? ((ToolResultBlock) message.getBlocks().get(0)).getBlocks()
                        : message.getBlocks();
        for (ContentBlock block : blocks) {
            if (!(block instanceof MediaBlock)) {
                continue;
            }
            if (message.getRole() != MessageRole.USER) {
                throw UnsupportedContentBlockException.forBlock(
                        "Ollama",
                        block,
                        "only user messages can carry media, not "
                                + message.getRole().getValue()
                                + " messages");
            }
            if (!(block instanceof ImageBlock)) {
                throw UnsupportedContentBlockException.forBlock(
                        "Ollama", block, "Ollama accepts images only");
            }
            final ImageBlock image = (ImageBlock) block;
            if (!(image.getSource() instanceof Base64Source)) {
                throw UnsupportedContentBlockException.forBlock(
                        "Ollama", block, "Ollama takes base64 image data, not a URL");
            }
            try {
                // ollama4j base64-encodes the bytes again when it serializes the request.
                images.add(
                        Base64.getDecoder().decode(((Base64Source) image.getSource()).getData()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "An image block's base64 data could not be decoded.", e);
            }
        }
        return images;
    }

    /**
     * Answers {@link NativeStructuredOutputSupport#NATIVE_RECOMMENDED} whenever the request can
     * carry the schema, whatever the model.
     *
     * <p>Capability is deliberately independent of the model: schema-constrained decoding is
     * applied by the Ollama server's sampler rather than by the model, so it holds for every model
     * served by a server at or above v0.5.0. There is also no model-level signal to key on.
     * Ollama's model capability set — completion, tools, insert, vision, embedding, thinking,
     * image, audio — carries nothing schema-related, {@code /api/show} reports exactly that set,
     * and {@code /api/version} reports only a version string. Since a server runs arbitrary local
     * models, any allowlist would be invented, and would report not-capable for models that do
     * work.
     *
     * <p>Three deployments break the guarantee, none of them distinguishable from a model name: a
     * server below v0.5.0 rejects the {@code format} field with HTTP 400; Ollama Cloud accepts the
     * request but does not enforce the schema; and the MLX runner accepts the field and drops it.
     */
    @Override
    protected NativeStructuredOutputSupport supportsNativeStructuredOutput(
            Object outputSchema, List<Tool> tools, Map<String, Object> modelParams) {
        return canApplyNativeStructuredOutput(outputSchema, tools, modelParams)
                ? NativeStructuredOutputSupport.NATIVE_RECOMMENDED
                : NativeStructuredOutputSupport.INFEASIBLE;
    }

    /**
     * Whether a request built from these inputs would carry a native {@code format}, the effective
     * model's capability aside.
     *
     * <p>Only a POJO {@link Class} has a native translation here; a {@code RowTypeInfo} wrapped in
     * {@code OutputSchema}, or any other form, has none and keeps the prompt-engineering fallback.
     * Since this connection's capability does not depend on the model, the schema form is the whole
     * of what it can report infeasible.
     *
     * <p>Neither the tools nor the parameters are read; this connection sends a native schema
     * alongside bound tools.
     *
     * @param outputSchema the schema the request would carry, or null for an unconstrained request
     * @param tools not read; bound tools do not stop this connection sending a native schema
     * @param modelParams not read
     * @return true if {@code outputSchema} is a POJO {@link Class}
     */
    private boolean canApplyNativeStructuredOutput(
            Object outputSchema, List<Tool> tools, Map<String, Object> modelParams) {
        return outputSchema instanceof Class;
    }

    @Override
    public ChatResult chat(
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
    public ChatResult chat(
            List<ChatMessage> messages,
            List<Tool> tools,
            Map<String, Object> modelParams,
            Object outputSchema) {
        return doChat(messages, tools, modelParams, outputSchema);
    }

    private ChatResult doChat(
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
            return convertResponse(
                    ollamaChatResponse, (String) modelParams.get("model"), extractReasoning);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Separates response parsing from transport for contract tests.
    ChatResult convertResponse(
            OllamaChatResponseModel ollamaChatResponse, String model, boolean extractReasoning) {
        final OllamaChatMessage ollamaChatMessage = ollamaChatResponse.getMessage();

        List<ContentBlock> blocks = new ArrayList<>();
        if (extractReasoning && ollamaChatMessage.getThinking() != null) {
            blocks.add(new ReasoningBlock(ollamaChatMessage.getThinking()));
        }
        if (!ollamaChatMessage.getResponse().isEmpty()) {
            blocks.add(new TextBlock(ollamaChatMessage.getResponse()));
        }
        List<OllamaChatToolCalls> calls = ollamaChatMessage.getToolCalls();
        if (calls != null) {
            blocks.addAll(convertToAgentsTools(calls));
        }
        Integer input = ollamaChatResponse.getPromptEvalCount();
        Integer output = ollamaChatResponse.getEvalCount();
        return new ChatResult(
                ChatMessage.assistant(blocks),
                model,
                null,
                new TokenUsage(
                        input == null ? null : input.longValue(),
                        output == null ? null : output.longValue()),
                ollamaChatResponse.getDoneReason(),
                null);
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
        // The feasibility check is asked rather than restated, so a caller asking the same question
        // gets the answer this branch acts on.
        if (canApplyNativeStructuredOutput(outputSchema, tools, modelParams)) {
            chatRequest.setFormat(toNativeFormat((Class<?>) outputSchema));
        }

        return chatRequest;
    }

    // Derives the JSON schema Ollama's format field expects from a POJO class. The schema comes
    // from the shared generator with one option added:
    //
    //   - MAP_VALUES_AS_ADDITIONAL_PROPERTIES gives a Map its value schema. Without it the map
    //     admits any value, and a model does emit values that the declared value type then fails
    //     to deserialize.
    //
    // When Ollama runs a model on llama.cpp, the grammar built from the schema generates the
    // required properties in the order the schema declares them, followed by the optional ones,
    // so the declaration order the shared schema keeps is the order the model fills in a class's
    // required fields. An alphabetical order would condition generation on an order the class
    // does not declare.
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
        return PojoJsonSchemaGenerator.generate(
                schemaClass, Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES);
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
    private List<ToolCallBlock> convertToAgentsTools(List<OllamaChatToolCalls> calls) {
        List<ToolCallBlock> result = new ArrayList<>();
        for (OllamaChatToolCalls call : calls) {
            result.add(
                    new ToolCallBlock(
                            (call.getId() == null || call.getId().isEmpty())
                                    ? UUID.randomUUID().toString()
                                    : call.getId(),
                            call.getFunction().getName(),
                            toArgumentsMap(call.getFunction().getArguments())));
        }
        return result;
    }
}
