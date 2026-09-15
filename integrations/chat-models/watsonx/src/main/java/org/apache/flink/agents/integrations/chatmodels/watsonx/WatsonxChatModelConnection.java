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
package org.apache.flink.agents.integrations.chatmodels.watsonx;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.Option;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.integrations.chatmodels.common.PojoJsonSchemaGenerator;
import org.apache.flink.annotation.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Chat model connection for the IBM watsonx.ai text chat REST API. */
public class WatsonxChatModelConnection extends BaseChatModelConnection {

    private static final Logger LOG = LoggerFactory.getLogger(WatsonxChatModelConnection.class);

    static final String DEFAULT_IAM_URL = "https://iam.cloud.ibm.com";
    static final String DEFAULT_API_VERSION = "2025-04-23";
    static final long DEFAULT_REQUEST_TIMEOUT_SEC = 120;
    static final int DEFAULT_MAX_RETRIES = 3;
    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(408, 429, 500, 502, 503, 504);

    private static final Set<String> CONTROL_PARAMS =
            Set.of(
                    "model",
                    "tool_choice",
                    "tool_choice_option",
                    "extract_reasoning",
                    "additional_kwargs");
    private static final Set<String> REQUEST_OWNED_PARAMS =
            Set.of("model_id", "messages", "tools", "project_id", "space_id");
    private static final Set<String> RESERVED_ADDITIONAL_KWARGS =
            Set.of(
                    "model",
                    "model_id",
                    "messages",
                    "tools",
                    "project_id",
                    "space_id",
                    "temperature",
                    "max_tokens",
                    "extract_reasoning",
                    "tool_choice",
                    "tool_choice_option");

    private static final Pattern[] REASONING_PATTERNS = {
        Pattern.compile("<think>(.*?)</think>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE),
        Pattern.compile("<analysis>(.*?)</analysis>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE),
        Pattern.compile("<reasoning>(.*?)</reasoning>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE),
        Pattern.compile(
                "```(?:think|reasoning|thought)\\s*\\n(.*?)\\n```",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE),
        Pattern.compile(
                "(?:^|\\n)Reasoning:\\s*(.*?)(?:\\n{2,}|$)",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE),
    };

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ObjectMapper LENIENT_MAPPER =
            JsonMapper.builder()
                    .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                    .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
                    .build();

    private final String url;
    private final String apiKey;
    private final String staticToken;
    private final String projectId;
    private final String spaceId;
    private final String apiVersion;
    private final String iamUrl;
    private final Duration requestTimeout;
    private final int maxRetries;

    private final HttpClient httpClient;

    private transient String cachedIamToken;
    private transient long iamTokenExpirationEpochSec;

    public WatsonxChatModelConnection(
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        this(descriptor, resourceContext, System::getenv);
    }

    @VisibleForTesting
    WatsonxChatModelConnection(
            ResourceDescriptor descriptor,
            ResourceContext resourceContext,
            Function<String, String> environmentLookup) {
        super(descriptor, resourceContext);

        this.url =
                trimTrailingSlash(
                        argumentOrEnv(descriptor, "url", "WATSONX_URL", environmentLookup));
        this.apiKey = argumentOrEnv(descriptor, "api_key", "WATSONX_API_KEY", environmentLookup);
        this.staticToken = argumentOrEnv(descriptor, "token", "WATSONX_TOKEN", environmentLookup);
        this.projectId =
                argumentOrEnv(descriptor, "project_id", "WATSONX_PROJECT_ID", environmentLookup);
        this.spaceId = argumentOrEnv(descriptor, "space_id", "WATSONX_SPACE_ID", environmentLookup);

        String apiVersion = normalize(descriptor.getArgument("api_version"));
        this.apiVersion = apiVersion != null ? apiVersion : DEFAULT_API_VERSION;
        String iamUrl = normalize(descriptor.getArgument("iam_url"));
        this.iamUrl = trimTrailingSlash(iamUrl != null ? iamUrl : DEFAULT_IAM_URL);
        Number requestTimeout = descriptor.getArgument("request_timeout");
        double requestTimeoutSeconds =
                requestTimeout != null ? requestTimeout.doubleValue() : DEFAULT_REQUEST_TIMEOUT_SEC;
        if (!Double.isFinite(requestTimeoutSeconds) || requestTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("request_timeout must be a positive finite number.");
        }
        this.requestTimeout =
                Duration.ofMillis(Math.max(1L, Math.round(requestTimeoutSeconds * 1000.0)));
        Number maxRetries = descriptor.getArgument("max_retries");
        this.maxRetries =
                maxRetries != null
                        ? requireInteger(maxRetries, "max_retries", 0)
                        : DEFAULT_MAX_RETRIES;

        if (this.url == null || this.url.isEmpty()) {
            throw new IllegalArgumentException(
                    "watsonx.ai url is not provided. Please pass the 'url' argument or set the"
                            + " 'WATSONX_URL' environment variable.");
        }
        if ((this.apiKey == null || this.apiKey.isEmpty())
                && (this.staticToken == null || this.staticToken.isEmpty())) {
            throw new IllegalArgumentException(
                    "watsonx.ai credentials are not provided. Please pass the 'api_key' or 'token'"
                            + " argument, or set the 'WATSONX_API_KEY' or 'WATSONX_TOKEN'"
                            + " environment variable.");
        }
        if (this.apiKey != null && this.staticToken != null) {
            throw new IllegalArgumentException(
                    "watsonx.ai api_key and token cannot both be provided. Please configure"
                            + " exactly one credential source.");
        }
        if ((this.projectId == null || this.projectId.isEmpty())
                && (this.spaceId == null || this.spaceId.isEmpty())) {
            throw new IllegalArgumentException(
                    "watsonx.ai project or space is not provided. Please pass the 'project_id' or"
                            + " 'space_id' argument, or set the 'WATSONX_PROJECT_ID' or"
                            + " 'WATSONX_SPACE_ID' environment variable.");
        }
        if (this.projectId != null
                && !this.projectId.isEmpty()
                && this.spaceId != null
                && !this.spaceId.isEmpty()) {
            throw new IllegalArgumentException(
                    "watsonx.ai project and space cannot both be provided. Please configure"
                            + " exactly one of 'project_id' or 'space_id'.");
        }

        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    static int requireInteger(Number value, String argumentName, int minimum) {
        double numericValue = value.doubleValue();
        if (!Double.isFinite(numericValue)
                || numericValue != Math.rint(numericValue)
                || numericValue < minimum
                || numericValue > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    argumentName
                            + " must be "
                            + (minimum == 0 ? "a non-negative" : "a positive")
                            + " integer.");
        }
        return (int) numericValue;
    }

    private static String argumentOrEnv(
            ResourceDescriptor descriptor,
            String argumentName,
            String envName,
            Function<String, String> environmentLookup) {
        String value = normalize(descriptor.getArgument(argumentName));
        if (value == null) {
            value = normalize(environmentLookup.apply(envName));
        }
        return value;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String trimTrailingSlash(String value) {
        if (value != null && value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * Whether watsonx.ai can constrain generation to a schema for {@code effectiveModel}.
     *
     * <p>Always {@code true}, and deliberately independent of the argument. The constraint is
     * applied by the serving runtime, through vLLM guided decoding, rather than by a per-model
     * capability. IBM states that chat API support requires the vLLM runtime, on its pages about
     * inferencing <i>custom</i> foundation models; it does not state in so many words that its own
     * provided models are served the same way. That last step rests instead on the chat request
     * body exposing vLLM's guided-decoding parameters verbatim.
     *
     * <p>There is no allowlist because IBM publishes no per-model structured-output signal to key
     * on: the foundation-model comparison table has columns for chat and tool interaction and none
     * for structured output, and the model-specs API exposes no such flag. A list written here
     * would encode a gate nobody documents and would report not-capable for models that do work.
     *
     * <p>This diverges from the base contract, which describes capability as model-dependent and
     * requires an unrecognized model to report {@code false}. That rule guards against failing at
     * the provider for a model whose capability is unknown; here capability is a property of the
     * endpoint rather than of the model, so there is no unknown to guard against.
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
     * Translates {@code outputSchema} into watsonx.ai's native {@code response_format} field when
     * it is a POJO {@link Class}. Any other schema form — notably a {@code RowTypeInfo} wrapped in
     * {@code OutputSchema} — has no native translation here and leaves the request unconstrained,
     * so that the prompt-engineering fallback still governs the response.
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
            final String modelName = (String) modelParams.get("model");
            final boolean extractReasoning =
                    Boolean.TRUE.equals(modelParams.get("extract_reasoning"));
            final ObjectNode payload = buildPayload(messages, tools, modelParams, outputSchema);
            if (projectId != null && !projectId.isEmpty()) {
                payload.put("project_id", projectId);
            } else {
                payload.put("space_id", spaceId);
            }

            final String requestBody = MAPPER.writeValueAsString(payload);
            String bearerToken = getBearerToken();
            HttpResponse<String> response =
                    sendWithRetry(buildChatRequest(requestBody, bearerToken));
            if ((response.statusCode() == 401 || response.statusCode() == 403) && apiKey != null) {
                LOG.warn(
                        "watsonx.ai returned status {}; refreshing the cached IAM token and"
                                + " retrying once",
                        response.statusCode());
                invalidateCachedIamToken(bearerToken);
                bearerToken = getBearerToken();
                response = sendWithRetry(buildChatRequest(requestBody, bearerToken));
            }
            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException(
                        String.format(
                                "watsonx.ai chat request failed with status %d: %s",
                                response.statusCode(), response.body()));
            }

            final ChatMessage chatMessage =
                    parseResponse(MAPPER.readTree(response.body()), modelName);
            if (extractReasoning) {
                final String[] parts = extractReasoning(chatMessage.getContent());
                chatMessage.setContent(parts[0]);
                if (parts[1] != null) {
                    chatMessage.getExtraArgs().put("reasoning", parts[1]);
                }
            }
            return chatMessage;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while calling watsonx.ai.", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private HttpRequest buildChatRequest(String requestBody, String bearerToken) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url + "/ml/v1/text/chat?version=" + apiVersion))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
    }

    /**
     * Sends the request, retrying HTTP 408, 429, 500, 502, 503, and 504 responses and I/O errors up
     * to {@code max_retries} times with capped exponential backoff, honoring {@code Retry-After}.
     */
    private HttpResponse<String> sendWithRetry(HttpRequest request)
            throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            try {
                final HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (attempt >= maxRetries || !isRetryableStatus(response.statusCode())) {
                    return response;
                }
                final long delayMillis =
                        retryDelayMillis(
                                attempt, response.headers().firstValue("Retry-After").orElse(null));
                LOG.warn(
                        "watsonx.ai request to {} returned status {}; retry {}/{} in {} ms",
                        request.uri().getPath(),
                        response.statusCode(),
                        attempt + 1,
                        maxRetries,
                        delayMillis);
                Thread.sleep(delayMillis);
            } catch (IOException e) {
                if (attempt >= maxRetries) {
                    throw e;
                }
                final long delayMillis = retryDelayMillis(attempt, null);
                LOG.warn(
                        "watsonx.ai request to {} failed ({}); retry {}/{} in {} ms",
                        request.uri().getPath(),
                        e.toString(),
                        attempt + 1,
                        maxRetries,
                        delayMillis);
                Thread.sleep(delayMillis);
            }
        }
    }

    @VisibleForTesting
    static boolean isRetryableStatus(int status) {
        return RETRYABLE_STATUS_CODES.contains(status);
    }

    @VisibleForTesting
    static long retryDelayMillis(int attempt, String retryAfterHeader) {
        long backoffMillis = Math.min(1000L << attempt, 10_000L);
        if (retryAfterHeader != null) {
            try {
                long retryAfterMillis =
                        Math.min(Long.parseLong(retryAfterHeader.trim()) * 1000L, 30_000L);
                backoffMillis = Math.max(backoffMillis, retryAfterMillis);
            } catch (NumberFormatException ignored) {
                // Retry-After may be an HTTP date; fall back to exponential backoff.
            }
        }
        return backoffMillis;
    }

    @VisibleForTesting
    static String[] extractReasoning(String content) {
        if (content == null || content.isEmpty()) {
            return new String[] {"", null};
        }
        final List<String> reasoningChunks = new ArrayList<>();
        String cleaned = content;
        for (Pattern pattern : REASONING_PATTERNS) {
            final Matcher matcher = pattern.matcher(cleaned);
            final StringBuilder rest = new StringBuilder();
            boolean found = false;
            int position = 0;
            while (matcher.find()) {
                final String chunk = matcher.group(1).trim();
                if (!chunk.isEmpty()) {
                    reasoningChunks.add(chunk);
                }
                rest.append(cleaned, position, matcher.start());
                position = matcher.end();
                found = true;
            }
            if (found) {
                rest.append(cleaned, position, cleaned.length());
                cleaned = rest.toString();
            }
        }
        if (reasoningChunks.isEmpty()) {
            return new String[] {content, null};
        }
        final String reasoning = String.join("\n\n", reasoningChunks);
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n").replaceAll(" {2,}", " ").trim();
        return new String[] {cleaned, reasoning};
    }

    @VisibleForTesting
    ObjectNode buildPayload(
            List<ChatMessage> messages,
            List<Tool> tools,
            Map<String, Object> modelParams,
            Object outputSchema) {
        final ObjectNode payload = MAPPER.createObjectNode();
        final String modelName = (String) modelParams.get("model");
        payload.put("model_id", modelName);
        payload.set("messages", convertMessages(messages));

        if (tools != null && !tools.isEmpty()) {
            payload.set("tools", convertTools(tools));
        }
        final Object toolChoice = modelParams.get("tool_choice");
        if (toolChoice != null) {
            payload.set("tool_choice", MAPPER.valueToTree(toolChoice));
        }
        final Object toolChoiceOption = modelParams.get("tool_choice_option");
        if (toolChoiceOption != null) {
            payload.put("tool_choice_option", toolChoiceOption.toString());
        }

        @SuppressWarnings("unchecked")
        final Map<String, Object> additionalKwargs =
                (Map<String, Object>) modelParams.get("additional_kwargs");

        // Native structured output applies only for a POJO Class schema; any other schema form,
        // such as a RowTypeInfo wrapped in OutputSchema, keeps the prompt-engineering fallback.
        // The derived schema is a request field of its own rather than a sampling option, so it is
        // written at the payload root. When no native translation applies the key stays absent
        // rather than being written as a null, which would still be a present field on the wire.
        //
        // TODO(#912): the requested strategy is not visible here, so a request that explicitly
        // asked for NATIVE cannot be told apart from one that merely resolved to it. Capability is
        // unconditional on this connection, so the schema form is the only way through to an
        // unconstrained response: a caller who asked for NATIVE and passed a schema this branch
        // cannot translate gets one silently. Once strategy resolution is wired up, NATIVE must
        // either bypass this re-check or fail explicitly.
        if (outputSchema instanceof Class && supportsNativeStructuredOutput(modelName)) {
            // A caller reaches the same payload field through either channel. Only the branch that
            // actually sends a derived schema may reject the caller's value; every path that skips
            // it leaves that value untouched. A null is not a conflict, because both write loops
            // below skip null values and it therefore never reaches the payload.
            final boolean callerSuppliedResponseFormat =
                    modelParams.get("response_format") != null
                            || (additionalKwargs != null
                                    && additionalKwargs.get("response_format") != null);
            if (callerSuppliedResponseFormat) {
                throw new IllegalArgumentException(
                        "The "
                                + ((Class<?>) outputSchema).getSimpleName()
                                + " output schema is sent as response_format, so response_format"
                                + " must not also be set in the model parameters or in"
                                + " additional_kwargs. Remove that value, or omit the output"
                                + " schema to set response_format directly.");
            }
            payload.set("response_format", toNativeResponseFormat((Class<?>) outputSchema));
        }

        if (additionalKwargs != null) {
            final Set<String> collisions = new java.util.HashSet<>(additionalKwargs.keySet());
            collisions.retainAll(RESERVED_ADDITIONAL_KWARGS);
            if (!collisions.isEmpty()) {
                throw new IllegalArgumentException(
                        "additional_kwargs must not contain reserved typed fields: "
                                + collisions
                                + ". Set these via the corresponding Setup field instead.");
            }
            additionalKwargs.forEach(
                    (key, value) -> {
                        if (value != null) {
                            payload.set(key, MAPPER.valueToTree(value));
                        }
                    });
        }

        final Set<String> collisions = new java.util.HashSet<>(modelParams.keySet());
        collisions.retainAll(REQUEST_OWNED_PARAMS);
        if (!collisions.isEmpty()) {
            throw new IllegalArgumentException(
                    "model parameters must not contain framework-owned fields: " + collisions);
        }
        for (Map.Entry<String, Object> entry : modelParams.entrySet()) {
            if (!CONTROL_PARAMS.contains(entry.getKey()) && entry.getValue() != null) {
                payload.set(entry.getKey(), MAPPER.valueToTree(entry.getValue()));
            }
        }
        return payload;
    }

    // Derives the response_format value watsonx.ai expects from a POJO class. The schema inside it
    // comes from the shared generator with one option added:
    //
    //   - MAP_VALUES_AS_ADDITIONAL_PROPERTIES gives a Map its value schema. Without it the map
    //     admits any value, and a response that satisfies the schema can still fail to deserialize
    //     into the declared value type at the caller.
    //
    // This connection returns the content as a string and never deserializes into the schema
    // class, so a property or enum constant the schema states under a name Jackson does not read
    // produces a response that satisfies the schema and still fails to read back, at the caller
    // rather than here.
    //
    // Two settings are deliberately absent:
    //
    //   - FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT is omitted for cross-language parity:
    //     pydantic's plain schema does not emit additionalProperties: false, so emitting it on the
    //     Java side alone would make the two documents disagree. The endpoint does not require it,
    //     whether or not strict is set.
    //   - DEFINITION_FOR_MAIN_SCHEMA is left off, which is victools' default. Turning it on only
    //     moves the document root into $defs and makes the root a $ref, and nothing establishes
    //     that watsonx benefits from either shape, so there is no reason to deviate. It does not
    //     gate recursion: a self-referential type generates either way, as {"child": {"$ref": "#"}}
    //     with the option off, and any type used twice is extracted into $defs regardless. A
    //     recursive POJO reaches the provider unguarded either way, and this setting cannot
    //     change that.
    private static ObjectNode toNativeResponseFormat(Class<?> schemaClass) {
        final ObjectNode responseFormat = MAPPER.createObjectNode();
        responseFormat.put("type", "json_schema");
        final ObjectNode jsonSchema = responseFormat.putObject("json_schema");
        jsonSchema.put("name", schemaClass.getSimpleName());
        jsonSchema.set(
                "schema",
                PojoJsonSchemaGenerator.generate(
                        schemaClass, Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES));
        jsonSchema.put("strict", true);
        return responseFormat;
    }

    /**
     * Converts framework chat messages to the watsonx.ai (OpenAI-compatible) message format.
     *
     * <ul>
     *   <li>SYSTEM/USER messages carry {@code role} and {@code content}.
     *   <li>ASSISTANT messages may carry {@code tool_calls} with JSON string arguments.
     *   <li>TOOL messages carry {@code tool_call_id} referencing the original call, taken from the
     *       {@code externalId} entry of the message extra args.
     * </ul>
     */
    @VisibleForTesting
    static ArrayNode convertMessages(List<ChatMessage> messages) {
        final ArrayNode result = MAPPER.createArrayNode();
        for (ChatMessage message : messages) {
            final ObjectNode node = MAPPER.createObjectNode();
            final MessageRole role = message.getRole();
            switch (role) {
                case SYSTEM:
                case USER:
                    node.put("role", role.name().toLowerCase());
                    node.put("content", message.getContent());
                    break;
                case ASSISTANT:
                    node.put("role", "assistant");
                    if (message.getContent() != null && !message.getContent().isEmpty()) {
                        node.put("content", message.getContent());
                    }
                    final List<Map<String, Object>> toolCalls = message.getToolCalls();
                    if (toolCalls != null && !toolCalls.isEmpty()) {
                        node.set("tool_calls", convertToolCalls(toolCalls));
                    }
                    break;
                case TOOL:
                    final Object externalId = message.getExtraArgs().get("externalId");
                    if (externalId == null) {
                        throw new IllegalArgumentException(
                                "Tool message must have 'externalId' in extra args.");
                    }
                    node.put("role", "tool");
                    node.put("content", message.getContent());
                    node.put("tool_call_id", externalId.toString());
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported role: " + role);
            }
            result.add(node);
        }
        return result;
    }

    private static ArrayNode convertToolCalls(List<Map<String, Object>> toolCalls) {
        final ArrayNode result = MAPPER.createArrayNode();
        for (Map<String, Object> toolCall : toolCalls) {
            final Object originalId = toolCall.get("original_id");
            final Object id = originalId != null ? originalId : toolCall.get("id");
            if (id == null) {
                throw new IllegalArgumentException(
                        "Tool call must have either 'original_id' or 'id' field.");
            }

            @SuppressWarnings("unchecked")
            final Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
            final Object arguments = function.get("arguments");
            final String argumentsJson;
            try {
                argumentsJson =
                        arguments instanceof String
                                ? (String) arguments
                                : MAPPER.writeValueAsString(arguments);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            final ObjectNode node = MAPPER.createObjectNode();
            node.put("id", id.toString());
            node.put("type", "function");
            final ObjectNode functionNode = node.putObject("function");
            functionNode.put("name", (String) function.get("name"));
            functionNode.put("arguments", argumentsJson);
            result.add(node);
        }
        return result;
    }

    @VisibleForTesting
    static ArrayNode convertTools(List<Tool> tools) {
        final ArrayNode result = MAPPER.createArrayNode();
        try {
            for (Tool tool : tools) {
                final ObjectNode node = MAPPER.createObjectNode();
                node.put("type", "function");
                final ObjectNode functionNode = node.putObject("function");
                functionNode.put("name", tool.getName());
                functionNode.put("description", tool.getDescription());
                functionNode.set(
                        "parameters", MAPPER.readTree(tool.getMetadata().getInputSchema()));
                result.add(node);
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Parses the watsonx.ai chat response. When the provider reports a finish reason it is carried
     * verbatim in {@code extraArgs} under {@code finish_reason}, including values outside the
     * documented set, and the entry is absent when the provider reports none.
     */
    @VisibleForTesting
    static ChatMessage parseResponse(JsonNode response, String modelName) {
        final JsonNode choice = response.required("choices").get(0);
        final JsonNode responseMessage = choice.required("message");

        final JsonNode finishReasonNode = choice.get("finish_reason");
        String finishReason = null;
        if (finishReasonNode != null && !finishReasonNode.isNull()) {
            finishReason = finishReasonNode.asText();
            if (!"stop".equals(finishReason) && !"tool_calls".equals(finishReason)) {
                LOG.warn(
                        "watsonx.ai chat for model {} finished with reason '{}'; the response"
                                + " may be truncated or incomplete",
                        modelName,
                        finishReason);
            }
        }

        final JsonNode contentNode = responseMessage.get("content");
        final String content =
                contentNode != null && !contentNode.isNull() ? contentNode.asText() : "";
        final ChatMessage chatMessage = ChatMessage.assistant(content);

        final JsonNode toolCallsNode = responseMessage.get("tool_calls");
        if (toolCallsNode != null && toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
            final List<Map<String, Object>> toolCalls = new java.util.ArrayList<>();
            for (JsonNode toolCallNode : toolCallsNode) {
                final String id = toolCallNode.required("id").asText();
                final JsonNode functionNode = toolCallNode.required("function");
                final Map<String, Object> arguments =
                        parseToolArguments(functionNode.get("arguments"));
                toolCalls.add(
                        Map.of(
                                "id",
                                id,
                                "original_id",
                                id,
                                "type",
                                "function",
                                "function",
                                Map.of(
                                        "name",
                                        functionNode.required("name").asText(),
                                        "arguments",
                                        arguments)));
            }
            chatMessage.setToolCalls(toolCalls);
        }

        final JsonNode usage = response.get("usage");
        final boolean hasUsageMetadata =
                modelName != null && !modelName.isBlank() && usage != null && !usage.isNull();
        if (hasUsageMetadata || finishReason != null) {
            final Map<String, Object> extraArgs = new HashMap<>(chatMessage.getExtraArgs());
            if (hasUsageMetadata) {
                extraArgs.put("model_name", modelName);
                extraArgs.put("promptTokens", usage.path("prompt_tokens").asLong(0));
                extraArgs.put("completionTokens", usage.path("completion_tokens").asLong(0));
            }
            if (finishReason != null) {
                extraArgs.put("finish_reason", finishReason);
            }
            chatMessage.setExtraArgs(extraArgs);
        }

        return chatMessage;
    }

    /**
     * Parses model-emitted tool call arguments into a map, which is the format the framework's tool
     * execution expects.
     *
     * <p>Models do not always return arguments as a clean JSON object string: some double-encode
     * the JSON, and some (notably smaller models) emit single-quoted or unquoted pseudo-JSON. This
     * method tolerates those variants and throws a descriptive error (including the raw value) when
     * the arguments cannot be interpreted as an object.
     */
    @VisibleForTesting
    static Map<String, Object> parseToolArguments(JsonNode argumentsNode) {
        if (argumentsNode == null || argumentsNode.isNull()) {
            return Map.of();
        }
        if (argumentsNode.isObject()) {
            return MAPPER.convertValue(argumentsNode, new TypeReference<Map<String, Object>>() {});
        }
        if (argumentsNode.isTextual()) {
            String text = argumentsNode.asText().trim();
            if (text.isEmpty()) {
                return Map.of();
            }
            // Unwrap up to a few levels of string-encoding ("{\"a\": 1}" or "\"{\\\"a\\\": 1}\"").
            for (int i = 0; i < 3; i++) {
                final JsonNode parsed;
                try {
                    parsed = LENIENT_MAPPER.readTree(text);
                } catch (Exception e) {
                    break;
                }
                if (parsed.isObject()) {
                    return MAPPER.convertValue(parsed, new TypeReference<Map<String, Object>>() {});
                }
                if (parsed.isTextual()) {
                    text = parsed.asText().trim();
                    continue;
                }
                break;
            }
        }
        throw new RuntimeException(
                "Failed to parse tool call arguments returned by watsonx.ai as a JSON object: "
                        + argumentsNode);
    }

    private synchronized String getBearerToken() {
        if (staticToken != null && !staticToken.isEmpty()) {
            return staticToken;
        }
        final long nowEpochSec = System.currentTimeMillis() / 1000;
        // Refresh 60 seconds before the cached token expires.
        if (cachedIamToken != null && nowEpochSec < iamTokenExpirationEpochSec - 60) {
            return cachedIamToken;
        }

        try {
            final String form =
                    "grant_type=urn%3Aibm%3Aparams%3Aoauth%3Agrant-type%3Aapikey&apikey="
                            + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
            final HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(iamUrl + "/identity/token"))
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(form))
                            .build();
            final HttpResponse<String> response = sendWithRetry(request);
            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException(
                        String.format(
                                "IAM token request failed with status %d: %s",
                                response.statusCode(), response.body()));
            }
            final JsonNode tokenResponse = MAPPER.readTree(response.body());
            cachedIamToken = tokenResponse.required("access_token").asText();
            iamTokenExpirationEpochSec =
                    nowEpochSec + tokenResponse.path("expires_in").asLong(3600);
            return cachedIamToken;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while obtaining a watsonx.ai IAM token.", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private synchronized void invalidateCachedIamToken(String rejectedToken) {
        if (rejectedToken.equals(cachedIamToken)) {
            cachedIamToken = null;
            iamTokenExpirationEpochSec = 0;
        }
    }
}
