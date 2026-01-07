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
 * Unless required by applicable law or agreed in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.agents.api.embedding.model.python;

import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelConnection;
import org.apache.flink.agents.api.embedding.model.EmbeddingModelUtils;
import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.api.resource.python.PythonResourceWrapper;
import pemja.core.object.PyObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.apache.flink.util.Preconditions.checkState;

/**
 * Python-based implementation of EmbeddingModelConnection that bridges Java and Python embedding
 * model functionality. This class wraps a Python embedding model connection object and provides
 * Java interface compatibility while delegating actual embed operations to the underlying Python
 * implementation.
 */
public class PythonEmbeddingModelConnection extends BaseEmbeddingModelConnection
        implements PythonResourceWrapper {

    private final PyObject embeddingModel;
    private final PythonResourceAdapter adapter;

    /**
     * Creates a new PythonEmbeddingModelConnection.
     *
     * @param adapter The Python resource adapter (required by PythonResourceProvider's
     *     reflection-based instantiation but not used directly in this implementation)
     * @param embeddingModel The Python embedding model object
     * @param descriptor The resource descriptor
     * @param getResource Function to retrieve resources by name and type
     */
    public PythonEmbeddingModelConnection(
            PythonResourceAdapter adapter,
            PyObject embeddingModel,
            ResourceDescriptor descriptor,
            BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
        this.embeddingModel = embeddingModel;
        this.adapter = adapter;
    }

    @Override
    public float[] embed(String text, Map<String, Object> parameters) {
        checkState(
                embeddingModel != null,
                "EmbeddingModelSetup is not initialized. Cannot perform embed operation.");

        Map<String, Object> kwargs = new HashMap<>(parameters);
        kwargs.put("text", text);

        Object result = adapter.callMethod(embeddingModel, "embed", kwargs);

        // Convert to float arrays
        if (result instanceof List) {
            List<?> list = (List<?>) result;
            return EmbeddingModelUtils.toFloatArray(list);
        }

        throw new IllegalArgumentException(
                "Expected List from Python embed method, but got: "
                        + (result == null ? "null" : result.getClass().getName()));
    }

    @Override
    public List<float[]> embed(List<String> texts, Map<String, Object> parameters) {
        checkState(
                embeddingModel != null,
                "EmbeddingModelSetup is not initialized. Cannot perform embed operation.");

        Map<String, Object> kwargs = new HashMap<>(parameters);
        kwargs.put("text", texts);

        Object results = adapter.callMethod(embeddingModel, "embed", kwargs);

        if (results instanceof List) {
            List<?> list = (List<?>) results;
            List<float[]> embeddings = new ArrayList<>();

            for (Object element : list) {
                if (element instanceof List) {
                    List<?> listElement = (List<?>) element;
                    embeddings.add(EmbeddingModelUtils.toFloatArray(listElement));
                } else {
                    throw new IllegalArgumentException(
                            "Expected List value in embedding results, but got: "
                                    + element.getClass().getName());
                }
            }
            return embeddings;
        }

        throw new IllegalArgumentException(
                "Expected List from Python embed method, but got: "
                        + (results == null ? "null" : results.getClass().getName()));
    }

    @Override
    public Object getPythonResource() {
        return embeddingModel;
    }
}
