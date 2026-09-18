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
package org.apache.flink.agents.integrations.embeddingmodels.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonField;
import com.openai.core.JsonString;
import com.openai.core.JsonValue;
import com.openai.core.Timeout;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.models.embeddings.EmbeddingValue;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelConnection;
import org.apache.flink.agents.api.embedding.model.EmbeddingModelUtils;
import org.apache.flink.agents.api.embedding.model.EmbeddingResult;
import org.apache.flink.agents.api.embedding.model.EmbeddingTokenUsage;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Embedding model connection backed by the OpenAI Embeddings API.
 *
 * <p>Argument names mirror the Python {@code OpenAIEmbeddingModelConnection}:
 *
 * <ul>
 *   <li>{@code api_key} (required): OpenAI API key.
 *   <li>{@code base_url}: API base URL, default {@code https://api.openai.com/v1}.
 *   <li>{@code request_timeout}: per-attempt request timeout in seconds, default {@code 30}; {@code
 *       0} disables the timeout, as in the other OpenAI connections.
 *   <li>{@code max_retries}: SDK retry count for failed requests, default {@code 3}.
 *   <li>{@code organization}, {@code project}: optional OpenAI organization and project IDs.
 * </ul>
 *
 * <p>Per-request parameters come from {@link OpenAIEmbeddingModelSetup#getParameters()} merged with
 * any per-call parameters: {@code model} (required), {@code encoding_format} ({@code "float"} or
 * {@code "base64"}), {@code dimensions}, {@code user}, and {@code additional_kwargs}, a map whose
 * entries are sent as extra request body properties. Its keys must not collide with the typed
 * request fields ({@code model}, {@code input}, {@code encoding_format}, {@code dimensions}, {@code
 * user}); entries with a {@code null} value are omitted. Blank strings are treated as absent, and
 * other per-call keys are ignored. A batch of texts is sent as one request and the embeddings are
 * returned in input order. Token usage reported by the API is surfaced through {@link
 * #embedWithUsage}; servers that omit it yield a {@code null} usage.
 */
public class OpenAIEmbeddingModelConnection extends BaseEmbeddingModelConnection {

    static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 30;
    static final int DEFAULT_MAX_RETRIES = 3;
    /** The Java SDK stores timeouts as int milliseconds. */
    static final BigDecimal MAX_REQUEST_TIMEOUT_SECONDS = new BigDecimal("2147483.647");

    static final String MODEL = "model";
    static final String ENCODING_FORMAT = "encoding_format";
    static final String DIMENSIONS = "dimensions";
    static final String USER = "user";
    /** Setup parameter carrying extra request body properties. */
    static final String ADDITIONAL_KWARGS = "additional_kwargs";

    static final Set<String> ENCODING_FORMATS = Set.of("float", "base64");
    /**
     * Request fields owned by the typed builder calls; {@code additional_kwargs} may not set them.
     */
    static final Set<String> RESERVED_ADDITIONAL_KWARGS =
            Set.of(MODEL, "input", ENCODING_FORMAT, DIMENSIONS, USER);

    private final OpenAIClient client;

    public OpenAIEmbeddingModelConnection(
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.client = ClientConfig.parse(descriptor).buildClient();
    }

    // visible for testing
    OpenAIEmbeddingModelConnection(
            ResourceDescriptor descriptor, ResourceContext resourceContext, OpenAIClient client) {
        super(descriptor, resourceContext);
        this.client = client;
    }

    /** The parsed connection arguments; tests assert on them through {@link #parse}. */
    static final class ClientConfig {
        final String apiKey;
        final String baseUrl;
        final Duration requestTimeout;
        final int maxRetries;
        final String organization;
        final String project;

        private ClientConfig(
                String apiKey,
                String baseUrl,
                Duration requestTimeout,
                int maxRetries,
                String organization,
                String project) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.requestTimeout = requestTimeout;
            this.maxRetries = maxRetries;
            this.organization = organization;
            this.project = project;
        }

        /** Blank string arguments are treated as absent. */
        static ClientConfig parse(ResourceDescriptor descriptor) {
            String apiKey = requireString(descriptor.getArgument("api_key"), "api_key");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("api_key should not be null or empty.");
            }
            String baseUrl = requireString(descriptor.getArgument("base_url"), "base_url");
            return new ClientConfig(
                    apiKey,
                    baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl,
                    parseRequestTimeout(descriptor.getArgument("request_timeout")),
                    parseMaxRetries(descriptor.getArgument("max_retries")),
                    blankToNull(
                            requireString(descriptor.getArgument("organization"), "organization")),
                    blankToNull(requireString(descriptor.getArgument("project"), "project")));
        }

        OpenAIClient buildClient() {
            OpenAIOkHttpClient.Builder builder =
                    new OpenAIOkHttpClient.Builder()
                            .apiKey(apiKey)
                            .baseUrl(baseUrl)
                            .timeout(
                                    Timeout.builder()
                                            .connect(requestTimeout)
                                            .read(requestTimeout)
                                            .write(requestTimeout)
                                            .request(requestTimeout)
                                            .build())
                            .maxRetries(maxRetries);
            if (organization != null) {
                builder.organization(organization);
            }
            if (project != null) {
                builder.project(project);
            }
            return builder.build();
        }
    }

    /** Blank string arguments are treated as absent. */
    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Parses {@code request_timeout} seconds with the same rules as the OpenAI chat connection. */
    static Duration parseRequestTimeout(Object raw) {
        if (raw == null) {
            return Duration.ofSeconds(DEFAULT_REQUEST_TIMEOUT_SECONDS);
        }
        BigDecimal seconds = toBigDecimal(raw, "request_timeout");
        if (seconds.signum() < 0) {
            throw new IllegalArgumentException("request_timeout must be >= 0, got: " + raw);
        }
        if (seconds.compareTo(MAX_REQUEST_TIMEOUT_SECONDS) > 0) {
            throw new IllegalArgumentException(
                    "request_timeout exceeds the SDK maximum of "
                            + MAX_REQUEST_TIMEOUT_SECONDS.toPlainString()
                            + " seconds, got: "
                            + raw);
        }
        // Millisecond precision; positive values round up so a valid timeout never becomes zero,
        // which would disable it. Exactly 0 disables the timeout, like the chat connection.
        BigInteger millis =
                seconds.multiply(BigDecimal.valueOf(1_000L))
                        .setScale(0, RoundingMode.CEILING)
                        .toBigIntegerExact();
        return Duration.ofMillis(millis.longValueExact());
    }

    static int parseMaxRetries(Object raw) {
        if (raw == null) {
            return DEFAULT_MAX_RETRIES;
        }
        BigDecimal value = toBigDecimal(raw, "max_retries");
        try {
            BigInteger retries = value.toBigIntegerExact();
            if (retries.signum() < 0
                    || retries.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                throw new IllegalArgumentException(
                        "max_retries must be a non-negative integer, got: " + raw);
            }
            return retries.intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "max_retries must be a non-negative integer, got: " + raw, e);
        }
    }

    private static BigDecimal toBigDecimal(Object raw, String argumentName) {
        if (!(raw instanceof Number)) {
            throw new IllegalArgumentException(argumentName + " must be a number, got: " + raw);
        }
        Number number = (Number) raw;
        if ((number instanceof Double || number instanceof Float)
                && !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException(argumentName + " must be finite, got: " + raw);
        }
        try {
            return new BigDecimal(number.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    argumentName + " must be a finite number, got: " + raw, e);
        }
    }

    // ---- request parameter parsing, shared by the setup (fail fast) and buildParams (per call)

    static String requireString(Object value, String argumentName) {
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException(
                    argumentName + " must be a string, got: " + value.getClass().getSimpleName());
        }
        return (String) value;
    }

    static String parseModel(Object value) {
        String model = requireString(value, MODEL);
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException(
                    "OpenAI embedding requires a non-empty 'model' (setup or per-call parameter).");
        }
        return model;
    }

    static String parseEncodingFormat(Object value) {
        String format = requireString(value, ENCODING_FORMAT);
        if (format == null || format.isBlank()) {
            return OpenAIEmbeddingModelSetup.DEFAULT_ENCODING_FORMAT;
        }
        if (!ENCODING_FORMATS.contains(format)) {
            throw new IllegalArgumentException(
                    ENCODING_FORMAT + " must be one of " + ENCODING_FORMATS + ", got: " + format);
        }
        return format;
    }

    static Integer parseDimensions(Object value) {
        if (value == null) {
            return null;
        }
        BigDecimal number = toBigDecimal(value, DIMENSIONS);
        try {
            BigInteger dims = number.toBigIntegerExact();
            if (dims.signum() <= 0 || dims.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                throw new IllegalArgumentException(
                        DIMENSIONS + " must be a positive integer, got: " + value);
            }
            return dims.intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    DIMENSIONS + " must be a positive integer, got: " + value, e);
        }
    }

    static Map<String, Object> parseAdditionalKwargs(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value == null) {
            return result;
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(
                    ADDITIONAL_KWARGS + " must be a map, got: " + value.getClass().getSimpleName());
        }
        Set<String> collisions = null;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() == null || entry.getKey().toString().isBlank()) {
                throw new IllegalArgumentException(ADDITIONAL_KWARGS + " contains an empty key.");
            }
            String key = entry.getKey().toString();
            if (RESERVED_ADDITIONAL_KWARGS.contains(key)) {
                if (collisions == null) {
                    collisions = new TreeSet<>();
                }
                collisions.add(key);
            }
            result.put(key, entry.getValue());
        }
        if (collisions != null) {
            throw new IllegalArgumentException(
                    ADDITIONAL_KWARGS
                            + " must not contain the typed request fields "
                            + collisions
                            + "; set them through the corresponding setup argument instead.");
        }
        return result;
    }

    @Override
    public float[] embed(String text, Map<String, Object> parameters) {
        return embedWithUsage(text, parameters).getEmbeddings();
    }

    @Override
    public List<float[]> embed(List<String> texts, Map<String, Object> parameters) {
        return embedWithUsage(texts, parameters).getEmbeddings();
    }

    @Override
    public EmbeddingResult<float[]> embedWithUsage(String text, Map<String, Object> parameters) {
        EmbeddingResult<List<float[]>> result =
                embedWithUsage(Collections.singletonList(text), parameters);
        return new EmbeddingResult<>(result.getEmbeddings().get(0), result.getTokenUsage());
    }

    @Override
    public EmbeddingResult<List<float[]>> embedWithUsage(
            List<String> texts, Map<String, Object> parameters) {
        if (texts == null) {
            throw new IllegalArgumentException("texts must not be null.");
        }
        List<String> inputs = texts;
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i) == null) {
                throw new IllegalArgumentException(
                        "Text at index " + i + " is null; every input must be a string.");
            }
        }
        // Validate the parameters even for an empty batch so a bad per-call override is not
        // deferred to the first non-empty batch (the Python connection validates first too).
        EmbeddingCreateParams params =
                buildParams(inputs, parameters == null ? Collections.emptyMap() : parameters);
        if (inputs.isEmpty()) {
            return new EmbeddingResult<>(new ArrayList<>(), null);
        }
        CreateEmbeddingResponse response = client.embeddings().create(params);

        // A compatible server may answer 200 without `data`; the count check below reports it
        // instead of the SDK's required-field exception.
        JsonField<List<Embedding>> dataField = response._data();
        List<Embedding> data;
        if (dataField.isMissing() || dataField.isNull()) {
            data = Collections.emptyList();
        } else {
            // Present but not a list of embeddings: report the payload, not a count of zero.
            data =
                    dataField
                            .asKnown()
                            .orElseThrow(
                                    () ->
                                            new RuntimeException(
                                                    "OpenAI returned a malformed data field: "
                                                            + dataField.asUnknown().orElse(null)));
        }
        if (data.size() != texts.size()) {
            throw new RuntimeException(
                    String.format(
                            "OpenAI returned %d embeddings for %d input texts.",
                            data.size(), texts.size()));
        }
        boolean base64 =
                params.encodingFormat()
                        .map(EmbeddingCreateParams.EncodingFormat.BASE64::equals)
                        .orElse(false);
        return new EmbeddingResult<>(orderEmbeddings(data, base64), extractTokenUsage(response));
    }

    /**
     * Places each vector at its response {@code index} so input order is preserved even if the API
     * reorders; an item without an index (some OpenAI-compatible servers omit it) keeps its
     * response position. Every slot must be filled exactly once, so a response whose indices are
     * out of range, duplicated, or contradict the positions of index-less items is rejected rather
     * than silently mapped.
     */
    private static List<float[]> orderEmbeddings(List<Embedding> data, boolean base64) {
        float[][] ordered = new float[data.size()][];
        for (int position = 0; position < data.size(); position++) {
            Embedding embedding = data.get(position);
            if (embedding == null) {
                throw new RuntimeException(
                        "OpenAI returned a malformed embedding at position " + position + ".");
            }
            JsonField<Long> index = embedding._index();
            long target;
            if (index.isMissing() || index.isNull()) {
                target = position;
            } else {
                // A present but non-integer index is a malformed response, not an absent one.
                Optional<Long> known = index.asKnown();
                if (known.isEmpty()) {
                    throw new RuntimeException(
                            String.format(
                                    "OpenAI returned a non-integer embedding index %s at position %d.",
                                    index.asUnknown().orElse(null), position));
                }
                target = known.get();
            }
            if (target < 0 || target >= data.size() || ordered[(int) target] != null) {
                throw new RuntimeException(
                        String.format(
                                "OpenAI returned an unexpected embedding index %d at position %d.",
                                target, position));
            }
            try {
                ordered[(int) target] = toVector(embedding, base64);
            } catch (RuntimeException e) {
                // A null, non-numeric or undecodable vector; name the position as Python does.
                throw new RuntimeException(
                        "OpenAI returned a malformed embedding at position " + position + ".", e);
            }
        }
        return new ArrayList<>(List.of(ordered));
    }

    /**
     * Decodes one response vector. Base64 payloads are decoded here rather than through the SDK,
     * which silently drops trailing bytes when the length is not a multiple of four; the wire
     * format is little-endian float32.
     */
    private static float[] toVector(Embedding embedding, boolean base64Requested) {
        EmbeddingValue value = embedding.embeddingValue();
        if (value.isBase64()) {
            if (!base64Requested) {
                // A string vector is only meaningful when base64 was asked for.
                throw new IllegalArgumentException(
                        "a string vector was returned although encoding_format was float.");
            }
            byte[] bytes = Base64.getDecoder().decode(value.asBase64());
            if (bytes.length % Float.BYTES != 0) {
                throw new IllegalArgumentException(
                        "base64 embedding has " + bytes.length + " bytes, not a multiple of 4.");
            }
            float[] vector = new float[bytes.length / Float.BYTES];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vector);
            return vector;
        }
        return EmbeddingModelUtils.toFloatArray(value.asFloats());
    }

    /**
     * Converts an {@code additional_kwargs} value, naming the key if the SDK cannot serialize it.
     */
    static JsonValue toJsonValue(String key, Object value) {
        try {
            return JsonValue.from(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    ADDITIONAL_KWARGS
                            + " value for key '"
                            + key
                            + "' cannot be sent as JSON: "
                            + e.getMessage(),
                    e);
        }
    }

    static EmbeddingCreateParams buildParams(List<String> texts, Map<String, Object> parameters) {
        EmbeddingCreateParams.Builder builder =
                EmbeddingCreateParams.builder()
                        .model(parseModel(parameters.get(MODEL)))
                        .inputOfArrayOfStrings(texts)
                        .encodingFormat(
                                EmbeddingCreateParams.EncodingFormat.of(
                                        parseEncodingFormat(parameters.get(ENCODING_FORMAT))));
        Integer dimensions = parseDimensions(parameters.get(DIMENSIONS));
        if (dimensions != null) {
            builder.dimensions(dimensions);
        }
        String user = requireString(parameters.get(USER), USER);
        if (user != null && !user.isBlank()) {
            builder.user(user);
        }
        for (Map.Entry<String, Object> entry :
                parseAdditionalKwargs(parameters.get(ADDITIONAL_KWARGS)).entrySet()) {
            if (entry.getValue() != null) {
                builder.putAdditionalBodyProperty(
                        entry.getKey(), toJsonValue(entry.getKey(), entry.getValue()));
            }
        }
        return builder.build();
    }

    /**
     * Tolerates servers that omit {@code usage} or some of its fields. Embedding requests have no
     * completion tokens, so a missing side of the usage equals the other side (as the Tongyi
     * connection does) rather than being reported as zero.
     */
    private static EmbeddingTokenUsage extractTokenUsage(CreateEmbeddingResponse response) {
        Optional<CreateEmbeddingResponse.Usage> usage = response._usage().asKnown();
        if (usage.isEmpty()) {
            return null;
        }
        Optional<Long> prompt = usageCount(usage.get()._promptTokens());
        Optional<Long> total = usageCount(usage.get()._totalTokens());
        if (prompt.isEmpty() && total.isEmpty()) {
            return null;
        }
        long promptTokens = prompt.orElseGet(total::get);
        return new EmbeddingTokenUsage(promptTokens, total.orElse(promptTokens));
    }

    /**
     * A token count, also accepting the decimal-string form some compatible servers send (as the
     * Python connection does); anything else is treated as absent.
     */
    private static Optional<Long> usageCount(JsonField<Long> field) {
        Optional<Long> known = field.asKnown();
        if (known.isPresent()) {
            return known;
        }
        return field.asUnknown()
                .filter(value -> value instanceof JsonString)
                .map(value -> ((JsonString) value).value().trim())
                .filter(text -> !text.isEmpty() && text.chars().allMatch(Character::isDigit))
                .flatMap(
                        text -> {
                            try {
                                return Optional.of(Long.parseLong(text));
                            } catch (NumberFormatException e) {
                                return Optional.empty();
                            }
                        });
    }

    @Override
    public void close() {
        client.close();
    }
}
