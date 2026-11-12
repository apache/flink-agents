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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.apache.flink.util.Preconditions.checkState;

/** Invokes a Python embedding resource with Java-safe content and results. */
final class PythonEmbeddingModelInvoker {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CALL_EMBEDDING = "python_java_utils.call_embedding";

    private PythonEmbeddingModelInvoker() {}

    static float[] embedSingle(
            PythonResourceWrapper model, Object content, Map<String, Object> parameters) {
        return EmbeddingModelUtils.toFloatArray(
                requireList(
                        invoke(model, "embed", content, parameters),
                        "Expected List from Python embed method"));
    }

    static List<float[]> embedBatch(
            PythonResourceWrapper model, List<?> content, Map<String, Object> parameters) {
        List<?> result =
                requireList(
                        invoke(model, "embed", content, parameters),
                        "Expected List from Python embed method");
        List<float[]> embeddings = new ArrayList<>();
        for (Object vector : result) {
            embeddings.add(
                    EmbeddingModelUtils.toFloatArray(
                            requireList(vector, "Expected List value in embedding results")));
        }
        return embeddings;
    }

    static EmbeddingResult<float[]> embedSingleWithUsage(
            PythonResourceWrapper model, Object content, Map<String, Object> parameters) {
        return EmbeddingModelUtils.toSingleEmbeddingResult(
                invoke(model, "embed_with_usage", content, parameters));
    }

    static EmbeddingResult<List<float[]>> embedBatchWithUsage(
            PythonResourceWrapper model, List<?> content, Map<String, Object> parameters) {
        return EmbeddingModelUtils.toBatchEmbeddingResult(
                invoke(model, "embed_with_usage", content, parameters));
    }

    private static Object invoke(
            PythonResourceWrapper model,
            String method,
            Object content,
            Map<String, Object> parameters) {
        Object pythonModel = model.getPythonResource();
        checkState(
                pythonModel != null,
                "EmbeddingModelSetup is not initialized. Cannot perform embed operation.");
        return model.getPythonResourceAdapter()
                .invoke(
                        CALL_EMBEDDING,
                        pythonModel,
                        method,
                        MAPPER.convertValue(content, Object.class),
                        parameters);
    }

    private static List<?> requireList(Object result, String message) {
        if (result instanceof List) {
            return (List<?>) result;
        }
        throw new IllegalArgumentException(
                message + ", but got: " + (result == null ? "null" : result.getClass().getName()));
    }
}
