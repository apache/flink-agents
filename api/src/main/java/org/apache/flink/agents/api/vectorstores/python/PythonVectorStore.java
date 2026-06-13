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

import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.python.PythonResourceAdapter;
import org.apache.flink.agents.api.resource.python.PythonResourceWrapper;
import org.apache.flink.agents.api.vectorstores.BaseVectorStore;
import org.apache.flink.agents.api.vectorstores.Document;
import pemja.core.object.PyObject;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Python-based implementation of VectorStore that bridges Java and Python vector store
 * functionality. This class wraps a Python vector store object and provides Java interface
 * compatibility while delegating actual storage and retrieval operations to the underlying Python
 * implementation.
 *
 * <p>This class serves as a connection layer between Java and Python vector store environments,
 * enabling seamless integration of Python-based vector stores within Java applications.
 *
 * <p>Embedding is generated on the Java side via {@link BaseVectorStore}'s public add/update/query;
 * the {@code *Embedding} hooks then forward the pre-computed vectors to the Python {@code
 * _add_embedding}/{@code _update_embedding}/{@code _query_embedding}. This keeps each store
 * operation a single Java->Python crossing, avoiding a Python->Java re-entry that deadlocks when
 * run on the async pool thread.
 */
public class PythonVectorStore extends BaseVectorStore implements PythonResourceWrapper {
    protected final PyObject vectorStore;
    protected final PythonResourceAdapter adapter;

    /**
     * Creates a new PythonVectorStore.
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
            ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.vectorStore = vectorStore;
        this.adapter = adapter;
    }

    @Override
    public void open() throws Exception {
        // Resolve the Java-side embedding model so embeddings are generated on the mailbox thread
        // (single Java->Python crossing per op). Without this, add/query would re-embed inside
        // Python and re-enter Java, which deadlocks when run on the async pool thread.
        super.open();
        adapter.callMethod(vectorStore, "open", Collections.emptyMap());
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Document> get(
            @Nullable List<String> ids,
            @Nullable String collection,
            @Nullable Map<String, Object> filters,
            @Nullable Integer limit,
            Map<String, Object> extraArgs)
            throws IOException {
        Map<String, Object> kwargs = new HashMap<>(extraArgs);
        if (ids != null && !ids.isEmpty()) {
            kwargs.put("ids", ids);
        }
        if (collection != null) {
            kwargs.put("collection_name", collection);
        }
        if (filters != null) {
            kwargs.put("filters", filters);
        }
        if (limit != null) {
            kwargs.put("limit", limit);
        }

        Object pythonDocuments = adapter.callMethod(vectorStore, "get", kwargs);

        return adapter.fromPythonDocuments((List<PyObject>) pythonDocuments);
    }

    @Override
    public void delete(
            @Nullable List<String> ids,
            @Nullable String collection,
            @Nullable Map<String, Object> filters,
            Map<String, Object> extraArgs)
            throws IOException {
        Map<String, Object> kwargs = new HashMap<>(extraArgs);
        if (ids != null && !ids.isEmpty()) {
            kwargs.put("ids", ids);
        }
        if (collection != null) {
            kwargs.put("collection_name", collection);
        }
        if (filters != null) {
            kwargs.put("filters", filters);
        }
        adapter.callMethod(vectorStore, "delete", kwargs);
    }

    @Override
    public Map<String, Object> getStoreKwargs() {
        return new HashMap<>();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Document> queryEmbedding(
            float[] embedding,
            int limit,
            @Nullable String collection,
            @Nullable Map<String, Object> filters,
            Map<String, Object> args) {
        Map<String, Object> kwargs = new HashMap<>(args);
        // pemja maps float[] to a Python tuple, which Chroma rejects; pass a list instead.
        List<Float> embeddingList = new ArrayList<>(embedding.length);
        for (float v : embedding) {
            embeddingList.add(v);
        }
        kwargs.put("embedding", embeddingList);
        kwargs.put("limit", limit);
        if (collection != null) {
            kwargs.put("collection_name", collection);
        }
        if (filters != null) {
            kwargs.put("filters", filters);
        }
        Object pythonDocuments = adapter.callMethod(vectorStore, "_query_embedding", kwargs);
        return adapter.fromPythonDocuments((List<PyObject>) pythonDocuments);
    }

    /** Embed query text via the configured model (no numpy, so it stays on the async pool). */
    public float[] embedQuery(String text) {
        return getEmbeddingModel().embed(text);
    }

    /**
     * Convert the raw embedding to the Python store's native vector form. This runs the numpy
     * conversion on the mailbox thread: numpy releases/re-acquires the GIL during the copy, and
     * pemja keeps a single PyThreadState, so doing it on an async worker thread can stall the
     * interpreter (observed as a hang in CI; benign with spare cores locally). The returned Python
     * object is forwarded back into {@link #queryNormalized}. See
     * https://github.com/apache/flink-agents/issues/844.
     */
    public Object normalizeEmbedding(float[] embedding) {
        List<Float> embeddingList = new ArrayList<>(embedding.length);
        for (float v : embedding) {
            embeddingList.add(v);
        }
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("embeddings", embeddingList);
        return adapter.callMethod(vectorStore, "_normalize_embeddings", kwargs);
    }

    /** Query with a pre-normalized embedding; numpy-free, so it stays on the async pool. */
    @SuppressWarnings("unchecked")
    public List<Document> queryNormalized(
            Object normalizedEmbedding,
            int limit,
            @Nullable String collection,
            @Nullable Map<String, Object> filters,
            Map<String, Object> args) {
        Map<String, Object> kwargs = new HashMap<>(args);
        kwargs.put("embedding", normalizedEmbedding);
        kwargs.put("limit", limit);
        if (collection != null) {
            kwargs.put("collection_name", collection);
        }
        if (filters != null) {
            kwargs.put("filters", filters);
        }
        Object pythonDocuments = adapter.callMethod(vectorStore, "_query_embedding", kwargs);
        return adapter.fromPythonDocuments((List<PyObject>) pythonDocuments);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> addEmbedding(
            List<Document> documents, @Nullable String collection, Map<String, Object> extraArgs)
            throws IOException {
        Map<String, Object> kwargs = new HashMap<>(extraArgs);
        kwargs.put("documents", adapter.toPythonDocuments(documents));
        if (collection != null) {
            kwargs.put("collection_name", collection);
        }
        return (List<String>) adapter.callMethod(vectorStore, "_add_embedding", kwargs);
    }

    @Override
    public void updateEmbedding(
            List<Document> documents, @Nullable String collection, Map<String, Object> extraArgs) {
        Map<String, Object> kwargs = new HashMap<>(extraArgs);
        kwargs.put("documents", adapter.toPythonDocuments(documents));
        if (collection != null) {
            kwargs.put("collection_name", collection);
        }
        adapter.callMethod(vectorStore, "_update_embedding", kwargs);
    }

    @Override
    public Object getPythonResource() {
        return vectorStore;
    }
}
