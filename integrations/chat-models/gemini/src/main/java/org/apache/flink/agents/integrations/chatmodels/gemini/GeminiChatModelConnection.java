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
package org.apache.flink.agents.integrations.chatmodels.gemini;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.tools.ToolMetadata;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A chat model integration for the Google Gemini {@code generateContent} API using the official
 * google-genai Java SDK.
 *
 * <p>The native Gemini protocol differs from the OpenAI-compatible shape in a few places this
 * module handles directly:
 *
 * <ul>
 *   <li>System messages are passed as a separate {@code systemInstruction}, not a system role.
 *   <li>Conversation roles are {@code user} and {@code model} (assistant maps to {@code model}).
 *   <li>Tool calls are returned as {@code functionCall} parts carrying a native {@code id} (there
 *       is no separate {@code tool_call_id}); tool results are sent back as {@code
 *       functionResponse} parts inside a {@code user} turn.
 * </ul>
 *
 * <p>Supported connection parameters:
 *
 * <ul>
 *   <li><b>api_key</b> (optional): Gemini Developer API key. May be omitted when a local proxy
 *       injects the credential, but either {@code api_key} or {@code base_url} must be provided.
 *   <li><b>base_url</b> (optional): Custom endpoint, e.g. a local proxy such as {@code
 *       http://127.0.0.1:15721}. When set, requests are routed there instead of the default Google
 *       endpoint.
 *   <li><b>model</b> (optional): Default model name, used when no model is supplied per request.
 *   <li><b>timeout</b> (optional): Timeout in seconds for API requests.
 *   <li><b>vertex_ai</b> (optional): When true, use the Vertex AI backend together with {@code
 *       project} and {@code location}.
 *   <li><b>project</b> / <b>location</b> (optional): Vertex AI project id and location.
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyAgent extends Agent {
 *   @ChatModelConnection
 *   public static ResourceDesc gemini() {
 *     return ResourceDescriptor.Builder.newBuilder(GeminiChatModelConnection.class.getName())
 *             .addInitialArgument("api_key", System.getenv("GEMINI_API_KEY"))
 *             .addInitialArgument("model", "gemini-3-pro-preview")
 *             .build();
 *   }
 * }
 * }</pre>
 */
public class GeminiChatModelConnection extends BaseChatModelConnection {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper = new ObjectMapper();
    private final Client client;
    private final String defaultModel;

    public GeminiChatModelConnection(
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);

        String apiKey = descriptor.getArgument("api_key");
        String baseUrl = descriptor.getArgument("base_url");
        Boolean vertexAi = descriptor.getArgument("vertex_ai");

        boolean useVertex = Boolean.TRUE.equals(vertexAi);
        if (!useVertex
                && (apiKey == null || apiKey.isBlank())
                && (baseUrl == null || baseUrl.isBlank())) {
            throw new IllegalArgumentException(
                    "Either api_key or base_url must be provided for the Gemini connection.");
        }

        Client.Builder builder = Client.builder();
        if (!useVertex) {
            // The SDK requires a non-blank API key for the Gemini Developer backend. When the
            // caller relies on a proxy (base_url) to inject the real credential, supply a
            // placeholder so the SDK's own validation passes; the proxy overrides it on the wire.
            if (apiKey != null && !apiKey.isBlank()) {
                builder.apiKey(apiKey);
            } else {
                builder.apiKey("proxy-injected");
            }
        }

        HttpOptions.Builder httpOptions = null;
        if (baseUrl != null && !baseUrl.isBlank()) {
            httpOptions = HttpOptions.builder().baseUrl(baseUrl);
        }
        Integer timeoutSeconds = descriptor.getArgument("timeout");
        if (timeoutSeconds != null && timeoutSeconds > 0) {
            if (httpOptions == null) {
                httpOptions = HttpOptions.builder();
            }
            // HttpOptions timeout is expressed in milliseconds.
            httpOptions.timeout(timeoutSeconds * 1000);
        }
        if (httpOptions != null) {
            builder.httpOptions(httpOptions.build());
        }

        if (useVertex) {
            builder.vertexAI(true);
            String project = descriptor.getArgument("project");
            String location = descriptor.getArgument("location");
            if (project != null && !project.isBlank()) {
                builder.project(project);
            }
            if (location != null && !location.isBlank()) {
                builder.location(location);
            }
        }

        this.defaultModel = descriptor.getArgument("model");
        this.client = builder.build();
    }

    @Override
    public void close() {
        this.client.close();
    }

    @Override
    public ChatMessage chat(
            List<ChatMessage> messages,
            List<org.apache.flink.agents.api.tools.Tool> tools,
            Map<String, Object> arguments) {
        try {
            Map<String, Object> args =
                    arguments != null ? new HashMap<>(arguments) : new HashMap<>();

            Object modelObj = args.remove("model");
            String modelName = modelObj != null ? modelObj.toString() : this.defaultModel;
            if (modelName == null || modelName.isBlank()) {
                modelName = this.defaultModel;
            }
            if (modelName == null || modelName.isBlank()) {
                throw new IllegalArgumentException("model name must be provided for Gemini.");
            }

            List<Content> contents =
                    messages.stream()
                            .filter(m -> m.getRole() != MessageRole.SYSTEM)
                            .map(this::convertToContent)
                            .collect(Collectors.toList());

            GenerateContentConfig config = buildConfig(messages, tools, args);

            GenerateContentResponse response =
                    client.models.generateContent(modelName, contents, config);
            ChatMessage result = convertResponse(response);

            recordUsage(result, modelName, response);

            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to call Gemini generateContent API.", e);
        }
    }

    private GenerateContentConfig buildConfig(
            List<ChatMessage> messages,
            List<org.apache.flink.agents.api.tools.Tool> tools,
            Map<String, Object> arguments) {
        GenerateContentConfig.Builder builder = GenerateContentConfig.builder();

        Content systemInstruction = extractSystemInstruction(messages);
        if (systemInstruction != null) {
            builder.systemInstruction(systemInstruction);
        }

        Object temperature = arguments.remove("temperature");
        if (temperature instanceof Number) {
            builder.temperature(((Number) temperature).floatValue());
        }

        Object maxOutputTokens = arguments.remove("max_output_tokens");
        if (maxOutputTokens instanceof Number) {
            builder.maxOutputTokens(((Number) maxOutputTokens).intValue());
        }

        if (tools != null && !tools.isEmpty()) {
            builder.tools(List.of(convertTools(tools)));
        }

        return builder.build();
    }

    private Tool convertTools(List<org.apache.flink.agents.api.tools.Tool> tools) {
        List<FunctionDeclaration> declarations = new ArrayList<>(tools.size());
        for (org.apache.flink.agents.api.tools.Tool tool : tools) {
            ToolMetadata metadata = tool.getMetadata();
            FunctionDeclaration.Builder builder =
                    FunctionDeclaration.builder()
                            .name(metadata.getName())
                            .description(metadata.getDescription());

            String schema = metadata.getInputSchema();
            if (schema != null && !schema.isBlank()) {
                builder.parametersJsonSchema(parseSchema(schema));
            }

            declarations.add(builder.build());
        }
        return Tool.builder().functionDeclarations(declarations).build();
    }

    private Content extractSystemInstruction(List<ChatMessage> messages) {
        List<Part> parts =
                messages.stream()
                        .filter(m -> m.getRole() == MessageRole.SYSTEM)
                        .map(m -> Part.fromText(Optional.ofNullable(m.getContent()).orElse("")))
                        .collect(Collectors.toList());
        if (parts.isEmpty()) {
            return null;
        }
        return Content.builder().parts(parts).build();
    }

    // Package-visible for unit testing of the message conversion.
    Content convertToContent(ChatMessage message) {
        MessageRole role = message.getRole();
        String content = Optional.ofNullable(message.getContent()).orElse("");

        switch (role) {
            case USER:
                return Content.builder()
                        .role("user")
                        .parts(List.of(Part.fromText(content)))
                        .build();

            case ASSISTANT:
                List<Part> parts = new ArrayList<>();
                if (!content.isEmpty()) {
                    parts.add(Part.fromText(content));
                }
                List<Map<String, Object>> toolCalls = message.getToolCalls();
                if (toolCalls != null) {
                    for (Map<String, Object> call : toolCalls) {
                        parts.add(convertToolCallToPart(call));
                    }
                }
                if (parts.isEmpty()) {
                    parts.add(Part.fromText(""));
                }
                return Content.builder().role("model").parts(parts).build();

            case TOOL:
                Object name = message.getExtraArgs().get("name");
                if (name == null) {
                    throw new IllegalArgumentException(
                            "Tool message must have a 'name' in extraArgs for Gemini.");
                }
                Map<String, Object> responseMap = new LinkedHashMap<>();
                responseMap.put("result", content);
                return Content.builder()
                        .role("user")
                        .parts(List.of(Part.fromFunctionResponse(name.toString(), responseMap)))
                        .build();

            default:
                throw new IllegalArgumentException("Unsupported role: " + role);
        }
    }

    // Package-visible for unit testing of the tool-call round-trip.
    Part convertToolCallToPart(Map<String, Object> call) {
        Map<String, Object> functionPayload = toMap(call.get("function"));
        String functionName = String.valueOf(functionPayload.get("name"));
        Map<String, Object> argsMap = toMap(functionPayload.get("arguments"));

        FunctionCall.Builder fcBuilder = FunctionCall.builder().name(functionName).args(argsMap);
        Object originalId = call.get("original_id");
        if (originalId != null) {
            fcBuilder.id(originalId.toString());
        }

        Part.Builder partBuilder = Part.builder().functionCall(fcBuilder.build());
        // Echo back the thoughtSignature captured from the model response (Gemini 3 requirement).
        Object signature = call.get("thought_signature");
        if (signature != null) {
            partBuilder.thoughtSignature(Base64.getDecoder().decode(signature.toString()));
        }
        return partBuilder.build();
    }

    private Object parseSchema(String schemaJson) {
        try {
            return mapper.readValue(schemaJson, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse tool schema JSON.", e);
        }
    }

    private ChatMessage convertResponse(GenerateContentResponse response) {
        // Walk the first candidate's parts directly (rather than the response.text()/
        // functionCalls() conveniences) so we can capture the part-level thoughtSignature that
        // Gemini 3 emits alongside each functionCall and requires to be echoed back on the next
        // turn.
        StringBuilder textContent = new StringBuilder();
        List<Map<String, Object>> toolCalls = new ArrayList<>();

        List<Part> parts =
                response.candidates().orElseGet(List::of).stream()
                        .findFirst()
                        .flatMap(Candidate::content)
                        .flatMap(Content::parts)
                        .orElseGet(List::of);

        for (Part part : parts) {
            part.text().ifPresent(textContent::append);
            part.functionCall()
                    .ifPresent(
                            fc ->
                                    toolCalls.add(
                                            convertFunctionCall(
                                                    fc, part.thoughtSignature().orElse(null))));
        }

        ChatMessage chatMessage = ChatMessage.assistant(textContent.toString());
        if (!toolCalls.isEmpty()) {
            chatMessage.setToolCalls(toolCalls);
        }
        return chatMessage;
    }

    // Package-visible for unit testing of the function-call parsing.
    Map<String, Object> convertFunctionCall(FunctionCall functionCall, byte[] thoughtSignature) {
        String id = functionCall.id().orElse(null);
        String name = functionCall.name().orElse("");
        Map<String, Object> argsMap = functionCall.args().orElseGet(LinkedHashMap::new);

        Map<String, Object> functionMap = new LinkedHashMap<>();
        functionMap.put("name", name);
        functionMap.put("arguments", argsMap);

        Map<String, Object> toolCall = new LinkedHashMap<>();
        if (id != null) {
            toolCall.put("id", id);
            toolCall.put("original_id", id);
        }
        toolCall.put("type", "function");
        toolCall.put("function", functionMap);
        // Gemini 3 requires the opaque thoughtSignature to be echoed back when the tool-call turn
        // is replayed. Stash it as Base64 so it survives the Map<String, Object> representation.
        if (thoughtSignature != null) {
            toolCall.put("thought_signature", Base64.getEncoder().encodeToString(thoughtSignature));
        }
        return toolCall;
    }

    private void recordUsage(
            ChatMessage result, String modelName, GenerateContentResponse response) {
        GenerateContentResponseUsageMetadata usage = response.usageMetadata().orElse(null);
        if (usage == null) {
            return;
        }
        long promptTokens = usage.promptTokenCount().orElse(0);
        long completionTokens = usage.candidatesTokenCount().orElse(0);
        result.getExtraArgs().put("model_name", modelName);
        result.getExtraArgs().put("promptTokens", promptTokens);
        result.getExtraArgs().put("completionTokens", completionTokens);
    }

    private Map<String, Object> toMap(Object value) {
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> casted = (Map<String, Object>) value;
            return new LinkedHashMap<>(casted);
        }
        if (value == null) {
            return new LinkedHashMap<>();
        }
        return mapper.convertValue(value, MAP_TYPE);
    }
}
