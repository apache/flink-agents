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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.generator.impl.PropertySortUtils;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
 *       project} and {@code location}. The Vertex path wires the SDK builder flags and is
 *       smoke-tested at construction, but a full end-to-end run against Vertex is a follow-up.
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
 *             .addInitialArgument("model", "gemini-3.1-pro-preview")
 *             .build();
 *   }
 * }
 * }</pre>
 */
public class GeminiChatModelConnection extends BaseChatModelConnection {

    private static final Logger LOG = LoggerFactory.getLogger(GeminiChatModelConnection.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    // Models for which Google documents native structured output.
    // Source of truth: each model's Capabilities row at
    // https://ai.google.dev/gemini-api/docs/models
    //
    // Google added JSON Schema support to every actively supported Gemini model, and every model
    // predating that rollout has since been shut down, so the whole live text family is matched by
    // the family prefix rather than enumerated. A new Flash generation has shipped roughly monthly,
    // so an enumerated list would report not-capable for models that do work.
    //
    // Capability splits by output modality, not by generation: the image, speech, audio, Live,
    // transcription, video and embedding variants share the family prefix but expose no schema
    // parameter, so a modality marker is checked first and wins over the prefix. The markers also
    // cover gemini-2.5-flash-image, whose published capability row claims support that the service
    // rejects.
    //
    // A name outside the family — a Gemma model served by the same endpoint, a tuned model, or a
    // path-qualified form such as models/gemini-2.5-flash — reports not-capable and degrades to the
    // prompt fallback rather than failing at the provider.
    private static final String NATIVE_STRUCTURED_OUTPUT_FAMILY_PREFIX = "gemini-";
    private static final Set<String> NON_TEXT_MODALITY_MARKERS =
            Set.of("-image", "-tts", "-audio", "-live", "-transcribe", "-embedding", "-omni");

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
            // HttpOptions timeout is expressed in milliseconds. Compute in long to avoid int
            // overflow for large second values, then clamp to Integer.MAX_VALUE.
            long timeoutMs = (long) timeoutSeconds * 1000L;
            httpOptions.timeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
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

    /**
     * Whether Google documents native structured output for {@code effectiveModel}.
     *
     * <p>A {@code true} means the request carries the schema as a native {@code responseJsonSchema}
     * and the model is one Google documents as accepting one. What that buys is structural
     * conformance: the response is syntactically valid JSON whose object shape, key set and value
     * types follow the schema as the service interpreted it.
     *
     * <p>It does not buy conformance to every constraint the schema expresses. Gemini supports a
     * subset of JSON Schema and ignores the keywords outside that subset, server-side, without
     * reporting which. A {@code pattern}, {@code minLength}, {@code maxLength}, {@code
     * minProperties} or {@code maxProperties} in a derived schema is accepted by the request and
     * has no effect on the response, so well-formed JSON of the right shape is not evidence that
     * every declared constraint held. It does not buy semantically correct values either.
     *
     * <p>Capability is read from the model name alone, so a Gemini model released after this was
     * written is treated as capable. One that turns out not to support structured output fails at
     * the provider with {@code 400 INVALID_ARGUMENT}, "JSON mode is not enabled for this model",
     * rather than degrading quietly. A name outside the family — a Gemma model on the same
     * endpoint, a tuned model, or the path-qualified {@code models/gemini-2.5-flash} form the SDK
     * also accepts — reports not-capable and keeps the prompt-engineering fallback.
     */
    @Override
    protected boolean supportsNativeStructuredOutput(String effectiveModel) {
        if (effectiveModel == null || effectiveModel.isBlank()) {
            return false;
        }
        if (NON_TEXT_MODALITY_MARKERS.stream().anyMatch(effectiveModel::contains)) {
            return false;
        }
        return effectiveModel.startsWith(NATIVE_STRUCTURED_OUTPUT_FAMILY_PREFIX)
                && effectiveModel.length() > NATIVE_STRUCTURED_OUTPUT_FAMILY_PREFIX.length();
    }

    @Override
    public ChatMessage chat(
            List<ChatMessage> messages,
            List<org.apache.flink.agents.api.tools.Tool> tools,
            Map<String, Object> arguments) {
        return chat(messages, tools, arguments, null);
    }

    /**
     * Translates {@code outputSchema} into Gemini's native {@code responseJsonSchema} when it is a
     * POJO {@link Class}, the request carries no tools, and the effective model is one Google
     * documents structured-output support for. Any other combination sends no derived schema, so a
     * schema that cannot be sent natively degrades to the prompt-engineering fallback rather than
     * failing at the provider.
     *
     * <p>The tools condition is a provider constraint rather than a preference: outside a
     * documented preview, Gemini answers a request that combines function declarations with a JSON
     * response mime type with {@code 400 INVALID_ARGUMENT}, "Function calling with a response mime
     * type: 'application/json' is unsupported". The request therefore proceeds with its tools and
     * without the schema.
     */
    @Override
    public ChatMessage chat(
            List<ChatMessage> messages,
            List<org.apache.flink.agents.api.tools.Tool> tools,
            Map<String, Object> arguments,
            Object outputSchema) {
        Map<String, Object> args = arguments != null ? new HashMap<>(arguments) : new HashMap<>();

        Object modelObj = args.remove("model");
        String modelName = modelObj != null ? modelObj.toString() : this.defaultModel;
        if (modelName == null || modelName.isBlank()) {
            modelName = this.defaultModel;
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("model name must be provided for Gemini.");
        }

        // ChatModelAction emits TOOL messages with only `externalId` in extraArgs (matching the
        // sibling Anthropic/OpenAI connectors). Gemini's functionResponse part however requires the
        // function name. Build a tool-call-id -> name lookup from prior ASSISTANT turns so the TOOL
        // branch in convertToContent can recover the name from `externalId`.
        Map<String, String> toolCallIdToName = buildToolCallIdToNameMap(messages);

        try {
            List<Content> contents =
                    messages.stream()
                            .filter(m -> m.getRole() != MessageRole.SYSTEM)
                            .map(m -> convertToContent(m, toolCallIdToName))
                            .collect(Collectors.toList());

            GenerateContentConfig config =
                    buildConfig(messages, tools, args, modelName, outputSchema);

            GenerateContentResponse response =
                    client.models.generateContent(modelName, contents, config);
            ChatMessage result = convertResponse(response);

            recordUsage(result, modelName, response);

            return result;
        } catch (IllegalArgumentException e) {
            // Preserve the validation-error contract: surface IAE unwrapped, consistent with the
            // constructor.
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to call Gemini generateContent API.", e);
        }
    }

    // Package-visible for testing. Walks ASSISTANT messages and records every tool-call's
    // `original_id` (or `id`) -> function `name` mapping so TOOL turns can resolve their name from
    // `externalId` alone (which is what the runtime supplies).
    static Map<String, String> buildToolCallIdToNameMap(List<ChatMessage> messages) {
        Map<String, String> map = new HashMap<>();
        for (ChatMessage message : messages) {
            if (message.getRole() != MessageRole.ASSISTANT) {
                continue;
            }
            List<Map<String, Object>> toolCalls = message.getToolCalls();
            if (toolCalls == null) {
                continue;
            }
            for (Map<String, Object> call : toolCalls) {
                Object id = call.get("original_id");
                if (id == null) {
                    id = call.get("id");
                }
                Object function = call.get("function");
                if (id == null || !(function instanceof Map)) {
                    continue;
                }
                Object name = ((Map<?, ?>) function).get("name");
                if (name != null) {
                    map.put(id.toString(), name.toString());
                }
            }
        }
        return map;
    }

    // Package-visible for unit testing of the request-config assembly. modelName is passed
    // explicitly rather than read from arguments: chat() removes the model key before calling
    // this method, so the map never carries it here.
    GenerateContentConfig buildConfig(
            List<ChatMessage> messages,
            List<org.apache.flink.agents.api.tools.Tool> tools,
            Map<String, Object> arguments,
            String modelName,
            Object outputSchema) {
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

        @SuppressWarnings("unchecked")
        Map<String, Object> additionalKwargs =
                (Map<String, Object>) arguments.remove("additional_kwargs");
        if (additionalKwargs != null) {
            applyAdditionalKwargs(builder, additionalKwargs);
        }

        if (tools != null && !tools.isEmpty()) {
            builder.tools(List.of(convertTools(tools)));
        }

        // Native structured output applies only for a POJO Class schema; any other schema form,
        // such as a RowTypeInfo wrapped in OutputSchema, keeps the prompt-engineering fallback.
        // Nothing above writes either field this branch sets: the keys read directly are
        // temperature and max_output_tokens, and applyAdditionalKwargs recognizes only top_k,
        // top_p and stop_sequences, so there is no caller-supplied value to collide with.
        //
        // TODO(#912): the requested strategy is not visible here, so this re-check cannot tell an
        // explicit NATIVE request apart from one that merely resolved to native. A caller asking
        // for NATIVE on a schema form, a model, or a tool-carrying request this branch skips
        // therefore gets an unconstrained response instead of an error. Once strategy resolution is
        // wired up, NATIVE must either bypass this capability re-check or fail explicitly.
        if (outputSchema instanceof Class
                && (tools == null || tools.isEmpty())
                && supportsNativeStructuredOutput(modelName)) {
            builder.responseMimeType("application/json");
            builder.responseJsonSchema(toNativeJsonSchema((Class<?>) outputSchema));
        }

        return builder.build();
    }

    // Derives the JSON Schema Gemini's responseJsonSchema field expects from a POJO class. Gemini
    // supports a subset of JSON Schema and ignores the keywords outside that subset server-side
    // without reporting which, so every setting below is chosen against that published subset:
    //
    //   - DRAFT_2020_12 is the draft whose keywords Gemini's supported list names: $defs and
    //     prefixItems are listed, while the older drafts' definitions and tuple-form items are not.
    //   - The PLAIN_JSON preset keeps generation to fields. A preset is mandatory, and the
    //     generator's default one, FULL_DOCUMENTATION, surfaces getters as properties of their
    //     own, named after the accessor call, e.g. "getSummary()".
    //   - MAP_VALUES_AS_ADDITIONAL_PROPERTIES gives a Map its value schema, as an
    //     additionalProperties keyword carrying the declared value type. Dropped from this recipe,
    //     the map instead takes the additionalProperties:false of the option below and admits no
    //     entries at all.
    //   - FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT closes every object. No Gemini document states
    //     that a schema omitting the keyword is closed, and under ordinary JSON Schema semantics it
    //     is not, so without it a response may carry an undeclared key that the ObjectMapper
    //     read-back then rejects. If the service honors the keyword the object is closed; if it
    //     does not, the keyword is ignored like any other unsupported one, which is where omitting
    //     it would have left us. It applies to the enclosing object and leaves a Map's declared
    //     value schema alone.
    //   - Sorting fields before methods and applying no further comparison leaves properties in
    //     declaration order, which keeps the emitted document stable rather than alphabetized. It
    //     is not an ordering guarantee: Gemini's ordering knob is the non-standard propertyOrdering
    //     keyword, which this generator never emits.
    //   - The required check marks every field required except an Optional one. Gemini treats a
    //     field the schema does not list as required as optional and lets the model skip it, while
    //     marking everything required would force the fields a caller declared omissible.
    //   - The Jackson module makes the schema name properties the way Jackson names them. The
    //     response is read back into the same class with an ObjectMapper, so a property that
    //     @JsonProperty renames or @JsonIgnore drops has to be stated in the schema under the name
    //     the mapper reads, or a response that satisfies the schema still fails to deserialize. It
    //     is applied with no JacksonOption, so it contributes property naming, visibility and
    //     descriptions only: the required set and the property order stay the ones configured
    //     above.
    //
    // DEFINITION_FOR_MAIN_SCHEMA is deliberately absent. Without it a recursive type emits
    // {"$ref": "#"} at the recursion point, which is the form Google's own recursion example uses.
    // Enabling it instead produces a $defs entry referencing another $defs entry, a shape no
    // published Gemini example demonstrates.
    private static ObjectNode toNativeJsonSchema(Class<?> schemaClass) {
        SchemaGeneratorConfigBuilder configBuilder =
                new SchemaGeneratorConfigBuilder(
                                SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                        .with(Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES)
                        .with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT)
                        .with(new JacksonModule());
        configBuilder
                .forTypesInGeneral()
                .withPropertySorter(PropertySortUtils.SORT_PROPERTIES_FIELDS_BEFORE_METHODS);
        configBuilder
                .forFields()
                .withRequiredCheck(field -> !Optional.class.equals(field.getRawMember().getType()));
        ObjectNode schema = new SchemaGenerator(configBuilder.build()).generateSchema(schemaClass);
        stripRefSiblings(schema);
        return schema;
    }

    // Gemini states that a sub-schema setting $ref may set no other property except those starting
    // with a $, so every non-$ sibling is removed wherever a $ref appears. The generator writes a
    // referencing field's own keywords beside the reference, and two shapes are measured to produce
    // the pairing: a described field whose type is used more than once, which is extracted into
    // $defs and referenced there, and a described field that recurses into its own type, which
    // references the document root. What the strip removes is not only documentation: a Map's value
    // schema is written beside a $ref the same way a description is.
    //
    // The test is on the $ref value rather than on the presence of a member named $ref, because a
    // POJO may declare a property called $ref: its schema is then an object sitting under the
    // enclosing properties map, which makes that map itself carry a $ref member while being an
    // ordinary properties map rather than a reference. A reference's own $ref value is a URI
    // string, so requiring a textual value tells the two apart. Matching on presence alone would
    // delete every other declared property from such a map while required still listed them,
    // producing a document nothing can satisfy.
    private static void stripRefSiblings(JsonNode node) {
        if (node instanceof ObjectNode && node.path("$ref").isTextual()) {
            ObjectNode object = (ObjectNode) node;
            List<String> siblings = new ArrayList<>();
            object.fieldNames()
                    .forEachRemaining(
                            name -> {
                                if (!name.startsWith("$")) {
                                    siblings.add(name);
                                }
                            });
            siblings.forEach(object::remove);
        }
        node.forEach(GeminiChatModelConnection::stripRefSiblings);
    }

    // Package-visible for unit testing of the additional-kwargs forwarding.
    void applyAdditionalKwargs(GenerateContentConfig.Builder builder, Map<String, Object> kwargs) {
        for (Map.Entry<String, Object> entry : kwargs.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            switch (key) {
                case "top_k":
                    // Gemini's protocol defines topK as a float, despite the OpenAI/Anthropic
                    // convention of an integer.
                    if (value instanceof Number) {
                        builder.topK(((Number) value).floatValue());
                    }
                    break;
                case "top_p":
                    if (value instanceof Number) {
                        builder.topP(((Number) value).floatValue());
                    }
                    break;
                case "stop_sequences":
                    if (value instanceof List) {
                        List<String> stopSequences =
                                ((List<?>) value)
                                        .stream()
                                                .filter(Objects::nonNull)
                                                .map(Object::toString)
                                                .collect(Collectors.toList());
                        builder.stopSequences(stopSequences);
                    }
                    break;
                default:
                    // The Gemini SDK's GenerateContentConfig.Builder is AutoValue-generated and
                    // does not accept arbitrary body fields (unlike Anthropic/OpenAI which expose
                    // putAdditionalBodyProperty). Surface a warning so the user can see which key
                    // was dropped instead of silently mis-configuring sampling.
                    LOG.warn(
                            "Ignoring additional_kwargs.{}: not recognized by the Gemini connector"
                                    + " (supported keys: top_k, top_p, stop_sequences).",
                            key);
                    break;
            }
        }
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
        Part[] parts =
                messages.stream()
                        .filter(m -> m.getRole() == MessageRole.SYSTEM)
                        .map(m -> Part.fromText(Optional.ofNullable(m.getContent()).orElse("")))
                        .toArray(Part[]::new);
        return parts.length == 0 ? null : Content.fromParts(parts);
    }

    // Package-visible for unit testing of the message conversion.
    Content convertToContent(ChatMessage message, Map<String, String> toolCallIdToName) {
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
                String functionName = resolveToolFunctionName(message, toolCallIdToName);
                Map<String, Object> responseMap = new LinkedHashMap<>();
                responseMap.put("result", content);
                return Content.builder()
                        .role("user")
                        .parts(List.of(Part.fromFunctionResponse(functionName, responseMap)))
                        .build();

            default:
                throw new IllegalArgumentException("Unsupported role: " + role);
        }
    }

    private static String resolveToolFunctionName(
            ChatMessage toolMessage, Map<String, String> toolCallIdToName) {
        // 1. Honor an explicit `name` if the caller supplied one.
        Object explicit = toolMessage.getExtraArgs().get("name");
        if (explicit != null) {
            return explicit.toString();
        }
        // 2. Otherwise look up the function name via the tool-call id the runtime supplies as
        // `externalId` (set equal to the assistant turn's `original_id` by ToolCallAction).
        Object externalId = toolMessage.getExtraArgs().get("externalId");
        if (externalId != null && toolCallIdToName != null) {
            String name = toolCallIdToName.get(externalId.toString());
            if (name != null) {
                return name;
            }
        }
        throw new IllegalArgumentException(
                "Tool message must carry the function name: provide either 'name' in extraArgs, or"
                        + " an 'externalId' matching a prior ASSISTANT tool-call's id.");
    }

    // Package-visible for unit testing of the tool-call round-trip.
    Part convertToolCallToPart(Map<String, Object> call) {
        Map<String, Object> functionPayload = toMap(call.get("function"));
        String functionName = String.valueOf(functionPayload.get("name"));
        Map<String, Object> argsMap = toMap(functionPayload.get("arguments"));

        FunctionCall.Builder fcBuilder = FunctionCall.builder().name(functionName).args(argsMap);
        Object originalId = call.get("original_id");
        // A synthetic id exists only for runtime correlation (the API omitted the native id);
        // echoing a fabricated id back to Gemini would claim the model produced it.
        if (originalId != null && !Boolean.TRUE.equals(call.get("synthetic_id"))) {
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

        List<Candidate> candidates = response.candidates().orElseGet(List::of);
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "Gemini response did not contain any candidates (likely safety-blocked or"
                            + " filtered).");
        }
        // Let the SDK validate the finish reason: this raises IllegalArgumentException when the
        // model finished for an unexpected reason (SAFETY, MAX_TOKENS, RECITATION, …) instead of
        // silently returning a truncated or filtered message. The IAE is propagated unwrapped by
        // chat()'s catch block, matching the constructor's error contract.
        response.checkFinishReason();

        List<Part> parts = candidates.get(0).content().flatMap(Content::parts).orElseGet(List::of);

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
        } else {
            // The Gemini Developer API frequently omits functionCall.id. Downstream correlation
            // still needs one: ToolCallAction keys its result maps on `id` (two id-less parallel
            // calls would otherwise collide on the literal "null") and only propagates
            // `original_id` as the TOOL message's externalId, which is how the follow-up turn
            // recovers the function name for Gemini's functionResponse part. Synthesize an id for
            // the runtime round-trip and mark it so it is never echoed back to the API.
            String syntheticId = UUID.randomUUID().toString();
            toolCall.put("id", syntheticId);
            toolCall.put("original_id", syntheticId);
            toolCall.put("synthetic_id", Boolean.TRUE);
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
