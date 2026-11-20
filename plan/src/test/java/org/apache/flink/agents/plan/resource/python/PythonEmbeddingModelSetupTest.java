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
package org.apache.flink.agents.plan.resource.python;

import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.embedding.model.EmbeddingResult;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import pemja.core.object.PyObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PythonEmbeddingModelSetupTest {
    @Mock private PythonResourceAdapter mockAdapter;

    @Mock private PyObject mockEmbeddingModelSetup;

    @Mock private ResourceDescriptor mockDescriptor;

    @Mock private ResourceContext mockGetResource;

    private PythonEmbeddingModelSetup pythonEmbeddingModelSetup;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        pythonEmbeddingModelSetup =
                new PythonEmbeddingModelSetup(
                        mockAdapter, mockEmbeddingModelSetup, mockDescriptor, mockGetResource);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    void testConstructor() {
        assertThat(pythonEmbeddingModelSetup).isNotNull();
        assertThat(pythonEmbeddingModelSetup.getPythonResource())
                .isEqualTo(mockEmbeddingModelSetup);
    }

    @Test
    void testGetPythonResourceWithNullEmbeddingModelSetup() {
        PythonEmbeddingModelSetup setupWithNullModel =
                new PythonEmbeddingModelSetup(mockAdapter, null, mockDescriptor, mockGetResource);

        Object result = setupWithNullModel.getPythonResource();

        assertThat(result).isNull();
    }

    @Test
    void testGetParameters() {
        Map<String, Object> result = pythonEmbeddingModelSetup.getParameters();

        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    @Test
    void testEmbedSingleText() {
        String text = "test embedding text";
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("temperature", 0.5);

        List<Double> pythonResult = Arrays.asList(0.1, 0.2, 0.3, 0.4);

        when(mockAdapter.invoke(
                        eq("python_java_utils.call_embedding"),
                        eq(mockEmbeddingModelSetup),
                        eq("embed"),
                        any(),
                        any(Map.class)))
                .thenReturn(pythonResult);

        float[] result = pythonEmbeddingModelSetup.embed(text, parameters);

        assertThat(result).isNotNull();
        assertThat(result).hasSize(4);
        assertThat(result[0]).isEqualTo(0.1f, org.assertj.core.data.Offset.offset(0.0001f));
        assertThat(result[1]).isEqualTo(0.2f, org.assertj.core.data.Offset.offset(0.0001f));
        assertThat(result[2]).isEqualTo(0.3f, org.assertj.core.data.Offset.offset(0.0001f));
        assertThat(result[3]).isEqualTo(0.4f, org.assertj.core.data.Offset.offset(0.0001f));

        verify(mockAdapter)
                .invoke(
                        "python_java_utils.call_embedding",
                        mockEmbeddingModelSetup,
                        "embed",
                        text,
                        parameters);
    }

    @Test
    void testEmbedSingleTextWithEmptyParameters() {
        String text = "test text";
        Map<String, Object> parameters = new HashMap<>();

        List<Double> pythonResult = Arrays.asList(1.0, 2.0);

        when(mockAdapter.invoke(
                        eq("python_java_utils.call_embedding"),
                        eq(mockEmbeddingModelSetup),
                        eq("embed"),
                        any(),
                        any(Map.class)))
                .thenReturn(pythonResult);

        float[] result = pythonEmbeddingModelSetup.embed(text, parameters);

        assertThat(result).isNotNull();
        assertThat(result).hasSize(2);
    }

    @Test
    void testEmbedWithUsageSingleText() {
        String text = "test text";
        Map<String, Object> parameters = Map.of("model", "test-model");
        when(mockAdapter.invoke(
                        eq("python_java_utils.call_embedding"),
                        eq(mockEmbeddingModelSetup),
                        eq("embed_with_usage"),
                        eq(text),
                        any(Map.class)))
                .thenReturn(
                        Map.of(
                                "embeddings",
                                List.of(0.1, 0.2),
                                "token_usage",
                                Map.of("prompt_tokens", 7, "total_tokens", 9)));

        EmbeddingResult<float[]> result =
                pythonEmbeddingModelSetup.embedWithUsage(text, parameters);

        assertThat(result.getEmbeddings()).containsExactly(0.1f, 0.2f);
        assertThat(result.getTokenUsage()).isNotNull();
        assertThat(result.getTokenUsage().getPromptTokens()).isEqualTo(7L);
        assertThat(result.getTokenUsage().getTotalTokens()).isEqualTo(9L);
        verify(mockAdapter)
                .invoke(
                        "python_java_utils.call_embedding",
                        mockEmbeddingModelSetup,
                        "embed_with_usage",
                        text,
                        parameters);
    }

    @Test
    void testPythonSetupReturnsOrderedBlockEmbeddingsAndUsage() {
        when(mockAdapter.invoke("python_java_utils.get_model_name", mockEmbeddingModelSetup))
                .thenReturn("default-multimodal-model");
        pythonEmbeddingModelSetup.open();
        Map<String, Object> parameters = Map.of("dimension", 2);
        when(mockAdapter.invoke(
                        eq("python_java_utils.call_embedding"),
                        eq(mockEmbeddingModelSetup),
                        eq("embed_with_usage"),
                        any(),
                        eq(parameters)))
                .thenAnswer(
                        invocation -> {
                            List<?> blocks = invocation.getArgument(3);
                            assertThat(blocks).hasSize(2);
                            Map<?, ?> text = (Map<?, ?>) blocks.get(0);
                            assertThat(text.get("type")).isEqualTo("text");
                            assertThat(text.get("text")).isEqualTo("caption");
                            Map<?, ?> image = (Map<?, ?>) blocks.get(1);
                            assertThat(image.get("type")).isEqualTo("image");
                            assertThat(image.get("media_type")).isEqualTo("image/png");
                            assertThat(image.get("source"))
                                    .isEqualTo(Map.of("type", "base64", "data", "aGk="));
                            return Map.of(
                                    "embeddings",
                                    List.of(List.of(0.1, 0.2), List.of(0.3, 0.4)),
                                    "token_usage",
                                    Map.of("prompt_tokens", 7, "total_tokens", 9));
                        });

        EmbeddingResult<List<float[]>> result =
                pythonEmbeddingModelSetup.embedBlocksWithUsage(
                        List.of(
                                new TextBlock("caption"),
                                ImageBlock.fromBase64("image/png", "aGk=")),
                        parameters);

        assertThat(pythonEmbeddingModelSetup.getModel()).isEqualTo("default-multimodal-model");
        assertThat(result.getEmbeddings()).hasSize(2);
        assertThat(result.getEmbeddings().get(0)).containsExactly(0.1f, 0.2f);
        assertThat(result.getEmbeddings().get(1)).containsExactly(0.3f, 0.4f);
        assertThat(result.getTokenUsage().getTotalTokens()).isEqualTo(9L);
    }

    @Test
    void testEmbedSingleTextWithNullEmbeddingModelSetupThrowsException() {
        PythonEmbeddingModelSetup setupWithNullModel =
                new PythonEmbeddingModelSetup(mockAdapter, null, mockDescriptor, mockGetResource);

        String text = "test text";
        Map<String, Object> parameters = new HashMap<>();

        assertThatThrownBy(() -> setupWithNullModel.embed(text, parameters))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EmbeddingModelSetup is not initialized")
                .hasMessageContaining("Cannot perform embed operation");
    }

    @Test
    void testEmbedSingleTextWithNonListResultThrowsException() {
        String text = "test text";
        Map<String, Object> parameters = new HashMap<>();

        when(mockAdapter.invoke(
                        eq("python_java_utils.call_embedding"),
                        eq(mockEmbeddingModelSetup),
                        eq("embed"),
                        any(),
                        any(Map.class)))
                .thenReturn("invalid result");

        assertThatThrownBy(() -> pythonEmbeddingModelSetup.embed(text, parameters))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected List from Python embed method")
                .hasMessageContaining("java.lang.String");
    }

    @Test
    void testEmbedMultipleTexts() {
        List<String> texts = Arrays.asList("text1", "text2", "text3");
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("batch_size", 3);

        List<List<Double>> pythonResult = new ArrayList<>();
        pythonResult.add(Arrays.asList(0.1, 0.2));
        pythonResult.add(Arrays.asList(0.3, 0.4));
        pythonResult.add(Arrays.asList(0.5, 0.6));

        when(mockAdapter.invoke(
                        eq("python_java_utils.call_embedding"),
                        eq(mockEmbeddingModelSetup),
                        eq("embed"),
                        any(),
                        any(Map.class)))
                .thenReturn(pythonResult);

        List<float[]> result = pythonEmbeddingModelSetup.embed(texts, parameters);

        assertThat(result).isNotNull();
        assertThat(result).hasSize(3);
        assertThat(result.get(0)).hasSize(2);
        assertThat(result.get(0)[0]).isEqualTo(0.1f, org.assertj.core.data.Offset.offset(0.0001f));
        assertThat(result.get(1)[0]).isEqualTo(0.3f, org.assertj.core.data.Offset.offset(0.0001f));
        assertThat(result.get(2)[0]).isEqualTo(0.5f, org.assertj.core.data.Offset.offset(0.0001f));

        verify(mockAdapter)
                .invoke(
                        "python_java_utils.call_embedding",
                        mockEmbeddingModelSetup,
                        "embed",
                        texts,
                        parameters);
    }

    @Test
    void testEmbedMultipleTextsWithNullEmbeddingModelSetupThrowsException() {
        PythonEmbeddingModelSetup setupWithNullModel =
                new PythonEmbeddingModelSetup(mockAdapter, null, mockDescriptor, mockGetResource);

        List<String> texts = Arrays.asList("text1", "text2");
        Map<String, Object> parameters = new HashMap<>();

        assertThatThrownBy(() -> setupWithNullModel.embed(texts, parameters))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EmbeddingModelSetup is not initialized")
                .hasMessageContaining("Cannot perform embed operation");
    }

    @Test
    void testEmbedMultipleTextsWithNonListResultThrowsException() {
        List<String> texts = Arrays.asList("text1", "text2");
        Map<String, Object> parameters = new HashMap<>();

        when(mockAdapter.invoke(
                        eq("python_java_utils.call_embedding"),
                        eq(mockEmbeddingModelSetup),
                        eq("embed"),
                        any(),
                        any(Map.class)))
                .thenReturn("invalid result");

        assertThatThrownBy(() -> pythonEmbeddingModelSetup.embed(texts, parameters))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected List from Python embed method")
                .hasMessageContaining("java.lang.String");
    }

    @Test
    void testEmbedMultipleTextsWithNonListElementThrowsException() {
        List<String> texts = Arrays.asList("text1", "text2");
        Map<String, Object> parameters = new HashMap<>();

        List<Object> pythonResult = new ArrayList<>();
        pythonResult.add(Arrays.asList(0.1, 0.2));
        pythonResult.add("invalid element");

        when(mockAdapter.invoke(
                        eq("python_java_utils.call_embedding"),
                        eq(mockEmbeddingModelSetup),
                        eq("embed"),
                        any(),
                        any(Map.class)))
                .thenReturn(pythonResult);

        assertThatThrownBy(() -> pythonEmbeddingModelSetup.embed(texts, parameters))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected List value in embedding results")
                .hasMessageContaining("java.lang.String");
    }

    @Test
    void testInheritanceFromBaseEmbeddingModelSetup() {
        assertThat(pythonEmbeddingModelSetup)
                .isInstanceOf(
                        org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelSetup.class);
    }

    @Test
    void testImplementsPythonResourceWrapper() {
        assertThat(pythonEmbeddingModelSetup)
                .isInstanceOf(
                        org.apache.flink.agents.plan.resource.python.PythonResourceWrapper.class);
    }
}
