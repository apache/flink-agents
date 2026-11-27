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

package org.apache.flink.agents.api.embedding.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingResultTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static Stream<EmbeddingResult<?>> results() {
        return Stream.<Object>of(
                        new float[] {1.25f, -2.5f},
                        List.of(new float[] {1.25f}, new float[] {-2.5f}),
                        List.of())
                .flatMap(
                        embeddings ->
                                Stream.of(
                                                null,
                                                new EmbeddingTokenUsage(0, 0),
                                                new EmbeddingTokenUsage(3, 5))
                                        .map(usage -> new EmbeddingResult<>(embeddings, usage)));
    }

    @ParameterizedTest
    @MethodSource("results")
    void preservesVectorsAndUsageWithoutGenericTypeInformation(EmbeddingResult<?> original)
            throws Exception {
        EmbeddingResult<?> restored =
                MAPPER.readValue(MAPPER.writeValueAsBytes(original), EmbeddingResult.class);
        assertThat(restored.getEmbeddings())
                .usingRecursiveComparison()
                .isEqualTo(original.getEmbeddings());
        if (original.getEmbeddings() instanceof float[]) {
            assertThat(restored.getEmbeddings()).isInstanceOf(float[].class);
        } else {
            assertThat((List<?>) restored.getEmbeddings())
                    .allSatisfy(vector -> assertThat(vector).isInstanceOf(float[].class));
        }
        assertThat(restored.getTokenUsage())
                .usingRecursiveComparison()
                .isEqualTo(original.getTokenUsage());
    }

    @Test
    void alsoSupportsParameterizedResultTypes() throws Exception {
        EmbeddingResult<float[]> single =
                MAPPER.readValue(
                        MAPPER.writeValueAsBytes(new EmbeddingResult<>(new float[] {1.5f}, null)),
                        new TypeReference<EmbeddingResult<float[]>>() {});
        assertThat(single.getEmbeddings()).containsExactly(1.5f);
        EmbeddingResult<List<float[]>> batch =
                MAPPER.readValue(
                        MAPPER.writeValueAsBytes(
                                new EmbeddingResult<>(List.of(new float[] {2.5f}), null)),
                        new TypeReference<EmbeddingResult<List<float[]>>>() {});
        assertThat(batch.getEmbeddings().get(0)).containsExactly(2.5f);
    }

    @Test
    void emptyBatchUsesAnEmptyArrayWithoutAShapeFlag() throws Exception {
        String json = MAPPER.writeValueAsString(new EmbeddingResult<>(List.of(), null));
        assertThat(MAPPER.readTree(json).has("batch")).isFalse();
        assertThat(MAPPER.readTree(json).get("embeddings").isEmpty()).isTrue();
        EmbeddingResult<?> restored =
                MAPPER.readValue("{\"embeddings\":[]}", EmbeddingResult.class);
        assertThat((List<?>) restored.getEmbeddings()).isEmpty();
    }

    @Test
    void rejectsEmptyVectors() {
        assertThatThrownBy(() -> new EmbeddingResult<>(new float[0], null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vectors must be non-empty");
        assertThatThrownBy(() -> new EmbeddingResult<>(List.of(new float[0]), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vectors must be non-empty");
        assertThatThrownBy(() -> MAPPER.readValue("{\"embeddings\":[[]]}", EmbeddingResult.class))
                .hasMessageContaining("vectors must be non-empty");
    }

    @Test
    void rejectsMissingEmbeddings() {
        assertThatThrownBy(() -> MAPPER.readValue("{}", EmbeddingResult.class))
                .hasMessageContaining("requires an embeddings array");
    }
}
