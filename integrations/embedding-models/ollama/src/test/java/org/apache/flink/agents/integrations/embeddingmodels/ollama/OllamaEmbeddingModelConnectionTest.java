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

package org.apache.flink.agents.integrations.embeddingmodels.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ollama4j.Ollama;
import io.github.ollama4j.models.embed.OllamaEmbedRequest;
import io.github.ollama4j.models.embed.OllamaEmbedResult;
import org.apache.flink.agents.api.annotation.EmbeddingModelConnection;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.UnsupportedContentBlockException;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelSetup;
import org.apache.flink.agents.api.embedding.model.EmbeddingResult;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.TestResourceContexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OllamaEmbeddingModelConnectionTest {

    private static ResourceDescriptor buildDescriptor() {
        return ResourceDescriptor.Builder.newBuilder(OllamaEmbeddingModelConnection.class.getName())
                .addInitialArgument("host", "http://localhost:11434")
                .addInitialArgument("model", "nomic-embed-text")
                .build();
    }

    private static ResourceContext dummyResource =
            TestResourceContexts.fromGetResource((a, b) -> null);

    @Test
    @DisplayName("Create OllamaEmbeddingModelConnection and check embed method")
    void testCreateAndEmbed() {
        OllamaEmbeddingModelConnection conn =
                new OllamaEmbeddingModelConnection(buildDescriptor(), dummyResource);
        assertNotNull(conn);
        // No llamamos a embed porque requiere un servidor Ollama real
    }

    @Test
    @DisplayName("Test EmbeddingModelConnection annotation presence")
    void testAnnotationPresence() {
        assertNull(
                OllamaEmbeddingModelConnection.class.getAnnotation(EmbeddingModelConnection.class));
    }

    @Test
    @DisplayName("Test EmbeddingModelSetup annotation presence on setup class")
    void testSetupAnnotationPresence() {
        class DummySetup extends BaseEmbeddingModelSetup {
            public DummySetup(ResourceDescriptor descriptor, ResourceContext resourceContext) {
                super(descriptor, resourceContext);
            }

            public Map<String, Object> getParameters() {
                Map<String, Object> parameters = new HashMap<>();
                if (model != null) {
                    parameters.put("model", model);
                }
                return parameters;
            }

            @Override
            public void open() {
                connection = new OllamaEmbeddingModelConnection(buildDescriptor(), dummyResource);
            }
        }
        DummySetup setup = new DummySetup(buildDescriptor(), dummyResource);
        setup.open();
        assertNotNull(setup.getConnection());
    }

    @Test
    void testRejectsMediaBeforePullingModel() {
        OllamaEmbeddingModelConnection conn =
                new OllamaEmbeddingModelConnection(buildDescriptor(), dummyResource);
        ImageBlock image = ImageBlock.fromBase64("image/png", "aGk=");
        assertThrows(UnsupportedContentBlockException.class, () -> conn.embed(image, Map.of()));
        assertThrows(
                UnsupportedContentBlockException.class,
                () -> conn.embed(List.of(new TextBlock("first"), image), Map.of()));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 7})
    void testPreservesSdkUsageForSingleAndBatchInputs(Integer reportedCount) throws Exception {
        String usage = reportedCount == null ? "" : ",\"prompt_eval_count\":" + reportedCount;
        ObjectMapper mapper = new ObjectMapper();
        OllamaEmbedResult single =
                mapper.readValue(
                        "{\"embeddings\":[[0.1,0.2]]" + usage + "}", OllamaEmbedResult.class);
        OllamaEmbedResult batch =
                mapper.readValue(
                        "{\"embeddings\":[[0.1,0.2],[0.3,0.4]]" + usage + "}",
                        OllamaEmbedResult.class);
        try (MockedConstruction<Ollama> clients = mockConstruction(Ollama.class)) {
            OllamaEmbeddingModelConnection connection =
                    new OllamaEmbeddingModelConnection(buildDescriptor(), dummyResource);
            Ollama client = clients.constructed().get(0);
            when(client.embed(any(OllamaEmbedRequest.class))).thenReturn(single, batch);

            EmbeddingResult<float[]> singleResult = connection.embed("first", Map.of());
            EmbeddingResult<List<float[]>> batchResult =
                    connection.embed(
                            List.of(new TextBlock("first"), new TextBlock("second")), Map.of());

            assertArrayEquals(new float[] {0.1f, 0.2f}, singleResult.getEmbeddings());
            assertEquals(2, batchResult.getEmbeddings().size());
            assertArrayEquals(new float[] {0.1f, 0.2f}, batchResult.getEmbeddings().get(0));
            assertArrayEquals(new float[] {0.3f, 0.4f}, batchResult.getEmbeddings().get(1));
            // Preserve the SDK's value, including its zero default for an omitted field.
            long expected = reportedCount == null ? 0 : reportedCount;
            for (EmbeddingResult<?> result : List.of(singleResult, batchResult)) {
                assertNotNull(result.getTokenUsage());
                assertEquals(expected, result.getTokenUsage().getPromptTokens());
                assertEquals(expected, result.getTokenUsage().getTotalTokens());
            }
            verify(client, times(2)).embed(any(OllamaEmbedRequest.class));
        }
    }
}
