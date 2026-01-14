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

package org.apache.flink.agents.api.vectorstores.python;

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.api.resource.python.PythonResourceWrapper;
import org.apache.flink.agents.api.vectorstores.BaseVectorStore;
import org.apache.flink.agents.api.vectorstores.Document;
import org.apache.flink.agents.api.vectorstores.VectorStoreQuery;
import org.apache.flink.agents.api.vectorstores.VectorStoreQueryResult;
import pemja.core.object.PyObject;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Python-based implementation of VectorStore that bridges Java and Python vector store
 * functionality. This class wraps a Python vector store object and provides Java interface
 * compatibility while delegating actual storage and retrieval operations to the underlying Python
 * implementation.
 *
 * <p>This class serves as a connection layer between Java and Python vector store environments,
 * enabling seamless integration of Python-based vector stores within Java applications.
 */
public class PythonVectorStore extends BaseVectorStore implements PythonResourceWrapper {
    protected final PyObject vectorStore;
    protected final PythonResourceAdapter adapter;

    /**
     * Creates a new PythonEmbeddingModelConnection.
     *
     * @param adapter The Python resource adapter (required by PythonResourceProvider's
     *     reflection-based instantiation but not used directly in this implementation)
     * @param vectorStore The Python vector store object
     * @param descriptor The resource descriptor
     * @param getResource Function to retrieve resources by name and type
     */
    public PythonVectorStore(
            PythonResourceAdapter adapter,
            PyObject vectorStore,
            ResourceDescriptor descriptor,
            BiFunction<String, ResourceType, Resource> getResource) {
        super(descriptor, getResource);
        this.vectorStore = vectorStore;
        this.adapter = adapter;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> add(
            List<Document> documents, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException {
        Object pythonDocuments = adapter.toPythonDocuments(documents);

        Map<String, Object> kwargs = new HashMap<>(extraArgs);
        kwargs.put("documents", pythonDocuments);

        if (collection != null) {
            kwargs.put("collection", collection);
        }

        return (List<String>) adapter.callMethod(vectorStore, "add", kwargs);
    }

    @Override
    public VectorStoreQueryResult query(VectorStoreQuery query) {
        Object pythonQuery = adapter.toPythonVectorStoreQuery(query);

        PyObject pythonResult = (PyObject) vectorStore.invokeMethod("query", pythonQuery);

        return adapter.fromPythonVectorStoreQueryResult(pythonResult);
    }

    @Override
    public long size(@Nullable String collection) throws Exception {
        return (long) vectorStore.invokeMethod("size", collection);
    }

    @Override
    public List<Document> get(
            @Nullable List<String> ids, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException {
        Map<String, Object> kwargs = new HashMap<>(extraArgs);
        if (ids != null && !ids.isEmpty()) {
            kwargs.put("ids", ids);
        }
        if (collection != null) {
            kwargs.put("collection", collection);
        }

        Object pythonDocuments = adapter.callMethod(vectorStore, "get", kwargs);

        return adapter.fromPythonDocuments((List<PyObject>) pythonDocuments);
    }

    @Override
    public void delete(
            @Nullable List<String> ids, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException {
        Map<String, Object> kwargs = new HashMap<>(extraArgs);
        if (ids != null && !ids.isEmpty()) {
            kwargs.put("ids", ids);
        }
        if (collection != null) {
            kwargs.put("collection", collection);
        }
        adapter.callMethod(vectorStore, "delete", kwargs);
    }

    @Override
    public Map<String, Object> getStoreKwargs() {
        return Map.of();
    }

    @Override
    protected List<Document> queryEmbedding(
            float[] embedding, int limit, @Nullable String collection, Map<String, Object> args) {
        return List.of();
    }

    @Override
    protected List<String> addEmbedding(
            List<Document> documents, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException {
        return List.of();
    }

    @Override
    public Object getPythonResource() {
        return vectorStore;
    }
}
