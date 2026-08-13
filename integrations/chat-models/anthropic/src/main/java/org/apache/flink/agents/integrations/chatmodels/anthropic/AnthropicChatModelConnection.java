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
package org.apache.flink.agents.integrations.chatmodels.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonSchemaLocalValidation;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.tools.ToolMetadata;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A chat model integration for the Anthropic Chat service using the official Java SDK.
 *
 * <p>Supported connection parameters:
 *
 * <ul>
 *   <li><b>api_key</b> (required): Anthropic API key
 *   <li><b>timeout</b> (optional): Timeout in seconds for API requests
 *   <li><b>max_retries</b> (optional): Maximum number of retry attempts (default: 2)
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyAgent extends Agent {
 *   @ChatModelConnection
 *   public static ResourceDesc anthropic() {
 *     return ResourceDescriptor.Builder.newBuilder(AnthropicChatModelConnection.class.getName())
 *             .addInitialArgument("api_key", System.getenv("ANTHROPIC_API_KEY"))
 *             .addInitialArgument("timeout", 120)
 *             .addInitialArgument("max_retries", 3)
 *             .build();
 *   }
 * }
 * }</pre>
 */
public class AnthropicChatModelConnection extends BaseChatModelConnection {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicClient client;
    private final String defaultModel;

    public AnthropicChatModelConnection(
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);

        String apiKey = descriptor.getArgument("api_key");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("api_key should not be null or empty.");
        }

        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(apiKey);

        Integer timeoutSeconds = descriptor.getArgument("timeout");
        if (timeoutSeconds != null && timeoutSeconds > 0) {
            builder.timeout(Duration.ofSeconds(timeoutSeconds));
        }

        Integer maxRetries = descriptor.getArgument("max_retries");
        if (maxRetries != null && maxRetries >= 0) {
            builder.maxRetries(maxRetries);
        }

        this.defaultModel = descriptor.getArgument("model");
        this.client = builder.build();
    }

    @Override
    public void close() {
        this.client.close();
    }

    // Models Anthropic documents native structured-output support for. Source of truth:
    // https://platform.claude.com/docs/en/build-with-claude/structured-outputs
    //
    // The documented rule is generational rather than a per-snapshot list: structured outputs are
    // generally available for Claude 4.5 and later models, and for Claude Mythos Preview. Names
    // from the 4.6 generation onward carry no date and are pinned, so the name is itself the
    // snapshot and is matched exactly.
    //
    // The three 4.5-generation names are aliases that front a dated snapshot, so a request may
    // carry either the alias or the snapshot behind it and both have to match. Those are matched
    // by prefix instead, and the prefix has to retain the minor version: "claude-opus-4" would
    // also capture claude-opus-4-1-20250805, which predates the cutoff and is not capable.
    //
    // A name outside both sets reports not-capable and degrades to the prompt-engineering
    // fallback rather than failing at the provider.
    private static final Set<String> NATIVE_STRUCTURED_OUTPUT_MODELS =
            Set.of(
                    "claude-opus-4-6",
                    "claude-opus-4-7",
                    "claude-opus-4-8",
                    "claude-opus-5",
                    "claude-sonnet-4-6",
                    "claude-sonnet-5",
                    "claude-fable-5",
                    "claude-mythos-5",
                    "claude-mythos-preview");

    private static final Set<String> NATIVE_STRUCTURED_OUTPUT_ALIAS_PREFIXES =
            Set.of("claude-opus-4-5", "claude-sonnet-4-5", "claude-haiku-4-5");

    /**
     * Whether Anthropic documents native structured-output support for {@code effectiveModel}.
     *
     * <p>See the allowlists above for the source of truth and for why the 4.5-generation aliases
     * are matched by prefix while every other name is matched exactly. An unrecognized name reports
     * {@code false} so that it degrades to the prompt-engineering fallback rather than failing at
     * the provider.
     *
     * <p>Reads no instance state, so capability stays answerable independently of how the
     * connection was configured.
     */
    @Override
    protected boolean supportsNativeStructuredOutput(String effectiveModel) {
        // Load-bearing: the allowlist is an immutable Set, whose contains(null) throws rather than
        // reporting absence.
        if (effectiveModel == null) {
            return false;
        }
        return NATIVE_STRUCTURED_OUTPUT_MODELS.contains(effectiveModel)
                || NATIVE_STRUCTURED_OUTPUT_ALIAS_PREFIXES.stream()
                        .anyMatch(effectiveModel::startsWith);
    }

    // Models Anthropic documents as rejecting assistant-message prefilling. Source of truth:
    // https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/prefill-claudes-response
    //
    // Prefilling is not supported from the Claude 4.6 generation onward, nor on Claude Mythos
    // Preview, Claude Fable 5 or Claude Mythos 5; a request that prefills one of them is answered
    // with a 400 rather than a completion. Anthropic publishes no programmatic signal for prefill
    // support the way it does for structured outputs, so the rule has to be a maintained list of
    // names. Those names carry no date and are pinned, so the name is itself the snapshot and is
    // matched exactly, and a name outside the list is treated as accepting the prefill.
    //
    // Kept in its own storage rather than derived from the structured-output allowlists above,
    // whose contents it currently coincides with. The two encode different documented boundaries:
    // structured output starts at the 4.5 generation while prefill rejection starts at 4.6, so the
    // three 4.5-generation names are structured-output capable and still accept a prefill. Sharing
    // one list would hold only until a model moves one boundary without moving the other.
    private static final Set<String> PREFILL_UNSUPPORTED_MODELS =
            Set.of(
                    "claude-opus-4-6",
                    "claude-opus-4-7",
                    "claude-opus-4-8",
                    "claude-opus-5",
                    "claude-sonnet-4-6",
                    "claude-sonnet-5",
                    "claude-fable-5",
                    "claude-mythos-5",
                    "claude-mythos-preview");

    /**
     * Whether {@code effectiveModel} accepts the prefilled assistant {@code "{"} message.
     *
     * <p>See the list above for the source of truth and for why it is matched exactly and kept
     * apart from the structured-output allowlists. An unrecognized name reports {@code true}, which
     * matches the documented rule: prefilling is the long-standing behaviour and only the listed
     * names withdraw it. The cost of that default runs the opposite way to {@link
     * #supportsNativeStructuredOutput}: a rejecting model this list has not caught up with is
     * prefilled and answered with a 400, where an unrecognized name on the structured-output path
     * degrades silently to the prompt-engineering fallback instead.
     */
    static boolean supportsJsonPrefill(String effectiveModel) {
        // Load-bearing: the list is an immutable Set, whose contains(null) throws rather than
        // reporting absence.
        if (effectiveModel == null) {
            return true;
        }
        return !PREFILL_UNSUPPORTED_MODELS.contains(effectiveModel);
    }

    /**
     * Derives the native {@code output_config} for a POJO class through the SDK's typed
     * structured-output builder.
     *
     * <p>The Kotlin facade {@code StructuredOutputsKt.outputFormatFromClass} would produce this
     * directly, but it is compiled {@code ACC_SYNTHETIC} and so cannot be named from Java. The
     * typed builder generates the same schema; the config is extracted from the throwaway request
     * it produces and reattached to the real one, which also avoids that overload's side effect of
     * retyping the request and the response as {@code StructuredMessageCreateParams} and {@code
     * StructuredMessage}. The throwaway request is never sent, so its placeholder model, message
     * and token limit only have to satisfy the builder's required-field check.
     *
     * <p>Local schema validation is off so that the provider, not the client, is the authority on
     * which schemas it accepts.
     */
    private static <T> OutputConfig toNativeOutputConfig(Class<T> schemaClass) {
        return MessageCreateParams.builder()
                .model(Model.of(""))
                .addUserMessage("")
                .maxTokens(1)
                .outputConfig(schemaClass, JsonSchemaLocalValidation.NO)
                .build()
                .rawParams()
                .outputConfig()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Anthropic SDK did not produce an output_config for schema "
                                                + schemaClass.getName()));
    }

    @Override
    public ChatMessage chat(
            List<ChatMessage> messages,
            List<org.apache.flink.agents.api.tools.Tool> tools,
            Map<String, Object> modelParams) {
        return chat(messages, tools, modelParams, null);
    }

    /**
     * Translates {@code outputSchema} into Anthropic's native {@code output_config.format} when it
     * is a POJO {@link Class}, the effective model is one Anthropic documents structured-output
     * support for, and the caller has not already supplied its own {@code output_config}. Any other
     * combination sends no derived schema, so the request carries only the output configuration the
     * caller supplied, if any, and a schema that cannot be sent natively degrades to the
     * prompt-engineering fallback rather than failing at the provider.
     *
     * <p>A request that ends up carrying an {@code output_config} — whether derived here or
     * supplied by the caller — also suppresses the {@code json_prefill} parameter, since Anthropic
     * documents message prefilling as incompatible with structured outputs.
     */
    @Override
    public ChatMessage chat(
            List<ChatMessage> messages,
            List<org.apache.flink.agents.api.tools.Tool> tools,
            Map<String, Object> modelParams,
            Object outputSchema) {
        try {
            BuiltRequest built = buildRequest(messages, tools, modelParams, outputSchema);
            Message response = client.messages().create(built.params);
            ChatMessage result = convertResponse(built, response);

            // Stash token usage
            String modelName = null;
            if (modelParams != null && modelParams.get("model") != null) {
                modelName = modelParams.get("model").toString();
            }
            if (modelName == null || modelName.isBlank()) {
                modelName = this.defaultModel;
            }
            if (modelName != null && !modelName.isBlank()) {
                result.getExtraArgs().put("model_name", modelName);
                result.getExtraArgs().put("promptTokens", response.usage().inputTokens());
                result.getExtraArgs().put("completionTokens", response.usage().outputTokens());
            }

            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to call Anthropic messages API.", e);
        }
    }

    /**
     * Builds the request and reports the JSON prefill decision it made.
     *
     * <p>Whether the prefilled assistant {@code "{"} message was appended cannot be recomputed from
     * the request alone, and {@link #convertResponse} must know it to reconstruct the full JSON
     * document. Deciding once here and carrying the answer out keeps the request and the response
     * conversion from disagreeing.
     */
    BuiltRequest buildRequest(
            List<ChatMessage> messages,
            List<org.apache.flink.agents.api.tools.Tool> tools,
            Map<String, Object> rawModelParams,
            Object outputSchema) {
        Map<String, Object> modelParams =
                rawModelParams != null ? new HashMap<>(rawModelParams) : new HashMap<>();

        Object modelObj = modelParams.remove("model");
        String modelName = modelObj != null ? modelObj.toString() : this.defaultModel;
        if (modelName == null || modelName.isBlank()) {
            modelName = this.defaultModel;
        }

        List<TextBlockParam> systemBlocks = extractSystemMessages(messages);

        List<MessageParam> anthropicMessages =
                messages.stream()
                        .filter(m -> m.getRole() != MessageRole.SYSTEM)
                        .map(this::convertToAnthropicMessage)
                        .collect(Collectors.toList());

        MessageCreateParams.Builder builder =
                MessageCreateParams.builder()
                        .model(Model.of(modelName))
                        .messages(anthropicMessages);

        if (!systemBlocks.isEmpty()) {
            builder.systemOfTextBlockParams(systemBlocks);
        }

        // Handle strict tools - enables structured outputs for tool use
        Object strictTools = modelParams.remove("strict_tools");
        boolean strictToolsEnabled = Boolean.TRUE.equals(strictTools);

        if (tools != null && !tools.isEmpty()) {
            for (Tool tool : convertTools(tools, strictToolsEnabled)) {
                builder.addTool(tool);
            }
        }

        // Add beta header for strict tool use
        // https://platform.claude.com/docs/en/build-with-claude/structured-outputs#strict-tool-use
        if (strictToolsEnabled) {
            builder.putAdditionalHeader("anthropic-beta", "structured-outputs-2025-11-13");
        }

        Object maxTokens = modelParams.remove("max_tokens");
        if (maxTokens instanceof Number) {
            builder.maxTokens(((Number) maxTokens).longValue());
        }

        Object temperature = modelParams.remove("temperature");
        if (temperature instanceof Number) {
            builder.temperature(((Number) temperature).doubleValue());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> additionalKwargs =
                (Map<String, Object>) modelParams.remove("additional_kwargs");
        if (additionalKwargs != null) {
            applyAdditionalKwargs(builder, additionalKwargs);
        }

        // Read here rather than inside the native structured-output branch below because it governs
        // the JSON prefill too, and a caller can supply an output_config without supplying any
        // output schema for that branch to look at.
        boolean callerSuppliedOutputConfig =
                additionalKwargs != null && additionalKwargs.containsKey("output_config");

        // Native structured output applies only for a POJO Class schema on a model Anthropic
        // documents as capable; a RowTypeInfo (wrapped in OutputSchema) or an incapable model keeps
        // the prompt-engineering fallback. A caller-supplied output_config is the caller being
        // explicit about the exact parameter this branch writes, so it wins and the schema falls
        // back to prompt engineering rather than the two competing on the same request.
        //
        // TODO(#912): the requested strategy is not visible here, so this re-check cannot tell an
        // explicit NATIVE request apart from one that merely resolved to native. A caller asking
        // for NATIVE on a model this predicate rejects therefore degrades silently to the
        // prompt-engineering fallback instead of getting an error. Once strategy resolution is
        // wired up, NATIVE must either bypass this capability re-check or fail explicitly.
        boolean nativeSchemaApplied = false;
        if (outputSchema instanceof Class
                && supportsNativeStructuredOutput(modelName)
                && !callerSuppliedOutputConfig) {
            builder.outputConfig(toNativeOutputConfig((Class<?>) outputSchema));
            nativeSchemaApplied = true;
        }

        // JSON prefill appends a prefilled assistant "{" message to steer the model into emitting a
        // JSON document. It applies only when the request carries none of three features:
        //   - tool use, because the prefill forces JSON text instead of native tool_use blocks;
        //   - structured outputs, which Anthropic documents as incompatible with message prefilling
        //     — output_config already has the provider enforcing the very document the prefill
        //     exists to coax out of the model;
        //   - a model that rejects prefilling outright, which answers with a 400 rather than a
        //     completion.
        // The output_config test covers both ways one can reach the request: derived from
        // outputSchema above, or supplied by the caller through additional_kwargs. It keys on what
        // the request ends up carrying rather than on what was supplied, so a schema that could not
        // be sent natively keeps the prefill its prompt-engineering fallback depends on — unless
        // the caller supplied an output_config of its own.
        Object jsonPrefill = modelParams.remove("json_prefill");
        boolean hasToolsInRequest = tools != null && !tools.isEmpty();
        boolean requestCarriesOutputConfig = nativeSchemaApplied || callerSuppliedOutputConfig;
        boolean jsonPrefillApplied =
                Boolean.TRUE.equals(jsonPrefill)
                        && !hasToolsInRequest
                        && !requestCarriesOutputConfig
                        && supportsJsonPrefill(modelName);
        if (jsonPrefillApplied) {
            anthropicMessages.add(
                    MessageParam.builder().role(MessageParam.Role.ASSISTANT).content("{").build());
            // The builder copies the list it is given, so appending to the local list after the
            // earlier messages(...) call is not enough - the list has to be handed over again.
            builder.messages(anthropicMessages);
        }

        return new BuiltRequest(builder.build(), jsonPrefillApplied);
    }

    /** A built request together with the JSON prefill decision applied while building it. */
    static final class BuiltRequest {
        final MessageCreateParams params;
        final boolean jsonPrefillApplied;

        BuiltRequest(MessageCreateParams params, boolean jsonPrefillApplied) {
            this.params = params;
            this.jsonPrefillApplied = jsonPrefillApplied;
        }
    }

    private List<TextBlockParam> extractSystemMessages(List<ChatMessage> messages) {
        return messages.stream()
                .filter(m -> m.getRole() == MessageRole.SYSTEM)
                .map(m -> TextBlockParam.builder().text(m.getContent()).build())
                .collect(Collectors.toList());
    }

    private MessageParam convertToAnthropicMessage(ChatMessage message) {
        MessageRole role = message.getRole();
        String content = Optional.ofNullable(message.getContent()).orElse("");

        switch (role) {
            case USER:
                return MessageParam.builder().role(MessageParam.Role.USER).content(content).build();

            case ASSISTANT:
                List<Map<String, Object>> toolCalls = message.getToolCalls();
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    List<ContentBlockParam> contentBlocks = new ArrayList<>();
                    if (!content.isEmpty()) {
                        contentBlocks.add(
                                ContentBlockParam.ofText(
                                        TextBlockParam.builder().text(content).build()));
                    }
                    contentBlocks.addAll(convertToolCallsToToolUse(toolCalls));
                    return MessageParam.builder()
                            .role(MessageParam.Role.ASSISTANT)
                            .contentOfBlockParams(contentBlocks)
                            .build();
                } else {
                    return MessageParam.builder()
                            .role(MessageParam.Role.ASSISTANT)
                            .content(content)
                            .build();
                }

            case TOOL:
                Object toolCallId = message.getExtraArgs().get("externalId");
                if (toolCallId == null) {
                    throw new IllegalArgumentException(
                            "Tool message must have an externalId in extraArgs.");
                }
                ToolResultBlockParam toolResult =
                        ToolResultBlockParam.builder()
                                .toolUseId(toolCallId.toString())
                                .content(content)
                                .build();
                return MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(List.of(ContentBlockParam.ofToolResult(toolResult)))
                        .build();

            default:
                throw new IllegalArgumentException("Unsupported role: " + role);
        }
    }

    private List<ContentBlockParam> convertToolCallsToToolUse(List<Map<String, Object>> toolCalls) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (Map<String, Object> call : toolCalls) {
            Object type = call.getOrDefault("type", "function");
            if (!"function".equals(String.valueOf(type))) {
                continue;
            }

            Map<String, Object> functionPayload = toMap(call.get("function"));
            String functionName = String.valueOf(functionPayload.get("name"));
            Object arguments = functionPayload.get("arguments");
            Map<String, Object> inputMap = toMap(arguments);

            Object originalIdObj = call.get("original_id");
            if (originalIdObj == null) {
                throw new IllegalArgumentException(
                        "Tool call must have an original_id for Anthropic.");
            }

            ToolUseBlockParam toolUse =
                    ToolUseBlockParam.builder()
                            .id(originalIdObj.toString())
                            .name(functionName)
                            .input(toJsonValue(inputMap))
                            .build();

            blocks.add(ContentBlockParam.ofToolUse(toolUse));
        }
        return blocks;
    }

    private List<Tool> convertTools(
            List<org.apache.flink.agents.api.tools.Tool> tools, boolean strictToolsEnabled) {
        List<Tool> anthropicTools = new ArrayList<>(tools.size());
        for (org.apache.flink.agents.api.tools.Tool tool : tools) {
            ToolMetadata metadata = tool.getMetadata();
            Tool.Builder toolBuilder =
                    Tool.builder().name(metadata.getName()).description(metadata.getDescription());

            String schema = metadata.getInputSchema();
            if (schema != null && !schema.isBlank()) {
                toolBuilder.inputSchema(parseToolInputSchema(schema));
            }

            if (strictToolsEnabled) {
                toolBuilder.putAdditionalProperty("strict", JsonValue.from(true));
            }

            anthropicTools.add(toolBuilder.build());
        }
        return anthropicTools;
    }

    private Tool.InputSchema parseToolInputSchema(String schemaJson) {
        try {
            JsonNode root = mapper.readTree(schemaJson);
            if (root == null || !root.isObject()) {
                return Tool.InputSchema.builder().build();
            }

            Tool.InputSchema.Builder builder = Tool.InputSchema.builder();
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

    /**
     * Converts a response into a {@link ChatMessage}, reconstructing the leading {@code "{"} when
     * the request carried the JSON prefill.
     *
     * <p>Takes the whole {@link BuiltRequest} rather than the prefill flag on its own so the flag
     * travels with the request it was derived from, instead of being computed separately at the call
     * site where the two can drift apart. A flag that disagrees with the request either prepends a
     * stray {@code "{"} or drops a required one, and the resulting JSON is malformed in a way the
     * response itself gives no sign of.
     */
    ChatMessage convertResponse(BuiltRequest built, Message response) {
        List<ContentBlock> contentBlocks = response.content();
        if (contentBlocks.isEmpty()) {
            throw new IllegalStateException("Anthropic response did not contain any content.");
        }

        StringBuilder textContent = new StringBuilder();
        // If JSON prefill was used, prepend "{" since the response only contains the continuation
        if (built.jsonPrefillApplied) {
            textContent.append("{");
        }
        List<Map<String, Object>> toolCalls = new ArrayList<>();

        for (ContentBlock block : contentBlocks) {
            if (block.isText()) {
                block.text()
                        .ifPresent(
                                textBlock -> {
                                    textContent.append(textBlock.text());
                                });
            } else if (block.isToolUse()) {
                block.toolUse()
                        .ifPresent(
                                toolUse -> {
                                    String toolUseId = toolUse.id();
                                    Map<String, Object> toolCall = new LinkedHashMap<>();
                                    toolCall.put("id", toolUseId);
                                    toolCall.put("type", "function");

                                    Map<String, Object> functionMap = new LinkedHashMap<>();
                                    functionMap.put("name", toolUse.name());
                                    JsonValue inputValue = toolUse._input();
                                    Map<String, Object> inputMap = jsonValueToMap(inputValue);
                                    functionMap.put("arguments", inputMap);
                                    toolCall.put("function", functionMap);
                                    toolCall.put("original_id", toolUseId);

                                    toolCalls.add(toolCall);
                                });
            }
        }

        String finalText = textContent.toString();

        // If the response has no tool calls, try to extract JSON from markdown code blocks.
        if (toolCalls.isEmpty()) {
            finalText = extractJsonFromMarkdown(finalText);
        }

        ChatMessage chatMessage = ChatMessage.assistant(finalText);
        if (!toolCalls.isEmpty()) {
            chatMessage.setToolCalls(toolCalls);
        }

        return chatMessage;
    }

    /**
     * Extracts JSON content from a string that may contain markdown code blocks.
     *
     * <p>Claude often wraps JSON responses in markdown code blocks like {@code ```json ... ```},
     * especially on a response no JSON prefill was applied to, since an assistant turn already
     * opened with {@code "{"} cannot be continued into a fence. This method extracts the JSON
     * content from such responses. If no code block is found, the original content is returned
     * unchanged.
     *
     * @param content The response content that may contain markdown-wrapped JSON
     * @return The extracted JSON string, or the original content if no code block is found
     */
    private String extractJsonFromMarkdown(String content) {
        if (content == null) {
            return null;
        }

        String trimmed = content.trim();

        // Try to find JSON in markdown code block (```json ... ``` or ``` ... ```)
        int jsonBlockStart = trimmed.indexOf("```json");
        int genericBlockStart = trimmed.indexOf("```");

        int contentStart;

        if (jsonBlockStart != -1) {
            contentStart = jsonBlockStart + 7; // length of "```json"
        } else if (genericBlockStart != -1) {
            contentStart = genericBlockStart + 3; // length of "```"
        } else {
            return content;
        }

        // Find the closing ```
        int blockEnd = trimmed.indexOf("```", contentStart);
        if (blockEnd == -1) {
            return content;
        }

        // Extract content between the markers
        return trimmed.substring(contentStart, blockEnd).trim();
    }

    private void applyAdditionalKwargs(
            MessageCreateParams.Builder builder, Map<String, Object> kwargs) {
        for (Map.Entry<String, Object> entry : kwargs.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            switch (key) {
                case "top_k":
                    if (value instanceof Number) {
                        builder.topK(((Number) value).longValue());
                    }
                    break;
                case "top_p":
                    if (value instanceof Number) {
                        builder.topP(((Number) value).doubleValue());
                    }
                    break;
                case "stop_sequences":
                    if (value instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<String> stopSequences = (List<String>) value;
                        builder.stopSequences(stopSequences);
                    }
                    break;
                default:
                    builder.putAdditionalBodyProperty(key, toJsonValue(value));
                    break;
            }
        }
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

    private Map<String, Object> jsonValueToMap(JsonValue jsonValue) {
        try {
            String jsonString = mapper.writeValueAsString(jsonValue);
            return mapper.readValue(jsonString, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to convert JsonValue to Map.", e);
        }
    }
}
