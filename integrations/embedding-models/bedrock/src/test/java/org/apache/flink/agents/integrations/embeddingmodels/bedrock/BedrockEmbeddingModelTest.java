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

package org.apache.flink.agents.integrations.embeddingmodels.bedrock;

import org.apache.flink.agents.api.RetryExecutor;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelConnection;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelSetup;
import org.apache.flink.agents.api.embedding.model.EmbeddingResult;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests for {@link BedrockEmbeddingModelConnection} and {@link BedrockEmbeddingModelSetup}. */
class BedrockEmbeddingModelTest {

    private static final ResourceContext NOOP = ResourceContext.fromGetResource((a, b) -> null);

    private static ResourceDescriptor connDescriptor(String region) {
        ResourceDescriptor.Builder b =
                ResourceDescriptor.Builder.newBuilder(
                        BedrockEmbeddingModelConnection.class.getName());
        if (region != null) b.addInitialArgument("region", region);
        return b.build();
    }

    @Test
    @DisplayName("Connection constructor creates client with defaults")
    void testConnectionDefaults() {
        BedrockEmbeddingModelConnection conn =
                new BedrockEmbeddingModelConnection(connDescriptor(null), NOOP);
        assertNotNull(conn);
        assertThat(conn).isInstanceOf(BaseEmbeddingModelConnection.class);
    }

    @Test
    @DisplayName("Connection constructor with explicit region and concurrency")
    void testConnectionExplicitParams() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(
                                BedrockEmbeddingModelConnection.class.getName())
                        .addInitialArgument("region", "eu-west-1")
                        .addInitialArgument("embed_concurrency", 8)
                        .build();
        BedrockEmbeddingModelConnection conn = new BedrockEmbeddingModelConnection(desc, NOOP);
        assertNotNull(conn);
    }

    @Test
    @DisplayName("Setup getParameters includes model and dimensions")
    void testSetupParameters() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(BedrockEmbeddingModelSetup.class.getName())
                        .addInitialArgument("connection", "conn")
                        .addInitialArgument("model", "amazon.titan-embed-text-v2:0")
                        .addInitialArgument("dimensions", 1024)
                        .build();
        BedrockEmbeddingModelSetup setup = new BedrockEmbeddingModelSetup(desc, NOOP);

        Map<String, Object> params = setup.getParameters();
        assertThat(params).containsEntry("model", "amazon.titan-embed-text-v2:0");
        assertThat(params).containsEntry("dimensions", 1024);
        assertThat(setup).isInstanceOf(BaseEmbeddingModelSetup.class);
    }

    @Test
    @DisplayName("Setup getParameters omits null dimensions")
    void testSetupParametersNoDimensions() {
        ResourceDescriptor desc =
                ResourceDescriptor.Builder.newBuilder(BedrockEmbeddingModelSetup.class.getName())
                        .addInitialArgument("connection", "conn")
                        .addInitialArgument("model", "amazon.titan-embed-text-v2:0")
                        .build();
        BedrockEmbeddingModelSetup setup = new BedrockEmbeddingModelSetup(desc, NOOP);

        assertThat(setup.getParameters()).doesNotContainKey("dimensions");
    }

    @Test
    @DisplayName("Batch embedding aggregates token usage from worker results")
    void testBatchEmbeddingAggregatesTokenUsage() throws Exception {
        BedrockRuntimeClient client = mock(BedrockRuntimeClient.class);
        when(client.invokeModel(any(InvokeModelRequest.class)))
                .thenReturn(
                        InvokeModelResponse.builder()
                                .body(
                                        SdkBytes.fromUtf8String(
                                                "{\"embedding\":[0.1,0.2],\"inputTextTokenCount\":5}"))
                                .build());

        ExecutorService embedPool = Executors.newFixedThreadPool(2);
        BedrockEmbeddingModelConnection conn =
                new BedrockEmbeddingModelConnection(
                        connDescriptor(null),
                        NOOP,
                        client,
                        embedPool,
                        RetryExecutor.builder().maxRetries(0).build(),
                        "mock-model");

        try {
            EmbeddingResult<List<float[]>> result =
                    conn.embedWithUsage(List.of("first", "second"), Map.of("model", "mock-model"));

            assertThat(result.getEmbeddings()).hasSize(2);
            assertThat(result.getEmbeddings().get(0)).containsExactly(0.1f, 0.2f);
            assertThat(result.getEmbeddings().get(1)).containsExactly(0.1f, 0.2f);
            assertThat(result.getTokenUsage()).isNotNull();
            assertThat(result.getTokenUsage().getPromptTokens()).isEqualTo(10L);
            assertThat(result.getTokenUsage().getTotalTokens()).isEqualTo(10L);
            verify(client, times(2)).invokeModel(any(InvokeModelRequest.class));
        } finally {
            conn.close();
        }
    }
}
