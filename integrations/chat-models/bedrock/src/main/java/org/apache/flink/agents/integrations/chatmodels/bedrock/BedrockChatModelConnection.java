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

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.agents.api.RetryExecutor;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.messages.TokenUsage;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.integrations.chatmodels.common.PojoJsonSchemaGenerator;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.JsonSchemaDefinition;
import software.amazon.awssdk.services.bedrockruntime.model.OutputConfig;
import software.amazon.awssdk.services.bedrockruntime.model.OutputFormat;
import software.amazon.awssdk.services.bedrockruntime.model.OutputFormatStructure;
import software.amazon.awssdk.services.bedrockruntime.model.OutputFormatType;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;

import java.util.List;
import java.util.Map;
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
 * <p>Reasoning content preserves signed text and redacted data for history replay. Citation and
 * image/document content blocks are not yet supported.
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
                    "anthropic.claude-opus-4-6-v1",
                    "anthropic.claude-sonnet-4-6",
                    "deepseek.v3-v1:0",
                    "deepseek.v3.2",
                    "google.gemma-3-12b-it",
                    "google.gemma-3-27b-it",
                    "minimax.minimax-m2",
                    "minimax.minimax-m2.1",
                    "minimax.minimax-m2.5",
                    "mistral.mistral-large-3-675b-instruct",
                    "mistral.devstral-2-123b",
                    "mistral.magistral-small-2509",
                    "mistral.ministral-3-14b-instruct",
                    "mistral.ministral-3-3b-instruct",
                    "mistral.ministral-3-8b-instruct",
                    "mistral.voxtral-mini-3b-2507",
                    "mistral.voxtral-small-24b-2507",
                    "moonshot.kimi-k2-thinking",
                    "moonshotai.kimi-k2.5",
                    "nvidia.nemotron-nano-12b-v2",
                    "nvidia.nemotron-nano-3-30b",
                    "nvidia.nemotron-nano-9b-v2",
                    "nvidia.nemotron-super-3-120b",
                    "openai.gpt-oss-120b-1:0",
                    "openai.gpt-oss-20b-1:0",
                    "openai.gpt-5.6-luna",
                    "openai.gpt-oss-safeguard-120b",
                    "openai.gpt-oss-safeguard-20b",
                    "qwen.qwen3-235b-a22b-2507-v1:0",
                    "qwen.qwen3-32b-v1:0",
                    "qwen.qwen3-coder-30b-a3b-v1:0",
                    "qwen.qwen3-coder-480b-a35b-v1:0",
                    "qwen.qwen3-coder-next",
                    "qwen.qwen3-next-80b-a3b",
                    "writer.palmyra-vision-7b",
                    "zai.glm-4.7",
                    "zai.glm-4.7-flash",
                    "zai.glm-5");

    // A cross-Region inference profile id is a model id behind a geographic or global prefix, and
    // AWS documents structured output as working through cross-Region inference. The prefix set is
    // open-ended — the documentation names members by example and states that new profiles may
    // be created — so a leading segment is matched by shape rather than against a fixed list,
    // which would already have missed the documented us-gov. profiles. The charset excludes ":"
    // and "/", so no ARN can be shortened this way. The strip is attempted only after the id itself
    // fails to match, so a listed model id always matches as itself.
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

    /**
     * The {@code model} parameter, falling back to the model configured on the connection when the
     * call names none, which is how the request itself resolves the model it is issued against.
     *
     * <p>Resolving to nothing comes back null rather than raising the way {@code resolveModel}
     * does, because the capability predicate reports a null model not capable.
     */
    @Override
    protected String effectiveModelFor(Map<String, Object> modelParams) {
        String model = modelParams != null ? (String) modelParams.get("model") : null;
        if (model == null || model.isBlank()) {
            return this.defaultModel;
        }
        return model;
    }

    @Override
    public ChatResult chat(
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
    public ChatResult chat(
            List<ChatMessage> messages,
            List<Tool> tools,
            Map<String, Object> modelParams,
            Object outputSchema) {
        ConverseRequest request = buildRequest(messages, tools, modelParams, outputSchema);
        String modelId = request.modelId();

        ConverseResponse response =
                retryExecutor.execute(() -> client.converse(request), "BedrockConverse");

        return new ChatResult(
                BedrockChatUtils.convertResponse(response),
                modelId,
                null,
                response.usage() == null
                        ? null
                        : new TokenUsage(
                                response.usage().inputTokens().longValue(),
                                response.usage().outputTokens().longValue()),
                "max_tokens".equals(response.stopReasonAsString())
                        ? "length"
                        : ("guardrail_intervened".equals(response.stopReasonAsString())
                                ? "content_filter"
                                : response.stopReasonAsString()),
                null);
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
                        .messages(BedrockChatUtils.mergeMessages(conversationMsgs));

        if (!systemMsgs.isEmpty()) {
            requestBuilder.system(
                    systemMsgs.stream()
                            .map(m -> SystemContentBlock.builder().text(m.getText()).build())
                            .collect(Collectors.toList()));
        }

        if (tools != null && !tools.isEmpty()) {
            requestBuilder.toolConfig(
                    ToolConfiguration.builder()
                            .tools(
                                    tools.stream()
                                            .map(BedrockChatUtils::toBedrockTool)
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

    // Derives the JSON schema from a POJO class with the shared generator, adding no option. The
    // shared schema declares draft 2020-12, the dialect Bedrock validates a schema against.
    //
    // A Map's value schema is deliberately left underived. Bedrock accepts additionalProperties
    // only as false, and rejects a schema that carries it as a subschema, so typing map values
    // would trade an unconstrained map for a rejected request. A Map field reaches the model as a
    // bare object.
    //
    // A self-referencing class derives its own field as a reference back to the schema root,
    // whatever the shared generator's required check says. Bedrock does not accept a recursive
    // schema and rejects the request before the model runs, so declaring the field Optional does
    // not rescue it; only flattening the recursion does.
    private static JsonNode toNativeSchema(Class<?> schemaClass) {
        return PojoJsonSchemaGenerator.generate(schemaClass);
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
}
