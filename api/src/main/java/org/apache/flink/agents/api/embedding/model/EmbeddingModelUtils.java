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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EmbeddingModelUtils {
    public static float[] toFloatArray(List list) {
        float[] array = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object element = list.get(i);
            if (element instanceof Number) {
                array[i] = ((Number) element).floatValue();
            } else {
                throw new IllegalArgumentException(
                        "Expected numeric value in embedding result, but got: "
                                + element.getClass().getName());
            }
        }
        return array;
    }

    public static EmbeddingResult<float[]> toSingleEmbeddingResult(Object result) {
        Map<?, ?> values = toResultMap(result);
        return new EmbeddingResult<>(
                toFloatArray(toEmbeddingList(values.get("embeddings"))),
                toTokenUsage(values.get("token_usage")));
    }

    public static EmbeddingResult<List<float[]>> toBatchEmbeddingResult(Object result) {
        Map<?, ?> values = toResultMap(result);
        List<?> rawEmbeddings = toEmbeddingList(values.get("embeddings"));
        List<float[]> embeddings = new ArrayList<>();
        for (Object embedding : rawEmbeddings) {
            embeddings.add(toFloatArray(toEmbeddingList(embedding)));
        }
        return new EmbeddingResult<>(embeddings, toTokenUsage(values.get("token_usage")));
    }

    private static Map<?, ?> toResultMap(Object result) {
        if (result instanceof Map) {
            return (Map<?, ?>) result;
        }
        throw new IllegalArgumentException(
                "Expected Map from Python embed_with_usage method, but got: "
                        + (result == null ? "null" : result.getClass().getName()));
    }

    private static List<?> toEmbeddingList(Object embeddings) {
        if (embeddings instanceof List) {
            return (List<?>) embeddings;
        }
        throw new IllegalArgumentException(
                "Expected List value in Python embedding result, but got: "
                        + (embeddings == null ? "null" : embeddings.getClass().getName()));
    }

    private static EmbeddingTokenUsage toTokenUsage(Object tokenUsage) {
        if (tokenUsage == null) {
            return null;
        }
        if (!(tokenUsage instanceof Map)) {
            throw new IllegalArgumentException(
                    "Expected Map token_usage in Python embedding result, but got: "
                            + tokenUsage.getClass().getName());
        }

        Map<?, ?> usage = (Map<?, ?>) tokenUsage;
        return new EmbeddingTokenUsage(
                toLong(usage.get("prompt_tokens"), "prompt_tokens"),
                toLong(usage.get("total_tokens"), "total_tokens"));
    }

    private static long toLong(Object value, String fieldName) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        throw new IllegalArgumentException(
                "Expected numeric "
                        + fieldName
                        + " in Python embedding token usage, but got: "
                        + (value == null ? "null" : value.getClass().getName()));
    }
}
