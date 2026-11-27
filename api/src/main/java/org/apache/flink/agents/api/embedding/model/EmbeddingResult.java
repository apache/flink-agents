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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.Nullable;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Embedding provider result with optional token usage metadata.
 *
 * <p>Each vector must be non-empty. A batch may contain no vectors.
 *
 * @param <T> {@code float[]} for a single input or {@code List<float[]>} for a batch
 */
public class EmbeddingResult<T> {
    private final T embeddings;

    @Nullable private final EmbeddingTokenUsage tokenUsage;

    public EmbeddingResult(T embeddings, @Nullable EmbeddingTokenUsage tokenUsage) {
        if (embeddings instanceof List) {
            ((List<?>) embeddings).forEach(EmbeddingResult::validateVector);
        } else {
            validateVector(embeddings);
        }
        this.embeddings = embeddings;
        this.tokenUsage = tokenUsage;
    }

    private static void validateVector(Object vector) {
        if (vector instanceof float[] && ((float[]) vector).length == 0) {
            throw new IllegalArgumentException("Embedding vectors must be non-empty");
        }
    }

    public T getEmbeddings() {
        return embeddings;
    }

    @Nullable
    public EmbeddingTokenUsage getTokenUsage() {
        return tokenUsage;
    }

    @JsonCreator
    private static EmbeddingResult<?> fromJson(
            @JsonProperty("embeddings") List<?> embeddings,
            @JsonProperty("tokenUsage") @Nullable EmbeddingTokenUsage tokenUsage) {
        if (embeddings == null) {
            throw new IllegalArgumentException("EmbeddingResult requires an embeddings array");
        }
        // With non-empty vectors, [] unambiguously represents an empty batch.
        if (embeddings.isEmpty() || embeddings.get(0) instanceof List) {
            return new EmbeddingResult<>(
                    embeddings.stream()
                            .map(vector -> EmbeddingModelUtils.toFloatArray((List<?>) vector))
                            .collect(Collectors.toList()),
                    tokenUsage);
        }
        return new EmbeddingResult<>(EmbeddingModelUtils.toFloatArray(embeddings), tokenUsage);
    }
}
