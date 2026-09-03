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

package org.apache.flink.agents.integrations.chatmodels.bedrock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import org.apache.flink.agents.api.RetryExecutor;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkNumber;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.JsonSchemaDefinition;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.OutputConfig;
import software.amazon.awssdk.services.bedrockruntime.model.OutputFormat;
import software.amazon.awssdk.services.bedrockruntime.model.OutputFormatStructure;
import software.amazon.awssdk.services.bedrockruntime.model.OutputFormatType;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Bedrock Converse API chat model connection for flink-agents.
 *
 * <p>Uses the Converse API which provides a unified interface across all Bedrock models with native
 * tool calling support. Authentication is handled via SigV4 using the default AWS credentials
 * chain.
 *
 * <p>Future work: support reasoning content blocks (Claude extended thinking), citation blocks, and
 * image/document content blocks.
 *
 * <p>Supported connection parameters:
 *
 * <ul>
 *   <li><b>region</b> (optional): AWS region (defaults to us-east-1)
 *   <li><b>model</b> (optional): Default model ID (e.g. us.anthropic.claude-sonnet-4-20250514-v1:0)
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * @ChatModelConnection
 * public static ResourceDescriptor bedrockConnection() {
 *     return ResourceDescriptor.Builder.newBuilder(BedrockChatModelConnection.class.getName())
 *             .addInitialArgument("region", "us-east-1")
 *             .addInitialArgument("model", "us.anthropic.claude-sonnet-4-20250514-v1:0")
 *             .build();
 * }
 * }</pre>
 */
public class BedrockChatModelConnection extends BaseChatModelConnection {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Models AWS documents structured-output support for on the bedrock-runtime endpoint. There is
    // no single list page: the feature page delegates the per-model answer to the individual model
    // cards, where each card carries it as a "Structured outputs" bullet in the Supported or Not
    // Supported column of its "Features supported using bedrock-runtime endpoint" table.
    //
    // The ids are the Model ID column of each card's Programmatic Access table, read from the
    // bedrock-runtime row. A card commonly prints a different id for bedrock-mantle and can carry
    // opposite verdicts for the two, so the endpoint an id was read from is part of what makes the
    // entry correct. This connection calls Converse on bedrock-runtime.
    //
    // Matching is exact, never by prefix. A Bedrock id already pins the vendor, the snapshot date
    // and the version in one string, so there is no alias for a prefix to cover, and a prefix would
    // over-capture: "qwen.qwen3" admits qwen.qwen3-vl-235b-a22b, which AWS documents as not
    // supported, and "anthropic.claude-sonnet-4" admits anthropic.claude-sonnet-4-20250514-v1:0,
    // whose card carries no answer at all. Exact matching also keeps irregular id shapes correct
    // with no normalisation rule: mistral.mistral-large-3-675b-instruct carries no version suffix,
    // openai.gpt-oss-120b-1:0 carries "-1:0" rather than "-v1:0".
    //
    // A card whose capability table carries the bullet in neither column is undocumented rather
    // than negative, and is absent from this set for that reason.
    private static final Set<String> NATIVE_STRUCTURED_OUTPUT_MODELS =
            Set.of(
                    "anthropic.claude-sonnet-4-5-20250929-v1:0",
                    "anthropic.claude-opus-4-5-20251101-v1:0",
                    "anthropic.claude-haiku-4-5-20251001-v1:0",
                    "mistral.mistral-large-3-675b-instruct",
                    "openai.gpt-oss-120b-1:0",
                    "openai.gpt-oss-20b-1:0",
                    "qwen.qwen3-235b-a22b-2507-v1:0",
                    "qwen.qwen3-32b-v1:0",
                    "qwen.qwen3-coder-30b-a3b-v1:0",
                    "qwen.qwen3-coder-480b-a35b-v1:0",
                    "qwen.qwen3-coder-next",
                    "qwen.qwen3-next-80b-a3b");

    // A cross-Region inference profile id is a model id behind a geographic or global prefix, and
    // AWS documents structured output as working through cross-Region inference. The prefix set is
    // open-ended — the documentation names members by example and states that new profiles may
    // be created — so a leading segment is matched by shape rather than against a fixed list,
    // which would already have missed the documented us-gov. profiles. The charset excludes ":"
    // and "/", so no ARN can be shortened this way, and the strip is attempted only after the id
    // itself fails to match, so a bare model id is never shortened.
    private static final Pattern INFERENCE_PROFILE_PREFIX = Pattern.compile("^[a-z0-9-]+\\.(.+)$");

    private final BedrockRuntimeClient client;
    private final String defaultModel;
    private final RetryExecutor retryExecutor;

    public BedrockChatModelConnection(
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);

        String region = descriptor.getArgument("region");
        if (region == null || region.isBlank()) {
            region = "us-east-1";
        }

        this.client =
                BedrockRuntimeClient.builder()
                        .region(Region.of(region))
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .build();

        this.defaultModel = descriptor.getArgument("model");
        Integer retries = descriptor.getArgument("max_retries");
        this.retryExecutor =
                RetryExecutor.builder()
                        .maxRetries(retries != null ? retries : 5)
                        .initialBackoffMs(200)
                        .retryablePredicate(BedrockChatModelConnection::isRetryable)
                        .build();
    }

    /**
     * Whether AWS documents structured-output support for {@code effectiveModel}.
     *
     * <p>See the allowlist above for the source of truth, for why the match is exact, and for why a
     * geographic or global inference-profile prefix is stripped before it.
     *
     * <p>Every ARN reports {@code false}. A provisioned-throughput, imported-model,
     * custom-model-deployment, application-inference-profile or marketplace-endpoint ARN identifies
     * a resource without naming the model behind it, and a prompt-router ARN names a set whose
     * member is chosen per request, so for none of them is an answer derivable from the identifier
     * the request carries. An unrecognized identifier reports {@code false} so that it degrades to
     * the prompt-engineering fallback rather than failing at the provider.
     *
     * <p>A null or blank model reports {@code false} rather than throwing: {@code resolveModel}
     * rejects one before a request is built, but this method is part of the connection contract and
     * answers for whatever it is given. Only the null case needs a guard of its own, because the
     * allowlist is an immutable Set whose {@code contains(null)} throws; a blank model is merely
     * absent from it.
     *
     * <p>Reads no instance state, so capability stays answerable independently of how the
     * connection was configured.
     */
    @Override
    protected boolean supportsNativeStructuredOutput(String effectiveModel) {
        // Load-bearing: the allowlist is an immutable Set, whose contains(null) throws rather than
        // reporting absence.
        if (effectiveModel == null || effectiveModel.isBlank()) {
            return false;
        }
        if (NATIVE_STRUCTURED_OUTPUT_MODELS.contains(effectiveModel)) {
            return true;
        }
        Matcher profile = INFERENCE_PROFILE_PREFIX.matcher(effectiveModel);
        return profile.matches() && NATIVE_STRUCTURED_OUTPUT_MODELS.contains(profile.group(1));
    }

    @Override
    public ChatMessage chat(
            List<ChatMessage> messages, List<Tool> tools, Map<String, Object> modelParams) {
        return chat(messages, tools, modelParams, null);
    }

    /**
     * Translates {@code outputSchema} into Converse's native {@code outputConfig} when it is a POJO
     * {@link Class} and the effective model is one AWS documents as supporting it. Any other schema
     * form — notably a {@code RowTypeInfo} wrapped in {@code OutputSchema} — and any other model
     * leave the request unconstrained, so that the caller keeps the prompt-engineering fallback.
     */
    @Override
    public ChatMessage chat(
            List<ChatMessage> messages,
            List<Tool> tools,
            Map<String, Object> modelParams,
            Object outputSchema) {
        ConverseRequest request = buildRequest(messages, tools, modelParams, outputSchema);
        String modelId = request.modelId();

        ConverseResponse response =
                retryExecutor.execute(() -> client.converse(request), "BedrockConverse");

        ChatMessage result = convertResponse(response);
        if (response.usage() != null) {
            result.getExtraArgs().put("model_name", modelId);
            result.getExtraArgs().put("promptTokens", response.usage().inputTokens().longValue());
            result.getExtraArgs()
                    .put("completionTokens", response.usage().outputTokens().longValue());
        }
        return result;
    }

    /**
     * Translate the flink-agents call arguments into a Converse request: the effective model id,
     * the SYSTEM/conversation message split, the tool configuration, the inference configuration,
     * and the native output configuration when the schema and the model both admit one.
     *
     * <p>Package-private so a test can assert the request body without issuing a live call through
     * the Bedrock runtime client.
     *
     * <p>Resolving the model is the first step, so an absent model id is rejected before any
     * request state is built.
     *
     * @param messages the conversation, SYSTEM messages included; must not be null
     * @param tools the tools to advertise, or {@code null} / empty for none
     * @param modelParams per-call parameters; {@code model}, {@code temperature} and {@code
     *     max_tokens} are read, and {@code null} is accepted
     * @param outputSchema the schema the response should conform to, or {@code null} for an
     *     unconstrained response; applied natively only for a POJO {@link Class} on a model that
     *     supports it, and otherwise left to the caller's prompt-engineering fallback
     * @return the request to send to Converse
     * @throws IllegalArgumentException if neither the call nor the connection supplies a model id
     */
    ConverseRequest buildRequest(
            List<ChatMessage> messages,
            List<Tool> tools,
            Map<String, Object> modelParams,
            Object outputSchema) {
        String modelId = resolveModel(modelParams);

        List<ChatMessage> systemMsgs =
                messages.stream()
                        .filter(m -> m.getRole() == MessageRole.SYSTEM)
                        .collect(Collectors.toList());
        List<ChatMessage> conversationMsgs =
                messages.stream()
                        .filter(m -> m.getRole() != MessageRole.SYSTEM)
                        .collect(Collectors.toList());

        ConverseRequest.Builder requestBuilder =
                ConverseRequest.builder()
                        .modelId(modelId)
                        .messages(mergeMessages(conversationMsgs));

        if (!systemMsgs.isEmpty()) {
            requestBuilder.system(
                    systemMsgs.stream()
                            .map(m -> SystemContentBlock.builder().text(m.getContent()).build())
                            .collect(Collectors.toList()));
        }

        if (tools != null && !tools.isEmpty()) {
            requestBuilder.toolConfig(
                    ToolConfiguration.builder()
                            .tools(
                                    tools.stream()
                                            .map(this::toBedrockTool)
                                            .collect(Collectors.toList()))
                            .build());
        }

        // Inference config: temperature and max_tokens
        if (modelParams != null) {
            InferenceConfiguration.Builder inferenceBuilder = null;
            Object temp = modelParams.get("temperature");
            if (temp instanceof Number) {
                inferenceBuilder = InferenceConfiguration.builder();
                inferenceBuilder.temperature(((Number) temp).floatValue());
            }
            Object maxTokens = modelParams.get("max_tokens");
            if (maxTokens instanceof Number) {
                if (inferenceBuilder == null) {
                    inferenceBuilder = InferenceConfiguration.builder();
                }
                inferenceBuilder.maxTokens(((Number) maxTokens).intValue());
            }
            if (inferenceBuilder != null) {
                requestBuilder.inferenceConfig(inferenceBuilder.build());
            }
        }

        if (outputSchema instanceof Class && supportsNativeStructuredOutput(modelId)) {
            requestBuilder.outputConfig(nativeOutputConfig((Class<?>) outputSchema));
        }

        return requestBuilder.build();
    }

    /**
     * Wraps the schema derived from {@code schemaClass} in the request element Converse reads it
     * from.
     *
     * <p>Converse takes the schema as serialized text rather than as a document, unlike the tool
     * input schema on the same request, so the derived schema is written out here.
     */
    private static OutputConfig nativeOutputConfig(Class<?> schemaClass) {
        return OutputConfig.builder()
                .textFormat(
                        OutputFormat.builder()
                                .type(OutputFormatType.JSON_SCHEMA)
                                .structure(
                                        OutputFormatStructure.builder()
                                                .jsonSchema(
                                                        JsonSchemaDefinition.builder()
                                                                .schema(
                                                                        toNativeSchema(schemaClass)
                                                                                .toString())
                                                                .build())
                                                .build())
                                .build())
                .build();
    }

    // Derives the JSON schema from a POJO class. Every setting below addresses a concrete way the
    // generated schema otherwise fails to constrain generation:
    //
    //   - DRAFT_2020_12 is the dialect Bedrock validates a schema against, so the schema
    //     declares it rather than the generator's older default.
    //   - The PLAIN_JSON preset keeps generation to fields. Without a preset, getters surface as
    //     properties of their own, named after the accessor call, e.g. "getSummary()".
    //   - The required check marks every field required except an Optional one. The default marks
    //     nothing required, which lets a model omit fields at will, while marking everything
    //     required would force the fields a caller declared omissible.
    //   - The Jackson module makes the schema name properties the way Jackson names them. The
    //     response is read back into the same class with an ObjectMapper, so a property that
    //     @JsonProperty renames or @JsonIgnore drops has to be stated in the schema under the name
    //     the mapper reads, or a response that satisfies the schema still fails to deserialize.
    //     It is applied with no JacksonOption, so it contributes property naming and visibility
    //     only: the required set stays the one configured above.
    //
    // A Map's value schema is deliberately left underived. Bedrock accepts additionalProperties
    // only as false, and rejects a schema that carries it as a subschema, so typing map values
    // would trade an unconstrained map for a rejected request. A Map field reaches the model as a
    // bare object.
    //
    // A self-referencing class derives its own field as a reference back to the schema root,
    // whatever the required check says. Bedrock does not accept a recursive schema and rejects the
    // request before the model runs, so declaring the field Optional does not rescue it; only
    // flattening the recursion does.
    private static JsonNode toNativeSchema(Class<?> schemaClass) {
        SchemaGeneratorConfigBuilder configBuilder =
                new SchemaGeneratorConfigBuilder(
                                SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                        .with(new JacksonModule());
        configBuilder
                .forFields()
                .withRequiredCheck(field -> !Optional.class.equals(field.getRawMember().getType()));
        return new SchemaGenerator(configBuilder.build()).generateSchema(schemaClass);
    }

    private static boolean isRetryable(Exception e) {
        String msg = e.toString();
        return msg.contains("ThrottlingException")
                || msg.contains("ServiceUnavailableException")
                || msg.contains("ModelErrorException")
                || msg.contains("429")
                || msg.contains("503");
    }

    @Override
    public void close() throws Exception {
        this.client.close();
    }

    private String resolveModel(Map<String, Object> modelParams) {
        String model = modelParams != null ? (String) modelParams.get("model") : null;
        if (model == null || model.isBlank()) {
            model = this.defaultModel;
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("No model specified for Bedrock.");
        }
        return model;
    }

    /**
     * Merge consecutive TOOL messages into a single USER message with multiple toolResult content
     * blocks, as required by Bedrock Converse API.
     */
    private List<Message> mergeMessages(List<ChatMessage> msgs) {
        List<Message> result = new ArrayList<>();
        int i = 0;
        while (i < msgs.size()) {
            ChatMessage msg = msgs.get(i);
            if (msg.getRole() == MessageRole.TOOL) {
                List<ContentBlock> toolResultBlocks = new ArrayList<>();
                while (i < msgs.size() && msgs.get(i).getRole() == MessageRole.TOOL) {
                    ChatMessage toolMsg = msgs.get(i);
                    String toolCallId = (String) toolMsg.getExtraArgs().get("externalId");
                    toolResultBlocks.add(
                            ContentBlock.fromToolResult(
                                    ToolResultBlock.builder()
                                            .toolUseId(toolCallId)
                                            .content(
                                                    ToolResultContentBlock.builder()
                                                            .text(toolMsg.getContent())
                                                            .build())
                                            .build()));
                    i++;
                }
                result.add(
                        Message.builder()
                                .role(ConversationRole.USER)
                                .content(toolResultBlocks)
                                .build());
            } else {
                result.add(toBedrockMessage(msg));
                i++;
            }
        }
        return result;
    }

    private Message toBedrockMessage(ChatMessage msg) {
        switch (msg.getRole()) {
            case USER:
                return Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(msg.getContent()))
                        .build();
            case ASSISTANT:
                List<ContentBlock> blocks = new ArrayList<>();
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    blocks.add(ContentBlock.fromText(msg.getContent()));
                }
                if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                    for (Map<String, Object> call : msg.getToolCalls()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> fn = (Map<String, Object>) call.get("function");
                        String toolUseId = (String) call.get("id");
                        String name = (String) fn.get("name");
                        Object args = fn.get("arguments");
                        blocks.add(
                                ContentBlock.fromToolUse(
                                        ToolUseBlock.builder()
                                                .toolUseId(toolUseId)
                                                .name(name)
                                                .input(toDocument(args))
                                                .build()));
                    }
                }
                return Message.builder().role(ConversationRole.ASSISTANT).content(blocks).build();
            case TOOL:
                String toolCallId = (String) msg.getExtraArgs().get("externalId");
                return Message.builder()
                        .role(ConversationRole.USER)
                        .content(
                                ContentBlock.fromToolResult(
                                        ToolResultBlock.builder()
                                                .toolUseId(toolCallId)
                                                .content(
                                                        ToolResultContentBlock.builder()
                                                                .text(msg.getContent())
                                                                .build())
                                                .build()))
                        .build();
            default:
                throw new IllegalArgumentException(
                        "Unsupported role for Bedrock: " + msg.getRole());
        }
    }

    private software.amazon.awssdk.services.bedrockruntime.model.Tool toBedrockTool(Tool tool) {
        ToolMetadata meta = tool.getMetadata();
        ToolSpecification.Builder specBuilder =
                ToolSpecification.builder().name(meta.getName()).description(meta.getDescription());

        String schema = meta.getInputSchema();
        if (schema != null && !schema.isBlank()) {
            try {
                Map<String, Object> schemaMap =
                        MAPPER.readValue(schema, new TypeReference<Map<String, Object>>() {});
                specBuilder.inputSchema(ToolInputSchema.fromJson(toDocument(schemaMap)));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to parse tool schema.", e);
            }
        }

        return software.amazon.awssdk.services.bedrockruntime.model.Tool.builder()
                .toolSpec(specBuilder.build())
                .build();
    }

    private ChatMessage convertResponse(ConverseResponse response) {
        List<ContentBlock> outputBlocks = response.output().message().content();
        StringBuilder textContent = new StringBuilder();
        List<Map<String, Object>> toolCalls = new ArrayList<>();

        for (ContentBlock block : outputBlocks) {
            if (block.text() != null) {
                textContent.append(block.text());
            }
            if (block.toolUse() != null) {
                ToolUseBlock toolUse = block.toolUse();
                Map<String, Object> callMap = new LinkedHashMap<>();
                callMap.put("id", toolUse.toolUseId());
                callMap.put("type", "function");
                Map<String, Object> fnMap = new LinkedHashMap<>();
                fnMap.put("name", toolUse.name());
                fnMap.put("arguments", documentToMap(toolUse.input()));
                callMap.put("function", fnMap);
                callMap.put("original_id", toolUse.toolUseId());
                toolCalls.add(callMap);
            }
        }

        ChatMessage result = ChatMessage.assistant(textContent.toString());
        if (!toolCalls.isEmpty()) {
            result.setToolCalls(toolCalls);
        } else {
            // Only strip markdown fences for non-tool-call responses.
            result = ChatMessage.assistant(stripMarkdownFences(textContent.toString()));
        }
        return result;
    }

    /**
     * Strip markdown code fences from text responses. Some Bedrock models wrap JSON output in
     * markdown fences like {@code ```json ... ```}.
     *
     * <p>Only strips code fences; does not extract JSON from arbitrary text, as that could corrupt
     * normal prose responses containing braces.
     */
    static String stripMarkdownFences(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
            }
            return trimmed;
        }
        return trimmed;
    }

    @SuppressWarnings("unchecked")
    private Document toDocument(Object obj) {
        if (obj == null) {
            return Document.fromNull();
        }
        if (obj instanceof Map) {
            Map<String, Document> docMap = new LinkedHashMap<>();
            ((Map<String, Object>) obj).forEach((k, v) -> docMap.put(k, toDocument(v)));
            return Document.fromMap(docMap);
        }
        if (obj instanceof List) {
            return Document.fromList(
                    ((List<Object>) obj)
                            .stream().map(this::toDocument).collect(Collectors.toList()));
        }
        if (obj instanceof String) {
            return Document.fromString((String) obj);
        }
        if (obj instanceof Number) {
            return Document.fromNumber(SdkNumber.fromBigDecimal(new BigDecimal(obj.toString())));
        }
        if (obj instanceof Boolean) {
            return Document.fromBoolean((Boolean) obj);
        }
        return Document.fromString(obj.toString());
    }

    private Map<String, Object> documentToMap(Document doc) {
        if (doc == null || !doc.isMap()) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        doc.asMap().forEach((k, v) -> result.put(k, documentToObject(v)));
        return result;
    }

    private Object documentToObject(Document doc) {
        if (doc == null || doc.isNull()) return null;
        if (doc.isString()) return doc.asString();
        if (doc.isNumber()) return doc.asNumber().bigDecimalValue();
        if (doc.isBoolean()) return doc.asBoolean();
        if (doc.isList()) {
            return doc.asList().stream().map(this::documentToObject).collect(Collectors.toList());
        }
        if (doc.isMap()) return documentToMap(doc);
        return doc.toString();
    }
}
