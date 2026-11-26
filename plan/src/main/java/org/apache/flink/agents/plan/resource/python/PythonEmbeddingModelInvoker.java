/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.agents.plan.resource.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.embedding.model.EmbeddingModelUtils;
import org.apache.flink.agents.api.embedding.model.EmbeddingResult;
import org.apache.flink.agents.api.embedding.model.EmbeddingTokenUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.apache.flink.util.Preconditions.checkState;

/** Invokes a Python embedding resource with Java-safe content and results. */
final class PythonEmbeddingModelInvoker {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CALL_EMBEDDING = "python_java_utils.call_embedding";

    private PythonEmbeddingModelInvoker() {}

    static EmbeddingResult<List<float[]>> embedBatch(
            PythonResourceWrapper model, List<?> content, Map<String, Object> parameters) {
        return toBatchEmbeddingResult(invoke(model, content, parameters));
    }

    private static EmbeddingResult<List<float[]>> toBatchEmbeddingResult(Object result) {
        Map<?, ?> values = toResultMap(result);
        List<?> rawEmbeddings = toEmbeddingList(values.get("embeddings"));
        List<float[]> embeddings = new ArrayList<>();
        for (Object embedding : rawEmbeddings) {
            embeddings.add(EmbeddingModelUtils.toFloatArray(toEmbeddingList(embedding)));
        }
        return new EmbeddingResult<>(embeddings, toTokenUsage(values.get("token_usage")));
    }

    private static Map<?, ?> toResultMap(Object result) {
        if (result instanceof Map) {
            return (Map<?, ?>) result;
        }
        throw new IllegalArgumentException(
                "Expected Map from Python embed method, but got: "
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

    private static Object invoke(
            PythonResourceWrapper model, Object content, Map<String, Object> parameters) {
        Object pythonModel = model.getPythonResource();
        checkState(
                pythonModel != null,
                "EmbeddingModelSetup is not initialized. Cannot perform embed operation.");
        return model.getPythonResourceAdapter()
                .invoke(
                        CALL_EMBEDDING,
                        pythonModel,
                        MAPPER.convertValue(content, Object.class),
                        parameters);
    }
}
