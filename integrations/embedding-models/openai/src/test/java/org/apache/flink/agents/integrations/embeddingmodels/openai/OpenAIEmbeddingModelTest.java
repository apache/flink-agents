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
import com.openai.core.JsonField;
import com.openai.core.JsonMissing;
import com.openai.core.JsonValue;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.models.embeddings.EmbeddingValue;
import com.openai.services.blocking.EmbeddingService;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelConnection;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelSetup;
import org.apache.flink.agents.api.embedding.model.EmbeddingResult;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAIEmbeddingModelTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    private static ResourceDescriptor connDescriptor() {
        return ResourceDescriptor.Builder.newBuilder(OpenAIEmbeddingModelConnection.class.getName())
                .addInitialArgument("api_key", "test-key")
                .build();
    }

    private static Embedding embedding(long index, float... values) {
        Embedding.Builder builder =
                Embedding.builder().index(index).object_(JsonValue.from("embedding"));
        List<Float> boxed = new java.util.ArrayList<>();
        for (float v : values) {
            boxed.add(v);
        }
        return builder.embedding(boxed).build();
    }

    private static CreateEmbeddingResponse response(long promptTokens, Embedding... data) {
        CreateEmbeddingResponse.Builder builder =
                CreateEmbeddingResponse.builder()
                        .model("text-embedding-3-small")
                        .object_(JsonValue.from("list"))
                        .usage(
                                CreateEmbeddingResponse.Usage.builder()
                                        .promptTokens(promptTokens)
                                        .totalTokens(promptTokens)
                                        .build());
        for (Embedding e : data) {
            builder.addData(e);
        }
        return builder.build();
    }

    private static OpenAIEmbeddingModelConnection connectionWith(
            OpenAIClient client, EmbeddingService service) {
        when(client.embeddings()).thenReturn(service);
        return new OpenAIEmbeddingModelConnection(connDescriptor(), NOOP, client);
    }

    @Test
    @DisplayName("Connection constructor builds a client with defaults")
    void testConnectionDefaults() {
        OpenAIEmbeddingModelConnection conn =
                new OpenAIEmbeddingModelConnection(connDescriptor(), NOOP);
        assertThat(conn).isInstanceOf(BaseEmbeddingModelConnection.class);
        OpenAIEmbeddingModelConnection.ClientConfig config =
                OpenAIEmbeddingModelConnection.ClientConfig.parse(connDescriptor());
        assertThat(config.apiKey).isEqualTo("test-key");
        assertThat(config.baseUrl).isEqualTo(OpenAIEmbeddingModelConnection.DEFAULT_BASE_URL);
        assertThat(config.requestTimeout).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.maxRetries).isEqualTo(3);
        assertThat(config.organization).isNull();
        assertThat(config.project).isNull();
        conn.close();
    }

    @Test
    @DisplayName("Connection accepts organization, project, base_url and numeric options")
    void testConnectionExplicitParams() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(
                                OpenAIEmbeddingModelConnection.class.getName())
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("base_url", "https://example.invalid/v1")
                        .addInitialArgument("request_timeout", 12.5)
                        .addInitialArgument("max_retries", 0)
                        .addInitialArgument("organization", "org-1")
                        .addInitialArgument("project", "proj-1")
                        .build();
        OpenAIEmbeddingModelConnection conn = new OpenAIEmbeddingModelConnection(desc, NOOP);
        OpenAIEmbeddingModelConnection.ClientConfig config =
                OpenAIEmbeddingModelConnection.ClientConfig.parse(desc);
        assertThat(config.baseUrl).isEqualTo("https://example.invalid/v1");
        assertThat(config.requestTimeout).isEqualTo(Duration.ofMillis(12_500));
        assertThat(config.maxRetries).isZero();
        assertThat(config.organization).isEqualTo("org-1");
        assertThat(config.project).isEqualTo("proj-1");
        conn.close();

        ResourceDescriptor blanks =
                ResourceDescriptor.Builder.newBuilder(
                                OpenAIEmbeddingModelConnection.class.getName())
                        .addInitialArgument("api_key", "test-key")
                        .addInitialArgument("base_url", " ")
                        .addInitialArgument("organization", "")
                        .build();
        OpenAIEmbeddingModelConnection.ClientConfig blankConfig =
                OpenAIEmbeddingModelConnection.ClientConfig.parse(blanks);
        assertThat(blankConfig.baseUrl).isEqualTo(OpenAIEmbeddingModelConnection.DEFAULT_BASE_URL);
        assertThat(blankConfig.organization).isNull();
    }

    @Test
    @DisplayName("Missing api_key is rejected at construction")
    void testMissingApiKey() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(
                                OpenAIEmbeddingModelConnection.class.getName())
                        .build();
        assertThatThrownBy(() -> new OpenAIEmbeddingModelConnection(desc, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api_key");
    }

    @Test
    @DisplayName(
            "request_timeout and max_retries follow the OpenAI chat connection's parsing rules")
    void testNumericOptionParsing() {
        assertThat(OpenAIEmbeddingModelConnection.parseRequestTimeout(null))
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(OpenAIEmbeddingModelConnection.parseRequestTimeout(1.5))
                .isEqualTo(Duration.ofMillis(1500));
        // sub-millisecond values round up instead of collapsing to "disabled"
        assertThat(OpenAIEmbeddingModelConnection.parseRequestTimeout(0.0001))
                .isEqualTo(Duration.ofMillis(1));
        assertThat(OpenAIEmbeddingModelConnection.parseRequestTimeout(0)).isEqualTo(Duration.ZERO);
        assertThatThrownBy(() -> OpenAIEmbeddingModelConnection.parseRequestTimeout(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenAIEmbeddingModelConnection.parseRequestTimeout(2147483.648))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenAIEmbeddingModelConnection.parseRequestTimeout("30"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenAIEmbeddingModelConnection.parseRequestTimeout(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(OpenAIEmbeddingModelConnection.parseMaxRetries(null)).isEqualTo(3);
        assertThat(OpenAIEmbeddingModelConnection.parseMaxRetries(7L)).isEqualTo(7);
        assertThat(OpenAIEmbeddingModelConnection.parseMaxRetries(2.0)).isEqualTo(2);
        assertThatThrownBy(() -> OpenAIEmbeddingModelConnection.parseMaxRetries(1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenAIEmbeddingModelConnection.parseMaxRetries(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("dimensions must be a positive integer within int range, also per call")
    void testDimensionsValidation() {
        assertThat(OpenAIEmbeddingModelConnection.parseDimensions(256L)).isEqualTo(256);
        assertThat(OpenAIEmbeddingModelConnection.parseDimensions(256.0)).isEqualTo(256);
        for (Object bad : new Object[] {256.7, 0, -1, 4294967552L, 1e10, "256"}) {
            assertThatThrownBy(() -> OpenAIEmbeddingModelConnection.parseDimensions(bad))
                    .as("dimensions=" + bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dimensions");
        }
        // per-call overrides go through the same rules as the setup
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService service = mock(EmbeddingService.class);
        OpenAIEmbeddingModelConnection conn = connectionWith(client, service);
        assertThatThrownBy(
                        () ->
                                conn.embed(
                                        "hello",
                                        Map.of(
                                                "model",
                                                "text-embedding-3-small",
                                                "dimensions",
                                                256.7)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensions");
    }

    @Test
    @DisplayName("Setup getParameters mirrors the Python model_kwargs")
    void testSetupParameters() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(OpenAIEmbeddingModelSetup.class.getName())
                        .addInitialArgument("connection", "conn")
                        .addInitialArgument("model", "text-embedding-3-small")
                        .addInitialArgument("dimensions", 256)
                        .addInitialArgument("user", "user-42")
                        .addInitialArgument("additional_kwargs", Map.of("custom", "x"))
                        .build();
        OpenAIEmbeddingModelSetup setup = new OpenAIEmbeddingModelSetup(desc, NOOP);
        assertThat(setup).isInstanceOf(BaseEmbeddingModelSetup.class);
        assertThat(setup.getParameters())
                .containsEntry("model", "text-embedding-3-small")
                .containsEntry("encoding_format", "float")
                .containsEntry("dimensions", 256)
                .containsEntry("user", "user-42")
                .containsEntry("additional_kwargs", Map.of("custom", "x"));
    }

    @Test
    @DisplayName("Setup omits optional parameters that were not set")
    void testSetupParametersDefaults() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(OpenAIEmbeddingModelSetup.class.getName())
                        .addInitialArgument("connection", "conn")
                        .addInitialArgument("model", "text-embedding-3-small")
                        .build();
        Map<String, Object> params = new OpenAIEmbeddingModelSetup(desc, NOOP).getParameters();
        assertThat(params)
                .containsOnlyKeys("model", "encoding_format")
                .containsEntry("encoding_format", "float");
        ResourceDescriptor blankUser =
                ResourceDescriptor.Builder.newBuilder(OpenAIEmbeddingModelSetup.class.getName())
                        .addInitialArgument("connection", "conn")
                        .addInitialArgument("model", "text-embedding-3-small")
                        .addInitialArgument("user", " ")
                        .build();
        // Blank strings are treated as absent, so a blank user is not emitted.
        assertThat(new OpenAIEmbeddingModelSetup(blankUser, NOOP).getParameters())
                .doesNotContainKey("user");
    }

    @Test
    @DisplayName("Single embedding returns the vector and token usage")
    void testSingleEmbedding() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService service = mock(EmbeddingService.class);
        when(service.create(any(EmbeddingCreateParams.class)))
                .thenReturn(response(5, embedding(0, 0.1f, 0.2f, 0.3f)));
        OpenAIEmbeddingModelConnection conn = connectionWith(client, service);

        EmbeddingResult<float[]> result =
                conn.embedWithUsage("hello", Map.of("model", "text-embedding-3-small"));

        assertThat(result.getEmbeddings()).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(result.getTokenUsage()).isNotNull();
        assertThat(result.getTokenUsage().getPromptTokens()).isEqualTo(5L);
        assertThat(result.getTokenUsage().getTotalTokens()).isEqualTo(5L);
        assertThat(conn.embed("hello", Map.of("model", "text-embedding-3-small")))
                .containsExactly(0.1f, 0.2f, 0.3f);
    }

    @Test
    @DisplayName("Batch embedding is one request and preserves input order")
    void testBatchEmbeddingOrderAndSingleRequest() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService service = mock(EmbeddingService.class);
        // Return the vectors out of order to prove the index is honored.
        when(service.create(any(EmbeddingCreateParams.class)))
                .thenReturn(response(9, embedding(1, 0.9f), embedding(0, 0.1f)));
        OpenAIEmbeddingModelConnection conn = connectionWith(client, service);

        EmbeddingResult<List<float[]>> result =
                conn.embedWithUsage(
                        List.of("first", "second"), Map.of("model", "text-embedding-3-small"));

        assertThat(result.getEmbeddings()).hasSize(2);
        assertThat(result.getEmbeddings().get(0)).containsExactly(0.1f);
        assertThat(result.getEmbeddings().get(1)).containsExactly(0.9f);
        assertThat(result.getTokenUsage().getPromptTokens()).isEqualTo(9L);
        verify(service).create(any(EmbeddingCreateParams.class));
    }

    @Test
    @DisplayName("Setup parameters are forwarded to the request, extras as body properties")
    void testRequestParametersForwarded() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService service = mock(EmbeddingService.class);
        when(service.create(any(EmbeddingCreateParams.class)))
                .thenReturn(response(1, embedding(0, 1f)));
        OpenAIEmbeddingModelConnection conn = connectionWith(client, service);

        conn.embed(
                "hello",
                Map.of(
                        "model",
                        "text-embedding-3-large",
                        "encoding_format",
                        "base64",
                        "dimensions",
                        512,
                        "user",
                        "u1",
                        "timeout",
                        5000,
                        "additional_kwargs",
                        Map.of("custom_flag", true)));

        ArgumentCaptor<EmbeddingCreateParams> captor =
                ArgumentCaptor.forClass(EmbeddingCreateParams.class);
        verify(service).create(captor.capture());
        EmbeddingCreateParams params = captor.getValue();
        assertThat(params.model().toString()).isEqualTo("text-embedding-3-large");
        assertThat(params.encodingFormat()).contains(EmbeddingCreateParams.EncodingFormat.BASE64);
        assertThat(params.dimensions()).contains(512L);
        assertThat(params.user()).contains("u1");
        assertThat(params._additionalBodyProperties())
                .containsKey("custom_flag")
                .doesNotContainKey("timeout");
    }

    @Test
    @DisplayName("A mismatched number of embeddings fails loudly")
    void testMismatchedResponseFails() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService service = mock(EmbeddingService.class);
        when(service.create(any(EmbeddingCreateParams.class)))
                .thenReturn(response(1, embedding(0, 1f)));
        OpenAIEmbeddingModelConnection conn = connectionWith(client, service);

        assertThatThrownBy(
                        () ->
                                conn.embed(
                                        List.of("a", "b"),
                                        Map.of("model", "text-embedding-3-small")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("2 input texts");
    }

    @Test
    @DisplayName("Missing model parameter is rejected before any request")
    void testMissingModel() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService service = mock(EmbeddingService.class);
        OpenAIEmbeddingModelConnection conn = connectionWith(client, service);
        assertThatThrownBy(() -> conn.embed("hello", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
    }

    @Test
    @DisplayName("Setup validates argument types and requires a model")
    void testSetupValidation() {
        ResourceDescriptor noModel =
                ResourceDescriptor.Builder.newBuilder(OpenAIEmbeddingModelSetup.class.getName())
                        .addInitialArgument("connection", "conn")
                        .build();
        assertThatThrownBy(() -> new OpenAIEmbeddingModelSetup(noModel, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
        ResourceDescriptor stringDims =
                ResourceDescriptor.Builder.newBuilder(OpenAIEmbeddingModelSetup.class.getName())
                        .addInitialArgument("connection", "conn")
                        .addInitialArgument("model", "text-embedding-3-small")
                        .addInitialArgument("dimensions", "256")
                        .build();
        assertThatThrownBy(() -> new OpenAIEmbeddingModelSetup(stringDims, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensions");
        ResourceDescriptor badExtra =
                ResourceDescriptor.Builder.newBuilder(OpenAIEmbeddingModelSetup.class.getName())
                        .addInitialArgument("connection", "conn")
                        .addInitialArgument("model", "text-embedding-3-small")
                        .addInitialArgument("additional_kwargs", "not-a-map")
                        .build();
        assertThatThrownBy(() -> new OpenAIEmbeddingModelSetup(badExtra, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("additional_kwargs");
        ResourceDescriptor unserializable =
                ResourceDescriptor.Builder.newBuilder(OpenAIEmbeddingModelSetup.class.getName())
                        .addInitialArgument("connection", "conn")
                        .addInitialArgument("model", "text-embedding-3-small")
                        .addInitialArgument("additional_kwargs", Map.of("opts", new Object()))
                        .build();
        assertThatThrownBy(() -> new OpenAIEmbeddingModelSetup(unserializable, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be sent as JSON");
    }

    @Test
    @DisplayName("A response without usage yields embeddings and null token usage")
    void testMissingUsageIsTolerated() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService service = mock(EmbeddingService.class);
        // The SDK builder refuses a response without usage, so mock the parsed response instead.
        CreateEmbeddingResponse noUsage = mock(CreateEmbeddingResponse.class);
        when(noUsage._data()).thenReturn(JsonField.of(List.of(embedding(0, 0.5f))));
        @SuppressWarnings("unchecked")
        JsonField<CreateEmbeddingResponse.Usage> missing =
                (JsonField<CreateEmbeddingResponse.Usage>) (JsonField<?>) JsonMissing.of();
        when(noUsage._usage()).thenReturn(missing);
        when(service.create(any(EmbeddingCreateParams.class))).thenReturn(noUsage);
        OpenAIEmbeddingModelConnection conn = connectionWith(client, service);

        EmbeddingResult<float[]> result =
                conn.embedWithUsage("hello", Map.of("model", "text-embedding-3-small"));
        assertThat(result.getEmbeddings()).containsExactly(0.5f);
        assertThat(result.getTokenUsage()).isNull();
    }

    @Test
    @DisplayName("A null input text is rejected with its index before any request")
    void testNullTextRejected() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService service = mock(EmbeddingService.class);
        OpenAIEmbeddingModelConnection conn = connectionWith(client, service);
        List<String> texts = new java.util.ArrayList<>();
        texts.add("ok");
        texts.add(null);
        assertThatThrownBy(() -> conn.embed(texts, Map.of("model", "text-embedding-3-small")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index 1");
    }

    @Test
    @DisplayName("Partial usage and missing indices from compatible servers are tolerated")
    void testPartialUsageAndMissingIndex() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService service = mock(EmbeddingService.class);
        @SuppressWarnings("unchecked")
        JsonField<Long> missingLong = (JsonField<Long>) (JsonField<?>) JsonMissing.of();
        CreateEmbeddingResponse.Usage usage = mock(CreateEmbeddingResponse.Usage.class);
        when(usage._promptTokens()).thenReturn(missingLong);
        when(usage._totalTokens()).thenReturn(JsonField.of(7L));
        Embedding first = mock(Embedding.class);
        when(first._index()).thenReturn(missingLong);
        when(first.embeddingValue()).thenReturn(EmbeddingValue.ofFloats(List.of(0.1f)));
        Embedding second = mock(Embedding.class);
        when(second._index()).thenReturn(missingLong);
        when(second.embeddingValue()).thenReturn(EmbeddingValue.ofFloats(List.of(0.2f)));
        CreateEmbeddingResponse response = mock(CreateEmbeddingResponse.class);
        when(response._data()).thenReturn(JsonField.of(List.of(first, second)));
        when(response._usage()).thenReturn(JsonField.of(usage));
        when(service.create(any(EmbeddingCreateParams.class))).thenReturn(response);
        OpenAIEmbeddingModelConnection conn = connectionWith(client, service);

        EmbeddingResult<List<float[]>> result =
                conn.embedWithUsage(List.of("a", "b"), Map.of("model", "text-embedding-3-small"));

        assertThat(result.getEmbeddings().get(0)).containsExactly(0.1f);
        assertThat(result.getEmbeddings().get(1)).containsExactly(0.2f);
        assertThat(result.getTokenUsage()).isNotNull();
        // Missing prompt_tokens equals total_tokens for embedding requests, not zero.
        assertThat(result.getTokenUsage().getPromptTokens()).isEqualTo(7L);
        assertThat(result.getTokenUsage().getTotalTokens()).isEqualTo(7L);
    }

    @Test
    @DisplayName("additional_kwargs may not repeat the typed request fields")
    void testReservedAdditionalKwargsRejected() {
        ResourceDescriptor setup =
                ResourceDescriptor.Builder.newBuilder(OpenAIEmbeddingModelSetup.class.getName())
                        .addInitialArgument("connection", "conn")
                        .addInitialArgument("model", "text-embedding-3-small")
                        .addInitialArgument(
                                "additional_kwargs", Map.of("model", "other", "custom", 1))
                        .build();
        assertThatThrownBy(() -> new OpenAIEmbeddingModelSetup(setup, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("additional_kwargs")
                .hasMessageContaining("[model]");

        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService service = mock(EmbeddingService.class);
        OpenAIEmbeddingModelConnection conn = connectionWith(client, service);
        assertThatThrownBy(
                        () ->
                                conn.embed(
                                        "hello",
                                        Map.of(
                                                "model",
                                                "text-embedding-3-small",
                                                "additional_kwargs",
                                                Map.of("input", "x", "dimensions", 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[dimensions, input]");
        verify(service, never()).create(any(EmbeddingCreateParams.class));
    }

    @Test
    @DisplayName("encoding_format accepts only float and base64")
    void testEncodingFormatValidation() {
        ResourceDescriptor setup =
                ResourceDescriptor.Builder.newBuilder(OpenAIEmbeddingModelSetup.class.getName())
                        .addInitialArgument("connection", "conn")
                        .addInitialArgument("model", "text-embedding-3-small")
                        .addInitialArgument("encoding_format", "hex")
                        .build();
        assertThatThrownBy(() -> new OpenAIEmbeddingModelSetup(setup, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("encoding_format")
                .hasMessageContaining("hex");
        assertThat(OpenAIEmbeddingModelConnection.parseEncodingFormat("base64"))
                .isEqualTo("base64");
        assertThat(OpenAIEmbeddingModelConnection.parseEncodingFormat(null)).isEqualTo("float");

        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService service = mock(EmbeddingService.class);
        OpenAIEmbeddingModelConnection conn = connectionWith(client, service);
        assertThatThrownBy(
                        () ->
                                conn.embed(
                                        "hello",
                                        Map.of(
                                                "model",
                                                "text-embedding-3-small",
                                                "encoding_format",
                                                "FLOAT")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("encoding_format");
        verify(service, never()).create(any(EmbeddingCreateParams.class));
    }

    @Test
    @DisplayName("Non-string or missing connection/model fail with named errors, not a CCE")
    void testSetupConnectionAndModelValidation() {
        ResourceDescriptor intModel =
                ResourceDescriptor.Builder.newBuilder(OpenAIEmbeddingModelSetup.class.getName())
                        .addInitialArgument("connection", "conn")
                        .addInitialArgument("model", 42)
                        .build();
        assertThatThrownBy(() -> new OpenAIEmbeddingModelSetup(intModel, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model must be a string");
        ResourceDescriptor intConnection =
                ResourceDescriptor.Builder.newBuilder(OpenAIEmbeddingModelSetup.class.getName())
                        .addInitialArgument("connection", 42)
                        .addInitialArgument("model", "text-embedding-3-small")
                        .build();
        assertThatThrownBy(() -> new OpenAIEmbeddingModelSetup(intConnection, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connection must be a string");
        ResourceDescriptor noConnection =
                ResourceDescriptor.Builder.newBuilder(OpenAIEmbeddingModelSetup.class.getName())
                        .addInitialArgument("model", "text-embedding-3-small")
                        .build();
        assertThatThrownBy(() -> new OpenAIEmbeddingModelSetup(noConnection, NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty 'connection'");
    }

    private static Embedding indexlessEmbedding(float value) {
        @SuppressWarnings("unchecked")
        JsonField<Long> missingLong = (JsonField<Long>) (JsonField<?>) JsonMissing.of();
        Embedding embedding = mock(Embedding.class);
        when(embedding._index()).thenReturn(missingLong);
        when(embedding.embeddingValue()).thenReturn(EmbeddingValue.ofFloats(List.of(value)));
        return embedding;
    }

    private static Embedding indexedMock(long index, float value) {
        Embedding embedding = mock(Embedding.class);
        when(embedding._index()).thenReturn(JsonField.of(index));
        when(embedding.embeddingValue()).thenReturn(EmbeddingValue.ofFloats(List.of(value)));
        return embedding;
    }

    @SuppressWarnings("unchecked")
    private static CreateEmbeddingResponse responseWithoutUsage(Embedding... data) {
        CreateEmbeddingResponse response = mock(CreateEmbeddingResponse.class);
        when(response._data()).thenReturn(JsonField.of(List.of(data)));
        when(response._usage())
                .thenReturn(
                        (JsonField<CreateEmbeddingResponse.Usage>) (JsonField<?>) JsonMissing.of());
        return response;
    }

    @Test
    @DisplayName("A partially indexed response is accepted only if its indices match positions")
    void testPartiallyIndexedResponse() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService service = mock(EmbeddingService.class);
        CreateEmbeddingResponse response =
                responseWithoutUsage(indexlessEmbedding(0.1f), indexedMock(1, 0.2f));
        when(service.create(any(EmbeddingCreateParams.class))).thenReturn(response);
        OpenAIEmbeddingModelConnection conn = connectionWith(client, service);

        List<float[]> embeddings =
                conn.embed(List.of("a", "b"), Map.of("model", "text-embedding-3-small"));

        assertThat(embeddings.get(0)).containsExactly(0.1f);
        assertThat(embeddings.get(1)).containsExactly(0.2f);
    }

    @Test
    @DisplayName("A partially indexed response that contradicts response order is rejected")
    void testContradictoryPartialIndexRejected() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService service = mock(EmbeddingService.class);
        // Vector 0.2 claims index 0 while sitting at position 1: neither order can be trusted.
        CreateEmbeddingResponse response =
                responseWithoutUsage(indexlessEmbedding(0.1f), indexedMock(0, 0.2f));
        when(service.create(any(EmbeddingCreateParams.class))).thenReturn(response);
        OpenAIEmbeddingModelConnection conn = connectionWith(client, service);

        assertThatThrownBy(
                        () ->
                                conn.embed(
                                        List.of("a", "b"),
                                        Map.of("model", "text-embedding-3-small")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("unexpected embedding index 0 at position 1");
    }

    @Test
    @DisplayName("An empty batch is validated but sends no request")
    void testEmptyBatchValidatesWithoutRequest() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService service = mock(EmbeddingService.class);
        OpenAIEmbeddingModelConnection conn = connectionWith(client, service);

        assertThat(conn.embed(List.of(), Map.of("model", "text-embedding-3-small"))).isEmpty();
        assertThatThrownBy(
                        () ->
                                conn.embed(
                                        (List<String>) null,
                                        Map.of("model", "text-embedding-3-small")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("texts must not be null");
        // A null parameter map is treated as empty, so the missing model is the reported error.
        assertThatThrownBy(() -> conn.embed("hello", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
        assertThatThrownBy(
                        () ->
                                conn.embed(
                                        List.of(),
                                        Map.of(
                                                "model",
                                                "text-embedding-3-small",
                                                "encoding_format",
                                                "bogus")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("encoding_format");
        verify(service, never()).create(any(EmbeddingCreateParams.class));
    }

    @Test
    @DisplayName("A base64 response is decoded to floats")
    void testBase64ResponseDecoded() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService service = mock(EmbeddingService.class);
        // 0.5f and 0.25f as little-endian float32, base64 encoded (the OpenAI wire format).
        String base64 =
                Base64.getEncoder()
                        .encodeToString(
                                ByteBuffer.allocate(8)
                                        .order(ByteOrder.LITTLE_ENDIAN)
                                        .putFloat(0.5f)
                                        .putFloat(0.25f)
                                        .array());
        Embedding encoded =
                Embedding.builder()
                        .index(0L)
                        .object_(JsonValue.from("embedding"))
                        .embedding(base64)
                        .build();
        CreateEmbeddingResponse response = response(3, encoded);
        when(service.create(any(EmbeddingCreateParams.class))).thenReturn(response);
        OpenAIEmbeddingModelConnection conn = connectionWith(client, service);

        float[] vector =
                conn.embed(
                        "hello",
                        Map.of("model", "text-embedding-3-small", "encoding_format", "base64"));

        assertThat(vector).containsExactly(0.5f, 0.25f);
    }

    @Test
    @DisplayName(
            "A base64 vector whose length is not a multiple of four is rejected, not truncated")
    void testTruncatedBase64Rejected() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService service = mock(EmbeddingService.class);
        // 6 bytes: one float plus two trailing bytes; the SDK would silently return [1.0].
        Embedding truncated =
                Embedding.builder()
                        .index(0L)
                        .object_(JsonValue.from("embedding"))
                        .embedding("AACAPwAA")
                        .build();
        when(service.create(any(EmbeddingCreateParams.class))).thenReturn(response(3, truncated));
        OpenAIEmbeddingModelConnection conn = connectionWith(client, service);

        assertThatThrownBy(
                        () ->
                                conn.embed(
                                        "hello",
                                        Map.of(
                                                "model",
                                                "text-embedding-3-small",
                                                "encoding_format",
                                                "base64")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("malformed embedding at position 0")
                .hasRootCauseMessage("base64 embedding has 6 bytes, not a multiple of 4.");
    }

    @Test
    @DisplayName("Decimal-string token counts from compatible servers are accepted")
    void testStringTokenCounts() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService service = mock(EmbeddingService.class);
        @SuppressWarnings("unchecked")
        JsonField<Long> five = (JsonField<Long>) (JsonField<?>) JsonValue.from("5");
        @SuppressWarnings("unchecked")
        JsonField<Long> junk = (JsonField<Long>) (JsonField<?>) JsonValue.from("n/a");
        CreateEmbeddingResponse.Usage usage = mock(CreateEmbeddingResponse.Usage.class);
        when(usage._promptTokens()).thenReturn(five);
        when(usage._totalTokens()).thenReturn(junk);
        CreateEmbeddingResponse response = mock(CreateEmbeddingResponse.class);
        when(response._data()).thenReturn(JsonField.of(List.of(embedding(0, 0.1f))));
        when(response._usage()).thenReturn(JsonField.of(usage));
        when(service.create(any(EmbeddingCreateParams.class))).thenReturn(response);
        OpenAIEmbeddingModelConnection conn = connectionWith(client, service);

        EmbeddingResult<float[]> result =
                conn.embedWithUsage("a", Map.of("model", "text-embedding-3-small"));

        assertThat(result.getTokenUsage()).isNotNull();
        assertThat(result.getTokenUsage().getPromptTokens()).isEqualTo(5L);
        assertThat(result.getTokenUsage().getTotalTokens()).isEqualTo(5L);
    }

    @Test
    @DisplayName("A response without data is reported as a short response")
    void testMissingDataReported() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService service = mock(EmbeddingService.class);
        @SuppressWarnings("unchecked")
        JsonField<List<Embedding>> missingData =
                (JsonField<List<Embedding>>) (JsonField<?>) JsonMissing.of();
        CreateEmbeddingResponse response = mock(CreateEmbeddingResponse.class);
        when(response._data()).thenReturn(missingData);
        when(service.create(any(EmbeddingCreateParams.class))).thenReturn(response);
        OpenAIEmbeddingModelConnection conn = connectionWith(client, service);

        assertThatThrownBy(
                        () ->
                                conn.embed(
                                        List.of("a", "b"),
                                        Map.of("model", "text-embedding-3-small")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("returned 0 embeddings for 2 input texts");

        // A data field that is present but not a list of embeddings names the payload.
        @SuppressWarnings("unchecked")
        JsonField<List<Embedding>> objectData =
                (JsonField<List<Embedding>>) (JsonField<?>) JsonValue.from(Map.of("0", 1));
        CreateEmbeddingResponse badData = mock(CreateEmbeddingResponse.class);
        when(badData._data()).thenReturn(objectData);
        when(service.create(any(EmbeddingCreateParams.class))).thenReturn(badData);
        assertThatThrownBy(() -> conn.embed("a", Map.of("model", "text-embedding-3-small")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("malformed data field");

        // A string vector when float was requested is malformed, not silently decoded.
        Embedding stringVector =
                Embedding.builder()
                        .index(0L)
                        .object_(JsonValue.from("embedding"))
                        .embedding("AAAAAAAAAAA=")
                        .build();
        when(service.create(any(EmbeddingCreateParams.class)))
                .thenReturn(response(3, stringVector));
        assertThatThrownBy(() -> conn.embed("a", Map.of("model", "text-embedding-3-small")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("malformed embedding at position 0")
                .hasRootCauseMessage(
                        "a string vector was returned although encoding_format was float.");

        // A null element in data is a malformed embedding at its position.
        CreateEmbeddingResponse nullItem = mock(CreateEmbeddingResponse.class);
        when(nullItem._data()).thenReturn(JsonField.of(java.util.Arrays.asList((Embedding) null)));
        when(service.create(any(EmbeddingCreateParams.class))).thenReturn(nullItem);
        assertThatThrownBy(() -> conn.embed("a", Map.of("model", "text-embedding-3-small")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("malformed embedding at position 0");

        OpenAIClient client2 = mock(OpenAIClient.class);
        EmbeddingService service2 = mock(EmbeddingService.class);
        OpenAIEmbeddingModelConnection conn2 = connectionWith(client2, service2);
        // A per-call additional_kwargs value the SDK cannot serialize names its key.
        assertThatThrownBy(
                        () ->
                                conn2.embed(
                                        "hello",
                                        Map.of(
                                                "model",
                                                "text-embedding-3-small",
                                                "additional_kwargs",
                                                Map.of("meta", new Object()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value for key 'meta' cannot be sent as JSON");
    }

    @Test
    @DisplayName("A null or undecodable vector is reported with its position")
    void testMalformedVectorReported() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService service = mock(EmbeddingService.class);
        Embedding broken = mock(Embedding.class);
        when(broken._index()).thenReturn(JsonField.of(1L));
        when(broken.embeddingValue())
                .thenThrow(new IllegalStateException("Invalid EmbeddingValue"));
        CreateEmbeddingResponse response = responseWithoutUsage(indexedMock(0, 0.1f), broken);
        when(service.create(any(EmbeddingCreateParams.class))).thenReturn(response);
        OpenAIEmbeddingModelConnection conn = connectionWith(client, service);

        assertThatThrownBy(
                        () ->
                                conn.embed(
                                        List.of("a", "b"),
                                        Map.of("model", "text-embedding-3-small")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("malformed embedding at position 1")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("A non-integer index is a malformed response, not an absent index")
    void testNonIntegerIndexRejected() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService service = mock(EmbeddingService.class);
        // A JSON string where a long is expected: present, not null, but not a known Long.
        @SuppressWarnings("unchecked")
        JsonField<Long> stringIndex = (JsonField<Long>) (JsonField<?>) JsonValue.from("1");
        Embedding malformed = mock(Embedding.class);
        when(malformed._index()).thenReturn(stringIndex);
        when(malformed.embeddingValue()).thenReturn(EmbeddingValue.ofFloats(List.of(0.9f)));
        CreateEmbeddingResponse response = responseWithoutUsage(malformed, indexedMock(0, 0.1f));
        when(service.create(any(EmbeddingCreateParams.class))).thenReturn(response);
        OpenAIEmbeddingModelConnection conn = connectionWith(client, service);

        assertThatThrownBy(
                        () ->
                                conn.embed(
                                        List.of("a", "b"),
                                        Map.of("model", "text-embedding-3-small")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("non-integer embedding index");
    }

    @Test
    @DisplayName("Indices outside the batch fail with a clear error")
    void testOutOfRangeIndexFails() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService service = mock(EmbeddingService.class);
        when(service.create(any(EmbeddingCreateParams.class)))
                .thenReturn(response(3, embedding(0, 0.1f), embedding(Long.MAX_VALUE, 0.2f)));
        OpenAIEmbeddingModelConnection conn = connectionWith(client, service);

        assertThatThrownBy(
                        () ->
                                conn.embed(
                                        List.of("a", "b"),
                                        Map.of("model", "text-embedding-3-small")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("unexpected embedding index " + Long.MAX_VALUE);
    }
}
