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

import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.embedding.model.BaseEmbeddingModelSetup;
import org.apache.flink.agents.api.embedding.model.EmbeddingResult;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import pemja.core.object.PyObject;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Python-based implementation of EmbeddingModelSetup that bridges Java and Python embedding model
 * setup functionality. This class wraps a Python embedding model setup object and provides Java
 * interface compatibility while delegating actual embed operations to the underlying Python
 * implementation.
 */
public class PythonEmbeddingModelSetup extends BaseEmbeddingModelSetup
        implements PythonResourceWrapper {
    private final PyObject embeddingModelSetup;
    private final PythonResourceAdapter adapter;
    private boolean closed;

    /**
     * Creates a new PythonEmbeddingModelSetup.
     *
     * @param adapter The Python resource adapter (required by PythonResourceProvider's
     *     reflection-based instantiation but not used directly in this implementation)
     * @param embeddingModelSetup The Python embedding model object
     * @param descriptor The resource descriptor
     * @param getResource Function to retrieve resources by name and type
     */
    public PythonEmbeddingModelSetup(
            PythonResourceAdapter adapter,
            PyObject embeddingModelSetup,
            ResourceDescriptor descriptor,
            ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.embeddingModelSetup = embeddingModelSetup;
        this.adapter = adapter;
    }

    @Override
    public void open() {
        this.adapter.callMethod(embeddingModelSetup, "open", Collections.emptyMap());
        if (model == null) {
            model =
                    (String)
                            adapter.invoke("python_java_utils.get_model_name", embeddingModelSetup);
        }
    }

    @Override
    public float[] embed(String text, Map<String, Object> parameters) {
        return PythonEmbeddingModelInvoker.embedSingle(this, text, parameters);
    }

    @Override
    public List<float[]> embed(List<String> texts, Map<String, Object> parameters) {
        return PythonEmbeddingModelInvoker.embedBatch(this, texts, parameters);
    }

    @Override
    public EmbeddingResult<float[]> embedWithUsage(String text, Map<String, Object> parameters) {
        return PythonEmbeddingModelInvoker.embedSingleWithUsage(this, text, parameters);
    }

    @Override
    public EmbeddingResult<List<float[]>> embedWithUsage(
            List<String> texts, Map<String, Object> parameters) {
        return PythonEmbeddingModelInvoker.embedBatchWithUsage(this, texts, parameters);
    }

    @Override
    public EmbeddingResult<List<float[]>> embedBlocksWithUsage(
            List<? extends DataContentBlock> blocks, Map<String, Object> parameters) {
        return PythonEmbeddingModelInvoker.embedBatchWithUsage(this, blocks, parameters);
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of();
    }

    @Override
    public Object getPythonResource() {
        return embeddingModelSetup;
    }

    @Override
    public PythonResourceAdapter getPythonResourceAdapter() {
        return adapter;
    }

    @Override
    public void setMetricGroup(FlinkAgentsMetricGroup metricGroup) {
        super.setMetricGroup(metricGroup);
        setPythonResourceMetricGroup(metricGroup);
    }

    @Override
    public void close() throws Exception {
        if (closed || embeddingModelSetup == null) {
            return;
        }
        closed = true;
        try (embeddingModelSetup) {
            adapter.callMethod(embeddingModelSetup, "close", Map.of());
        }
    }
}
